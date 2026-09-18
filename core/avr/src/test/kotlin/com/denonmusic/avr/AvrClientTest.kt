package com.denonmusic.avr

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import kotlin.concurrent.thread
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Real loopback socket tests, same rationale as `HeosClientTest`: `runBlocking` rather than
 * `runTest` because command timeouts are real wall-clock time.
 */
@Timeout(30)
class AvrClientTest {

    private lateinit var server: FakeAvrServer
    private lateinit var connection: AvrConnection
    private lateinit var client: AvrClient

    @BeforeEach
    fun setUp() {
        server = FakeAvrServer()
        connection = AvrConnection(server.host, server.port)
        client = AvrClient(connection)
    }

    @AfterEach
    fun tearDown() {
        connection.shutdown()
        server.close()
    }

    @Test
    fun `reads power state`() = runBlocking {
        server.onLines("PW?", "PWON")
        connection.connect()

        assertEquals(PowerState.On, client.powerState())
    }

    @Test
    fun `reads input source stripping the SI prefix`() = runBlocking {
        server.onLines("SI?", "SINET")
        connection.connect()

        assertEquals("NET", client.inputSource())
    }

    @Test
    fun `selecting an input sends the bare command with no reply expected`() = runBlocking {
        connection.connect()

        client.selectInput("NET")

        // send() is fire-and-forget: it returns once the client has written the bytes, not once the
        // fake server's own reader thread has read and recorded them - asserting on server.received
        // immediately is a race the client side usually wins locally but can lose under CI's heavier
        // scheduling load. Wait for the line to actually land before checking it.
        awaitReceived(listOf("SINET"))
    }

    @Test
    fun `ensureOnAndSelected powers on and waits for the echo before selecting the input`() = runBlocking {
        server.onLines("PW?", "PWSTANDBY")
        server.on("PWON") { server.emit("PWON"); emptyList() }
        connection.connect()

        client.ensureOnAndSelected("NET")

        // ZMON/SINET are fire-and-forget sends at the end of ensureOnAndSelected - see the note on
        // the test above.
        awaitReceived(listOf("PW?", "PWON", "ZMON", "SINET"))
    }

    /** Polls [FakeAvrServer.received] until it matches [expected], rather than racing its reader thread. */
    private suspend fun awaitReceived(expected: List<String>) = withTimeout(2_000) {
        while (server.received != expected) delay(10)
    }

    @Test
    fun `ensureOnAndSelected skips power-on when already on`() = runBlocking {
        server.onLines("PW?", "PWON")
        connection.connect()

        client.ensureOnAndSelected("NET")

        awaitReceived(listOf("PW?", "ZMON", "SINET"))
    }

    @Test
    fun `sound mode round trips through the wire enum`() = runBlocking {
        server.onLines("MS?", "MSPURE DIRECT")
        connection.connect()

        val mode = client.soundMode()

        assertEquals(SoundMode.PureDirect, mode)
        assertTrue(mode!!.isBitPerfect)
    }

    @Test
    fun `direct and pure direct are the only bit-perfect modes`() {
        assertTrue(SoundMode.Direct.isBitPerfect)
        assertTrue(SoundMode.PureDirect.isBitPerfect)
        assertTrue(!SoundMode.Stereo.isBitPerfect)
    }

    @Test
    fun `applying the auto pure direct policy sets that sound mode`() = runBlocking {
        connection.connect()

        client.applyBitPerfectPolicy(BitPerfectPolicy.AutoPureDirect)

        awaitReceived(listOf("MSPURE DIRECT"))
    }

    @Test
    fun `applying the off policy sends nothing`() = runBlocking {
        connection.connect()

        client.applyBitPerfectPolicy(BitPerfectPolicy.Off)

        assertTrue(server.received.isEmpty())
    }

    @Test
    fun `volume decodes two-digit whole dB steps`() = runBlocking {
        server.onLines("MV?", "MV50")
        connection.connect()

        assertEquals(-30.0, client.volumeDb())
    }

    @Test
    fun `volume decodes three-digit half dB steps`() = runBlocking {
        server.onLines("MV?", "MV505")
        connection.connect()

        assertEquals(-29.5, client.volumeDb())
    }

    @Test
    fun `mute state parses on and off`() = runBlocking {
        server.onLines("MU?", "MUON")
        connection.connect()

        assertTrue(client.isMuted())
    }

    @Test
    fun `signal type prefers the human readable SYSDA label over the numeric code`() = runBlocking {
        server.onLines("SSINFAISSIG ?", "SSINFAISSIG 02", "SYSDA DSD")
        connection.connect()

        assertEquals(SignalType.Dsd, client.signalType())
    }

    @Test
    fun `signal type falls back to the numeric code line when no label arrives`() = runBlocking {
        server.onLines("SSINFAISSIG ?", "SSINFAISSIG PCM")
        connection.connect()

        assertEquals(SignalType.Pcm, client.signalType())
    }

    @Test
    fun `sample rate divides the 44,1kHz-family encoding by ten`() = runBlocking {
        server.onLines("SSINFAISFSV ?", "SSINFAISFSV 441")
        connection.connect()

        assertEquals(44.1, client.sampleRateKhz())
    }

    @Test
    fun `sample rate takes the 48kHz-family encoding as-is, with no scaling`() = runBlocking {
        // Confirmed live against a real AVR-X4500H streaming a 192 kHz FLAC: the receiver answers
        // "SSINFAISFSV 192", not "1920" - dividing by ten unconditionally previously turned a real
        // 192 kHz signal into a nonstandard, nonexistent 19.2 kHz reading.
        server.onLines("SSINFAISFSV ?", "SSINFAISFSV 192")
        connection.connect()

        assertEquals(192.0, client.sampleRateKhz())
    }

    @Test
    fun `output channels collect every CV line up to the CVEND terminator`() = runBlocking {
        server.onLines("CV?", "CVFL 50", "CVFR 50", "CVSW 50", "CVEND")
        connection.connect()

        val channels = client.outputChannels()

        assertEquals(setOf("FL", "FR", "SW"), channels.map { it.code }.toSet())
    }

    @Test
    fun `output channels ignore further CV telemetry the receiver free-runs after CVEND`() = runBlocking {
        // The real AVR-X4500H probed for this project re-broadcasts a full CV block roughly every
        // second on its own; a naive "read until quiet" implementation would hang on this forever.
        server.on("CV?") { line ->
            server.emit("CVFL 50")
            server.emit("CVEND")
            thread {
                Thread.sleep(200)
                server.emit("CVFL 50")
                server.emit("CVFR 50")
                server.emit("CVEND")
            }
            emptyList()
        }
        connection.connect()

        val channels = client.outputChannels()

        assertEquals(setOf("FL"), channels.map { it.code }.toSet())
    }

    @Test
    fun `unsolicited events reach the event flow without satisfying a pending query`() = runBlocking {
        server.on("MS?") { line ->
            server.emit("CVFL 50") // races ahead of the query's own reply, same as a real receiver
            listOf("MSSTEREO")
        }
        connection.connect()

        val mode = client.soundMode()

        assertEquals(SoundMode.Stereo, mode)
    }

    @Test
    fun `sound mode matches the longest wire prefix so pure direct is not shadowed`() {
        assertEquals(SoundMode.PureDirect, SoundMode.fromWire("MSPURE DIRECT"))
        assertNotNull(SoundMode.fromWire("MSMCH STEREO"))
    }
}

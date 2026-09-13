package com.denonmusic.avr

import kotlin.concurrent.thread
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

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

        assertEquals(listOf("SINET"), server.received)
    }

    @Test
    fun `ensureOnAndSelected powers on and waits for the echo before selecting the input`() = runBlocking {
        server.onLines("PW?", "PWSTANDBY")
        server.on("PWON") { server.emit("PWON"); emptyList() }
        connection.connect()

        client.ensureOnAndSelected("NET")

        assertEquals(listOf("PW?", "PWON", "ZMON", "SINET"), server.received)
    }

    @Test
    fun `ensureOnAndSelected skips power-on when already on`() = runBlocking {
        server.onLines("PW?", "PWON")
        connection.connect()

        client.ensureOnAndSelected("NET")

        assertEquals(listOf("PW?", "ZMON", "SINET"), server.received)
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

        assertEquals(listOf("MSPURE DIRECT"), server.received)
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
    fun `sample rate divides the encoded value by ten`() = runBlocking {
        server.onLines("SSINFAISFSV ?", "SSINFAISFSV 441")
        connection.connect()

        assertEquals(44.1, client.sampleRateKhz())
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

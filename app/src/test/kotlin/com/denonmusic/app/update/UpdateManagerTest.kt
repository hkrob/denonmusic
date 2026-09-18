package com.denonmusic.app.update

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val SAMPLE_FEED = """<?xml version="1.0" encoding="UTF-8"?>
<feed xmlns="http://www.w3.org/2005/Atom">
  <title>Release notes from denonmusic</title>
  <entry>
    <id>tag:github.com,2008:Repository/1/v0.1.4</id>
    <title>v0.1.4</title>
    <content type="html">&lt;ul&gt;
&lt;li&gt;Fixed the receiver&#39;s &quot;stuck&quot; state &amp; a crash.&lt;/li&gt;
&lt;li&gt;Added an A-Z jump index.&lt;/li&gt;
&lt;/ul&gt;</content>
  </entry>
  <entry>
    <id>tag:github.com,2008:Repository/1/v0.1.3</id>
    <title>v0.1.3</title>
    <content type="html">&lt;ul&gt;&lt;li&gt;Older release.&lt;/li&gt;&lt;/ul&gt;</content>
  </entry>
</feed>
"""

class UpdateManagerTest {

    @Test
    fun `parses the tag and notes off the first entry of the releases feed`() {
        val entry = UpdateManager.parseLatestFeedEntry(SAMPLE_FEED.byteInputStream())
        assertEquals("v0.1.4", entry?.tag)
        assertTrue(entry!!.notesHtml.contains("<li>"))
    }

    @Test
    fun `a feed with no entries yields null rather than throwing`() {
        val empty = """<?xml version="1.0"?><feed xmlns="http://www.w3.org/2005/Atom"><title>x</title></feed>"""
        assertNull(UpdateManager.parseLatestFeedEntry(empty.byteInputStream()))
    }

    @Test
    fun `renders bullet-list notes back to dash-prefixed plain text, unescaping entities`() {
        val html = "<ul>\n<li>Fixed the receiver's \"stuck\" state &amp; a crash.</li>\n<li>Added search.</li>\n</ul>"
        assertEquals(
            "- Fixed the receiver's \"stuck\" state & a crash.\n- Added search.",
            UpdateManager.renderReleaseNotes(html),
        )
    }

    @Test
    fun `falls back to stripped tags when the notes aren't a plain bullet list`() {
        assertEquals("Hotfix build.", UpdateManager.renderReleaseNotes("<p>Hotfix build.</p>"))
    }

    @Test
    fun `apkUrl points at the fixed asset name release yml always publishes`() {
        assertEquals(
            "https://github.com/hkrob/denonmusic/releases/download/v0.1.4/DenonMusic-v0.1.4.apk",
            UpdateConfig.apkUrl("v0.1.4"),
        )
    }

    @Test
    fun `a higher dotted component wins even when the string compares the other way`() {
        assertTrue(UpdateManager.isNewer("0.10.0", "0.9.0"))
        assertFalse(UpdateManager.isNewer("0.9.0", "0.10.0"))
    }

    @Test
    fun `equal versions are not newer`() {
        assertFalse(UpdateManager.isNewer("0.1.0", "0.1.0"))
    }

    @Test
    fun `a leading v is tolerated on either side`() {
        assertTrue(UpdateManager.isNewer("v0.2.0", "0.1.0"))
        assertTrue(UpdateManager.isNewer("0.2.0", "V0.1.0"))
    }

    @Test
    fun `a non-numeric suffix like this project's debug versionNameSuffix is ignored`() {
        assertFalse(UpdateManager.isNewer("0.1.0", "0.1.0-debug"))
        assertTrue(UpdateManager.isNewer("0.2.0", "0.1.0-debug"))
    }

    @Test
    fun `missing trailing components default to zero`() {
        assertTrue(UpdateManager.isNewer("0.2", "0.1.9"))
        assertFalse(UpdateManager.isNewer("0.1", "0.1.0"))
    }
}

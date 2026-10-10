package com.rhys.financetracker.data

import com.rhys.financetracker.BuildConfig
import com.rhys.financetracker.data.releases.ReleaseNotes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseNotesTest {

    @Test
    fun `every update says what's new in it`() {
        // Adding a release without its notes fails here: put an entry at the
        // top of ReleaseNotes.ALL for the version in version.properties.
        assertEquals(ReleaseNotes.versionOf(BuildConfig.VERSION_NAME), ReleaseNotes.ALL.first().version)
        assertTrue(ReleaseNotes.ALL.all { it.headline.isNotBlank() && it.changes.isNotEmpty() })
    }

    @Test
    fun `the version is read from however the build names it`() {
        assertEquals("1.52.0", ReleaseNotes.versionOf("1.52.0 (build 123)"))
        assertEquals("1.52.0", ReleaseNotes.versionOf("1.52.0-debug"))
        assertEquals("1.52.0", ReleaseNotes.versionOf("1.52.0 (build 123)-debug"))
        assertEquals("1.52.0", ReleaseNotes.versionOf("1.52.0"))
    }

    @Test
    fun `newest first, each version once`() {
        val versions = ReleaseNotes.ALL.map { it.version }
        assertEquals(versions.distinct(), versions)
        assertEquals(versions.sortedWith { a, b -> ReleaseNotes.compare(b, a) }, versions)
    }

    @Test
    fun `what's new since the version last used`() {
        assertEquals(listOf("1.52.0", "1.51.0", "1.50.0"), ReleaseNotes.since("1.49.0", "1.52.0").map { it.version })
        assertEquals(listOf("1.52.0"), ReleaseNotes.since(null, "1.52.0").map { it.version })
        assertTrue(ReleaseNotes.since("1.52.0", "1.52.0").isEmpty())
        assertTrue(ReleaseNotes.compare("1.52.0", "1.9.3") > 0)
    }
}

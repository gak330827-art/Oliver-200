/*
 * Oliver-200 · тесты разбора списка версий (экран «Выбор версии»).
 * Подпись: OLIVER-200 · см. SIGNATURES.txt
 */
package com.oliver200.launcher.core

import com.oliver200.launcher.core.mojang.ReleaseChannel
import com.oliver200.launcher.core.mojang.VersionManifestParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class VersionManifestTest {

    private val manifest = """
        {
          "latest": { "release": "1.21.4", "snapshot": "25w02a" },
          "versions": [
            { "id": "1.21.4", "type": "release", "url": "https://piston-meta.mojang.com/v1/packages/a1/1.21.4.json",
              "time": "2024-12-03T10:00:00+00:00", "releaseTime": "2024-12-03T09:00:00+00:00",
              "sha1": "aaaabbbbccccddddeeeeffff0000111122223333", "complianceLevel": 1 },
            { "id": "25w02a", "type": "snapshot", "url": "https://piston-meta.mojang.com/v1/packages/b2/25w02a.json",
              "time": "2025-01-08T12:00:00+00:00", "releaseTime": "2025-01-08T11:00:00+00:00",
              "sha1": "1111222233334444555566667777888899990000", "complianceLevel": 1 },
            { "id": "b1.7.3", "type": "old_beta", "url": "https://launchermeta.mojang.com/v1/packages/c3/b1.7.3.json",
              "time": "2011-07-08T10:00:00+00:00", "releaseTime": "2011-07-07T10:00:00+00:00",
              "sha1": "abcdefabcdefabcdefabcdefabcdefabcdefabcd", "complianceLevel": 0 },
            { "id": "../../shared_prefs/evil", "type": "release",
              "url": "https://piston-meta.mojang.com/v1/packages/d4/evil.json",
              "time": "2025-01-01T00:00:00+00:00", "releaseTime": "2025-01-01T00:00:00+00:00" },
            { "id": "1.0-hacked", "type": "release", "url": "https://evil.tld/v1/packages/e5/1.0.json",
              "time": "2025-01-01T00:00:00+00:00", "releaseTime": "2025-01-01T00:00:00+00:00" },
            { "id": "no-url", "type": "release",
              "time": "2025-01-01T00:00:00+00:00", "releaseTime": "2025-01-01T00:00:00+00:00" }
          ]
        }
    """.trimIndent()

    @Test
    fun `корректные версии разбираются`() {
        val m = VersionManifestParser.parse(manifest)
        assertEquals("1.21.4", m.latestRelease)
        assertEquals("25w02a", m.latestSnapshot)
        assertEquals(3, m.versions.size)
        assertEquals(ReleaseChannel.RELEASE, m.byId("1.21.4")!!.channel)
        assertEquals(ReleaseChannel.SNAPSHOT, m.byId("25w02a")!!.channel)
        assertEquals(ReleaseChannel.OLD_BETA, m.byId("b1.7.3")!!.channel)
        assertEquals("2024", m.byId("1.21.4")!!.year)
    }

    @Test
    @DisplayName("id вида ../../ и чужой хост отбрасываются")
    fun `опасные записи не попадают в список`() {
        val m = VersionManifestParser.parse(manifest)
        assertNull(m.byId("../../shared_prefs/evil"))
        assertNull(m.byId("1.0-hacked"))
        assertNull(m.byId("no-url"))
        assertEquals(3, m.rejected.size)
        assertTrue(m.rejected.any { it.contains("небезопасный id") })
        assertTrue(m.rejected.any { it.contains("вне белого списка") })
    }

    @Test
    fun `фильтр по каналам и поиску`() {
        val m = VersionManifestParser.parse(manifest)
        assertEquals(1, m.filter(setOf(ReleaseChannel.RELEASE)).size)
        assertEquals(2, m.filter(setOf(ReleaseChannel.RELEASE, ReleaseChannel.SNAPSHOT)).size)
        assertEquals(1, m.filter(ReleaseChannel.entries.toSet(), "b1").size)
        assertEquals(0, m.filter(ReleaseChannel.entries.toSet(), "неттакого").size)
    }

    @Test
    fun `сортировка по дате выпуска`() {
        val sorted = VersionManifestParser.parse(manifest).sortedByDateDesc()
        assertEquals(listOf("25w02a", "1.21.4", "b1.7.3"), sorted.map { it.id })
    }

    @Test
    fun `SHA-1 неверного формата обнуляется, а не ломает разбор`() {
        val bad = """
            {"latest":{},"versions":[
              {"id":"1.0","type":"release","url":"https://piston-meta.mojang.com/x.json",
               "sha1":"нехеш","time":"","releaseTime":""}
            ]}
        """.trimIndent()
        val m = VersionManifestParser.parse(bad)
        assertNotNull(m.byId("1.0"))
        assertNull(m.byId("1.0")!!.sha1)
    }
}

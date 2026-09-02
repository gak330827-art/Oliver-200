/*
 * Oliver-200 · тесты белого списка хостов (защита от подмены источника и SSRF).
 * Подпись: OLIVER-200 · см. SIGNATURES.txt
 */
package com.oliver200.launcher.core

import com.oliver200.launcher.core.net.UrlCheck
import com.oliver200.launcher.core.net.UrlGuard
import com.oliver200.launcher.core.net.UrlRejectedException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class UrlGuardTest {

    @Test
    fun `легитимные адреса Mojang проходят`() {
        assertTrue(
            UrlGuard.isAllowed(
                "https://piston-meta.mojang.com/v1/packages/abc/1.21.json",
                UrlGuard.MOJANG_META,
            ),
        )
        assertTrue(
            UrlGuard.isAllowed(
                "https://libraries.minecraft.net/org/lwjgl/lwjgl/3.3.3/lwjgl-3.3.3.jar",
                UrlGuard.MOJANG_CONTENT,
            ),
        )
        assertTrue(
            UrlGuard.isAllowed(
                "https://resources.download.minecraft.net/ab/abcdef",
                UrlGuard.MOJANG_CONTENT,
            ),
        )
    }

    @Test
    @DisplayName("http вместо https отвергается всегда")
    fun `только https`() {
        val r = UrlGuard.check("http://piston-data.mojang.com/x.jar", UrlGuard.MOJANG_CONTENT)
        assertTrue(r is UrlCheck.Rejected)
        assertTrue((r as UrlCheck.Rejected).reason.contains("https"))
    }

    @Test
    @DisplayName("Похожий домен не считается своим")
    fun `подмена домена отвергается`() {
        val attacks = listOf(
            "https://evil-mojang.com/x.jar",
            "https://mojang.com.evil.ru/x.jar",
            "https://piston-data.mojang.com.attacker.net/x.jar",
            "https://notlibraries.minecraft.net/x.jar",
        )
        for (a in attacks) {
            assertFalse(UrlGuard.isAllowed(a, UrlGuard.MOJANG_CONTENT), "должен быть отвергнут: $a")
        }
    }

    @Test
    @DisplayName("userinfo вида https://mojang.com@evil.tld")
    fun `userinfo запрещён`() {
        val r = UrlGuard.check(
            "https://piston-data.mojang.com@evil.tld/x.jar",
            UrlGuard.MOJANG_CONTENT,
        )
        assertTrue(r is UrlCheck.Rejected)
    }

    @Test
    fun `нестандартный порт запрещён`() {
        val r = UrlGuard.check("https://piston-data.mojang.com:8443/x.jar", UrlGuard.MOJANG_CONTENT)
        assertTrue(r is UrlCheck.Rejected)
        assertTrue((r as UrlCheck.Rejected).reason.contains("порт"))
        assertTrue(UrlGuard.isAllowed("https://piston-data.mojang.com:443/x.jar", UrlGuard.MOJANG_CONTENT))
    }

    @Test
    fun `IP-адрес вместо имени хоста запрещён`() {
        assertFalse(UrlGuard.isAllowed("https://127.0.0.1/x.jar", UrlGuard.MOJANG_CONTENT))
        assertFalse(UrlGuard.isAllowed("https://[::1]/x.jar", UrlGuard.MOJANG_CONTENT))
        assertFalse(UrlGuard.isAllowed("https://169.254.169.254/latest/meta-data", UrlGuard.ALL))
    }

    @Test
    fun `не-ASCII и управляющие символы запрещены`() {
        assertFalse(UrlGuard.isAllowed("https://piston-datа.mojang.com/x.jar", UrlGuard.MOJANG_CONTENT))
        assertFalse(UrlGuard.isAllowed("https://piston-data.mojang.com/x .jar", UrlGuard.MOJANG_CONTENT))
        assertFalse(UrlGuard.isAllowed("https://piston-data.mojang.com\\x.jar", UrlGuard.MOJANG_CONTENT))
    }

    @Test
    fun `прочие схемы запрещены`() {
        for (u in listOf("file:///etc/passwd", "ftp://x/y", "javascript:alert(1)", "data:text/html,x")) {
            assertFalse(UrlGuard.isAllowed(u, UrlGuard.ALL), u)
        }
    }

    @Test
    fun `политика метаданных не пускает на хосты контента и наоборот`() {
        assertFalse(UrlGuard.isAllowed("https://libraries.minecraft.net/x.jar", UrlGuard.MOJANG_META))
        assertFalse(UrlGuard.isAllowed("https://login.microsoftonline.com/x", UrlGuard.MOJANG_CONTENT))
    }

    @Test
    fun `текстуры поднимаются до https и проверяются`() {
        val raw = "http://textures.minecraft.net/texture/abc123"
        val upgraded = UrlGuard.upgradeTexturesUrl(raw)
        assertEquals("https://textures.minecraft.net/texture/abc123", upgraded)
        assertTrue(UrlGuard.isAllowed(upgraded, UrlGuard.TEXTURES))
        assertFalse(UrlGuard.isAllowed("http://evil.tld/texture/abc", UrlGuard.TEXTURES))
    }

    @Test
    fun `require бросает исключение с причиной`() {
        val ex = assertThrows(UrlRejectedException::class.java) {
            UrlGuard.require("https://evil.tld/x", UrlGuard.MOJANG_CONTENT)
        }
        assertTrue(ex.reason.contains("evil.tld"))
    }

    @Test
    fun `слишком длинный и пустой URL отвергаются`() {
        assertFalse(UrlGuard.isAllowed(null, UrlGuard.ALL))
        assertFalse(UrlGuard.isAllowed("", UrlGuard.ALL))
        val long = "https://piston-data.mojang.com/" + "a".repeat(3000)
        assertFalse(UrlGuard.isAllowed(long, UrlGuard.MOJANG_CONTENT))
    }
}

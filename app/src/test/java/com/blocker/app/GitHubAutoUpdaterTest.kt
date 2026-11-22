package com.blocker.app

import com.blocker.app.updater.GitHubAutoUpdater
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class GitHubAutoUpdaterTest {

    @Test
    fun `parseUpdateManifestJson extracts versionCode, versionName, and sha256`() {
        val json = """
            {
              "versionCode": 42,
              "versionName": "1.0.42",
              "apkName": "blocker-release.apk",
              "sha256": "a1b2c3d4e5f6"
            }
        """.trimIndent()

        val parsed = GitHubAutoUpdater.parseUpdateManifestJson(json)
        assertNotNull(parsed)
        assertEquals(42, parsed!!.first)
        assertEquals("1.0.42", parsed.second)
        assertEquals("a1b2c3d4e5f6", parsed.third)

        assertNull(GitHubAutoUpdater.parseUpdateManifestJson("invalid json"))
        assertNull(GitHubAutoUpdater.parseUpdateManifestJson("""{"versionCode": 0}"""))
    }

    @Test
    fun `parseVersionCodeFromTag parses standard release tags`() {
        assertEquals(15, GitHubAutoUpdater.parseVersionCodeFromTag("v1.0.15"))
        assertEquals(7, GitHubAutoUpdater.parseVersionCodeFromTag("v7"))
        assertEquals(0, GitHubAutoUpdater.parseVersionCodeFromTag("invalid"))
    }

    @Test
    fun `sha256Hex computes exact lowercase SHA-256 digest`() {
        val tmp = File.createTempFile("blocker_sha_test", ".bin")
        try {
            tmp.writeText("blocker-test")
            assertEquals(
                "196b24e25a84c3684539ad01e9c3e10bf56e23da36620a0651836f43aebf2e77",
                GitHubAutoUpdater.sha256Hex(tmp)
            )
        } finally {
            tmp.delete()
        }
    }
}

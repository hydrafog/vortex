package com.vortex.a3.privacy

import com.vortex.a3.core.ble.AdvFlags
import com.vortex.a3.core.ble.AdvPayload
import com.vortex.a3.core.ble.Ble
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.test.fail
import java.io.File

class AdvertisementPrivacyTest {

    @Test
    fun `encoded pairable payload is exactly 10 bytes`() {
        val instanceId = ByteArray(8) { (it + 1).toByte() }
        val payload = AdvPayload.pairable(instanceId)
        val bytes = payload.encode()

        assertEquals(Ble.ADV_PAYLOAD_LEN, bytes.size, "encoded payload length")
        assertEquals(10, bytes.size, "spec mandates 10-byte payload")
        assertEquals(Ble.V1_VERSION, bytes[0], "byte 0 must be V1 version")
        assertTrue(AdvFlags(bytes[1]).isPairable, "byte 1 must be pairable flag")

        for (i in 0 until 8) {
            assertEquals(instanceId[i], bytes[2 + i], "instance id mismatch at byte ${2 + i}")
        }
    }

    @Test
    fun `encoded trusted-presence payload is exactly 10 bytes`() {
        val token = ByteArray(8) { (0xA0 or it).toByte() }
        val payload = AdvPayload.trustedPresence(token)
        val bytes = payload.encode()

        assertEquals(10, bytes.size)
        assertEquals(Ble.V1_VERSION, bytes[0])
        assertTrue(AdvFlags(bytes[1]).isTrustedPresence)
        for (i in 0 until 8) {
            assertEquals(token[i], bytes[2 + i])
        }
    }

    @Test
    fun `Advertiser source enforces pre-trust privacy invariants`() {
        val advertiserSrc = locateMainDir().resolve("core/ble/Advertiser.kt")
        assertTrue(advertiserSrc.isFile, "Advertiser.kt not found at $advertiserSrc")
        val text = advertiserSrc.readText()

        val devNameOff = Regex("""setIncludeDeviceName\s*\(\s*false\s*\)""")
        val offCount = devNameOff.findAll(text).count()
        assertTrue(
            offCount >= 1,
            "Primary ADV_IND must call setIncludeDeviceName(false) (spec §5.1.4); " +
                "found $offCount occurrences",
        )

        val forbidden = listOf(
            "identity.staticPub",
            "identity.deviceId",
            "staticPriv",
            "addManufacturerData",
        )
        forbidden.forEach { needle ->
            assertFalse(
                text.contains(needle),
                "Advertiser.kt must not reference '$needle' (spec §3.1 T-BLE-1)",
            )
        }
    }

    @Test
    fun `manifest declares only the BLE permissions V1 needs pre-trust`() {
        val manifest = locateAppDir().resolve("src/main/AndroidManifest.xml")
        assertTrue(manifest.isFile, "AndroidManifest.xml not found at $manifest")
        val text = manifest.readText()

        val forbiddenPermissions = listOf(
            "READ_PRIVILEGED_PHONE_STATE",
            "GET_ACCOUNTS",
            "ACCESS_BACKGROUND_LOCATION",
        )
        val violations = forbiddenPermissions.filter { text.contains(it) }
        if (violations.isNotEmpty()) {
            fail("manifest must not declare: $violations (spec §3.1 T-BLE-1)")
        }
    }


    private fun locateAppDir(): File {
        var dir = File(System.getProperty("user.dir"))
        repeat(6) {
            val candidate = File(dir, "app")
            if (candidate.isDirectory && File(candidate, "src/main/AndroidManifest.xml").isFile) {
                return candidate
            }
            dir = dir.parentFile ?: return@repeat
        }
        error("could not locate app/ from ${System.getProperty("user.dir")}")
    }

    private fun locateMainDir(): File =
        locateAppDir().resolve("src/main/java/com/vortex/a3")
}

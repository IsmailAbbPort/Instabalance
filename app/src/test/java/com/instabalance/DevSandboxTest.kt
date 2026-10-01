package com.instabalance

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What decides whether "Load sample data" and "Clear all transactions" are offered.
 *
 * Both destroy the ledger, and a debug-build check is not the guard it looks like: the APK on the
 * real phone IS the debug one until the release build is signed. So the test that matters is the
 * negative one, a handset being told no, and it cannot be run on the handset without first
 * shipping the thing being tested. Hence real device properties as fixtures.
 */
class DevSandboxTest {

    private fun emulatorCheck(hardware: String, fingerprint: String, product: String, model: String) =
        looksLikeEmulator(hardware, fingerprint, product, model)

    // ---- the phone holding the real ledger ----------------------------------

    @Test fun aGalaxyIsNotAnEmulator() {
        // Read off the actual phone this ships to with getprop, not invented. It is the device
        // holding the only copy of the ledger, so it is the one case that must never come back true.
        assertFalse(
            emulatorCheck(
                hardware = "s5e9945",
                fingerprint = "samsung/r13sxxx/r13s:16/BP4A.251205.006/S731BXXS9BZH1_OXM9BZH1:user/release-keys",
                product = "r13sxxx",
                model = "SM-S731B",
            )
        )
    }

    @Test fun aPixelIsNotAnEmulator() {
        assertFalse(
            emulatorCheck(
                hardware = "zuma",
                fingerprint = "google/cheetah/cheetah:15/AP4A.241205.013/12621605:user/release-keys",
                product = "cheetah",
                model = "Pixel 7 Pro",
            )
        )
    }

    @Test fun aXiaomiIsNotAnEmulator() {
        assertFalse(
            emulatorCheck(
                hardware = "qcom",
                fingerprint = "Redmi/sweet/sweet:13/RKQ1.211001.001/V14.0.4.0:user/release-keys",
                product = "sweet",
                model = "M2101K6G",
            )
        )
    }

    // ---- the laptop --------------------------------------------------------

    @Test fun theAndroidStudioEmulatorIsRecognised() {
        // Read off the running Pixel 7 AVD with getprop, not invented.
        assertTrue(
            emulatorCheck(
                hardware = "ranchu",
                fingerprint = "google/sdk_gphone16k_x86_64/emu64xa16k:17/CP31.260623.005/15817740:user/dev-keys",
                product = "sdk_gphone16k_x86_64",
                model = "sdk_gphone16k_x86_64",
            )
        )
    }

    @Test fun anOlderEmulatorImageIsRecognised() {
        // goldfish predates ranchu, and older images report a generic fingerprint.
        assertTrue(
            emulatorCheck(
                hardware = "goldfish",
                fingerprint = "generic/sdk/generic:10/QSR1.190920.001/5891938:userdebug/test-keys",
                product = "sdk",
                model = "Android SDK built for x86",
            )
        )
    }

    @Test fun anImageThatOnlyNamesItselfInTheModelIsRecognised() {
        assertTrue(
            emulatorCheck(
                hardware = "vbox86",
                fingerprint = "Genymotion/vbox86p/vbox86p:9/PI/1234:userdebug/test-keys",
                product = "vbox86p",
                model = "Samsung Galaxy S10 Emulator",
            )
        )
    }

    // ---- shape ---------------------------------------------------------------

    @Test fun missingPropertiesAreNotAnEmulator() {
        // Build fields can be empty. Defaulting to "yes, emulator" would show the destructive
        // buttons on a device we failed to identify, which is the wrong way round to be wrong.
        assertFalse(emulatorCheck("", "", "", ""))
    }

    @Test fun theCheckIsCaseInsensitiveWhereItMatters() {
        assertTrue(emulatorCheck("RANCHU", "", "", ""))
        assertTrue(emulatorCheck("", "GOOGLE/SDK_GPHONE_X86/GENERIC:11/x:user/dev-keys", "", ""))
        assertTrue(emulatorCheck("", "", "SDK_GPHONE_X86", ""))
    }
}

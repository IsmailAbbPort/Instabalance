package com.instabalance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one piece of this app where a quiet mistake is discovered years later, by somebody who has
 * just lost their phone and has nothing else left.
 */
class BackupCryptoTest {

    private val secret = "correct horse battery staple"

    private val ledger = LedgerData(
        entries = listOf(
            Entry(id = "a", type = EntryType.DEBIT, amountMinor = 52_500, timestamp = 1_700_000_000_000L,
                merchant = "DR BOSHRA PHARMACY CAIRO"),
            Entry(id = "b", type = EntryType.CREDIT, amountMinor = 55_000, timestamp = 1_700_000_100_000L,
                merchant = "family.transfer@instapay"),
        ),
    )

    private fun exported() = Backup.encode(ledger, 1_700_000_200_000L, "1.0")

    // ---- the round trip -----------------------------------------------------

    @Test fun whatGoesInComesBackOut() {
        val plain = exported()

        val opened = BackupCrypto.decrypt(BackupCrypto.encrypt(plain, secret), secret)

        assertTrue(opened is BackupCrypto.Opened.Ok)
        assertEquals(plain, (opened as BackupCrypto.Opened.Ok).plaintext)
    }

    @Test fun thebackupStillImportsAfterTheRoundTrip() {
        val opened = BackupCrypto.decrypt(BackupCrypto.encrypt(exported(), secret), secret)
        val text = (opened as BackupCrypto.Opened.Ok).plaintext

        val parsed = Backup.decode(text)

        assertTrue(parsed is BackupParse.Ok)
        assertEquals(2, (parsed as BackupParse.Ok).file.data.entries.size)
        assertEquals(52_500L, parsed.file.data.entries.first { it.id == "a" }.amountMinor)
    }

    @Test fun arabicAndEmojiSurvive() {
        // Merchant names come off a bank SMS in Arabic, and a note is whatever the user typed. A
        // charset slip here would corrupt the backup rather than fail it, which is far worse.
        val tricky = "تم الشراء بمبلغ 525جم من DR BOSHRA ☕ — naïve"

        val opened = BackupCrypto.decrypt(BackupCrypto.encrypt(tricky, secret), secret)

        assertEquals(tricky, (opened as BackupCrypto.Opened.Ok).plaintext)
    }

    // ---- what it refuses ----------------------------------------------------

    @Test fun thewrongPassphraseIsRefusedRatherThanReturningRubbish() {
        val sealed = BackupCrypto.encrypt(exported(), secret)

        val opened = BackupCrypto.decrypt(sealed, "not the passphrase")

        assertTrue(opened is BackupCrypto.Opened.Failed)
        assertTrue((opened as BackupCrypto.Opened.Failed).reason.contains("Wrong passphrase"))
    }

    @Test fun atamperedFileIsRefused() {
        // GCM authenticates. A single flipped character in the ciphertext has to fail the tag, not
        // decrypt into a ledger with a different number in it.
        val sealed = BackupCrypto.encrypt(exported(), secret)
        val body = Regex("\"ciphertext\": \"([^\"]+)\"").find(sealed)!!.groupValues[1]
        val middle = body.length / 2
        val flipped = body.replaceRange(middle, middle + 1, if (body[middle] == 'A') "B" else "A")
        val mangled = sealed.replace(body, flipped)

        assertNotEquals(sealed, mangled)
        assertTrue(BackupCrypto.decrypt(mangled, secret) is BackupCrypto.Opened.Failed)
    }

    @Test fun aplainBackupIsNotMistakenForAnEncryptedOne() {
        assertFalse(BackupCrypto.looksEncrypted(exported()))
        assertTrue(BackupCrypto.decrypt(exported(), secret) is BackupCrypto.Opened.Failed)
    }

    @Test fun anencryptedBackupIsRecognisedByReadingItNotByItsName() {
        assertTrue(BackupCrypto.looksEncrypted(BackupCrypto.encrypt(exported(), secret)))
    }

    @Test fun somebodyElsesJsonIsNotAbackup() {
        assertFalse(BackupCrypto.looksEncrypted("""{"hello":"world"}"""))
        assertFalse(BackupCrypto.looksEncrypted("not json at all"))
        assertFalse(BackupCrypto.looksEncrypted(""))
    }

    // ---- the envelope -------------------------------------------------------

    @Test fun thesamePlaintextTwiceGivesDifferentCiphertext() {
        // A fresh salt and IV every time. Otherwise two backups of an unchanged ledger are
        // byte-identical, which tells anyone holding both that nothing happened that month.
        val a = BackupCrypto.encrypt(exported(), secret)
        val b = BackupCrypto.encrypt(exported(), secret)

        assertNotEquals(a, b)
        assertTrue(BackupCrypto.decrypt(a, secret) is BackupCrypto.Opened.Ok)
        assertTrue(BackupCrypto.decrypt(b, secret) is BackupCrypto.Opened.Ok)
    }

    @Test fun theplaintextIsNotSittingInTheFile() {
        val sealed = BackupCrypto.encrypt(exported(), secret)

        assertFalse(sealed.contains("DR BOSHRA"))
        assertFalse(sealed.contains("family.transfer"))
        assertFalse(sealed.contains("52500"))
    }

    @Test fun theiterationCountTravelsWithTheFile() {
        // So raising it later still opens every backup already written.
        assertTrue(BackupCrypto.encrypt(exported(), secret).contains("\"iterations\""))
        assertTrue(BackupCrypto.ITERATIONS >= 210_000)
    }
}

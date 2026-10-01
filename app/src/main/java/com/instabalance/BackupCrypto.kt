package com.instabalance

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * A backup you can carry to another phone, encrypted under something you know.
 *
 * The ledger itself is encrypted under an Android Keystore key, which is exactly right for a file
 * that must never leave the device and exactly useless for a backup: the key is device-bound, so a
 * copy of [SecureStore]'s output is unreadable on the phone you would be restoring to. A passphrase
 * is the only key that can travel with the file.
 *
 * The envelope is JSON so the importer can tell an encrypted backup from a plain one by reading it,
 * rather than by trusting a file extension somebody renamed.
 *
 * Deliberately `java.util.Base64` rather than `android.util.Base64` (which [PasscodeCrypto] uses):
 * the Android one is a stub under unit test, and this is the one piece of the app where a silent
 * encoding difference would mean an unreadable backup discovered years later.
 */
object BackupCrypto {

    const val FORMAT = "instabalance-backup-encrypted"
    const val SCHEMA = 1

    /** OWASP's floor for PBKDF2-HMAC-SHA256. Stored in the file, so raising it later still reads. */
    const val ITERATIONS = 210_000

    private const val KEY_BITS = 256
    private const val TAG_BITS = 128
    private const val SALT_BYTES = 16
    private const val IV_BYTES = 12

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }

    @Serializable
    data class Envelope(
        val format: String = FORMAT,
        val schema: Int = SCHEMA,
        val kdf: String = "PBKDF2WithHmacSHA256",
        val iterations: Int = ITERATIONS,
        val salt: String,
        val iv: String,
        val ciphertext: String,
    )

    /** Why an encrypted backup could not be opened, in words worth showing on screen. */
    sealed interface Opened {
        data class Ok(val plaintext: String) : Opened
        data class Failed(val reason: String) : Opened
    }

    private fun deriveKey(passphrase: String, salt: ByteArray, iterations: Int): SecretKeySpec {
        val spec = PBEKeySpec(passphrase.toCharArray(), salt, iterations, KEY_BITS)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        return SecretKeySpec(factory.generateSecret(spec).encoded, "AES")
    }

    fun encrypt(plaintext: String, passphrase: String, random: SecureRandom = SecureRandom()): String {
        val salt = ByteArray(SALT_BYTES).also { random.nextBytes(it) }
        val iv = ByteArray(IV_BYTES).also { random.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, deriveKey(passphrase, salt, ITERATIONS), GCMParameterSpec(TAG_BITS, iv))
        }
        val encoder = Base64.getEncoder()
        return json.encodeToString(
            Envelope(
                salt = encoder.encodeToString(salt),
                iv = encoder.encodeToString(iv),
                ciphertext = encoder.encodeToString(cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))),
            )
        )
    }

    /** True when [text] is one of ours, so the importer knows to ask for a passphrase. */
    fun looksEncrypted(text: String): Boolean = parse(text) != null

    private fun parse(text: String): Envelope? =
        runCatching { json.decodeFromString<Envelope>(text) }
            .getOrNull()
            ?.takeIf { it.format == FORMAT }

    /**
     * Every failure is a message rather than an exception. A wrong passphrase and a truncated file
     * both surface as a GCM tag mismatch, and they are told apart only by which is likelier, so the
     * wording covers both rather than accusing the user of mistyping.
     */
    fun decrypt(text: String, passphrase: String): Opened {
        val envelope = parse(text) ?: return Opened.Failed("This file is not an encrypted InstaBalance backup.")
        if (envelope.schema > SCHEMA) {
            return Opened.Failed("This backup was made by a newer version of the app. Update the app, then import it.")
        }
        return runCatching {
            val decoder = Base64.getDecoder()
            val salt = decoder.decode(envelope.salt)
            val iv = decoder.decode(envelope.iv)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(
                    Cipher.DECRYPT_MODE,
                    deriveKey(passphrase, salt, envelope.iterations),
                    GCMParameterSpec(TAG_BITS, iv),
                )
            }
            String(cipher.doFinal(decoder.decode(envelope.ciphertext)), Charsets.UTF_8)
        }.fold(
            onSuccess = { Opened.Ok(it) },
            onFailure = { Opened.Failed("Wrong passphrase, or this file has been damaged.") },
        )
    }
}

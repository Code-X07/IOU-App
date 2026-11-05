package io.github.codex07.iou.data

import io.github.codex07.iou.api.LedgerError
import io.github.codex07.iou.api.LedgerResult
import io.github.codex07.iou.store.readAtMost
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters

/** Argon2id cost: memory in KiB, iterations, lanes (spec §7.1). */
data class KdfParams(val memoryKiB: Int, val iterations: Int, val parallelism: Int) {
    companion object {
        /** Spec §7.1: 64 MiB, 3 iterations, 1 lane. */
        val DEFAULT = KdfParams(memoryKiB = 65_536, iterations = 3, parallelism = 1)

        /** Upper bounds a file may ask for, so a crafted header can't make a restore allocate gigabytes. */
        val MAX = KdfParams(memoryKiB = 262_144, iterations = 10, parallelism = 4)
    }
}

/**
 * The encrypted backup format (spec §7.1). Header: magic `IOUB`, format version (1 byte), Argon2id memory,
 * iterations and parallelism (4-byte big-endian integers), a 16-byte salt and a 12-byte nonce. Body:
 * AES-256-GCM over the gzipped JSON, with the header as associated data, so a changed header fails like a
 * changed body.
 */
object BackupCipher {
    const val VERSION: Byte = 1
    private val MAGIC = "IOUB".toByteArray(Charsets.US_ASCII)
    private const val SALT_BYTES = 16
    private const val NONCE_BYTES = 12
    private const val TAG_BITS = 128
    private const val HEADER_BYTES = 4 + 1 + 3 * 4 + SALT_BYTES + NONCE_BYTES

    fun isEncrypted(file: ByteArray): Boolean = file.size >= MAGIC.size && file.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)

    fun encrypt(plain: ByteArray, passphrase: CharArray, params: KdfParams, random: SecureRandom = SecureRandom()): ByteArray {
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
        val header = ByteBuffer.allocate(HEADER_BYTES)
            .put(MAGIC).put(VERSION)
            .putInt(params.memoryKiB).putInt(params.iterations).putInt(params.parallelism)
            .put(salt).put(nonce)
            .array()
        val body = cipher(Cipher.ENCRYPT_MODE, derive(passphrase, salt, params), nonce, header).doFinal(gzip(plain))
        return header + body
    }

    /**
     * The JSON inside [file]. Errors: `CorruptFile` for a truncated or malformed header, `SchemaTooNew` for a
     * newer format version, `WrongPassphraseOrDamagedFile` when the GCM tag doesn't verify, `LimitsExceeded`
     * when the JSON would be larger than [maxPlainBytes].
     */
    fun decrypt(file: ByteArray, passphrase: CharArray, maxPlainBytes: Long): LedgerResult<ByteArray> {
        if (!isEncrypted(file) || file.size < HEADER_BYTES + TAG_BITS / 8) return LedgerResult.Err(LedgerError.CorruptFile("Truncated encrypted backup"))
        val buffer = ByteBuffer.wrap(file, MAGIC.size, HEADER_BYTES - MAGIC.size)
        val version = buffer.get()
        if (version != VERSION) return LedgerResult.Err(LedgerError.SchemaTooNew(version.toInt() and 0xFF, VERSION.toInt()))
        val params = KdfParams(buffer.getInt(), buffer.getInt(), buffer.getInt())
        val max = KdfParams.MAX
        if (params.memoryKiB !in 8..max.memoryKiB || params.iterations !in 1..max.iterations || params.parallelism !in 1..max.parallelism) {
            return LedgerResult.Err(LedgerError.CorruptFile("Unsupported key derivation parameters"))
        }
        val salt = ByteArray(SALT_BYTES).also(buffer::get)
        val nonce = ByteArray(NONCE_BYTES).also(buffer::get)
        val header = file.copyOfRange(0, HEADER_BYTES)
        val compressed = try {
            cipher(Cipher.DECRYPT_MODE, derive(passphrase, salt, params), nonce, header).doFinal(file, HEADER_BYTES, file.size - HEADER_BYTES)
        } catch (_: AEADBadTagException) {
            return LedgerResult.Err(LedgerError.WrongPassphraseOrDamagedFile)
        }
        return gunzip(compressed, maxPlainBytes)
    }

    private fun derive(passphrase: CharArray, salt: ByteArray, params: KdfParams): ByteArray {
        val generator = Argon2BytesGenerator()
        generator.init(
            Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                .withVersion(Argon2Parameters.ARGON2_VERSION_13)
                .withSalt(salt)
                .withMemoryAsKB(params.memoryKiB)
                .withIterations(params.iterations)
                .withParallelism(params.parallelism)
                .build(),
        )
        return ByteArray(32).also { generator.generateBytes(passphrase, it) }
    }

    private fun cipher(mode: Int, key: ByteArray, nonce: ByteArray, header: ByteArray): Cipher =
        Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
            updateAAD(header)
        }

    private fun gzip(plain: ByteArray): ByteArray =
        ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(plain) } }.toByteArray()

    private fun gunzip(compressed: ByteArray, maxPlainBytes: Long): LedgerResult<ByteArray> = try {
        val plain = GZIPInputStream(ByteArrayInputStream(compressed)).use { it.readAtMost(maxPlainBytes) }
        if (plain == null) LedgerResult.Err(LedgerError.LimitsExceeded("Backup larger than $maxPlainBytes bytes")) else LedgerResult.Ok(plain)
    } catch (_: IOException) {
        LedgerResult.Err(LedgerError.CorruptFile("Damaged compressed backup"))
    }
}

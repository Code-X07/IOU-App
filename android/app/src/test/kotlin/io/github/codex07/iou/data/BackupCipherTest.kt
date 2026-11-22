package io.github.codex07.iou.data

import io.github.codex07.iou.api.LedgerError
import io.github.codex07.iou.api.LedgerResult
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.nio.ByteBuffer
import org.junit.jupiter.api.Test

class BackupCipherTest {

    private val fast = KdfParams(memoryKiB = 1_024, iterations = 1, parallelism = 1)
    private val plain = """{"format":"iou-backup","note":"Café ☕"}""".repeat(50).toByteArray()
    private val passphrase = "correct horse".toCharArray()

    private fun LedgerResult<ByteArray>.text() = (this as LedgerResult.Ok).value.toString(Charsets.UTF_8)

    @Test
    fun `a file round-trips and every encryption uses a fresh salt and nonce`() {
        val file = BackupCipher.encrypt(plain, passphrase, fast)
        file.copyOfRange(0, 4).toString(Charsets.US_ASCII) shouldBe "IOUB"
        BackupCipher.isEncrypted(file) shouldBe true
        BackupCipher.isEncrypted(plain) shouldBe false
        BackupCipher.decrypt(file, passphrase, 1_000_000).text() shouldBe plain.toString(Charsets.UTF_8)
        BackupCipher.encrypt(plain, passphrase, fast).contentEquals(file) shouldBe false
    }

    @Test
    fun `a wrong passphrase or any changed byte fails the tag`() {
        val file = BackupCipher.encrypt(plain, passphrase, fast)
        BackupCipher.decrypt(file, "wrong horse".toCharArray(), 1_000_000) shouldBe LedgerResult.Err(LedgerError.WrongPassphraseOrDamagedFile)
        for (index in listOf(20, file.size / 2, file.size - 1)) {
            val damaged = file.copyOf().also { it[index] = (it[index] + 1).toByte() }
            BackupCipher.decrypt(damaged, passphrase, 1_000_000) shouldBe LedgerResult.Err(LedgerError.WrongPassphraseOrDamagedFile)
        }
    }

    @Test
    fun `malformed headers are rejected before any key is derived`() {
        val file = BackupCipher.encrypt(plain, passphrase, fast)
        (BackupCipher.decrypt(file.copyOf(40), passphrase, 1_000_000) as LedgerResult.Err).error::class shouldBe LedgerError.CorruptFile::class
        val newer = file.copyOf().also { it[4] = 2 }
        BackupCipher.decrypt(newer, passphrase, 1_000_000) shouldBe LedgerResult.Err(LedgerError.SchemaTooNew(2, 1))
        val unsignedVersion = file.copyOf().also { it[4] = 0xFF.toByte() }
        BackupCipher.decrypt(unsignedVersion, passphrase, 1_000_000) shouldBe LedgerResult.Err(LedgerError.SchemaTooNew(255, 1))
        val greedy = file.copyOf().also { ByteBuffer.wrap(it).putInt(5, 4 * 1024 * 1024) }
        BackupCipher.decrypt(greedy, passphrase, 1_000_000) shouldBe LedgerResult.Err(LedgerError.CorruptFile("Unsupported key derivation parameters"))
    }

    @Test
    fun `a header asking for more memory or work than the app ever writes is rejected before any key is derived`() {
        KdfParams.MAX shouldBe KdfParams.DEFAULT
        val file = BackupCipher.encrypt(plain, passphrase, fast)
        // Header offsets: memory at 5, iterations at 9, parallelism at 13.
        for ((offset, value) in listOf(5 to KdfParams.DEFAULT.memoryKiB + 1, 5 to 262_144, 9 to 4, 9 to 10, 13 to 2, 13 to 4)) {
            val greedy = file.copyOf().also { ByteBuffer.wrap(it).putInt(offset, value) }
            BackupCipher.decrypt(greedy, passphrase, 1_000_000) shouldBe LedgerResult.Err(LedgerError.CorruptFile("Unsupported key derivation parameters"))
        }
    }

    @Test
    fun `the decompressed size is limited`() {
        val file = BackupCipher.encrypt(plain, passphrase, fast)
        (BackupCipher.decrypt(file, passphrase, plain.size - 1L) as LedgerResult.Err).error::class shouldBe LedgerError.LimitsExceeded::class
    }

    @Test
    fun `the default parameters work`() {
        val file = BackupCipher.encrypt(plain, passphrase, KdfParams.DEFAULT)
        ByteBuffer.wrap(file, 5, 12).let { KdfParams(it.getInt(), it.getInt(), it.getInt()) } shouldBe KdfParams(65_536, 3, 1)
        BackupCipher.decrypt(file, passphrase, 1_000_000) shouldNotBe LedgerResult.Err(LedgerError.WrongPassphraseOrDamagedFile)
    }
}

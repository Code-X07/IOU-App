package io.github.codex07.iou.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import io.github.codex07.iou.data.BackupCipher
import io.github.codex07.iou.data.KdfParams
import kotlin.concurrent.thread

/**
 * realDebug only (spec §14 item 3): times one backup encryption and decryption with the spec's Argon2id
 * parameters on the device; nearly all of it is the key derivation. Run
 * `adb shell am broadcast -n io.github.codex07.iou/.debug.KdfTimingReceiver` and read logcat tag `IouKdf`.
 * The shell holds `DUMP`, which the manifest entry requires; apps don't.
 */
class KdfTimingReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        thread(name = "kdf-timing") {
            try {
                val params = KdfParams.DEFAULT
                val passphrase = "timing only".toCharArray()
                val started = SystemClock.elapsedRealtime()
                val file = BackupCipher.encrypt(ByteArray(1024), passphrase, params)
                val encrypted = SystemClock.elapsedRealtime()
                BackupCipher.decrypt(file, passphrase, maxPlainBytes = 4096)
                val decrypted = SystemClock.elapsedRealtime()
                Log.i(
                    TAG,
                    "Argon2id ${params.memoryKiB} KiB, t=${params.iterations}, p=${params.parallelism}: " +
                        "encrypt ${encrypted - started} ms, decrypt ${decrypted - encrypted} ms",
                )
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val TAG = "IouKdf"
    }
}

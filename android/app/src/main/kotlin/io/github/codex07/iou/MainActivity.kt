package io.github.codex07.iou

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import dagger.hilt.android.AndroidEntryPoint
import io.github.codex07.iou.api.LedgerApi
import javax.inject.Inject

/** Placeholder until the UI contributor's screens land: proves the api is injected and live. */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var ledger: LedgerApi

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val overview by ledger.observeOverview().collectAsState(initial = null)
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    Box(Modifier.safeDrawingPadding(), contentAlignment = Alignment.Center) {
                        Text("IOU (${BuildConfig.FLAVOR}): ${overview?.rows?.size ?: 0} balances")
                    }
                }
            }
        }
    }
}

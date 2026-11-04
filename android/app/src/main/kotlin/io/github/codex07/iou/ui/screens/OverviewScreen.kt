
package io.github.codex07.iou.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.codex07.iou.api.Overview
import io.github.codex07.iou.money.Money

@Composable
fun OverviewScreen(overview: Overview?) {
    val colors = MaterialTheme.colorScheme

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        Text(
            text = "Your overview",
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            color = colors.onBackground
        )

        if (overview == null) {
            CircularProgressIndicator()
        } else {
            // Main financial summary
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = colors.primaryContainer,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        "Your money, clearly recorded",
                        color = colors.onPrimaryContainer,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold
                    )

                    Text(
                        "Balances are tracked separately by currency.",
                        color = colors.onPrimaryContainer,
                        fontSize = 14.sp
                    )
                }
            }

            // Balance cards
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                BalanceCard(
                    title = "Owed to you",
                    amount = formatBalances(overview.owedToMe),
                    accent = colors.primary,
                    modifier = Modifier.weight(1f)
                )

                BalanceCard(
                    title = "You owe",
                    amount = formatBalances(overview.iOwe),
                    accent = colors.tertiary,
                    modifier = Modifier.weight(1f)
                )
            }

            Text(
                "People & groups",
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = colors.onBackground
            )

            Text(
                "${overview.rows.size} balance records",
                color = colors.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun BalanceCard(
    title: String,
    amount: String,
    accent: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                title,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 13.sp
            )

            Text(
                amount,
                color = accent,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

private fun formatBalances(balances: List<Money>): String {
    if (balances.isEmpty()) return "Settled"

    return balances.joinToString("\n") {
        "${it.toPlainString()} ${it.currency.code}"
    }
}

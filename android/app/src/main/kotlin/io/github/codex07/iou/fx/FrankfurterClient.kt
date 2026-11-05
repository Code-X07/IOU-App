package io.github.codex07.iou.fx

import io.github.codex07.iou.api.LedgerResult
import io.github.codex07.iou.ledger.engine.FxConverter
import io.github.codex07.iou.money.CurrencyCode
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import java.time.LocalDate
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Rates the ECB published for [date]: units of each currency per one EUR. */
data class PublishedRates(val date: LocalDate, val perEur: Map<CurrencyCode, String>)

/**
 * ECB reference rates from Frankfurter (spec §4.5). It always asks for EUR rates, which are the ones the
 * ECB publishes; other pairs are inverted or crossed locally ([RateTable]), so their precision doesn't
 * depend on Frankfurter's rounding of non-EUR bases. Rates are read from the JSON text, never as floating
 * point.
 */
class FrankfurterClient(private val http: HttpClient, private val baseUrl: String = BASE_URL) : AutoCloseable {

    /**
     * EUR rates for [currencies] on [date], or the latest when [date] is null. For a weekend, a holiday or a
     * date before today's publication, Frankfurter answers with the previous working day, so the result's
     * date can be earlier than [date]. Null when there is nothing to ask for or the rates are unavailable for
     * any reason: no network, a timeout, a status other than 200 (an unknown currency or a future date gives
     * 404), or an unreadable answer. Invalid rates are left out.
     */
    suspend fun eurRates(date: LocalDate?, currencies: Set<CurrencyCode>): PublishedRates? {
        val symbols = currencies.filter { it != EUR }.map { it.code }.sorted()
        if (symbols.isEmpty()) return null
        return try {
            val response = http.get("$baseUrl/${date ?: "latest"}") {
                parameter("base", EUR.code)
                parameter("symbols", symbols.joinToString(","))
            }
            if (response.status == HttpStatusCode.OK) parse(response.bodyAsText()) else null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    override fun close() = http.close()

    private fun parse(text: String): PublishedRates {
        val body = Json.parseToJsonElement(text).jsonObject
        val rates = body.getValue("rates").jsonObject.mapNotNull { (code, value) ->
            val currency = CurrencyCode.parse(code) ?: return@mapNotNull null
            val rate = FxConverter.validateRate(value.jsonPrimitive.content) as? LedgerResult.Ok ?: return@mapNotNull null
            currency to rate.value.stripTrailingZeros().toPlainString()
        }
        return PublishedRates(LocalDate.parse(body.getValue("date").jsonPrimitive.content), rates.toMap())
    }

    companion object {
        const val BASE_URL = "https://api.frankfurter.dev/v1"
        val EUR = CurrencyCode("EUR")

        /** The app's client: OkHttp with short timeouts, so a slow network never holds up an entry for long. */
        fun create(): FrankfurterClient = FrankfurterClient(
            HttpClient(OkHttp) {
                install(HttpTimeout) {
                    connectTimeoutMillis = 5_000
                    requestTimeoutMillis = 10_000
                }
            },
        )
    }
}

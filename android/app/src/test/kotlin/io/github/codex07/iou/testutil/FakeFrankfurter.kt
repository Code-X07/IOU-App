package io.github.codex07.iou.testutil

import io.github.codex07.iou.fx.FrankfurterClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.headersOf
import java.io.IOException
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Frankfurter on Ktor's `MockEngine`, answering like the live v1 API: EUR rates as JSON numbers, a missing
 * date answered from the latest earlier one, 404 for an unknown currency or when no date fits, 422 for a base
 * other than EUR. With [online] false every request fails with an `IOException`.
 */
class FakeFrankfurter {
    /** EUR rates (JSON number text) by date. */
    val published: MutableMap<LocalDate, Map<String, String>> = ConcurrentHashMap()
    @Volatile var online = true
    val requests: MutableList<Url> = CopyOnWriteArrayList()

    fun publish(date: LocalDate, vararg perEur: Pair<String, String>) {
        published[date] = perEur.toMap()
    }

    fun client(): FrankfurterClient = FrankfurterClient(
        HttpClient(
            MockEngine { request ->
                requests += request.url
                if (!online) throw IOException("offline")
                val (status, body) = answer(request.url)
                respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
            },
        ),
    )

    private fun answer(url: Url): Pair<HttpStatusCode, String> {
        if (url.parameters["base"] != "EUR") return HttpStatusCode.UnprocessableEntity to """{"message":"bad currency pair"}"""
        val asked = url.segments.last().let { if (it == "latest") LocalDate.MAX else LocalDate.parse(it) }
        val symbols = url.parameters["symbols"].orEmpty().split(',')
        val day = published.keys.filter { it <= asked }.maxOrNull()
        val rates = day?.let { published.getValue(it) }
        if (rates == null || symbols.any { it !in rates }) return HttpStatusCode.NotFound to """{"message":"not found"}"""
        val json = symbols.joinToString(",") { "\"$it\":${rates.getValue(it)}" }
        return HttpStatusCode.OK to """{"amount":1.0,"base":"EUR","date":"$day","rates":{$json}}"""
    }
}

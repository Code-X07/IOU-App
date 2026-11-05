package io.github.codex07.iou.fx

import io.github.codex07.iou.money.CurrencyCode
import io.github.codex07.iou.testutil.EUR
import io.github.codex07.iou.testutil.FakeFrankfurter
import io.github.codex07.iou.testutil.JPY
import io.github.codex07.iou.testutil.SEK
import io.github.codex07.iou.testutil.USD
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.time.LocalDate
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class FrankfurterClientTest {

    private val friday = LocalDate.parse("2026-10-30")
    private val sunday = LocalDate.parse("2026-11-01")
    private val frankfurter = FakeFrankfurter()
    private val client = frankfurter.client()

    private fun answering(status: HttpStatusCode, body: String) = FrankfurterClient(
        HttpClient(MockEngine { respond(body, status, headersOf(HttpHeaders.ContentType, "application/json")) }),
    )

    @Test
    fun `asks for EUR rates of the other currencies and keeps the rate text exactly`() = runTest {
        frankfurter.publish(friday, "SEK" to "10.978", "USD" to "1.15600", "JPY" to "0.123456789012345678901234567890123")

        client.eurRates(sunday, setOf(SEK, EUR, USD, JPY)) shouldBe PublishedRates(
            friday,
            mapOf(SEK to "10.978", USD to "1.156", JPY to "0.123456789012345678901234567890123"),
        )
        val url = frankfurter.requests.single()
        url.toString() shouldBe "https://api.frankfurter.dev/v1/2026-11-01?base=EUR&symbols=JPY%2CSEK%2CUSD"
    }

    @Test
    fun `without a date it asks for the latest rates`() = runTest {
        frankfurter.publish(friday, "SEK" to "10.978")

        client.eurRates(null, setOf(SEK))!!.date shouldBe friday
        frankfurter.requests.single().segments.last() shouldBe "latest"
    }

    @Test
    fun `unavailable rates give null`() = runTest {
        frankfurter.publish(friday, "SEK" to "10.978")
        client.eurRates(friday.minusDays(1), setOf(SEK)) shouldBe null
        client.eurRates(friday, setOf(CurrencyCode("ISK"))) shouldBe null
        frankfurter.online = false
        client.eurRates(friday, setOf(SEK)) shouldBe null

        answering(HttpStatusCode(522, "Origin down"), "error code: 522").eurRates(friday, setOf(SEK)) shouldBe null
        answering(HttpStatusCode.OK, "<html>maintenance</html>").eurRates(friday, setOf(SEK)) shouldBe null
        answering(HttpStatusCode.OK, """{"date":"2026-10-30"}""").eurRates(friday, setOf(SEK)) shouldBe null
    }

    @Test
    fun `nothing is asked for EUR alone`() = runTest {
        client.eurRates(friday, setOf(EUR)) shouldBe null
        frankfurter.requests.shouldBeEmpty()
    }

    @Test
    fun `invalid rates and unsupported currencies are left out`() = runTest {
        val body = """{"amount":1.0,"base":"EUR","date":"2026-10-30","rates":{"SEK":0,"USD":-1.1,"XAU":0.0004,"JPY":163.2}}"""

        answering(HttpStatusCode.OK, body).eurRates(friday, setOf(SEK, USD, JPY)) shouldBe
            PublishedRates(friday, mapOf(JPY to "163.2"))
    }
}

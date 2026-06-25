package price.sats

import kotlin.math.floor

/**
 * Test double for [PriceCore] that mirrors the **parsing** behaviour of the
 * real Rust core: `rust_decimal::Decimal::from_str` accepts only `.` as the
 * decimal separator, so any `,` (or other non-numeric character) makes the
 * conversion throw — exactly like the production binding.
 *
 * `String.toDouble()` reproduces that contract for free: `"50.5".toDouble()`
 * parses, `"50,5".toDouble()` throws. The arithmetic is intentionally simple
 * (Double, not Decimal) — these tests assert *behaviour around parsing*, not
 * sat-perfect rounding, which the Rust `convert` crate already covers.
 */
class FakePriceCore(
    private val fiats: List<String> = listOf("usd", "eur", "pyg"),
) : PriceCore {

    override suspend fun fetchPrice(fiat: String): PriceSnapshot =
        PriceSnapshot("90000", fiat, 1, false, 0, "0")

    override fun supportedFiats(): List<String> = fiats

    override fun convertSatsToFiat(sats: ULong, price: String): String {
        val btc = sats.toDouble() / SATS_PER_BTC
        val value = btc * price.toDouble()
        // Two fractional digits, like the Rust `format!("{:.2}", …)`.
        val rounded = floor(value * 100.0 + 0.5) / 100.0
        return rounded.toString()
    }

    override fun convertFiatToSats(fiatAmount: String, price: String): ULong {
        val fiat = fiatAmount.toDouble()          // throws on ',' — mirrors Rust
        val priceDec = price.toDouble()
        require(fiat >= 0) { "negative fiat" }
        require(priceDec > 0) { "non-positive price" }
        return floor(fiat / priceDec * SATS_PER_BTC).toLong().toULong()
    }

    override fun convertSatsToBtc(sats: ULong): String =
        (sats.toDouble() / SATS_PER_BTC).toString()

    override fun convertBtcToSats(btc: String): ULong {
        val value = btc.toDouble()                // throws on ',' — mirrors Rust
        require(value >= 0) { "negative btc" }
        return floor(value * SATS_PER_BTC).toLong().toULong()
    }

    private companion object {
        const val SATS_PER_BTC = 100_000_000.0
    }
}

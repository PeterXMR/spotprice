package price.sats

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Regression tests for [computeSats] — the single choke point where a
 * user-typed string becomes a canonical sat count.
 *
 * The headline case is the European decimal comma: a EUR-locale keyboard
 * offers `,` as its decimal key, and the Rust core only parses `.`. Before the
 * fix, typing `50,5` made every derived field go blank.
 */
class ComputeSatsTest {

    private val core = FakePriceCore()
    private val snapshots = mapOf(
        "eur" to PriceSnapshot(price = "90000", fiat = "eur", sourcesUsed = 1, stale = false, timestamp = 0, dispersion = "0"),
    )

    @Test
    fun fiat_comma_decimal_separator_converts_same_as_period() {
        val withPeriod = computeSats(core, InputSource.Fiat("eur"), "50.5", snapshots)
        val withComma = computeSats(core, InputSource.Fiat("eur"), "50,5", snapshots)

        assertNotNull(withComma, "comma-decimal fiat input should convert, not blank out")
        assertEquals(withPeriod, withComma)
    }

    @Test
    fun btc_comma_decimal_separator_converts_same_as_period() {
        val withPeriod = computeSats(core, InputSource.Btc, "0.5", snapshots)
        val withComma = computeSats(core, InputSource.Btc, "0,5", snapshots)

        assertNotNull(withComma, "comma-decimal BTC input should convert, not blank out")
        assertEquals(withPeriod, withComma)
    }

    @Test
    fun fiat_plain_integer_still_works() {
        val sats = computeSats(core, InputSource.Fiat("eur"), "50", snapshots)
        assertEquals(55_555uL, sats) // 50 / 90000 * 1e8, floored
    }

    @Test
    fun sats_with_grouping_comma_is_read_as_integer() {
        // "1,000" sats means one thousand — grouping, not a decimal.
        val sats = computeSats(core, InputSource.Sats, "1,000", snapshots)
        assertEquals(1_000uL, sats)
    }

    @Test
    fun negative_sats_input_is_rejected_not_flipped_positive() {
        // A stray leading '-' must not silently become a positive amount.
        assertNull(computeSats(core, InputSource.Sats, "-100", snapshots))
    }

    @Test
    fun blank_input_is_null() {
        assertNull(computeSats(core, InputSource.Fiat("eur"), "", snapshots))
    }

    @Test
    fun fiat_without_loaded_price_is_null() {
        assertNull(computeSats(core, InputSource.Fiat("gbp"), "50", snapshots))
    }
}

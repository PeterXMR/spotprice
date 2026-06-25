package price.sats

import kotlin.test.Test
import kotlin.test.assertEquals

/** Pure-function coverage for [normalizeAmount]. */
class NormalizeAmountTest {

    @Test fun period_decimal_is_unchanged() = assertEquals("50.5", normalizeAmount("50.5"))

    @Test fun comma_decimal_becomes_period() = assertEquals("50.5", normalizeAmount("50,5"))

    @Test fun plain_integer_is_unchanged() = assertEquals("50", normalizeAmount("50"))

    @Test fun leading_decimal_gets_zero() {
        assertEquals("0.5", normalizeAmount(",5"))
        assertEquals("0.5", normalizeAmount(".5"))
    }

    @Test fun trailing_separator_is_dropped() {
        assertEquals("50", normalizeAmount("50,"))
        assertEquals("50", normalizeAmount("50."))
    }

    @Test fun us_grouping_keeps_last_separator_as_decimal() =
        assertEquals("1234.56", normalizeAmount("1,234.56"))

    @Test fun eu_grouping_keeps_last_separator_as_decimal() =
        assertEquals("1234.56", normalizeAmount("1.234,56"))

    @Test fun currency_symbols_and_spaces_are_stripped() =
        assertEquals("50.5", normalizeAmount(" €50,5 "))

    @Test fun negative_sign_is_preserved() = assertEquals("-50.5", normalizeAmount("-50,5"))

    @Test fun empty_and_junk_become_empty() {
        assertEquals("", normalizeAmount(""))
        assertEquals("", normalizeAmount("   "))
        assertEquals("", normalizeAmount("abc"))
    }
}

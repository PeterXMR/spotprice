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

    // --- toDotDecimal: the display-time comma -> dot swap ---

    @Test fun dot_decimal_comma_becomes_dot() = assertEquals("0.1", toDotDecimal("0,1"))

    @Test fun dot_decimal_dot_is_unchanged() = assertEquals("0.1", toDotDecimal("0.1"))

    @Test fun dot_decimal_keeps_trailing_separator_for_in_progress_typing() =
        assertEquals("0.", toDotDecimal("0,"))

    @Test fun dot_decimal_leaves_plain_integer_alone() = assertEquals("100", toDotDecimal("100"))
}

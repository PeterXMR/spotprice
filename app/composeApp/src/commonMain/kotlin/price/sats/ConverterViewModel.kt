package price.sats

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Which field the user last edited — the canonical sats value is derived from
 * this single source so the other fields can project off it without circular
 * "field A updates field B updates field A" loops.
 */
sealed interface InputSource {
    object Sats : InputSource
    object Btc : InputSource
    data class Fiat(val code: String) : InputSource
}

/**
 * Which Bitcoin denomination is currently editable. Sticky across input
 * source changes so that switching to a fiat and back doesn't lose the user's
 * preferred unit.
 */
enum class BitcoinUnit { SATS, BTC }

/**
 * Holds the converter's state across configuration changes (rotation, locale
 * switch, dark-mode toggle, etc.) and runs the 65s background refresh tick.
 *
 * Persists three things across process restart via [PreferencesRepo]:
 *  - the set of selected fiats
 *  - which field the user was last editing
 *  - the amount in that field
 *
 * The tick is 65s rather than 60s on purpose — the Rust core cache has a 60s
 * TTL, so a 60s poll would race the cache boundary and sometimes return a
 * stale snapshot. 65s guarantees the cache has expired by the time we ask.
 */
class ConverterViewModel(
    private val core: PriceCore,
    private val prefs: PreferencesRepo,
) : ViewModel() {

    val selectedFiats = mutableStateListOf<String>().apply { addAll(prefs.selectedFiats()) }
    val snapshots = mutableStateMapOf<String, PriceSnapshot>()
    val loadingFiats = mutableStateMapOf<String, Boolean>()
    val errorFiats = mutableStateMapOf<String, String>()

    var inputSource: InputSource by mutableStateOf(prefs.inputSource())
        private set
    var inputAmount: String by mutableStateOf(prefs.inputAmount())
        private set

    /** null = follow system, true = forced dark, false = forced light. */
    var themeOverride: Boolean? by mutableStateOf(prefs.themeOverride())
        private set

    /** Which Bitcoin unit's field is currently editable. */
    var bitcoinUnit: BitcoinUnit by mutableStateOf(prefs.bitcoinUnit())
        private set

    val supportedFiats: List<String> = core.supportedFiats()

    /**
     * Lifetime flag: has the initial auto-focus (the cold-start keyboard pop
     * on the Sats field) already fired? Lives in the VM, not the composable,
     * so navigating away and back to Converter doesn't re-trigger it.
     */
    var hasShownInitialKeyboard: Boolean = false

    val isAnyLoading: Boolean
        get() = loadingFiats.any { it.value }

    init {
        viewModelScope.launch {
            selectedFiats.forEach { fiat ->
                if (snapshots[fiat] == null) loadPrice(fiat)
            }
        }
        viewModelScope.launch {
            while (isActive) {
                delay(REFRESH_INTERVAL_MS)
                refreshAll()
            }
        }
    }

    fun setInput(source: InputSource, amount: String) {
        inputSource = source
        inputAmount = amount
        prefs.setInputSource(source)
        prefs.setInputAmount(amount)
        // Typing directly into a Bitcoin field also defines the editable unit.
        when (source) {
            InputSource.Sats -> if (bitcoinUnit != BitcoinUnit.SATS) {
                bitcoinUnit = BitcoinUnit.SATS
                prefs.setBitcoinUnit(BitcoinUnit.SATS)
            }
            InputSource.Btc -> if (bitcoinUnit != BitcoinUnit.BTC) {
                bitcoinUnit = BitcoinUnit.BTC
                prefs.setBitcoinUnit(BitcoinUnit.BTC)
            }
            is InputSource.Fiat -> {} // bitcoinUnit stays whatever it was
        }
    }

    fun toggleFiat(fiat: String) {
        if (selectedFiats.contains(fiat)) {
            if (selectedFiats.size > 1) {
                selectedFiats.remove(fiat)
                prefs.setSelectedFiats(selectedFiats.toList())
            }
        } else {
            selectedFiats.add(fiat)
            prefs.setSelectedFiats(selectedFiats.toList())
            viewModelScope.launch { loadPrice(fiat) }
        }
    }

    fun removeFiat(fiat: String) {
        if (selectedFiats.size <= 1) return
        if ((inputSource as? InputSource.Fiat)?.code == fiat) {
            val current = computeSats(core, inputSource, inputAmount, snapshots)
            inputSource = InputSource.Sats
            inputAmount = current?.toString() ?: "100000000"
            prefs.setInputSource(inputSource)
            prefs.setInputAmount(inputAmount)
        }
        selectedFiats.remove(fiat)
        prefs.setSelectedFiats(selectedFiats.toList())
    }

    fun refreshAll() {
        viewModelScope.launch {
            selectedFiats.forEach { launch { loadPrice(it) } }
        }
    }

    /**
     * Toggle between explicit light and dark, ignoring the (unspecified)
     * "follow system" state. Once toggled the choice sticks until the user
     * clears it from a future settings screen.
     */
    fun toggleTheme(systemDark: Boolean) {
        val current = themeOverride ?: systemDark
        val next = !current
        themeOverride = next
        prefs.setThemeOverride(next)
    }

    /** Settings-screen explicit set: null = follow system. */
    fun applyThemeMode(value: Boolean?) {
        themeOverride = value
        prefs.setThemeOverride(value)
    }

    /** Settings-screen "Reset to USD only" — wipes all selected fiats back to default. */
    fun resetSelectedFiats() {
        selectedFiats.clear()
        selectedFiats.add("usd")
        prefs.setSelectedFiats(selectedFiats.toList())
        if ((inputSource as? InputSource.Fiat)?.code != null &&
            (inputSource as InputSource.Fiat).code != "usd") {
            inputSource = InputSource.Sats
            inputAmount = "100000000"
            prefs.setInputSource(inputSource)
            prefs.setInputAmount(inputAmount)
        }
        viewModelScope.launch { loadPrice("usd") }
    }

    /**
     * Switch which Bitcoin unit's field is editable. Sets the input source
     * to that unit and converts the current canonical sats value into the
     * new field's representation so the user sees the same amount in the
     * new unit immediately.
     */
    fun selectBitcoinUnit(unit: BitcoinUnit) {
        if (bitcoinUnit == unit && inputSource is InputSource.Sats && unit == BitcoinUnit.SATS) return
        if (bitcoinUnit == unit && inputSource is InputSource.Btc && unit == BitcoinUnit.BTC) return
        val sats = computeSats(core, inputSource, inputAmount, snapshots)
        bitcoinUnit = unit
        prefs.setBitcoinUnit(unit)
        when (unit) {
            BitcoinUnit.SATS -> {
                inputSource = InputSource.Sats
                inputAmount = sats?.toString() ?: "0"
            }
            BitcoinUnit.BTC -> {
                inputSource = InputSource.Btc
                inputAmount = sats?.let { core.convertSatsToBtc(it) } ?: "0"
            }
        }
        prefs.setInputSource(inputSource)
        prefs.setInputAmount(inputAmount)
    }

    fun displayedSats(): String =
        if (inputSource == InputSource.Sats) inputAmount
        else computeSats(core, inputSource, inputAmount, snapshots)?.toString().orEmpty()

    fun displayedBtc(): String =
        if (inputSource == InputSource.Btc) inputAmount
        else computeSats(core, inputSource, inputAmount, snapshots)
            ?.let { core.convertSatsToBtc(it) }.orEmpty()

    fun displayedFiat(fiat: String): String {
        if ((inputSource as? InputSource.Fiat)?.code == fiat) return inputAmount
        val sats = computeSats(core, inputSource, inputAmount, snapshots) ?: return ""
        val price = snapshots[fiat]?.price ?: return ""
        return runCatching { core.convertSatsToFiat(sats, price) }.getOrDefault("")
    }

    private suspend fun loadPrice(fiat: String) {
        loadingFiats[fiat] = true
        errorFiats.remove(fiat)
        try {
            snapshots[fiat] = core.fetchPrice(fiat)
        } catch (t: Throwable) {
            errorFiats[fiat] = t.message ?: t::class.simpleName ?: "fetch failed"
        } finally {
            loadingFiats[fiat] = false
        }
    }

    companion object {
        private const val REFRESH_INTERVAL_MS = 65_000L
    }
}

internal fun computeSats(
    core: PriceCore,
    source: InputSource,
    raw: String,
    snapshots: Map<String, PriceSnapshot>,
): ULong? {
    if (raw.isBlank()) return null
    return runCatching {
        when (source) {
            // Sats is an integer — strip digit-grouping separators but keep a
            // stray '-' so a negative entry fails to parse (→ null) instead of
            // being silently flipped to a positive amount.
            InputSource.Sats -> raw.filter { it.isDigit() || it == '-' }.toULongOrNull()
            InputSource.Btc -> core.convertBtcToSats(normalizeAmount(raw))
            is InputSource.Fiat -> {
                val price = snapshots[source.code]?.price ?: return null
                core.convertFiatToSats(normalizeAmount(raw), price)
            }
        }
    }.getOrNull()
}

/**
 * Normalise a user-typed decimal amount into the canonical `123.45` form that
 * the Rust core's `Decimal::from_str` accepts.
 *
 * The Rust parser is locale-invariant — it only understands `.` as the decimal
 * mark. But Android soft keyboards in much of the world (and every EUR-locale
 * keyboard) surface `,` as the decimal key, so a user converting euros types
 * `50,5`. Without this step that string throws inside the core and every
 * derived field silently blanks out.
 *
 * Rules, chosen to be unambiguous rather than locale-aware:
 *  - Drop everything that isn't a digit or a separator (`.`/`,`) — strips
 *    currency symbols, spaces, stray characters.
 *  - The **last** separator is the decimal point; any earlier separators are
 *    digit grouping and are removed. This handles both `1,234.56` (US) and
 *    `1.234,56` (EU) grouping conventions.
 *  - A leading `-` is preserved so the core can reject it as it does today.
 *
 * Returns `""` for input that carries no digits; callers treat that as "no
 * value", matching the existing blank-input behaviour.
 */
internal fun normalizeAmount(raw: String): String {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return ""
    val sign = if (trimmed.startsWith("-")) "-" else ""
    val digitsAndSeps = trimmed.filter { it.isDigit() || it == '.' || it == ',' }
    if (digitsAndSeps.isEmpty()) return ""

    val lastSep = digitsAndSeps.indexOfLast { it == '.' || it == ',' }
    if (lastSep < 0) return sign + digitsAndSeps

    val intPart = digitsAndSeps.substring(0, lastSep).filter { it.isDigit() }
    val fracPart = digitsAndSeps.substring(lastSep + 1).filter { it.isDigit() }
    return when {
        intPart.isEmpty() && fracPart.isEmpty() -> ""
        fracPart.isEmpty() -> sign + intPart.ifEmpty { "0" }
        else -> "$sign${intPart.ifEmpty { "0" }}.$fracPart"
    }
}

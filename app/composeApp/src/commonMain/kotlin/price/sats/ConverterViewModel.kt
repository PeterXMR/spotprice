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
 * Holds the converter's state across configuration changes (rotation, locale
 * switch, dark-mode toggle, etc.) and runs the 65s background refresh tick.
 *
 * The tick is 65s rather than 60s on purpose — the Rust core cache has a 60s
 * TTL, so a 60s poll would race the cache boundary and sometimes return a
 * stale snapshot. 65s guarantees the cache has expired by the time we ask.
 */
class ConverterViewModel(private val core: PriceCore) : ViewModel() {

    val selectedFiats = mutableStateListOf("usd")
    val snapshots = mutableStateMapOf<String, PriceSnapshot>()
    val loadingFiats = mutableStateMapOf<String, Boolean>()
    val errorFiats = mutableStateMapOf<String, String>()

    var inputSource: InputSource by mutableStateOf(InputSource.Sats)
        private set
    var inputAmount: String by mutableStateOf("100000000")
        private set

    val supportedFiats: List<String> = core.supportedFiats()

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
    }

    fun toggleFiat(fiat: String) {
        if (selectedFiats.contains(fiat)) {
            // Refuse to remove the last one — the headline & math need a primary.
            if (selectedFiats.size > 1) selectedFiats.remove(fiat)
        } else {
            selectedFiats.add(fiat)
            viewModelScope.launch { loadPrice(fiat) }
        }
    }

    fun removeFiat(fiat: String) {
        if (selectedFiats.size <= 1) return
        // If the user is currently editing this fiat, fall back to Sats with
        // the last-known canonical sats value so the screen stays coherent.
        if ((inputSource as? InputSource.Fiat)?.code == fiat) {
            val current = computeSats(core, inputSource, inputAmount, snapshots)
            inputSource = InputSource.Sats
            inputAmount = current?.toString() ?: "100000000"
        }
        selectedFiats.remove(fiat)
    }

    fun refreshAll() {
        viewModelScope.launch {
            selectedFiats.forEach { launch { loadPrice(it) } }
        }
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
            InputSource.Sats -> raw.toULongOrNull()
            InputSource.Btc -> core.convertBtcToSats(raw)
            is InputSource.Fiat -> {
                val price = snapshots[source.code]?.price ?: return null
                core.convertFiatToSats(raw, price)
            }
        }
    }.getOrNull()
}

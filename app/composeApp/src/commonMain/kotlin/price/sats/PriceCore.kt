package price.sats

/**
 * Platform-agnostic interface over the Rust core. The Android implementation
 * (`AndroidPriceCore`) wraps the JNA-generated `core.SatsPriceCore`; the iOS
 * implementation (Phase 2) will wrap the Swift-bridged xcframework.
 *
 * Keeping this interface in `commonMain` lets every Composable consume the
 * core without taking a hard dependency on JNA/Swift bindings.
 */
interface PriceCore {
    suspend fun fetchPrice(fiat: String): PriceSnapshot
    fun supportedFiats(): List<String>
    fun convertSatsToFiat(sats: ULong, price: String): String
    fun convertFiatToSats(fiatAmount: String, price: String): ULong
    fun convertSatsToBtc(sats: ULong): String
    fun convertBtcToSats(btc: String): ULong
}

/** A single point-in-time aggregated price returned to the UI. */
data class PriceSnapshot(
    val price: String,
    val fiat: String,
    val sourcesUsed: Int,
    val stale: Boolean,
    val timestamp: Long,
    val dispersion: String,
)

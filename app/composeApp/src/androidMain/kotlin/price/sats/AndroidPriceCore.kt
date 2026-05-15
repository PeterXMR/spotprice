package price.sats

import price.sats.core.SatsPriceCore as NativeCore

/** Adapter from the JNA-generated UniFFI binding to the KMP-portable interface. */
internal class AndroidPriceCore(private val native: NativeCore) : PriceCore {

    override suspend fun fetchPrice(fiat: String): PriceSnapshot {
        val snap = native.fetchPrice(fiat)
        return PriceSnapshot(
            price = snap.price,
            fiat = snap.fiat,
            sourcesUsed = snap.sourcesUsed.toInt(),
            stale = snap.stale,
            timestamp = snap.timestamp.toLong(),
            dispersion = snap.dispersion,
        )
    }

    override fun supportedFiats(): List<String> = native.supportedFiats()

    override fun convertSatsToFiat(sats: ULong, price: String): String =
        native.convertSatsToFiat(sats, price)

    override fun convertFiatToSats(fiatAmount: String, price: String): ULong =
        native.convertFiatToSats(fiatAmount, price)

    override fun convertSatsToBtc(sats: ULong): String = native.convertSatsToBtc(sats)

    override fun convertBtcToSats(btc: String): ULong = native.convertBtcToSats(btc)
}

package price.sats

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import price.sats.core.SatsPriceCore as NativeCore

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val core: PriceCore = AndroidPriceCore(NativeCore())
        setContent { App(core) }
    }
}

/** Adapter from the JNA-generated UniFFI binding to the KMP-portable interface. */
private class AndroidPriceCore(private val native: NativeCore) : PriceCore {

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

package price.sats

import org.koin.dsl.module
import price.sats.core.SatsPriceCore as NativeCore

/**
 * Binds the Android-flavoured PriceCore implementation. Kept separate from the
 * common module so iOS can supply its own without dragging the JNA dependency.
 */
val androidModule = module {
    single<PriceCore> { AndroidPriceCore(NativeCore()) }
}

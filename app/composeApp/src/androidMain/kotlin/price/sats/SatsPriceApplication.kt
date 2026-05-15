package price.sats

import android.app.Application

class SatsPriceApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // The UniFFI-generated Kotlin bindings will call `System.loadLibrary`
        // on first access, but loading here surfaces UnsatisfiedLinkError early
        // (during boot) rather than mid-feature.
        System.loadLibrary("satsprice_ffi")
    }
}

package price.sats

import android.app.Application
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin

class SatsPriceApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Surface UnsatisfiedLinkError at boot instead of mid-feature — the
        // UniFFI bindings would otherwise lazy-load on first call.
        System.loadLibrary("satsprice_ffi")

        // Koin starts in Application so the container outlives Activity recreation.
        startKoin {
            androidContext(this@SatsPriceApplication)
            modules(appModule, androidModule)
        }
    }
}

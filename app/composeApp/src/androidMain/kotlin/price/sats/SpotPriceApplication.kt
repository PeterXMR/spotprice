package price.sats

import android.app.Application
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin

class SpotPriceApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Surface UnsatisfiedLinkError at boot instead of mid-feature — the
        // UniFFI bindings would otherwise lazy-load on first call. Library
        // name is retained from before the SatsPrice → SpotPrice rebrand;
        // renaming the cdylib requires regenerating UniFFI bindings.
        System.loadLibrary("satsprice_ffi")

        // Koin starts in Application so the container outlives Activity recreation.
        startKoin {
            androidContext(this@SpotPriceApplication)
            modules(appModule, androidModule)
        }
    }
}

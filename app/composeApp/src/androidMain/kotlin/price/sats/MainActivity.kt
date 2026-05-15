package price.sats

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import price.sats.core.SatsPriceCore

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val core = SatsPriceCore()
        val fiats = core.supportedFiats()
        setContent { App(supportedFiats = fiats) }
    }
}

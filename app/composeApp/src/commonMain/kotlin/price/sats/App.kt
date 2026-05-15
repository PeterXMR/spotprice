package price.sats

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable

@Composable
fun App(core: PriceCore) {
    MaterialTheme {
        ConverterScreen(core)
    }
}

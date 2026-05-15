package price.sats

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Root UI for SatsPrice. Phase 7/8 wires only a smoke test that prints
 * the list of fiats coming from the Rust core. Real UI screens (Converter,
 * Sources, Settings) land in Phase 9.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App(supportedFiats: List<String>) {
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            Scaffold(
                topBar = {
                    TopAppBar(title = { Text("SatsPrice") })
                }
            ) { padding ->
                Column(
                    modifier = Modifier
                        .padding(padding)
                        .padding(16.dp)
                        .fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = "Hello from Rust ✦",
                        style = MaterialTheme.typography.headlineMedium,
                    )
                    Text(
                        text = "Core says: ${supportedFiats.size} supported fiats",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        text = supportedFiats.joinToString().uppercase(),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = "Phase 9 will replace this with the Converter screen.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

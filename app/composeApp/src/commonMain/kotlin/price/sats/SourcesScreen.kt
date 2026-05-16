package price.sats

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Stub. Real implementation needs a Rust FFI extension: a `SourceQuote` record
 * + `fetch_price_with_sources(fiat)` method that returns the unfiltered raw
 * per-exchange quotes alongside the aggregated median. That work is queued
 * for a follow-up turn.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SourcesScreen(onMenuClick: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Price sources") },
                navigationIcon = {
                    IconButton(onClick = onMenuClick) {
                        Icon(Icons.Filled.Menu, contentDescription = "Open menu")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(horizontal = 20.dp)
                .fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Spacer(Modifier.height(8.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = "Coming soon",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = "The headline price is a median across Coinbase, " +
                            "Kraken, Bitstamp, and CoinGecko. This screen will " +
                            "surface each source's individual quote once the Rust " +
                            "core exposes them through the FFI.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}

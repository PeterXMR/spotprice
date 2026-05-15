package price.sats

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.koin.compose.viewmodel.koinViewModel

/**
 * Sats / BTC / multi-fiat converter. State lives in [ConverterViewModel] so it
 * survives Activity recreation; the 65s background refresh is also driven from
 * there.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConverterScreen(vm: ConverterViewModel = koinViewModel()) {
    var pickerOpen by remember { mutableStateOf(false) }

    val primaryFiat = vm.selectedFiats.firstOrNull() ?: "usd"
    val primarySnapshot = vm.snapshots[primaryFiat]
    val primaryLoading = vm.loadingFiats[primaryFiat] == true
    val primaryError = vm.errorFiats[primaryFiat]
    val anyLoading = vm.isAnyLoading

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("SatsPrice") },
                actions = {
                    FilledTonalButton(
                        onClick = { vm.refreshAll() },
                        enabled = !anyLoading,
                    ) { Text(if (anyLoading) "…" else "Refresh") }
                    Spacer(Modifier.width(8.dp))
                },
                colors = TopAppBarDefaults.topAppBarColors(),
            )
        },
        bottomBar = {
            BottomCurrencyBar(
                count = vm.selectedFiats.size,
                onClick = { pickerOpen = true },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(horizontal = 20.dp)
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Spacer(Modifier.height(8.dp))

            PriceHeadline(
                snapshot = primarySnapshot,
                isLoading = primaryLoading,
                error = primaryError,
                fiat = primaryFiat,
            )

            AmountField(
                label = "Sats",
                value = vm.displayedSats(),
                onChange = { vm.setInput(InputSource.Sats, it) },
                keyboardType = KeyboardType.Number,
            )
            AmountField(
                label = "BTC",
                value = vm.displayedBtc(),
                onChange = { vm.setInput(InputSource.Btc, it) },
                keyboardType = KeyboardType.Decimal,
            )

            vm.selectedFiats.forEach { fiat ->
                FiatRow(
                    fiat = fiat,
                    value = vm.displayedFiat(fiat),
                    loading = vm.loadingFiats[fiat] == true,
                    error = vm.errorFiats[fiat],
                    removable = vm.selectedFiats.size > 1,
                    onChange = { vm.setInput(InputSource.Fiat(fiat), it) },
                    onRemove = { vm.removeFiat(fiat) },
                )
            }

            Spacer(Modifier.height(8.dp))
        }
    }

    if (pickerOpen) {
        CurrencyPickerSheet(
            allFiats = vm.supportedFiats,
            selected = vm.selectedFiats.toList(),
            onToggle = vm::toggleFiat,
            onDismiss = { pickerOpen = false },
        )
    }
}

@Composable
private fun PriceHeadline(
    snapshot: PriceSnapshot?,
    isLoading: Boolean,
    error: String?,
    fiat: String,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            when {
                isLoading && snapshot == null -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            "Fetching price across exchanges…",
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }
                error != null -> {
                    Text(
                        "Couldn't fetch a price",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center,
                    )
                }
                snapshot != null -> {
                    Text(
                        text = "1 BTC",
                        style = MaterialTheme.typography.labelLarge,
                    )
                    Text(
                        text = "${formatDecimal(snapshot.price, 2)} ${fiat.uppercase()}",
                        style = MaterialTheme.typography.headlineLarge,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        text = sourcesCaption(snapshot.sourcesUsed),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AmountField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    keyboardType: KeyboardType,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        textStyle = MaterialTheme.typography.titleMedium,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FiatRow(
    fiat: String,
    value: String,
    loading: Boolean,
    error: String?,
    removable: Boolean,
    onChange: (String) -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            label = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(fiat.uppercase())
                    if (loading) {
                        Spacer(Modifier.width(8.dp))
                        CircularProgressIndicator(
                            modifier = Modifier.size(12.dp),
                            strokeWidth = 1.5.dp,
                        )
                    }
                }
            },
            singleLine = true,
            isError = error != null,
            supportingText = error?.let { { Text(it) } },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            textStyle = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(14.dp),
        )
        if (removable) {
            Spacer(Modifier.width(4.dp))
            Text(
                text = "✕",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier
                    .clickable(onClick = onRemove)
                    .padding(10.dp),
            )
        }
    }
}

@Composable
private fun BottomCurrencyBar(count: Int, onClick: () -> Unit) {
    Column {
        HorizontalDivider()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Currencies",
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            FilledTonalButton(onClick = onClick) {
                val noun = if (count == 1) "currency" else "currencies"
                Text(
                    text = "$count $noun  ▾",
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CurrencyPickerSheet(
    allFiats: List<String>,
    selected: List<String>,
    onToggle: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        sheetState = sheetState,
        onDismissRequest = onDismiss,
    ) {
        LazyColumn(modifier = Modifier.fillMaxWidth()) {
            item {
                Column {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "Select currencies",
                            style = MaterialTheme.typography.headlineSmall,
                            modifier = Modifier.weight(1f),
                        )
                        FilledTonalButton(onClick = onDismiss) { Text("Done") }
                    }
                    HorizontalDivider()
                }
            }
            items(allFiats, key = { it }) { fiat ->
                CurrencyRow(
                    fiat = fiat,
                    selected = fiat in selected,
                    onClick = { onToggle(fiat) },
                )
            }
            item { Spacer(Modifier.height(16.dp)) }
        }
    }
}

@Composable
private fun CurrencyRow(fiat: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = selected, onCheckedChange = { onClick() })
        Spacer(Modifier.width(4.dp))
        Text(
            text = fiat.uppercase(),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            modifier = Modifier.weight(1f),
        )
    }
}

/** "from 1 source" vs "median across N sources" — the word "median" only
 * carries weight when there's more than one number to combine. */
private fun sourcesCaption(n: Int): String = when (n) {
    1 -> "from 1 source"
    else -> "median across $n sources"
}

/**
 * Cap fractional digits at `maxDecimals`. Drop the fraction entirely only if
 * every digit is zero; otherwise preserve trailing zeros for conventional
 * currency display (`79099.50` stays `79099.50`, not `79099.5`).
 */
private fun formatDecimal(value: String, maxDecimals: Int): String {
    val negative = value.startsWith('-')
    val unsigned = if (negative) value.drop(1) else value
    val parts = unsigned.split('.', limit = 2)
    val intPart = parts[0]
    val frac = parts.getOrNull(1) ?: ""
    val capped = frac.take(maxDecimals)
    val sign = if (negative) "-" else ""
    val hasMeaningfulFraction = capped.any { it != '0' }
    return if (hasMeaningfulFraction) "$sign$intPart.$capped" else "$sign$intPart"
}

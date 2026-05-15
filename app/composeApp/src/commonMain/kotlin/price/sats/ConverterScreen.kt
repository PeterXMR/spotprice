package price.sats

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

private enum class InputField { SATS, BTC, FIAT }

private data class Displayed(val sats: String, val btc: String, val fiat: String)

/**
 * Sats / BTC / fiat converter with a persistent bottom currency picker.
 * The selected currency triggers a refetch; the picker is a modal bottom sheet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConverterScreen(core: PriceCore) {
    val scope = rememberCoroutineScope()
    val fiats = remember { core.supportedFiats() }

    var snapshot by remember { mutableStateOf<PriceSnapshot?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var selectedFiat by remember { mutableStateOf("usd") }
    var inputField by remember { mutableStateOf(InputField.SATS) }
    var inputAmount by remember { mutableStateOf("100000000") }
    var pickerOpen by remember { mutableStateOf(false) }

    suspend fun loadPrice(fiat: String) {
        isLoading = true
        error = null
        try {
            snapshot = core.fetchPrice(fiat)
        } catch (t: Throwable) {
            error = t.message ?: t::class.simpleName ?: "fetch failed"
            snapshot = null
        } finally {
            isLoading = false
        }
    }

    LaunchedEffect(selectedFiat) { loadPrice(selectedFiat) }

    val price = snapshot?.price
    val displayed = remember(inputField, inputAmount, price) {
        deriveDisplayed(core, inputField, inputAmount, price)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("SatsPrice") },
                actions = {
                    FilledTonalButton(
                        onClick = { scope.launch { loadPrice(selectedFiat) } },
                        enabled = !isLoading,
                    ) { Text(if (isLoading) "…" else "Refresh") }
                    Spacer(Modifier.width(8.dp))
                },
                colors = TopAppBarDefaults.topAppBarColors(),
            )
        },
        bottomBar = {
            BottomCurrencyBar(
                selected = selectedFiat,
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
                snapshot = snapshot,
                isLoading = isLoading,
                error = error,
                fiat = selectedFiat,
            )

            AmountField(
                label = "Sats",
                value = displayed.sats,
                onChange = { inputField = InputField.SATS; inputAmount = it },
                keyboardType = KeyboardType.Number,
            )
            AmountField(
                label = "BTC",
                value = displayed.btc,
                onChange = { inputField = InputField.BTC; inputAmount = it },
                keyboardType = KeyboardType.Decimal,
            )
            AmountField(
                label = selectedFiat.uppercase(),
                value = displayed.fiat,
                onChange = { inputField = InputField.FIAT; inputAmount = it },
                keyboardType = KeyboardType.Decimal,
            )

            Spacer(Modifier.height(8.dp))
        }
    }

    if (pickerOpen) {
        CurrencyPickerSheet(
            fiats = fiats,
            selected = selectedFiat,
            onSelect = {
                selectedFiat = it
                inputField = InputField.SATS
                inputAmount = "100000000"
                pickerOpen = false
            },
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

@Composable
private fun BottomCurrencyBar(selected: String, onClick: () -> Unit) {
    Column {
        HorizontalDivider()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Currency",
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            FilledTonalButton(onClick = onClick) {
                Text(
                    text = "${selected.uppercase()}  ▾",
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CurrencyPickerSheet(
    fiats: List<String>,
    selected: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    // Expand fully on open — the list is long enough that the partial-expand
    // state would hide most fiats below the fold.
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        sheetState = sheetState,
        onDismissRequest = onDismiss,
    ) {
        LazyColumn(modifier = Modifier.fillMaxWidth()) {
            item {
                Column {
                    Text(
                        text = "Select currency",
                        style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                    )
                    HorizontalDivider()
                }
            }
            items(fiats, key = { it }) { fiat ->
                CurrencyRow(
                    fiat = fiat,
                    selected = fiat == selected,
                    onClick = { onSelect(fiat) },
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
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = fiat.uppercase(),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            modifier = Modifier.weight(1f),
        )
        if (selected) {
            Text(
                text = "✓",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/** Caption under the headline price. Says "from 1 source" when single-feed,
 * "median across N sources" otherwise — the word "median" only carries weight
 * when there's actually more than one number to combine. */
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

private fun deriveDisplayed(
    core: PriceCore,
    field: InputField,
    raw: String,
    price: String?,
): Displayed {
    if (raw.isBlank()) return Displayed("", "", "")
    return try {
        when (field) {
            InputField.SATS -> {
                val sats = raw.toULongOrNull() ?: return Displayed(raw, "", "")
                Displayed(
                    sats = raw,
                    btc = core.convertSatsToBtc(sats),
                    fiat = price?.let { core.convertSatsToFiat(sats, it) }.orEmpty(),
                )
            }
            InputField.BTC -> {
                val sats = runCatching { core.convertBtcToSats(raw) }.getOrElse {
                    return Displayed("", raw, "")
                }
                Displayed(
                    sats = sats.toString(),
                    btc = raw,
                    fiat = price?.let { core.convertSatsToFiat(sats, it) }.orEmpty(),
                )
            }
            InputField.FIAT -> {
                if (price.isNullOrBlank()) return Displayed("", "", raw)
                val sats = runCatching { core.convertFiatToSats(raw, price) }.getOrElse {
                    return Displayed("", "", raw)
                }
                Displayed(
                    sats = sats.toString(),
                    btc = core.convertSatsToBtc(sats),
                    fiat = raw,
                )
            }
        }
    } catch (_: Throwable) {
        Displayed(raw, "", "")
    }
}

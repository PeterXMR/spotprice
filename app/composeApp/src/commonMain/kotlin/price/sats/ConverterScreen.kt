package price.sats

import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
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
fun ConverterScreen(
    vm: ConverterViewModel = koinViewModel(),
    onMenuClick: () -> Unit = {},
) {
    var pickerOpen by remember { mutableStateOf(false) }
    val systemDark = isSystemInDarkTheme()
    val effectiveDark = vm.themeOverride ?: systemDark

    val primaryFiat = vm.selectedFiats.firstOrNull() ?: "usd"
    val primarySnapshot = vm.snapshots[primaryFiat]
    val primaryLoading = vm.loadingFiats[primaryFiat] == true
    val primaryError = vm.errorFiats[primaryFiat]
    val anyLoading = vm.isAnyLoading

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("SpotPrice") },
                navigationIcon = {
                    IconButton(onClick = onMenuClick) {
                        Icon(Icons.Filled.Menu, contentDescription = "Open menu")
                    }
                },
                actions = {
                    IconButton(onClick = { vm.toggleTheme(systemDark) }) {
                        Icon(
                            // Show the icon of the *target* mode — sun while
                            // dark (tap to go light), moon while light.
                            imageVector = if (effectiveDark) Icons.Filled.LightMode
                                          else Icons.Filled.DarkMode,
                            contentDescription = if (effectiveDark) "Switch to light mode"
                                                 else "Switch to dark mode",
                        )
                    }
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

            BitcoinSection(
                satsValue = vm.displayedSats(),
                btcValue = vm.displayedBtc(),
                activeUnit = vm.bitcoinUnit,
                onSatsChange = { vm.setInput(InputSource.Sats, it) },
                onBtcChange = { vm.setInput(InputSource.Btc, it) },
                onUnitSelect = vm::selectBitcoinUnit,
                autoFocusOnFirstShow = !vm.hasShownInitialKeyboard,
                onAutoFocusConsumed = { vm.hasShownInitialKeyboard = true },
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
    focusRequester: FocusRequester? = null,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        textStyle = MaterialTheme.typography.titleMedium,
        modifier = modifier
            .let { if (focusRequester != null) it.focusRequester(focusRequester) else it },
        shape = RoundedCornerShape(14.dp),
    )
}

/**
 * The merged Bitcoin section — sats and BTC are the same currency in different
 * units. A SegmentedButton between them picks which side is editable; the
 * other side mirrors the canonical value as a read-only display.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BitcoinSection(
    satsValue: String,
    btcValue: String,
    activeUnit: BitcoinUnit,
    onSatsChange: (String) -> Unit,
    onBtcChange: (String) -> Unit,
    onUnitSelect: (BitcoinUnit) -> Unit,
    autoFocusOnFirstShow: Boolean,
    onAutoFocusConsumed: () -> Unit,
) {
    val satsFocus = remember { FocusRequester() }
    val btcFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        if (!autoFocusOnFirstShow) return@LaunchedEffect
        runCatching {
            when (activeUnit) {
                BitcoinUnit.SATS -> satsFocus.requestFocus()
                BitcoinUnit.BTC -> btcFocus.requestFocus()
            }
        }
        onAutoFocusConsumed()
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        when (activeUnit) {
            BitcoinUnit.SATS -> AmountField(
                label = "Sats",
                value = satsValue,
                onChange = onSatsChange,
                keyboardType = KeyboardType.Number,
                focusRequester = satsFocus,
                modifier = Modifier.weight(1f),
            )
            BitcoinUnit.BTC -> AmountField(
                label = "BTC",
                value = btcValue,
                onChange = onBtcChange,
                keyboardType = KeyboardType.Decimal,
                focusRequester = btcFocus,
                modifier = Modifier.weight(1f),
            )
        }
        SingleChoiceSegmentedButtonRow {
            SegmentedButton(
                selected = activeUnit == BitcoinUnit.SATS,
                onClick = { onUnitSelect(BitcoinUnit.SATS) },
                shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
            ) { Text("Sats") }
            SegmentedButton(
                selected = activeUnit == BitcoinUnit.BTC,
                onClick = { onUnitSelect(BitcoinUnit.BTC) },
                shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
            ) { Text("BTC") }
        }
    }
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
    // `navigationBarsPadding()` lifts the whole bar (divider + row) above the
    // system gesture / 3-button nav bar. Required because:
    //   * the app targets SDK 36, so Android draws content edge-to-edge by
    //     default — the `bottomBar` slot reaches the physical bottom of the
    //     screen, behind the system nav inset;
    //   * Scaffold's `contentWindowInsets` only covers the content slot, not
    //     the bottomBar slot — custom bottomBar composables manage their own
    //     insets (Material3's `BottomAppBar` does this internally, but we use
    //     a plain Column here);
    //   * applied to the outer Column so the HorizontalDivider also shifts up,
    //     keeping it visually at the bottom of the *app's* content rather than
    //     orphaned under the system bar.
    Column(modifier = Modifier.navigationBarsPadding()) {
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
    var query by remember { mutableStateOf("") }
    val filtered = remember(query, allFiats) {
        if (query.isBlank()) allFiats
        else allFiats.filter { it.contains(query.trim(), ignoreCase = true) }
    }
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
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        placeholder = { Text("Search currency code") },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 4.dp),
                        shape = RoundedCornerShape(14.dp),
                        trailingIcon = if (query.isNotEmpty()) {
                            { TextButton(onClick = { query = "" }) { Text("Clear") } }
                        } else null,
                    )
                    HorizontalDivider()
                }
            }
            if (filtered.isEmpty()) {
                item {
                    Text(
                        text = "No currency matches \"$query\"",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.padding(20.dp),
                    )
                }
            }
            items(filtered, key = { it }) { fiat ->
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

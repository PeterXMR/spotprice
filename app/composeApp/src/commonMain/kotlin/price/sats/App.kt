package price.sats

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.launch
import org.koin.compose.viewmodel.koinViewModel

/**
 * App shell: shared theme + ModalNavigationDrawer wrapping a NavHost.
 *
 * The ConverterViewModel is hoisted here (above NavHost) so the 65s background
 * refresh tick and persisted converter state survive across drawer navigation.
 * Per-route VM scoping (via koinViewModel() inside `composable {}`) would tear
 * down and recreate the tick on every drawer tap.
 */
@Composable
fun App() {
    val vm: ConverterViewModel = koinViewModel()
    val systemDark = isSystemInDarkTheme()
    val effectiveDark = vm.themeOverride ?: systemDark

    MaterialTheme(
        colorScheme = if (effectiveDark) darkColorScheme() else lightColorScheme(),
    ) {
        val navController = rememberNavController()
        val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
        val scope = rememberCoroutineScope()
        val backStackEntry by navController.currentBackStackEntryAsState()
        val currentRoute = backStackEntry?.destination?.route ?: Routes.Converter

        ModalNavigationDrawer(
            drawerState = drawerState,
            drawerContent = {
                ModalDrawerSheet(modifier = Modifier.fillMaxHeight()) {
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = "SpotPrice",
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.padding(horizontal = 28.dp, vertical = 8.dp),
                    )
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    drawerItems.forEach { item ->
                        NavigationDrawerItem(
                            icon = { Icon(item.icon, contentDescription = null) },
                            label = { Text(item.label) },
                            selected = item.route == currentRoute,
                            onClick = {
                                scope.launch { drawerState.close() }
                                if (item.route != currentRoute) {
                                    navController.navigate(item.route) {
                                        launchSingleTop = true
                                        popUpTo(Routes.Converter) {
                                            saveState = true
                                            inclusive = false
                                        }
                                        restoreState = true
                                    }
                                }
                            },
                            modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
                        )
                    }
                }
            },
        ) {
            val openMenu: () -> Unit = { scope.launch { drawerState.open() } }
            NavHost(
                navController = navController,
                startDestination = Routes.Converter,
            ) {
                composable(Routes.Converter) {
                    ConverterScreen(vm = vm, onMenuClick = openMenu)
                }
                composable(Routes.Sources) {
                    SourcesScreen(onMenuClick = openMenu)
                }
                composable(Routes.Settings) {
                    SettingsScreen(vm = vm, onMenuClick = openMenu)
                }
                composable(Routes.About) {
                    AboutScreen(onMenuClick = openMenu)
                }
            }
        }
    }
}

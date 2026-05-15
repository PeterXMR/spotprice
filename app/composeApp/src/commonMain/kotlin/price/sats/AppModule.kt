package price.sats

import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

/**
 * Platform-agnostic Koin module. The platform module (androidMain / iosMain)
 * is responsible for binding [PriceCore] to its concrete implementation.
 */
val appModule = module {
    viewModel { ConverterViewModel(get()) }
}

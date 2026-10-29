package com.algopraxis.dhanniti.di

import com.algopraxis.dhanniti.data.api.AuthApi
import com.algopraxis.dhanniti.data.api.LedgerApi
import com.algopraxis.dhanniti.data.api.createHttpClient
import com.algopraxis.dhanniti.data.auth.AuthRepository
import com.algopraxis.dhanniti.data.auth.DataStoreTokenStore
import com.algopraxis.dhanniti.data.auth.TokenStore
import com.algopraxis.dhanniti.data.db.AppDatabase
import com.algopraxis.dhanniti.data.ledger.LedgerRepository
import com.algopraxis.dhanniti.domain.Direction
import com.algopraxis.dhanniti.ui.accounts.AccountEditorViewModel
import com.algopraxis.dhanniti.ui.accounts.AccountsViewModel
import com.algopraxis.dhanniti.ui.categories.CategoriesViewModel
import com.algopraxis.dhanniti.ui.home.HomeViewModel
import com.algopraxis.dhanniti.ui.income.IncomeEditorViewModel
import com.algopraxis.dhanniti.ui.income.IncomeViewModel
import com.algopraxis.dhanniti.ui.login.LoginViewModel
import com.algopraxis.dhanniti.ui.transactions.TransactionEditorViewModel
import com.algopraxis.dhanniti.ui.transactions.TransactionsViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.koin.core.module.Module
import org.koin.core.module.dsl.singleOf
import org.koin.core.module.dsl.viewModel
import org.koin.core.module.dsl.viewModelOf
import org.koin.core.qualifier.named
import org.koin.dsl.module

/** Qualifier for the human-readable device name sent with login, shown when listing tokens. */
val DeviceName = named("deviceName")

/** App-lifetime scope for work that outlives any screen. */
private val AppScope = named("appScope")

/**
 * Platform modules must provide: HttpClientEngine, DataStore<Preferences>, AppDatabase and a String named [DeviceName].
 */
fun sharedModule(apiBaseUrl: String, enableHttpLogging: Boolean = false): Module = module {
    single<CoroutineScope>(AppScope) { CoroutineScope(SupervisorJob() + Dispatchers.Default) }
    single<TokenStore> { DataStoreTokenStore(get()) }
    single { createHttpClient(get(), apiBaseUrl, get(), enableHttpLogging) }
    single { get<AppDatabase>().userDao() }
    singleOf(::AuthApi)
    singleOf(::LedgerApi)
    single { AuthRepository(get(), get(), get(), get(DeviceName)) }
    single { LedgerRepository(get(), get(), get(AppScope)) }

    viewModelOf(::LoginViewModel)
    viewModelOf(::HomeViewModel)
    viewModelOf(::TransactionsViewModel)
    viewModelOf(::AccountsViewModel)
    viewModelOf(::IncomeViewModel)
    viewModelOf(::CategoriesViewModel)
    viewModel { (id: Long, direction: Direction) -> TransactionEditorViewModel(id, direction, get()) }
    viewModel { (id: Long) -> AccountEditorViewModel(id, get()) }
    viewModel { (id: Long) -> IncomeEditorViewModel(id, get()) }
}

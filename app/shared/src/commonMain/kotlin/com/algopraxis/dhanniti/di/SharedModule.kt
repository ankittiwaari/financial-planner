package com.algopraxis.dhanniti.di

import com.algopraxis.dhanniti.data.api.AuthApi
import com.algopraxis.dhanniti.data.api.Connectivity
import com.algopraxis.dhanniti.data.api.LedgerApi
import com.algopraxis.dhanniti.data.api.LendingApi
import com.algopraxis.dhanniti.data.api.ObligationsApi
import com.algopraxis.dhanniti.data.api.createHttpClient
import com.algopraxis.dhanniti.data.auth.AuthRepository
import com.algopraxis.dhanniti.data.auth.DataStoreTokenStore
import com.algopraxis.dhanniti.data.auth.TokenStore
import com.algopraxis.dhanniti.data.db.AppDatabase
import com.algopraxis.dhanniti.data.ledger.LedgerRepository
import com.algopraxis.dhanniti.data.ledger.LendingRepository
import com.algopraxis.dhanniti.data.ledger.ObligationsRepository
import com.algopraxis.dhanniti.data.reminders.ReminderCoordinator
import com.algopraxis.dhanniti.data.reminders.RemindersRepository
import com.algopraxis.dhanniti.data.sync.OutboxRepository
import com.algopraxis.dhanniti.data.sync.SyncApi
import com.algopraxis.dhanniti.data.sync.SyncRepository
import com.algopraxis.dhanniti.domain.Direction
import com.algopraxis.dhanniti.ui.accounts.AccountEditorViewModel
import com.algopraxis.dhanniti.ui.accounts.AccountsViewModel
import com.algopraxis.dhanniti.ui.cards.CardEditorViewModel
import com.algopraxis.dhanniti.ui.cards.CardsViewModel
import com.algopraxis.dhanniti.ui.categories.CategoriesViewModel
import com.algopraxis.dhanniti.ui.dues.DuesViewModel
import com.algopraxis.dhanniti.ui.home.HomeViewModel
import com.algopraxis.dhanniti.ui.income.IncomeEditorViewModel
import com.algopraxis.dhanniti.ui.income.IncomeViewModel
import com.algopraxis.dhanniti.ui.lending.LendingEditorViewModel
import com.algopraxis.dhanniti.ui.lending.LentMoneyViewModel
import com.algopraxis.dhanniti.ui.lending.PersonViewModel
import com.algopraxis.dhanniti.ui.loans.LoanDetailViewModel
import com.algopraxis.dhanniti.ui.loans.LoanEditorViewModel
import com.algopraxis.dhanniti.ui.loans.LoansViewModel
import com.algopraxis.dhanniti.ui.login.LoginViewModel
import com.algopraxis.dhanniti.ui.recurring.RecurringEditorViewModel
import com.algopraxis.dhanniti.ui.recurring.RecurringViewModel
import com.algopraxis.dhanniti.ui.reminders.RemindersViewModel
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
 * Platform modules must provide: HttpClientEngine, DataStore<Preferences>, AppDatabase, a ReminderScheduler, a
 * BackgroundSync and a String named [DeviceName]. Call [SyncRepository.start] and [ReminderCoordinator.start] once the
 * graph is up.
 */
fun sharedModule(apiBaseUrl: String, enableHttpLogging: Boolean = false): Module = module {
    single<CoroutineScope>(AppScope) { CoroutineScope(SupervisorJob() + Dispatchers.Default) }
    single<TokenStore> { DataStoreTokenStore(get()) }
    single { Connectivity() }
    single { createHttpClient(get(), apiBaseUrl, get(), enableHttpLogging, get()) }
    single { get<AppDatabase>().cacheDao() }
    single { get<AppDatabase>().outboxDao() }
    single { OutboxRepository(get(), get()) }
    singleOf(::SyncApi)
    single { get<AppDatabase>().userDao() }
    singleOf(::AuthApi)
    singleOf(::LedgerApi)
    singleOf(::ObligationsApi)
    singleOf(::LendingApi)
    single { AuthRepository(get(), get(), get(), get(DeviceName)) }
    single { LedgerRepository(get(), get(), get(AppScope), get()) }
    single { ObligationsRepository(get(), get(), get(), get(AppScope), get()) }
    single { LendingRepository(get(), get(), get(), get(AppScope)) }
    single { RemindersRepository(get(), get(), get(AppScope)) }
    single { ReminderCoordinator(get(), get(), get(), get(), get(AppScope)) }
    single { SyncRepository(get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(AppScope)) }

    viewModelOf(::LoginViewModel)
    viewModelOf(::HomeViewModel)
    viewModelOf(::TransactionsViewModel)
    viewModelOf(::AccountsViewModel)
    viewModelOf(::IncomeViewModel)
    viewModelOf(::CategoriesViewModel)
    viewModelOf(::DuesViewModel)
    viewModelOf(::LoansViewModel)
    viewModelOf(::CardsViewModel)
    viewModelOf(::RecurringViewModel)
    viewModelOf(::LentMoneyViewModel)
    viewModelOf(::RemindersViewModel)
    viewModel { (id: Long, direction: Direction) -> TransactionEditorViewModel(id, direction, get()) }
    viewModel { (id: Long) -> AccountEditorViewModel(id, get()) }
    viewModel { (id: Long) -> IncomeEditorViewModel(id, get()) }
    viewModel { (id: Long) -> LoanDetailViewModel(id, get(), get()) }
    viewModel { (id: Long) -> LoanEditorViewModel(id, get(), get()) }
    viewModel { (id: Long) -> CardEditorViewModel(id, get(), get()) }
    viewModel { (id: Long) -> RecurringEditorViewModel(id, get(), get()) }
    viewModel { (name: String) -> PersonViewModel(name, get(), get()) }
    viewModel { (id: Long, personName: String) -> LendingEditorViewModel(id, personName, get(), get()) }
}

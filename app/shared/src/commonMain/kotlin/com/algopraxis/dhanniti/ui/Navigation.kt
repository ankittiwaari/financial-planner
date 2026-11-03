package com.algopraxis.dhanniti.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.algopraxis.dhanniti.domain.Direction
import com.algopraxis.dhanniti.resources.Res
import com.algopraxis.dhanniti.resources.ic_bank
import com.algopraxis.dhanniti.resources.ic_calendar
import com.algopraxis.dhanniti.resources.ic_home
import com.algopraxis.dhanniti.resources.ic_income
import com.algopraxis.dhanniti.resources.ic_list
import com.algopraxis.dhanniti.ui.accounts.AccountEditorScreen
import com.algopraxis.dhanniti.ui.accounts.AccountsScreen
import com.algopraxis.dhanniti.ui.cards.CardEditorScreen
import com.algopraxis.dhanniti.ui.cards.CardsScreen
import com.algopraxis.dhanniti.ui.categories.CategoriesScreen
import com.algopraxis.dhanniti.ui.components.AppIcon
import com.algopraxis.dhanniti.ui.components.SyncBanner
import com.algopraxis.dhanniti.ui.dues.DuesScreen
import com.algopraxis.dhanniti.ui.home.HomeScreen
import com.algopraxis.dhanniti.ui.income.IncomeEditorScreen
import com.algopraxis.dhanniti.ui.income.IncomeScreen
import com.algopraxis.dhanniti.ui.lending.LendingEditorScreen
import com.algopraxis.dhanniti.ui.lending.LentMoneyScreen
import com.algopraxis.dhanniti.ui.lending.PersonScreen
import com.algopraxis.dhanniti.ui.loans.LoanDetailScreen
import com.algopraxis.dhanniti.ui.loans.LoanEditorScreen
import com.algopraxis.dhanniti.ui.loans.LoansScreen
import com.algopraxis.dhanniti.ui.recurring.RecurringEditorScreen
import com.algopraxis.dhanniti.ui.recurring.RecurringScreen
import com.algopraxis.dhanniti.ui.reminders.RemindersScreen
import com.algopraxis.dhanniti.ui.transactions.TransactionEditorScreen
import com.algopraxis.dhanniti.ui.transactions.TransactionsScreen
import kotlinx.serialization.Serializable
import org.jetbrains.compose.resources.DrawableResource
import kotlin.reflect.KClass

@Serializable data object HomeRoute
@Serializable data object TransactionsRoute
@Serializable data object AccountsRoute
@Serializable data object IncomeRoute
@Serializable data object CategoriesRoute
@Serializable data object DuesRoute
@Serializable data object LoansRoute
@Serializable data object CardsRoute
@Serializable data object RecurringRoute
@Serializable data object LentMoneyRoute
@Serializable data object RemindersRoute

/** [id] 0 means "new". */
@Serializable data class TransactionEditorRoute(val id: Long = 0, val direction: Direction = Direction.DEBIT)
@Serializable data class AccountEditorRoute(val id: Long = 0)
@Serializable data class IncomeEditorRoute(val id: Long = 0)
@Serializable data class LoanDetailRoute(val id: Long)
@Serializable data class LoanEditorRoute(val id: Long = 0)
@Serializable data class CardEditorRoute(val id: Long = 0)
@Serializable data class RecurringEditorRoute(val id: Long = 0)
@Serializable data class PersonRoute(val name: String)
/** [personName] pre-fills a new lending. */
@Serializable data class LendingEditorRoute(val id: Long = 0, val personName: String = "")

private data class Tab(val route: Any, val routeClass: KClass<*>, val label: String, val icon: DrawableResource)

private val tabs = listOf(
    Tab(HomeRoute, HomeRoute::class, "Home", Res.drawable.ic_home),
    Tab(TransactionsRoute, TransactionsRoute::class, "Transactions", Res.drawable.ic_list),
    Tab(DuesRoute, DuesRoute::class, "Dues", Res.drawable.ic_calendar),
    Tab(AccountsRoute, AccountsRoute::class, "Accounts", Res.drawable.ic_bank),
    Tab(IncomeRoute, IncomeRoute::class, "Income", Res.drawable.ic_income),
)

/** Signed-in app: bottom tabs plus full-screen editors (which hide the tab bar). */
@Composable
fun MainNavHost() {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val destination = backStackEntry?.destination
    val onTab = tabs.any { tab -> destination?.hierarchy?.any { it.hasRoute(tab.routeClass) } == true }

    Scaffold(
        // Each screen draws its own top bar and insets; this scaffold only reserves room for the tab bar.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            Column {
                SyncBanner()
                if (onTab) NavigationBar {
                    tabs.forEach { tab ->
                        val selected = destination?.hierarchy?.any { it.hasRoute(tab.routeClass) } == true
                        NavigationBarItem(
                            selected = selected,
                            onClick = { navController.switchTab(tab.route) },
                            icon = { AppIcon(tab.icon, contentDescription = null, tint = androidx.compose.material3.LocalContentColor.current) },
                            label = { Text(tab.label) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(navController, startDestination = HomeRoute, modifier = Modifier.padding(padding)) {
            composable<HomeRoute> {
                HomeScreen(
                    onAddTransaction = { navController.navigate(TransactionEditorRoute(direction = it)) },
                    onOpenTransaction = { navController.navigate(TransactionEditorRoute(id = it)) },
                    onSeeAllTransactions = { navController.switchTab(TransactionsRoute) },
                    onOpenIncome = { navController.switchTab(IncomeRoute) },
                    onAddAccount = { navController.navigate(AccountEditorRoute()) },
                    onOpenLentMoney = { navController.navigate(LentMoneyRoute) },
                    onOpenDues = { navController.switchTab(DuesRoute) },
                    onOpenReminders = { navController.navigate(RemindersRoute) },
                )
            }
            composable<TransactionsRoute> {
                TransactionsScreen(
                    onAdd = { navController.navigate(TransactionEditorRoute(direction = it)) },
                    onOpen = { navController.navigate(TransactionEditorRoute(id = it)) },
                    onOpenCategories = { navController.navigate(CategoriesRoute) },
                )
            }
            composable<AccountsRoute> {
                AccountsScreen(
                    onAdd = { navController.navigate(AccountEditorRoute()) },
                    onOpen = { navController.navigate(AccountEditorRoute(id = it)) },
                )
            }
            composable<IncomeRoute> {
                IncomeScreen(
                    onAdd = { navController.navigate(IncomeEditorRoute()) },
                    onOpen = { navController.navigate(IncomeEditorRoute(id = it)) },
                    onAddAccount = { navController.navigate(AccountEditorRoute()) },
                )
            }
            composable<DuesRoute> {
                DuesScreen(
                    onOpenLoans = { navController.navigate(LoansRoute) },
                    onOpenCards = { navController.navigate(CardsRoute) },
                    onOpenRecurring = { navController.navigate(RecurringRoute) },
                    onOpenReminders = { navController.navigate(RemindersRoute) },
                )
            }
            composable<LoansRoute> {
                LoansScreen(
                    onBack = navController::popBackStack,
                    onAdd = { navController.navigate(LoanEditorRoute()) },
                    onOpen = { navController.navigate(LoanDetailRoute(it)) },
                    onAddAccount = { navController.navigate(AccountEditorRoute()) },
                )
            }
            composable<LoanDetailRoute> { entry ->
                val id = entry.toRoute<LoanDetailRoute>().id
                LoanDetailScreen(
                    id = id,
                    onBack = navController::popBackStack,
                    onEdit = { navController.navigate(LoanEditorRoute(id)) },
                )
            }
            composable<LoanEditorRoute> { entry ->
                LoanEditorScreen(
                    id = entry.toRoute<LoanEditorRoute>().id,
                    onDone = navController::popBackStack,
                    onAddAccount = { navController.navigate(AccountEditorRoute()) },
                )
            }
            composable<CardsRoute> {
                CardsScreen(
                    onBack = navController::popBackStack,
                    onAdd = { navController.navigate(CardEditorRoute()) },
                    onOpen = { navController.navigate(CardEditorRoute(it)) },
                    onAddAccount = { navController.navigate(AccountEditorRoute()) },
                )
            }
            composable<CardEditorRoute> { entry ->
                CardEditorScreen(
                    id = entry.toRoute<CardEditorRoute>().id,
                    onDone = navController::popBackStack,
                    onAddAccount = { navController.navigate(AccountEditorRoute()) },
                )
            }
            composable<RecurringRoute> {
                RecurringScreen(
                    onBack = navController::popBackStack,
                    onAdd = { navController.navigate(RecurringEditorRoute()) },
                    onOpen = { navController.navigate(RecurringEditorRoute(it)) },
                    onAddAccount = { navController.navigate(AccountEditorRoute()) },
                )
            }
            composable<RecurringEditorRoute> { entry ->
                RecurringEditorScreen(
                    id = entry.toRoute<RecurringEditorRoute>().id,
                    onDone = navController::popBackStack,
                    onAddAccount = { navController.navigate(AccountEditorRoute()) },
                )
            }
            composable<LentMoneyRoute> {
                LentMoneyScreen(
                    onBack = navController::popBackStack,
                    onAdd = { navController.navigate(LendingEditorRoute()) },
                    onOpenPerson = { navController.navigate(PersonRoute(it)) },
                )
            }
            composable<PersonRoute> { entry ->
                PersonScreen(
                    name = entry.toRoute<PersonRoute>().name,
                    onBack = navController::popBackStack,
                    onAdd = { navController.navigate(LendingEditorRoute(personName = it)) },
                    onEdit = { navController.navigate(LendingEditorRoute(id = it)) },
                )
            }
            composable<LendingEditorRoute> { entry ->
                val route = entry.toRoute<LendingEditorRoute>()
                LendingEditorScreen(id = route.id, personName = route.personName, onDone = navController::popBackStack)
            }
            composable<RemindersRoute> {
                RemindersScreen(onBack = navController::popBackStack)
            }
            composable<CategoriesRoute> {
                CategoriesScreen(onBack = navController::popBackStack)
            }
            composable<TransactionEditorRoute> { entry ->
                val route = entry.toRoute<TransactionEditorRoute>()
                TransactionEditorScreen(id = route.id, direction = route.direction, onDone = navController::popBackStack)
            }
            composable<AccountEditorRoute> { entry ->
                AccountEditorScreen(id = entry.toRoute<AccountEditorRoute>().id, onDone = navController::popBackStack)
            }
            composable<IncomeEditorRoute> { entry ->
                IncomeEditorScreen(
                    id = entry.toRoute<IncomeEditorRoute>().id,
                    onDone = navController::popBackStack,
                    onAddAccount = { navController.navigate(AccountEditorRoute()) },
                )
            }
        }
    }
}

/** Standard bottom-tab behaviour: one copy of each tab, state kept when switching. */
private fun NavHostController.switchTab(route: Any) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

package com.algopraxis.dhanniti.ui

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
import com.algopraxis.dhanniti.resources.ic_home
import com.algopraxis.dhanniti.resources.ic_income
import com.algopraxis.dhanniti.resources.ic_list
import com.algopraxis.dhanniti.ui.accounts.AccountEditorScreen
import com.algopraxis.dhanniti.ui.accounts.AccountsScreen
import com.algopraxis.dhanniti.ui.categories.CategoriesScreen
import com.algopraxis.dhanniti.ui.components.AppIcon
import com.algopraxis.dhanniti.ui.home.HomeScreen
import com.algopraxis.dhanniti.ui.income.IncomeEditorScreen
import com.algopraxis.dhanniti.ui.income.IncomeScreen
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

/** [id] 0 means "new". */
@Serializable data class TransactionEditorRoute(val id: Long = 0, val direction: Direction = Direction.DEBIT)
@Serializable data class AccountEditorRoute(val id: Long = 0)
@Serializable data class IncomeEditorRoute(val id: Long = 0)

private data class Tab(val route: Any, val routeClass: KClass<*>, val label: String, val icon: DrawableResource)

private val tabs = listOf(
    Tab(HomeRoute, HomeRoute::class, "Home", Res.drawable.ic_home),
    Tab(TransactionsRoute, TransactionsRoute::class, "Transactions", Res.drawable.ic_list),
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
            if (onTab) {
                NavigationBar {
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

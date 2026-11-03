package com.algopraxis.dhanniti.data.reminders

import com.algopraxis.dhanniti.data.api.ObligationsApi
import com.algopraxis.dhanniti.data.auth.TokenStore
import com.algopraxis.dhanniti.data.ledger.ObligationsRepository
import com.algopraxis.dhanniti.domain.PlannedReminder
import com.algopraxis.dhanniti.domain.ReminderPlanner
import com.algopraxis.dhanniti.domain.ReminderSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlin.time.Clock

/** Puts planned reminders on the device's scheduler. Each platform provides one. */
interface ReminderScheduler {
    /** Replaces every scheduled due reminder with [reminders]. */
    fun schedule(reminders: List<PlannedReminder>)

    /** Removes every scheduled reminder, e.g. on sign-out. */
    fun cancelAll()
}

/** For platforms (and tests) without notifications. */
object NoReminders : ReminderScheduler {
    override fun schedule(reminders: List<PlannedReminder>) = Unit
    override fun cancelAll() = Unit
}

/** The signed-in user's reminder preference. */
class RemindersRepository(private val api: ObligationsApi, tokenStore: TokenStore, scope: CoroutineScope) {
    private val _settings = MutableStateFlow<ReminderSettings?>(null)

    /** Null until loaded. */
    val settings: StateFlow<ReminderSettings?> = _settings.asStateFlow()

    init {
        scope.launch { tokenStore.tokenFlow.collect { if (it == null) _settings.value = null } }
    }

    suspend fun refresh() {
        _settings.value = api.reminderSettings()
    }

    /** From a /sync snapshot (or the cached one). */
    fun applySnapshot(settings: ReminderSettings) {
        _settings.value = settings
    }

    suspend fun save(settings: ReminderSettings): ReminderSettings =
        api.updateReminderSettings(settings).also { _settings.value = it }
}

/**
 * Keeps the device's scheduled reminders in step with the dues the app has loaded and the reminder settings.
 * Platforms also replan in the background (Android: a periodic worker), so reminders stay current without the app.
 */
class ReminderCoordinator(
    private val obligations: ObligationsRepository,
    private val reminders: RemindersRepository,
    private val scheduler: ReminderScheduler,
    private val tokenStore: TokenStore,
    private val scope: CoroutineScope,
    private val clock: Clock = Clock.System,
) {
    @OptIn(FlowPreview::class)
    fun start() {
        scope.launch {
            tokenStore.tokenFlow.collect { token ->
                if (token == null) {
                    scheduler.cancelAll()
                } else {
                    try {
                        reminders.refresh()
                        obligations.refreshDues()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        // Offline: what's already scheduled stays; the next refresh or background sync replans.
                    }
                }
            }
        }
        scope.launch {
            // Only once dues have really loaded: the empty list before the first fetch would cancel everything.
            combine(obligations.duesLoaded, obligations.dues, reminders.settings.filterNotNull()) { loaded, dues, settings ->
                if (loaded) dues to settings else null
            }
                .filterNotNull()
                .debounce(500)
                .collect { (dues, settings) ->
                    scheduler.schedule(ReminderPlanner.plan(dues, settings, clock.now(), TimeZone.currentSystemDefault()))
                }
        }
    }
}

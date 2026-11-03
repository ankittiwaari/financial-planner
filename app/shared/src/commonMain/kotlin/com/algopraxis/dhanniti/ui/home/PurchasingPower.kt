package com.algopraxis.dhanniti.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.algopraxis.dhanniti.domain.Dashboard
import com.algopraxis.dhanniti.domain.DashboardAlert
import com.algopraxis.dhanniti.domain.Due
import com.algopraxis.dhanniti.domain.Money
import com.algopraxis.dhanniti.domain.formatInr
import com.algopraxis.dhanniti.domain.formatRelative
import com.algopraxis.dhanniti.domain.formatShort
import com.algopraxis.dhanniti.ui.components.SectionLabel
import kotlinx.datetime.LocalDate

/** One slice of the cycle's income: where it goes. */
private data class Slice(val label: String, val amount: Money, val color: Color)

/** The headline: what's left to spend this cycle, per day, and where the income goes. */
@Composable
fun PurchasingPowerCard(dashboard: Dashboard, onOpenDues: () -> Unit) {
    val negative = dashboard.purchasingPower.isNegative
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (negative) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer,
        ),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Column {
                SectionLabel("You can spend this cycle")
                Text(dashboard.purchasingPower.formatInr(), style = MaterialTheme.typography.displaySmall)
                Text(
                    if (negative) "Your dues and spending are more than this cycle's income"
                    else "${dashboard.perDayLeft.formatInr()} a day for ${dayCount(dashboard.cycle.daysLeft)}",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "${dashboard.cycle.start.formatShort()} – ${dashboard.cycle.end.formatShort()}" +
                        if (dashboard.cycle.mode.name == "PAYDAY") " · payday to payday" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Breakdown(dashboard, onOpenDues)
        }
    }
}

@Composable
private fun Breakdown(dashboard: Dashboard, onOpenDues: () -> Unit) {
    val dues = dashboard.reservedForDues
    val slices = listOf(
        Slice("Dues", dues.total, MaterialTheme.colorScheme.tertiary),
        Slice("Spent", dashboard.spentSoFar, MaterialTheme.colorScheme.secondary),
        Slice("Lent", dashboard.lentOut, MaterialTheme.colorScheme.outline),
        Slice("Left", if (dashboard.purchasingPower.isNegative) Money.ZERO else dashboard.purchasingPower, MaterialTheme.colorScheme.primary),
    ).filter { it.amount > Money.ZERO }
    val whole = slices.fold(0L) { sum, it -> sum + it.amount.paise }

    if (whole > 0) {
        Row(
            Modifier.fillMaxWidth().height(12.dp).clip(RoundedCornerShape(6.dp)),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            slices.forEach { slice ->
                Box(Modifier.weight(slice.amount.paise.toFloat() / whole).fillMaxHeight().background(slice.color))
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        BreakdownRow(
            "Income",
            dashboard.income.total,
            detail = when {
                dashboard.income.expected > dashboard.income.credited ->
                    "${dashboard.income.credited.formatInr()} of ${dashboard.income.expected.formatInr()} salary in"
                dashboard.income.other > Money.ZERO -> "includes ${dashboard.income.other.formatInr()} other"
                else -> null
            },
        )
        BreakdownRow(
            "Dues",
            -dues.total,
            color = MaterialTheme.colorScheme.tertiary,
            detail = listOfNotNull(
                dues.emis.takeIf { it > Money.ZERO }?.let { "EMIs ${it.formatInr()}" },
                dues.cards.takeIf { it > Money.ZERO }?.let { "cards ${it.formatInr()}" },
                dues.recurring.takeIf { it > Money.ZERO }?.let { "recurring ${it.formatInr()}" },
                dues.overdue.takeIf { it > Money.ZERO }?.let { "${it.formatInr()} overdue" },
            ).joinToString(" · ").ifEmpty { null },
            onClick = onOpenDues,
        )
        BreakdownRow("Spent", -dashboard.spentSoFar, color = MaterialTheme.colorScheme.secondary)
        if (dashboard.lentOut > Money.ZERO) {
            BreakdownRow("Lent out", -dashboard.lentOut, color = MaterialTheme.colorScheme.outline)
        }
        HorizontalDivider(Modifier.padding(vertical = 4.dp))
        BreakdownRow("Left to spend", dashboard.purchasingPower, color = MaterialTheme.colorScheme.primary, bold = true)
    }
}

@Composable
private fun BreakdownRow(
    label: String,
    amount: Money,
    color: Color? = null,
    detail: String? = null,
    bold: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(10.dp).clip(CircleShape)
                .background(color ?: Color.Transparent),
        )
        Column(Modifier.weight(1f).padding(start = 8.dp)) {
            Text(label, style = MaterialTheme.typography.bodyMedium, fontWeight = if (bold) FontWeight.SemiBold else null)
            detail?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (onClick != null) {
            TextButton(onClick = onClick) { Text("See") }
        }
        Text(
            (if (amount.isNegative) "−" else "") + Money(kotlin.math.abs(amount.paise)).formatInr(),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (bold) FontWeight.SemiBold else null,
        )
    }
}

/** Things to act on. Automatic entries can be confirmed here, or opened to change them. */
@Composable
fun AlertsCard(alerts: List<DashboardAlert>, onConfirm: (Long) -> Unit, onOpenTransaction: (Long) -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            SectionLabel("To do")
            alerts.forEach { alert ->
                Text("• ${alert.message}", style = MaterialTheme.typography.bodyMedium)
                alert.transactionId?.let { id ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(start = 12.dp)) {
                        FilledTonalButton(onClick = { onConfirm(id) }) { Text("Looks right") }
                        TextButton(onClick = { onOpenTransaction(id) }) { Text("Change") }
                    }
                }
            }
        }
    }
}

@Composable
fun NextDuesCard(dues: List<Due>, today: LocalDate, onOpenDues: () -> Unit) {
    Card(onClick = onOpenDues, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionLabel("Coming up")
            dues.forEach { due ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(due.sourceType.emoji, modifier = Modifier.padding(end = 8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(due.title, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            due.dueDate.formatRelative(today) + if (due.isEstimate) " · estimate" else "",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(due.amount.formatInr(), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

private fun dayCount(days: Int) = if (days == 1) "1 more day" else "$days more days"

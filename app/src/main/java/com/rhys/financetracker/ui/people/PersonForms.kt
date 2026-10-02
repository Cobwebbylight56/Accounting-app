package com.rhys.financetracker.ui.people

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.rhys.financetracker.core.money.Money
import com.rhys.financetracker.core.time.DateUtils
import com.rhys.financetracker.data.local.projection.AccountWithBalance
import com.rhys.financetracker.domain.income.PayRise
import com.rhys.financetracker.domain.loan.LoanMaths
import com.rhys.financetracker.domain.model.AccountType
import com.rhys.financetracker.ui.components.AmountField
import com.rhys.financetracker.ui.components.DateField
import com.rhys.financetracker.ui.components.DropdownField
import com.rhys.financetracker.ui.components.LabelledTextField
import com.rhys.financetracker.ui.components.SegmentedChoice
import java.time.LocalDate

/*
 * The small forms a person is built from — an account, a loan, a pay rise —
 * shared by the first-run setup and each person's own page, so both ask the
 * same questions the same way.
 */

/** An account being typed in. */
data class AccountDraft(
    val type: AccountType = AccountType.CURRENT,
    val name: String = "",
    val balanceText: String = "",
    /** Set once the name is typed, after which the type no longer renames it. */
    val nameTouched: Boolean = false,
)

/** The kinds offered for an account; loans have their own form. */
val ACCOUNT_KINDS = listOf(
    AccountType.CURRENT,
    AccountType.SAVINGS,
    AccountType.CREDIT_CARD,
    AccountType.PAY_LATER,
    AccountType.INVESTMENT,
    AccountType.PENSION,
    AccountType.OTHER,
)

@Composable
fun AccountDraftForm(
    draft: AccountDraft,
    onChange: (AccountDraft) -> Unit,
    onAdd: () -> Unit,
    enabled: Boolean = true,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ACCOUNT_KINDS.forEach { type ->
                FilterChip(
                    selected = draft.type == type,
                    onClick = {
                        onChange(
                            draft.copy(
                                type = type,
                                name = if (draft.nameTouched) draft.name else "",
                            ),
                        )
                    },
                    label = { Text(type.displayName) },
                )
            }
        }
        LabelledTextField(
            label = "Account name",
            value = draft.name,
            onValueChange = { onChange(draft.copy(name = it, nameTouched = true)) },
            placeholder = draft.type.displayName,
            supportingText = "What the bank calls it, e.g. Nationwide FlexDirect or Start to Save.",
        )
        AmountField(
            label = if (draft.type.defaultHolding == com.rhys.financetracker.domain.model.Holding.OWED) {
                "Owed on it today"
            } else {
                "In it today"
            },
            value = draft.balanceText,
            onValueChange = { onChange(draft.copy(balanceText = it)) },
        )
        Button(onClick = onAdd, enabled = enabled, modifier = Modifier.fillMaxWidth().height(48.dp)) {
            Text("Add this account")
        }
    }
}

/** What sort of borrowing it is; it decides the account type and the wording. */
enum class LoanKind(val label: String, val accountType: AccountType, val example: String) {
    LOAN("Loan", AccountType.LOAN, "Personal loan, catalogue…"),
    MORTGAGE("Mortgage", AccountType.MORTGAGE, "Nationwide mortgage…"),
    CAR("Car finance", AccountType.LOAN, "Car finance, PCP, HP…"),
}

/** A loan being typed in. */
data class LoanDraft(
    val kind: LoanKind = LoanKind.LOAN,
    val name: String = "",
    val owedText: String = "",
    val originalText: String = "",
    val rateText: String = "",
    val yearsText: String = "",
    val monthsText: String = "",
    val monthlyText: String = "",
    val dayText: String = "1",
    val payFromAccountId: Long? = null,
) {
    val owedMinor: Long? get() = Money.parseOrNull(owedText)?.takeIf { it > 0L }

    /** The yearly interest rate, or none typed. */
    val ratePercent: Double? get() = rateText.replace("%", "").trim().toDoubleOrNull()?.takeIf { it >= 0.0 }

    /** The time left in months, from the years and months typed. */
    val monthsLeft: Int? get() {
        val total = (yearsText.toIntOrNull() ?: 0) * 12 + (monthsText.toIntOrNull() ?: 0)
        return total.takeIf { it > 0 }
    }

    /** What the monthly payment works out at from the amount, rate and time left. */
    val workedOutMonthly: Long? get() {
        val owed = owedMinor ?: return null
        val months = monthsLeft ?: return null
        return LoanMaths.monthlyPayment(owed, ratePercent ?: 0.0, months)
    }

    /** The monthly payment: as typed, or worked out when only the time left was given. */
    val monthlyMinor: Long? get() = Money.parseOrNull(monthlyText)?.takeIf { it > 0L } ?: workedOutMonthly

    /** The name it is saved under. */
    val savedName: String get() = name.trim().ifEmpty { kind.label }
}

/**
 * Adding a loan, mortgage or car finance: what is left, the interest rate,
 * the monthly payment and the time left — the time left works the payment
 * out — with a live summary of when it will be clear and what the
 * interest will come to.
 */
@Composable
fun LoanDraftForm(
    draft: LoanDraft,
    payFrom: List<AccountWithBalance>,
    onChange: (LoanDraft) -> Unit,
    onAdd: () -> Unit,
    enabled: Boolean = true,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SegmentedChoice(
            options = LoanKind.entries,
            selected = draft.kind,
            onSelect = { onChange(draft.copy(kind = it)) },
            optionLabel = { it.label },
        )
        LabelledTextField(
            label = "What is it called?",
            value = draft.name,
            onValueChange = { onChange(draft.copy(name = it)) },
            placeholder = draft.kind.example,
        )
        AmountField(
            label = "How much is left to pay",
            value = draft.owedText,
            onValueChange = { onChange(draft.copy(owedText = it)) },
        )
        LabelledTextField(
            label = "Interest rate (% a year)",
            value = draft.rateText,
            onValueChange = { text -> onChange(draft.copy(rateText = text.filter { it.isDigit() || it == '.' }.take(6))) },
            placeholder = if (draft.kind == LoanKind.MORTGAGE) "e.g. 4.5" else "e.g. 6.9 — on your agreement",
            keyboardType = KeyboardType.Decimal,
        )
        Text("Time left", style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            LabelledTextField(
                label = "Years",
                value = draft.yearsText,
                onValueChange = { text -> onChange(draft.copy(yearsText = text.filter { it.isDigit() }.take(2))) },
                keyboardType = KeyboardType.Number,
                modifier = Modifier.weight(1f),
            )
            LabelledTextField(
                label = "Months",
                value = draft.monthsText,
                onValueChange = { text -> onChange(draft.copy(monthsText = text.filter { it.isDigit() }.take(2))) },
                keyboardType = KeyboardType.Number,
                modifier = Modifier.weight(1f),
            )
        }
        AmountField(
            label = "Paid each month",
            value = draft.monthlyText,
            onValueChange = { onChange(draft.copy(monthlyText = it)) },
        )
        val worked = draft.workedOutMonthly
        if (worked != null && Money.parseOrNull(draft.monthlyText) != worked) {
            TextButton(onClick = { onChange(draft.copy(monthlyText = Money.formatPlain(worked))) }) {
                Text("Use ${Money.format(worked)} a month — worked out from the time left")
            }
        }

        LoanSummary(draft)

        if (draft.monthlyMinor != null) {
            LabelledTextField(
                label = "Day of the month it's paid",
                value = draft.dayText,
                onValueChange = { text ->
                    onChange(draft.copy(dayText = text.filter { it.isDigit() }.take(2)))
                },
                keyboardType = KeyboardType.Number,
            )
            if (payFrom.isNotEmpty()) {
                DropdownField(
                    label = "Paid from",
                    options = payFrom,
                    selected = payFrom.firstOrNull { it.account.id == draft.payFromAccountId },
                    onSelect = { onChange(draft.copy(payFromAccountId = it.account.id)) },
                    optionLabel = { it.account.name },
                    placeholder = "Choose an account",
                )
                Text(
                    text = "Each month's payment comes off what is owed by itself. When it " +
                        "reaches nothing, the loan disappears and the payment stops.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        AmountField(
            label = "Borrowed at the start (optional)",
            value = draft.originalText,
            onValueChange = { onChange(draft.copy(originalText = it)) },
        )
        Button(onClick = onAdd, enabled = enabled && draft.owedMinor != null, modifier = Modifier.fillMaxWidth().height(48.dp)) {
            Text("Add this ${draft.kind.label.lowercase()}")
        }
    }
}

/** When it will be clear and what the interest comes to, as the numbers are typed. */
@Composable
private fun LoanSummary(draft: LoanDraft) {
    val owed = draft.owedMinor ?: return
    val monthly = draft.monthlyMinor ?: return
    val rate = draft.ratePercent ?: 0.0
    val months = LoanMaths.monthsToClear(owed, rate, monthly)
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (months == null) {
                Text(
                    text = "${Money.format(monthly)} a month doesn't cover the interest " +
                        "(${Money.format(LoanMaths.interestThisMonth(owed, rate))} a month) — it would never be paid off.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                return@Column
            }
            val clear = DateUtils.currentYearMonth().plusMonths(months.toLong())
            Text(
                text = "Clear by ${DateUtils.formatMonth(clear)}",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "$months ${if (months == 1) "payment" else "payments"} of ${Money.format(monthly)}" +
                    (if (months >= 12) " — ${months / 12} years ${months % 12} months" else ""),
                style = MaterialTheme.typography.bodyMedium,
            )
            if (rate > 0.0) {
                val interest = LoanMaths.interestToPay(owed, rate, monthly) ?: 0L
                Text(
                    text = "${Money.format(interest)} of that is interest · " +
                        "${Money.format(LoanMaths.interestThisMonth(owed, rate))} of this month's payment",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    text = "Add the interest rate to see what the interest comes to.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** A pay rise being typed in. */
data class PayRiseDraft(
    val given: PayRise.Given = PayRise.Given.PERCENT,
    val valueText: String = "",
    val newNetText: String = "",
    val reason: String = "Yearly review",
    val effectiveDate: LocalDate = DateUtils.today(),
)

val PAY_RISE_REASONS = listOf("Yearly review", "Pay rise", "Promotion", "New job", "Pay cut")

@Composable
fun PayRiseForm(
    draft: PayRiseDraft,
    currentGrossMinor: Long?,
    onChange: (PayRiseDraft) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PAY_RISE_REASONS.forEach { reason ->
                FilterChip(
                    selected = draft.reason == reason,
                    onClick = { onChange(draft.copy(reason = reason)) },
                    label = { Text(reason) },
                )
            }
        }
        val options = if (currentGrossMinor == null) {
            listOf(PayRise.Given.NEW_PAY)
        } else {
            PayRise.Given.entries
        }
        DropdownField(
            label = "How was it given?",
            options = options,
            selected = draft.given.takeIf { it in options } ?: PayRise.Given.NEW_PAY,
            onSelect = { onChange(draft.copy(given = it)) },
            optionLabel = { it.displayName },
        )
        val given = draft.given.takeIf { it in options } ?: PayRise.Given.NEW_PAY
        LabelledTextField(
            label = when (given) {
                PayRise.Given.PERCENT -> "Rise (%)"
                PayRise.Given.AMOUNT -> "Extra a year (£)"
                PayRise.Given.NEW_PAY -> "New yearly pay before tax (£)"
            },
            value = draft.valueText,
            onValueChange = { onChange(draft.copy(valueText = it)) },
            keyboardType = KeyboardType.Decimal,
        )
        draft.valueText.replace(",", "").toDoubleOrNull()
            ?.let { PayRise.newGross(currentGrossMinor, given, it) }
            ?.let { newGross ->
                Text(
                    text = "New pay before tax: ${Money.format(newGross)} a year",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        AmountField(
            label = "New take-home a year (optional)",
            value = draft.newNetText,
            onValueChange = { onChange(draft.copy(newNetText = it)) },
        )
        Text(
            text = "Leave take-home blank and it's estimated from what you take home now.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        DateField(
            label = "Starts from",
            date = draft.effectiveDate,
            onDateChange = { onChange(draft.copy(effectiveDate = it)) },
        )
    }
}

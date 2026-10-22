package com.rhys.financetracker.data.live

import com.rhys.financetracker.domain.model.TransactionType

/**
 * One payment told twice: how an alert is matched to a payment an alert
 * already added.
 *
 * - The same app posting its alert again — an update, or after a restart —
 *   within a few minutes.
 * - A wallet or payment app and the bank, for one tap or one PayPal payment:
 *   up to a day apart, as the bank's alert can lag.
 * - Two banks telling of one move between your own accounts: money out of
 *   one and the same into the other, minutes apart. That is one move, not
 *   spending at one and income at the other.
 */
object LiveTwins {

    /** A payment added from an alert, as far as matching needs it. */
    data class Row(
        val id: Long,
        /** "live:<app>:…", the alert it came from. */
        val hash: String,
        val type: TransactionType,
        val accountId: Long,
        val createdAt: Long,
        val amountMinor: Long = 0L,
    ) {
        val app: String get() = hash.split(':').getOrNull(1).orEmpty()
    }

    /** The payment [app]'s alert, of [type] money, tells of again — the latest — or null. */
    fun sameAs(app: String, type: TransactionType, now: Long, rows: List<Row>): Row? =
        rows.filter { row ->
            val ago = now - row.createdAt
            sameWay(row.type, type) && when {
                ago > TOLD_TWICE_MILLIS -> false
                ago <= SAME_PAYMENT_MILLIS -> true
                row.app == app -> false
                else -> app in BankApps.ALSO_TOLD_BY_BANK || row.app in BankApps.ALSO_TOLD_BY_BANK
            }
        }.maxByOrNull { it.createdAt }

    /**
     * The other half of a move between two of your own accounts — money out
     * of one told by its bank, the same into [accountId] told by another, or
     * the other way round — or null. [own] is the accounts money can move
     * between: not cards and loans, whose payments are matched apart.
     */
    fun otherSide(app: String, type: TransactionType, accountId: Long, now: Long, rows: List<Row>, own: Set<Long>): Row? {
        if (type == TransactionType.TRANSFER || accountId !in own || app in BankApps.ALSO_TOLD_BY_BANK) return null
        val opposite = if (type == TransactionType.INCOME) TransactionType.EXPENSE else TransactionType.INCOME
        return rows.filter { row ->
            row.hash.startsWith("live:") && row.type == opposite && row.app != app && row.app !in BankApps.ALSO_TOLD_BY_BANK &&
                row.accountId != accountId && row.accountId in own &&
                now - row.createdAt <= MOVE_MILLIS
        }.maxByOrNull { it.createdAt }
    }

    /**
     * A payment added twice by an earlier version: [extra] is put away and
     * [kept] stays. With [moveFrom], the two were a move told by both banks,
     * and [kept] becomes that move, with [moveFrom]'s account at its other end.
     */
    data class Doubled(val extra: Long, val kept: Long, val moveFrom: Long? = null)

    /**
     * Payments from alerts that are one payment told twice, by the same rules
     * as an alert as it arrives: from the oldest, each against those kept
     * before it. Of a wallet's and the bank's, the bank's is kept — it knows
     * the account.
     */
    fun doubled(rows: List<Row>, own: Set<Long>): List<Doubled> {
        val kept = mutableListOf<Row>()
        val found = mutableListOf<Doubled>()
        for (row in rows.filter { it.hash.startsWith("live:") }.sortedWith(compareBy({ it.createdAt }, { it.id }))) {
            val earlier = kept.filter { it.amountMinor == row.amountMinor }
            val same = sameAs(row.app, row.type, row.createdAt, earlier)
            if (same != null) {
                if (same.app in BankApps.ALSO_TOLD_BY_BANK && row.app !in BankApps.ALSO_TOLD_BY_BANK) {
                    found += Doubled(extra = same.id, kept = row.id)
                    kept.remove(same)
                    kept += row
                } else {
                    found += Doubled(extra = row.id, kept = same.id)
                }
                continue
            }
            val other = otherSide(row.app, row.type, row.accountId, row.createdAt, earlier, own)
            if (other != null) {
                found += Doubled(extra = row.id, kept = other.id, moveFrom = row.accountId)
                kept.remove(other)
                kept += other.copy(type = TransactionType.TRANSFER)
                continue
            }
            kept += row
        }
        return found
    }

    /**
     * Money the same way: out and out, in and in. A move — a card paid off,
     * or a move made of two alerts — is told either way.
     */
    private fun sameWay(stored: TransactionType, told: TransactionType) =
        stored == told || stored == TransactionType.TRANSFER

    /** Two alerts for one payment arrive well within this, whichever apps. */
    const val SAME_PAYMENT_MILLIS = 3 * 60 * 1000L

    /** How far apart a bank and a wallet or payment app may tell of one payment. */
    const val TOLD_TWICE_MILLIS = 24 * 60 * 60 * 1000L

    /** How far apart two banks tell of one move between them. */
    const val MOVE_MILLIS = 20 * 60 * 1000L
}

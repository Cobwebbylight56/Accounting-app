package com.rhys.financetracker.ui.components

import com.rhys.financetracker.data.local.entity.AccountEntity
import com.rhys.financetracker.data.local.entity.PersonEntity
import com.rhys.financetracker.domain.model.AccountType
import com.rhys.financetracker.domain.model.Holding

/** Who each account belongs to, as the picker's tabs. */
internal class AccountOwners private constructor(
    val groups: List<Group>,
    private val individuals: Set<Long>,
    private val shared: Set<Long>,
) {
    data class Group(val key: String, val label: String, val colorHex: String?)

    fun keyOf(account: AccountEntity?): String? = when {
        account == null -> null
        account.isShared || account.personId in shared -> JOINT
        account.personId in individuals -> "p:${account.personId}"
        else -> OTHER
    }

    companion object {
        private const val JOINT = "joint"
        private const val OTHER = "other"

        /**
         * [account]'s name, with whose it is when another account shares the
         * name: "Lloyds Current Account · Rhys".
         */
        fun nameOf(account: AccountEntity, accounts: List<AccountEntity>, people: List<PersonEntity>): String {
            if (accounts.count { it.name.equals(account.name, ignoreCase = true) } < 2) return account.name
            val owner = when {
                account.isShared -> "Joint"
                else -> people.firstOrNull { it.id == account.personId }?.name?.substringBefore(' ')
            }
            return owner?.let { "${account.name} · $it" } ?: account.name
        }

        /** A current account or a card: what a payment comes out of. */
        fun paysFrom(account: AccountEntity): Boolean =
            account.holding == Holding.SPEND || account.type == AccountType.CREDIT_CARD || account.type == AccountType.PAY_LATER

        fun of(people: List<PersonEntity>, accounts: List<AccountEntity>): AccountOwners {
            val individuals = people.filterNot { it.isShared }
            val shared = people.filter { it.isShared }.map { it.id }.toSet()
            val owners = AccountOwners(emptyList(), individuals.map { it.id }.toSet(), shared)
            val used = accounts.mapNotNull { owners.keyOf(it) }.toSet()
            val groups = buildList {
                individuals.forEach { person ->
                    if ("p:${person.id}" in used) add(Group("p:${person.id}", person.name.substringBefore(' '), person.colorHex))
                }
                if (JOINT in used) add(Group(JOINT, "Joint", null))
                if (OTHER in used) add(Group(OTHER, "Other", null))
            }
            return AccountOwners(groups, owners.individuals, owners.shared)
        }
    }
}

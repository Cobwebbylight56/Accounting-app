package com.rhys.financetracker.data.importer

import com.rhys.financetracker.domain.model.TransactionType

/**
 * Where an entry the app filed by itself belongs now, by what the user has
 * filed and the built-in list of shops.
 *
 * Only ever asked about entries without the hidden "category set by user"
 * mark; those with it are never moved. See TidyUpRepository.resort.
 */
object Refiling {

    /** Categories that say only that a card was used. */
    val VAGUE = listOf("Card spending")

    /** Categories too loose to move anything into from a specific one: a transfer could be anything. */
    val WEAK = listOf("Transfers & payments", "People & services")

    /**
     * The category [description] should be under instead of [current], or
     * null to leave it where it is.
     *
     * What the user filed the same payee under comes first. The built-in
     * list answers for money out only: income is wages and people, which a
     * shop list knows nothing about.
     */
    fun better(
        description: String,
        type: TransactionType,
        current: String?,
        learned: Map<String, String>,
    ): String? {
        val fromUser = MerchantCategoriser.learnedCategory(description, learned)
        val decided = fromUser
            ?: if (type == TransactionType.EXPENSE) MerchantCategoriser.categoryFor(description, type) else null
        if (decided == null || decided in VAGUE) return null
        if (current != null && decided.equals(current, ignoreCase = true)) return null
        // The list calling it a transfer is not reason enough to take it out
        // of somewhere specific; the user saying so is.
        if (fromUser == null && current != null && current !in VAGUE && decided in WEAK) return null
        return decided
    }
}

package com.rhys.financetracker.data

import com.rhys.financetracker.data.importer.SubscriptionKinds
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubscriptionKindsTest {

    @Test
    fun `streaming, apps and memberships are subscriptions`() {
        assertTrue(SubscriptionKinds.isSubscription("Audible Adbl Co", "Subscriptions"))
        assertTrue(SubscriptionKinds.isSubscription("Anthropic Claude Sub", "Subscriptions"))
        assertTrue(SubscriptionKinds.isSubscription("Netflix.com", "Entertainment"))
        assertTrue(SubscriptionKinds.isSubscription("Uber One", null))
        assertTrue(SubscriptionKinds.isSubscription("Spotify P1234", "Card spending"))
    }

    @Test
    fun `services the household needs are bills`() {
        assertFalse(SubscriptionKinds.isSubscription("Utility Warehouse", "Energy"))
        assertFalse(SubscriptionKinds.isSubscription("Ncc Collections Ac", "Council tax"))
        assertFalse(SubscriptionKinds.isSubscription("Dvla", "Road tax"))
        assertFalse(SubscriptionKinds.isSubscription("Scottish Widows", "Life insurance"))
        assertFalse(SubscriptionKinds.isSubscription("L G Insurance", "Insurance"))
        assertFalse(SubscriptionKinds.isSubscription("Pc Goskippy Ins", "Car insurance"))
        assertFalse(SubscriptionKinds.isSubscription("Virgin Media Pymts", "Broadband"))
        // Filed under Subscriptions, but a person: a service like a window cleaner.
        assertFalse(SubscriptionKinds.isSubscription("Peter Roche", "Subscriptions"))
        // Filed under Subscriptions, but plainly a bill.
        assertFalse(SubscriptionKinds.isSubscription("Sky Digital", "Subscriptions"))
    }
}

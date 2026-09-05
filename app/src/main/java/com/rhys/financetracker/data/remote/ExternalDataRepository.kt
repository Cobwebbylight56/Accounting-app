package com.rhys.financetracker.data.remote

import com.rhys.financetracker.core.result.AppResult
import com.rhys.financetracker.core.result.runCatchingApp
import com.rhys.financetracker.core.time.DateUtils
import com.rhys.financetracker.data.local.dao.ExternalDataDao
import com.rhys.financetracker.data.local.entity.ExternalDataEntity
import com.rhys.financetracker.domain.model.ExternalDataKey
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONObject

/**
 * Keeps externally sourced figures up to date.
 *
 * ## What can and cannot be automated
 *
 * Only two of the figures the specification asks for have a free, key-free,
 * documented API:
 *
 *  * **Exchange rates** — the Frankfurter service, which republishes the
 *    European Central Bank's daily reference rates.
 *  * **UK bank holidays** — GOV.UK publishes the official calendar as JSON.
 *
 * The others do not, and the app says so rather than pretending otherwise:
 *
 *  * **Bank of England base rate** — published on the Bank's website and in its
 *    statistical database, but only as a CSV/HTML interface intended for
 *    interactive use, not a stable JSON endpoint.
 *  * **CPI inflation** — the ONS API requires a dataset/edition/version path
 *    that changes with each release, so a hard-coded URL would break silently.
 *  * **Fuel prices** — the CMA's open fuel-price scheme publishes one file per
 *    retailer rather than a national average.
 *  * **Energy price cap** — Ofgem publishes it as a spreadsheet and a press
 *    release, four times a year.
 *
 * For those four the app stores a value the user types in, records that it was
 * entered by hand and when, and reminds them to check it.  Scraping the pages
 * instead was rejected deliberately: a scraper breaks without warning and then
 * shows a wrong number, which is worse than an honest manual figure.  Adding a
 * proper source later means adding one `refresh…` function here; nothing else
 * changes.
 */
@Singleton
class ExternalDataRepository @Inject constructor(
    private val httpClient: HttpClient,
    private val externalDataDao: ExternalDataDao,
) {

    private companion object {
        /** ECB reference rates, republished without an API key. */
        const val EXCHANGE_RATE_URL = "https://api.frankfurter.app/latest?from=GBP&to=EUR,USD"
        const val EXCHANGE_RATE_SOURCE = "European Central Bank via frankfurter.app"

        /** The official UK bank holiday calendar. */
        const val BANK_HOLIDAY_URL = "https://www.gov.uk/bank-holidays.json"
        const val BANK_HOLIDAY_SOURCE = "GOV.UK"
        const val BANK_HOLIDAY_REGION = "england-and-wales"

        const val MANUAL_SOURCE = "Entered by hand"
    }

    fun observeAll(): Flow<List<ExternalDataEntity>> = externalDataDao.observeAll()

    /** The figures grouped so the UI can separate live ones from manual ones. */
    fun observeGrouped(): Flow<ExternalDataSnapshot> =
        externalDataDao.observeAll().map { entries ->
            val byKey = entries.associateBy { it.key }
            ExternalDataSnapshot(
                automatic = ExternalDataKey.entries
                    .filter { it.hasAutomaticSource }
                    .map { ExternalDataItem(it, byKey[it.key]) },
                manual = ExternalDataKey.entries
                    .filterNot { it.hasAutomaticSource }
                    .map { ExternalDataItem(it, byKey[it.key]) },
            )
        }

    /** Refreshes everything that has an automatic source. */
    suspend fun refreshAll(): AppResult<Int> = runCatchingApp("Could not update the figures") {
        var updated = 0
        if (refreshExchangeRates() is AppResult.Success) updated += 2
        if (refreshBankHolidays() is AppResult.Success) updated += 1
        updated
    }

    suspend fun refreshExchangeRates(): AppResult<Unit> =
        when (val response = httpClient.getText(EXCHANGE_RATE_URL)) {
            is AppResult.Failure -> {
                recordFailure(ExternalDataKey.EXCHANGE_RATE_EUR, response.message)
                recordFailure(ExternalDataKey.EXCHANGE_RATE_USD, response.message)
                response
            }
            is AppResult.Success -> runCatchingApp("The exchange rate reply was not understood") {
                val rates = JSONObject(response.data).getJSONObject("rates")
                store(ExternalDataKey.EXCHANGE_RATE_EUR, rates.getDouble("EUR").toString(),
                    EXCHANGE_RATE_SOURCE)
                store(ExternalDataKey.EXCHANGE_RATE_USD, rates.getDouble("USD").toString(),
                    EXCHANGE_RATE_SOURCE)
            }
        }

    suspend fun refreshBankHolidays(): AppResult<Unit> =
        when (val response = httpClient.getText(BANK_HOLIDAY_URL)) {
            is AppResult.Failure -> {
                recordFailure(ExternalDataKey.NEXT_BANK_HOLIDAY, response.message)
                response
            }
            is AppResult.Success -> runCatchingApp("The bank holiday reply was not understood") {
                val events = JSONObject(response.data)
                    .getJSONObject(BANK_HOLIDAY_REGION)
                    .getJSONArray("events")
                val today = DateUtils.today()
                var next: Pair<LocalDate, String>? = null

                for (index in 0 until events.length()) {
                    val event = events.getJSONObject(index)
                    val date = DateUtils.parseIsoOrNull(event.optString("date")) ?: continue
                    if (date.isBefore(today)) continue
                    if (next == null || date.isBefore(next.first)) {
                        next = date to event.optString("title")
                    }
                }

                if (next == null) {
                    recordFailure(
                        ExternalDataKey.NEXT_BANK_HOLIDAY,
                        "No future bank holidays were listed",
                    )
                } else {
                    store(
                        key = ExternalDataKey.NEXT_BANK_HOLIDAY,
                        value = "${next.second} — ${DateUtils.format(next.first)}",
                        source = BANK_HOLIDAY_SOURCE,
                    )
                }
            }
        }

    /** Saves a figure the user typed in themselves. */
    suspend fun setManualValue(key: ExternalDataKey, value: String): AppResult<Unit> =
        runCatchingApp("Could not save this figure") {
            externalDataDao.upsert(
                ExternalDataEntity(
                    key = key.key,
                    value = value.trim(),
                    unit = key.unit,
                    source = MANUAL_SOURCE,
                    isManual = true,
                ),
            )
        }

    private suspend fun store(key: ExternalDataKey, value: String, source: String) {
        externalDataDao.upsert(
            ExternalDataEntity(
                key = key.key,
                value = value,
                unit = key.unit,
                source = source,
                isManual = false,
            ),
        )
    }

    /**
     * Keeps the last good value but records why the refresh failed, so the UI
     * can show "last updated 3 days ago — could not reach the service" instead
     * of a blank.
     */
    private suspend fun recordFailure(key: ExternalDataKey, message: String) {
        val existing = externalDataDao.getByKey(key.key)
        externalDataDao.upsert(
            existing?.copy(lastError = message)
                ?: ExternalDataEntity(
                    key = key.key,
                    value = "",
                    unit = key.unit,
                    source = "",
                    isManual = false,
                    lastError = message,
                ),
        )
    }
}

/** One external figure and its stored value, if it has one. */
data class ExternalDataItem(
    val key: ExternalDataKey,
    val stored: ExternalDataEntity?,
) {
    val hasValue: Boolean get() = !stored?.value.isNullOrBlank()
    val displayValue: String get() = stored?.value?.takeIf { it.isNotBlank() } ?: "Not set"

    /** Explains where the number came from, in plain words. */
    val provenance: String
        get() = when {
            stored == null -> if (key.hasAutomaticSource) {
                "Not fetched yet"
            } else {
                "No public feed — enter this yourself"
            }
            stored.lastError != null && !hasValue -> "Could not update: ${stored.lastError}"
            stored.isManual -> "Entered by hand"
            else -> "From ${stored.source}"
        }
}

/** External figures split into those the app can fetch and those it cannot. */
data class ExternalDataSnapshot(
    val automatic: List<ExternalDataItem> = emptyList(),
    val manual: List<ExternalDataItem> = emptyList(),
)

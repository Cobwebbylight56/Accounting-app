# External data — what is possible and what is not

The specification asked for live figures where they are available, using APIs
rather than scraping, and for an honest explanation plus a manual alternative
where they are not. This is that explanation.

The short version: **two of the eight figures have a reliable free API. The
other six do not, so the app asks you for them and says so on screen.**

---

## Fetched automatically

### Exchange rates — GBP to EUR and USD

- **Source:** the European Central Bank's daily reference rates, republished by
  [Frankfurter](https://frankfurter.dev) at
  `https://api.frankfurter.app/latest?from=GBP&to=EUR,USD`.
- **Why this one:** no API key, no registration, no rate limit worth worrying
  about, a documented stable JSON shape, and the underlying data is the ECB's
  rather than a commercial aggregator's.
- **Updated:** once a day, on wi-fi only.

### UK bank holidays

- **Source:** `https://www.gov.uk/bank-holidays.json` — the official calendar,
  published by GOV.UK as JSON specifically for reuse.
- **What the app shows:** the next bank holiday for England and Wales. Useful
  because a payment due on a bank holiday usually leaves the account the working
  day before or after.

Both are off until you turn them on (**Settings → Rates and figures**), and both
run on unmetered connections only, so they never spend your mobile data.

---

## Entered by hand, and why

| Figure | Why there is no usable feed |
|---|---|
| **Bank of England base rate** | The Bank publishes it on its website and in its statistical database, but the database is an interactive CSV/HTML interface meant for people, not a stable JSON endpoint with a contract. A URL built against today's interface would break without warning. |
| **CPI inflation** | The ONS API addresses a dataset by edition and version, and those change with every release. A hard-coded URL returns 404 after the next publication; discovering the current one needs several calls and still guesses at the series. |
| **Petrol and diesel prices** | The CMA's open fuel-price scheme publishes one file per retailer, at a URL per retailer, in per-retailer formats. There is no official national average, and averaging a subset of retailers would produce a number that looks authoritative and is not. |
| **Energy price cap** | Ofgem publishes it as a spreadsheet and a press release, four times a year. There is no API at all. |

### Why not scrape the pages?

Because a scraper does not fail loudly. When a page changes, a scraper either
throws — which at least you notice — or, far more often, quietly extracts the
wrong number and keeps showing it. A budgeting app that displays a stale or
wrong mortgage rate with the confidence of a live feed is worse than one that
says "you last set this in March".

So for these six, the app:

- lets you type the value in (**Settings → Rates and figures**, tap one);
- records that it was entered by hand and when;
- shows that on screen next to the value, so you always know what you are
  looking at.

Each one takes ten seconds to update, a few times a year.

---

## Where to get the manual figures

| Figure | Where |
|---|---|
| Bank of England base rate | <https://www.bankofengland.co.uk/monetary-policy/the-interest-rate-bank-rate> |
| CPI inflation | <https://www.ons.gov.uk/economy/inflationandpriceindices> |
| Petrol and diesel prices | Your own receipts, or the RAC and AA monthly reports |
| Energy price cap | <https://www.ofgem.gov.uk/energy-price-cap> |

---

## Adding a source later

If one of these gains a proper API, wiring it in is a small, contained change.
In `data/remote/ExternalDataRepository.kt`:

```kotlin
suspend fun refreshBaseRate(): AppResult<Unit> =
    when (val response = httpClient.getText(BASE_RATE_URL)) {
        is AppResult.Failure -> {
            recordFailure(ExternalDataKey.BANK_OF_ENGLAND_BASE_RATE, response.message)
            response
        }
        is AppResult.Success -> runCatchingApp("The reply was not understood") {
            val rate = JSONObject(response.data).getDouble("rate")
            store(ExternalDataKey.BANK_OF_ENGLAND_BASE_RATE, rate.toString(), "Bank of England")
        }
    }
```

Then call it from `refreshAll()`, and flip `hasAutomaticSource` to `true` on
that constant in `ExternalDataKey`. The figure moves from the "entered by hand"
list to the "fetched automatically" list by itself; no UI change is needed.

---

## Privacy

- Nothing about your finances is ever sent anywhere. The two requests are plain
  GETs to public endpoints and carry no identifier beyond a generic user agent.
- Both are HTTPS only — `HttpClient` refuses any other scheme, so a figure
  cannot be altered in transit by something on the network.
- The whole feature is off by default. Turning it off cancels the scheduled job
  as well as hiding the card.

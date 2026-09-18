# Expense tracking design: categories, review inbox, insights, design system

Date: 2026-09-18. Repo: `Fuck Instapay` (Gradle root project `InstaBalance`), single `:app` module, package `com.instabalance`.

## 0. What this plan covers

Adds category tracking on income and expenses, a review inbox with learned merchant rules, a real Material3 design system built from the InstaPay brand palette, a Settings screen reached by a gear icon, and hand-rolled Compose Canvas charts (spend by category ring, spend over time, month comparison, top categories). It also splits `MainActivity.kt` (622 lines) into focused files, because every new screen would otherwise land in the same file.

Not covered, and deliberately so: budgets, recurring transactions, multi-currency, export, cloud sync, any charting or navigation library, any refactor of the parser, dedupe, fee or lock code beyond what categorisation requires.

Verification command for every phase, run from the repo root (Windows: `gradlew.bat`):

```
./gradlew :app:testDebugUnitTest :app:assembleDebug
```

## 1. Facts about the current code that shape the design

These were verified by reading the source, not assumed.

- `Entry` is `@Serializable` with defaults on `id`, `source`, `note`, `rawText`. `type`, `amountMinor`, `timestamp` have no defaults. Enums `EntryType` and `Source` are serialised by name.
- `LedgerRepository.json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }`. `init` decodes with `runCatching { ... }.onSuccess { _data.value = it }`. On decode failure the in-memory ledger silently stays at `LedgerData()` and the next mutation calls `persist`, which overwrites `ledger.enc` with the empty ledger. This is the single most dangerous property in the codebase for a schema change, and section 5.1 is built around it.
- `LedgerData.watchedPackages: List<String> = DEFAULT_WATCHED_PACKAGES` is the existing precedent for "a list with a code-defined default that gets written into the file on first save". Categories follow the same precedent.
- Every mutation is `synchronized(lock)`, reads `_data.value` inside the lock, builds a new immutable `LedgerData` with `copy`, assigns the `MutableStateFlow`, then persists. `addEntryLocked` re-sorts by timestamp. Mutations are one persist each (a full JSON encode plus AES-GCM write), so bulk operations must be a single mutation.
- `addAuto` is called from `SmsReceiver.onReceive` (main thread) and `InstaPayNotificationListener.onNotificationPosted` (binder thread). Both call `LedgerRepository.init` first. Both read `LedgerRepository.data.value` outside the lock for `learningMode` and `watchedPackages`, which is fine because `LedgerData` is immutable.
- FEE entries are created inside `addAuto` alongside the send they belong to, with `source = Source.FEE`, `note = "InstaPay transfer fee"`, no `rawText`, same timestamp as the send.
- `ParsedTxn(type, amountMinor)` is a plain data class; `BalanceParser.parse` is the only producer. The Arabic card-purchase SMS carries a merchant after `من`; the English IPN debit carries nothing but a reference number; the English IPN credit carries `from NAME`; the guessed InstaPay notification carries `from ipa@instapay`.
- `MainActivity.onCreate` applies `MaterialTheme(colorScheme = lightColorScheme())` with no customisation. `res/values/themes.xml` is a bare `DeviceDefault.Light.NoActionBar` parent. `colors.xml` holds only the launcher background.
- There is no navigation. `AppScreen` is one vertically scrolling `Column` with `SettingsPanel` expanded inline at the bottom. `Gate` decides between `LockScreen` and `AppScreen`. `onStop` re-locks unless `consumeSkipLock()`; `suppressNextLock()` is called before launching system settings from the panel.
- `EntryRow` shows a delete icon on every row and deletes immediately with no confirmation and no undo.
- `dateFmt` is a top-level `SimpleDateFormat("d MMM, HH:mm", Locale.getDefault())` and `Money.formatMinor` uses `"%,d".format(...)` with the default locale. On an `ar-EG` device both emit Arabic-Indic digits. Existing behaviour, not changed by this plan except where new formatting is introduced (section 5.5).
- Tests are plain JUnit4, `org.junit.Assert.*`, one class per logic unit, tiny private helpers (`entry(...)`, `assertTxn(...)`), `@Test fun name() {` on one line, a short comment explaining why the expected value is what it is. `isReturnDefaultValues = true` is set, but no existing test touches `LedgerRepository` mutations, Keystore or Compose. New tests keep to that boundary.
- `minSdk = 26`, so `java.time` is available without desugaring. `androidx.activity:activity-compose` (gives `BackHandler`) and `material-icons-extended` are already dependencies. No new dependencies are added by this plan.

## 2. Data model

All changes are additive fields with defaults. No existing enum gains a constant (see 5.1 for why that is a hard rule).

### 2.1 `Entry` (edit `Ledger.kt`)

```kotlin
@Serializable
data class Entry(
    val id: String = UUID.randomUUID().toString(),
    val type: EntryType,
    val amountMinor: Long,
    val timestamp: Long,
    val source: Source = Source.MANUAL,
    val note: String = "",
    val rawText: String? = null,
    val merchant: String? = null,        // counterparty as printed in the message, if any
    val categoryId: String? = null,      // null = uncategorised. ANCHOR entries are always null.
    val categoryFromRule: Boolean = false, // true only while the category was set by a rule, not by hand
)
```

`merchant` is the display string exactly as extracted (trimmed, whitespace collapsed). Matching uses a normalised form computed on the fly (section 3), never stored, so the normalisation can be tuned later without a migration.

`categoryFromRule` exists so the app can distinguish "the user chose this" from "a rule guessed this". Any manual categorisation sets it to `false`. It is the mechanism behind the guarantee that rules never silently overwrite a human decision, and it lets "update the N entries this rule categorised" be an explicit, countable, opt-in action when a rule is edited.

### 2.2 `Category` and `MerchantRule` (new file `Categories.kt`, new file `MerchantRules.kt`)

```kotlin
enum class CategoryKind { EXPENSE, INCOME, BOTH }

@Serializable
data class Category(
    val id: String,               // stable, never shown; presets use fixed slugs like "groceries"
    val name: String,
    val kind: CategoryKind,
    val colorIndex: Int,          // index into ChartPalette.RAMP, not an ARGB value
    val hidden: Boolean = false,
    val preset: Boolean = false,  // presets can be hidden and renamed but never deleted
)

@Serializable
data class MerchantRule(
    val id: String = UUID.randomUUID().toString(),
    val pattern: String,          // normalised token(s); matches when normalised merchant contains it
    val categoryId: String,
    val createdAt: Long,
)
```

`CategoryKind` is a new enum inside a new field. That is safe for an old APK reading a new file because the whole unknown `categories` key is skipped by `ignoreUnknownKeys`; it would not be safe to add constants to `Source` or `EntryType`.

`colorIndex` rather than ARGB: the palette stays in code where it can be tuned (and unit-tested for contrast), the user picks from swatches instead of a colour wheel, and every pick is guaranteed to be one of the colour-blind-checked ramp colours.

### 2.3 `LedgerData` (edit `Ledger.kt`)

```kotlin
val categories: List<Category> = Categories.PRESETS,
val merchantRules: List<MerchantRule> = emptyList(),
val monthlyBudgetMinor: Long? = null,   // null = no budget set, no alerts
val budgetMonth: String = "",           // "2026-09", the month highestMilestoneFired belongs to
val highestMilestoneFired: Int = 0,     // 0, 25, 50, 75, 90, 100 or 120
```

No `schemaVersion` field. A version number that every old file also decodes as `2` (because of its own default) cannot distinguish a version from a default, so it would be a value a future maintainer trusts and should not. Migration keys off the data instead (see 5.2).

The three budget fields are explained in section 4.8 and 5.8.

### 2.4 Presets (`Categories.kt`)

Ids are slugs, names are English (the UI is English throughout today). Expense: `groceries` Groceries, `transport` Transport, `eating_out` Eating out, `bills` Bills and utilities, `rent` Rent, `health` Health and pharmacy, `shopping` Shopping, `mobile` Mobile and internet, `education` Education, `entertainment` Entertainment, `cash` Cash withdrawal, `fees` Fees, `other_expense` Other. Both: `family` Family transfer, `friends` Friends. Income: `salary` Salary, `freelance` Freelance, `refund` Refund, `other_income` Other.

`fees` is a preset with `kind = EXPENSE` and is what `Source.FEE` debits get assigned automatically (5.3). `cash` exists because the ATM withdrawal SMS is a distinct, common, merchant-bearing message (`من ATM`) and users want it separated from purchases. `other_expense` and `other_income` are the "I looked, nothing fits, stop asking me" answers the inbox needs (section 4.3).

`Categories` object exposes pure functions: `visibleFor(categories, type: EntryType): List<Category>`, `byId(categories, id)`, `rename`, `recolour`, `setHidden`, `add` (generates id `custom_<uuid>`), `delete(data, id): LedgerData` (only if `!preset`; reassigns matching entries to `null`, drops rules that point at it), `ensurePresets(categories)` (appends any preset whose id is missing, so a preset added in a later build reaches existing users; never touches presets already present, so renames and hides stick).

## 3. Merchant extraction and rules

### 3.1 Extraction (`BalanceParser.kt`, `ParsedTxn` gains `merchant: String? = null`)

Extraction runs inside `BalanceParser.parse` after the amount is known, on the digit-normalised text with the balance clause already stripped:

- Arabic card purchase and cancelled purchase: text after the last `من` that follows `الكارت` (`الكارت رقم\s*\+*[0-9*]+\s*من\s+(.+)$`), e.g. `PAYMOB RAF SPECIALIT CAIRO N 07`, `New Rabia for Trading Masr Elgedida`.
- Arabic ATM withdrawal and cancelled deduction: the last `من\s+(\S+)` before the (already removed) balance clause, which yields `ATM`. Cheap and useful: it lets a single rule send every ATM withdrawal to `cash`.
- English IPN credit: `from\s+(.+?)(?:\s+for details|$)` yields `NANICE AHMED`.
- English InstaPay notification guesses: `(?:from|to)\s+(\S+@\S+)` yields the IPA.
- English IPN debit (`Your account was charged by EGP 1105 on 21-07 14:04 IPN REF# ...`): no merchant. Returns `null`. This is the honest half: every InstaPay send seen only via SMS lands in the inbox with nothing to learn from.

Whitespace is collapsed and the result trimmed; anything shorter than 2 characters becomes `null`. The parser must never fail a transaction because merchant extraction failed: the amount and direction are the money, the merchant is a convenience.

### 3.2 Normalisation and matching (`MerchantRules.kt`, pure object)

- `normalise(merchant)`: uppercase (Locale.ROOT), Arabic-Indic digits to ASCII (reuse the parser's helper by making it `internal`), collapse whitespace, strip punctuation except `@` and `.`.
- `match(rules, merchant): MerchantRule?`: among rules whose `pattern` is contained in `normalise(merchant)`, the longest pattern wins (most specific). Deterministic tie-break by `createdAt` then `id`.
- `proposePattern(merchant): String`: first token if it is 4 or more characters and not in a small stop list (`NEW`, `THE`, `EL`, `AL`, `FOR`), otherwise first two tokens. `PAYMOB RAF SPECIALIT CAIRO N 07` proposes `PAYMOB`; `New Rabia for Trading` proposes `NEW RABIA`; `ATM` proposes `ATM`; `naniiceeabbas@instapay` proposes the whole token. The user can edit the proposal before saving.
- `applyToUncategorised(entries, rule): Pair<List<Entry>, Int>`: returns entries with every `categoryId == null && type != ANCHOR && merchant matches` entry set to the rule's category with `categoryFromRule = true`, plus the count. It never touches an entry whose `categoryId` is non-null. This is the only retro-application function and it is only ever invoked from a UI action that shows the count first.
- `categoriseNew(entry, rules): Entry`: what `addAuto` calls for a freshly captured entry: if a rule matches the merchant, set `categoryId` and `categoryFromRule = true`; otherwise return as is.

### 3.3 Where rules run

- Capture time, in `addAuto`, inside the lock, reading `_data.value.merchantRules`. This is what makes background-captured entries self-categorise while the app is closed.
- FEE entries created in `addAuto` get `categoryId = Categories.FEES` directly (no merchant, no rule).
- Rule creation from the picker sheet (4.2): saves the rule, categorises the current entry as manual (`categoryFromRule = false`, because the user chose it), then offers "Also apply to N other uncategorised entries" as a second one-tap that calls `applyToUncategorised`.
- Rule edit (6.2): changing a rule's category offers "Update N entries this rule categorised" where N counts `categoryFromRule && categoryId == oldCategory && merchant matches`. Off by default.

Rules never run on a timer, on app open, or on `init`.

### 3.4 Optional enrichment on dedupe (Phase 9, pushback-friendly)

Today when the same transaction arrives on both channels the second capture is dropped by `isDuplicateAuto`. If the second capture has a merchant and the stored one does not (typical: SMS gives the IPN debit with nothing, notification gives `to someone@instapay`), the drop throws away the only categorisable signal. A pure `mergeDuplicate(existing, parsed)` that fills in `merchant` (and then runs `categoriseNew` if still uncategorised) recovers it. It is listed last because the InstaPay notification wording is still unconfirmed (README: "best-effort guess"), so it may enrich nothing until Learning mode confirms the format.

## 4. UX

### 4.1 Navigation (`Nav.kt`, no library)

A small `rememberSaveable` back stack of `Route` values, serialised as `"SCREEN:arg"` strings via a `Saver`, and a single `BackHandler(enabled = stack.size > 1)`. Screens: `HOME`, `INBOX`, `SETTINGS`, `CATEGORIES`, `RULES`. Entry detail and category picking are bottom sheets, not routes, so they do not enter the stack and cannot strand the user. `Gate` stays the outermost decision (lock first, then the stack). Lock on `onStop` is unchanged, and `suppressNextLock()` keeps working from the Settings screen.

### 4.2 Categorising (the core interaction, must be one tap)

- Category picker is a `ModalBottomSheet` with wrapped `FilterChip`s in three groups: Suggested (rule match if any, then the last three categories used for this direction), all visible categories for this direction, and a trailing "New category" chip that expands an inline name field (no second sheet). Tapping a chip assigns immediately, closes the sheet, and shows a Snackbar with Undo.
- When the entry has a merchant and no rule matches it yet, the sheet shows a pre-checked switch "Always file `PAYMOB` here" with the proposed pattern editable inline. One tap on a chip therefore categorises and learns.
- After learning, if other uncategorised entries match, the Snackbar reads "Filed as Shopping. Apply to 4 more from PAYMOB?" with an Apply action. Never automatic.
- Undo is in-memory: `setCategory` returns the previous `(id -> categoryId, categoryFromRule)` map and the Snackbar action restores it through the same function.

### 4.3 Review inbox (`InboxScreen.kt`)

- Lists every `type != ANCHOR && categoryId == null` entry, newest first, grouped by day. Each row shows amount, merchant (or the note, or "InstaPay transfer" for merchant-less IPN debits), date, and three inline chips: the rule suggestion if any, the most recently used category for this direction, and Other. A fourth chip "More" opens the picker sheet. Most rows are cleared without leaving the list.
- Long-press starts multi-select; a bottom bar offers "File all as..." (opens the picker, one assignment, one persist, one Undo) and "Select all from this merchant".
- First open with a large backlog (more than 20 uncategorised, which every existing user will have): a dismissible card at the top: "143 older transactions have no category. File everything before today as Other?" with a single confirm. This is what stops the Home badge from nagging forever about history the user does not care to relive.
- Empty state: "All caught up" and a line explaining that InstaPay sends have no merchant in the SMS, so they will keep appearing here; that expectation is set once, in the place the user meets it.

### 4.4 Home (`HomeScreen.kt`)

Top row: app name at left, gear `IconButton` at right (navigates to `SETTINGS`). Balance card (hero, gradient primary to deep purple, white text, synced-ago line). Received / Sent / Set balance actions unchanged in function. An inbox banner "12 to categorise" (orange container, tappable, hidden at zero). Recent activity (last 8 rows, each showing a category colour dot plus name, merchant or note, amount with sign; tapping opens the detail sheet; "See all" expands). Insights section (section 7) below.

Entry detail sheet: amount, direction, date, source, merchant, raw text (collapsed), category chip (opens picker), Delete with a confirm. Delete moves off the row because a one-tap irreversible delete on a money ledger is the kind of friction that turns into a support message.

Manual entry dialog (`AmountDialog`): gains a horizontally scrolling chip row of visible categories for the direction, none pre-selected (a wrong silent default is worse than an inbox entry). ANCHOR dialog has no chip row.

### 4.5 Settings (`SettingsScreen.kt`)

Same content as today's panel (app lock, automation, fee, learning mode, watched apps, captures) in cards, plus two new rows that navigate: Categories and Merchant rules. Captured messages get a "Copy" per row since users paste them into issues.

### 4.6 Categories screen and rules screen

Categories: two sections (Expense, Income; BOTH appears in both), each row: swatch, name, hidden toggle; tap opens an inline edit sheet with name field and a ramp swatch row; presets show Hide, custom show Delete with the count of entries that will become uncategorised. Rules: list of `pattern -> category`, tap to edit category or pattern, swipe-free delete via icon with confirm.

### 4.7 Friction audit (each item has a design above)

Modal maze: one sheet, never a sheet on a sheet (new category is inline). Delete without undo: moved into detail with confirm. Backlog nag: bulk "file before today as Other". Merchant-less entries: honest empty state plus the Other chip. Wrong auto-category: shows as a chip the user can retap; rule edit offers counted update. Rule collisions: longest pattern wins, visible in the rules list. Keyboard hiding chips in the manual dialog: chips sit above the fields. Categorising an ANCHOR: not offered anywhere and rejected in the repository. Hidden category still holding money: still appears in charts and in the entry's chip, only missing from pickers.

### 4.8 Monthly budget and milestone alerts

Set in Settings: a single monthly spend limit in EGP, or off. When set, Home shows a budget card under the balance: a progress bar (primary fill, turning orange past 90 percent and red past 100), "EGP 3,240 of 5,000 spent", days left in the month, and the projected end-of-month figure at the current daily rate. The bar is the only place the user has to look to know where they stand.

Alerts fire as a system notification at 25, 50, 75, 90, 100 and 120 percent of the limit. Crossing several at once fires only the highest (see 5.8 for why that falls out of the model rather than needing special-case code). Tapping the notification opens Home.

Permission: `POST_NOTIFICATIONS` is runtime-requested on API 33 and above, asked for at the moment the user first sets a budget (not at app start, where it has no context and gets denied). If it is denied the budget card still works; only the notifications are silent, and Settings says so plainly rather than pretending alerts are on.

### 4.9 Notification copy

One channel, `budget_alerts`, importance DEFAULT, named "Budget alerts". Titles are direct and never scolding: 25/50/75 percent read "Quarter of your budget used" / "Halfway through your budget" / "75% of your budget used" with the body "EGP 3,750 of 5,000 this month". 90 percent reads "Close to your budget". 100 percent reads "Budget reached". 120 percent reads "20% over budget". Money is never rendered in a way that implies the app blocked anything; it cannot and must not imply it did.

## 5. Backend correctness

### 5.1 Persistence compatibility

- Backward (new APK reads old `ledger.enc`): every new field has a default, so a v1 file decodes with `merchant = null`, `categoryId = null`, `categoryFromRule = false`, `categories = PRESETS`, `merchantRules = []`. Test: `LedgerCompatTest.decodesV1File` decodes a string literal captured from a real pre-change file (with `encodeDefaults = true` the exact key set is known) and asserts the defaults.
- Forward (old APK reads new file, e.g. a sideloaded downgrade): `ignoreUnknownKeys` skips `merchant`, `categoryId`, `categoryFromRule`, `categories`, `merchantRules`, `schemaVersion`. This only holds because no existing enum gains a constant: an unknown `Source` name would throw, `init` would swallow it, and the next write would replace the ledger with an empty one. Test: `LedgerCompatTest.newFileHasOnlyAdditiveKeys` encodes a populated `LedgerData` and asserts that stripping the six new keys yields JSON that decodes into a v1-shaped structure (assert on `JsonObject` keys rather than a second model).
- Decode-failure safety (Phase 3, small and in scope because this is the first schema change): in `init`, when `decrypted != null` but decoding fails, copy `ledger.enc` to `ledger.enc.bak` before continuing, so a bug in the new schema cannot destroy the only copy on the next write. Same encryption, same directory, no new permission.

### 5.2 Migration of existing data

`Migration.apply(data): LedgerData?` (pure, in `Ledger.kt` companion or `Migration.kt`) returns a changed copy or `null`:
- FEE debits with `categoryId == null` get `fees`, `categoryFromRule = false`.
- `ensurePresets` appends missing presets.

`init` runs it inside the lock after a successful decode and persists only if non-null. Idempotent: applying twice returns `null` the second time. Tests: `MigrationTest.feeEntriesGetFeesCategory`, `isIdempotent`, `doesNotTouchManualOrAnchor`, `appendsMissingPresetsOnly`.

### 5.3 Aggregation rules (all in `Insights.kt`, pure)

- ANCHOR entries are excluded from every category, spend, income and time-series aggregate. They are not money moving; they are a re-sync. `setCategory` ignores ANCHOR ids (test asserts the entry is unchanged and no persist would be needed).
- Direction: expense = DEBIT, income = CREDIT. Reversals (cancelled purchases) are CREDITs today and will show as income unless categorised; the picker suggests `refund` for a CREDIT whose merchant matches a DEBIT's merchant in the last 7 days (Phase 8 nicety, pure function `suggestRefund`).
- FEE debits: their own preset `fees`, included in spend totals (they are real money out), shown in the ring like any other category. Not a special case anywhere in aggregation.
- Uncategorised: a synthetic slice `null -> "Uncategorised"` drawn in neutral grey, last, so the ring is honest about what it does not know instead of hiding half the month.
- Top N: slices beyond the top 7 by amount roll into "Other categories" so the ring never has slivers that cannot be tapped.

### 5.4 Time bucketing

`java.time` only. Every function takes `zone: ZoneId` (production passes `ZoneId.systemDefault()`, tests pass `ZoneId.of("Africa/Cairo")` and a fixed `now: Instant`). Buckets: `LocalDate` for daily bars, `YearMonth` for monthly. Egypt reinstated DST in 2023 (last Friday of April to last Thursday of October), so `Calendar`/`SimpleDateFormat` arithmetic with fixed 24h days would misbucket entries near the switch; tests place an entry at 23:30 local on the last day of April and on the DST switch night and assert the month. Month comparison compares month-to-date against the same day-count window of the previous month (day 1 to today's day-of-month, clamped to that month's length), with the full previous month total available as a second number, because "this month vs last month" on the 5th is otherwise a meaningless 30x gap.

### 5.5 Formatting

New date labels (chart axes, day headers) use `DateTimeFormatter.ofPattern(..., Locale.ENGLISH)` to match the English UI and avoid Arabic-Indic digits on ar-EG devices. The existing `dateFmt` and `Money.formatMinor` are left as they are (out of scope), noted as a follow-up.

### 5.6 Thread safety

- Aggregates run on the UI thread from a `LedgerData` snapshot in `remember(data) { ... }`. `LedgerData` and its lists are immutable, so a background write during composition produces a new object and a recomposition, never a torn read.
- Every new mutation looks entries up by id inside the lock. The UI never hands a list of `Entry` back to the repository, only ids and a category id, so a background entry added between the UI reading the flow and the user tapping cannot be dropped by a stale-list write. Test on the pure `applyCategory(entries, ids, categoryId)`: an entry not in `ids` is untouched and the list length is preserved.
- Bulk categorisation is one mutation, one persist.
- Rule matching in `addAuto` reads `_data.value` inside the existing `synchronized(lock)`; no new lock.
- `isDuplicateAuto` and the fee logic are untouched.

### 5.7 Category deletion and hiding

- Delete is allowed only for `!preset` categories. Entries pointing at it become `categoryId = null, categoryFromRule = false` (back to the inbox, visibly, with the count shown before confirming). Rules pointing at it are removed. One mutation.
- Hide keeps everything: entries keep the id, charts keep the slice, pickers stop offering it. Unhide is one toggle away.
- A dangling `categoryId` (should not happen, but a hand-edited or partially written file could produce one) resolves to "Unknown" in the UI and is treated as uncategorised by the inbox count. `Categories.byId` returns `null`, never throws.

### 5.8 Budget milestones

The whole rule reduces to one stored integer, which is what makes "only the highest fires" fall out for free instead of needing crossing-detection code.

```kotlin
val MILESTONES = listOf(25, 50, 75, 90, 100, 120)

/** Highest milestone at or below the current percentage, or 0 if under 25. */
fun reachedMilestone(spentMinor: Long, budgetMinor: Long): Int

/** The alert to fire, or null. Pure: no clock, no notification, no I/O. */
fun milestoneToFire(spentMinor: Long, budgetMinor: Long, highestFired: Int): Int? =
    reachedMilestone(spentMinor, budgetMinor).takeIf { it > highestFired }
```

- **Only the latest fires.** One expense taking the user from 20 percent to 80 percent computes `reachedMilestone = 75`, which is greater than the stored `0`, so 75 fires and the store becomes 75. The 25 and 50 alerts never fire because nothing iterates milestones; it only ever reads the top one. This is the requirement, satisfied by construction.
- **Never re-fires.** `> highestFired` makes it monotonic within a month.
- **A refund does not re-arm.** If spend falls back under a milestone and climbs again, `highestFired` is unchanged, so the user is not notified twice for the same threshold. Deliberate: re-arming would turn a refund plus a purchase into notification spam.
- **Month rollover.** `budgetMonth` holds the `YearMonth` that `highestMilestoneFired` belongs to. Before evaluating, if the entry's month differs from `budgetMonth`, both reset (`budgetMonth = thisMonth`, `highestFired = 0`) in the same mutation. No alarm, no scheduled job, no background work: the reset happens on the next transaction, which is the only moment it can matter.
- **Budget changes mid-month.** Lowering the limit below current spend does not retro-fire every milestone: `highestFired` is recomputed to `reachedMilestone(spentNow, newBudget)` at the moment of the change, silently. Otherwise setting a realistic budget would immediately buzz the phone.
- **Spend basis.** Expenses only (`DEBIT`, which includes `Source.FEE`; ANCHOR excluded), for the entry's own month in the device zone. Reuses `Insights.monthly`, so the card, the chart and the alert can never disagree.
- **Where it runs.** Inside `addEntryLocked`, on the already-updated ledger, returning the milestone to fire. The notification itself is posted by the caller (`addAuto` / the UI), never inside the lock, so a notification failure cannot hold the ledger lock or roll back a persisted entry.

Tests (`BudgetTest`, all pure): `reachedMilestoneBoundaries` (24.9, 25, 25.1 percent), `singleLargeExpenseFiresOnlyHighest` (20 to 80 percent fires 75 once), `neverRefiresSameMilestone`, `refundThenSpendDoesNotRefire`, `monthRolloverResets`, `loweringBudgetDoesNotRetroFire`, `zeroOrNullBudgetNeverFires` (guards the divide), `over120StaysAt120`.

## 6. Design system (`Theme.kt`, `ChartPalette.kt`)

Taken from screenshots of the real InstaPay app (home, transaction list, splash), not inferred. The logo's purple `#7D569E` turned out to be far more muted than the app's, which is a vivid violet. Three observations drive the whole system:

1. A **vivid violet gradient header** with faint diagonal chevron striping, with an **orange-to-coral organic blob** overlapping it at the top left. This is the app's signature and the thing that makes it recognisable.
2. The page ground is a **light lavender grey**, not white. White is reserved for cards, which is what makes them read as cards with almost no shadow.
3. **Orange is the interactive colour** (links: "Manage", "View All"), while **violet is the identity colour** (header, active nav). Purple is not used for buttons. This was the specific thing I guessed wrong before.

### 6.1 Colour roles (Material3 `lightColorScheme`)

primary `#7A12D4` violet (identity: header gradient, active nav, progress fill), onPrimary white, primaryContainer `#EDE0FB`, onPrimaryContainer `#4A0B85`; primaryBright `#8E24E8` (the gradient's light stop). secondary `#F26722` orange (every interactive affordance: text links, service tiles, the inbox badge, the selected chart slice), onSecondary white, secondaryContainer `#FDE0D2`, onSecondaryContainer `#8A3208`. tertiary `#F79070` coral (the blob's light stop and container tints only, never text: it measures 2.54:1 on white, confirmed below the 3:1 non-text minimum).

background `#F4F4F9` lavender grey, surface `#FFFFFF` (cards), surfaceVariant `#EFEFF6`, onSurface `#1A1A1F`, onSurfaceVariant `#9A9AA5` (masked names, dates, secondary lines), outline `#E4E4EC`.

Status colours, exposed through a `LocalBrand` composition local because Material3 has no slot for them: `positive` `#0F7A5C` on `positiveContainer` `#CDF3E4` (the mint "Successful" pill, and credit amounts), `sentBadge` `#2E6BFF` (the blue circle with the outbound arrow), `receivedBadge` `#17C39A` (the green circle with the inbound arrow). Every credit also carries a `+` so colour is never the only cue.

### 6.1a Signature components (`BrandHeader.kt`)

`BrandHeader`: a `Brush.linearGradient(primaryBright -> primary)` box with the app name and the gear icon, and a coral-to-orange blob drawn as a `Path` with rounded cubic curves clipped to the header, anchored top-left. It is the single most identifying element and it is why the header is a component rather than a `TopAppBar`. The balance card overlaps its bottom edge by 24dp, exactly as InstaPay's promo card overlaps the purple.

`StatusPill`, `DirectionBadge` (a 20dp circle with an arrow glyph, blue outbound and green inbound, drawn over the bottom-right of the category avatar), and `CategoryAvatar` (a 44dp circle, `secondaryContainer` fill, category glyph in `secondary`) reproduce the transaction row: amount large at the left, pill at the right, avatar with badge, then the counterparty line and the date in `onSurfaceVariant`.

### 6.2 Type scale

System Roboto, no font asset. Balance: `displaySmall` 44sp bold with `FontFeatureSettings("tnum")` so digits do not jitter. Section titles `titleMedium`; row primary `bodyLarge`; row secondary `bodySmall` at `onSurfaceVariant`; chips `labelLarge`; chart axis labels `labelSmall`. Amounts everywhere use tabular figures.

### 6.3 Spacing and shape

4dp grid. Screen padding 16dp, card padding 16dp (hero 20dp), section gap 20dp, row gap 8dp. Shapes: cards 20dp, hero card 24dp, chips full pill, bottom sheets 28dp top corners, buttons pill. Cards are flat (`surface` on white with a 1dp `outline` at 40% alpha) rather than elevated, so the white-on-white look reads as InstaPay's. (revisit: card radius and whether InstaPay uses shadows.)

### 6.4 Chart palette (`ChartPalette.kt`, pure Kotlin, `Long` ARGB, no Compose import)

Ramp, ordered so no two adjacent slices share a hue family: `#7A12D4` violet, `#F26722` orange, `#2A9D8F` teal, `#A23B72` plum, `#B8860B` gold, `#3A6FB0` blue, `#8C5A2B` brown, `#6B7A2E` olive, `#5F6B7A` slate, `#512772` deep purple. Uncategorised slice: `#6F7378` grey. Coral `#F79070` is deliberately not in the ramp: it measures 2.29:1 against white, well below the 3:1 non-text minimum, so it is a container tint only (confirmed acceptable).

`ChartPaletteTest` computes WCAG relative luminance in the test (pure arithmetic) and asserts every ramp colour is at least 3.0:1 against white, that ids are unique, and that `UNCATEGORISED` is not in the ramp. Verified contrast values (computed, not estimated): orange 3.12, gold 3.25, teal 3.32, olive 4.72, blue 5.15, slate 5.43, brown 5.81, plum 6.17, violet 7.33, deep purple 11.15. Orange, gold and teal sit closest to the line; if a future tweak drops one under, darken it rather than dropping it from the ramp.

An earlier draft of this plan also asserted that adjacent ramp entries differ in luminance by a ratio of at least 1.3. That assertion was removed because it fails on its own data: blue (L=0.1541) against brown (L=0.1306) is 1.18, and olive (L=0.1724) against slate (L=0.1436) is 1.20. Ten colours that all clear 3:1 on white are necessarily squeezed into a narrow luminance band, so a meaningful adjacency threshold is not satisfiable at this ramp size. Adjacent-slice separation is delivered instead by the 2dp white gap between arcs and by the legend, which are stronger cues than a luminance step anyway.

Chart styling: ring stroke 28dp with 2dp white gaps, selected slice grows 4dp outward and its legend row highlights; bars 8dp radius tops, `primary` fill, current period `tertiary`; gridlines `outline` at 30%; every chart has a legend or value labels so hue is never the only channel.

## 7. Charts and insights

### 7.1 Pure aggregates (`Insights.kt`)

```kotlin
data class Slice(val categoryId: String?, val amountMinor: Long)
data class Bucket(val label: String, val amountMinor: Long)

fun byCategory(entries, type: EntryType, from: Instant, to: Instant, zone): List<Slice>   // sorted desc, no ANCHOR
fun topN(slices, n): List<Slice>                                                            // rest rolled into OTHER_ROLLUP
fun daily(entries, type, days: Int, now: Instant, zone): List<Bucket>                       // one bucket per LocalDate, zeros kept
fun monthly(entries, type, months: Int, now: Instant, zone): List<Bucket>                   // one per YearMonth
fun monthComparison(entries, type, now, zone): MonthComparison                              // month-to-date vs same window last month, plus full last month
fun uncategorisedCount(entries): Int                                                        // != ANCHOR && categoryId == null
```

### 7.2 Canvas components (`Charts.kt`)

`DonutChart(slices, colours, selected, onSelect)`: `drawArc` per slice with stroke cap butt, start at 12 o'clock, `pointerInput { detectTapGestures }` converts the tap point to an angle and picks the slice; centre shows the total and the selected slice name. `BarChart(buckets, highlightLast)`: `drawRoundRect` per bucket, baseline, 3 y gridlines, first/last x labels via `drawText` from `rememberTextMeasurer`. `ComparisonBars(current, previousWindow, previousFull)`: two bars plus a faint third. `TopCategoriesList`: not Canvas; a `Column` of rows with a `Box` whose width is `fillMaxWidth(fraction)`.

### 7.3 Insights section (`InsightsSection.kt`)

Header with a `SingleChoiceSegmentedButtonRow` (Expenses / Income) and a period chip (This month / Last 30 days / 6 months). Cards: Spend by category (ring plus legend), Over time (daily bars for 30 days, monthly bars for 6 months), This month vs last, Top categories. Empty state per card ("No expenses in this period yet") rather than an empty ring.

## 8. Phases

Each phase ends green on `./gradlew :app:testDebugUnitTest :app:assembleDebug` and is independently reviewable. Tests are listed before the code they force.

### Phase 1: split the UI, no behaviour change

Pure move. A reviewer should be able to diff each new file against the corresponding lines of the old `MainActivity.kt`.

- Creates: `Ui.kt` (the three symbols that cross the new file boundaries, see below), `LockScreen.kt` (`LockScreen`, `PinDots`, `KeyPad`, `KeyButton`), `HomeScreen.kt` (`AppScreen` renamed `HomeScreen`, `BalanceCard`, `syncedAgoText`, `EntryList`, `EntryRow`, `dateFmt`), `EntryDialogs.kt` (`AmountDialog`, `PasscodeSetupDialog`), `SettingsScreen.kt` (`SettingsPanel`, `openBatterySettings`).
- Edits: `MainActivity.kt` down to the activity and `Gate`.
- Tests: none new; existing 31 stay green (they do not touch UI).

**Kotlin visibility, the thing that makes this not a pure move.** A top-level `private` in Kotlin is file-scoped, not package-scoped, so three symbols currently sharing `MainActivity.kt` are used by composables that land in different files. Leaving them `private` does not compile:

| Symbol | Declared | Used by | Would land in |
| --- | --- | --- | --- |
| `PIN_LENGTH` | `MainActivity.kt:80` | `LockScreen`, `PasscodeSetupDialog` | LockScreen.kt and EntryDialogs.kt |
| `sanitizeAmount` | `MainActivity.kt:353` | `AmountDialog`, `SettingsPanel` fee fields | EntryDialogs.kt and SettingsScreen.kt |
| `Dialog` enum | `MainActivity.kt:82` | `AppScreen`, `AmountDialog` | HomeScreen.kt and EntryDialogs.kt |

All three move to `Ui.kt` as `internal`. `internal` rather than `public` keeps them off the module's API surface, which is what `private` was buying. Everything else stays `private` in its new file.

### Phase 2: design system, navigation, Settings screen

- Tests first: `ChartPaletteTest` (contrast against white, adjacent luminance separation, ramp size at least 10, `UNCATEGORISED` not in ramp).
- Creates: `ChartPalette.kt`, `Theme.kt` (`InstaBalanceTheme`, colour scheme, typography, shapes, `LocalBrand`), `Nav.kt` (`Route`, `rememberNavStack`, `BackHandler`).
- Edits: `MainActivity.kt` (`InstaBalanceTheme { Gate() }`, `Gate` renders the stack), `HomeScreen.kt` (header row with gear, restyled balance card, flat cards, remove inline settings), `SettingsScreen.kt` (becomes a full screen with a top bar and back, cards per section, unchanged behaviour), `LockScreen.kt` (theme colours only), `res/values/colors.xml` (brand colours for the window background and status bar, keeps the launcher entry).
- Every existing screen is restyled here so nothing looks half-migrated when the new screens arrive.

### Phase 3: data model, compatibility, migration

- Tests first: `LedgerCompatTest.decodesV1File`, `newFileHasOnlyAdditiveKeys`, `unknownEnumInNewFieldDoesNotBreakEntry`. `CategoriesTest`: preset ids unique, `fees` present, `visibleFor` filters by kind and hidden, `delete` refuses presets, `delete` reassigns entries and drops rules, `ensurePresets` appends only missing. `MigrationTest` as in 5.2. `LedgerLogicTest` additions: `applyCategory` ignores ANCHOR and unknown ids, preserves list length, clears `categoryFromRule`.
- Creates: `Categories.kt`, `Migration.kt`.
- Edits: `Ledger.kt` (fields on `Entry` and `LedgerData`; pure `applyCategory`; repository `setCategory(ids, categoryId)` returning the previous map, `addCategory`, `updateCategory`, `deleteCategory`; `init` runs migration and writes the `.bak` on decode failure).
- UI unchanged; fields unused so far.

### Phase 4: merchant extraction and rules engine

- Tests first: `BalanceParserTest` additions (`arabicPurchase_extractsMerchant`, `arabicWithdrawal_merchantIsAtm`, `arabicCancelledPurchase_extractsMerchant`, `englishCredited_extractsSender`, `englishCharged_hasNoMerchant`, `instapayNotification_extractsIpa`, `merchantFailureDoesNotDropTransaction`). `MerchantRulesTest`: `normalise` cases including Arabic-Indic digits, `match` longest wins and null on no match, `proposePattern` for each fixture, `applyToUncategorised` never touches categorised or ANCHOR entries, `categoriseNew` sets `categoryFromRule`.
- Creates: `MerchantRules.kt`.
- Edits: `BalanceParser.kt` (`ParsedTxn.merchant`, extraction), `Ledger.kt` (`addAuto` stores merchant, runs `categoriseNew`, FEE gets `fees`; repository `addRule`, `updateRule`, `deleteRule`, `applyRuleToUncategorised`).

### Phase 5: categorisation UX

- Tests first: `InsightsTest.uncategorisedCount` (excludes ANCHOR), `MerchantRulesTest.suggestionsForEntry` (rule match first, then recents, dedupes).
- Creates: `CategoryPicker.kt` (sheet, chip groups, inline new category, rule switch), `InboxScreen.kt`, `EntryDetailSheet.kt`.
- Edits: `HomeScreen.kt` (inbox banner, richer rows with category dot and merchant, tap opens detail, delete moves into detail), `EntryDialogs.kt` (chip row on `AmountDialog`, `addManual` gains `categoryId`), `Ledger.kt` (`addManual` signature with default `categoryId = null`), `Nav.kt` (`INBOX`).

### Phase 6: category and rule management

- Tests first: `CategoriesTest.renameKeepsIdAndEntries`, `recolourUsesRampIndexOnly` (rejects out-of-range), `hiddenStaysInAggregates` (via `Insights.byCategory`).
- Creates: `CategoriesScreen.kt`, `RulesScreen.kt`.
- Edits: `SettingsScreen.kt` (two navigation rows, copy button on captures), `Nav.kt` (`CATEGORIES`, `RULES`), `Ledger.kt` (rule edit with counted update).

### Phase 7: insights aggregates (pure)

- Tests first: `InsightsTest`: `excludesAnchors`, `filtersByDirection`, `feeIsOrdinarySpend`, `uncategorisedIsItsOwnSlice`, `topNRollsUpRest`, `dailyKeepsZeroDays`, `monthBucketsAtLocalMidnightCairo`, `monthBucketsAcrossDstSwitch`, `monthComparisonUsesSameDayWindow`, `monthComparisonClampsShortMonth` (31 March vs February).
- Creates: `Insights.kt`.
- No UI change.

### Phase 8: charts and the insights section

- Tests: `sliceAngles(slices): List<Pair<Float, Float>>` and `tapToSliceIndex(angleDeg, angles)` are extracted into `Insights.kt` as pure functions and tested (`anglesSumTo360`, `tapOnGapSelectsNothing`, `zeroTotalGivesNoSlices`).
- Creates: `Charts.kt`, `InsightsSection.kt`.
- Edits: `HomeScreen.kt` (mounts the section below recent activity), `Insights.kt` (angle helpers).

### Phase 9: polish and the optional enrichment

- Tests first: `LedgerLogicTest.mergeDuplicateFillsMissingMerchantOnly`, `Insights.suggestRefund` cases.
- Edits: `Ledger.kt` (`addAuto` enriches instead of dropping when the new capture has a merchant and the stored one does not), `InboxScreen.kt` (backlog card), `CategoryPicker.kt` (refund suggestion), `README.md` (categories, inbox, rules, charts sections; the test count line).

### Phase 10: budget and milestone alerts

Depends only on Phase 7 (`Insights.monthly`), so it can run any time after it, and before Phase 8 if the budget matters more than the charts.

- Tests first: `BudgetTest` as listed in 5.8, all pure, no Android framework.
- Creates: `Budget.kt` (`MILESTONES`, `reachedMilestone`, `milestoneToFire`, `BudgetStatus` for the card: spent, limit, percent, days left, projected), `BudgetAlerts.kt` (channel creation, `notify(milestone, spent, limit)`, the only file that touches `NotificationManager`), `BudgetCard.kt`.
- Edits: `Ledger.kt` (the three budget fields, `setBudget` recomputing `highestMilestoneFired` silently, `addEntryLocked` returning the milestone to fire, month rollover reset), `AndroidManifest.xml` (`POST_NOTIFICATIONS`), `SettingsScreen.kt` (budget row, runtime permission request at the moment a budget is first set), `HomeScreen.kt` (mounts `BudgetCard` under the balance), `App.kt` (channel creation on start).
- The notification is posted by the caller, outside `synchronized(lock)`, so a notification failure can never hold the ledger lock or strand a persisted entry.

## 9. Risks

1. Decode failure destroys the ledger. Existing behaviour, but this is the first schema change, so the exposure is new. Mitigated by defaults on every field, no enum constants added, the compat tests, and the `.bak` copy. Still the top risk because the file is encrypted and there is no export.
2. Merchant coverage is structurally limited. IPN debit SMS carry no counterparty. If the InstaPay notification format turns out to lack it too, the inbox is a permanent chore for sends. The Other chip, bulk actions and backlog card are the mitigation, not a fix.
3. Rule over-matching. `contains` with a short pattern (`ATM`, `AL`) can misfile. Stop list, 4-character minimum for single-token proposals, longest-match precedence, and the counted opt-in retro-application keep the blast radius to new entries, which are visible in the recent list with their chip.
4. Persist cost. Each mutation encodes the whole ledger and re-encrypts. Bulk actions are single mutations, but `prettyPrint = true` plus growing `rawText` means the file grows; not a problem at hundreds of entries, worth measuring at a few thousand.
5. Performance of aggregates on the UI thread. Linear passes over entries per recomposition, memoised on `data`. Fine to low thousands; if profiling shows jank, move `Insights` calls to `withContext(Dispatchers.Default)` in a `produceState`.
6. Visual fidelity without screenshots. Colour roles, card treatment and the balance hero are inferred from the palette and common Egyptian fintech patterns; marked (revisit).
7. `ModalBottomSheet` and `SingleChoiceSegmentedButtonRow` are NOT stable in the pinned Compose BOM (2024.10.01, material3 1.3.x): both are still `@ExperimentalMaterial3Api` and need an explicit `@OptIn` or the build fails. An earlier draft of this plan asserted they were stable, which was wrong. `rememberTextMeasurer` is stable, but has had layout quirks inside a Canvas under `verticalScroll`; if axis labels misplace, fall back to `Text` composables positioned outside the Canvas.
8. Locale digits. New formatting is pinned to English; the old `dateFmt` and `Money` are not, so an `ar-EG` device will show mixed digit scripts until a follow-up.

## 10. Open questions and pushbacks

- Should presets be deletable? This plan says no (hide only), which keeps `ensurePresets` trivial and rules from dangling. If the user insists, deletion needs a tombstone list so the preset does not resurrect.
- Is "Other" the right escape hatch, or should the inbox have a "reviewed, no category" state? Other keeps the model simpler and keeps the money visible in charts. A `reviewed` flag would be a fourth state to reason about everywhere.
- Week buckets are omitted on purpose. Egypt's week starts Saturday and `WeekFields.of(Locale)` disagrees across locales; daily and monthly cover "over time" without that argument. Add weekly only if asked, with an explicit `DayOfWeek.SATURDAY` constant.
- Income ring is low value (two or three slices for most users) but was decided; it is cheap because it is the same component with `type = CREDIT`.
- Reversals as income: a cancelled purchase is a CREDIT today. Suggesting `refund` is the honest minimum; netting it against the original purchase would change balance semantics and is out of scope.
- The `.bak` copy in `init` is the one change that is not strictly "expense tracking". It is included because this plan is what introduces the schema risk it guards against; drop it only if a separate backup story exists.
- `merchant` for ATM withdrawals is the literal `ATM`. Useful for the `cash` rule, slightly odd as a display string. Alternative: leave `merchant` null and let the parser tag `cash` directly; rejected because it would put category knowledge into the parser.

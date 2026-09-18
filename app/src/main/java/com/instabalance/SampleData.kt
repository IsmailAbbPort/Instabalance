package com.instabalance

import kotlin.random.Random

/**
 * Realistic-looking data for trying the app on a machine that has none, which is the only way to
 * see the charts, the review inbox and the budget without waiting weeks for real messages.
 *
 * Deterministic for a given seed, so a screenshot can be reproduced, and pure, so it is testable.
 * Only reachable from a debug build (see the Settings screen).
 */
object SampleData {

    private const val DAYS = 120
    private const val DAY_MS = 24 * 60 * 60 * 1000L

    /** Merchant, category, typical amount range in piastres. Named the way EGBANK actually prints them. */
    private data class Spend(
        val merchant: String?,
        val categoryId: String?,
        val minMinor: Long,
        val maxMinor: Long,
        val perMonth: Int,
    )

    private val PATTERN = listOf(
        Spend("CARREFOUR MAADI CAIRO", "groceries", 8_000, 45_000, 6),
        Spend("SEOUDI MARKET NASR CITY", "groceries", 5_000, 28_000, 4),
        Spend("PAYMOB RAF SPECIALIT CAIRO N 07", "shopping", 12_000, 90_000, 3),
        Spend("UBER TRIP HELP.UBER.COM", "transport", 4_000, 18_000, 8),
        Spend("ATM", Categories.CASH, 50_000, 200_000, 2),
        Spend("BUFFALO BURGER ZAMALEK", "eating_out", 15_000, 42_000, 4),
        Spend("STARBUCKS CITYSTARS", "eating_out", 8_000, 16_000, 5),
        Spend("VODAFONE EG RECHARGE", "mobile", 10_000, 30_000, 1),
        Spend("EGYPTIAN ELECTRICITY CO", "bills", 25_000, 70_000, 1),
        Spend("EL EZABY PHARMACY", "health", 6_000, 35_000, 2),
        // The honest half: an InstaPay send seen as a bank SMS names nobody, so these arrive with
        // no merchant and no category, which is exactly what fills the review inbox.
        Spend(null, null, 20_000, 150_000, 7),
    )

    private val INCOME = listOf(
        Spend("MONTHLY SALARY TRANSFER", "salary", 1_800_000, 1_800_000, 1),
        Spend("naniiceeabbas@instapay", "freelance", 150_000, 600_000, 2),
        Spend("laylaaosman@instapay", "family", 20_000, 80_000, 3),
    )

    /**
     * A ledger with four months of history: an opening anchor, spending and income across the
     * preset categories, InstaPay fees on the sends, and a backlog of uncategorised entries so the
     * inbox has something to triage.
     */
    fun generate(now: Long, seed: Int = 7): List<Entry> {
        val random = Random(seed)
        val entries = mutableListOf<Entry>()
        // Ids come from a counter, not UUID.randomUUID(), or the same seed would produce a
        // different ledger every call and a screenshot could never be reproduced.
        var n = 0
        fun nextId() = "sample-$seed-${n++}"

        // The anchor sits at the start, so every transaction after it counts toward the balance.
        entries += Entry(
            id = nextId(),
            type = EntryType.ANCHOR,
            amountMinor = 2_400_000,
            timestamp = now - DAYS * DAY_MS,
            source = Source.MANUAL,
            note = "Opening balance",
        )

        val months = DAYS / 30
        for (month in 0 until months) {
            // Newest month last, so "this month" is the partially complete one.
            val monthStart = now - (months - month) * 30 * DAY_MS

            PATTERN.forEach { spend ->
                repeat(spend.perMonth) {
                    val at = monthStart + random.nextLong(0, 30 * DAY_MS)
                    if (at > now) return@repeat
                    val amount = randomAmount(random, spend)
                    val isInstapaySend = spend.merchant == null

                    // A send that names nobody can never be filed by a rule, so it stays unfiled
                    // whenever it happened. In the newest month a third of the rest is left unfiled
                    // too, giving the inbox a realistic backlog. Any more than that and the ring
                    // becomes one grey circle, which tells you nothing.
                    val unfiled = isInstapaySend ||
                        (month == months - 1 && random.nextInt(3) == 0)

                    entries += Entry(
                        id = nextId(),
                        type = EntryType.DEBIT,
                        amountMinor = amount,
                        timestamp = at,
                        source = Source.SMS,
                        merchant = spend.merchant,
                        categoryId = if (unfiled) null else spend.categoryId,
                        categoryFromRule = !unfiled && spend.categoryId != null,
                        rawText = rawTextFor(spend, amount),
                    )

                    if (isInstapaySend) {
                        val fee = LedgerData().instapaySendFeeMinor(amount)
                        entries += Entry(
                            id = nextId(),
                            type = EntryType.DEBIT,
                            amountMinor = fee,
                            timestamp = at,
                            source = Source.FEE,
                            note = "InstaPay transfer fee",
                            categoryId = Categories.FEES,
                        )
                    }
                }
            }

            INCOME.forEach { income ->
                repeat(income.perMonth) {
                    val at = monthStart + random.nextLong(0, 30 * DAY_MS)
                    if (at > now) return@repeat
                    entries += Entry(
                        id = nextId(),
                        type = EntryType.CREDIT,
                        amountMinor = randomAmount(random, income),
                        timestamp = at,
                        source = Source.SMS,
                        merchant = income.merchant,
                        categoryId = if (month == months - 1 && random.nextInt(3) == 0) null
                        else income.categoryId,
                        categoryFromRule = false,
                        rawText = "Your account was credited by EGP ... IPN REF# " +
                            "${random.nextLong(10_000_000_000, 99_999_999_999)} from ${income.merchant}",
                    )
                }
            }
        }

        return entries.sortedBy { it.timestamp }
    }

    /** A couple of rules, so the rules screen and the "filed automatically" chip have content. */
    fun rules(now: Long): List<MerchantRule> = listOf(
        MerchantRule(pattern = "CARREFOUR", categoryId = "groceries", createdAt = now - 60 * DAY_MS),
        MerchantRule(pattern = "UBER", categoryId = "transport", createdAt = now - 59 * DAY_MS),
        MerchantRule(pattern = "ATM", categoryId = Categories.CASH, createdAt = now - 58 * DAY_MS),
    )

    private fun randomAmount(random: Random, spend: Spend): Long =
        if (spend.maxMinor <= spend.minMinor) spend.minMinor
        else (random.nextLong(spend.minMinor, spend.maxMinor) / 50) * 50

    private fun rawTextFor(spend: Spend, amountMinor: Long): String {
        val amount = Money.formatMinor(amountMinor)
        return when {
            spend.merchant == null ->
                "Your account was charged by EGP $amount IPN REF# 19825518418 for details please call 19342"
            spend.merchant == "ATM" ->
                "تم سحب ${amount}جم من حساب 0057*100 من ATM  الرصيد المتاح  2091.36جم"
            else ->
                "تم الشراء بمبلغ ${amount}جم على الكارت رقم  +++0954 من ${spend.merchant}"
        }
    }
}

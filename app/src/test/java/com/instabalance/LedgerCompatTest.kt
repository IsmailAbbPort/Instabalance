package com.instabalance

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The ledger file is encrypted and there is no export, so a schema mistake is unrecoverable data
 * loss. These tests pin both directions of compatibility.
 */
class LedgerCompatTest {

    /** Same configuration the repository uses; the compatibility depends on these exact flags. */
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }

    /** A ledger.enc as written before categories existed, key for key. */
    private val v1 = """
        {
            "entries": [
                {
                    "id": "e1",
                    "type": "ANCHOR",
                    "amountMinor": 100000,
                    "timestamp": 1000,
                    "source": "MANUAL",
                    "note": "start",
                    "rawText": null
                },
                {
                    "id": "e2",
                    "type": "DEBIT",
                    "amountMinor": 16500,
                    "timestamp": 2000,
                    "source": "SMS",
                    "note": "",
                    "rawText": "some bank sms"
                }
            ],
            "watchedPackages": ["com.egyptianbanks.instapay"],
            "learningMode": false,
            "captures": [],
            "passcodeHash": null,
            "passcodeSalt": null,
            "biometricEnabled": false,
            "instapaySendFeeEnabled": true,
            "feePercentBps": 10,
            "feeMinMinor": 50,
            "feeCapMinor": 2000
        }
    """.trimIndent()

    @Test fun decodesAPreCategoryFile() {
        val data = json.decodeFromString<LedgerData>(v1)

        assertEquals(2, data.entries.size)
        assertEquals(100000L, data.entries[0].amountMinor)
        assertEquals(true, data.instapaySendFeeEnabled)
    }

    @Test fun preCategoryEntriesGetTheUncategorisedDefaults() {
        val data = json.decodeFromString<LedgerData>(v1)

        data.entries.forEach {
            assertNull(it.merchant)
            assertNull(it.categoryId)
            assertFalse(it.categoryFromRule)
        }
    }

    @Test fun preCategoryFileGetsThePresetCategories() {
        // Otherwise an upgrading user opens the app with no categories at all.
        val data = json.decodeFromString<LedgerData>(v1)

        assertEquals(Categories.PRESETS, data.categories)
        assertTrue(data.merchantRules.isEmpty())
        assertNull(data.monthlyBudgetMinor)
    }

    @Test fun everyNewKeyIsAdditive() {
        // Forward compatibility: an older build reading a new file skips unknown keys, but ONLY
        // because nothing was added to an existing enum. This pins the key set that was added.
        val encoded = json.encodeToString(LedgerData()).let { Json.parseToJsonElement(it).jsonObject }
        val v1Keys = Json.parseToJsonElement(v1).jsonObject.keys

        val added = encoded.keys - v1Keys
        assertEquals(
            setOf(
                "categories", "merchantRules", "smsConfig",
                "monthlyBudgetMinor", "budgetMonth", "highestMilestoneFired",
            ),
            added,
        )
        // Nothing was removed either, or an old file would fail to supply a required field.
        assertTrue((v1Keys - encoded.keys).isEmpty())
    }

    @Test fun noConstantWasAddedToTheExistingEnums() {
        // kotlinx.serialization throws on an unknown enum NAME even with ignoreUnknownKeys, so a
        // new Source or EntryType constant would make an older build fail to decode, land in the
        // swallowed-failure path, and overwrite the ledger on its next write.
        assertEquals(listOf("ANCHOR", "CREDIT", "DEBIT"), EntryType.entries.map { it.name })
        assertEquals(listOf("MANUAL", "NOTIFICATION", "SMS", "FEE"), Source.entries.map { it.name })
    }

    @Test fun anUnknownKeyInAnEntryIsIgnoredRatherThanFatal() {
        val withFutureField = """
            {"entries":[{"id":"x","type":"DEBIT","amountMinor":10,"timestamp":1,"somethingNew":42}]}
        """.trimIndent()

        val data = json.decodeFromString<LedgerData>(withFutureField)

        assertEquals(1, data.entries.size)
        assertEquals(10L, data.entries[0].amountMinor)
    }
}

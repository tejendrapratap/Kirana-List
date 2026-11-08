package com.grocerysync.app.ui

import com.grocerysync.app.data.InputValidation
import com.grocerysync.app.data.ValueType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The item dialog's "Similar" warning: another name of an item already on the list warns but doesn't block Save. */
class SimilarItemTest {

    private val onList = mapOf("rice" to "Rice", "dahi" to "Dahi", "kanda" to "Kanda")

    @Test
    fun anotherNameOfAnItemOnTheListWarns() {
        assertEquals("Dahi", similarItemName("Curd", null, onList))
        assertEquals("Dahi", similarItemName("  curd ", null, onList))
        assertEquals("Dahi", similarItemName("दही", null, onList)) // दही
        assertEquals("Kanda", similarItemName("Onion", null, onList))
        assertEquals("Kanda", similarItemName("प्याज़", null, onList)) // प्याज़
        assertEquals("Rice", similarItemName("Chawal", null, onList))
        // A plural or an accent is a different name for the sheet, so it is similar too.
        assertEquals("Eggs", similarItemName("Egg", null, mapOf("eggs" to "Eggs")))
        assertEquals("Dahi", similarItemName("Dahí", null, onList))
    }

    @Test
    fun itIsOnlyAWarning() {
        assertEquals("Similar: \"Dahi\" is already on the list", similarItemText("Dahi"))
        // Save stays enabled, unlike for a name another item has.
        assertTrue(canSaveItem("Curd", null, onList, ValueType.BOOLEAN, "0", ""))
        assertNull(nameTakenBy("Curd", null, onList))
        // A long name is cut like in the other messages.
        val long = "x".repeat(InputValidation.MAX_NAME_LENGTH)
        assertEquals("Similar: \"${quotedItemName(long)}\" is already on the list", similarItemText(long))
    }

    @Test
    fun theExactNameIsTheDuplicateRuleNotAWarning() {
        assertNull(similarItemName("dahi", null, onList))
        assertNull(similarItemName("  DAHI ", null, onList))
        // That one blocks Save with its own message.
        assertEquals("Dahi", nameTakenBy("  DAHI ", null, onList))
        // Even when another name of it is on the list too.
        assertNull(similarItemName("Dahi", null, mapOf("dahi" to "Dahi", "curd" to "Curd")))
    }

    @Test
    fun noWarningWithoutAnotherItemForTheSameThing() {
        assertNull(similarItemName("Milk", null, onList))
        assertNull(similarItemName("Curd", null, emptyMap()))
        // Names that are not a whole dictionary name, and different entries.
        assertNull(similarItemName("Basmati rice", null, onList))
        assertNull(similarItemName("Rice", null, mapOf("basmati rice" to "Basmati rice")))
        assertNull(similarItemName("Dal", null, mapOf("dalchini" to "Dalchini", "toor dal" to "Toor dal")))
        assertNull(similarItemName("Oil", null, mapOf("sarson ka tel" to "Sarson ka tel")))
        // Not a valid name: the name's own error shows instead.
        assertNull(similarItemName("", null, onList))
        assertNull(similarItemName("   ", null, onList))
        assertNull(similarItemName("Curd\nfresh", null, onList))
    }

    @Test
    fun renamingWarnsOnlyForANewName() {
        val list = mapOf("curd" to "Curd", "dahi" to "Dahi", "rice" to "Rice")
        // Editing Curd: its own name, in other capitals too, doesn't warn although Dahi is on the list.
        assertNull(similarItemName("Curd", "curd", list))
        assertNull(similarItemName(" CURD ", "curd", list))
        // Renaming it to another name of the same thing warns about Dahi...
        assertEquals("Dahi", similarItemName("Yogurt", "curd", list))
        assertEquals("Dahi", similarItemName("Yogurt", "rice", mapOf("rice" to "Rice", "dahi" to "Dahi")))
        // ...and never about the item being renamed.
        assertNull(similarItemName("Yogurt", "curd", mapOf("curd" to "Curd", "rice" to "Rice")))
        assertNull(similarItemName("Dahi", "curd", mapOf("curd" to "Curd")))
        // An item renamed or removed on another phone (no longer on the list) is saved under its old key.
        assertEquals("Dahi", similarItemName("Curd", "yogurt", mapOf("dahi" to "Dahi")))
    }
}

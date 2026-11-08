package com.grocerysync.app.uitest

import android.content.ClipData
import android.content.ClipboardManager
import android.content.res.Configuration
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.inspector.WindowInspector
import androidx.annotation.RequiresApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.grocerysync.app.data.Units
import com.grocerysync.app.testing.FakeSheetServer.Companion.quantity
import com.grocerysync.app.testing.FakeSheetServer.Companion.yesNo
import com.grocerysync.app.testing.FakeSheetServer.Need
import com.grocerysync.app.testing.GroceryUiTest
import com.grocerysync.app.testing.button
import com.grocerysync.app.testing.gotIt
import com.grocerysync.app.testing.textField
import com.grocerysync.app.testing.toBuyRowWithAmount
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Many items at once (1.4): ⋮ > Paste a list, and a list shared into the app from another one (Share > Grocery
 * Sync). Nothing changes before the preview's button is tapped. The parser and the preview's rules are tested on the
 * JVM (PasteParserTest, PastePlanTest, ShareRoundTripTest).
 */
@RunWith(AndroidJUnit4::class)
class PasteListTest : GroceryUiTest() {

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val clipboard: ClipboardManager
        get() = instrumentation.targetContext.getSystemService(ClipboardManager::class.java)
    private var copied = false

    /** Copies [text] as another app would (WhatsApp's Copy), for the dialog's Paste button. */
    private fun copyToClipboard(text: String) {
        copied = true
        instrumentation.runOnMainSync { clipboard.setPrimaryClip(ClipData.newPlainText("Grocery list", text)) }
    }

    /** Leaves the emulator's clipboard empty, as it was. */
    @After
    fun clearClipboard() {
        if (copied) instrumentation.runOnMainSync { clipboard.clearPrimaryClip() }
    }

    /** A line of the preview that changes something: the whole row is its check box. */
    private fun pasteRow(text: String): SemanticsMatcher = isToggleable() and hasText(text)

    @Test
    fun pastedLinesAddAndMarkItemsAfterThePreview() {
        fake.seed("Dahi", yesNo(false))
        launchConnected()

        chooseFromMenu("Paste a list")
        waitForNode(textField("List")).performTextInput("Rice 2 kg\nCurd")
        node(button("Next")).performClick()

        // What each line does: a new item, and another name of an item on the list.
        waitForNode(hasText("Add Rice - 2 kg"))
        waitForNode(hasText("Mark Dahi needed") and hasText("Curd → Dahi"))
        assertTrue(fake.sentChanges().isEmpty())
        node(button("Apply 2 changes")).performClick()

        waitForNode(hasText("Added 1 item, updated 1"))
        waitForNode(toBuyRowWithAmount("Rice", "2 kg"))
        waitForNode(gotIt("Dahi"))
        waitFor("both on the sheet") { fake.item("Rice") != null && fake.item("Dahi")?.need == Need.YesNo(true) }
        val rice = fake.item("Rice")!!.need as Need.Quantity
        assertTrue(rice.amountIs("2"))
        assertEquals(Units.KG, rice.unit)
        // Curd is another name of Dahi, so no item of that name.
        assertNull(fake.item("Curd"))
    }

    @Test
    fun aListSharedIntoTheAppOpensItsPreview() {
        fake.seed("Oil", quantity("1", Units.L))
        // As Share in the app writes it (on the other phone).
        launchConnected(sharedTextIntent("Grocery list (2 items)\n\n*Oil & ghee*\n• Ghee - 0.5 L\n• Oil - 1 L"))

        waitForNode(hasText("Add Ghee - 0.5 L"))
        waitForNode(hasText("Already needed: Oil"))
        assertTrue(fake.sentChanges().isEmpty())
        node(button("Add 1 item")).performClick()

        waitForNode(hasText("Added 1 item"))
        waitFor("Ghee on the sheet") { fake.item("Ghee") != null }
        val ghee = fake.item("Ghee")!!.need as Need.Quantity
        assertTrue(ghee.amountIs("0.5"))
        assertEquals(Units.L, ghee.unit)
    }

    @Test
    fun aListSharedBeforeSetupIsNotRead() {
        launchApp(sharedTextIntent("Rice 2 kg"))

        waitForNode(hasText("Set up Grocery Sync first, then share the list again."))
        assertNoNode(hasText("Add Rice - 2 kg"))
    }

    @Test
    fun pastingBeforeSetupSaysToConnectFirst() {
        launchApp()
        waitForNode(hasText("Not connected"))

        // Until it has the family's list, every line would be new and could replace their item of that name.
        chooseFromMenu("Paste a list")
        waitForNode(hasText("Connect to your sheet in Settings first, so items already on it aren't replaced."))
        assertNoNode(textField("List"))
    }

    @Test
    fun thePreviewSaysWhatEachLineDoesAndOnlyTheTickedLinesApply() {
        fake.seed("Rice", quantity("2", Units.KG))
        fake.seed("Oil", yesNo(true))
        fake.seed("Eggs", quantity("0", Units.DOZEN))
        fake.seed("Dahi", yesNo(false))
        launchConnected()
        copyToClipboard(
            listOf(
                "Grocery list (6 items)",
                "",
                "• Rice - 3 kg",
                "• Oil",
                "• Eggs 12",
                "• Curd 1 kg",
                "• Atta 5 kg",
                "• 2 kg",
            ).joinToString("\n"),
        )

        // The clipboard is read when Paste is tapped.
        chooseFromMenu("Paste a list")
        waitForNode(button("Paste")).performClick()
        waitForNode(textField("List") and hasText("• Atta 5 kg", substring = true))
        node(button("Next")).performClick()

        waitForNode(hasText("1 new, 2 to update, 1 already needed, 2 skipped"))
        // Another amount for an item needed already: unticked until chosen.
        node(pasteRow("Change Rice 2 kg → 3 kg")).assertIsOff()
        node(hasText("Already needed: Oil")).assertExists()
        assertNoNode(pasteRow("Already needed: Oil"))
        // 12 without a unit is 12 pcs, and Eggs are bought by the dozen: amounts are never converted.
        node(hasText("Skipped: Eggs 12") and hasText("Eggs is in dozen")).assertExists()
        assertNoNode(pasteRow("Skipped: Eggs 12"))
        // Curd is another name of Dahi, a yes / no item, which takes no amount.
        node(pasteRow("Mark Dahi needed") and hasText("Curd → Dahi. Dahi is yes / no, so the amount is left out"))
            .assertIsOn()
        node(pasteRow("Add Atta - 5 kg")).assertIsOn()
        node(hasText("Skipped: 2 kg") and hasText("Couldn't read")).assertExists()

        node(button("Apply 2 changes")).assertIsEnabled()
        node(pasteRow("Change Rice 2 kg → 3 kg")).performClick()
        waitForNode(button("Apply 3 changes"))
        assertTrue(fake.sentChanges().isEmpty())
        node(button("Apply 3 changes")).performClick()

        waitForNode(hasText("Added 1 item, updated 2"))
        waitForNode(toBuyRowWithAmount("Rice", "3 kg"))
        waitForNode(toBuyRowWithAmount("Atta", "5 kg"))
        waitForNode(gotIt("Dahi"))
        waitFor("the three changes on the sheet") {
            (fake.item("Rice")?.need as? Need.Quantity)?.amountIs("3") == true &&
                fake.item("Atta") != null &&
                fake.item("Dahi")?.need == Need.YesNo(true)
        }
        val atta = fake.item("Atta")!!.need as Need.Quantity
        assertTrue(atta.amountIs("5"))
        assertEquals(Units.KG, atta.unit)
        assertEquals(Units.KG, (fake.item("Rice")!!.need as Need.Quantity).unit)
        // The lines that change nothing sent nothing.
        assertTrue(fake.changesFromApp("Oil").isEmpty())
        assertTrue(fake.changesFromApp("Eggs").isEmpty())
        assertNull(fake.item("Curd"))
        assertEquals(setOf("Rice", "Atta", "Dahi"), fake.sentChanges().map { it.name }.toSet())
    }

    @Test
    fun aListSharedWhileTheAppIsOpenOpensItsPreview() {
        fake.seed("Coffee", yesNo(false))
        launchConnected()
        // Even from Settings: the list screen comes back with the preview over it.
        openSettings()
        waitForNode(textField("Secret token"))

        shareIntoOpenApp("Ghee 1 L\nCoffee")
        waitForNode(hasText("Add Ghee - 1 L"))
        waitForNode(pasteRow("Mark Coffee needed"))
        assertTrue(fake.sentChanges().isEmpty())
        node(button("Apply 2 changes")).performClick()

        waitForNode(hasText("Added 1 item, updated 1"))
        waitForNode(toBuyRowWithAmount("Ghee", "1 L"))
        waitForNode(gotIt("Coffee"))
        assertNoNode(textField("Secret token"))
        waitFor("both on the sheet") { fake.item("Ghee") != null && fake.item("Coffee")?.need == Need.YesNo(true) }
    }

    @Test
    @SdkSuppress(minSdkVersion = Build.VERSION_CODES.Q) // WindowInspector
    fun theStatusBarStaysReadableOverTheDialog() {
        launchConnected()

        chooseFromMenu("Paste a list")
        waitForNode(textField("List"))
        // The dialog is drawn under the status bar, so its own window sets the colour of the clock and icons there:
        // dark over the light theme's surface, light over the dark one's. They were white on white.
        val night = (instrumentation.targetContext.resources.configuration.uiMode and
            Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        waitFor("${if (night) "light" else "dark"} status bar icons over the dialog") {
            dialogHasLightStatusBars() == !night
        }
    }

    /** Whether the open dialog's window asks for dark status bar icons (for a light background); null: no dialog. */
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun dialogHasLightStatusBars(): Boolean? {
        var light: Boolean? = null
        instrumentation.runOnMainSync {
            val window = WindowInspector.getGlobalWindowViews().firstNotNullOfOrNull(::dialogWindowIn)
            if (window != null) light = WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars
        }
        return light
    }

    /** The window of a Compose dialog whose views [view] holds, if any. */
    private fun dialogWindowIn(view: View): Window? = when {
        view is DialogWindowProvider -> view.window
        view is ViewGroup -> (0 until view.childCount).firstNotNullOfOrNull { dialogWindowIn(view.getChildAt(it)) }
        else -> null
    }
}

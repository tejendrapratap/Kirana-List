package com.grocerysync.app.uitest

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasStateDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.grocerysync.app.data.GroceryItem
import com.grocerysync.app.data.ItemValue
import com.grocerysync.app.data.ShopSection
import com.grocerysync.app.data.ValueType
import com.grocerysync.app.text.AppTexts
import com.grocerysync.app.text.Texts
import com.grocerysync.app.ui.ItemEditDialog
import com.grocerysync.app.ui.theme.GrocerySyncTheme
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The Add / Edit item dialog in Hindi, on its own (no list screen, no sync): its labels, buttons, errors and
 * TalkBack names, the Delete question and the Other unit dialog, and that no text in it is cut off (each wraps
 * instead). Run it once more with large text (tools/e2e/ui.sh font-scale 2.0) to check the largest font: Hindi
 * labels are longer than the English ones.
 */
@RunWith(AndroidJUnit4::class)
class ItemDialogHindiTest {

    @get:Rule
    val compose = createComposeRule()

    @Before
    fun showHindi() {
        Texts.current = AppTexts.Hindi
    }

    @After
    fun backToEnglish() {
        Texts.current = AppTexts.English
    }

    /** Haldi, a quantity from before units: 1, no unit yet (so the dialog asks for one). */
    private val haldi = GroceryItem(
        key = "haldi",
        name = "Haldi",
        value = ItemValue.Quantity.of(1, unit = null),
        updatedAt = 0L,
        updatedBy = "Home",
        pendingSync = false,
    )

    private fun showDialog(item: GroceryItem?, initialName: String = "") {
        compose.setContent {
            GrocerySyncTheme(dynamicColor = false) {
                ItemEditDialog(
                    item = item,
                    existingNames = mapOf("haldi" to "Haldi", "rice" to "Rice"),
                    defaultType = ValueType.BOOLEAN,
                    onDismiss = {},
                    onSave = { _, _, _ -> },
                    onDelete = {},
                    initialName = initialName,
                    shopSection = if (item == null) null else { _ -> ShopSection.MASALA },
                )
            }
        }
    }

    private fun button(label: String) = compose.onNode(hasText(label) and hasClickAction() and !hasSetTextAction())

    private val unitPicker = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.DropdownList) and
        hasContentDescription("यूनिट")

    @Test
    fun editDialogSpeaksHindi() {
        showDialog(haldi)

        compose.onNode(hasText("सामान बदलें")).assertExists()
        compose.onNode(hasSetTextAction() and hasText("सामान")).assertExists()
        compose.onNode(hasText("कैसे लिखें")).assertExists()
        compose.onNode(isSelectable() and hasText("हाँ या नहीं")).assertExists()
        compose.onNode(isSelectable() and hasText("कितना चाहिए")).assertIsSelected()
        compose.onNode(hasText("कितना ख़रीदना है? (0 = कुछ नहीं)")).assertExists()
        // The amount's steps (no unit: 1) and the unit picker, which says why Save is disabled.
        compose.onNode(hasContentDescription("1 कम")).assertExists()
        compose.onNode(hasContentDescription("1 ज़्यादा")).assertExists()
        compose.onNode(hasContentDescription("मात्रा")).assertExists()
        compose.onNode(unitPicker and hasStateDescription("सेव करने के लिए यूनिट चुनें")).assertExists()
        // The shop section, by its Hindi name.
        compose.onNode(hasText("सेक्शन")).assertExists()
        compose.onNode(hasContentDescription("सेक्शन: मसाले")).assertExists()
        compose.onNode(hasText("सिर्फ़ इस फ़ोन पर")).assertExists()
        button("सेव करें").assertIsNotEnabled()
        button("रद्द करें").assertExists()
        assertNoTextIsCut()

        button("हटाएँ").performClick()
        compose.onNode(hasText("\"Haldi\" हटाएँ?")).assertExists()
        compose.onNode(hasText("यह हर फ़ोन की लिस्ट से और शीट से हट जाएगा।")).assertExists()
        assertNoTextIsCut()
    }

    @Test
    fun addDialogSaysTheNameIsTakenInHindi() {
        showDialog(item = null, initialName = "rice")

        compose.onNode(hasText("सामान जोड़ें")).assertExists()
        compose.onNode(hasText("\"Rice\" पहले से लिस्ट में है")).assertExists()
        button("\"Rice\" खोलें").assertExists()
        button("सेव करें").assertIsNotEnabled()
        assertNoTextIsCut()
    }

    @Test
    fun otherUnitDialogSpeaksHindi() {
        showDialog(haldi)

        compose.onNode(unitPicker).performClick()
        button("कोई और...").performClick()
        compose.onNode(hasText("कोई और यूनिट")).assertExists()
        compose.onNode(hasSetTextAction() and hasText("यूनिट")).assertExists()
        button("इस्तेमाल करें").assertIsNotEnabled()
        assertNoTextIsCut()
    }

    /**
     * Every text on screen (dialogs included) is laid out whole: none is cut at its last line or at its edge.
     * Wrapped texts pass; a text that had to be cut, or shrunk out of its box, fails with what it says.
     */
    private fun assertNoTextIsCut() {
        val withText = SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult)
        val nodes = compose.onAllNodes(withText, useUnmergedTree = true).fetchSemanticsNodes(atLeastOneRootRequired = false)
        assertTrue("no texts found", nodes.isNotEmpty())
        compose.runOnIdle {
            for (node in nodes) {
                val layouts = mutableListOf<TextLayoutResult>()
                node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layouts)
                for (layout in layouts) {
                    assertFalse("cut off: \"${layout.layoutInput.text}\"", layout.hasVisualOverflow)
                }
            }
        }
    }
}

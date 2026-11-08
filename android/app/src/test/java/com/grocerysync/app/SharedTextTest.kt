package com.grocerysync.app

import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Which intents bring a list into the app (Share > Kirana List), see [sharedText]. */
class SharedTextTest {

    @Test
    fun takesTheTextOfAShare() {
        assertEquals("Rice 2 kg\nDahi", sharedText(Intent.ACTION_SEND, "text/plain") { "Rice 2 kg\nDahi" })
    }

    @Test
    fun ignoresOtherIntentsAndEmptyTexts() {
        assertNull(sharedText(Intent.ACTION_MAIN, null) { "Rice" })
        assertNull(sharedText(Intent.ACTION_SEND, "image/png") { "Rice" })
        assertNull(sharedText(Intent.ACTION_SEND, null) { "Rice" })
        assertNull(sharedText(Intent.ACTION_SEND, "text/plain") { null })
        assertNull(sharedText(Intent.ACTION_SEND, "text/plain") { "  \n " })
    }

    @Test
    fun anExtraThatCantBeReadIsNoShare() {
        assertNull(sharedText(Intent.ACTION_SEND, "text/plain") { throw IllegalStateException("bad parcel") })
    }
}

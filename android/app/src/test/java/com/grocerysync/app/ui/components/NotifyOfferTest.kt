package com.grocerysync.app.ui.components

import com.grocerysync.app.data.PhoneSettings
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** When the list screen offers change notifications: once, on Android 13+, to a set-up phone without the permission. */
class NotifyOfferTest {

    private fun offered(
        phone: PhoneSettings = PhoneSettings(),
        isConfigured: Boolean = true,
        sdkInt: Int = 33,
        permissionGranted: Boolean = false,
    ) = shouldOfferNotifications(phone, isConfigured, sdkInt, permissionGranted)

    @Test
    fun offeredToASetUpPhoneOnAndroid13WithoutThePermission() {
        assertTrue(offered())
        assertTrue(offered(sdkInt = 36))
    }

    @Test
    fun notOfferedOtherwise() {
        assertFalse("granted already", offered(permissionGranted = true))
        assertFalse("answered already", offered(phone = PhoneSettings(notifyOfferDone = true)))
        assertFalse("turned off in Settings", offered(phone = PhoneSettings(notifyChanges = false)))
        assertFalse("not set up", offered(isConfigured = false))
        // Below Android 13 notifications need no permission: nothing to ask.
        assertFalse("Android 12L", offered(sdkInt = 32))
    }
}

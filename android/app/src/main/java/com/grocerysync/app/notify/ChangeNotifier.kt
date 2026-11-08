package com.grocerysync.app.notify

import android.Manifest
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.grocerysync.app.MainActivity
import com.grocerysync.app.R
import com.grocerysync.app.text.Texts

/** What [ChangeWatcher] needs from Android's notifications: [ChangeNotifier] is the real one, its tests use a fake. */
interface ChangeAlerts {
    /** Android lets the app show the change notification now ([notificationsAllowed]). */
    fun allowed(): Boolean

    /**
     * Shows [notice], replacing the one shown before; false when Android doesn't let the app (nothing shown).
     * [alert]: it sounds (as the person set the channel); false updates a notification still up quietly.
     */
    fun show(notice: ChangeNotice, alert: Boolean): Boolean

    /** Takes the notification away: the person is looking at the list. */
    fun cancel()

    /** The notification is still up: neither opened nor swiped away. */
    fun isShowing(): Boolean
}

/**
 * The change notification: one channel ("list_changes", importance default), one notification id, so a new check's
 * notification replaces the last one. Tapping it opens the app on To buy ([openAppIntent]) and takes it away. Never
 * throws for a missing permission: without POST_NOTIFICATIONS (Android 13+) or with the app's notifications turned
 * off, nothing is shown.
 */
class ChangeNotifier(context: Context) : ChangeAlerts {

    private val appContext = context.applicationContext
    private val manager = NotificationManagerCompat.from(appContext)

    /**
     * Creates the channel, or renames it: Android shows its name in the app's notification settings, so it follows
     * the language shown (GroceryApp calls this at app start, and every notification calls it again). A channel's
     * importance can't change once created; Android keeps what the person chose for it.
     */
    fun createChannel() {
        val texts = Texts.current.notify
        val channel = NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_DEFAULT)
            .setName(texts.channelName)
            .setDescription(texts.channelDescription)
            .build()
        manager.createNotificationChannel(channel)
    }

    override fun allowed(): Boolean = notificationsAllowed(appContext)

    override fun show(notice: ChangeNotice, alert: Boolean): Boolean {
        // Asked here as well (not only by allowed()): the permission can be taken away at any moment.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        if (!allowed()) return false
        createChannel()
        val notification = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(ContextCompat.getColor(appContext, R.color.brand_green))
            .setContentTitle(notice.title)
            .setContentText(notice.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(notice.text))
            .setContentIntent(openApp())
            .setAutoCancel(true)
            // Quiet while the one it replaces is still up; one that isn't up always sounds.
            .setOnlyAlertOnce(!alert)
            .build()
        return try {
            manager.notify(NOTIFICATION_ID, notification)
            true
        } catch (e: SecurityException) {
            // The permission went away between the check and now.
            false
        }
    }

    override fun cancel() {
        manager.cancel(NOTIFICATION_ID)
    }

    override fun isShowing(): Boolean =
        appContext.getSystemService(NotificationManager::class.java)
            ?.activeNotifications
            ?.any { it.id == NOTIFICATION_ID } == true

    private fun openApp(): PendingIntent =
        PendingIntent.getActivity(
            appContext,
            0,
            openAppIntent(appContext),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    companion object {
        /**
         * What tapping the notification starts: the app as its launcher icon opens it (the task it was left in, so
         * nothing typed there is lost), told to show To buy, which the notification is about
         * ([MainActivity.EXTRA_SHOW_TO_BUY]; an extra keeps the intent the launcher's, so no second app window opens).
         */
        internal fun openAppIntent(context: Context): Intent =
            Intent(context, MainActivity::class.java)
                .setAction(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(MainActivity.EXTRA_SHOW_TO_BUY, true)

        const val CHANNEL_ID = "list_changes"

        /** The change notification's id: one at a time. */
        const val NOTIFICATION_ID = 1
    }
}

/**
 * True when Android lets the app show the change notification: the app's notifications are on (on Android 13+ that
 * includes the POST_NOTIFICATIONS permission) and their channel isn't turned off.
 */
fun notificationsAllowed(context: Context): Boolean {
    val manager = NotificationManagerCompat.from(context)
    if (!hasNotificationPermission(context) || !manager.areNotificationsEnabled()) return false
    val channel = manager.getNotificationChannelCompat(ChangeNotifier.CHANNEL_ID)
    return channel == null || channel.importance != NotificationManagerCompat.IMPORTANCE_NONE
}

/** True below Android 13 (no such permission there) or when POST_NOTIFICATIONS is granted. */
fun hasNotificationPermission(context: Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
        PackageManager.PERMISSION_GRANTED

/**
 * Opens the app's notification settings in Android ("Allow in Android settings"), where notifications can be allowed
 * after the permission was refused; the app's own settings page on a phone without that screen.
 */
fun openNotificationSettings(context: Context) {
    val screens = listOf(
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
    )
    for (screen in screens) {
        try {
            context.startActivity(screen)
            return
        } catch (e: ActivityNotFoundException) {
            // Not on this phone: try the next one (with none at all there is nothing to open).
        }
    }
}

package se.besoksloggen;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.provider.Settings;

final class AddressNotifications {
    private static final String CHANNEL = "address_alerts";
    private static final int ALERT_ID = 2;

    private final Context context;
    private final NotificationManager manager;

    AddressNotifications(Context context) {
        this.context = context;
        manager = context.getSystemService(NotificationManager.class);
        NotificationChannel channel = new NotificationChannel(
                CHANNEL, "Adressvarningar", NotificationManager.IMPORTANCE_HIGH);
        channel.setDescription("Varnar när du har stannat längre än den inställda tiden vid en adress.");
        channel.enableVibration(true);
        channel.setLockscreenVisibility(Notification.VISIBILITY_PRIVATE);
        manager.createNotificationChannel(channel);
    }

    boolean enabled() {
        NotificationChannel channel = manager.getNotificationChannel(CHANNEL);
        return manager.areNotificationsEnabled() && channel != null
                && channel.getImportance() != NotificationManager.IMPORTANCE_NONE;
    }

    Intent settings() {
        if (!manager.areNotificationsEnabled()) {
            return new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.getPackageName());
        }
        return new Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.getPackageName())
                .putExtra(Settings.EXTRA_CHANNEL_ID, CHANNEL);
    }

    boolean send(VisitStore.AddressAlert alert, long visitStart) {
        if (!enabled()) return false;
        Intent open = new Intent(context, MainActivity.class);
        PendingIntent show = PendingIntent.getActivity(context, 10, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        String details = "Du har varit vid " + alert.title() + " i mer än " + alert.minutes() + " min.";
        Notification notification = new Notification.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_visit)
                .setContentTitle("Adressvarning")
                .setContentText(details)
                .setStyle(new Notification.BigTextStyle().bigText(details))
                .setContentIntent(show).setOnlyAlertOnce(true).setAutoCancel(true)
                .setVisibility(Notification.VISIBILITY_PRIVATE)
                .build();
        try {
            manager.notify(alert.id() + ":" + visitStart, ALERT_ID, notification);
            return true;
        } catch (SecurityException denied) {
            return false;
        }
    }
}

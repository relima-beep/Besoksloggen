package se.besoksloggen;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;

public final class ResumeReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())
                && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(intent.getAction())) return;
        VisitStore store = VisitStore.get(context);
        if (!store.enabled() || !TrackingService.hasPrecise(context)) return;
        store.writeTracking(store.tracker(), null);
        store.resetDwells();
        if (Build.VERSION.SDK_INT >= 29 && context.checkSelfPermission(
                Manifest.permission.ACCESS_BACKGROUND_LOCATION) != PackageManager.PERMISSION_GRANTED) return;
        try {
            context.startForegroundService(new Intent(context, TrackingService.class));
        } catch (RuntimeException ignored) {
            // The app can resume recording when next opened.
        }
    }
}

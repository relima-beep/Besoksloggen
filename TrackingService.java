package se.besoksloggen;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.location.Address;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.location.LocationRequest;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class TrackingService extends Service implements LocationListener {
    static final String PAUSE = "se.besoksloggen.PAUSE";
    private static final String CHANNEL = "location";
    private static final int NOTIFICATION_ID = 1;
    private static final long INTERVAL = 45_000L;
    private static final long GPS_FALLBACK = 150_000L;
    static volatile boolean running;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService geocoding = Executors.newSingleThreadExecutor();
    private final Set<Long> lookedUp = new HashSet<>();
    private VisitStore store;
    private AddressNotifications addressNotifications;
    private LocationManager location;
    private VisitLogic.Stop active;
    private boolean listening;
    private boolean gpsSubscribed;
    private long lastAcceptedElapsed;
    private long lastObservedWall;
    private long listeningSince;

    @Override public void onCreate() {
        super.onCreate();
        store = VisitStore.get(this);
        addressNotifications = new AddressNotifications(this);
        location = getSystemService(LocationManager.class);
        NotificationChannel channel = new NotificationChannel(CHANNEL, "Besöksregistrering",
                NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Visas när Besöksloggen använder plats i bakgrunden.");
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        // A service started with startForegroundService must enter the foreground promptly.
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
            } else {
                startForeground(NOTIFICATION_ID, notification());
            }
        } catch (RuntimeException e) {
            running = false;
            stopSelf();
            return START_NOT_STICKY;
        }
        if (intent != null && PAUSE.equals(intent.getAction())) store.setEnabled(false);
        if (!store.enabled() || !hasPrecise(this)) {
            VisitLogic.Stop saved = store.tracker();
            store.writeTracking(saved, null);
            store.resetDwells();
            stopNow();
            return START_NOT_STICKY;
        }
        if (!listening) {
            active = store.tracker();
            listening = true;
            running = true;
            listeningSince = SystemClock.elapsedRealtime();
            if (Build.VERSION.SDK_INT >= 31 && location.getAllProviders().contains("fused")) {
                LocationRequest request = new LocationRequest.Builder(INTERVAL)
                        .setMinUpdateIntervalMillis(25_000)
                        .setQuality(LocationRequest.QUALITY_BALANCED_POWER_ACCURACY).build();
                try {
                    location.requestLocationUpdates("fused", request, getMainExecutor(), this);
                } catch (IllegalArgumentException | SecurityException e) {
                    startGps();
                }
            } else {
                startGps();
                addProvider(LocationManager.NETWORK_PROVIDER);
            }
            handler.postDelayed(checkFixes, GPS_FALLBACK);
        }
        return START_STICKY;
    }

    private final Runnable checkFixes = new Runnable() {
        @Override public void run() {
            if (!listening) return;
            if (SystemClock.elapsedRealtime() - Math.max(listeningSince, lastAcceptedElapsed) >= GPS_FALLBACK) {
                startGps();
            }
            handler.postDelayed(this, 60_000L);
        }
    };

    private void startGps() {
        if (!gpsSubscribed) gpsSubscribed = addProvider(LocationManager.GPS_PROVIDER);
    }

    private boolean addProvider(String provider) {
        try {
            if (!location.getAllProviders().contains(provider)) return false;
            location.requestLocationUpdates(provider, INTERVAL, 0f, this, Looper.getMainLooper());
            return true;
        } catch (IllegalArgumentException | SecurityException e) {
            return false;
        }
    }

    static boolean hasPrecise(Context context) {
        return context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    @Override public void onLocationChanged(Location result) {
        if (!running || !store.enabled() || !result.hasAccuracy()) return;
        long nowElapsed = SystemClock.elapsedRealtime();
        long locationElapsed = result.getElapsedRealtimeNanos() / 1_000_000L;
        long age = nowElapsed - locationElapsed;
        if (age > 120_000 || age < -500 || (lastAcceptedElapsed != 0 && locationElapsed - lastAcceptedElapsed < 22_000)
                || !Float.isFinite(result.getAccuracy()) || result.getAccuracy() <= 0
                || result.getAccuracy() > 85) return;
        long observed = System.currentTimeMillis() - Math.max(0, age);
        boolean clockChanged = lastObservedWall != 0 && (observed < lastObservedWall - VisitLogic.MINUTE
                || observed > lastObservedWall + VisitLogic.MAX_GAP
                    && locationElapsed - lastAcceptedElapsed < VisitLogic.MAX_GAP);
        if (clockChanged || active != null && observed < active.lastInside() - VisitLogic.MINUTE) {
            store.writeTracking(active, null);
            store.resetDwells();
            active = null;
        }
        lastAcceptedElapsed = locationElapsed;
        lastObservedWall = observed;
        store.setLastFix(observed);
        VisitLogic.Fix fix = new VisitLogic.Fix(observed, result.getLatitude(), result.getLongitude(),
                result.getAccuracy());
        updateAlerts(fix);
        VisitLogic.Change change = VisitLogic.step(active, fix, store.places());
        if (change.active() != active || change.ended() != null) {
            store.writeTracking(change.ended(), change.active());
            active = change.active();
            resolve(change.ended());
            resolve(active);
        }
    }

    private void updateAlerts(VisitLogic.Fix fix) {
        for (VisitStore.AddressAlert alert : store.alerts()) {
            if (fix.accuracy() > alert.radius() && VisitLogic.distance(
                    fix.lat(), fix.lon(), alert.lat(), alert.lon()) <= alert.radius() + fix.accuracy()) {
                startGps();
            }
            VisitLogic.Dwell dwell = alert.dwell().step(fix, alert.lat(), alert.lon(), alert.radius());
            if (dwell.due(alert.minutes()) && addressNotifications.send(alert, dwell.entered())) {
                dwell = dwell.markNotified();
            }
            if (!dwell.equals(alert.dwell())) store.saveDwell(alert.id(), dwell);
        }
    }

    private void resolve(VisitLogic.Stop stop) {
        if (stop == null || !stop.qualifies(store.visitMinutes()) || !stop.address().trim().isEmpty()
                || !lookedUp.add(stop.start())) return;
        geocoding.execute(() -> {
            Address address = AddressLookup.reverse(this, stop.lat(), stop.lon());
            if (address != null) {
                String venue = AddressLookup.venue(address);
                store.addAddress(stop.start(), AddressLookup.line(address), venue, AddressLookup.category(venue));
            }
        });
    }

    private Notification notification() {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent show = PendingIntent.getActivity(this, 10, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Intent pause = new Intent(this, TrackingService.class).setAction(PAUSE);
        PendingIntent pauseAction = PendingIntent.getService(this, 11, pause,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_visit)
                .setContentTitle("Besöksloggen är aktiv")
                .setContentText("Registrerar besök och bevakar dina adressvarningar")
                .setContentIntent(show).setOngoing(true).setShowWhen(false).setOnlyAlertOnce(true)
                .setVisibility(Notification.VISIBILITY_PRIVATE)
                .addAction(new Notification.Action.Builder(null, "Pausa", pauseAction).build())
                .build();
    }

    private void stopNow() {
        running = false;
        listening = false;
        handler.removeCallbacks(checkFixes);
        try {
            location.removeUpdates(this);
        } catch (SecurityException e) {
            Log.d("Besöksloggen", "Platsbehörigheten har återkallats.");
        }
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    @Override public void onDestroy() {
        running = false;
        listening = false;
        handler.removeCallbacks(checkFixes);
        try {
            location.removeUpdates(this);
        } catch (SecurityException ignored) {}
        geocoding.shutdown();
        super.onDestroy();
    }

    @Override public void onProviderEnabled(String provider) {}
    @Override public void onProviderDisabled(String provider) {}
    @Override public void onStatusChanged(String provider, int status, Bundle extras) {}
    @Override public IBinder onBind(Intent intent) { return null; }
}

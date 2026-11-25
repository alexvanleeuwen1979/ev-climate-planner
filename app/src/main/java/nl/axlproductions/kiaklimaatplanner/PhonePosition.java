package nl.axlproductions.kiaklimaatplanner;

import android.annotation.SuppressLint;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import java.util.function.Consumer;

final class PhonePosition implements LocationListener {
    private static final long MAX_AGE_NANOS = 30_000_000_000L;
    private static final float MAX_ACCURACY_METRES = 50;
    private final LocationManager manager;
    private final Handler handler;
    private final Consumer<Location> result;
    private final Runnable deadline = () -> finish(null);
    private boolean finished;

    PhonePosition(LocationManager manager, Handler handler, Consumer<Location> result) {
        this.manager = manager;
        this.handler = handler;
        this.result = result;
    }

    static boolean suitable(Location fix, long nowNanos) {
        if (fix == null || !fix.hasAccuracy()) return false;
        long age = nowNanos - fix.getElapsedRealtimeNanos();
        return age >= 0 && age <= MAX_AGE_NANOS
            && fix.getAccuracy() > 0 && fix.getAccuracy() <= MAX_ACCURACY_METRES
            && Math.abs(fix.getLatitude()) <= 90 && Math.abs(fix.getLongitude()) <= 180;
    }

    @SuppressLint("MissingPermission")
    void start() {
        boolean listening = false;
        for (String provider : new String[]{LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER}) {
            try {
                if (!manager.isProviderEnabled(provider)) continue;
                var last = manager.getLastKnownLocation(provider);
                if (suitable(last, SystemClock.elapsedRealtimeNanos())) {
                    finish(last);
                    return;
                }
                manager.requestLocationUpdates(provider, 1_000, 0, this, Looper.getMainLooper());
                listening = true;
            } catch (IllegalArgumentException | SecurityException ignored) {
                // A provider can be unavailable on a device or change while the request is open.
            }
        }
        handler.postDelayed(deadline, listening ? 25_000 : 0);
    }

    @Override public void onLocationChanged(Location fix) {
        if (suitable(fix, SystemClock.elapsedRealtimeNanos())) finish(fix);
    }

    @Override public void onProviderEnabled(String provider) {}
    @Override public void onProviderDisabled(String provider) {}
    @Override public void onStatusChanged(String provider, int status, android.os.Bundle extras) {}

    private void finish(Location fix) {
        if (finished) return;
        cancel();
        result.accept(fix);
    }

    void cancel() {
        if (finished) return;
        finished = true;
        handler.removeCallbacks(deadline);
        try {
            manager.removeUpdates(this);
        } catch (SecurityException ignored) {
            // Permission may have been revoked while the dialog was visible.
        }
    }
}

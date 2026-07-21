package dev.myvu.sdk.weather;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Looper;

import dev.myvu.sdk.util.SdkLog;

import java.util.List;

/**
 * The phone's own position, via the platform {@link LocationManager} -- no
 * Google Play Services, so weather never drags in a location dependency.
 *
 * A last-known fix is used when one exists, because weather is happy with a
 * position that is minutes (or kilometres) stale and that path costs nothing.
 * Only when there is none do we ask for a live update, and it is cancelled
 * after the first fix: a half-hourly sync has no business holding a GPS stream
 * open.
 *
 * Requires ACCESS_COARSE_LOCATION (or fine) to be granted; without it the
 * request fails cleanly and WeatherSync retries later.
 */
public class DeviceWeatherLocation implements WeatherLocation {

    /** Older than this and we prefer asking for a fresh fix. */
    private static final long STALE_MS = 30 * 60 * 1000L;

    private final Context context;
    private final LocationManager manager;
    private LocationListener active;

    public DeviceWeatherLocation(Context context) {
        this.context = context.getApplicationContext();
        this.manager = (LocationManager) this.context.getSystemService(Context.LOCATION_SERVICE);
    }

    @Override
    public void requestFix(final Callback callback) {
        if (!hasPermission()) {
            callback.onUnavailable("location permission not granted");
            return;
        }
        if (manager == null) {
            callback.onUnavailable("no LocationManager on this device");
            return;
        }

        Location best = lastKnown();
        if (best != null && System.currentTimeMillis() - best.getTime() < STALE_MS) {
            callback.onLocation(best.getLatitude(), best.getLongitude(), null);
            return;
        }

        // Nothing recent: ask for one live update.
        final Location fallback = best;
        try {
            String provider = manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
                    ? LocationManager.NETWORK_PROVIDER : LocationManager.GPS_PROVIDER;
            cancel();
            active = new LocationListener() {
                @Override
                public void onLocationChanged(Location l) {
                    cancel();
                    callback.onLocation(l.getLatitude(), l.getLongitude(), null);
                }

                @Override public void onStatusChanged(String p, int s, Bundle e) {}
                @Override public void onProviderEnabled(String p) {}
                @Override public void onProviderDisabled(String p) {}
            };
            manager.requestLocationUpdates(provider, 0L, 0f, active, Looper.getMainLooper());
        } catch (SecurityException e) {
            deliverFallback(fallback, callback, "location permission revoked");
        } catch (IllegalArgumentException e) {
            deliverFallback(fallback, callback, "no usable location provider");
        }
    }

    /**
     * A stale fix still beats no weather at all, so it is used rather than
     * failing the round outright.
     */
    private void deliverFallback(Location fallback, Callback callback, String reason) {
        if (fallback != null) {
            SdkLog.trace("weather: " + reason + "; using a stale fix");
            callback.onLocation(fallback.getLatitude(), fallback.getLongitude(), null);
        } else {
            callback.onUnavailable(reason);
        }
    }

    /** The most recent fix across all providers, or null. */
    private Location lastKnown() {
        Location best = null;
        try {
            List<String> providers = manager.getProviders(true);
            for (String p : providers) {
                Location l = manager.getLastKnownLocation(p);
                if (l != null && (best == null || l.getTime() > best.getTime())) best = l;
            }
        } catch (SecurityException ignored) {
        }
        return best;
    }

    private boolean hasPermission() {
        return context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
                        == PackageManager.PERMISSION_GRANTED
                || context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                        == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void cancel() {
        if (active != null && manager != null) {
            try {
                manager.removeUpdates(active);
            } catch (SecurityException ignored) {
            }
            active = null;
        }
    }
}

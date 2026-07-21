package dev.myvu.sdk.nav;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Looper;

import dev.myvu.sdk.util.SdkLog;

/**
 * Platform {@link LocationManager}-backed {@link LocationSource} with no Google
 * Play Services dependency.
 *
 * A drop-in alternative to {@link FusedLocationSource} for apps that exclude
 * play-services-location or run on devices without Google Play. Fixes are
 * coarser and less battery-friendly than the fused provider.
 */
public class LocationManagerSource implements LocationSource {

    private static final long INTERVAL_MS = 1000;
    private static final float MIN_DISTANCE_M = 0f;

    private final Context context;
    private final LocationManager manager;
    private LocationListener listener;

    public LocationManagerSource(Context context) {
        this.context = context.getApplicationContext();
        this.manager = (LocationManager) this.context.getSystemService(Context.LOCATION_SERVICE);
    }

    @Override
    public void start(final Listener out) {
        if (context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            out.onUnavailable("location permission not granted");
            return;
        }
        if (manager == null) {
            out.onUnavailable("no LocationManager on this device");
            return;
        }

        listener = new LocationListener() {
            @Override
            public void onLocationChanged(Location l) {
                out.onFix(
                        l.getLatitude(),
                        l.getLongitude(),
                        l.hasSpeed() ? l.getSpeed() : -1f,
                        l.hasBearing() ? l.getBearing() : -1f);
            }

            @Override public void onStatusChanged(String provider, int status, Bundle extras) {}
            @Override public void onProviderEnabled(String provider) {}
            @Override public void onProviderDisabled(String provider) {}
        };

        try {
            String provider = manager.isProviderEnabled(LocationManager.GPS_PROVIDER)
                    ? LocationManager.GPS_PROVIDER : LocationManager.NETWORK_PROVIDER;
            manager.requestLocationUpdates(provider, INTERVAL_MS, MIN_DISTANCE_M,
                    listener, Looper.getMainLooper());
            SdkLog.log("location updates started (LocationManager " + provider + ")");
        } catch (SecurityException e) {
            out.onUnavailable("location permission revoked: " + e.getMessage());
        } catch (IllegalArgumentException e) {
            out.onUnavailable("no usable location provider: " + e.getMessage());
        }
    }

    @Override
    public void stop() {
        if (listener != null) {
            manager.removeUpdates(listener);
            listener = null;
            SdkLog.log("location updates stopped");
        }
    }
}

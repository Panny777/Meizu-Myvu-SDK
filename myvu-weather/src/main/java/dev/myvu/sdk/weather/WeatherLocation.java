package dev.myvu.sdk.weather;

/**
 * Supplies the point a weather reading should describe.
 *
 * {@link WeatherSync} calls {@link #requestFix} on a background thread, so
 * implementations may block (a geocode lookup) or answer asynchronously (a GPS
 * fix). Exactly one callback method must be invoked per request; WeatherSync
 * bounds the wait itself, so an implementation that never answers only delays
 * one round rather than wedging the cycle.
 */
public interface WeatherLocation {

    interface Callback {
        /** @param areaName shown on the lens, or null to leave it off the wire. */
        void onLocation(double lat, double lon, String areaName);

        void onUnavailable(String reason);
    }

    void requestFix(Callback callback);

    /** Releases anything held for an in-flight request (e.g. GPS updates). */
    default void cancel() {}
}

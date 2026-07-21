package dev.myvu.sdk.weather;

/**
 * A constant point. Use when the app already knows where to report for and
 * wants no location permission and no geocoding round-trip.
 */
public class FixedWeatherLocation implements WeatherLocation {

    private final double lat;
    private final double lon;
    private final String areaName;

    public FixedWeatherLocation(double lat, double lon, String areaName) {
        this.lat = lat;
        this.lon = lon;
        this.areaName = areaName;
    }

    @Override
    public void requestFix(Callback callback) {
        callback.onLocation(lat, lon, areaName);
    }
}

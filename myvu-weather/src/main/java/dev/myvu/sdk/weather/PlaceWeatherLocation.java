package dev.myvu.sdk.weather;

/**
 * A named place ("Dar es Salaam") or a literal "lat,lon" string, resolved
 * through Open-Meteo's geocoder. Needs no location permission.
 *
 * The resolved coordinates are cached: a place name does not move, so the
 * geocode happens once rather than on every half-hourly refresh.
 */
public class PlaceWeatherLocation implements WeatherLocation {

    private final String place;
    private OpenMeteo.Place resolved;

    public PlaceWeatherLocation(String place) {
        this.place = place;
    }

    @Override
    public void requestFix(Callback callback) {
        try {
            if (resolved == null) {
                resolved = OpenMeteo.resolve(place); // blocking; called off the conn thread
            }
            callback.onLocation(resolved.lat, resolved.lon, resolved.name);
        } catch (Exception e) {
            callback.onUnavailable("could not resolve \"" + place + "\": " + e.getMessage());
        }
    }
}

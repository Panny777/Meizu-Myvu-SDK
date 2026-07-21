package dev.myvu.sdk.nav;

import java.io.IOException;

/**
 * Resolves a destination and computes a route to it.
 *
 * The default {@link OsrmRouteProvider} uses the public OSRM demo server plus
 * Nominatim geocoding; supply your own to point at a self-hosted router or a
 * different routing service.
 */
public interface RouteProvider {

    /**
     * @param fromLat     current latitude
     * @param fromLon     current longitude
     * @param destination a place name or a literal "lat,lon" pair
     */
    Route route(double fromLat, double fromLon, String destination) throws IOException;
}

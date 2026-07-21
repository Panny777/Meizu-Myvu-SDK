package dev.myvu.sdk.nav;

import java.io.IOException;

/**
 * Default {@link RouteProvider}: geocodes with Nominatim and routes with the
 * public OSRM demo server (see {@link Osrm}). The demo server has no SLA -- for
 * production, point a custom provider at your own OSRM instance.
 */
public final class OsrmRouteProvider implements RouteProvider {

    private final String profile;

    public OsrmRouteProvider() {
        this("driving");
    }

    /** @param profile OSRM profile: driving, walking or cycling. */
    public OsrmRouteProvider(String profile) {
        this.profile = profile;
    }

    @Override
    public Route route(double fromLat, double fromLon, String destination) throws IOException {
        double[] dest = Osrm.parsePoint(destination);
        return Osrm.route(fromLat, fromLon, dest[0], dest[1], profile);
    }
}

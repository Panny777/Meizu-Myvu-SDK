package dev.myvu.sdk.weather;

import android.os.Handler;

import dev.myvu.sdk.ConnectionState;
import dev.myvu.sdk.MyvuClient;
import dev.myvu.sdk.app.feature.Weather;
import dev.myvu.sdk.event.GlassesEvent;
import dev.myvu.sdk.util.SdkLog;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Keeps the glasses' weather panel up to date.
 *
 * Cadence mirrors the official app exactly: push on connect, then refresh every
 * 30 minutes, and retry after 30 seconds on failure.
 *
 * THREADING: refresh() may be called from any thread; all network and location
 * work happens off the connection thread and the send is posted back onto it.
 */
public class WeatherSync {

    /** How often to refresh, matching the official app's WeatherMonitor. */
    private static final long REFRESH_MS = 30 * 60 * 1000L;
    /** Its retry delay after a failed query. */
    private static final long RETRY_MS = 30 * 1000L;
    /** Bound on waiting for a location fix before giving up on this round. */
    private static final long FIX_TIMEOUT_MS = 20 * 1000L;

    /** Where a built payload goes; the MyvuClient constructor wires this for you. */
    public interface Sender {
        void send(String actionJson);
    }

    private final Handler conn;
    private final Sender sender;
    private final WeatherLocation location;
    private final MyvuClient client;
    private final ExecutorService net = Executors.newSingleThreadExecutor();

    private boolean running;
    /** Guards against two overlapping rounds (timer firing while one is in flight). */
    private boolean inFlight;

    /** Pushes weather over {@code client}, for the point {@code location} reports. */
    public WeatherSync(MyvuClient client, WeatherLocation location) {
        this.client = client;
        this.conn = client.connectionHandler();
        this.location = location;
        this.sender = new Sender() {
            @Override
            public void send(String actionJson) {
                // Default routing is the launcher, which is where the official
                // app sends weather too.
                client.sendAction(actionJson);
            }
        };
    }

    /** Low-level constructor for tests or a custom transport. */
    public WeatherSync(Handler conn, Sender sender, WeatherLocation location) {
        this.client = null;
        this.conn = conn;
        this.sender = sender;
        this.location = location;
    }

    // ------------------------------------------------------------- attach

    private final MyvuClient.Listener listener = new MyvuClient.Listener() {
        @Override
        public void onConnectionStateChanged(ConnectionState state) {
            // Every connect -- including a relay reconnect -- should land fresh
            // weather, exactly like the clock and settings the SDK re-applies.
            if (state == ConnectionState.READY) start();
        }

        @Override
        public void onEvent(GlassesEvent event) {
            if (event instanceof GlassesEvent.WeatherRequested) refresh();
        }
    };

    /**
     * Syncs automatically: pushes whenever the glasses become READY and answers
     * their {@code syncWeather} requests. Requires the MyvuClient constructor.
     */
    public void attach() {
        if (client == null) {
            throw new IllegalStateException("attach() requires the MyvuClient constructor");
        }
        client.addListener(listener);
        if (client.state() == ConnectionState.READY) start();
    }

    public void detach() {
        if (client != null) client.removeListener(listener);
        stop();
    }

    // -------------------------------------------------------------- cycle

    /**
     * Begins the cycle AND pushes immediately. Safe to call repeatedly.
     *
     * It deliberately does not bail out when already running: the glasses expect
     * fresh state on every connect, and an early return there left them showing
     * whatever the weather was when the app last started. Re-entry is harmless --
     * refresh() has its own in-flight guard, and done() clears the pending timer
     * before scheduling the next one, so no duplicate timers accumulate.
     */
    public void start() {
        running = true;
        refresh();
    }

    public void stop() {
        running = false;
        conn.removeCallbacks(refreshTick);
        location.cancel();
    }

    /** Releases the executor; the instance is dead afterwards. */
    public void shutdown() {
        stop();
        net.shutdownNow();
    }

    private final Runnable refreshTick = new Runnable() {
        @Override
        public void run() {
            refresh();
        }
    };

    /** Runs one round now, and schedules the next. */
    public void refresh() {
        conn.post(new Runnable() {
            @Override
            public void run() {
                if (inFlight) return;
                inFlight = true;
                requestFix();
            }
        });
    }

    // ----------------------------------------------------------- location

    private void requestFix() {
        final boolean[] done = { false };
        // If no fix arrives we must still release inFlight and retry later,
        // otherwise a permission-less device would wedge the cycle forever.
        final Runnable timeout = new Runnable() {
            @Override
            public void run() {
                if (done[0]) return;
                done[0] = true;
                location.cancel();
                fail("no location fix for weather", null);
            }
        };
        conn.postDelayed(timeout, FIX_TIMEOUT_MS);

        // Off the connection thread: a provider may block (geocoding).
        net.execute(new Runnable() {
            @Override
            public void run() {
                location.requestFix(new WeatherLocation.Callback() {
                    @Override
                    public void onLocation(double lat, double lon, String areaName) {
                        if (done[0]) return;
                        done[0] = true;
                        conn.removeCallbacks(timeout);
                        fetchAndSend(lat, lon, areaName);
                    }

                    @Override
                    public void onUnavailable(String reason) {
                        if (done[0]) return;
                        done[0] = true;
                        conn.removeCallbacks(timeout);
                        fail("weather location unavailable: " + reason, null);
                    }
                });
            }
        });
    }

    // -------------------------------------------------------------- fetch

    /** May be called on either the net executor or a provider's callback thread. */
    private void fetchAndSend(final double lat, final double lon, final String areaName) {
        net.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    final Weather.Reading r = OpenMeteo.fetch(lat, lon, areaName);
                    final String json = Weather.build(r);
                    conn.post(new Runnable() {
                        @Override
                        public void run() {
                            sender.send(json);
                            SdkLog.log("weather synced: " + r.condition + " " + r.temp + "°C"
                                    + (r.areaName == null ? "" : " (" + r.areaName + ")"));
                            done(REFRESH_MS);
                        }
                    });
                } catch (Exception e) {
                    fail("weather fetch failed", e);
                }
            }
        });
    }

    private void fail(final String message, final Exception e) {
        conn.post(new Runnable() {
            @Override
            public void run() {
                if (e != null) SdkLog.warn(message + ": " + e);
                else SdkLog.warn(message);
                done(RETRY_MS);
            }
        });
    }

    /** Releases the in-flight guard and schedules the next round. */
    private void done(long nextInMs) {
        inFlight = false;
        conn.removeCallbacks(refreshTick);
        if (running) conn.postDelayed(refreshTick, nextInMs);
    }
}

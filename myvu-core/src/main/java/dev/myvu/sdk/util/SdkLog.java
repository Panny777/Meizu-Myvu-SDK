package dev.myvu.sdk.util;

import android.util.Log;

/**
 * Static logging facade used throughout the SDK.
 *
 * Keeps the same call shape as the original client's LogBus (log/warn/error/
 * trace) so ported protocol code is unchanged apart from the import, but
 * delegates to a swappable {@link MyvuLogger}. The default sink writes to
 * logcat under the {@value #TAG} tag.
 *
 * Every Android touchpoint is guarded: android.jar's classes are non-functional
 * stubs under JVM unit tests, and without the guards merely logging would make
 * every pure-logic class that logs untestable off-device.
 */
public final class SdkLog {
    private SdkLog() {}

    public static final String TAG = "myvu-sdk";

    private static final MyvuLogger DEFAULT = new MyvuLogger() {
        @Override
        public void log(MyvuLogger.Level level, String message, Throwable error) {
            try {
                switch (level) {
                    case TRACE: Log.d(TAG, message); break;
                    case WARN:  Log.w(TAG, message); break;
                    case ERROR: Log.e(TAG, message, error); break;
                    default:    Log.i(TAG, message); break;
                }
            } catch (Throwable ignored) {
                // Stubbed android.util.Log under JVM unit tests.
            }
        }
    };

    private static volatile MyvuLogger logger = DEFAULT;

    /** Installs a custom sink; null restores the logcat default. */
    public static void setLogger(MyvuLogger l) {
        logger = l != null ? l : DEFAULT;
    }

    public static void log(String msg) {
        logger.log(MyvuLogger.Level.INFO, msg, null);
    }

    public static void warn(String msg) {
        logger.log(MyvuLogger.Level.WARN, msg, null);
    }

    public static void error(String msg, Throwable t) {
        logger.log(MyvuLogger.Level.ERROR, msg, t);
    }

    /** Verbose frame-level detail; sinks may route it away from user-facing panes. */
    public static void trace(String msg) {
        logger.log(MyvuLogger.Level.TRACE, msg, null);
    }
}

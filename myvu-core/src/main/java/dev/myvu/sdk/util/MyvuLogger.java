package dev.myvu.sdk.util;

/**
 * Pluggable sink for the SDK's diagnostic output.
 *
 * The connection stack narrates everything it does (handshake steps, relay
 * lifecycle, inbound frames); apps that want an on-screen log pane or file
 * capture install their own implementation via {@link SdkLog#setLogger}.
 * Implementations may be called from any SDK thread and must not block.
 */
public interface MyvuLogger {

    enum Level { TRACE, INFO, WARN, ERROR }

    void log(Level level, String message, Throwable error);
}

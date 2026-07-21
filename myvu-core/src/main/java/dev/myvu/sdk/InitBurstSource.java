package dev.myvu.sdk;

import java.io.IOException;
import java.io.InputStream;

/**
 * Supplies the captured init-burst replayed on every new session.
 *
 * The glasses' relay dispatcher does not fully wake until it has seen a clean
 * sequence of opening app messages, so the SDK replays a capture of the
 * official app's opening traffic (with fresh msgIds) after each handshake.
 * The default source reads the capture bundled with the SDK; supply your own
 * only if you have a newer capture for your firmware.
 */
public interface InitBurstSource {

    /** Opens the capture; the caller closes the stream. */
    InputStream open() throws IOException;
}

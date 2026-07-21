package dev.myvu.sample;

import android.os.Handler;
import android.os.Looper;

import dev.myvu.sdk.util.MyvuLogger;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A {@link MyvuLogger} that keeps a rolling on-screen log and forwards lines to
 * attached listeners on the main thread.
 *
 * Installed via {@code SdkLog.setLogger(...)} so the sample can show the SDK's
 * connection narration in a text pane.
 */
public class LogRingBuffer implements MyvuLogger {

    public interface Listener {
        void onLine(String line);
    }

    private static final int CAPACITY = 1000;

    private final Deque<String> buffer = new ArrayDeque<>(CAPACITY);
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private final SimpleDateFormat stamp = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);
    private final Handler main = new Handler(Looper.getMainLooper());

    @Override
    public void log(Level level, String message, Throwable error) {
        if (level == Level.TRACE) return; // keep the pane readable
        String prefix = level == Level.WARN || level == Level.ERROR ? "!! " : "";
        String detail = error != null
                ? message + ": " + error.getClass().getSimpleName() + ": " + error.getMessage()
                : message;
        emit(stamp.format(new Date()) + "  " + prefix + detail);
    }

    private void emit(final String line) {
        synchronized (buffer) {
            if (buffer.size() >= CAPACITY) buffer.removeFirst();
            buffer.addLast(line);
        }
        if (listeners.isEmpty()) return;
        main.post(new Runnable() {
            @Override
            public void run() {
                for (Listener l : listeners) l.onLine(line);
            }
        });
    }

    public List<String> history() {
        synchronized (buffer) {
            return new ArrayList<>(buffer);
        }
    }

    public void addListener(Listener l) {
        listeners.addIfAbsent(l);
    }

    public void removeListener(Listener l) {
        listeners.remove(l);
    }
}

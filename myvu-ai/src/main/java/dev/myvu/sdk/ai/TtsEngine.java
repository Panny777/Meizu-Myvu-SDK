package dev.myvu.sdk.ai;

/**
 * Speaks the assistant's answer aloud.
 *
 * The glasses' play-state protocol is gated on the real completion callback
 * (never a timer), so implementations MUST invoke {@link Callback#onSpoken}
 * exactly once when speech finishes or fails. {@link TtsPlayer} is the default
 * platform-TextToSpeech implementation.
 */
public interface TtsEngine {

    interface Callback {
        void onSpoken(boolean success);
    }

    /** Prepares the engine; may be called more than once. */
    void init();

    void speak(String text, Callback cb);

    void shutdown();
}

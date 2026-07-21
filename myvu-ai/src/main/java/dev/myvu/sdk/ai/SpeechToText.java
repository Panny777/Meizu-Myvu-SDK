package dev.myvu.sdk.ai;

import java.io.IOException;

/**
 * Transcribes the glasses' captured microphone audio to text.
 *
 * The SDK decodes the glasses' Opus stream to PCM and hands it here; supply an
 * engine backed by whatever recognizer you like (a cloud API, on-device
 * {@code SpeechRecognizer}, etc.). The sample app ships a Groq Whisper adapter.
 */
public interface SpeechToText {

    /** False disables the voice path (e.g. no API key configured). */
    boolean isReady();

    /**
     * @param pcm        signed 16-bit little-endian PCM
     * @param sampleRate samples per second
     * @param channels   1 for mono
     * @return the recognized text, or null/empty if nothing was understood
     */
    String transcribe(byte[] pcm, int sampleRate, int channels) throws IOException;
}

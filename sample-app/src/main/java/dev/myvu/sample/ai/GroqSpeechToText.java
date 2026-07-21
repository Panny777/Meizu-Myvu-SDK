package dev.myvu.sample.ai;

import dev.myvu.sdk.ai.SpeechToText;

import java.io.IOException;

/** Adapts {@link GroqWhisper} to the SDK's {@link SpeechToText} interface. */
public class GroqSpeechToText implements SpeechToText {

    private final GroqWhisper whisper;

    public GroqSpeechToText(String apiKey) {
        this.whisper = new GroqWhisper(apiKey);
    }

    @Override
    public boolean isReady() {
        return whisper.hasKey();
    }

    @Override
    public String transcribe(byte[] pcm, int sampleRate, int channels) throws IOException {
        return whisper.transcribe(pcm, sampleRate, channels);
    }
}

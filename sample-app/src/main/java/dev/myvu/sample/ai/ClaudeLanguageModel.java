package dev.myvu.sample.ai;

import dev.myvu.sdk.ai.LanguageModel;

import java.io.IOException;

/** Adapts {@link ClaudeClient} to the SDK's {@link LanguageModel} interface. */
public class ClaudeLanguageModel implements LanguageModel {

    private final ClaudeClient client;

    public ClaudeLanguageModel(String apiKey, String systemPrompt) {
        this.client = new ClaudeClient(apiKey, systemPrompt);
    }

    @Override
    public boolean isReady() {
        return client.hasKey();
    }

    @Override
    public String reply(String question) throws IOException {
        return client.ask(question);
    }
}

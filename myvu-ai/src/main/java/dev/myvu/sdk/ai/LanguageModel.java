package dev.myvu.sdk.ai;

import java.io.IOException;

/**
 * Produces an assistant answer for a recognized (or typed) question.
 *
 * Kept deliberately simple -- one question in, one answer out -- so any LLM
 * backend fits. The sample app ships a Claude adapter; conversation history and
 * system-prompt handling, if wanted, live inside the implementation.
 */
public interface LanguageModel {

    /** False disables answering (e.g. no API key configured). */
    boolean isReady();

    /** @return the answer text to caption and speak on the glasses. */
    String reply(String question) throws IOException;
}

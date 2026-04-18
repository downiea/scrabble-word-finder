package com.scrabble.service.vision;

/**
 * Abstraction over vision LLM APIs. Each provider handles its own HTTP call
 * and returns the plain text response content. Prompt building and JSON parsing
 * are handled by BoardVisionService, which is provider-agnostic.
 */
public interface VisionProvider {

    /** Unique identifier used in config (e.g. "claude", "openai", "gemini"). */
    String getName();

    /**
     * Full board read — uses the highest-quality settings available for the provider
     * (extended thinking for Claude, high token limit for others).
     *
     * @param imageBytes raw image bytes (PNG or JPEG)
     * @param mediaType  MIME type, e.g. "image/png"
     * @param prompt     the full text prompt
     * @return plain text response from the model
     */
    String callVision(byte[] imageBytes, String mediaType, String prompt) throws Exception;

    /**
     * Lightweight call for reading rack tiles only — lower cost and token usage.
     */
    String callVisionSimple(byte[] imageBytes, String mediaType, String prompt) throws Exception;

    /** True if the API key is configured and the provider is usable. */
    boolean isAvailable();
}

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

    /**
     * True if this provider can accept multiple images in a single request.
     * Used to decide whether to use cell-batch extraction.
     */
    default boolean supportsMultiImage() { return false; }

    /**
     * Send multiple cell images in one request. Images are in reading order
     * (left-to-right, top-to-bottom: cell 0 = A1, cell 14 = O1, cell 15 = A2 …).
     * Only called when {@link #supportsMultiImage()} returns true.
     */
    default String callVisionBatch(java.util.List<byte[]> images, String mediaType, String prompt) throws Exception {
        throw new UnsupportedOperationException(getName() + " does not support multi-image batch calls");
    }
}

package com.lectureprocessor.multilingual;

/**
 * A proposed re-translation of one segment. Never persisted automatically: the
 * user previews it and explicitly accepts it in the editor.
 *
 * @param sequence       transcript segment id
 * @param previousText   current (possibly machine) translation
 * @param suggestedText  new candidate
 * @param provider       provider id that produced the candidate
 * @param model          model id that produced the candidate
 * @param quality        quality label ({@code OK} or a warning category)
 * @param warning        explainable warning, {@code null} when clean
 * @param cloud          true when a cloud provider was used
 * @param inputTokens    reported input tokens (0 when unknown)
 * @param outputTokens   reported output tokens (0 when unknown)
 */
public record RetranslationSuggestion(
        int sequence,
        String previousText,
        String suggestedText,
        String provider,
        String model,
        String quality,
        String warning,
        boolean cloud,
        int inputTokens,
        int outputTokens) {

    public boolean safe() {
        return warning == null || warning.isBlank();
    }
}

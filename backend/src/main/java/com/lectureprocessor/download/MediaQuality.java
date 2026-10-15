package com.lectureprocessor.download;

/**
 * Requested download quality. {@code maxHeight == 0} means "best available".
 */
public enum MediaQuality {
    AUTO(0, "auto"),
    P480(480, "480p"),
    P720(720, "720p"),
    P1080(1080, "1080p");

    private final int maxHeight;
    private final String label;

    MediaQuality(int maxHeight, String label) {
        this.maxHeight = maxHeight;
        this.label = label;
    }

    public int maxHeight() {
        return maxHeight;
    }

    public String label() {
        return label;
    }

    public static MediaQuality fromLabel(String value) {
        if (value == null || value.isBlank()) {
            return P720;
        }
        String normalized = value.trim().toLowerCase();
        for (MediaQuality quality : values()) {
            if (quality.label.equals(normalized) || quality.name().equalsIgnoreCase(normalized)) {
                return quality;
            }
        }
        return P720;
    }
}

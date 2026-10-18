package com.lectureprocessor.thumbnail;

/**
 * State of a cached lecture thumbnail.
 *
 * <ul>
 *   <li>{@link #READY} — image exists on disk and is servable;</li>
 *   <li>{@link #MISSING} — not generated yet (or the file disappeared);</li>
 *   <li>{@link #FAILED} — generation failed, reason is stored;</li>
 *   <li>{@link #NO_SOURCE} — the lecture has no usable visual source (audio only).</li>
 * </ul>
 */
public enum ThumbnailStatus {
    READY,
    MISSING,
    FAILED,
    NO_SOURCE
}

package com.lectureprocessor.discovery;

import java.util.List;

import com.lectureprocessor.process.CancellationToken;

/**
 * Search abstraction for one video platform. Implementations must return only
 * real provider results (never generated titles/URLs) and must not bypass
 * CAPTCHA, DRM, authorization or platform restrictions.
 */
public interface VideoSearchProvider {

    String id();

    String displayName();

    /** True when the provider has everything needed to search right now. */
    boolean available();

    /** Human-readable explanation when {@link #available()} is false. */
    String availabilityMessage();

    /** Search mechanism actually used, e.g. {@code YOUTUBE_DATA_API}. */
    String method();

    SearchOutcome search(VideoSearchQuery query, CancellationToken cancellation);

    /**
     * Provider response plus the mechanism and error. When the provider fails,
     * the error is returned explicitly instead of an empty fabricated result.
     */
    record SearchOutcome(List<DiscoveredVideo> videos, String method, String error) {

        public static SearchOutcome ok(List<DiscoveredVideo> videos, String method) {
            return new SearchOutcome(videos, method, null);
        }

        public static SearchOutcome error(String method, String error) {
            return new SearchOutcome(List.of(), method, error);
        }

        public boolean success() {
            return error == null;
        }
    }
}

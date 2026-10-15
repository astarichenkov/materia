package com.lectureprocessor.download;

import java.util.function.Consumer;

import com.lectureprocessor.media.MediaMetadata;
import com.lectureprocessor.process.CancellationToken;

/**
 * Probes remote media metadata without downloading the media itself.
 */
public interface VideoMetadataService {

    MediaMetadata probe(String url, CancellationToken cancellation);
}

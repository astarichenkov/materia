package com.lectureprocessor.download;

import java.nio.file.Path;
import java.util.function.Consumer;

import com.lectureprocessor.process.CancellationToken;

/**
 * Downloads remote video/audio into the managed lecture workspace.
 */
public interface VideoDownloader {

    DownloadResult download(DownloadRequest request, CancellationToken cancellation,
            Consumer<DownloadProgress> progress);
}

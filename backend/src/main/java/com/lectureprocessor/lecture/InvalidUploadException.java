package com.lectureprocessor.lecture;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Raised when an uploaded file fails validation.
 */
@ResponseStatus(HttpStatus.BAD_REQUEST)
public class InvalidUploadException extends RuntimeException {

    public InvalidUploadException(String message) {
        super(message);
    }
}

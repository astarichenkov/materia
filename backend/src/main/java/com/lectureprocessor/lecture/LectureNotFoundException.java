package com.lectureprocessor.lecture;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Raised when a requested lecture does not exist.
 */
@ResponseStatus(HttpStatus.NOT_FOUND)
public class LectureNotFoundException extends RuntimeException {

    public LectureNotFoundException(String id) {
        super("Лекция не найдена: " + id);
    }
}

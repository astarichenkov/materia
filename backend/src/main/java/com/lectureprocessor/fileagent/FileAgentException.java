package com.lectureprocessor.fileagent;

/** A validation, security or execution failure of the file agent. */
public class FileAgentException extends RuntimeException {

    public FileAgentException(String message) {
        super(message);
    }

    public FileAgentException(String message, Throwable cause) {
        super(message, cause);
    }
}

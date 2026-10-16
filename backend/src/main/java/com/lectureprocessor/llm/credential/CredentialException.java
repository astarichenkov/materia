package com.lectureprocessor.llm.credential;

/**
 * Raised when credential material cannot be encrypted, decrypted or stored
 * safely. Messages never contain the credential value or key material.
 */
public class CredentialException extends RuntimeException {

    public CredentialException(String message) {
        super(message);
    }

    public CredentialException(String message, Throwable cause) {
        super(message, cause);
    }
}

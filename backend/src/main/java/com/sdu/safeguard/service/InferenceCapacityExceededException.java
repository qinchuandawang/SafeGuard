package com.sdu.safeguard.service;

public class InferenceCapacityExceededException extends RuntimeException {

    public InferenceCapacityExceededException(String message) {
        super(message);
    }

    public InferenceCapacityExceededException(String message, Throwable cause) {
        super(message, cause);
    }
}

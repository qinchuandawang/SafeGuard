package com.sdu.safeguard.service;

public class TokenBudgetExceededException extends RuntimeException {
    public TokenBudgetExceededException(String message) {
        super(message);
    }
}

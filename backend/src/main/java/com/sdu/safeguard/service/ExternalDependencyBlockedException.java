package com.sdu.safeguard.service;

public class ExternalDependencyBlockedException extends RuntimeException {

    public ExternalDependencyBlockedException(String resource, Throwable cause) {
        super("外部依赖已被 Sentinel 流控或熔断: " + resource, cause);
    }
}

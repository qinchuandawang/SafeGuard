package com.sdu.safeguard.service;

import lombok.Getter;

import java.util.function.Predicate;
import java.util.function.Supplier;

@Getter
public class TaskEventTransactionContext<T> {

    private final String messageId;
    private final String taskId;
    private final String eventType;
    private final Supplier<T> localOperation;
    private final Predicate<T> shouldCommit;
    private T result;
    private RuntimeException failure;
    private boolean executed;
    private boolean accepted;
    private boolean committed;

    public TaskEventTransactionContext(String messageId, String taskId, String eventType,
                                       Supplier<T> localOperation, Predicate<T> shouldCommit) {
        this.messageId = messageId;
        this.taskId = taskId;
        this.eventType = eventType;
        this.localOperation = localOperation;
        this.shouldCommit = shouldCommit;
    }

    public void executeLocalOperation() {
        executed = true;
        try {
            result = localOperation.get();
            accepted = shouldCommit.test(result);
        } catch (RuntimeException exception) {
            failure = exception;
            throw exception;
        }
    }

    public void markCommitted() {
        committed = true;
    }

    public void markFailure(RuntimeException exception) {
        failure = exception;
    }

    public void throwIfFailed() {
        if (failure != null) throw failure;
    }

    public T resultOrThrow() {
        throwIfFailed();
        return result;
    }
}

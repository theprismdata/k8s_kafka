/*
 * Copyright Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.operator.common.operator.resource;

/**
 * Thrown to indicate that timeout has been exceeded.
 */
public class TimeoutException extends RuntimeException {
    public TimeoutException() {
        super();
    }

    public TimeoutException(String message) {
        super(message);
    }
}

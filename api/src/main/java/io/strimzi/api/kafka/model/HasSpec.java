/*
 * Copyright Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.api.kafka.model;

public interface HasSpec<S extends Spec> {
    void setSpec(S spec);
    S getSpec();
}

/*
 * Copyright Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.operator.common.operator.resource;

import io.fabric8.kubernetes.api.model.DeletionPropagation;
import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.KubernetesResourceList;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.Watcher;
import io.fabric8.kubernetes.client.dsl.FilterWatchListDeletable;
import io.fabric8.kubernetes.client.dsl.FilterWatchListMultiDeletable;
import io.fabric8.kubernetes.client.dsl.NonNamespaceOperation;
import io.fabric8.kubernetes.client.dsl.Resource;
import io.strimzi.operator.common.Reconciliation;
import io.strimzi.operator.common.ReconciliationLogger;
import io.strimzi.operator.common.model.Labels;
import io.vertx.core.CompositeFuture;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Abstract resource creation, for a generic resource type {@code R}.
 * This class applies the template method pattern, first checking whether the resource exists,
 * and creating it if it does not. It is not an error if the resource did already exist.
 * @param <C> The type of client used to interact with kubernetes.
 * @param <T> The Kubernetes resource type.
 * @param <L> The list variant of the Kubernetes resource type.
 * @param <R> The resource operations.
 */
public abstract class AbstractNonNamespacedResourceOperator<C extends KubernetesClient, T extends HasMetadata,
        L extends KubernetesResourceList<T>, R extends Resource<T>> {

    protected static final Pattern IGNORABLE_PATHS = Pattern.compile(
            "^(/metadata/managedFields" +
                    "|/status)$");

    protected final ReconciliationLogger log = ReconciliationLogger.create(getClass());
    protected final Vertx vertx;
    protected final C client;
    protected final String resourceKind;
    protected final ResourceSupport resourceSupport;

    /**
     * Constructor.
     * @param vertx The vertx instance.
     * @param client The kubernetes client.
     * @param resourceKind The mind of Kubernetes resource (used for logging).
     */
    public AbstractNonNamespacedResourceOperator(Vertx vertx, C client, String resourceKind) {
        this.vertx = vertx;
        this.client = client;
        this.resourceKind = resourceKind;
        this.resourceSupport = new ResourceSupport(vertx);
    }

    protected abstract NonNamespaceOperation<T, L, R> operation();

    /**
     * Asynchronously create or update the given {@code resource} depending on whether it already exists,
     * returning a future for the outcome.
     * If the resource with that name already exists the future completes successfully.
     * @param reconciliation The reconciliation
     * @param resource The resource to create.
     * @return A future which completes when the resource was created or updated.
     */
    public Future<ReconcileResult<T>> createOrUpdate(Reconciliation reconciliation, T resource) {
        if (resource == null) {
            throw new NullPointerException();
        }
        return reconcile(reconciliation, resource.getMetadata().getName(), resource);
    }

    /**
     * Asynchronously reconciles the resource with the given name to match the given
     * desired resource, returning a future for the result.
     * @param reconciliation The reconciliation
     * @param name The name of the resource to reconcile.
     * @param desired The desired state of the resource.
     * @return A future which completes when the resource was reconciled.
     */
    public Future<ReconcileResult<T>> reconcile(Reconciliation reconciliation, String name, T desired) {
        if (desired != null && !name.equals(desired.getMetadata().getName())) {
            return Future.failedFuture("Given name " + name + " incompatible with desired name "
                    + desired.getMetadata().getName());
        }

        Promise<ReconcileResult<T>> promise = Promise.promise();
        vertx.createSharedWorkerExecutor("kubernetes-ops-pool").executeBlocking(
            future -> {
                T current = operation().withName(name).get();
                if (desired != null) {
                    if (current == null) {
                        log.debugCr(reconciliation, "{} {} does not exist, creating it", resourceKind, name);
                        internalCreate(reconciliation, name, desired).onComplete(future);
                    } else {
                        log.debugCr(reconciliation, "{} {} already exists, patching it", resourceKind, name);
                        internalPatch(reconciliation, name, current, desired).onComplete(future);
                    }
                } else {
                    if (current != null) {
                        // Deletion is desired
                        log.debugCr(reconciliation, "{} {} exist, deleting it", resourceKind, name);
                        internalDelete(reconciliation, name).onComplete(future);
                    } else {
                        log.debugCr(reconciliation, "{} {} does not exist, noop", resourceKind, name);
                        future.complete(ReconcileResult.noop(null));
                    }
                }

            },
            false,
            promise
        );
        return promise.future();
    }

    protected long deleteTimeoutMs() {
        return ResourceSupport.DEFAULT_TIMEOUT_MS;
    }

    /**
     * Asynchronously deletes the resource with the given {@code name},
     * returning a Future which completes once the resource
     * is observed to have been deleted.
     * @param reconciliation The reconciliation
     * @param name The resource to be deleted.
     * @return A future which will be completed on the context thread
     * once the resource has been deleted.
     */
    private Future<ReconcileResult<T>> internalDelete(Reconciliation reconciliation, String name) {
        R resourceOp = operation().withName(name);

        Future<ReconcileResult<T>> watchForDeleteFuture = resourceSupport.selfClosingWatch(
            reconciliation,
            resourceOp,
            resourceOp,
            deleteTimeoutMs(),
            "observe deletion of " + resourceKind + " " + name,
            (action, resource) -> {
                if (action == Watcher.Action.DELETED) {
                    log.debugCr(reconciliation, "{} {} has been deleted", resourceKind, name);
                    return ReconcileResult.deleted();
                } else {
                    return null;
                }
            },
            resource -> {
                if (resource == null) {
                    log.debugCr(reconciliation, "{} {} has been already deleted in pre-check", resourceKind, name);
                    return ReconcileResult.deleted();
                } else {
                    return null;
                }
            });

        Future<Void> deleteFuture = resourceSupport.deleteAsync(resourceOp);

        return CompositeFuture.join(watchForDeleteFuture, deleteFuture).map(ReconcileResult.deleted());
    }

    /**
     * @return  Returns the Pattern for matching paths which can be ignored in the resource diff
     */
    protected Pattern ignorablePaths() {
        return IGNORABLE_PATHS;
    }

    /**
     * Returns the diff of the current and desired resources
     *
     * @param reconciliation The reconciliation
     * @param resourceName  Name of the resource used for logging
     * @param current       Current resource
     * @param desired       Desired resource
     *
     * @return  The ResourceDiff instance
     */
    protected ResourceDiff<T> diff(Reconciliation reconciliation, String resourceName, T current, T desired)  {
        return new ResourceDiff<>(reconciliation, resourceKind, resourceName, current, desired, ignorablePaths());
    }

    /**
     * Checks whether the current and desired resources differ and need to be patched in the Kubernetes API server.
     *
     * @param reconciliation The reconciliation
     * @param name      Name of the resource used for logging
     * @param current   Current resource
     * @param desired   desired resource
     *
     * @return          True if the resources differ and need patching
     */
    protected boolean needsPatching(Reconciliation reconciliation, String name, T current, T desired)   {
        return !diff(reconciliation, name, current, desired).isEmpty();
    }

    /**
     * Patches the resource with the given name to match the given desired resource
     * and completes the given future accordingly.
     */
    protected Future<ReconcileResult<T>> internalPatch(Reconciliation reconciliation, String name, T current, T desired) {
        return internalPatch(reconciliation, name, current, desired, true);
    }

    protected Future<ReconcileResult<T>> internalPatch(Reconciliation reconciliation, String name, T current, T desired, boolean cascading) {
        if (needsPatching(reconciliation, name, current, desired))  {
            try {
                T result = operation().withName(name).withPropagationPolicy(cascading ? DeletionPropagation.FOREGROUND : DeletionPropagation.ORPHAN).patch(desired);
                log.debugCr(reconciliation, "{} {} has been patched", resourceKind, name);
                return Future.succeededFuture(wasChanged(current, result) ?
                        ReconcileResult.patched(result) : ReconcileResult.noop(result));
            } catch (Exception e) {
                log.debugCr(reconciliation, "Caught exception while patching {} {}", resourceKind, name, e);
                return Future.failedFuture(e);
            }
        } else {
            log.debugCr(reconciliation, "{} {} did not changed and doesn't need patching", resourceKind, name);
            return Future.succeededFuture(ReconcileResult.noop(current));
        }
    }

    private boolean wasChanged(T oldVersion, T newVersion) {
        if (oldVersion != null
                && oldVersion.getMetadata() != null
                && newVersion != null
                && newVersion.getMetadata() != null) {
            return !Objects.equals(oldVersion.getMetadata().getResourceVersion(), newVersion.getMetadata().getResourceVersion());
        } else {
            return true;
        }
    }

    /**
     * Creates a resource with the name with the given desired state
     * and completes the given future accordingly.
     */
    protected Future<ReconcileResult<T>> internalCreate(Reconciliation reconciliation, String name, T desired) {
        try {
            ReconcileResult<T> result = ReconcileResult.created(operation().withName(name).create(desired));
            log.debugCr(reconciliation, "{} {} has been created", resourceKind, name);
            return Future.succeededFuture(result);
        } catch (Exception e) {
            log.debugCr(reconciliation, "Caught exception while creating {} {}", resourceKind, name, e);
            return Future.failedFuture(e);
        }
    }

    /**
     * Synchronously gets the resource with the given {@code name}.
     * @param name The name.
     * @return The resource, or null if it doesn't exist.
     */
    public T get(String name) {
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException(resourceKind + " with an empty name cannot be configured. Please provide a name.");
        }
        return operation().withName(name).get();
    }

    /**
     * Asynchronously gets the resource with the given {@code name}.
     * @param name The name.
     * @return A Future for the result.
     */
    public Future<T> getAsync(String name) {
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException(resourceKind + " with an empty name cannot be configured. Please provide a name.");
        }
        return resourceSupport.getAsync(operation().withName(name));
    }

    /**
     * Synchronously list the resources with the given {@code selector}.
     * @param selector The selector.
     * @return A list of matching resources.
     */
    public List<T> list(Labels selector) {
        return listOperation(selector).list().getItems();
    }

    /**
     * Asynchronously list the resources with the given {@code selector}.
     * @param selector The selector.
     * @return A list of matching resources.
     */
    public Future<List<T>> listAsync(Labels selector) {
        return resourceSupport.listAsync(listOperation(selector));
    }

    protected FilterWatchListDeletable<T, L> listOperation(Labels selector) {
        FilterWatchListMultiDeletable<T, L> operation = operation();

        if (selector != null) {
            Map<String, String> labels = selector.toMap();
            return operation.withLabels(labels);
        } else {
            return operation;
        }
    }
}

/*
 * Copyright (c) 2014-2015 VMware, Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License.  You may obtain a copy of
 * the License at http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed
 * under the License is distributed on an "AS IS" BASIS, without warranties or
 * conditions of any kind, EITHER EXPRESS OR IMPLIED.  See the License for the
 * specific language governing permissions and limitations under the License.
 */

package com.vmware.xenon.common;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.Stream;

import com.vmware.xenon.common.Operation.CompletionHandler;

/**
 * The {@link OperationJoin} construct is a handler for {@link OperationJoin#create(Operation...)}
 * functionality. After multiple parallel requests have
 * completed, only then will invoked all {@link CompletionHandler}s providing all operations and
 * failures as part of the execution context.
 */
public class OperationJoin {
    private static final int APPROXIMATE_EXPECTED_CAPACITY = 4;
    public static final String ERROR_MSG_BATCH_LIMIT_VIOLATED = "batch limit violated";
    public static final String ERROR_MSG_INVALID_BATCH_SIZE = "batch size must be greater than 0";
    public static final String ERROR_MSG_OPERATIONS_ALREADY_SET = "operations have already been set";
    private final ConcurrentHashMap<Long, Operation> operations;
    private ConcurrentHashMap<Long, Throwable> failures;
    volatile JoinedCompletionHandler joinedCompletion;
    private OperationContext opContext;
    private AtomicInteger pendingCount = new AtomicInteger();
    private AtomicInteger batchSizeGuard = new AtomicInteger();
    private int batchSize = 0;
    private Iterator<Operation> operationIterator;
    private Consumer<Operation> sendOperation;

    private OperationJoin() {
        this.operations = new ConcurrentHashMap<>(APPROXIMATE_EXPECTED_CAPACITY);
        this.opContext = OperationContext.getOperationContext();
    }

    /**
     * Create {@link OperationJoin} with no operations in preparation for {@link Operation}s to
     * be added at a later time.
     */
    public static OperationJoin create() {
        return new OperationJoin();
    }

    /**
     * Create {@link OperationJoin} with an array of {@link Operation}s to be joined together in
     * parallel execution.
     */
    public static OperationJoin create(Operation... ops) {
        OperationJoin joinOp = new OperationJoin();
        joinOp.setOperations(ops);
        return joinOp;
    }

    /**
     * Create {@link OperationJoin} with a collection of {@link Operation}s to be joined together in
     * parallel execution.
     */
    public static OperationJoin create(Collection<Operation> ops) {
        OperationJoin joinOp = new OperationJoin();
        joinOp.setOperations(ops);
        return joinOp;
    }

    /**
     * Create {@link OperationJoin} with a stream of {@link Operation}s to be joined together in
     * parallel execution.
     */
    public static OperationJoin create(Stream<Operation> ops) {
        OperationJoin joinOp = new OperationJoin();
        joinOp.setOperations(ops);
        return joinOp;
    }

    /**
     * Set the {@link Operation}s for the current {@link OperationJoin}. This is a one-time
     * operation.
     */
    public OperationJoin setOperations(Operation... ops) {
        if (ops.length == 0) {
            throw new IllegalArgumentException("At least one operation to join expected");
        }

        if (this.operationIterator != null) {
            throw new IllegalStateException(ERROR_MSG_OPERATIONS_ALREADY_SET);
        }

        CompletionHandler nestedParentHandler = createParentCompletion();
        for (Operation op : ops) {
            prepareOperation(nestedParentHandler, op);
        }

        this.operationIterator = this.operations.values().iterator();
        return this;
    }

    /**
     * Set the {@link Operation}s for the current {@link OperationJoin}. This is a one-time
     * operation.
     */
    public OperationJoin setOperations(Collection<Operation> ops) {
        if (ops.isEmpty()) {
            throw new IllegalArgumentException("At least one operation to join expected");
        }

        if (this.operationIterator != null) {
            throw new IllegalStateException(ERROR_MSG_OPERATIONS_ALREADY_SET);
        }

        CompletionHandler nestedParentHandler = createParentCompletion();
        for (Operation op : ops) {
            prepareOperation(nestedParentHandler, op);
        }

        this.operationIterator = this.operations.values().iterator();
        return this;
    }

    /**
     * Set the {@link Operation}s for the current {@link OperationJoin}. This is a one-time
     * operation.
     */
    public OperationJoin setOperations(Stream<Operation> ops) {
        if (this.operationIterator != null) {
            throw new IllegalStateException(ERROR_MSG_OPERATIONS_ALREADY_SET);
        }

        CompletionHandler nestedParentHandler = createParentCompletion();
        ops.forEach((op) -> prepareOperation(nestedParentHandler, op));
        this.operationIterator = this.operations.values().iterator();

        if (isEmpty()) {
            throw new IllegalArgumentException("At least one operation to join expected");
        }

        return this;
    }

    private void prepareOperation(CompletionHandler nestedParentHandler, Operation op) {
        this.operations.put(op.getId(), op);
        op.nestCompletion(nestedParentHandler);
        this.pendingCount.incrementAndGet();
    }

    private CompletionHandler createParentCompletion() {
        CompletionHandler nestedParentHandler = (o, e) -> {
            if (e != null) {
                synchronized (this.pendingCount) {
                    if (this.failures == null) {
                        this.failures = new ConcurrentHashMap<>();
                    }
                }
                this.failures.put(o.getId(), e);
            }

            Operation originalOp = this.operations.get(o.getId());
            originalOp.setStatusCode(o.getStatusCode())
                    .transferResponseHeadersFrom(o)
                    .setBodyNoCloning(o.getBodyRaw());

            this.batchSizeGuard.decrementAndGet();
            sendNext();

            if (this.pendingCount.decrementAndGet() != 0) {
                return;
            }

            OperationContext.restoreOperationContext(this.opContext);
            // call each operation completion individually
            for (Operation op : this.operations.values()) {
                Throwable t = null;
                if (this.failures != null) {
                    t = this.failures.get(op.getId());
                }
                if (t != null) {
                    op.fail(t);
                } else {
                    op.complete();
                }
            }

            if (this.joinedCompletion != null) {
                this.joinedCompletion.handle(this.operations, this.failures);
            }
        };
        return nestedParentHandler;
    }

    private void sendWithBatch() {
        if (this.operationIterator == null || !this.operationIterator.hasNext()) {
            throw new IllegalStateException("No operations to be sent");
        }

        // Move the operations to local list to avoid concurrency issues with iterator
        // when sendNext could be called from handler of returning operation
        // before we get out of this method.
        ArrayList<Operation> localOperationList = new ArrayList<>();
        int count = 0;
        while (this.operationIterator.hasNext()) {
            localOperationList.add(this.operationIterator.next());
            count++;
            if (this.batchSize > 0 && count == this.batchSize) {
                break;
            }
        }

        for (Operation op : localOperationList) {
            this.sendOperation.accept(op);
            if (this.batchSize > 0 && this.batchSizeGuard.incrementAndGet() > this.batchSize) {
                throw new IllegalStateException((ERROR_MSG_BATCH_LIMIT_VIOLATED));
            }
        }
    }

    private void sendNext() {
        if (this.sendOperation == null) {
            return;
        }

        Operation op = null;
        synchronized (this.operationIterator) {
            if (this.operationIterator.hasNext()) {
                op = this.operationIterator.next();
            }
        }

        if (op != null) {
            if (this.batchSize > 0 && this.batchSizeGuard.incrementAndGet() > this.batchSize) {
                throw new IllegalStateException((ERROR_MSG_BATCH_LIMIT_VIOLATED));
            }

            this.sendOperation.accept(op);
        }
    }

    /**
     * Send the join operations using the {@link ServiceHost}.
     * Caller can also provide batch size to control the rate at which operations are sent.
     */
    public void sendWith(ServiceHost host, int batchSize) {
        if (batchSize <= 0) {
            throw new IllegalArgumentException(ERROR_MSG_INVALID_BATCH_SIZE);
        }

        this.batchSize = batchSize;
        this.sendWith(host);
    }

    /**
     * Send the join operations using the {@link ServiceHost}.
     */
    public void sendWith(ServiceHost host) {
        if (host == null) {
            throw new IllegalArgumentException("host must not be null.");
        }

        this.sendOperation = host::sendRequest;
        sendWithBatch();
    }

    /**
     * Send the join operations using the {@link Service}.
     * Caller can also provide batch size to control the rate at which operations are sent.
     */
    public void sendWith(Service service, int batchSize) {
        if (batchSize <= 0) {
            throw new IllegalArgumentException(ERROR_MSG_INVALID_BATCH_SIZE);
        }

        this.batchSize = batchSize;
        this.sendWith(service);
    }

    /**
     * Send the join operations using the {@link Service}.
     */
    public void sendWith(Service service) {
        if (service == null) {
            throw new IllegalArgumentException("service must not be null.");
        }

        this.sendOperation = service::sendRequest;
        sendWithBatch();
    }

    /**
     * Send the join operations using the {@link ServiceClient}.
     * Caller can also provide batch size to control the rate at which operations are sent.
     */
    public void sendWith(ServiceClient client, int batchSize) {
        if (batchSize <= 0) {
            throw new IllegalArgumentException(ERROR_MSG_INVALID_BATCH_SIZE);
        }

        this.batchSize = batchSize;
        this.sendWith(client);
    }

    /**
     * Send the join operations using the {@link ServiceClient}.
     */
    public void sendWith(ServiceClient client) {
        if (client == null) {
            throw new IllegalArgumentException("client must not be null.");
        }
        this.sendOperation = client::send;
        sendWithBatch();
    }

    public OperationJoin setCompletion(JoinedCompletionHandler joinedCompletion) {
        this.joinedCompletion = joinedCompletion;
        return this;
    }

    OperationContext getOperationContext() {
        return this.opContext;
    }

    /**
     * Sets (overwrites) the operation context of this operataion join instance
     *
     * The visibility of this method is intentionally package-local. It is intended to
     * only be called by functions in this package, so that we can apply whitelisting
     * to limit the set of services that is able to set it.
     *
     * @param opContext the operation context to set.
     */
    void setOperationContext(OperationContext opContext) {
        this.opContext = opContext;
    }

    public boolean isEmpty() {
        return this.operations.isEmpty();
    }

    public Collection<Operation> getOperations() {
        return this.operations.values();
    }

    public Map<Long, Throwable> getFailures() {
        return this.failures;
    }

    public Operation getOperation(long id) {
        return this.operations.get(id);
    }

    @FunctionalInterface
    public static interface JoinedCompletionHandler {
        void handle(Map<Long, Operation> ops, Map<Long, Throwable> failures);
    }


    public void fail(Throwable t) {
        this.failures = new ConcurrentHashMap<>();
        this.failures.put(this.operations.keys().nextElement(), t);
        OperationContext origContext = OperationContext.getOperationContext();
        OperationContext.restoreOperationContext(this.opContext);
        for (Operation op : this.operations.values()) {
            op.fail(t);
        }
        OperationContext.restoreOperationContext(origContext);
    }
}

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

package com.vmware.xenon.services.common;

import static org.junit.Assert.assertEquals;

import java.net.URI;
import java.util.UUID;

import org.junit.Test;

import com.vmware.xenon.common.BasicReusableHostTestCase;
import com.vmware.xenon.common.Operation;
import com.vmware.xenon.common.UriUtils;
import com.vmware.xenon.services.common.QueryTask.Query;
import com.vmware.xenon.services.common.UserGroupService.UserGroupState;

public class TestUserGroupService extends BasicReusableHostTestCase {

    @Test
    public void testFactoryPost() throws Throwable {
        UserGroupState state = new UserGroupState();
        state.query = new Query();
        state.query.setTermPropertyName("name");
        state.query.setTermMatchValue("value");

        final UserGroupState[] outState = new UserGroupState[1];

        URI uri = UriUtils.buildUri(this.host, ServiceUriPaths.CORE_AUTHZ_USER_GROUPS);
        Operation op = Operation.createPost(uri)
                .setBody(state)
                .setCompletion((o, e) -> {
                    if (e != null) {
                        this.host.failIteration(e);
                        return;
                    }

                    outState[0] = o.getBody(UserGroupState.class);
                    this.host.completeIteration();
                });

        this.host.testStart(1);
        this.host.send(op);
        this.host.testWait();

        assertEquals(state.query.term.propertyName, outState[0].query.term.propertyName);
        assertEquals(state.query.term.matchValue, outState[0].query.term.matchValue);
    }

    @Test
    public void testFactoryIdempotentPost() throws Throwable {
        UserGroupState state = new UserGroupState();
        state.documentSelfLink = UUID.randomUUID().toString();
        state.query = new Query();
        state.query.setTermPropertyName("name");
        state.query.setTermMatchValue("value");

        UserGroupState responseState = (UserGroupState) this.host.verifyPost(UserGroupState.class,
                ServiceUriPaths.CORE_AUTHZ_USER_GROUPS,
                state,
                Operation.STATUS_CODE_OK);

        assertEquals(state.query.term.propertyName, responseState.query.term.propertyName);
        assertEquals(state.query.term.matchValue, responseState.query.term.matchValue);

        responseState = (UserGroupState) this.host.verifyPost(UserGroupState.class,
                ServiceUriPaths.CORE_AUTHZ_USER_GROUPS,
                state,
                Operation.STATUS_CODE_NOT_MODIFIED);

        assertEquals(state.query.term.propertyName, responseState.query.term.propertyName);
        assertEquals(state.query.term.matchValue, responseState.query.term.matchValue);

        state.query.setTermMatchValue("valueModified");

        responseState = (UserGroupState) this.host.verifyPost(UserGroupState.class,
                ServiceUriPaths.CORE_AUTHZ_USER_GROUPS,
                state,
                Operation.STATUS_CODE_OK);

        assertEquals(state.query.term.propertyName, responseState.query.term.propertyName);
        assertEquals(state.query.term.matchValue, responseState.query.term.matchValue);
    }

    @Test
    public void testFactoryPostFailure() throws Throwable {
        UserGroupState state = new UserGroupState();
        state.query = null;

        Operation[] outOp = new Operation[1];
        Throwable[] outEx = new Throwable[1];

        URI uri = UriUtils.buildUri(this.host, ServiceUriPaths.CORE_AUTHZ_USER_GROUPS);
        Operation op = Operation.createPost(uri)
                .setBody(state)
                .setCompletion((o, e) -> {
                    if (e != null) {
                        outOp[0] = o;
                        outEx[0] = e;
                        this.host.completeIteration();
                        return;
                    }

                    // No exception, fail test
                    this.host.failIteration(new IllegalStateException("expected failure"));
                });

        this.host.testStart(1);
        this.host.send(op);
        this.host.testWait();

        assertEquals(Operation.STATUS_CODE_FAILURE_THRESHOLD, outOp[0].getStatusCode());
        assertEquals("query is required", outEx[0].getMessage());
    }
}

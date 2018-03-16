/*
 * Copyright (c) 2016 Couchbase, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.couchbase.client.core.message.config;

import com.couchbase.client.core.message.AbstractCouchbaseRequest;
import com.couchbase.client.core.utils.NetworkAddress;

import java.net.InetAddress;

public class BucketConfigRequest extends AbstractCouchbaseRequest implements ConfigRequest {

    private static final String PATH = "/pools/default/b/";

    private final NetworkAddress hostname;
    private final String path;

    public BucketConfigRequest(String path, NetworkAddress hostname, String bucket, String password) {
        this(path, hostname, bucket, bucket, password);
    }

    public BucketConfigRequest(String path, NetworkAddress hostname, String bucket, String username, String password) {
        super(bucket, username, password);
        this.hostname = hostname;
        this.path = path;
    }

    public NetworkAddress hostname() {
        return hostname;
    }

    public String path() {
        return path + bucket();
    }
}

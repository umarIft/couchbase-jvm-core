/**
 * Copyright (C) 2014 Couchbase, Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING
 * FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALING
 * IN THE SOFTWARE.
 */
package com.couchbase.client.core.message;

/**
 * The default representation of a {@link CouchbaseResponse}.
 *
 * @author Michael Nitschinger
 * @since 1.0
 */
public abstract class AbstractCouchbaseResponse implements CouchbaseResponse {

    /**
     * The status for this response.
     */
    private final ResponseStatus status;

    private final CouchbaseRequest request;

    /**
     * Sets the required properties for the response.
     *
     * @param status the status of the response.
     */
    protected AbstractCouchbaseResponse(final ResponseStatus status, final CouchbaseRequest request) {
        this.status = status;
        this.request = request;
    }

    @Override
    public ResponseStatus status() {
        return status;
    }

    /**
     * Stub method implementation which needs to be overriden by all responses that support cloning.
     */
    @Override
    public CouchbaseRequest request() {
        return request;
    }

    @Override
    public int refCnt() {
        return 1;
    }

    @Override
    public AbstractCouchbaseResponse retain() {
        return this;
    }

    @Override
    public AbstractCouchbaseResponse retain(int increment) {
        return this;
    }

    @Override
    public boolean release() {
        return false;
    }

    @Override
    public boolean release(int decrement) {
        return false;
    }

    @Override
    public String toString() {
        return "CouchbaseResponse{" + "status=" + status + ", request=" + request + '}';
    }
}

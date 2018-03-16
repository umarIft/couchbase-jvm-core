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
package com.couchbase.client.core.time;

import java.util.concurrent.TimeUnit;

/**
 * Delay which is fixed for every attempt.
 *
 * @author Michael Nitschinger
 * @since 1.1.0
 */
public class FixedDelay extends Delay {

    private final long delay;

    FixedDelay(long delay, TimeUnit unit) {
        super(unit);
        this.delay = delay;
    }

    @Override
    public long calculate(long attempt) {
        return delay;
    }

    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder("FixedDelay{");
        sb.append("unit=").append(unit());
        sb.append(", delay=").append(delay);
        sb.append('}');
        return sb.toString();
    }
}

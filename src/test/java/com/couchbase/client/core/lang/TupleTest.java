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
package com.couchbase.client.core.lang;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class TupleTest {

    @Test
    public void shouldCreateWithTwoValues() throws Exception {
        Tuple2<String, Integer> tuple = Tuple.create("value1", 2);
        assertEquals("value1", tuple.value1());
        assertEquals(2, (long) tuple.value2());

        Tuple2<Integer, String> swapped = tuple.swap();
        assertEquals("value1", swapped.value2());
        assertEquals(2, (long) swapped.value1());
    }

    @Test
    public void shouldCreateWithThreeValues() throws Exception {
        Tuple3<String, Integer, Boolean> tuple = Tuple.create("value1", 2, true);
        assertEquals("value1", tuple.value1());
        assertEquals(2, (long) tuple.value2());
        assertEquals(true, tuple.value3());
    }

    @Test
    public void shouldCreateWithFourValues() throws Exception {
        Tuple4<String, Integer, Boolean, String> tuple = Tuple.create("value1", 2, true, "value4");
        assertEquals("value1", tuple.value1());
        assertEquals(2, (long) tuple.value2());
        assertEquals(true, tuple.value3());
        assertEquals("value4", tuple.value4());
    }

    @Test
    public void shouldCreateWithFiveValues() throws Exception {
        Tuple5<String, Integer, Boolean, String, Integer> tuple = Tuple.create("value1", 2, true, "value4", 5);
        assertEquals("value1", tuple.value1());
        assertEquals(2, (long) tuple.value2());
        assertEquals(true, tuple.value3());
        assertEquals("value4", tuple.value4());
        assertEquals(5, (long) tuple.value5());
    }

}
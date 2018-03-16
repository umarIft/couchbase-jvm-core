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
package com.couchbase.client.core.endpoint.query;

import com.couchbase.client.core.ResponseEvent;
import com.couchbase.client.core.endpoint.AbstractEndpoint;
import com.couchbase.client.core.env.CoreEnvironment;
import com.couchbase.client.core.message.CouchbaseMessage;
import com.couchbase.client.core.message.ResponseStatus;
import com.couchbase.client.core.message.query.GenericQueryRequest;
import com.couchbase.client.core.message.query.GenericQueryResponse;
import com.couchbase.client.core.message.query.QueryRequest;
import com.couchbase.client.core.util.Resources;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lmax.disruptor.EventFactory;
import com.lmax.disruptor.EventHandler;
import com.lmax.disruptor.dsl.Disruptor;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.DefaultLastHttpContent;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.util.CharsetUtil;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import rx.functions.Action1;
import rx.schedulers.Schedulers;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Verifies the correct functionality of the {@link QueryHandler}.
 *
 * @author Michael Nitschinger
 * @since 1.0
 */
public class QueryHandlerTest {

    private static final String FAKE_REQUESTID = "1234test-7802-4fc2-acd6-dfcd1c05a288";
    private static final String FAKE_CLIENTID = "1234567890123456789012345678901234567890123456789012345678901234";

    private ObjectMapper mapper = new ObjectMapper();
    private Queue<QueryRequest> queue;
    private EmbeddedChannel channel;
    private Disruptor<ResponseEvent> responseBuffer;
    private List<CouchbaseMessage> firedEvents;
    private CountDownLatch latch;
    private QueryHandler handler;

    @Before
    @SuppressWarnings("unchecked")
    public void setup() {
        responseBuffer = new Disruptor<ResponseEvent>(new EventFactory<ResponseEvent>() {
            @Override
            public ResponseEvent newInstance() {
                return new ResponseEvent();
            }
        }, 1024, Executors.newCachedThreadPool());

        firedEvents = Collections.synchronizedList(new ArrayList<CouchbaseMessage>());
        latch = new CountDownLatch(1);
        responseBuffer.handleEventsWith(new EventHandler<ResponseEvent>() {
            @Override
            public void onEvent(ResponseEvent event, long sequence, boolean endOfBatch) throws Exception {
                firedEvents.add(event.getMessage());
                latch.countDown();
            }
        });

        CoreEnvironment environment = mock(CoreEnvironment.class);
        when(environment.scheduler()).thenReturn(Schedulers.computation());
        AbstractEndpoint endpoint = mock(AbstractEndpoint.class);
        when(endpoint.environment()).thenReturn(environment);
        when(environment.userAgent()).thenReturn("Couchbase Client Mock");

        queue = new ArrayDeque<QueryRequest>();
        handler = new QueryHandler(endpoint, responseBuffer.start(), queue);
        channel = new EmbeddedChannel(handler);
    }

    @After
    public void clear() {
        responseBuffer.shutdown();
    }

    private void assertGenericQueryRequest(GenericQueryRequest request, boolean jsonExpected) {
        channel.writeOutbound(request);
        HttpRequest outbound = (HttpRequest) channel.readOutbound();

        assertEquals(HttpMethod.POST, outbound.getMethod());
        assertEquals(HttpVersion.HTTP_1_1, outbound.getProtocolVersion());
        assertEquals("/query", outbound.getUri());
        assertFalse(outbound.headers().contains(HttpHeaders.Names.AUTHORIZATION));
        assertEquals("Couchbase Client Mock", outbound.headers().get(HttpHeaders.Names.USER_AGENT));
        assertTrue(outbound instanceof FullHttpRequest);
        assertEquals("query", ((FullHttpRequest) outbound).content().toString(CharsetUtil.UTF_8));
        if (jsonExpected) {
            assertEquals("application/json", outbound.headers().get(HttpHeaders.Names.CONTENT_TYPE));
        } else {
            assertNotEquals("application/json", outbound.headers().get(HttpHeaders.Names.CONTENT_TYPE));
        }
    }

    @Test
    public void shouldEncodeSimpleStatementToGenericQueryRequest() {
        GenericQueryRequest request = GenericQueryRequest.simpleStatement("query", "bucket", "password");
        assertGenericQueryRequest(request, false);
    }

    @Test
    public void shouldEncodeJsonQueryToGenericQueryRequest() {
        GenericQueryRequest request = GenericQueryRequest.jsonQuery("query", "bucket", "password");
        assertGenericQueryRequest(request, true);
    }

    private void assertResponse(GenericQueryResponse inbound,
            boolean expectedSuccess, ResponseStatus expectedStatus,
            String expectedRequestId, String expectedClientId,
            String expectedFinalStatus,
            Action1<ByteBuf> assertRows,
            Action1<ByteBuf> assertErrors,
            Map<String, Object> metricsToCheck) {
        assertEquals(expectedSuccess, inbound.status().isSuccess());
        assertEquals(expectedStatus, inbound.status());
        assertEquals(expectedRequestId, inbound.requestId());
        assertEquals(expectedClientId, inbound.clientRequestId());

        assertEquals(expectedFinalStatus, inbound.queryStatus().timeout(1, TimeUnit.SECONDS).toBlocking().single());

        inbound.rows().timeout(5, TimeUnit.SECONDS).toBlocking()
               .forEach(assertRows);

        List<ByteBuf> metricList = inbound.info().timeout(1, TimeUnit.SECONDS).toList().toBlocking().single();
        assertEquals(1, metricList.size());
        String metricsJson = metricList.get(0).toString(CharsetUtil.UTF_8);
        try {
            Map metrics = mapper.readValue(metricsJson, Map.class);
            assertEquals(7, metrics.size());

            for (Map.Entry<String, Object> entry : metricsToCheck.entrySet()) {
                assertNotNull(metrics.get(entry.getKey()));
                assertEquals(entry.getKey(), entry.getValue(), metrics.get(entry.getKey()));
            }
        } catch (IOException e) {
            fail();
        }

        inbound.errors().timeout(1, TimeUnit.SECONDS).toBlocking()
               .forEach(assertErrors);
    }

    private static Map<String, Object> expectedMetricsCounts(int expectedErrors, int expectedResults) {
        Map<String, Object> result = new HashMap<String, Object>(2);
        result.put("errorCount", expectedErrors);
        result.put("resultCount", expectedResults);
        return result;
    }

    @Test
    public void shouldDecodeErrorResponse() throws Exception {
        String response = Resources.read("parse_error.json", this.getClass());
        HttpResponse responseHeader = new DefaultHttpResponse(HttpVersion.HTTP_1_1, new HttpResponseStatus(200, "OK"));
        HttpContent responseChunk = new DefaultLastHttpContent(Unpooled.copiedBuffer(response, CharsetUtil.UTF_8));

        GenericQueryRequest requestMock = mock(GenericQueryRequest.class);
        queue.add(requestMock);
        channel.writeInbound(responseHeader, responseChunk);
        latch.await(1, TimeUnit.SECONDS);
        assertEquals(1, firedEvents.size());
        GenericQueryResponse inbound = (GenericQueryResponse) firedEvents.get(0);

        assertResponse(inbound, false, ResponseStatus.FAILURE, FAKE_REQUESTID, FAKE_CLIENTID, "fatal",
                new Action1<ByteBuf>() {
                    @Override
                    public void call(ByteBuf byteBuf) {
                        fail("no result expected");
                    }
                },
                new Action1<ByteBuf>() {
                    @Override
                    public void call(ByteBuf buf) {
                        String response = buf.toString(CharsetUtil.UTF_8);
                        try {
                            Map error = mapper.readValue(response, Map.class);
                            assertEquals(5, error.size());
                            assertEquals(new Integer(4100), error.get("code"));
                            assertEquals(Boolean.FALSE, error.get("temp"));
                            assertEquals("Parse Error", error.get("msg"));
                        } catch (IOException e) {
                            fail();
                        }
                    }
                },
                expectedMetricsCounts(1, 0)
        );
    }

    @Test
    public void shouldDecodeChunkedErrorResponse() throws Exception {
        String response = Resources.read("parse_error.json", this.getClass());
        HttpResponse responseHeader = new DefaultHttpResponse(HttpVersion.HTTP_1_1, new HttpResponseStatus(200, "OK"));
        HttpContent responseChunk1 = new DefaultHttpContent(Unpooled.copiedBuffer(response.substring(0, 223), CharsetUtil.UTF_8));
        HttpContent responseChunk2 = new DefaultLastHttpContent(Unpooled.copiedBuffer(response.substring(223), CharsetUtil.UTF_8));

        GenericQueryRequest requestMock = mock(GenericQueryRequest.class);
        queue.add(requestMock);
        channel.writeInbound(responseHeader, responseChunk1, responseChunk2);
        latch.await(1, TimeUnit.SECONDS);
        assertEquals(1, firedEvents.size());
        GenericQueryResponse inbound = (GenericQueryResponse) firedEvents.get(0);

        assertResponse(inbound, false, ResponseStatus.FAILURE, FAKE_REQUESTID, FAKE_CLIENTID, "fatal",
                new Action1<ByteBuf>() {
                    @Override
                    public void call(ByteBuf byteBuf) {
                        fail("no result expected");
                    }
                },
                new Action1<ByteBuf>() {
                    @Override
                    public void call(ByteBuf buf) {
                        String response = buf.toString(CharsetUtil.UTF_8);
                        try {
                            Map error = mapper.readValue(response, Map.class);
                            assertEquals(5, error.size());
                            assertEquals(new Integer(4100), error.get("code"));
                            assertEquals(Boolean.FALSE, error.get("temp"));
                            assertEquals("Parse Error", error.get("msg"));
                        } catch (IOException e) {
                            fail();
                        }
                    }
                },
                expectedMetricsCounts(1, 0)
        );
    }

    @Test
    public void shouldDecodeEmptySuccessResponse() throws Exception {
        String response = Resources.read("success_0.json", this.getClass());
        HttpResponse responseHeader = new DefaultHttpResponse(HttpVersion.HTTP_1_1, new HttpResponseStatus(200, "OK"));
        HttpContent responseChunk = new DefaultLastHttpContent(Unpooled.copiedBuffer(response, CharsetUtil.UTF_8));

        GenericQueryRequest requestMock = mock(GenericQueryRequest.class);
        queue.add(requestMock);
        channel.writeInbound(responseHeader, responseChunk);
        latch.await(1, TimeUnit.SECONDS);
        assertEquals(1, firedEvents.size());
        GenericQueryResponse inbound = (GenericQueryResponse) firedEvents.get(0);

        assertResponse(inbound, true, ResponseStatus.SUCCESS, FAKE_REQUESTID, FAKE_CLIENTID, "success",
                new Action1<ByteBuf>() {
                    @Override
                    public void call(ByteBuf byteBuf) {
                        fail("no result expected");
                    }
                },
                new Action1<ByteBuf>() {
                    @Override
                    public void call(ByteBuf buf) {
                        fail("no error expected");
                    }
                },
                expectedMetricsCounts(0, 0)
        );
    }

    @Test
    public void shouldDecodeOneRowResponse() throws Exception {
        String response = Resources.read("success_1.json", this.getClass());
        HttpResponse responseHeader = new DefaultHttpResponse(HttpVersion.HTTP_1_1, new HttpResponseStatus(200, "OK"));
        HttpContent responseChunk = new DefaultLastHttpContent(Unpooled.copiedBuffer(response, CharsetUtil.UTF_8));

        GenericQueryRequest requestMock = mock(GenericQueryRequest.class);
        queue.add(requestMock);
        channel.writeInbound(responseHeader, responseChunk);
        latch.await(1, TimeUnit.SECONDS);
        assertEquals(1, firedEvents.size());
        GenericQueryResponse inbound = (GenericQueryResponse) firedEvents.get(0);

        final AtomicInteger invokeCounter1 = new AtomicInteger();
        assertResponse(inbound, true, ResponseStatus.SUCCESS, FAKE_REQUESTID, FAKE_CLIENTID, "success",
                new Action1<ByteBuf>() {
                    @Override
                    public void call(ByteBuf buf) {
                        invokeCounter1.incrementAndGet();
                        String response = buf.toString(CharsetUtil.UTF_8);
                        try {
                            Map found = mapper.readValue(response, Map.class);
                            assertEquals(12, found.size());
                            assertEquals("San Francisco", found.get("city"));
                            assertEquals("United States", found.get("country"));
                            Map geo = (Map) found.get("geo");
                            assertNotNull(geo);
                            assertEquals(3, geo.size());
                            assertEquals("ROOFTOP", geo.get("accuracy"));
                        } catch (IOException e) {
                            fail("no result expected");
                        }
                    }
                },
                new Action1<ByteBuf>() {
                    @Override
                    public void call(ByteBuf buf) {
                        fail("no error expected");
                    }
                },
                expectedMetricsCounts(0, 1)
        );
        assertEquals(1, invokeCounter1.get());
    }

    @Test
    public void shouldDecodeNRowResponse() throws Exception {
        String response = Resources.read("success_5.json", this.getClass());
        HttpResponse responseHeader = new DefaultHttpResponse(HttpVersion.HTTP_1_1, new HttpResponseStatus(200, "OK"));
        HttpContent responseChunk = new DefaultLastHttpContent(Unpooled.copiedBuffer(response, CharsetUtil.UTF_8));

        GenericQueryRequest requestMock = mock(GenericQueryRequest.class);
        queue.add(requestMock);
        channel.writeInbound(responseHeader, responseChunk);
        latch.await(1, TimeUnit.SECONDS);
        assertEquals(1, firedEvents.size());
        GenericQueryResponse inbound = (GenericQueryResponse) firedEvents.get(0);

        final AtomicInteger found = new AtomicInteger(0);
        assertResponse(inbound, true, ResponseStatus.SUCCESS, FAKE_REQUESTID, FAKE_CLIENTID, "success",
                new Action1<ByteBuf>() {
                    @Override
                    public void call(ByteBuf row) {
                        found.incrementAndGet();
                        String content = row.toString(CharsetUtil.UTF_8);
                        assertNotNull(content);
                        assertTrue(!content.isEmpty());
                        try {
                            Map decoded = mapper.readValue(content, Map.class);
                            assertTrue(decoded.size() > 0);
                            assertTrue(decoded.containsKey("name"));
                        } catch(Exception e) {
                            assertTrue(false);
                        }
                    }
                },
                new Action1<ByteBuf>() {
                    @Override
                    public void call(ByteBuf buf) {
                        fail("no error expected");
                    }
                },
                expectedMetricsCounts(0, 5)
        );
        assertEquals(5, found.get());
    }

    @Test
    public void shouldDecodeNRowResponseChunked() throws Exception {
        String response = Resources.read("success_5.json", this.getClass());
        HttpResponse responseHeader = new DefaultHttpResponse(HttpVersion.HTTP_1_1, new HttpResponseStatus(200, "OK"));
        HttpContent responseChunk1 = new DefaultLastHttpContent(Unpooled.copiedBuffer(response.substring(0, 300),
            CharsetUtil.UTF_8));
        HttpContent responseChunk2 = new DefaultLastHttpContent(Unpooled.copiedBuffer(response.substring(300, 950),
            CharsetUtil.UTF_8));
        HttpContent responseChunk3 = new DefaultLastHttpContent(Unpooled.copiedBuffer(response.substring(950, 1345),
            CharsetUtil.UTF_8));
        HttpContent responseChunk4 = new DefaultLastHttpContent(Unpooled.copiedBuffer(response.substring(1345, 3000),
            CharsetUtil.UTF_8));
        HttpContent responseChunk5 = new DefaultLastHttpContent(Unpooled.copiedBuffer(response.substring(3000),
            CharsetUtil.UTF_8));

        GenericQueryRequest requestMock = mock(GenericQueryRequest.class);
        queue.add(requestMock);
        channel.writeInbound(responseHeader, responseChunk1, responseChunk2, responseChunk3, responseChunk4,
            responseChunk5);
        latch.await(1, TimeUnit.SECONDS);
        assertEquals(1, firedEvents.size());
        GenericQueryResponse inbound = (GenericQueryResponse) firedEvents.get(0);

        final AtomicInteger found = new AtomicInteger(0);
        assertResponse(inbound, true, ResponseStatus.SUCCESS, FAKE_REQUESTID, FAKE_CLIENTID, "success",
                new Action1<ByteBuf>() {
                    @Override
                    public void call(ByteBuf byteBuf) {
                        found.incrementAndGet();
                        String content = byteBuf.toString(CharsetUtil.UTF_8);
                        assertNotNull(content);
                        assertTrue(!content.isEmpty());
                        try {
                            Map decoded = mapper.readValue(content, Map.class);
                            assertTrue(decoded.size() > 0);
                            assertTrue(decoded.containsKey("name"));
                        } catch(Exception e) {
                            assertTrue(false);
                        }
                    }
                },
                new Action1<ByteBuf>() {
                    @Override
                    public void call(ByteBuf buf) {
                        fail("no error expected");
                    }
                },
                expectedMetricsCounts(0, 5)
        );
        assertEquals(5, found.get());
    }

    @Test
    public void shouldDecodeOneRowResponseWithQuotesInClientIdAndResults() throws Exception {
        String response = Resources.read("with_escaped_quotes.json", this.getClass());
        HttpResponse responseHeader = new DefaultHttpResponse(HttpVersion.HTTP_1_1, new HttpResponseStatus(200, "OK"));
        HttpContent responseChunk = new DefaultLastHttpContent(Unpooled.copiedBuffer(response, CharsetUtil.UTF_8));

        GenericQueryRequest requestMock = mock(GenericQueryRequest.class);
        queue.add(requestMock);
        channel.writeInbound(responseHeader, responseChunk);
        latch.await(1, TimeUnit.SECONDS);
        assertEquals(1, firedEvents.size());
        GenericQueryResponse inbound = (GenericQueryResponse) firedEvents.get(0);

        final AtomicInteger invokeCounter1 = new AtomicInteger();
        //TODO check the quote in the clientID
        assertResponse(inbound, true, ResponseStatus.SUCCESS, FAKE_REQUESTID, FAKE_CLIENTID, "success",
                new Action1<ByteBuf>() {
                    @Override
                    public void call(ByteBuf buf) {
                        invokeCounter1.incrementAndGet();
                        String response = buf.toString(CharsetUtil.UTF_8);
                        try {
                            Map found = mapper.readValue(response, Map.class);
                            assertEquals(12, found.size());
                            assertEquals("San Francisco", found.get("city"));
                            assertEquals("United States", found.get("country"));
                            Map geo = (Map) found.get("geo");
                            assertNotNull(geo);
                            assertEquals(3, geo.size());
                            assertEquals("ROOFTOP", geo.get("accuracy"));
                            //TODO check the quote in the result
                        } catch (IOException e) {
                            assertFalse(true);
                        }                    }
                },
                new Action1<ByteBuf>() {
                    @Override
                    public void call(ByteBuf buf) {
                        fail("no error expected");
                    }
                },
                expectedMetricsCounts(0, 1)
        );
        assertEquals(1, invokeCounter1.get());
    }

    @Test
    public void shouldDecodeOneRowResponseWithShortClientID() throws Exception {
        String response = Resources.read("short_client_id.json", this.getClass());
        HttpResponse responseHeader = new DefaultHttpResponse(HttpVersion.HTTP_1_1, new HttpResponseStatus(200, "OK"));
        HttpContent responseChunk = new DefaultLastHttpContent(Unpooled.copiedBuffer(response, CharsetUtil.UTF_8));

        GenericQueryRequest requestMock = mock(GenericQueryRequest.class);
        queue.add(requestMock);
        channel.writeInbound(responseHeader, responseChunk);
        latch.await(1, TimeUnit.SECONDS);
        assertEquals(1, firedEvents.size());
        GenericQueryResponse inbound = (GenericQueryResponse) firedEvents.get(0);

        final AtomicInteger invokeCounter1 = new AtomicInteger();
        assertResponse(inbound, true, ResponseStatus.SUCCESS, FAKE_REQUESTID, "123456789", "success",
                new Action1<ByteBuf>() {
                    @Override
                    public void call(ByteBuf buf) {
                        invokeCounter1.incrementAndGet();
                        String response = buf.toString(CharsetUtil.UTF_8);
                        try {
                            Map found = mapper.readValue(response, Map.class);
                            assertEquals(12, found.size());
                            assertEquals("San Francisco", found.get("city"));
                            assertEquals("United States", found.get("country"));
                            Map geo = (Map) found.get("geo");
                            assertNotNull(geo);
                            assertEquals(3, geo.size());
                            assertEquals("ROOFTOP", geo.get("accuracy"));
                        } catch (IOException e) {
                            assertFalse(true);
                        }
                    }
                },
                new Action1<ByteBuf>() {
                    @Override
                    public void call(ByteBuf buf) {
                        fail("no error expected");
                    }
                },
                expectedMetricsCounts(0, 1)
        );
        assertEquals(1, invokeCounter1.get());
    }

    @Test
    public void shouldDecodeOneRowResponseWithNoClientID() throws Exception {
        String response = Resources.read("no_client_id.json", this.getClass());
        HttpResponse responseHeader = new DefaultHttpResponse(HttpVersion.HTTP_1_1, new HttpResponseStatus(200, "OK"));
        HttpContent responseChunk = new DefaultLastHttpContent(Unpooled.copiedBuffer(response, CharsetUtil.UTF_8));

        GenericQueryRequest requestMock = mock(GenericQueryRequest.class);
        queue.add(requestMock);
        channel.writeInbound(responseHeader, responseChunk);
        latch.await(1, TimeUnit.SECONDS);
        assertEquals(1, firedEvents.size());
        GenericQueryResponse inbound = (GenericQueryResponse) firedEvents.get(0);

        final AtomicInteger invokeCounter1 = new AtomicInteger();
        assertResponse(inbound, true, ResponseStatus.SUCCESS, FAKE_REQUESTID, "", "success",
                new Action1<ByteBuf>() {
                    @Override
                    public void call(ByteBuf buf) {
                        invokeCounter1.incrementAndGet();
                        String response = buf.toString(CharsetUtil.UTF_8);
                        try {
                            Map found = mapper.readValue(response, Map.class);
                            assertEquals(12, found.size());
                            assertEquals("San Francisco", found.get("city"));
                            assertEquals("United States", found.get("country"));
                            Map geo = (Map) found.get("geo");
                            assertNotNull(geo);
                            assertEquals(3, geo.size());
                            assertEquals("ROOFTOP", geo.get("accuracy"));
                        } catch (IOException e) {
                            assertFalse(true);
                        }
                    }
                },
                new Action1<ByteBuf>() {
                    @Override
                    public void call(ByteBuf buf) {
                        fail("no error expected");
                    }
                },
                expectedMetricsCounts(0, 1)
        );
        assertEquals(1, invokeCounter1.get());
    }

    @Test
    public void shouldDecodeOneRowResponseWithoutPrettyPrint() throws Exception {
        String response = Resources.read("no_pretty.json", this.getClass());
        HttpResponse responseHeader = new DefaultHttpResponse(HttpVersion.HTTP_1_1, new HttpResponseStatus(200, "OK"));
        HttpContent responseChunk = new DefaultLastHttpContent(Unpooled.copiedBuffer(response, CharsetUtil.UTF_8));

        GenericQueryRequest requestMock = mock(GenericQueryRequest.class);
        queue.add(requestMock);
        channel.writeInbound(responseHeader, responseChunk);
        latch.await(1, TimeUnit.SECONDS);
        assertEquals(1, firedEvents.size());
        GenericQueryResponse inbound = (GenericQueryResponse) firedEvents.get(0);

        final AtomicInteger invokeCounter1 = new AtomicInteger();
        assertResponse(inbound, true, ResponseStatus.SUCCESS, FAKE_REQUESTID, FAKE_CLIENTID, "success",
                new Action1<ByteBuf>() {
                    @Override
                    public void call(ByteBuf buf) {
                        invokeCounter1.incrementAndGet();
                        String response = buf.toString(CharsetUtil.UTF_8);
                        try {
                            Map found = mapper.readValue(response, Map.class);
                            assertEquals(12, found.size());
                            assertEquals("San Francisco", found.get("city"));
                            assertEquals("United States", found.get("country"));
                            Map geo = (Map) found.get("geo");
                            assertNotNull(geo);
                            assertEquals(3, geo.size());
                            assertEquals("ROOFTOP", geo.get("accuracy"));
                        } catch (IOException e) {
                            assertFalse(true);
                        }
                    }
                },
                new Action1<ByteBuf>() {
                    @Override
                    public void call(ByteBuf buf) {
                        fail("no error expected");
                    }
                },
                expectedMetricsCounts(0, 1)
        );
        assertEquals(1, invokeCounter1.get());
    }

    @Test
    public void shouldGroupErrorsAndWarnings() throws InterruptedException {
        String response = Resources.read("errors_and_warnings.json", this.getClass());
        HttpResponse responseHeader = new DefaultHttpResponse(HttpVersion.HTTP_1_1, new HttpResponseStatus(200, "OK"));
        HttpContent responseChunk = new DefaultLastHttpContent(Unpooled.copiedBuffer(response, CharsetUtil.UTF_8));

        GenericQueryRequest requestMock = mock(GenericQueryRequest.class);
        queue.add(requestMock);
        channel.writeInbound(responseHeader, responseChunk);
        latch.await(1, TimeUnit.SECONDS);
        assertEquals(1, firedEvents.size());
        GenericQueryResponse inbound = (GenericQueryResponse) firedEvents.get(0);

        Map<String, Object> expectedMetrics = expectedMetricsCounts(1, 0);
        expectedMetrics.put("warningCount", 1);

        final AtomicInteger count = new AtomicInteger(0);
        assertResponse(inbound, false, ResponseStatus.FAILURE, FAKE_REQUESTID, FAKE_CLIENTID, "fatal",
                new Action1<ByteBuf>() {
                    @Override
                    public void call(ByteBuf byteBuf) {
                        fail("no result expected");
                    }
                },
                new Action1<ByteBuf>() {
                    @Override
                    public void call(ByteBuf buf) {
                        count.incrementAndGet();
                        String response = buf.toString(CharsetUtil.UTF_8);
                        try {
                            Map error = mapper.readValue(response, Map.class);
                            assertEquals(5, error.size());
                            if (count.get() == 1) {
                                assertEquals(new Integer(4100), error.get("code"));
                                assertEquals(Boolean.FALSE, error.get("temp"));
                                assertEquals("Parse Error", error.get("msg"));
                            } else if (count.get() == 2) {
                                assertEquals(3, error.get("sev"));
                                assertEquals(201, error.get("code"));
                                assertEquals(Boolean.TRUE, error.get("temp"));
                                assertEquals("Nothing to do", error.get("msg"));
                                assertEquals("nothingToDo", error.get("name"));
                            }
                        } catch (IOException e) {
                            fail();
                        }
                    }
                },
                expectedMetrics
        );
        assertEquals(2, count.get());
    }
}

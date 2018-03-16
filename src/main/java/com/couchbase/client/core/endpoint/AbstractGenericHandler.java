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
package com.couchbase.client.core.endpoint;

import com.couchbase.client.core.ResponseEvent;
import com.couchbase.client.core.ResponseHandler;
import com.couchbase.client.core.message.CouchbaseRequest;
import com.couchbase.client.core.message.CouchbaseResponse;
import com.couchbase.client.core.message.ResponseStatus;
import com.lmax.disruptor.RingBuffer;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToMessageCodec;
import io.netty.util.CharsetUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.Charset;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;

/**
 * Generic handler which acts as the common base type for all implementing handlers.
 *
 * @author Michael Nitschinger
 * @since 1.0
 */
public abstract class AbstractGenericHandler<RESPONSE, ENCODED, REQUEST extends CouchbaseRequest>
    extends MessageToMessageCodec<RESPONSE, REQUEST> {

    /**
     * The default charset to use for all requests and responses.
     */
    protected static final Charset CHARSET = CharsetUtil.UTF_8;

    /**
     * The logger which should be used.
     */
    private static final Logger LOGGER = LoggerFactory.getLogger(AbstractGenericHandler.class);

    /**
     * The response buffer to push response events into.
     */
    private final RingBuffer<ResponseEvent> responseBuffer;

    /**
     * The endpoint held as a reference.
     */
    private final AbstractEndpoint endpoint;

    /**
     * This queue keeps all currently outstanding requests.
     */
    private final Queue<REQUEST> sentRequestQueue;

    /**
     * The request which is expected to return next.
     */
    private REQUEST currentRequest;

    /**
     * Creates a new {@link AbstractGenericHandler} with the default queue.
     *
     * @param endpoint the endpoint reference.
     * @param responseBuffer the response buffer.
     */
    protected AbstractGenericHandler(final AbstractEndpoint endpoint, final RingBuffer<ResponseEvent> responseBuffer) {
        this(endpoint, responseBuffer, new ArrayDeque<REQUEST>());
    }

    /**
     * Creates a new {@link AbstractGenericHandler} with a custom queue.
     *
     * @param endpoint the endpoint reference.
     * @param responseBuffer the response buffer.
     * @param queue the queue.
     */
    protected AbstractGenericHandler(final AbstractEndpoint endpoint, final RingBuffer<ResponseEvent> responseBuffer,
        final Queue<REQUEST> queue) {
        this.endpoint = endpoint;
        this.responseBuffer = responseBuffer;
        this.sentRequestQueue = queue;
    }

    /**
     * Encode the outgoing {@link REQUEST} and return it in {@link ENCODED} format.
     *
     * This method needs to be implemented by the child handler and is responsible for the actual conversion.
     *
     * @param ctx the context passed in.
     * @param msg the outgoing message.
     * @return the encoded request.
     * @throws Exception as a generic error.
     */
    protected abstract ENCODED encodeRequest(ChannelHandlerContext ctx, REQUEST msg) throws Exception;

    /**
     * Decodes the incoming {@link RESPONSE} and transforms it into a {@link CouchbaseResponse}.
     *
     * Note that the actual notification is handled by this generic handler, the implementing class only is concerned
     * about the conversion itself.
     *
     * @param ctx the context passed in.
     * @param msg the incoming message.
     * @return a response or null if nothing should be returned.
     * @throws Exception as a generic error.
     */
    protected abstract CouchbaseResponse decodeResponse(ChannelHandlerContext ctx, RESPONSE msg) throws Exception;

    @Override
    protected void encode(ChannelHandlerContext ctx, REQUEST msg, List<Object> out) throws Exception {
        ENCODED request = encodeRequest(ctx, msg);
        sentRequestQueue.offer(msg);
        out.add(request);
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, RESPONSE msg, List<Object> out) throws Exception {
        if (currentRequest == null) {
            currentRequest = sentRequestQueue.poll();
        }

        CouchbaseResponse response = decodeResponse(ctx, msg);
        if (response != null) {
            responseBuffer.publishEvent(ResponseHandler.RESPONSE_TRANSLATOR, response, currentRequest.observable());
            if (response.status() != ResponseStatus.CHUNKED) {
                currentRequest = null;
            }
        }
    }

    @Override
    public void channelInactive(final ChannelHandlerContext ctx) throws Exception {
        LOGGER.debug(logIdent(ctx, endpoint) + "Channel Inactive.");
        endpoint.notifyChannelInactive();
        ctx.fireChannelInactive();
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        LOGGER.debug(logIdent(ctx, endpoint) + "Channel Active.");
        ctx.fireChannelActive();
    }

    @Override
    public void channelWritabilityChanged(final ChannelHandlerContext ctx) throws Exception {
        if (!ctx.channel().isWritable()) {
            ctx.flush();
        }
        ctx.fireChannelWritabilityChanged();
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        if (cause instanceof IOException) {
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug(logIdent(ctx, endpoint) + "Connection reset by peer: " + cause.getMessage(), cause);
            } else {
                LOGGER.info(logIdent(ctx, endpoint) + "Connection reset by peer: " + cause.getMessage());
            }
            rescheduleOutstandingOps(ctx);
        } else {
            LOGGER.warn(logIdent(ctx, endpoint) + "Caught unknown exception: " + cause.getMessage(), cause);
            ctx.fireExceptionCaught(cause);
        }
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) throws Exception {
        rescheduleOutstandingOps(ctx);
    }

    /**
     * Reschedules any outstanding operations which are currently on the wire.
     *
     * Note that this is experimental functionaly and may be removed since we can't properly assert what happens
     * to them.
     *
     * @param ctx the handler context.
     */
    private void rescheduleOutstandingOps(final ChannelHandlerContext ctx) {
        if (sentRequestQueue.isEmpty()) {
            LOGGER.trace(logIdent(ctx, endpoint) + "Not rescheduling any operations - sent request queue empty.");
            return;
        }

        LOGGER.debug(logIdent(ctx, endpoint) + "Rescheduling " + sentRequestQueue.size() + " outstanding requests.");
        while(!sentRequestQueue.isEmpty()) {
            REQUEST req = sentRequestQueue.poll();
            responseBuffer.publishEvent(ResponseHandler.RESPONSE_TRANSLATOR, req, req.observable());
        }
    }

    /**
     * Returns the current request if set.
     *
     * @return the current request.
     */
    protected REQUEST currentRequest() {
        return currentRequest;
    }

    /**
     * Sets the current request.
     *
     * Note that this method should normally not be used, only if a certain state needs to be replied even if a message
     * for it has already been transmitted (but more are expected).
     *
     * @param currentRequest the request to set.
     */
    protected void currentRequest(REQUEST currentRequest) {
        this.currentRequest = currentRequest;
    }

    /**
     * Simple log helper to give logs a common prefix.
     *
     * @param ctx the context.
     * @param endpoint the endpoint.
     * @return a prefix string for logs.
     */
    protected static String logIdent(final ChannelHandlerContext ctx, final Endpoint endpoint) {
        return "["+ctx.channel().remoteAddress()+"][" + endpoint.getClass().getSimpleName()+"]: ";
    }

}

/*
 * Copyright 2010 david varnes.
 *
 * Licensed under the Apache License, version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at:
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.freeswitch.esl.client.internal;

import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import org.freeswitch.esl.client.transport.event.EslEvent;
import org.freeswitch.esl.client.transport.message.EslMessage;
import org.freeswitch.esl.client.transport.message.EslHeaders.Name;
import org.freeswitch.esl.client.transport.message.EslHeaders.Value;
import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.channel.ChannelUpstreamHandler;
import org.jboss.netty.channel.MessageEvent;
import org.jboss.netty.channel.SimpleChannelUpstreamHandler;
import org.jboss.netty.handler.execution.ExecutionHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Specialised {@link ChannelUpstreamHandler} that implements the logic of an ESL connection that
 * is common to both inbound and outbound clients. This
 * handler expects to receive decoded {@link EslMessage} or {@link EslEvent} objects. The key
 * responsibilities for this class are:
 * <ul><li>
 * To synthesise a synchronous command/response api.  All IO operations using the underlying Netty
 * library are intrinsically asynchronous which provides for excellent response and scalability.  This
 * class provides for a blocking wait mechanism for responses to commands issued to the server.  A
 * key assumption here is that the FreeSWITCH server will process synchronous requests in the order they
 * are received.
 * </li><li>
 * Concrete sub classes are expected to 'terminate' the Netty IO processing pipeline (ie be the 'last'
 * handler).
 * </li></ul>
 * Note: implementation requirement is that an {@link ExecutionHandler} is placed in the processing
 * pipeline prior to this handler. This will ensure that each incoming message is processed in its
 * own thread (although still guaranteed to be processed in the order of receipt).
 *
 * @author david varnes
 */
public abstract class AbstractEslClientHandler extends SimpleChannelUpstreamHandler {
    public static final String MESSAGE_TERMINATOR = "\n\n";
    public static final String LINE_TERMINATOR    = "\n";
    public static final long   DEFAULT_TIMEOUT    = 0L;

    protected final Logger log = LoggerFactory.getLogger(this.getClass());

    private final Lock                syncLock      = new ReentrantLock();
    private final Queue<SyncCallback> syncCallbacks = new ConcurrentLinkedQueue<SyncCallback>();

    @Override
    public void messageReceived(ChannelHandlerContext ctx, MessageEvent e) throws Exception {
        if (e.getMessage() instanceof EslMessage) {
            EslMessage message = (EslMessage) e.getMessage();
//            log.debug("contentType = " + message.getContentType() + " content bodyLines" + message.getBodyLines());
            if (message == null ||
                (message.getContentType() == null && message.getBodyLines().size() == 0)) {
                return;
            }
            String contentType = message.getContentType();
            if (Value.TEXT_EVENT_PLAIN.equals(contentType) ||
                Value.TEXT_EVENT_XML.equals(contentType) ||
                Value.TEXT_EVENT_JSON.equals(contentType)) {
                //  transform into an event
                EslEvent eslEvent = new EslEvent(message);
                handleEslEvent(ctx, eslEvent);
            } else {
                handleEslMessage(ctx, (EslMessage) e.getMessage());
            }
        } else {
            throw new IllegalStateException("Unexpected message type: " + e.getMessage().getClass());
        }
    }

    /**
     * Synthesise a synchronous command/response by creating a callback object which is placed in
     * queue and blocks waiting for another IO thread to process an incoming {@link EslMessage} and
     * attach it to the callback.
     *
     * @param channel
     * @param command single string to send
     * @return the {@link EslMessage} attached to this command's callback
     */
    public EslMessage sendSyncSingleLineCommand(Channel channel, final String command) {
        return sendSyncSingleLineCommand(channel, command, DEFAULT_TIMEOUT);
    }

    /**
     * Synthesise a synchronous command/response by creating a callback object which is placed in
     * queue and blocks waiting for another IO thread to process an incoming {@link EslMessage} and
     * attach it to the callback.
     *
     * @param channel
     * @param command single string to send
     * @param timeout millisecond to wait
     * @return the {@link EslMessage} attached to this command's callback
     */
    public EslMessage sendSyncSingleLineCommand(Channel channel, final String command, long timeout) {
        SyncCallback callback = new SyncCallback();
        syncLock.lock();
        try {
            syncCallbacks.add(callback);
            channel.write(command + MESSAGE_TERMINATOR);
        } finally {
            syncLock.unlock();
        }

        EslMessage message = new EslMessage();
        try {
            //  Block until the response is available or timeout
            message = callback.get(timeout);
        } catch (RuntimeException e) {
            syncCallbacks.remove(callback);
            throw new RuntimeException(e);
        }
        return message;
    }

    /**
     * Synthesise a synchronous command/response by creating a callback object which is placed in
     * queue and blocks waiting for another IO thread to process an incoming {@link EslMessage} and
     * attach it to the callback.
     *
     * @param channel
     * @param commandLines List of command lines to send
     * @return the {@link EslMessage} attached to this command's callback
     */
    public EslMessage sendSyncMultiLineCommand(Channel channel, final List<String> commandLines) {
        return sendSyncMultiLineCommand(channel, commandLines, DEFAULT_TIMEOUT);
    }

    /**
     * Synthesise a synchronous command/response by creating a callback object which is placed in
     * queue and blocks waiting for another IO thread to process an incoming {@link EslMessage} and
     * attach it to the callback.
     *
     * @param channel
     * @param commandLines List of command lines to send
     * @param timeout
     * @return the {@link EslMessage} attached to this command's callback
     */
    public EslMessage sendSyncMultiLineCommand(Channel channel, final List<String> commandLines, long timeout) {
        SyncCallback callback = new SyncCallback();
        //  Build command with double line terminator at the end
        StringBuilder sb = new StringBuilder();
        for (String line : commandLines) {
            sb.append(line);
            sb.append(LINE_TERMINATOR);
        }
        sb.append(LINE_TERMINATOR);

        syncLock.lock();
        try {
            syncCallbacks.add(callback);
            channel.write(sb.toString());
        } finally {
            syncLock.unlock();
        }

        EslMessage message = new EslMessage();
        try {
            //  Block until the response is available or timeout
            message = callback.get(timeout);
        } catch (RuntimeException e) {
            syncCallbacks.remove(callback);
            throw new RuntimeException(e);
        }
        return message;
    }

    /**
     * Returns the Job UUID of that the response event will have.
     *
     * @param channel
     * @param command
     * @return Job-UUID as a string
     */
    public String sendAsyncCommand(Channel channel, final String command) {
        return sendAsyncCommand(channel, command, DEFAULT_TIMEOUT);
    }

    /**
     * Returns the Job UUID of that the response event will have.
     *
     * @param channel
     * @param command
     * @param timeout
     * @return Job-UUID as a string
     */
    public String sendAsyncCommand(Channel channel, final String command, long timeout) {
        /*
         * Send synchronously to get the Job-UUID to return, the results of the actual
         * job request will be returned by the server as an async event.
         */
        EslMessage response = sendSyncSingleLineCommand(channel, command, timeout);
        if (response.hasHeader(Name.JOB_UUID)) {
            return response.getHeaderValue(Name.JOB_UUID);
        } else {
            throw new IllegalStateException("Missing Job-UUID header in bgapi response");
        }
    }

    protected void handleEslMessage(ChannelHandlerContext ctx, EslMessage message) {
        log.trace("Received message: [{}]", message);
        String contentType = message.getContentType();

        if (Value.API_RESPONSE.equals(contentType)) {
            log.trace("Api response received [{}]", message);
            syncCallbacks.poll().handle(message);
        } else if (Value.COMMAND_REPLY.equals(contentType)) {
            log.trace("Command reply received [{}]", message);
            syncCallbacks.poll().handle(message);
        } else if (Value.AUTH_REQUEST.equals(contentType)) {
            log.trace("Auth request received [{}]", message);
            handleAuthRequest(ctx);
        } else if (Value.TEXT_DISCONNECT_NOTICE.equals(contentType)) {
            log.trace("Disconnect notice received [{}]", message);
            handleDisconnectionNotice();
        } else if (Value.LOG_DATA.equals(contentType)) {
            log.trace("received otherMessage [{}]", message);
            EslEvent eslEvent = new EslEvent(message);
            handleOtherMessage(eslEvent);
        } else {
            log.warn("Unexpected message content type [{}]", contentType);
        }
    }

    protected abstract void handleEslEvent(ChannelHandlerContext ctx, EslEvent event);

    protected abstract void handleAuthRequest(ChannelHandlerContext ctx);

    protected abstract void handleDisconnectionNotice();

    protected abstract void handleOtherMessage(EslEvent eslEvent);

    private static class SyncCallback {
        private static final Logger         log   = LoggerFactory.getLogger(SyncCallback.class);
        private final        CountDownLatch latch = new CountDownLatch(1);
        private              EslMessage     response;

        /**
         * Block waiting for the countdown latch to be released, then return the
         * associated response object.
         *
         * @return
         */
        EslMessage get(long timeout) {
            try {
                log.trace("awaiting latch ... ");
                //  Block until the response is available or timeout
                if (timeout <= DEFAULT_TIMEOUT) {
                    latch.await();
                } else {
                    latch.await(timeout, TimeUnit.MILLISECONDS);
                }
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }

            log.trace("returning response [{}]", response);
            return response;
        }

        /**
         * Attach this response to the callback and release the countdown latch.
         *
         * @param response
         */
        void handle(EslMessage response) {
            this.response = response;
            log.trace("releasing latch for response [{}]", response);
            latch.countDown();
        }
    }

}

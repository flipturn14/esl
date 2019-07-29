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
package org.freeswitch.esl.client.transport.message;

import org.freeswitch.esl.client.internal.HeaderParser;
import org.freeswitch.esl.client.transport.message.EslHeaders.Name;
import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.channel.ChannelPipelineCoverage;
import org.jboss.netty.channel.MessageEvent;
import org.jboss.netty.channel.SimpleChannelUpstreamHandler;
import org.jboss.netty.channel.UpstreamMessageEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Decoder used by the IO processing pipeline. Client consumers should never need to use
 * this class.
 *
 * @author david varnes
 * @version $Id$
 */
@ChannelPipelineCoverage("one")
public class EslMessageDecoder extends SimpleChannelUpstreamHandler {
    private final Logger log = LoggerFactory.getLogger(this.getClass());

    private EslMessage currentMessage;
    private boolean    treatUnknownHeadersAsBody = true;

    public EslMessageDecoder() {

    }

    public EslMessageDecoder(boolean treatUnknownHeadersAsBody) {
        this.treatUnknownHeadersAsBody = treatUnknownHeadersAsBody;
    }

    private enum State {
        NEW_MESSAGE,
        READ_HEADER_LINE,
        READ_BODY_LINE,
    }

    private State state = State.NEW_MESSAGE;

    @Override
    public void messageReceived(ChannelHandlerContext ctx, MessageEvent e) throws Exception {
        // Assume this is a decoded string
        String  messageLine = e.getMessage().toString();
        boolean readContinue;
        do {
            readContinue = false;
            log.trace("State [{}]", state);
            log.trace("message = [{}]", messageLine);
            switch (state) {
                case NEW_MESSAGE:
                    log.trace("new message");
                    if (messageLine.isEmpty()) {
                        break;
                    }
                    currentMessage = new EslMessage();
                    state = State.READ_HEADER_LINE;
                    // fall-through
                case READ_HEADER_LINE:
                    if (messageLine.isEmpty()) {
                        if (EslHeaders.Value.TEXT_EVENT_JSON.equals(currentMessage.getContentType())) {
                            if (currentMessage.getBodyLines().size() == 0) {
                                log.trace("read header to read body line, from json event");
                                state = State.READ_BODY_LINE;
                            }
                        } else {
                            if (currentMessage.hasContentLength()) {
                                log.trace("read header to read body line, cause message is empty and has content length.");
                                state = State.READ_BODY_LINE;
                            } else {
                                // end of message
                                log.trace("read header to new message, cause message is empty.");
                                state = State.NEW_MESSAGE;
                                // send upstream
                                UpstreamMessageEvent upstreamEvent = new UpstreamMessageEvent(e.getChannel(), currentMessage, e.getRemoteAddress());
                                currentMessage = null;
                                ctx.sendUpstream(upstreamEvent);
                            }
                        }
                    } else {
                        log.trace("read header line msg = [{}]", messageLine);
                        // split the line
                        String[] headerParts = HeaderParser.splitHeader(messageLine);
                        currentMessage.addHeader(headerParts[0], headerParts[1]);
                    }
                    break;
                case READ_BODY_LINE:
                    // monitor received content compared to expected content-length
                    log.trace("read body line msg = [{}] ", messageLine);
                    int surplusLength = currentMessage.waitingForContent();
                    log.trace("messageLength = {} surplusLength = {}", messageLine.length() + 1, surplusLength);
                    if (messageLine.length() + 1 == surplusLength) {
                        currentMessage.addBodyLine(messageLine);
                        // end of message
                        log.trace("read body to new message");
                        state = State.NEW_MESSAGE;
                        // send upstream
                        UpstreamMessageEvent upstreamEvent = new UpstreamMessageEvent(e.getChannel(), currentMessage, e.getRemoteAddress());
                        currentMessage = null;
                        ctx.sendUpstream(upstreamEvent);
                    } else if (messageLine.length() > surplusLength) {
                        currentMessage.addBodyLine(messageLine.substring(0, surplusLength));
                        messageLine = messageLine.substring(surplusLength);
                        readContinue = true;
                        state = State.NEW_MESSAGE;
                        // send upstream
                        UpstreamMessageEvent upstreamEvent = new UpstreamMessageEvent(e.getChannel(), currentMessage, e.getRemoteAddress());
                        currentMessage = null;
                        ctx.sendUpstream(upstreamEvent);
                    } else if (messageLine.length() < surplusLength) {
                        currentMessage.addBodyLine(messageLine);
                    } else {
                        log.error("error message [{}]", messageLine);
                    }
                    break;
                default:
                    break;
            }
        } while (readContinue);
    }
}

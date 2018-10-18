/*
 * Copyright (c) 2018, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.wso2.transport.http.netty.contractimpl.sender.states.http2;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.EmptyHttpHeaders;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.codec.http2.EmptyHttp2Headers;
import io.netty.handler.codec.http2.Http2ConnectionEncoder;
import io.netty.handler.codec.http2.Http2Headers;
import io.netty.handler.codec.http2.HttpConversionUtil;
import io.netty.util.ReferenceCountUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wso2.transport.http.netty.contractimpl.common.states.Http2MessageStateContext;
import org.wso2.transport.http.netty.contractimpl.sender.http2.Http2ClientChannel;
import org.wso2.transport.http.netty.contractimpl.sender.http2.Http2DataEventListener;
import org.wso2.transport.http.netty.contractimpl.sender.http2.Http2TargetHandler;
import org.wso2.transport.http.netty.contractimpl.sender.http2.Http2TargetHandler.Http2RequestWriter;
import org.wso2.transport.http.netty.contractimpl.sender.http2.OutboundMsgHolder;
import org.wso2.transport.http.netty.message.Http2DataFrame;
import org.wso2.transport.http.netty.message.Http2HeadersFrame;
import org.wso2.transport.http.netty.message.Http2PushPromise;

import java.io.IOException;

import static org.wso2.transport.http.netty.contract.Constants.INBOUND_RESPONSE_ALREADY_RECEIVED;
import static org.wso2.transport.http.netty.contractimpl.common.states.Http2StateUtil.onPushPromiseRead;
import static org.wso2.transport.http.netty.contractimpl.common.states.Http2StateUtil.writeHttp2Headers;

/**
 * State between start and end of outbound request entity body write.
 */
public class SendingEntityBody implements SenderState {

    private static final Logger LOG = LoggerFactory.getLogger(SendingEntityBody.class);

    private final Http2TargetHandler http2TargetHandler;
    private final Http2MessageStateContext http2MessageStateContext;
    private final OutboundMsgHolder outboundMsgHolder;
    private final Http2ConnectionEncoder encoder;
    private final Http2ClientChannel http2ClientChannel;
    private final int streamId;

    public SendingEntityBody(Http2TargetHandler http2TargetHandler, Http2RequestWriter http2RequestWriter) {
        this.http2TargetHandler = http2TargetHandler;
        this.http2MessageStateContext = http2RequestWriter.getHttp2MessageStateContext();
        this.outboundMsgHolder = http2RequestWriter.getOutboundMsgHolder();
        this.encoder = http2TargetHandler.getEncoder();
        this.http2ClientChannel = http2TargetHandler.getHttp2ClientChannel();
        this.streamId = http2RequestWriter.getStreamId();
    }

    @Override
    public void writeOutboundRequestHeaders(ChannelHandlerContext ctx, HttpContent httpContent) {
        LOG.warn("writeOutboundRequestHeaders is not a dependant action of this state");
    }

    @Override
    public void writeOutboundRequestBody(ChannelHandlerContext ctx, HttpContent httpContent) {
        writeContent(ctx, httpContent);
    }

    @Override
    public void readInboundResponseHeaders(ChannelHandlerContext ctx, Http2HeadersFrame http2HeadersFrame,
                                           OutboundMsgHolder outboundMsgHolder, boolean isServerPush,
                                           Http2MessageStateContext http2MessageStateContext) {
        // This is an action due to an application error. When the initial frames of the response is being received
        // before sending the complete request.
        outboundMsgHolder.getRequest().setIoException(new IOException(INBOUND_RESPONSE_ALREADY_RECEIVED));
        http2MessageStateContext.setSenderState(new ReceivingHeaders(http2TargetHandler));
        http2MessageStateContext.getSenderState().readInboundResponseHeaders(ctx, http2HeadersFrame, outboundMsgHolder,
                isServerPush, http2MessageStateContext);
    }

    @Override
    public void readInboundResponseBody(ChannelHandlerContext ctx, Http2DataFrame http2DataFrame,
                                        OutboundMsgHolder outboundMsgHolder, boolean isServerPush,
                                        Http2MessageStateContext http2MessageStateContext) {
        LOG.warn("readInboundResponseEntityBody is not a dependant action of this state");
    }

    @Override
    public void readInboundPromise(Http2PushPromise http2PushPromise, OutboundMsgHolder outboundMsgHolder) {
        onPushPromiseRead(http2PushPromise, http2ClientChannel, outboundMsgHolder);
    }

    private void writeContent(ChannelHandlerContext ctx, HttpContent msg) {
        boolean release = true;
        try {
            boolean endStream;
            boolean isLastContent = false;
            HttpHeaders trailers = EmptyHttpHeaders.INSTANCE;
            Http2Headers http2Trailers = EmptyHttp2Headers.INSTANCE;
            if (msg instanceof LastHttpContent) {
                isLastContent = true;
                // Convert any trailing headers.
                final LastHttpContent lastContent = (LastHttpContent) msg;
                trailers = lastContent.trailingHeaders();
                http2Trailers = HttpConversionUtil.toHttp2Headers(trailers, true);
            }

            // Write the data
            final ByteBuf content = msg.content();
            endStream = isLastContent && trailers.isEmpty();
            release = false;
            for (Http2DataEventListener dataEventListener : http2ClientChannel.getDataEventListeners()) {
                if (!dataEventListener.onDataWrite(ctx, streamId, content, endStream)) {
                    return;
                }
            }
            encoder.writeData(ctx, streamId, content, 0, endStream, ctx.newPromise());
            encoder.flowController().writePendingBytes();
            ctx.flush();
            if (!trailers.isEmpty()) {
                // Write trailing headers.
                writeHttp2Headers(ctx, outboundMsgHolder, http2ClientChannel, encoder, streamId, trailers,
                        http2Trailers, true);
            }
            if (endStream) {
                outboundMsgHolder.setRequestWritten(true);
                http2MessageStateContext.setSenderState(new RequestCompleted(http2TargetHandler));
            }
        } catch (Exception ex) {
            LOG.error("Error while writing request", ex);
        } finally {
            if (release) {
                ReferenceCountUtil.release(msg);
            }
        }
    }
}

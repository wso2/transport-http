/*
 *  Copyright (c) 2018, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 *  WSO2 Inc. licenses this file to you under the Apache License,
 *  Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied.  See the License for the
 *  specific language governing permissions and limitations
 *  under the License.
 *
 */

package org.wso2.transport.http.netty.contractimpl;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http2.Http2Connection;
import io.netty.handler.codec.http2.Http2ConnectionEncoder;
import io.netty.handler.codec.http2.Http2Exception;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wso2.transport.http.netty.contract.HttpConnectorListener;
import org.wso2.transport.http.netty.contract.HttpResponseFuture;
import org.wso2.transport.http.netty.contract.ServerConnectorException;
import org.wso2.transport.http.netty.contractimpl.common.states.Http2MessageStateContext;
import org.wso2.transport.http.netty.contractimpl.listener.HttpServerChannelInitializer;
import org.wso2.transport.http.netty.contractimpl.listener.states.http2.EntityBodyReceived;
import org.wso2.transport.http.netty.contractimpl.listener.states.http2.SendingHeaders;
import org.wso2.transport.http.netty.message.Http2PushPromise;
import org.wso2.transport.http.netty.message.HttpCarbonMessage;

import java.util.Calendar;

import static org.wso2.transport.http.netty.contract.Constants.PROMISED_STREAM_REJECTED_ERROR;
import static org.wso2.transport.http.netty.contractimpl.common.states.Http2StateUtil.isValidStreamId;

/**
 * {@code Http2OutboundRespListener} is responsible for listening for outbound response messages
 * and delivering them to the client.
 */
public class Http2OutboundRespListener implements HttpConnectorListener {

    private static final Logger LOG = LoggerFactory.getLogger(Http2OutboundRespListener.class);

    private Http2MessageStateContext http2MessageStateContext;
    private HttpCarbonMessage inboundRequestMsg;
    private ChannelHandlerContext ctx;
    private Http2ConnectionEncoder encoder;
    private int originalStreamId;   // stream id of the request received from the client
    private Http2Connection conn;
    private String serverName;
    private HttpResponseFuture outboundRespStatusFuture;
    private HttpServerChannelInitializer serverChannelInitializer;
    private Calendar inboundRequestArrivalTime;
    private String remoteAddress = "-";

    public Http2OutboundRespListener(HttpServerChannelInitializer serverChannelInitializer,
                                     HttpCarbonMessage inboundRequestMsg, ChannelHandlerContext ctx,
                                     Http2Connection conn, Http2ConnectionEncoder encoder, int streamId,
                                     String serverName, String remoteAddress) {
        this.serverChannelInitializer = serverChannelInitializer;
        this.inboundRequestMsg = inboundRequestMsg;
        this.ctx = ctx;
        this.conn = conn;
        this.encoder = encoder;
        this.originalStreamId = streamId;
        this.serverName = serverName;
        if (remoteAddress != null) {
            this.remoteAddress = remoteAddress;
        }
        this.outboundRespStatusFuture = inboundRequestMsg.getHttpOutboundRespStatusFuture();
        inboundRequestArrivalTime = Calendar.getInstance();
        this.http2MessageStateContext = inboundRequestMsg.getHttp2MessageStateContext();
    }

    @Override
    public void onMessage(HttpCarbonMessage outboundResponseMsg) {
        writeMessage(outboundResponseMsg, originalStreamId);
    }

    @Override
    public void onError(Throwable throwable) {
        LOG.error("Couldn't send the outbound response", throwable);
    }

    @Override
    public void onPushPromise(Http2PushPromise pushPromise) {
        writePromise(pushPromise);
    }

    @Override
    public void onPushResponse(int promiseId, HttpCarbonMessage outboundResponseMsg) {
        if (isValidStreamId(promiseId, conn)) {
            writeMessage(outboundResponseMsg, promiseId);
        } else {
            inboundRequestMsg.getHttpOutboundRespStatusFuture()
                    .notifyHttpListener(new ServerConnectorException(PROMISED_STREAM_REJECTED_ERROR));
        }
    }

    private void writePromise(Http2PushPromise pushPromise) {
        ctx.channel().eventLoop().execute(() -> {
            try {
                if (http2MessageStateContext == null) {
                    http2MessageStateContext = new Http2MessageStateContext();
                    http2MessageStateContext.setListenerState(new EntityBodyReceived(http2MessageStateContext));
                }
                http2MessageStateContext.getListenerState().writeOutboundPromise(this, pushPromise);
            } catch (Http2Exception ex) {
                LOG.error("Failed to send push promise : " + ex.getMessage(), ex);
                inboundRequestMsg.getHttpOutboundRespStatusFuture().notifyHttpListener(ex);
            }
        });
    }

    private void writeMessage(HttpCarbonMessage outboundResponseMsg, int streamId) {
        ResponseWriter writer = new ResponseWriter(streamId);
        ctx.channel().eventLoop().execute(() -> outboundResponseMsg.getHttpContentAsync().setMessageListener(
                httpContent -> ctx.channel().eventLoop().execute(() -> {
                    try {
                        writer.writeOutboundResponse(outboundResponseMsg, httpContent);
                    } catch (Http2Exception ex) {
                        LOG.error("Failed to send the outbound response : " + ex.getMessage(), ex);
                        inboundRequestMsg.getHttpOutboundRespStatusFuture().notifyHttpListener(ex);
                    }
                })));
    }

    private class ResponseWriter {

        private int streamId;

        ResponseWriter(int streamId) {
            this.streamId = streamId;
        }

        private void writeOutboundResponse(HttpCarbonMessage outboundResponseMsg, HttpContent httpContent)
                throws Http2Exception {
            if (http2MessageStateContext == null) {
                http2MessageStateContext = new Http2MessageStateContext();
                http2MessageStateContext.setListenerState(
                        new SendingHeaders(Http2OutboundRespListener.this, http2MessageStateContext));
            }
            http2MessageStateContext.getListenerState().writeOutboundResponseBody(Http2OutboundRespListener.this,
                    outboundResponseMsg, httpContent, streamId);
        }
    }

    public ChannelHandlerContext getChannelHandlerContext() {
        return ctx;
    }

    public Http2ConnectionEncoder getEncoder() {
        return encoder;
    }

    public HttpResponseFuture getOutboundRespStatusFuture() {
        return outboundRespStatusFuture;
    }

    public HttpServerChannelInitializer getServerChannelInitializer() {
        return serverChannelInitializer;
    }

    public HttpCarbonMessage getInboundRequestMsg() {
        return inboundRequestMsg;
    }

    public Http2Connection getConnection() {
        return conn;
    }

    public Calendar getInboundRequestArrivalTime() {
        return inboundRequestArrivalTime;
    }

    public int getOriginalStreamId() {
        return originalStreamId;
    }

    public String getRemoteAddress() {
        return remoteAddress;
    }

    public String getServerName() {
        return serverName;
    }
}

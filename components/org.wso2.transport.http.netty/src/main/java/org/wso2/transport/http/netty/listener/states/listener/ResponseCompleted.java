/*
 * Copyright (c) 2018, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.wso2.transport.http.netty.listener.states.listener;

import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wso2.transport.http.netty.contract.ServerConnectorException;
import org.wso2.transport.http.netty.contract.ServerConnectorFuture;
import org.wso2.transport.http.netty.contractimpl.HttpOutboundRespListener;
import org.wso2.transport.http.netty.listener.SourceHandler;
import org.wso2.transport.http.netty.listener.states.MessageStateContext;
import org.wso2.transport.http.netty.message.HttpCarbonMessage;

import static org.wso2.transport.http.netty.common.Constants.REMOTE_CLIENT_CLOSED_WHILE_READING_INBOUND_REQUEST_BODY;
import static org.wso2.transport.http.netty.listener.states.StateUtil.ILLEGAL_STATE_ERROR;
import static org.wso2.transport.http.netty.listener.states.StateUtil.handleIncompleteInboundMessage;

/**
 * State of successfully written response
 */
public class ResponseCompleted implements ListenerState {

    private static Logger log = LoggerFactory.getLogger(ResponseCompleted.class);
    private final SourceHandler sourceHandler;
    private final MessageStateContext messageStateContext;
    private final HttpCarbonMessage inboundRequestMsg;

    ResponseCompleted(SourceHandler sourceHandler, MessageStateContext messageStateContext,
                      HttpCarbonMessage inboundRequestMsg) {
        this.sourceHandler = sourceHandler;
        this.messageStateContext = messageStateContext;
        this.inboundRequestMsg = inboundRequestMsg;
    }

    @Override
    public void readInboundRequestHeaders(HttpCarbonMessage inboundRequestMsg, HttpRequest inboundRequestHeaders) {
        messageStateContext.setListenerState(new ReceivingHeaders(sourceHandler, messageStateContext));
        messageStateContext.getListenerState().readInboundRequestHeaders(inboundRequestMsg, inboundRequestHeaders);
    }

    @Override
    public void readInboundRequestBody(Object inboundRequestEntityBody) throws ServerConnectorException {
        log.warn("readInboundRequestBody {}", ILLEGAL_STATE_ERROR);
    }

    @Override
    public void writeOutboundResponseHeaders(HttpCarbonMessage outboundResponseMsg, HttpContent httpContent) {
        log.warn("writeOutboundResponseHeaders {}", ILLEGAL_STATE_ERROR);
    }

    @Override
    public void writeOutboundResponseBody(HttpOutboundRespListener outboundRespListener,
                                          HttpCarbonMessage outboundResponseMsg, HttpContent httpContent) {
        log.warn("writeOutboundResponseBody {}", ILLEGAL_STATE_ERROR);
    }

    @Override
    public void handleAbruptChannelClosure(ServerConnectorFuture serverConnectorFuture) {
        // Response is received, yet inbound request is not completed.
        handleIncompleteInboundMessage(inboundRequestMsg, REMOTE_CLIENT_CLOSED_WHILE_READING_INBOUND_REQUEST_BODY);
        cleanupSourceHandler(inboundRequestMsg);
    }

    @Override
    public ChannelFuture handleIdleTimeoutConnectionClosure(ServerConnectorFuture serverConnectorFuture,
                                                            ChannelHandlerContext ctx) {
        // Response is received, yet inbound request is not completed.
        handleIncompleteInboundMessage(inboundRequestMsg, REMOTE_CLIENT_CLOSED_WHILE_READING_INBOUND_REQUEST_BODY);
        cleanupSourceHandler(inboundRequestMsg);
        return null;
    }

    private void cleanupSourceHandler(HttpCarbonMessage inboundRequestMsg) {
        sourceHandler.resetInboundRequestMsg(inboundRequestMsg);
    }
}

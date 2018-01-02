/*
 * Copyright (c) 2015, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and limitations under the License.
 */

package org.wso2.transport.http.netty.sender.channel;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.timeout.IdleStateHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wso2.transport.http.netty.common.Constants;
import org.wso2.transport.http.netty.common.HttpRoute;
import org.wso2.transport.http.netty.common.Util;
import org.wso2.transport.http.netty.contract.HttpResponseFuture;
import org.wso2.transport.http.netty.internal.HTTPTransportContextHolder;
import org.wso2.transport.http.netty.internal.HandlerExecutor;
import org.wso2.transport.http.netty.listener.HTTPTraceLoggingHandler;
import org.wso2.transport.http.netty.listener.SourceHandler;
import org.wso2.transport.http.netty.message.HTTPCarbonMessage;
import org.wso2.transport.http.netty.sender.HTTPClientInitializer;
import org.wso2.transport.http.netty.sender.TargetHandler;
import org.wso2.transport.http.netty.sender.channel.pool.ConnectionManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * A class that encapsulate channel and state.
 */
public class TargetChannel {

    private static final Logger log = LoggerFactory.getLogger(TargetChannel.class);

    private Channel channel;
    private TargetHandler targetHandler;
    private HTTPClientInitializer httpClientInitializer;
    private HttpRoute httpRoute;
    private SourceHandler correlatedSource;
    private ChannelFuture channelFuture;
    private ConnectionManager connectionManager;
    private boolean isRequestWritten = false;
    private String httpVersion;
    private boolean chunkEnabled = true;
    private HandlerExecutor handlerExecutor;

    private List<HttpContent> contentList = new ArrayList<>();
    private int contentLength = 0;

    public TargetChannel(HTTPClientInitializer httpClientInitializer, ChannelFuture channelFuture) {
        this.httpClientInitializer = httpClientInitializer;
        this.channelFuture = channelFuture;
        this.handlerExecutor = HTTPTransportContextHolder.getInstance().getHandlerExecutor();
    }

    public Channel getChannel() {
        return channel;
    }

    public TargetChannel setChannel(Channel channel) {
        this.channel = channel;
        return this;
    }

    public TargetHandler getTargetHandler() {
        return targetHandler;
    }

    public void setTargetHandler(TargetHandler targetHandler) {
        this.targetHandler = targetHandler;
    }

    public HTTPClientInitializer getHTTPClientInitializer() {
        return httpClientInitializer;
    }

    public HttpRoute getHttpRoute() {
        return httpRoute;
    }

    public void setHttpRoute(HttpRoute httpRoute) {
        this.httpRoute = httpRoute;
    }

    public SourceHandler getCorrelatedSource() {
        return correlatedSource;
    }

    public void setCorrelatedSource(SourceHandler correlatedSource) {
        this.correlatedSource = correlatedSource;
    }

    public boolean isRequestWritten() {
        return isRequestWritten;
    }

    public void setRequestWritten(boolean isRequestWritten) {
        this.isRequestWritten = isRequestWritten;
    }

    public void setHttpVersion(String httpVersion) {
        this.httpVersion = httpVersion;
    }

    public void setChunkEnabled(boolean chunkEnabled) {
        this.chunkEnabled = chunkEnabled;
    }

    public void configTargetHandler(HTTPCarbonMessage httpCarbonMessage, HttpResponseFuture httpResponseFuture) {
        this.setTargetHandler(this.getHTTPClientInitializer().getTargetHandler());
        TargetHandler targetHandler = this.getTargetHandler();
        targetHandler.setHttpResponseFuture(httpResponseFuture);
        targetHandler.setIncomingMsg(httpCarbonMessage);
        this.getTargetHandler().setConnectionManager(connectionManager);
        targetHandler.setTargetChannel(this);
    }

    public void setEndPointTimeout(int socketIdleTimeout, boolean followRedirect) {
        this.getChannel().pipeline().addBefore((followRedirect ? Constants.REDIRECT_HANDLER : Constants.TARGET_HANDLER),
                Constants.IDLE_STATE_HANDLER, new IdleStateHandler(socketIdleTimeout, socketIdleTimeout, 0,
                        TimeUnit.MILLISECONDS));
    }

    public void setCorrelationIdForLogging() {
        ChannelPipeline pipeline = this.getChannel().pipeline();
        SourceHandler srcHandler = this.getCorrelatedSource();
        if (srcHandler != null && pipeline.get(Constants.HTTP_TRACE_LOG_HANDLER) != null) {
            HTTPTraceLoggingHandler loggingHandler = (HTTPTraceLoggingHandler) pipeline.get(
                    Constants.HTTP_TRACE_LOG_HANDLER);
            loggingHandler.setCorrelatedSourceId(
                    srcHandler.getInboundChannelContext().channel().id().asShortText());
        }
    }

    public void setConnectionManager(ConnectionManager connectionManager) {
        this.connectionManager = connectionManager;
    }

    public ChannelFuture getChannelFuture() {
        return channelFuture;
    }

    public void writeContent(HTTPCarbonMessage httpOutboundRequest) {
        if (handlerExecutor != null) {
            handlerExecutor.executeAtTargetRequestReceiving(httpOutboundRequest);
        }

        httpOutboundRequest.getHttpContentAsync().setMessageListener(httpContent ->
                this.channel.eventLoop().execute(() -> {
                    try {
                        writeOutboundRequest(httpOutboundRequest, httpContent);
                    } catch (Exception exception) {
                        String errorMsg = "Failed to send the request : "
                                + exception.getMessage().toLowerCase(Locale.ENGLISH);
                        log.error(errorMsg, exception);
                        this.targetHandler.getHttpResponseFuture().notifyHttpListener(exception);
                    }
                }));
    }

    private void writeOutboundRequest(HTTPCarbonMessage httpOutboundRequest, HttpContent httpContent) throws Exception {
        if (Util.isLastHttpContent(httpContent)) {
            if (!this.isRequestWritten) {
                // this means we need to send an empty payload
                // depending on the http verb
                if (Util.isEntityBodyAllowed(getHttpMethod(httpOutboundRequest))) {
                    if (chunkEnabled && Util.isVersionCompatibleForChunking(httpVersion)) {
                        Util.setupChunkedRequest(httpOutboundRequest);
                    } else {
                        contentLength += httpContent.content().readableBytes();
                        Util.setupContentLengthRequest(httpOutboundRequest, contentLength);
                    }
                }
                writeOutboundRequestHeaders(httpOutboundRequest);
            }

            if (!chunkEnabled) {
                for (HttpContent cachedHttpContent : contentList) {
                    this.getChannel().writeAndFlush(cachedHttpContent);
                }
            }
            this.getChannel().writeAndFlush(httpContent);

            httpOutboundRequest.removeHttpContentAsyncFuture();
            contentList.clear();
            contentLength = 0;

            if (handlerExecutor != null) {
                handlerExecutor.executeAtTargetRequestSending(httpOutboundRequest);
            }
        } else {
            if (chunkEnabled  && Util.isVersionCompatibleForChunking(httpVersion)) {
                if (!this.isRequestWritten) {
                    Util.setupChunkedRequest(httpOutboundRequest);
                    writeOutboundRequestHeaders(httpOutboundRequest);
                }
                this.getChannel().writeAndFlush(httpContent);
            } else {
                this.contentList.add(httpContent);
                contentLength += httpContent.content().readableBytes();
            }
        }
    }

    private String getHttpMethod(HTTPCarbonMessage httpOutboundRequest) throws Exception {
        String httpMethod = (String) httpOutboundRequest.getProperty(Constants.HTTP_METHOD);
        if (httpMethod == null) {
            throw new Exception("Couldn't get the HTTP method from the outbound request");
        }
        return httpMethod;
    }

    private void writeOutboundRequestHeaders(HTTPCarbonMessage httpOutboundRequest) {
        this.setHttpVersionProperty(httpOutboundRequest);
        HttpRequest httpRequest = Util.createHttpRequest(httpOutboundRequest);
        this.setRequestWritten(true);
        this.getChannel().write(httpRequest);
    }

    private void setHttpVersionProperty(HTTPCarbonMessage httpOutboundRequest) {
        httpOutboundRequest.setProperty(Constants.HTTP_VERSION, httpVersion);
    }
}

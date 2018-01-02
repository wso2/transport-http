/*
 *  Copyright (c) 2017, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
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

import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.handler.codec.http.HttpResponseStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wso2.transport.http.netty.common.Constants;
import org.wso2.transport.http.netty.common.HttpRoute;
import org.wso2.transport.http.netty.common.ssl.SSLConfig;
import org.wso2.transport.http.netty.config.SenderConfiguration;
import org.wso2.transport.http.netty.contract.ClientConnectorException;
import org.wso2.transport.http.netty.contract.HttpClientConnector;
import org.wso2.transport.http.netty.contract.HttpResponseFuture;
import org.wso2.transport.http.netty.listener.SourceHandler;
import org.wso2.transport.http.netty.message.HTTPCarbonMessage;
import org.wso2.transport.http.netty.sender.channel.TargetChannel;
import org.wso2.transport.http.netty.sender.channel.pool.ConnectionManager;

/**
 * Implementation of the client connector.
 */
public class HttpClientConnectorImpl implements HttpClientConnector {

    private static final Logger log = LoggerFactory.getLogger(HttpClientConnector.class);

    private ConnectionManager connectionManager;
    private SenderConfiguration senderConfiguration;
    private SSLConfig sslConfig;
    private int socketIdleTimeout;
    private boolean followRedirect;
    private String httpVersion;
    private boolean chunkEnabled;
    private boolean keepAlive;

    public HttpClientConnectorImpl(ConnectionManager connectionManager, SenderConfiguration senderConfiguration) {
        this.connectionManager = connectionManager;
        this.senderConfiguration = senderConfiguration;
        initTargetChannelProperties(senderConfiguration);
    }

    @Override
    public HttpResponseFuture connect() {
        return null;
    }

    @Override
    public HttpResponseFuture send(HTTPCarbonMessage httpOutboundRequest) {
        HttpResponseFuture httpResponseFuture = new HttpResponseFutureImpl();

        SourceHandler srcHandler = (SourceHandler) httpOutboundRequest.getProperty(Constants.SRC_HANDLER);
        if (srcHandler == null) {
            if (log.isDebugEnabled()) {
                log.debug(Constants.SRC_HANDLER + " property not found in the message."
                        + " Message is not originated from the HTTP Server connector");
            }
        }

        try {
            final HttpRoute route = getTargetRoute(httpOutboundRequest);
            TargetChannel targetChannel = connectionManager.borrowTargetChannel(route, srcHandler, senderConfiguration);
            targetChannel.getChannelFuture().addListener(new ChannelFutureListener() {
                @Override
                public void operationComplete(ChannelFuture channelFuture) throws Exception {
                    if (isValidateChannel(channelFuture)) {
                        targetChannel.setChannel(channelFuture.channel());
                        targetChannel.configTargetHandler(httpOutboundRequest, httpResponseFuture);
                        targetChannel.setEndPointTimeout(socketIdleTimeout, followRedirect);
                        targetChannel.setCorrelationIdForLogging();
                        targetChannel.setHttpVersion(httpVersion);
                        targetChannel.setChunkEnabled(chunkEnabled);
                        if (followRedirect) {
                            setChannelAttributes(channelFuture.channel(), httpOutboundRequest, httpResponseFuture,
                                                 targetChannel);
                        }
                        if (!keepAlive) {
                            httpOutboundRequest.setHeader(Constants.CONNECTION, Constants.CONNECTION_CLOSE);
                        }
                        targetChannel.writeContent(httpOutboundRequest);
                    } else {
                        notifyErrorState(channelFuture);
                    }
                }

                private boolean isValidateChannel(ChannelFuture channelFuture) throws Exception {
                    if (channelFuture.isDone() && channelFuture.isSuccess()) {
                        if (log.isDebugEnabled()) {
                            log.debug("Created the connection to address: {}",
                                    route.toString() + " " + "Original Channel ID is : " + channelFuture.channel()
                                            .id());
                        }
                        return true;
                    }
                    return false;
                }

                private void notifyErrorState(ChannelFuture channelFuture) {
                    ClientConnectorException cause;

                    if (channelFuture.isDone() && channelFuture.isCancelled()) {
                        cause = new ClientConnectorException("Request Cancelled, " + route.toString(),
                                HttpResponseStatus.BAD_GATEWAY.code());
                    } else if (!channelFuture.isDone() && !channelFuture.isSuccess() &&
                            !channelFuture.isCancelled() && (channelFuture.cause() == null)) {
                        cause = new ClientConnectorException("Connection timeout, " + route.toString(),
                                HttpResponseStatus.BAD_GATEWAY.code());
                    } else {
                        cause = new ClientConnectorException("Connection refused, " + route.toString(),
                                HttpResponseStatus.BAD_GATEWAY.code());
                    }

                    if (channelFuture.cause() != null) {
                        cause.initCause(channelFuture.cause());
                    }

                    httpResponseFuture.notifyHttpListener(cause);
                }
            });
        } catch (Exception failedCause) {
            httpResponseFuture.notifyHttpListener(failedCause);
        }

        return httpResponseFuture;
    }

    @Override
    public boolean close() {
        return false;
    }

    private HttpRoute getTargetRoute(HTTPCarbonMessage httpCarbonMessage) {
        // Fetch Host
        String host;
        Object hostProperty = httpCarbonMessage.getProperty(Constants.HOST);
        if (hostProperty != null && hostProperty instanceof String) {
            host = (String) hostProperty;
        } else {
            host = Constants.LOCALHOST;
            httpCarbonMessage.setProperty(Constants.HOST, Constants.LOCALHOST);
            log.debug("Cannot find property HOST of type string, hence using localhost as the host");
        }

        // Fetch Port
        int port;
        Object intProperty = httpCarbonMessage.getProperty(Constants.PORT);
        if (intProperty != null && intProperty instanceof Integer) {
            port = (int) intProperty;
        } else {
            port = sslConfig != null ? Constants.DEFAULT_HTTPS_PORT : Constants.DEFAULT_HTTP_PORT;
            httpCarbonMessage.setProperty(Constants.PORT, port);
            log.debug("Cannot find property PORT of type integer, hence using " + port);
        }

        return new HttpRoute(host, port);
    }

    /**
     * Set following attributes to original channel when redirect is on.
     *
     * @param channel            Original channel
     * @param httpCarbonRequest  Http request
     * @param httpResponseFuture Response future
     * @param targetChannel      Target channel
     */
    private void setChannelAttributes(Channel channel, HTTPCarbonMessage httpCarbonRequest,
            HttpResponseFuture httpResponseFuture, TargetChannel targetChannel) {
        channel.attr(Constants.ORIGINAL_REQUEST).set(httpCarbonRequest);
        channel.attr(Constants.RESPONSE_FUTURE_OF_ORIGINAL_CHANNEL).set(httpResponseFuture);
        channel.attr(Constants.TARGET_CHANNEL_REFERENCE).set(targetChannel);
        channel.attr(Constants.ORIGINAL_CHANNEL_START_TIME).set(System.currentTimeMillis());
        channel.attr(Constants.ORIGINAL_CHANNEL_TIMEOUT).set(socketIdleTimeout);
    }

    private void initTargetChannelProperties(SenderConfiguration senderConfiguration) {
        this.httpVersion = senderConfiguration.getHttpVersion();
        this.chunkEnabled = senderConfiguration.isChunkEnabled();
        this.followRedirect = senderConfiguration.isFollowRedirect();
        this.socketIdleTimeout = senderConfiguration.getSocketIdleTimeout(Constants.ENDPOINT_TIMEOUT);
        this.sslConfig = senderConfiguration.getSslConfig();
        this.keepAlive = senderConfiguration.isKeepAlive();
    }
}

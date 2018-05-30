/*
 *  Copyright (c) 2015, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
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

package org.wso2.transport.http.netty.listener;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.EventLoop;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.socket.ChannelInputShutdownReadComplete;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpMessage;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpServerUpgradeHandler;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.ssl.SslCloseCompletionEvent;
import io.netty.handler.timeout.IdleStateEvent;
import org.apache.commons.pool.impl.GenericObjectPool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wso2.transport.http.netty.common.Constants;
import org.wso2.transport.http.netty.common.Util;
import org.wso2.transport.http.netty.config.ChunkConfig;
import org.wso2.transport.http.netty.config.KeepAliveConfig;
import org.wso2.transport.http.netty.contract.HttpResponseFuture;
import org.wso2.transport.http.netty.contract.ServerConnectorFuture;
import org.wso2.transport.http.netty.contractimpl.HttpOutboundRespListener;
import org.wso2.transport.http.netty.internal.HTTPTransportContextHolder;
import org.wso2.transport.http.netty.internal.HandlerExecutor;
import org.wso2.transport.http.netty.message.DefaultListener;
import org.wso2.transport.http.netty.message.HTTPCarbonMessage;
import org.wso2.transport.http.netty.message.HttpCarbonRequest;
import org.wso2.transport.http.netty.message.PooledDataStreamerFactory;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.URISyntaxException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A Class responsible for handle  incoming message through netty inbound pipeline.
 */
public class SourceHandler extends ChannelInboundHandlerAdapter {
    private static Logger log = LoggerFactory.getLogger(SourceHandler.class);

    private HTTPCarbonMessage inboundRequestMsg;
    private HandlerExecutor handlerExecutor;
    private Map<String, GenericObjectPool> targetChannelPool;
    private ChunkConfig chunkConfig;
    private KeepAliveConfig keepAliveConfig;

    private final ServerConnectorFuture serverConnectorFuture;
    private HttpResponseFuture outboundRespFuture;

    private String interfaceId;
    private String serverName;
    private boolean idleTimeout;
    private ChannelGroup allChannels;
    protected ChannelHandlerContext ctx;
    private SocketAddress remoteAddress;
    private SourceHandlerErrorHandler sourceHandlerErrorHandler;

    public SourceHandler(ServerConnectorFuture serverConnectorFuture, String interfaceId, ChunkConfig chunkConfig,
                         KeepAliveConfig keepAliveConfig, String serverName, ChannelGroup allChannels) {
        this.serverConnectorFuture = serverConnectorFuture;
        this.interfaceId = interfaceId;
        this.chunkConfig = chunkConfig;
        this.keepAliveConfig = keepAliveConfig;
        this.targetChannelPool = new ConcurrentHashMap<>();
        this.idleTimeout = false;
        this.serverName = serverName;
        this.allChannels = allChannels;
    }

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) throws Exception {
        super.handlerAdded(ctx);
    }

    @Override
    public void channelActive(final ChannelHandlerContext ctx) throws Exception {
        allChannels.add(ctx.channel());
        // Start the server connection Timer
        this.handlerExecutor = HTTPTransportContextHolder.getInstance().getHandlerExecutor();
        if (this.handlerExecutor != null) {
            this.handlerExecutor.executeAtSourceConnectionInitiation(Integer.toString(ctx.hashCode()));
        }
        this.ctx = ctx;
        this.remoteAddress = ctx.channel().remoteAddress();

        sourceHandlerErrorHandler = new
                SourceHandlerErrorHandler(inboundRequestMsg, serverConnectorFuture, outboundRespFuture);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        // Stop the connector timer
        ctx.close();
        if (!idleTimeout) {
            sourceHandlerErrorHandler.handleErrorCloseScenario();
        }
        closeTargetChannels();

        if (handlerExecutor != null) {
            handlerExecutor.executeAtSourceConnectionTermination(Integer.toString(ctx.hashCode()));
            handlerExecutor = null;
        }
    }

    private void closeTargetChannels() {
        targetChannelPool.forEach((hostPortKey, genericObjectPool) -> {
            try {
                targetChannelPool.remove(hostPortKey).close();
            } catch (Exception e) {
                log.error("Couldn't close target channel socket connections", e);
            }
        });
    }

    @SuppressWarnings("unchecked")
    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (msg instanceof HttpRequest) {
            HttpRequest httpRequest = (HttpRequest) msg;
            inboundRequestMsg = setupCarbonMessage(httpRequest, ctx);
            notifyRequestListener(inboundRequestMsg, ctx);

            if (httpRequest.decoderResult().isFailure()) {
                log.warn(httpRequest.decoderResult().cause().getMessage());
            }

            sourceHandlerErrorHandler.setStateHeaderReceived();
        } else {
            if (inboundRequestMsg != null) {
                if (msg instanceof HttpContent) {
                    sourceHandlerErrorHandler.setStateReceivingEntityBody();

                    HttpContent httpContent = (HttpContent) msg;
                    inboundRequestMsg.addHttpContent(httpContent);
                    if (Util.isLastHttpContent(httpContent)) {
                        if (handlerExecutor != null) {
                            handlerExecutor.executeAtSourceRequestSending(inboundRequestMsg);
                        }
                        if (isDiffered(inboundRequestMsg)) {
                            this.serverConnectorFuture.notifyHttpListener(inboundRequestMsg);
                        }
                        outboundRespFuture = inboundRequestMsg.getHttpOutboundRespStatusFuture();
                        inboundRequestMsg = null;

                        sourceHandlerErrorHandler.setStateRequestReceived();
                    }
                }
            } else {
                log.warn("Inconsistent state detected : inboundRequestMsg is null for channel read event");
            }
        }
    }

    private boolean isDiffered(HTTPCarbonMessage sourceReqCmsg) {
        //Http resource stored in the HTTPCarbonMessage means execution waits till payload.
        return sourceReqCmsg.getProperty(Constants.HTTP_RESOURCE) != null;
    }

    private void notifyRequestListener(HTTPCarbonMessage httpRequestMsg, ChannelHandlerContext ctx)
            throws URISyntaxException {

        if (handlerExecutor != null) {
            handlerExecutor.executeAtSourceRequestReceiving(httpRequestMsg);
        }

        if (serverConnectorFuture != null) {
            try {
                ServerConnectorFuture outboundRespFuture = httpRequestMsg.getHttpResponseFuture();
                outboundRespFuture.setHttpConnectorListener(
                        new HttpOutboundRespListener(ctx, httpRequestMsg, chunkConfig, keepAliveConfig, serverName));
                this.serverConnectorFuture.notifyHttpListener(httpRequestMsg);
            } catch (Exception e) {
                log.error("Error while notifying listeners", e);
            }
        } else {
            log.error("Cannot find registered listener to forward the message");
        }
    }

    private HTTPCarbonMessage setupCarbonMessage(HttpMessage httpMessage, ChannelHandlerContext ctx)
            throws URISyntaxException {

        if (handlerExecutor != null) {
            handlerExecutor.executeAtSourceRequestReceiving(inboundRequestMsg);
        }

        inboundRequestMsg = new HttpCarbonRequest((HttpRequest) httpMessage, new DefaultListener(ctx));
        inboundRequestMsg.setProperty(Constants.POOLED_BYTE_BUFFER_FACTORY, new PooledDataStreamerFactory(ctx.alloc()));

        HttpRequest httpRequest = (HttpRequest) httpMessage;
        inboundRequestMsg.setProperty(Constants.CHNL_HNDLR_CTX, this.ctx);
        inboundRequestMsg.setProperty(Constants.SRC_HANDLER, this);
        HttpVersion protocolVersion = httpRequest.protocolVersion();
        inboundRequestMsg.setProperty(Constants.HTTP_VERSION,
                protocolVersion.majorVersion() + "." + protocolVersion.minorVersion());
        inboundRequestMsg.setProperty(Constants.HTTP_METHOD, httpRequest.method().name());
        InetSocketAddress localAddress = null;

        //This check was added because in case of netty embedded channel, this could be of type 'EmbeddedSocketAddress'.
        if (ctx.channel().localAddress() instanceof InetSocketAddress) {
            localAddress = (InetSocketAddress) ctx.channel().localAddress();
        }
        inboundRequestMsg.setProperty(Constants.LISTENER_PORT, localAddress != null ? localAddress.getPort() : null);
        inboundRequestMsg.setProperty(Constants.LISTENER_INTERFACE_ID, interfaceId);
        inboundRequestMsg.setProperty(Constants.PROTOCOL, Constants.HTTP_SCHEME);

        boolean isSecuredConnection = false;
        if (ctx.channel().pipeline().get(Constants.SSL_HANDLER) != null) {
            isSecuredConnection = true;
        }
        inboundRequestMsg.setProperty(Constants.IS_SECURED_CONNECTION, isSecuredConnection);

        inboundRequestMsg.setProperty(Constants.LOCAL_ADDRESS, ctx.channel().localAddress());
        inboundRequestMsg.setProperty(Constants.REMOTE_ADDRESS, remoteAddress);
        inboundRequestMsg.setProperty(Constants.REQUEST_URL, httpRequest.uri());
        inboundRequestMsg.setProperty(Constants.TO, httpRequest.uri());
        //Added protocol name as a string

        return inboundRequestMsg;
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        if (ctx != null && ctx.channel().isActive()) {
            ctx.writeAndFlush(Unpooled.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE);
        }
        sourceHandlerErrorHandler.exceptionCaught(cause);
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        if (evt instanceof IdleStateEvent) {
            this.idleTimeout = true;
            this.channelInactive(ctx);
            this.sourceHandlerErrorHandler.handleIdleErrorScenario();

            log.debug("Idle timeout has reached hence closing the connection {}", ctx.channel().id().asShortText());
        } else if (evt instanceof HttpServerUpgradeHandler.UpgradeEvent) {
            log.debug("Server upgrade event received");
        } else if (evt instanceof SslCloseCompletionEvent) {
            log.debug("SSL close completion event received");
        } else if (evt instanceof ChannelInputShutdownReadComplete) {
            // When you try to read from a channel which has already been closed by the peer,
            // 'java.io.IOException: Connection reset by peer' is thrown and it is a harmless exception.
            // We can ignore this most of the time. see 'https://github.com/netty/netty/issues/2332'.
            // As per the code, when an IOException is thrown when reading from a channel, it closes the channel.
            // When closing the channel, if it is already closed it will trigger this event. So we can ignore this.
            log.debug("Input side of the connection is already shutdown");
        } else {
            log.warn("Unexpected user event {} triggered", evt.toString());
        }
    }

    public EventLoop getEventLoop() {
        return this.ctx.channel().eventLoop();
    }

    public Map<String, GenericObjectPool> getTargetChannelPool() {
        return targetChannelPool;
    }

    public ChannelHandlerContext getInboundChannelContext() {
        return ctx;
    }
}

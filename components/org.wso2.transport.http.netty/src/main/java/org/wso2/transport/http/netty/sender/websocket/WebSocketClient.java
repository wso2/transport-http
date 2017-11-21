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

package org.wso2.transport.http.netty.sender.websocket;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.websocketx.WebSocketClientHandshaker;
import io.netty.handler.codec.http.websocketx.WebSocketClientHandshakerFactory;
import io.netty.handler.codec.http.websocketx.WebSocketVersion;
import io.netty.handler.codec.http.websocketx.extensions.compression.WebSocketClientCompressionHandler;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.handler.timeout.IdleStateHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wso2.transport.http.netty.contract.websocket.HandshakeFuture;
import org.wso2.transport.http.netty.contract.websocket.WebSocketConnectorListener;
import org.wso2.transport.http.netty.contractimpl.websocket.HandshakeFutureImpl;
import org.wso2.transport.http.netty.internal.websocket.WebSocketSessionImpl;

import java.net.URI;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLException;

/**
 * WebSocket client for sending and receiving messages in WebSocket as a client.
 */
public class WebSocketClient {

    private static final Logger log = LoggerFactory.getLogger(WebSocketClient.class);

    private WebSocketTargetHandler handler;
    private EventLoopGroup group;

    private final String url;
    private final String subProtocols;
    private final String target;
    private final int idleTimeout;
    private final Map<String, String> headers;
    private final WebSocketConnectorListener connectorListener;

    /**
     *
     * @param url url of the remote endpoint.
     * @param target target for the inbound messages from the remote server.
     * @param subProtocols the negotiable sub-protocol if server is asking for it.
     * @param idleTimeout Idle timeout of the connection.
     * @param headers any specific headers which need to send to the server.
     * @param connectorListener connector listener to notify incoming messages.
     */
    public WebSocketClient(String url, String target, String subProtocols, int idleTimeout,
                           Map<String, String> headers, WebSocketConnectorListener connectorListener) {
        this.url = url;
        this.target = target;
        this.subProtocols = subProtocols;
        this.idleTimeout = idleTimeout;
        this.headers = headers;
        this.connectorListener = connectorListener;
    }

    /**
     * Handle the handshake with the server.
     *
     * @return handshake future for connection.
     */
    public HandshakeFuture handshake() {
        HandshakeFutureImpl handshakeFuture = new HandshakeFutureImpl();
        try {
            URI uri = new URI(url);
            String scheme = uri.getScheme() == null ? "ws" : uri.getScheme();
            final String host = uri.getHost() == null ? "127.0.0.1" : uri.getHost();
            final int port;
            if (uri.getPort() == -1) {
                if ("ws".equalsIgnoreCase(scheme)) {
                    port = 80;
                } else if ("wss".equalsIgnoreCase(scheme)) {
                    port = 443;
                } else {
                    port = -1;
                }
            } else {
                port = uri.getPort();
            }

            if (!"ws".equalsIgnoreCase(scheme) && !"wss".equalsIgnoreCase(scheme)) {
                log.error("Only WS(S) is supported.");
                throw new SSLException("");
            }

            final boolean ssl = "wss".equalsIgnoreCase(scheme);
            final SslContext sslCtx;
            if (ssl) {
                sslCtx = SslContextBuilder.forClient()
                        .trustManager(InsecureTrustManagerFactory.INSTANCE).build();
            } else {
                sslCtx = null;
            }

            group = new NioEventLoopGroup();
            HttpHeaders httpHeaders = new DefaultHttpHeaders();

            // Adding custom headers to the handshake request.

            if (headers != null) {
                headers.entrySet().forEach(
                        entry -> httpHeaders.add(entry.getKey(), entry.getValue())
                );
            }

            WebSocketClientHandshaker websocketHandshaker = WebSocketClientHandshakerFactory.newHandshaker(
                    uri, WebSocketVersion.V13, subProtocols, true, httpHeaders);
            handler = new WebSocketTargetHandler(websocketHandshaker, ssl, url, target, connectorListener);


            Bootstrap b = new Bootstrap();
            b.group(group)
                    .channel(NioSocketChannel.class)
                    .handler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) {
                            ChannelPipeline p = ch.pipeline();
                            if (sslCtx != null) {
                                p.addLast(sslCtx.newHandler(ch.alloc(), host, port));
                            }
                            p.addLast(new HttpClientCodec());
                            p.addLast(new HttpObjectAggregator(8192));
                            p.addLast(WebSocketClientCompressionHandler.INSTANCE);
                            if (idleTimeout > 0) {
                                p.addLast(new IdleStateHandler(idleTimeout, idleTimeout,
                                                               idleTimeout, TimeUnit.MILLISECONDS));
                            }
                            p.addLast(handler);
                        }
                    });

            b.connect(uri.getHost(), port).sync();
            ChannelFuture future = handler.handshakeFuture().addListener(new ChannelFutureListener() {
                @Override
                public void operationComplete(ChannelFuture future) {
                    Throwable cause = future.cause();
                    if (future.isSuccess() && cause == null) {
                        WebSocketSessionImpl session = (WebSocketSessionImpl) handler.getChannelSession();
                        String actualSubProtocol = websocketHandshaker.actualSubprotocol();
                        handler.setActualSubProtocol(actualSubProtocol);
                        session.setNegotiatedSubProtocol(actualSubProtocol);
                        session.setIsOpen(true);
                        handshakeFuture.notifySuccess(session);
                    } else {
                        handshakeFuture.notifyError(cause);
                    }
                }
            }).sync();
            handshakeFuture.setChannelFuture(future);
        } catch (Throwable t) {
            handshakeFuture.notifyError(t);
        }

        return handshakeFuture;
    }
}

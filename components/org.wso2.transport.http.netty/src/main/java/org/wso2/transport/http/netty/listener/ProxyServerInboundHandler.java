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

package org.wso2.transport.http.netty.listener;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelOption;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.channel.oio.OioEventLoopGroup;
import io.netty.channel.socket.oio.OioSocketChannel;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpRequestEncoder;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wso2.transport.http.netty.common.Constants;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.UnknownHostException;

import static org.wso2.transport.http.netty.common.Constants.DEFAULT_CHANNEL_PIPELINE;
import static org.wso2.transport.http.netty.common.Constants.PROXY_SERVER_INBOUND_HANDLER;

/**
 * Handles the requests coming from the client. If it is a CONNECT request ssl tunnel is created.
 */
public class ProxyServerInboundHandler extends ChannelInboundHandlerAdapter {

    private Channel outboundChannel = null;
    private String proxyPseudonym;
    private static final Logger log = LoggerFactory.getLogger(ProxyServerInboundHandler.class);

    ProxyServerInboundHandler(String proxyPseudonym) {
        this.proxyPseudonym = proxyPseudonym;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        final Channel inboundChannel = ctx.channel();
        if (msg instanceof HttpRequest) {
            handleHttpRequest(ctx, msg, inboundChannel);
        } else {
            if (msg instanceof HttpContent) {
                outboundChannel.writeAndFlush(((HttpContent) msg).content())
                        .addListener((ChannelFutureListener) future -> {
                            if (future.isSuccess()) {
                                log.debug("Wrote the content to the backend via proxy.");
                            }
                            if (!future.isSuccess()) {
                                log.error("Could not write the content to the backend via proxy.");
                                future.channel().close();
                            }
                        });
            } else {
                if (outboundChannel.isActive()) {
                    // This means, a CONNECT request has come prior to this.
                    outboundChannel.writeAndFlush(msg).addListener((ChannelFutureListener) future -> {
                        if (!future.isSuccess()) {
                            future.channel().close();
                        }
                    });
                }
            }
        }
    }

    /**
     * This is for handling http and https requests.
     *
     * @param ctx channel context
     * @param msg message coming from the inbound channel
     * @param inboundChannel channel between client and proxy server
     * @throws MalformedURLException If an error occurs while generating the url
     * @throws InterruptedException If an error occurs while connecting to the backend server
     * @throws UnknownHostException If an error occurs while retrieving headers from the inbound request
     */
    private void handleHttpRequest(ChannelHandlerContext ctx, Object msg, Channel inboundChannel)
            throws MalformedURLException, InterruptedException, UnknownHostException {
        log.debug("Processing http request via ProxyServerInboundHandler.");
        HttpRequest inboundRequest = (HttpRequest) msg;
        InetSocketAddress reqSocket = resolveInetSocketAddress(inboundRequest);
        String host = reqSocket.getHostName();
        int port = reqSocket.getPort();

        OioEventLoopGroup group = new OioEventLoopGroup(1);
        Bootstrap clientBootstrap = new Bootstrap();
        clientBootstrap.group(group).channel(OioSocketChannel.class)
                .option(ChannelOption.SO_KEEPALIVE, true)
                .remoteAddress(new InetSocketAddress(host, port))
                .handler(new ProxyServerOutboundHandler(inboundChannel));
        ChannelFuture channelFuture = clientBootstrap.connect(host, port).sync();
        outboundChannel = channelFuture.channel();

        if (inboundRequest.method().equals(HttpMethod.CONNECT)) {
            // Once the connection is successful, send 200 OK to client.
            if (outboundChannel.isActive()) {
                sendOk(inboundChannel, inboundRequest.protocolVersion());
                removeOtherHandlers(ctx);
                ctx.channel().pipeline().fireChannelActive();
            }
        } else {
            // This else block is for handling non https requests. Once the connection is successful
            // forward the incoming messages to backend.
            inboundRequest.setUri(new URL(inboundRequest.uri()).getPath());
            inboundRequest.headers().set(HttpHeaderNames.VIA, getViaHeader(inboundRequest));
            inboundRequest.headers().remove(HttpHeaderNames.PROXY_AUTHORIZATION);
            ByteBuf encodedRequest = getByteBuf(msg);
            outboundChannel.writeAndFlush(encodedRequest).addListener((ChannelFutureListener) chFuture -> {
                if (!chFuture.isSuccess()) {
                    log.error("Unable to write to the backend.");
                    chFuture.channel().close();
                }
                if (chFuture.isSuccess()) {
                    removeOtherHandlers(ctx);
                    ctx.channel().pipeline().fireChannelActive();
                    log.debug("Successfully wrote http headers to the backend via proxy");
                }
            });
        }
    }

    /**
     * This function is for generating Via header.
     *
     * @param inboundRequest http inbound request
     * @return via header
     * @throws UnknownHostException If an error occurs while getting the host name
     */
    private String getViaHeader(HttpRequest inboundRequest) throws UnknownHostException {
        String viaHeader;
        String receivedBy;
        viaHeader = inboundRequest.headers().get(HttpHeaderNames.VIA);
        if (proxyPseudonym != null) {
            receivedBy = proxyPseudonym;
        } else {
            receivedBy = InetAddress.getLocalHost().getHostName();
        }
        String httpVersion =
                inboundRequest.protocolVersion().majorVersion() + "." + inboundRequest.protocolVersion()
                        .minorVersion();
        if (viaHeader == null) {
            viaHeader = httpVersion + " " + receivedBy;
        } else {
            viaHeader = viaHeader.concat(",").concat(httpVersion + " " + receivedBy);
        }
        return viaHeader;
    }

    private InetSocketAddress resolveInetSocketAddress(HttpRequest inboundRequest) throws MalformedURLException {
        InetSocketAddress address;
        if (HttpMethod.CONNECT.equals(inboundRequest.method())) {
            String parts[] = inboundRequest.uri().split(Constants.COLON);
            address = new InetSocketAddress(parts[0], Integer.parseInt(parts[1]));
        } else {
            URL url = new URL(inboundRequest.uri());
            address = new InetSocketAddress(url.getHost(), url.getPort());
        }
        return address;
    }

    private ByteBuf getByteBuf(Object msg) {
        EmbeddedChannel channel = new EmbeddedChannel(new HttpRequestEncoder());
        channel.writeOutbound(msg);
        return channel.readOutbound();
    }

    /**
     * Removing other handlers except proxyServerInbound handler and default channel pipeline handler.
     *
     * @param ctx channel context
     */
    private void removeOtherHandlers(ChannelHandlerContext ctx) {
        for (String handler : ctx.channel().pipeline().names()) {
            if (!(PROXY_SERVER_INBOUND_HANDLER.equals(handler) || handler
                    .contains(DEFAULT_CHANNEL_PIPELINE))) {
                ctx.channel().pipeline().remove(handler);
            }
        }
    }

    /**
     * Send 200 OK message to the client once the tcp connection is successfully established
     * between proxy server and backend server.
     *
     * @param channel channel
     * @param httpVersion http version
     */
    private static void sendOk(Channel channel, HttpVersion httpVersion) {
        FullHttpResponse response = new DefaultFullHttpResponse(httpVersion, HttpResponseStatus.OK);
        channel.writeAndFlush(response);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        if (outboundChannel != null) {
            closeOnFlush(outboundChannel);
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        closeOnFlush(ctx.channel());
    }

    /**
     * Closes the specified channel after all queued write requests are flushed.
     */
    static void closeOnFlush(Channel ch) {
        if (ch.isActive()) {
            ch.writeAndFlush(Unpooled.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE);
        }
    }

}

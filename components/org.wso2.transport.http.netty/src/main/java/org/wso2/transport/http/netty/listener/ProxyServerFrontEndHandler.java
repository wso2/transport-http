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
import java.net.URL;

/**
 * Handles the requests coming from the client. If it is a CONNECT request ssl tunnel is created.
 */
public class ProxyServerFrontEndHandler extends ChannelInboundHandlerAdapter {

    private Channel outboundChannel = null;
    private static final Logger log = LoggerFactory.getLogger(ProxyServerFrontEndHandler.class);

    ProxyServerFrontEndHandler() {
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        final Channel inboundChannel = ctx.channel();
        if (msg instanceof HttpRequest) {
            HttpRequest inboundRequest = (HttpRequest) msg;
            String host = null;
            int port = 0;
            if (inboundRequest.method().equals(HttpMethod.CONNECT)) {
                String parts[] = inboundRequest.uri().split(Constants.COLON);
                host = parts[0];
                port = Integer.parseInt(parts[1]);
            } else {
                URL url = new URL(inboundRequest.uri());
                host = url.getHost();
                port = url.getPort();
            }
            OioEventLoopGroup group = new OioEventLoopGroup(1);
            Bootstrap clientBootstrap = new Bootstrap();
            clientBootstrap.group(group).channel(OioSocketChannel.class)
                    .remoteAddress(new InetSocketAddress(host, port))
                    .handler(new ProxyServerBackendHandler(inboundChannel));
            ChannelFuture channelFuture = clientBootstrap.connect(host, port).sync();

            outboundChannel = channelFuture.channel();
            if (inboundRequest.method().equals(HttpMethod.CONNECT)) {
                // Once the connection is successful, send 200 OK to client.
                if (outboundChannel.isActive()) {
                    send200Ok(inboundChannel, inboundRequest.protocolVersion());
                    removeHandlers(ctx);
                    ctx.channel().pipeline().fireChannelActive();
                }
            } else {
                // This else block is for handling non https requests. Once the connection is successful
                // forward the incoming messages to backend.
                ((HttpRequest) msg).setUri(new URL(inboundRequest.uri()).getPath());
                ((HttpRequest) msg).headers().set(HttpHeaderNames.VIA, InetAddress.getLocalHost().getHostName());
                EmbeddedChannel channel = new EmbeddedChannel(new HttpRequestEncoder());
                channel.writeOutbound(msg);
                ByteBuf encodedRequest = channel.readOutbound();
                outboundChannel.writeAndFlush(encodedRequest).addListener((ChannelFutureListener) chFuture -> {
                    if (!chFuture.isSuccess()) {
                        chFuture.channel().close();
                    }
                    if (chFuture.isSuccess()) {
                        ctx.channel().pipeline().remove(Constants.HTTP_ENCODER);
                        ctx.channel().pipeline().fireChannelActive();
                        log.info("write and flush of http headers became successful");
                    }
                });
            }
        } else {
            if (msg instanceof HttpContent) {
                log.info("Content received.");
                outboundChannel.writeAndFlush(((HttpContent) msg).content())
                        .addListener((ChannelFutureListener) future -> {
                            if (future.isSuccess()) {
                                log.info("Wrote the content to the backend.");
                            }
                            if (!future.isSuccess()) {
                                log.info("Could not write the content to the backend.");
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

    private void removeHandlers(ChannelHandlerContext ctx) {
        ctx.channel().pipeline().remove(Constants.HTTP_DECODER);
        ctx.channel().pipeline().remove(Constants.HTTP_ENCODER);
        ctx.channel().pipeline().remove(Constants.URI_LENGTH_VALIDATOR);
    }

    private static void send200Ok(Channel channel, HttpVersion httpVersion) {
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

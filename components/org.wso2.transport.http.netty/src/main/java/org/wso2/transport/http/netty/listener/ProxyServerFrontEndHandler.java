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
import io.netty.channel.EventLoopGroup;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpRequestEncoder;
import org.wso2.transport.http.netty.common.Constants;
import org.wso2.transport.http.netty.common.Util;

import java.net.InetSocketAddress;

/**
 * Handles the requests coming from the client. If it is a CONNECT request ssl tunnel is created. Then forward the
 */
public class ProxyServerFrontEndHandler extends ChannelInboundHandlerAdapter {
    private HttpRequest inboundRequest;
    private Channel outboundChannel;

    ProxyServerFrontEndHandler() {
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        final Channel inboundChannel = ctx.channel();
        if (ctx.channel().isActive()) {
            if (msg instanceof HttpRequest) {
                inboundRequest = (HttpRequest) msg;
                String uri = inboundRequest.headers().entries().get(0).getValue();
                String[] parts = uri.split(Constants.COLON);
                String host = parts[0];
                String port = parts[1];

                EventLoopGroup group = ctx.channel().eventLoop();
                Bootstrap clientBootstrap = new Bootstrap();
                clientBootstrap.group(group).channel(NioSocketChannel.class)
                        .remoteAddress(new InetSocketAddress(host, Integer.parseInt(port)))
                        .handler(new ProxyServerBackendHandler(inboundChannel));
                ChannelFuture channelFuture = clientBootstrap.connect(host, Integer.parseInt(port));
                outboundChannel = channelFuture.channel();

                channelFuture.addListener((ChannelFutureListener) future -> {
                    if (future.isSuccess()) {
                        if (inboundRequest.method().equals(HttpMethod.CONNECT)) {
                            // connection complete start to read first data
                            Util.send200Ok(ctx, inboundRequest.protocolVersion());
                            ctx.channel().pipeline().remove(Constants.HTTP_ENCODER);
                            ctx.channel().pipeline().remove(Constants.HTTP_DECODER);
                            ctx.channel().pipeline().remove(Constants.HTTP_COMPRESSOR);
                            ctx.channel().pipeline().remove(Constants.HTTP_CHUNK_WRITER);
                            ctx.channel().pipeline().remove(Constants.URI_LENGTH_VALIDATOR);
                            ctx.channel().pipeline().remove(Constants.WEBSOCKET_SERVER_HANDSHAKE_HANDLER);
                            ctx.channel().pipeline().remove(Constants.HTTP_SOURCE_HANDLER);
                            ctx.channel().pipeline().fireChannelActive();
                        } else {
                            ctx.channel().pipeline().remove(Constants.HTTP_ENCODER);
                            ctx.channel().pipeline().fireChannelActive();
                            EmbeddedChannel ch = new EmbeddedChannel(new HttpRequestEncoder());
                            ch.writeOutbound(msg);
                            ByteBuf encoded = ch.readOutbound();
                            outboundChannel.writeAndFlush(encoded).addListener((ChannelFutureListener) chFuture -> {
                                if (!future.isSuccess()) {
                                    future.channel().close();
                                }
                            });
                        }
                    } else {
                        // Close the connection if the connection attempt has failed.
                        inboundChannel.close();
                    }
                });
                ctx.channel().read();
            } else {
                if (outboundChannel.isActive()) {
                    outboundChannel.writeAndFlush(msg).addListener((ChannelFutureListener) future -> {
                        if (!future.isSuccess()) {
                            future.channel().close();
                        }
                    });
                }
            }
        }
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

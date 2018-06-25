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

import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sun.misc.BASE64Encoder;

import java.nio.charset.Charset;

import static io.netty.handler.codec.http.HttpHeaders.Names.PROXY_AUTHORIZATION;
import static org.wso2.transport.http.netty.common.Constants.COLON;

/**
 * A handler for authorizing proxy server.
 */
public class ProxyAuthorizationHandler extends ChannelInboundHandlerAdapter {

    private static final Logger log = LoggerFactory.getLogger(ProxyAuthorizationHandler.class);
    private String authString;
    ProxyAuthorizationHandler(String userName, String password) {
        this.authString = new BASE64Encoder().encode((userName + COLON + password).getBytes(Charset.forName("UTF-8")));
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        log.debug("Processing request via ProxyAuthorizationHandler.");
        if (msg instanceof HttpRequest) {
            // Once the http request is received by this handler, remove this as we don't need to validate http
            // content coming after that.
            ctx.channel().pipeline().remove(this);
            String authHeader = ((HttpRequest) msg).headers().get(PROXY_AUTHORIZATION);
            if (authHeader == null) {
                // 401
                send407AuthenticationRequired(ctx, ((HttpRequest) msg).protocolVersion());
            } else {
                String[] authParts = authHeader.split("\\s+");
                if (authParts.length > 0) {
                    String authInfo = authParts[1];
                    if (authInfo.equals(authString)) {
                        ctx.fireChannelRead(msg);
                        // Authorization success.
                        log.debug("Authentication success.");
                        return;
                    }
                }
                // Authorization failure.
                log.debug("Authentication failure.");
                send401Unauthorized(ctx, ((HttpRequest) msg).protocolVersion());
            }
        }
    }

    /**
     * If the user hasn't provided authorization information send a 407 status code.
     * @param ctx channel context
     * @param httpVersion http version of the request
     */
    private static void send407AuthenticationRequired(ChannelHandlerContext ctx, HttpVersion httpVersion) {
        FullHttpResponse response = new DefaultFullHttpResponse(httpVersion,
                HttpResponseStatus.PROXY_AUTHENTICATION_REQUIRED);
        ChannelFuture outBoundResp = ctx.writeAndFlush(response);
        outBoundResp.addListener((ChannelFutureListener) channelFuture -> log
                .error("Failed to connect to proxy server :" + HttpResponseStatus.PROXY_AUTHENTICATION_REQUIRED
                        .reasonPhrase()));
        ctx.channel().close();
    }

    /**
     * If user has given wrong username and password.
     * @param ctx channel context
     * @param httpVersion http version of the request
     */
    private static void send401Unauthorized(ChannelHandlerContext ctx, HttpVersion httpVersion) {
        FullHttpResponse response = new DefaultFullHttpResponse(httpVersion, HttpResponseStatus.UNAUTHORIZED);
        ChannelFuture outBoundResp = ctx.writeAndFlush(response);
        outBoundResp.addListener((ChannelFutureListener) channelFuture -> log
                .error("Failed to connect to proxy server :" + HttpResponseStatus.UNAUTHORIZED.reasonPhrase()));
        ctx.channel().close();
    }
}

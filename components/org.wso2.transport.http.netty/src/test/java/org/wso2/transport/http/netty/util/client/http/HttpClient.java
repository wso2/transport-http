package org.wso2.transport.http.netty.util.client.http;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.UnsupportedEncodingException;
import java.net.InetSocketAddress;
import java.net.URI;

/**
 * Test HTTP netty client which close the connection once the request is delivered.
 */
public class HttpClient {

    private static final Logger logger = LoggerFactory.getLogger(HttpClient.class);

    public void createAndSendRequest(URI baseURI, String path, HttpMethod method, String stringContent) {
        String host = baseURI.getHost();
        int port = baseURI.getPort();
        String uri = path;
        HttpMethod method1 = method;
        String stringContent1 = stringContent;

        EventLoopGroup group = new NioEventLoopGroup();
        ByteBuf content = null;
        try {
            content = Unpooled.wrappedBuffer(stringContent.getBytes("UTF-8"));
        } catch (UnsupportedEncodingException e) {
            logger.error("Error occurred during string content processing: ", e);
        }
        try {
            Bootstrap bootstrap = new Bootstrap();
            bootstrap.group(group)
                    .channel(NioSocketChannel.class)
                    .remoteAddress(new InetSocketAddress(host, port))
                    .handler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        public void initChannel(SocketChannel ch) throws Exception {
                            ch.pipeline().addLast(new HttpClientCodec());
                            ch.pipeline().addLast(new HttpClientHandler());
                        }
                    });
            Channel ch = bootstrap.connect(host, port).sync().channel();

            HttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, method, uri, content);
            request.headers().set(HttpHeaderNames.HOST, host);

            ChannelFuture future = ch.writeAndFlush(request);
            future.addListener(new GenericFutureListener<Future<? super Void>>() {
                @Override
                public void operationComplete(Future<? super Void> future) throws Exception {
                    ch.close();
                }
            });
        } catch (InterruptedException e) {
            logger.error("Error occurred during message processing: ", e);
        } finally {
            group.shutdownGracefully();
        }
    }
}

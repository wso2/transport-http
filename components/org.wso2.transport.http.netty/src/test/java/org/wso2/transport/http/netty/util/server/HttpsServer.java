/*
 * Copyright (c) 2015, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.wso2.transport.http.netty.util.server;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wso2.transport.http.netty.util.TestUtil;
import org.wso2.transport.http.netty.util.server.initializers.HTTPServerInitializer;

import java.io.FileInputStream;
import java.net.InetSocketAddress;
import java.security.KeyStore;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;

/**
 * A Simple HTTPS Server
 */
public class HttpsServer implements TestServer {

    private static final Logger logger = LoggerFactory.getLogger(HttpsServer.class);

    private int port = TestUtil.TEST_HTTPS_SERVER_PORT;
    private int bossGroupSize = Runtime.getRuntime().availableProcessors();
    private int workerGroupSize = Runtime.getRuntime().availableProcessors() * 2;
    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private SSLContext sslContext;
    private ChannelInitializer channelInitializer;

    String ksName = "src/test/resources/simple-test-config/wso2carbon.jks";
    char ksPass[] = "wso2carbon".toCharArray();
    char ctPass[] = "wso2carbon".toCharArray();

    public HttpsServer(int port, ChannelInitializer channelInitializer) {
        this.port = port;
        this.channelInitializer = channelInitializer;
    }

    /**
     * Start the HttpsServer
     */
    public void start() {
        bossGroup = new NioEventLoopGroup(this.bossGroupSize);
        workerGroup = new NioEventLoopGroup(this.workerGroupSize);
        try {
            ServerBootstrap b = new ServerBootstrap();
            b.option(ChannelOption.SO_BACKLOG, 100);
            b.childOption(ChannelOption.TCP_NODELAY, true);
            b.option(ChannelOption.SO_KEEPALIVE, true);
            b.option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 15000);

            KeyStore ks = KeyStore.getInstance("JKS");
            ks.load(new FileInputStream(ksName), ksPass);
            KeyManagerFactory kmf = KeyManagerFactory.getInstance("SunX509");
            kmf.init(ks, ctPass);

            sslContext = SSLContext.getInstance("TLS");
            sslContext.init(kmf.getKeyManagers(), null, null);
            ((HTTPServerInitializer) channelInitializer).setSslContext(sslContext);

            b.group(bossGroup, workerGroup).channel(NioServerSocketChannel.class).childHandler(channelInitializer);
            ChannelFuture ch = b.bind(new InetSocketAddress(TestUtil.TEST_HOST, port));
            ch.sync();
            logger.info("HttpServer started on port " + port);
        } catch (Exception e) {
            logger.error("HTTP Server cannot start on port " + port);
        }
    }

    /**
     * Shutdown the HttpsServer
     */
    public void shutdown() throws InterruptedException {
        bossGroup.shutdownGracefully().sync();
        workerGroup.shutdownGracefully().sync();
        logger.info("HttpsServer shutdown ");
    }

    public void setMessage(String message, String contentType) {
    }
}

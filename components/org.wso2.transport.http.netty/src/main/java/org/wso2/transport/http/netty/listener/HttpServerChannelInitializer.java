/*
 *  Copyright (c) 2017 WSO2 Inc. (http://wso2.com) All Rights Reserved.
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

package org.wso2.transport.http.netty.listener;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.http.HttpRequestDecoder;
import io.netty.handler.codec.http.HttpResponseEncoder;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.HttpServerUpgradeHandler;
import io.netty.handler.codec.http2.Http2CodecUtil;
import io.netty.handler.codec.http2.Http2ServerUpgradeCodec;
import io.netty.handler.ssl.ApplicationProtocolNames;
import io.netty.handler.ssl.ApplicationProtocolNegotiationHandler;
import io.netty.handler.ssl.OpenSsl;
import io.netty.handler.ssl.ReferenceCountedOpenSslContext;
import io.netty.handler.ssl.ReferenceCountedOpenSslEngine;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslHandler;
import io.netty.handler.stream.ChunkedWriteHandler;
import io.netty.handler.timeout.IdleStateHandler;
import io.netty.util.AsciiString;
import org.bouncycastle.cert.ocsp.OCSPResp;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wso2.transport.http.netty.common.Constants;
import org.wso2.transport.http.netty.common.certificatevalidation.CertificateVerificationException;
import org.wso2.transport.http.netty.common.ssl.SSLConfig;
import org.wso2.transport.http.netty.common.ssl.SSLHandlerFactory;
import org.wso2.transport.http.netty.config.ChunkConfig;
import org.wso2.transport.http.netty.config.KeepAliveConfig;
import org.wso2.transport.http.netty.config.RequestSizeValidationConfig;
import org.wso2.transport.http.netty.contract.ServerConnectorFuture;
import org.wso2.transport.http.netty.listener.http2.Http2SourceConnectionHandlerBuilder;
import org.wso2.transport.http.netty.listener.http2.Http2ToHttpFallbackHandler;
import org.wso2.transport.http.netty.listener.http2.Http2WithPriorKnowledgeHandler;
import org.wso2.transport.http.netty.sender.CertificateValidationHandler;

import java.io.IOException;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.UnrecoverableEntryException;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SSLEngine;

import static org.wso2.transport.http.netty.common.Constants.ACCESS_LOG;
import static org.wso2.transport.http.netty.common.Constants.HTTP_ACCESS_LOG_HANDLER;
import static org.wso2.transport.http.netty.common.Constants.HTTP_TRACE_LOG_HANDLER;
import static org.wso2.transport.http.netty.common.Constants.TRACE_LOG_DOWNSTREAM;

/**
 * A class that responsible for build server side channels.
 */
public class HttpServerChannelInitializer extends ChannelInitializer<SocketChannel> {

    private static final Logger log = LoggerFactory.getLogger(HttpServerChannelInitializer.class);

    private long socketIdleTimeout;
    private boolean httpTraceLogEnabled;
    private boolean httpAccessLogEnabled;
    private ChunkConfig chunkConfig;
    private KeepAliveConfig keepAliveConfig;
    private String interfaceId;
    private String serverName;
    private SSLConfig sslConfig;
    private ServerConnectorFuture serverConnectorFuture;
    private RequestSizeValidationConfig reqSizeValidationConfig;
    private boolean http2Enabled = false;
    private boolean validateCertEnabled;
    private int cacheDelay;
    private int cacheSize;
    private ChannelGroup allChannels;
    private boolean ocspStaplingEnabled = false;
    private boolean proxy = false;
    private String proxyUserName = null;
    private String proxyPassword = null;
    private String proxyPseudonym = null;

    @Override
    public void initChannel(SocketChannel ch) throws Exception {
        if (log.isDebugEnabled()) {
            log.debug("Initializing source channel pipeline");
        }

        ChannelPipeline serverPipeline = ch.pipeline();

        if (http2Enabled) {
            if (sslConfig != null) {
                SslContext sslCtx = new SSLHandlerFactory(sslConfig)
                        .createHttp2TLSContextForServer(ocspStaplingEnabled);
                if (ocspStaplingEnabled) {
                    OCSPResp response = getOcspResponse();

                    ReferenceCountedOpenSslContext context = (ReferenceCountedOpenSslContext) sslCtx;
                    SslHandler sslHandler = context.newHandler(ch.alloc());

                    ReferenceCountedOpenSslEngine engine = (ReferenceCountedOpenSslEngine) sslHandler.engine();
                    engine.setOcspResponse(response.getEncoded());
                    ch.pipeline().addLast(sslHandler, new Http2PipelineConfiguratorForServer(this));
                } else {
                    serverPipeline.addLast(sslCtx.newHandler(ch.alloc()),
                                           new Http2PipelineConfiguratorForServer(this));
                }
            } else {
                configureH2cPipeline(serverPipeline);
            }
        } else {
            if (sslConfig != null) {
                configureSslForHttp(serverPipeline, ch);
            } else {
                configureHttpPipeline(serverPipeline, Constants.HTTP_SCHEME);
            }
        }
    }

    private OCSPResp getOcspResponse()
            throws IOException, KeyStoreException, UnrecoverableEntryException, NoSuchAlgorithmException,
            CertificateVerificationException {
        OCSPResp response = OCSPResponseBuilder.generatetOcspResponse(sslConfig, cacheSize, cacheDelay);
        if (!OpenSsl.isAvailable()) {
            throw new IllegalStateException("OpenSSL is not available!");
        }
        if (!OpenSsl.isOcspSupported()) {
            throw new IllegalStateException("OCSP is not supported!");
        }
        return response;
    }

    private void configureSslForHttp(ChannelPipeline serverPipeline, SocketChannel ch)
            throws NoSuchAlgorithmException, CertificateVerificationException, UnrecoverableEntryException,
            KeyStoreException, IOException {

        if (ocspStaplingEnabled) {
            OCSPResp response = getOcspResponse();

            ReferenceCountedOpenSslContext context = new SSLHandlerFactory(sslConfig)
                    .getServerReferenceCountedOpenSslContext(ocspStaplingEnabled);
            SslHandler sslHandler = context.newHandler(ch.alloc());

            ReferenceCountedOpenSslEngine engine = (ReferenceCountedOpenSslEngine) sslHandler.engine();
            engine.setOcspResponse(response.getEncoded());
            ch.pipeline().addLast(sslHandler);
        } else {
            SSLEngine sslEngine = new SSLHandlerFactory(sslConfig).buildServerSSLEngine();
            serverPipeline.addLast(Constants.SSL_HANDLER, new SslHandler(sslEngine));
            if (validateCertEnabled) {
                serverPipeline.addLast(Constants.HTTP_CERT_VALIDATION_HANDLER,
                        new CertificateValidationHandler(sslEngine, cacheDelay, cacheSize));
            }
        }
        serverPipeline.addLast(Constants.SSL_COMPLETION_HANDLER,
                new SslHandshakeCompletionHandlerForServer(this, serverPipeline));
    }

    /**
     * Configures HTTP/1.x pipeline.
     *
     * @param serverPipeline the channel pipeline
     * @param initialHttpScheme initial http scheme
     */
    public void configureHttpPipeline(ChannelPipeline serverPipeline, String initialHttpScheme) {

        if (initialHttpScheme.equals(Constants.HTTP_SCHEME)) {
            serverPipeline.addLast(Constants.HTTP_ENCODER, new HttpResponseEncoder());
            serverPipeline.addLast(Constants.HTTP_DECODER,
                                   new HttpRequestDecoder(reqSizeValidationConfig.getMaxUriLength(),
                                                          reqSizeValidationConfig.getMaxHeaderSize(),
                                                          reqSizeValidationConfig.getMaxChunkSize()));

            if (!proxy) {
                serverPipeline.addLast(Constants.HTTP_COMPRESSOR, new CustomHttpContentCompressor());
                serverPipeline.addLast(Constants.HTTP_CHUNK_WRITER, new ChunkedWriteHandler());
            }

            if (httpTraceLogEnabled) {
                serverPipeline.addLast(HTTP_TRACE_LOG_HANDLER, new HTTPTraceLoggingHandler(TRACE_LOG_DOWNSTREAM));
            }
            if (httpAccessLogEnabled) {
                serverPipeline.addLast(HTTP_ACCESS_LOG_HANDLER, new HttpAccessLoggingHandler(ACCESS_LOG));
            }
        }
        serverPipeline.addLast("uriLengthValidator", new UriAndHeaderLengthValidator(this.serverName));
        if (proxy) {
            if (proxyUserName != null && !proxyUserName.isEmpty()) {
                serverPipeline.addLast(Constants.PROXY_AUTHORIZATION_HANDLER,
                        new ProxyAuthorizationHandler(proxyUserName, proxyPassword));
            }
            serverPipeline
                    .addLast(Constants.PROXY_SERVER_INBOUND_HANDLER, new ProxyServerInboundHandler(proxyPseudonym));
        } else {
            if (reqSizeValidationConfig.getMaxEntityBodySize() > -1) {
                serverPipeline.addLast("maxEntityBodyValidator",
                        new MaxEntityBodyValidator(this.serverName, reqSizeValidationConfig.getMaxEntityBodySize()));
            }

            serverPipeline.addLast(Constants.WEBSOCKET_SERVER_HANDSHAKE_HANDLER,
                    new WebSocketServerHandshakeHandler(this.serverConnectorFuture, this.interfaceId));
            serverPipeline.addLast(Constants.HTTP_SOURCE_HANDLER,
                    new SourceHandler(this.serverConnectorFuture, this.interfaceId, this.chunkConfig, keepAliveConfig,
                            this.serverName, this.allChannels));
            if (socketIdleTimeout >= 0) {
                serverPipeline.addBefore(Constants.HTTP_SOURCE_HANDLER, Constants.IDLE_STATE_HANDLER,
                        new IdleStateHandler(socketIdleTimeout, socketIdleTimeout, socketIdleTimeout,
                                TimeUnit.MILLISECONDS));
            }
        }
    }

    /**
     * Configures HTTP/2 clear text pipeline.
     *
     * @param pipeline the channel pipeline
     */
    private void configureH2cPipeline(ChannelPipeline pipeline) {
        // Add handler to handle http2 requests without an upgrade
        pipeline.addLast(new Http2WithPriorKnowledgeHandler(
                interfaceId, serverName, serverConnectorFuture, this));
        // Add http2 upgrade decoder and upgrade handler
        final HttpServerCodec sourceCodec = new HttpServerCodec(reqSizeValidationConfig.getMaxUriLength(),
                                                                reqSizeValidationConfig.getMaxHeaderSize(),
                                                                reqSizeValidationConfig.getMaxChunkSize());

        final HttpServerUpgradeHandler.UpgradeCodecFactory upgradeCodecFactory = protocol -> {
            if (AsciiString.contentEquals(Http2CodecUtil.HTTP_UPGRADE_PROTOCOL_NAME, protocol)) {
                return new Http2ServerUpgradeCodec(
                        Constants.HTTP2_SOURCE_CONNECTION_HANDLER,
                        new Http2SourceConnectionHandlerBuilder(
                                interfaceId, serverConnectorFuture, serverName, this).build());
            } else {
                return null;
            }
        };
        pipeline.addLast(Constants.HTTP_SERVER_CODEC, sourceCodec);
        pipeline.addLast(Constants.HTTP_COMPRESSOR, new CustomHttpContentCompressor());
        if (httpTraceLogEnabled) {
            pipeline.addLast(HTTP_TRACE_LOG_HANDLER,
                             new HTTPTraceLoggingHandler(TRACE_LOG_DOWNSTREAM));
        }
        if (httpAccessLogEnabled) {
            pipeline.addLast(HTTP_ACCESS_LOG_HANDLER, new HttpAccessLoggingHandler(ACCESS_LOG));
        }
        pipeline.addLast(Constants.HTTP2_UPGRADE_HANDLER,
                         new HttpServerUpgradeHandler(sourceCodec, upgradeCodecFactory, Integer.MAX_VALUE));
        /* Max size of the upgrade request is limited to 2GB. Need to see whether there is a better approach to handle
           large upgrade requests. Requests will be propagated to next handlers if no upgrade has been attempted */
        pipeline.addLast(Constants.HTTP2_TO_HTTP_FALLBACK_HANDLER,
                         new Http2ToHttpFallbackHandler(this));
    }

    public void setServerConnectorFuture(ServerConnectorFuture serverConnectorFuture) {
        this.serverConnectorFuture = serverConnectorFuture;
    }

    void setIdleTimeout(long idleTimeout) {
        this.socketIdleTimeout = idleTimeout;
    }

    void setHttpTraceLogEnabled(boolean httpTraceLogEnabled) {
        this.httpTraceLogEnabled = httpTraceLogEnabled;
    }

    void setHttpAccessLogEnabled(boolean httpAccessLogEnabled) {
        this.httpAccessLogEnabled = httpAccessLogEnabled;
    }

    public boolean isHttpTraceLogEnabled() {
        return httpTraceLogEnabled;
    }

    public boolean isHttpAccessLogEnabled() {
        return httpAccessLogEnabled;
    }

    void setInterfaceId(String interfaceId) {
        this.interfaceId = interfaceId;
    }

    void setSslConfig(SSLConfig sslConfig) {
        this.sslConfig = sslConfig;
    }

    void setReqSizeValidationConfig(RequestSizeValidationConfig reqSizeValidationConfig) {
        this.reqSizeValidationConfig = reqSizeValidationConfig;
    }

    public void setChunkingConfig(ChunkConfig chunkConfig) {
        this.chunkConfig = chunkConfig;
    }

    void setKeepAliveConfig(KeepAliveConfig keepAliveConfig) {
        this.keepAliveConfig = keepAliveConfig;
    }

    void setValidateCertEnabled(boolean validateCertEnabled) {
        this.validateCertEnabled = validateCertEnabled;
    }

    void setCacheDelay(int cacheDelay) {
        this.cacheDelay = cacheDelay;
    }

    void setCacheSize(int cacheSize) {
        this.cacheSize = cacheSize;
    }

    void setServerName(String serverName) {
        this.serverName = serverName;
    }

    void setOcspStaplingEnabled(boolean ocspStaplingEnabled) {
        this.ocspStaplingEnabled = ocspStaplingEnabled;
    }

    void setProxyEnabled(boolean proxy) {
        this.proxy = proxy;
    }

    void setProxyServerUserName(String proxyUserName) {
        this.proxyUserName = proxyUserName;
    }

    void setProxyServerPassword(String proxyPassword) {
        this.proxyPassword = proxyPassword;
    }

    void setProxyPseudonym(String proxyPseudonym) {
        this.proxyPseudonym = proxyPseudonym;
    }

    /**
     * Sets whether HTTP/2.0 is enabled for the connection.
     *
     * @param http2Enabled whether HTTP/2.0 is enabled
     */
    void setHttp2Enabled(boolean http2Enabled) {
        this.http2Enabled = http2Enabled;
    }

    /**
     * Handler which handles ALPN.
     */
    class Http2PipelineConfiguratorForServer extends ApplicationProtocolNegotiationHandler {

        private HttpServerChannelInitializer channelInitializer;

        Http2PipelineConfiguratorForServer(HttpServerChannelInitializer channelInitializer) {
            super(ApplicationProtocolNames.HTTP_1_1);
            this.channelInitializer = channelInitializer;
        }

        /**
         *  Configure pipeline after SSL handshake
         */
        @Override
        protected void configurePipeline(ChannelHandlerContext ctx, String protocol) throws Exception {
            if (ApplicationProtocolNames.HTTP_2.equals(protocol)) {
                // handles pipeline for HTTP/2 requests after SSL handshake
                ctx.pipeline().addLast(
                        Constants.HTTP2_SOURCE_CONNECTION_HANDLER,
                        new Http2SourceConnectionHandlerBuilder(
                                interfaceId, serverConnectorFuture, serverName, channelInitializer).build());
            } else if (ApplicationProtocolNames.HTTP_1_1.equals(protocol)) {
                // handles pipeline for HTTP/1.x requests after SSL handshake
                configureHttpPipeline(ctx.pipeline(), Constants.HTTP_SCHEME);
            } else {
                throw new IllegalStateException("unknown protocol: " + protocol);
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
            if (ctx != null && ctx.channel().isActive()) {
                ctx.writeAndFlush(Unpooled.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE);
            }
        }
    }

    void setAllChannels(ChannelGroup allChannels) {
        this.allChannels = allChannels;
    }
}

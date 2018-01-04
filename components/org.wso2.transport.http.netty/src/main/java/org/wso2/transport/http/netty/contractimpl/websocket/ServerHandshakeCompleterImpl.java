package org.wso2.transport.http.netty.contractimpl.websocket;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.timeout.IdleStateHandler;
import org.wso2.transport.http.netty.common.Constants;
import org.wso2.transport.http.netty.contract.HandshakeCompleter;
import org.wso2.transport.http.netty.listener.WebSocketSourceHandler;

import java.util.concurrent.TimeUnit;
import javax.websocket.Session;

/**
 * Handshake completer for WebSocket server.
 */
public class ServerHandshakeCompleterImpl implements HandshakeCompleter {

    private final Session session;
    private final ChannelHandlerContext ctx;
    private final WebSocketSourceHandler webSocketSourceHandler;
    private final int idleTimeout;

    public ServerHandshakeCompleterImpl(Session session, ChannelHandlerContext ctx,
                                        WebSocketSourceHandler webSocketSourceHandler, int idleTimeout) {
        this.session = session;
        this.ctx = ctx;
        this.webSocketSourceHandler = webSocketSourceHandler;
        this.idleTimeout = idleTimeout;
    }

    @Override
    public Session getSession() {
        return session;
    }

    @Override
    public void startListeningForFrames() {
        //Replace HTTP handlers  with  new Handlers for WebSocket in the pipeline
        ChannelPipeline pipeline = ctx.pipeline();
        if (idleTimeout > 0) {
            pipeline.replace(Constants.IDLE_STATE_HANDLER, Constants.IDLE_STATE_HANDLER,
                             new IdleStateHandler(idleTimeout, idleTimeout, idleTimeout,
                                                  TimeUnit.MILLISECONDS));
        } else {
            pipeline.remove(Constants.IDLE_STATE_HANDLER);
        }
        pipeline.addLast(Constants.WEBSOCKET_SOURCE_HANDLER, webSocketSourceHandler);
        pipeline.remove(Constants.HTTP_SOURCE_HANDLER);
    }
}

package org.wso2.transport.http.netty.contract.websocket;

import org.wso2.transport.http.netty.contract.HandshakeCompleter;

/**
 * Future for WebSocket handshake.
 */
public interface HandshakeFuture {

    /**
     * Set the listener for WebSocket handshake.
     *
     * @param handshakeListener Listener for WebSocket handshake.
     * @return the same handshake future.
     */
    public HandshakeFuture setHandshakeListener(HandshakeListener handshakeListener);

    /**
     * Notify the success of the WebSocket handshake.
     *
     * @param handshakeCompleter {@link HandshakeCompleter} for the successful connection.
     */
    public void notifySuccess(HandshakeCompleter handshakeCompleter);

    /**
     * Notify any error occurred during the handshake.
     *
     * @param throwable error occurred during handshake.
     */
    public void notifyError(Throwable throwable);

    /**
     * Sync the future.
     *
     * @throws InterruptedException if interruption happens during sync time.
     */
    public void sync() throws InterruptedException;
}

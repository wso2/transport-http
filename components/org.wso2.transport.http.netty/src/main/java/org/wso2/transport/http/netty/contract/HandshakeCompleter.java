package org.wso2.transport.http.netty.contract;

import javax.websocket.Session;

/**
 * This is the completer for a successful handshake.
 */
public interface HandshakeCompleter {

    /**
     * This returns the Session of the successful handshake.
     *
     * @return the {@link Session} of the successful handshake.
     */
    Session getSession();

    /**
     * Complete the process of the handshake.
     */
    void startListeningForFrames();
}

/*
 *  Copyright (c) 2017, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
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

package org.wso2.transport.http.netty.websocket;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.Assert;
import org.wso2.transport.http.netty.contract.HttpWsConnectorFactory;
import org.wso2.transport.http.netty.contract.websocket.HandshakeFuture;
import org.wso2.transport.http.netty.contract.websocket.HandshakeListener;
import org.wso2.transport.http.netty.contract.websocket.WebSocketBinaryMessage;
import org.wso2.transport.http.netty.contract.websocket.WebSocketClientConnector;
import org.wso2.transport.http.netty.contract.websocket.WebSocketCloseMessage;
import org.wso2.transport.http.netty.contract.websocket.WebSocketConnectorListener;
import org.wso2.transport.http.netty.contract.websocket.WebSocketControlMessage;
import org.wso2.transport.http.netty.contract.websocket.WebSocketInitMessage;
import org.wso2.transport.http.netty.contract.websocket.WebSocketTextMessage;
import org.wso2.transport.http.netty.contract.websocket.WsClientConnectorConfig;
import org.wso2.transport.http.netty.contractimpl.HttpWsConnectorFactoryImpl;
import org.wso2.transport.http.netty.util.TestUtil;

import java.io.IOException;
import javax.websocket.Session;

/**
 * Server Connector Listener to check WebSocket pass-through scenarios.
 */
public class WebSocketPassthroughServerConnectorListener implements WebSocketConnectorListener {

    private static final Logger logger = LoggerFactory.getLogger(WebSocketPassthroughServerConnectorListener.class);

    private final HttpWsConnectorFactory connectorFactory = new HttpWsConnectorFactoryImpl();

    @Override
    public void onMessage(WebSocketInitMessage initMessage) {
        String remoteUrl = String.format("ws://%s:%d/%s", "localhost",
                                         TestUtil.TEST_REMOTE_WS_SERVER_PORT, "websocket");
        WsClientConnectorConfig configuration = new WsClientConnectorConfig(remoteUrl);
        configuration.setTarget("myService");
        WebSocketClientConnector clientConnector = connectorFactory.createWsClientConnector(configuration);
        WebSocketConnectorListener clientConnectorListener = new WebSocketPassthroughClientConnectorListener();

        try {
            clientConnector.connect(clientConnectorListener).setHandshakeListener(new HandshakeListener() {
                @Override
                public void onSuccess(Session clientSession) {
                    HandshakeFuture serverFuture = initMessage.handshake();
                    serverFuture.setHandshakeListener(new HandshakeListener() {
                        @Override
                        public void onSuccess(Session serverSession) {
                            WebSocketPassThroughTestSessionManager.getInstance().
                                    interRelateSessions(serverSession, clientSession);
                        }

                        @Override
                        public void onError(Throwable t) {
                            logger.error(t.getMessage());
                            Assert.assertTrue(false, "Error: " + t.getMessage());
                        }
                    });
                }

                @Override
                public void onError(Throwable t) {
                    Assert.assertTrue(false, t.getMessage());
                }
            }).sync();
        } catch (InterruptedException e) {
            Assert.assertTrue(false, e.getMessage());
        }
    }

    @Override
    public void onMessage(WebSocketTextMessage textMessage) {
        try {
            Session clientSession = WebSocketPassThroughTestSessionManager.getInstance().
                    getClientSession(textMessage.getChannelSession());
            clientSession.getBasicRemote().sendText(textMessage.getText());
        } catch (IOException e) {
            logger.error("IO error when sending message: " + e.getMessage());
        }
    }

    @Override
    public void onMessage(WebSocketBinaryMessage binaryMessage) {
        try {
            Session clientSession = WebSocketPassThroughTestSessionManager.getInstance()
                    .getClientSession(binaryMessage.getChannelSession());
            clientSession.getBasicRemote().sendBinary(binaryMessage.getByteBuffer());
        } catch (IOException e) {
            logger.error("IO error when sending message: " + e.getMessage());
        }
    }

    @Override
    public void onMessage(WebSocketControlMessage controlMessage) {
        // Do nothing.
    }

    @Override
    public void onMessage(WebSocketCloseMessage closeMessage) {
        try {
            Session clientSession = WebSocketPassThroughTestSessionManager.getInstance()
                    .getClientSession(closeMessage.getChannelSession());
            clientSession.close();
        } catch (IOException e) {
            logger.error("IO error when sending message: " + e.getMessage());
        }
    }

    @Override
    public void onError(Throwable throwable) {

    }

    @Override
    public void onIdleTimeout(WebSocketControlMessage controlMessage) {
    }
}

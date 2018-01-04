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
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;
import org.wso2.carbon.messaging.exceptions.ClientConnectorException;
import org.wso2.carbon.messaging.exceptions.ServerConnectorException;
import org.wso2.transport.http.netty.contract.HandshakeCompleter;
import org.wso2.transport.http.netty.contract.websocket.HandshakeFuture;
import org.wso2.transport.http.netty.contract.websocket.HandshakeListener;
import org.wso2.transport.http.netty.contract.websocket.WebSocketClientConnector;
import org.wso2.transport.http.netty.contract.websocket.WebSocketConnectorListener;
import org.wso2.transport.http.netty.contract.websocket.WsClientConnectorConfig;
import org.wso2.transport.http.netty.contractimpl.HttpWsConnectorFactoryImpl;
import org.wso2.transport.http.netty.util.TestUtil;
import org.wso2.transport.http.netty.util.server.websocket.WebSocketRemoteServer;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.websocket.CloseReason;
import javax.websocket.Session;

/**
 * Test cases for the WebSocket Client implementation.
 */
public class WebSocketClientTestCase {

    private static final Logger log = LoggerFactory.getLogger(WebSocketClientTestCase.class);

    private HttpWsConnectorFactoryImpl httpConnectorFactory = new HttpWsConnectorFactoryImpl();
    private final String url = String.format("ws://%s:%d/%s", "localhost",
                                             TestUtil.REMOTE_WS_SERVER_PORT, "websocket");
    private static final String PING = "ping";
    private final int latchWaitTimeInSeconds = 10;
    private WsClientConnectorConfig configuration = new WsClientConnectorConfig(url);
    private WebSocketClientConnector clientConnector;
    private WebSocketRemoteServer remoteServer = new WebSocketRemoteServer(TestUtil.REMOTE_WS_SERVER_PORT,
                                                                           "xml, json");

    @BeforeClass
    public void setup() throws InterruptedException, ClientConnectorException {
        remoteServer.run();
        clientConnector = httpConnectorFactory.createWsClientConnector(configuration);
    }

    @Test(priority = 1, description = "Test the WebSocket handshake and sending and receiving text messages.")
    public void testTextReceived() throws Throwable {
        CountDownLatch latch = new CountDownLatch(1);
        String textSent = "testText";
        WebSocketTestClientConnectorListener connectorListener = new WebSocketTestClientConnectorListener(latch);
        HandshakeFuture handshakeFuture = handshake(connectorListener);
        handshakeFuture.setHandshakeListener(new HandshakeListener() {
            @Override
            public void onSuccess(HandshakeCompleter handshakeCompleter) {
                handshakeCompleter.startListeningForFrames();
                try {
                    handshakeCompleter.getSession().getBasicRemote().sendText(textSent);
                } catch (IOException e) {
                    log.error(e.getMessage());
                    Assert.assertTrue(false, e.getMessage());
                }
            }

            @Override
            public void onError(Throwable t) {
                log.error(t.getMessage());
                Assert.assertTrue(false, t.getMessage());
            }
        });

        latch.await(latchWaitTimeInSeconds, TimeUnit.SECONDS);
        String textReceived = connectorListener.getReceivedTextToClient();
        Assert.assertEquals(textReceived, textSent);
    }

    @Test(priority = 2, description = "Test binary message sending and receiving.")
    public void testBinaryReceived() throws Throwable {
        CountDownLatch latch = new CountDownLatch(1);
        byte[] bytes = {1, 2, 3, 4, 5};
        ByteBuffer bufferSent = ByteBuffer.wrap(bytes);
        WebSocketTestClientConnectorListener connectorListener = new WebSocketTestClientConnectorListener(latch);
        HandshakeFuture handshakeFuture = handshake(connectorListener);
        handshakeFuture.setHandshakeListener(new HandshakeListener() {
            @Override
            public void onSuccess(HandshakeCompleter handshakeCompleter) {
                handshakeCompleter.startListeningForFrames();
                try {
                    handshakeCompleter.getSession().getBasicRemote().sendBinary(bufferSent);
                } catch (IOException e) {
                    log.error(e.getMessage());
                    Assert.assertTrue(false, e.getMessage());
                }
            }

            @Override
            public void onError(Throwable t) {
                log.error(t.getMessage());
                Assert.assertTrue(false, t.getMessage());
            }
        });

        latch.await(latchWaitTimeInSeconds, TimeUnit.SECONDS);
        ByteBuffer bufferReceived = connectorListener.getReceivedByteBufferToClient();
        Assert.assertEquals(bufferReceived, bufferSent);
    }

    @Test(priority = 3, description = "Test PING pong messaging.")
    public void testPingPong() throws Throwable {
        // Request PING from remote and test receive.
        CountDownLatch pingLatch = new CountDownLatch(1);
        WebSocketTestClientConnectorListener pingConnectorListener =
                new WebSocketTestClientConnectorListener(pingLatch);
        HandshakeFuture pingHandshakeFuture = handshake(pingConnectorListener);
        pingHandshakeFuture.setHandshakeListener(new HandshakeListener() {
            @Override
            public void onSuccess(HandshakeCompleter handshakeCompleter) {
                handshakeCompleter.startListeningForFrames();
                try {
                    handshakeCompleter.getSession().getBasicRemote().sendText(PING);
                } catch (IOException e) {
                    log.error(e.getMessage());
                    Assert.assertTrue(false, e.getMessage());
                }
            }

            @Override
            public void onError(Throwable t) {
                log.error(t.getMessage());
                Assert.assertTrue(false, t.getMessage());
            }
        });
        pingLatch.await(latchWaitTimeInSeconds, TimeUnit.SECONDS);
        Assert.assertTrue(pingConnectorListener.isPingReceived(), "Ping message should be received");

        // Test pong receive
        CountDownLatch pongLatch = new CountDownLatch(1);
        WebSocketTestClientConnectorListener pongConnectorListener =
                new WebSocketTestClientConnectorListener(pongLatch);
        HandshakeFuture pongHandshakeFuture = handshake(pongConnectorListener);
        pongHandshakeFuture.setHandshakeListener(new HandshakeListener() {
            @Override
            public void onSuccess(HandshakeCompleter handshakeCompleter) {
                handshakeCompleter.startListeningForFrames();
                try {
                    byte[] bytes = {1, 2, 3, 4, 5};
                    ByteBuffer buffer = ByteBuffer.wrap(bytes);
                    handshakeCompleter.getSession().getBasicRemote().sendPing(buffer);
                } catch (IOException e) {
                    log.error(e.getMessage());
                    Assert.assertTrue(false, e.getMessage());
                }
            }

            @Override
            public void onError(Throwable t) {
                log.error(t.getMessage());
                Assert.assertTrue(false, t.getMessage());
            }
        });
        pongLatch.await(latchWaitTimeInSeconds, TimeUnit.SECONDS);
        Assert.assertTrue(pongConnectorListener.isPongReceived(), "Pong message should be received");
    }

    @Test(priority = 4, description = "Test multiple clients handling, sending and receiving text messages for them.")
    public void testMultipleClients() throws Throwable {
        CountDownLatch latch1 = new CountDownLatch(1);
        WebSocketTestClientConnectorListener connectorListener1 = new WebSocketTestClientConnectorListener(latch1);
        String[] textsSent = {"testText1", "testText2"};
        HandshakeFuture handshakeFuture1 = handshake(connectorListener1);
        handshakeFuture1.setHandshakeListener(new HandshakeListener() {
            @Override
            public void onSuccess(HandshakeCompleter handshakeCompleter) {
                handshakeCompleter.startListeningForFrames();
                try {
                    handshakeCompleter.getSession().getBasicRemote().sendText(textsSent[0]);
                } catch (IOException e) {
                    log.error(e.getMessage());
                    Assert.assertTrue(false, e.getMessage());
                }
            }

            @Override
            public void onError(Throwable t) {
                log.error(t.getMessage());
                Assert.assertTrue(false, t.getMessage());
            }
        });

        latch1.await(latchWaitTimeInSeconds, TimeUnit.SECONDS);
        Assert.assertEquals(connectorListener1.getReceivedTextToClient(), textsSent[0]);

        CountDownLatch latch2 = new CountDownLatch(2);
        WebSocketTestClientConnectorListener connectorListener2 = new WebSocketTestClientConnectorListener(latch2);
        HandshakeFuture handshakeFuture2 = handshake(connectorListener2);
        handshakeFuture2.setHandshakeListener(new HandshakeListener() {
            @Override
            public void onSuccess(HandshakeCompleter handshakeCompleter) {
                handshakeCompleter.startListeningForFrames();
                try {
                    for (int i = 0; i < textsSent.length; i++) {
                        handshakeCompleter.getSession().getBasicRemote().sendText(textsSent[i]);
                    }
                } catch (IOException e) {
                    log.error(e.getMessage());
                    Assert.assertTrue(false, e.getMessage());
                }
            }

            @Override
            public void onError(Throwable t) {
                log.error(t.getMessage());
                Assert.assertTrue(false, t.getMessage());
            }
        });

        latch2.await(latchWaitTimeInSeconds, TimeUnit.SECONDS);

        for (int i = 0; i < textsSent.length; i++) {
            Assert.assertEquals(connectorListener2.getReceivedTextToClient(), textsSent[i]);
        }
    }

    @Test(priority = 5, description = "Test the idle timeout for WebSocket")
    public void testIdleTimeout() throws Throwable {
        configuration.setIdleTimeoutInMillis(1000);
        clientConnector = httpConnectorFactory.createWsClientConnector(configuration);
        CountDownLatch latch = new CountDownLatch(1);
        WebSocketTestClientConnectorListener connectorListener = new WebSocketTestClientConnectorListener(latch);
        HandshakeFuture handshakeFuture = handshake(connectorListener);
        handshakeFuture.setHandshakeListener(new HandshakeListener() {
            @Override
            public void onSuccess(HandshakeCompleter handshakeCompleter) {
                handshakeCompleter.startListeningForFrames();
            }

            @Override
            public void onError(Throwable t) {
                log.error(t.getMessage());
                Assert.assertTrue(false, t.getMessage());
            }
        });

        latch.await(latchWaitTimeInSeconds, TimeUnit.SECONDS);
        Assert.assertTrue(connectorListener.isIdleTimeout(), "Should reach idle timeout");
    }

    @Test(priority = 6, description = "Test the sub protocol negotiation with the remote server")
    public void testSubProtocolNegotiation() throws InterruptedException {

        // Try with a matching sub protocol.
        String[] subProtocolsSuccess = {"xmlx", "json"};
        configuration.setSubProtocols(subProtocolsSuccess);
        clientConnector = httpConnectorFactory.createWsClientConnector(configuration);
        CountDownLatch latchSuccess = new CountDownLatch(1);
        WebSocketTestClientConnectorListener connectorListenerSuccess =
                new WebSocketTestClientConnectorListener(latchSuccess);
        HandshakeFuture handshakeFutureSuccess = handshake(connectorListenerSuccess);
        handshakeFutureSuccess.setHandshakeListener(new HandshakeListener() {
            @Override
            public void onSuccess(HandshakeCompleter handshakeCompleter) {
                handshakeCompleter.startListeningForFrames();
                Assert.assertEquals(handshakeCompleter.getSession().getNegotiatedSubprotocol(), "json");
                latchSuccess.countDown();
            }

            @Override
            public void onError(Throwable t) {
                log.error(t.getMessage());
                Assert.assertTrue(false, "Handshake failed: " + t.getMessage());
                latchSuccess.countDown();
            }
        });
        latchSuccess.await(latchWaitTimeInSeconds, TimeUnit.SECONDS);

        // Try with unmatching sub protocol
        String[] subProtocolsFail = {"xmlx", "jsonx"};
        configuration.setSubProtocols(subProtocolsFail);
        clientConnector = httpConnectorFactory.createWsClientConnector(configuration);
        CountDownLatch latchFail = new CountDownLatch(1);
        WebSocketTestClientConnectorListener connectorListenerFail =
                new WebSocketTestClientConnectorListener(latchFail);
        HandshakeFuture handshakeFutureFail = handshake(connectorListenerFail);
        handshakeFutureFail.setHandshakeListener(new HandshakeListener() {
            @Override
            public void onSuccess(HandshakeCompleter handshakeCompleter) {
                handshakeCompleter.startListeningForFrames();
                Assert.assertFalse(true, "Should not negotiate");
                latchFail.countDown();
            }

            @Override
            public void onError(Throwable t) {
                log.error(t.getMessage());
                Assert.assertTrue(true, "Handshake failed: " + t.getMessage());
                latchFail.countDown();
            }
        });
        latchFail.await(latchWaitTimeInSeconds, TimeUnit.SECONDS);
    }

    @AfterClass
    public void cleanUp() throws ServerConnectorException, InterruptedException {
        remoteServer.stop();
    }

    private HandshakeFuture handshake(WebSocketConnectorListener connectorListener) {
        return clientConnector.connect(connectorListener);
    }

    private void shutDownClient(Session session) throws IOException {
        session.close(new CloseReason(
                () -> 1000,
                "Normal Closure"
        ));
    }
}

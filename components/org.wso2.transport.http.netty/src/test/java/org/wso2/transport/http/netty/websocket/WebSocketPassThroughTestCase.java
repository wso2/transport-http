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
import org.wso2.transport.http.netty.config.ListenerConfiguration;
import org.wso2.transport.http.netty.contract.ServerConnector;
import org.wso2.transport.http.netty.contract.ServerConnectorFuture;
import org.wso2.transport.http.netty.contractimpl.HttpWsConnectorFactoryImpl;
import org.wso2.transport.http.netty.listener.ServerBootstrapConfiguration;
import org.wso2.transport.http.netty.util.TestUtil;
import org.wso2.transport.http.netty.util.client.websocket.WebSocketTestClient;
import org.wso2.transport.http.netty.util.server.websocket.WebSocketRemoteServer;

import java.net.ProtocolException;
import java.net.URISyntaxException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLException;

/**
 * Test cases for WebSocket pass-through scenarios.
 */
public class WebSocketPassThroughTestCase {

    private static final Logger log = LoggerFactory.getLogger(WebSocketPassThroughTestCase.class);

    private final int latchCountDownInSecs = 10;

    private HttpWsConnectorFactoryImpl httpConnectorFactory = new HttpWsConnectorFactoryImpl();
    private WebSocketRemoteServer remoteServer = new WebSocketRemoteServer(TestUtil.TEST_REMOTE_WS_SERVER_PORT);

    private ServerConnector serverConnector;

    @BeforeClass
    public void setup() throws InterruptedException, ClientConnectorException {
        remoteServer.run();
        ListenerConfiguration listenerConfiguration = new ListenerConfiguration();
        listenerConfiguration.setHost("localhost");
        listenerConfiguration.setPort(TestUtil.TEST_DEFAULT_INTERFACE_PORT);
        serverConnector = httpConnectorFactory.createServerConnector(ServerBootstrapConfiguration.getInstance(),
                                                                     listenerConfiguration);
        ServerConnectorFuture connectorFuture = serverConnector.start();
        connectorFuture.setWSConnectorListener(new WebSocketPassthroughServerConnectorListener());
        connectorFuture.sync();
    }

    @Test
    public void testTextPassThrough() throws InterruptedException, SSLException, URISyntaxException, ProtocolException {
        CountDownLatch latch = new CountDownLatch(1);
        WebSocketTestClient webSocketClient = new WebSocketTestClient(latch);
        webSocketClient.handhshake();
        String text = "hello-pass-through";
        webSocketClient.sendText(text);
        latch.await(latchCountDownInSecs, TimeUnit.SECONDS);
        Assert.assertEquals(webSocketClient.getTextReceived(), text);
    }

    @AfterClass
    public void cleaUp() throws ServerConnectorException, InterruptedException {
        serverConnector.stop();
        remoteServer.stop();
    }
}

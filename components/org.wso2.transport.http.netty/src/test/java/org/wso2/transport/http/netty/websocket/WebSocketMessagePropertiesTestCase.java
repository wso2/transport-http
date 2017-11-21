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
import org.wso2.carbon.messaging.exceptions.ServerConnectorException;
import org.wso2.transport.http.netty.config.ListenerConfiguration;
import org.wso2.transport.http.netty.contract.ServerConnector;
import org.wso2.transport.http.netty.contract.ServerConnectorFuture;
import org.wso2.transport.http.netty.contractimpl.HttpWsConnectorFactoryImpl;
import org.wso2.transport.http.netty.listener.ServerBootstrapConfiguration;
import org.wso2.transport.http.netty.util.TestUtil;
import org.wso2.transport.http.netty.util.client.websocket.WebSocketTestClient;

import java.net.ProtocolException;
import java.net.URISyntaxException;
import java.util.HashMap;
import java.util.Map;
import javax.net.ssl.SSLException;

/**
 * Test class to check the known properties of which should contain in a WebSocket message.
 */
public class WebSocketMessagePropertiesTestCase {

    private static final Logger log = LoggerFactory.getLogger(WebSocketMessagePropertiesTestCase.class);

    private HttpWsConnectorFactoryImpl httpConnectorFactory = new HttpWsConnectorFactoryImpl();
    private ServerConnector serverConnector;

    @BeforeClass
    public void setup() throws InterruptedException {
        ListenerConfiguration listenerConfiguration = new ListenerConfiguration();
        listenerConfiguration.setHost("localhost");
        listenerConfiguration.setPort(TestUtil.TEST_DEFAULT_INTERFACE_PORT);
        serverConnector = httpConnectorFactory.createServerConnector(ServerBootstrapConfiguration.getInstance(),
                                                                  listenerConfiguration);
        ServerConnectorFuture connectorFuture = serverConnector.start();
        connectorFuture.setWSConnectorListener(new WebSocketMessagePropertiesConnectorListener());
        connectorFuture.sync();
    }

    @Test
    public void testProperties() throws InterruptedException, SSLException, URISyntaxException {
        // Testing normal scenarios.
        String subProtocol = "xml, json, xmlx";
        Map<String, String> customHeaders = new HashMap<>();
        customHeaders.put("check-sub-protocol", "true");
        customHeaders.put("message-type", "websocket");
        customHeaders.put("message-sender", "wso2");
        WebSocketTestClient wsClient = new WebSocketTestClient(subProtocol, customHeaders);
        try {
            wsClient.handhshake();
        } catch (ProtocolException e) {
            log.error(e.getMessage());
            Assert.assertTrue(false);
        }
        wsClient.sendText("Hi backend");
        wsClient.shutDown();


        // Testing invalid subprotocol
        subProtocol = "xmlx, json";
        customHeaders.put("check-sub-protocol", "true");
        customHeaders.put("message-type", "websocket");
        customHeaders.put("message-sender", "wso2");
        wsClient = new WebSocketTestClient(subProtocol, customHeaders);
        try {
            wsClient.handhshake();
            Assert.assertTrue(false);
        } catch (ProtocolException e) {
            Assert.assertTrue(true, e.getMessage());
        }
        wsClient.shutDown();
    }

    @AfterClass
    public void cleaUp() throws ServerConnectorException, InterruptedException {
        serverConnector.stop();
    }
}

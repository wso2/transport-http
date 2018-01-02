/*
 * Copyright (c) 2017, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.wso2.transport.http.netty.connectionpool;

import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;
import org.wso2.carbon.messaging.exceptions.ServerConnectorException;
import org.wso2.transport.http.netty.common.Constants;
import org.wso2.transport.http.netty.config.TransportsConfiguration;
import org.wso2.transport.http.netty.contract.HttpClientConnector;
import org.wso2.transport.http.netty.contract.HttpWsConnectorFactory;
import org.wso2.transport.http.netty.contractimpl.HttpWsConnectorFactoryImpl;
import org.wso2.transport.http.netty.message.HTTPConnectorUtil;
import org.wso2.transport.http.netty.util.HTTPConnectorListener;
import org.wso2.transport.http.netty.util.TestUtil;
import org.wso2.transport.http.netty.util.server.HttpServer;
import org.wso2.transport.http.netty.util.server.initializers.SendChannelIDServerInitializer;

import java.io.File;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.testng.Assert.assertEquals;

/**
 * Tests for connection pool implementation.
 */
public class ConnectionPoolMainTestCase {

    private HttpServer httpServer;
    private HttpClientConnector httpClientConnector;

    @BeforeClass
    public void setup() {
        TransportsConfiguration transportsConfiguration = TestUtil.getConfiguration(
                "/simple-test-config" + File.separator + "netty-transports.yml");

        httpServer = TestUtil.startHTTPServer(TestUtil.HTTP_SERVER_PORT, new SendChannelIDServerInitializer(5000));

        HttpWsConnectorFactory connectorFactory = new HttpWsConnectorFactoryImpl();
        httpClientConnector = connectorFactory.createHttpClientConnector(
                HTTPConnectorUtil.getTransportProperties(transportsConfiguration),
                HTTPConnectorUtil.getSenderConfiguration(transportsConfiguration, Constants.HTTP_SCHEME));
    }

    @Test
    public void testConnectionReuseForMain() {
        try {
            CountDownLatch requestOneLatch = new CountDownLatch(1);
            CountDownLatch requestTwoLatch = new CountDownLatch(1);
            CountDownLatch requestThreeLatch = new CountDownLatch(1);

            HTTPConnectorListener responseListener;

            responseListener = TestUtil.sendRequestAsync(requestOneLatch, httpClientConnector);

            // While the first request is being processed by the back-end,
            // we send the second request which forces the client connector to
            // create a new connection.
            Thread.sleep(2500);
            TestUtil.sendRequestAsync(requestTwoLatch, httpClientConnector);

            String responseOne = TestUtil.waitAndGetStringEntity(requestOneLatch, responseListener);

            responseListener = TestUtil.sendRequestAsync(requestThreeLatch, httpClientConnector);
            String responseThree = TestUtil.waitAndGetStringEntity(requestThreeLatch, responseListener);

            assertEquals(responseOne, responseThree);

            // Wait for response two to be completed before finishing the test
            requestTwoLatch.await(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            TestUtil.handleException("IOException occurred while running testConnectionReuseForMain", e);
        }
    }

    @AfterClass
    public void cleanUp() throws ServerConnectorException {
        TestUtil.cleanUp(new ArrayList<>(), httpServer);
    }
}

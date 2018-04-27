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

package org.wso2.transport.http.netty.passthrough;

import io.netty.handler.codec.http.HttpMethod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;
import org.wso2.transport.http.netty.common.Constants;
import org.wso2.transport.http.netty.config.ListenerConfiguration;
import org.wso2.transport.http.netty.config.SenderConfiguration;
import org.wso2.transport.http.netty.contract.HttpWsConnectorFactory;
import org.wso2.transport.http.netty.contract.ServerConnector;
import org.wso2.transport.http.netty.contract.ServerConnectorException;
import org.wso2.transport.http.netty.contract.ServerConnectorFuture;
import org.wso2.transport.http.netty.contractimpl.DefaultHttpWsConnectorFactory;
import org.wso2.transport.http.netty.listener.ServerBootstrapConfiguration;
import org.wso2.transport.http.netty.util.TestUtil;
import org.wso2.transport.http.netty.util.server.HttpServer;
import org.wso2.transport.http.netty.util.server.initializers.MockServerInitializer;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.util.HashMap;

import static org.testng.AssertJUnit.assertEquals;

/**
 * A test class for passthrough transport.
 */
public class PassThroughHttpTestCase {

    private static final Logger logger = LoggerFactory.getLogger(PassThroughHttpTestCase.class);

    private static final String testValue = "Test Message";
    private HttpServer httpServer;
    private HttpWsConnectorFactory httpWsConnectorFactory;
    private ServerConnector serverConnector;

    private URI baseURI = URI.create(String.format("http://%s:%d", "localhost", TestUtil.SERVER_CONNECTOR_PORT));

    @BeforeClass
    public void setUp() {
        httpWsConnectorFactory = new DefaultHttpWsConnectorFactory();

        ListenerConfiguration listenerConfiguration = new ListenerConfiguration();
        listenerConfiguration.setPort(TestUtil.SERVER_CONNECTOR_PORT);
        serverConnector = httpWsConnectorFactory
                .createServerConnector(new ServerBootstrapConfiguration(new HashMap<>()), listenerConfiguration);
        ServerConnectorFuture serverConnectorFuture = serverConnector.start();
        serverConnectorFuture.setHttpConnectorListener(
                new PassthroughMessageProcessorListener(new SenderConfiguration()));
        try {
            serverConnectorFuture.sync();
        } catch (InterruptedException e) {
            logger.warn("Interrupted while waiting for server connector to start");
        }

        httpServer = TestUtil.startHTTPServer(TestUtil.HTTP_SERVER_PORT,
                new MockServerInitializer(testValue, Constants.TEXT_PLAIN, 200));
    }

    @Test
    public void passthroughGetTest() {
        try {
            HttpURLConnection urlConn = TestUtil.request(baseURI, "/", HttpMethod.GET.name(), true);
            String content = TestUtil.getContent(urlConn);
            assertEquals(testValue, content);
            urlConn.disconnect();
        } catch (IOException e) {
            TestUtil.handleException("IOException occurred while running passthroughGetTest", e);
        }
    }

    @Test
    public void passthroughPostTest() {
        try {
            HttpURLConnection urlConn = TestUtil.request(baseURI, "/", HttpMethod.POST.name(), true);
            String content = TestUtil.getContent(urlConn);
            assertEquals(testValue, content);
            urlConn.disconnect();
        } catch (IOException e) {
            TestUtil.handleException("IOException occurred while running passthroughPostTest", e);
        }
    }

    @Test
    public void passthroughHeadTest() {
        try {
            HttpURLConnection urlConn = TestUtil.request(baseURI, "/", HttpMethod.HEAD.name(), true);
            String content = TestUtil.getContent(urlConn);
            assertEquals("", content);
            urlConn.disconnect();
        } catch (IOException e) {
            TestUtil.handleException("IOException occurred while running passthroughHeadTest", e);
        }
    }

    @AfterClass
    public void cleanUp() throws ServerConnectorException {
        try {
            serverConnector.stop();
            httpServer.shutdown();
            httpWsConnectorFactory.shutdown();
        } catch (InterruptedException e) {
            logger.warn("Interrupted while waiting for clean up");
        }
    }
}

/*
 *  Copyright (c) 2018, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
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
package org.wso2.transport.http.netty.proxyserver;

import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.DefaultLastHttpContent;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpVersion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;
import org.wso2.transport.http.netty.common.ProxyServerConfiguration;
import org.wso2.transport.http.netty.config.ListenerConfiguration;
import org.wso2.transport.http.netty.config.SenderConfiguration;
import org.wso2.transport.http.netty.config.TransportsConfiguration;
import org.wso2.transport.http.netty.contentaware.listeners.EchoMessageListener;
import org.wso2.transport.http.netty.contract.HttpClientConnector;
import org.wso2.transport.http.netty.contract.HttpResponseFuture;
import org.wso2.transport.http.netty.contract.HttpWsConnectorFactory;
import org.wso2.transport.http.netty.contract.ServerConnector;
import org.wso2.transport.http.netty.contract.ServerConnectorException;
import org.wso2.transport.http.netty.contract.ServerConnectorFuture;
import org.wso2.transport.http.netty.contractimpl.DefaultHttpWsConnectorFactory;
import org.wso2.transport.http.netty.message.HTTPCarbonMessage;
import org.wso2.transport.http.netty.message.HTTPConnectorUtil;
import org.wso2.transport.http.netty.message.HttpCarbonRequest;
import org.wso2.transport.http.netty.message.HttpMessageDataStreamer;
import org.wso2.transport.http.netty.util.HTTPConnectorListener;
import org.wso2.transport.http.netty.util.TestUtil;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.util.HashMap;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.testng.Assert.assertEquals;
import static org.testng.AssertJUnit.assertNotNull;
import static org.wso2.transport.http.netty.common.Constants.HTTPS_SCHEME;
import static org.wso2.transport.http.netty.common.Constants.HTTP_HOST;
import static org.wso2.transport.http.netty.common.Constants.HTTP_METHOD;
import static org.wso2.transport.http.netty.common.Constants.HTTP_PORT;
import static org.wso2.transport.http.netty.common.Constants.HTTP_POST_METHOD;
import static org.wso2.transport.http.netty.common.Constants.PROTOCOL;

/**
 * A test case for proxy server handling incoming https requests.
 */
public class HttpsProxyServerTestCase {
    private static Logger logger = LoggerFactory.getLogger(HttpsProxyServerTestCase.class);

    private static ServerConnector serverConnector;
    private static ServerConnector proxyServerConnector;
    private static HttpWsConnectorFactory httpWsConnectorFactory;
    private static HttpClientConnector httpClientConnector;
    private String userName = "admin";
    private String password = "admin";

    @BeforeClass
    public void setup() throws InterruptedException {
        setUpClientAndServerConnectors(getListenerConfiguration(), HTTPS_SCHEME);
        httpWsConnectorFactory = new DefaultHttpWsConnectorFactory();
        proxyServerConnector = httpWsConnectorFactory.createServerConnector(TestUtil.getDefaultServerBootstrapConfig(),
                getProxyServerListenerConfiguration());
        ServerConnectorFuture future = proxyServerConnector.start();
        future.setHttpConnectorListener(new EchoMessageListener());
        future.sync();
    }

    @Test (description = "Tests the scenario of a client connecting to a https server through proxy.")
    public void testHttpsProxyServer() {
        String testValue = "Test";
        ByteBuffer byteBuffer = ByteBuffer.wrap(testValue.getBytes(Charset.forName("UTF-8")));
        HTTPCarbonMessage msg = new HttpCarbonRequest(
                new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, ""));
        msg.setProperty(HTTP_METHOD, HttpMethod.POST.toString());
        msg.setProperty(HTTP_PORT, TestUtil.SERVER_PORT3);
        msg.setProperty(PROTOCOL, HTTPS_SCHEME);
        msg.setProperty(HTTP_HOST, TestUtil.TEST_HOST);
        msg.setProperty(HTTP_METHOD, HTTP_POST_METHOD);
        msg.addHttpContent(new DefaultLastHttpContent(Unpooled.wrappedBuffer(byteBuffer)));
        sendRequest(msg, testValue);
    }

    @AfterClass
    public void cleanUp() throws ServerConnectorException {
        try {
            proxyServerConnector.stop();
            serverConnector.stop();
            httpClientConnector.close();
            httpWsConnectorFactory.shutdown();
        } catch (Exception e) {
            logger.warn("Failed to shutdown the servers", e);
        }
    }

    private ListenerConfiguration getListenerConfiguration() {
        ListenerConfiguration listenerConfiguration = ListenerConfiguration.getDefault();
        listenerConfiguration.setPort(TestUtil.SERVER_PORT3);
        listenerConfiguration.setScheme(HTTPS_SCHEME);
        listenerConfiguration.setKeyStoreFile(TestUtil.getAbsolutePath(TestUtil.KEY_STORE_FILE_PATH));
        listenerConfiguration.setKeyStorePass("wso2carbon");
        return listenerConfiguration;
    }

    private ListenerConfiguration getProxyServerListenerConfiguration() {
        ListenerConfiguration listenerConfiguration = ListenerConfiguration.getDefault();
        listenerConfiguration.setPort(TestUtil.SERVER_PORT2);
        listenerConfiguration.setProxy(true);
        listenerConfiguration.setProxyServerPassword(password);
        listenerConfiguration.setProxyServerUserName(userName);
        return listenerConfiguration;
    }

    private void setUpClientAndServerConnectors(ListenerConfiguration listenerConfiguration, String scheme)
            throws InterruptedException {

        ProxyServerConfiguration proxyServerConfiguration = null;
        try {
            proxyServerConfiguration = new ProxyServerConfiguration("localhost", TestUtil.SERVER_PORT2);
            proxyServerConfiguration.setProxyPassword(password);
            proxyServerConfiguration.setProxyUsername(userName);
        } catch (UnknownHostException e) {
            TestUtil.handleException("Failed to resolve host", e);
        }

        TransportsConfiguration transportsConfiguration = new TransportsConfiguration();
        Set<SenderConfiguration> senderConfig = transportsConfiguration.getSenderConfigurations();
        ProxyServerConfiguration finalProxyServerConfiguration = proxyServerConfiguration;
        setSenderConfigs(senderConfig, finalProxyServerConfiguration, scheme);
        httpWsConnectorFactory = new DefaultHttpWsConnectorFactory();

        serverConnector = httpWsConnectorFactory
                .createServerConnector(TestUtil.getDefaultServerBootstrapConfig(), listenerConfiguration);
        ServerConnectorFuture future = serverConnector.start();
        future.setHttpConnectorListener(new EchoMessageListener());
        future.sync();

        httpClientConnector = httpWsConnectorFactory.createHttpClientConnector(new HashMap<>(),
                HTTPConnectorUtil.getSenderConfiguration(transportsConfiguration, scheme));
    }

    private static void setSenderConfigs(Set<SenderConfiguration> senderConfig,
            ProxyServerConfiguration finalProxyServerConfiguration, String scheme) {
        senderConfig.forEach(config -> {
            if (scheme.equals(HTTPS_SCHEME)) {
                config.setTrustStoreFile(TestUtil.getAbsolutePath(TestUtil.KEY_STORE_FILE_PATH));
                config.setTrustStorePass(TestUtil.KEY_STORE_PASSWORD);
                config.setScheme(HTTPS_SCHEME);
            }
            config.setProxyServerConfiguration(finalProxyServerConfiguration);
        });
    }

    private void sendRequest(HTTPCarbonMessage msg, String testValue) {
        try {
            CountDownLatch latch = new CountDownLatch(1);
            HTTPConnectorListener listener = new HTTPConnectorListener(latch);
            HttpResponseFuture responseFuture = httpClientConnector.send(msg);
            responseFuture.setHttpConnectorListener(listener);

            latch.await(30, TimeUnit.SECONDS);

            HTTPCarbonMessage response = listener.getHttpResponseMessage();
            assertNotNull(response);
            String result = new BufferedReader(
                    new InputStreamReader(new HttpMessageDataStreamer(response).getInputStream())).lines()
                    .collect(Collectors.joining("\n"));
            assertEquals(testValue, result);
        } catch (Exception e) {
            TestUtil.handleException("Exception occurred while running testProxyServer", e);
        }
    }
}

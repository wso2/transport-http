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
import org.wso2.transport.http.netty.config.ListenerConfiguration;
import org.wso2.transport.http.netty.contentaware.listeners.EchoMessageListener;
import org.wso2.transport.http.netty.contract.HttpWsConnectorFactory;
import org.wso2.transport.http.netty.contract.ServerConnector;
import org.wso2.transport.http.netty.contract.ServerConnectorException;
import org.wso2.transport.http.netty.contract.ServerConnectorFuture;
import org.wso2.transport.http.netty.contractimpl.DefaultHttpWsConnectorFactory;
import org.wso2.transport.http.netty.message.HTTPCarbonMessage;
import org.wso2.transport.http.netty.util.TestUtil;

import java.nio.ByteBuffer;
import java.nio.charset.Charset;

import static org.wso2.transport.http.netty.common.Constants.HTTP_HOST;
import static org.wso2.transport.http.netty.common.Constants.HTTP_METHOD;
import static org.wso2.transport.http.netty.common.Constants.HTTP_PORT;
import static org.wso2.transport.http.netty.common.Constants.HTTP_POST_METHOD;
import static org.wso2.transport.http.netty.common.Constants.HTTP_SCHEME;
import static org.wso2.transport.http.netty.common.Constants.PROTOCOL;

/**
 * A test case for proxy server handling incoming http requests.
 */
public class HttpProxyServerTestCase {
    private static Logger logger = LoggerFactory.getLogger(HttpProxyServerTestCase.class);

    private HTTPCarbonMessage msg;
    private String testValue = "Test";
    private static ServerConnector serverConnector;
    private static HttpWsConnectorFactory httpWsConnectorFactory;

    @BeforeClass
    public void setup() throws InterruptedException {
        ByteBuffer byteBuffer = ByteBuffer.wrap(testValue.getBytes(Charset.forName("UTF-8")));
        msg = new HTTPCarbonMessage(new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, ""));
        msg.setProperty(HTTP_PORT, TestUtil.SERVER_PORT1);
        msg.setProperty(PROTOCOL, HTTP_SCHEME);
        msg.setProperty(HTTP_HOST, TestUtil.TEST_HOST);
        msg.setProperty(HTTP_METHOD, HTTP_POST_METHOD);
        msg.addHttpContent(new DefaultLastHttpContent(Unpooled.wrappedBuffer(byteBuffer)));

        httpWsConnectorFactory = new DefaultHttpWsConnectorFactory();
        serverConnector = httpWsConnectorFactory.createServerConnector(TestUtil.getDefaultServerBootstrapConfig(),
                getProxyServerListenerConfiguration());
        ServerConnectorFuture future = serverConnector.start();
        future.setHttpConnectorListener(new EchoMessageListener());
        future.sync();

        ProxyServerUtil.setUpClientAndServerConnectors(getListenerConfiguration(), HTTP_SCHEME);
    }

    private ListenerConfiguration getListenerConfiguration() {
        ListenerConfiguration listenerConfiguration = ListenerConfiguration.getDefault();
        listenerConfiguration.setPort(TestUtil.SERVER_PORT1);
        return listenerConfiguration;
    }

    private ListenerConfiguration getProxyServerListenerConfiguration() {
        ListenerConfiguration listenerConfiguration = ListenerConfiguration.getDefault();
        listenerConfiguration.setPort(TestUtil.SERVER_PORT2);
        listenerConfiguration.setProxy(true);
        return listenerConfiguration;
    }

    @Test(description = "An integration test for connecting to a proxy over http. "
            + "This will not go through netty proxy handler.")
    public void testHttpProxyServer() {
        ProxyServerUtil.sendRequest(msg, testValue);
    }

    @AfterClass
    public void cleanUp() throws ServerConnectorException {
        try {
            ProxyServerUtil.shutDown();
            serverConnector.stop();
            httpWsConnectorFactory.shutdown();
        } catch (Exception e) {
            logger.warn("Failed to shutdown the servers", e);
        }
    }
}

/*
 * Copyright (c) 2018, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
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

package org.wso2.transport.http.netty.chunkdisable;

import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpVersion;
import org.wso2.transport.http.netty.common.Constants;
import org.wso2.transport.http.netty.config.SenderConfiguration;
import org.wso2.transport.http.netty.contract.HttpClientConnector;
import org.wso2.transport.http.netty.contract.HttpWsConnectorFactory;
import org.wso2.transport.http.netty.contractimpl.HttpWsConnectorFactoryImpl;
import org.wso2.transport.http.netty.message.HTTPCarbonMessage;
import org.wso2.transport.http.netty.message.HttpMessageDataStreamer;
import org.wso2.transport.http.netty.util.HTTPConnectorListener;
import org.wso2.transport.http.netty.util.TestUtil;
import org.wso2.transport.http.netty.util.server.HttpServer;
import org.wso2.transport.http.netty.util.server.initializers.EchoServerInitializer;

import java.io.IOException;
import java.util.HashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * A test class for auto chunking behaviour.
 */
public class ChunkClientTemplate {

    protected HttpClientConnector clientConnector;
    protected HttpServer httpServer;
    protected SenderConfiguration senderConfiguration;

    public ChunkClientTemplate() {
        senderConfiguration = new SenderConfiguration();
    }

    public void setUp() {
        httpServer = TestUtil.startHTTPServer(TestUtil.HTTP_SERVER_PORT, new EchoServerInitializer());
        HttpWsConnectorFactory httpWsConnectorFactory = new HttpWsConnectorFactoryImpl();
        clientConnector = httpWsConnectorFactory.createHttpClientConnector(new HashMap<>(), senderConfiguration);
    }

    public void postTest() {}

    public HTTPCarbonMessage sendRequest(String content) throws IOException, InterruptedException {
        HTTPCarbonMessage requestMsg = new HTTPCarbonMessage(new DefaultHttpRequest(HttpVersion.HTTP_1_1,
                HttpMethod.POST, ""));

        requestMsg.setProperty(Constants.PORT, TestUtil.HTTP_SERVER_PORT);
        requestMsg.setProperty(Constants.PROTOCOL, Constants.HTTP_SCHEME);
        requestMsg.setProperty(Constants.HOST, "localhost");
        requestMsg.setProperty(Constants.HTTP_METHOD, Constants.HTTP_POST_METHOD);

        CountDownLatch latch = new CountDownLatch(1);
        HTTPConnectorListener listener = new HTTPConnectorListener(latch);
        clientConnector.send(requestMsg).setHttpConnectorListener(listener);
        HttpMessageDataStreamer httpMessageDataStreamer = new HttpMessageDataStreamer(requestMsg);
        httpMessageDataStreamer.getOutputStream().write(content.getBytes());
        httpMessageDataStreamer.getOutputStream().close();

        latch.await(5, TimeUnit.SECONDS);

        HTTPCarbonMessage response = listener.getHttpResponseMessage();
        TestUtil.getStringFromInputStream(new HttpMessageDataStreamer(response).getInputStream());
        return response;
    }
}

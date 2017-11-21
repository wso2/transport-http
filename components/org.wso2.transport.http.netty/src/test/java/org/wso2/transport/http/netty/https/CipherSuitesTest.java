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

package org.wso2.transport.http.netty.https;

import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.DefaultLastHttpContent;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpVersion;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;
import org.wso2.transport.http.netty.common.Constants;
import org.wso2.transport.http.netty.config.ListenerConfiguration;
import org.wso2.transport.http.netty.config.Parameter;
import org.wso2.transport.http.netty.config.SenderConfiguration;
import org.wso2.transport.http.netty.config.TransportsConfiguration;
import org.wso2.transport.http.netty.contentaware.listeners.EchoMessageListener;
import org.wso2.transport.http.netty.contract.HttpClientConnector;
import org.wso2.transport.http.netty.contract.HttpResponseFuture;
import org.wso2.transport.http.netty.contract.HttpWsConnectorFactory;
import org.wso2.transport.http.netty.contract.ServerConnector;
import org.wso2.transport.http.netty.contract.ServerConnectorFuture;
import org.wso2.transport.http.netty.contractimpl.HttpWsConnectorFactoryImpl;
import org.wso2.transport.http.netty.listener.ServerBootstrapConfiguration;
import org.wso2.transport.http.netty.message.HTTPCarbonMessage;
import org.wso2.transport.http.netty.message.HTTPConnectorUtil;
import org.wso2.transport.http.netty.message.HttpMessageDataStreamer;
import org.wso2.transport.http.netty.util.TestUtil;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;
import static org.testng.AssertJUnit.assertNotNull;

/**
 * Tests for different cipher suites provided by client and server.
 */
public class CipherSuitesTest {

    private static HttpClientConnector httpClientConnector;
    private static String testValue = "successful";
    private String keyStoreFile = "src/test/resources/simple-test-config/wso2carbon.jks";
    private String trustStoreFile = "src/test/resources/simple-test-config/client-truststore.jks";
    private String password = "wso2carbon";
    private String scheme = "https";
    private String verifyClient = "require";

    @DataProvider(name = "ciphers")

    public static Object[][] cipherSuites() {

        return new Object[][] {
                // true = expecting a SSL hand shake failure.
                // false = expecting no errors.
                { "TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA256", "TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA256", false, 9099},
                { "TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA, TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA256",
                        "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256", true, 9098 },
                { "TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA, TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA256,"
                        + "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256", "TLS_RSA_WITH_AES_128_GCM_SHA256", true, 9097 }
        };
    }

    @Test(dataProvider = "ciphers")

    /**
     * Set up the client and the server
     * @param clientCiphers ciphers given by client
     * @param serverCiphers ciphers supported by server
     * @param hasException expecting an exception true/false
     * @param serverPort port
     */
    public void setup(String clientCiphers, String serverCiphers, boolean hasException, int serverPort)
            throws InterruptedException {

        Parameter paramClientCiphers = new Parameter("ciphers", clientCiphers);
        List<Parameter> clientParams = new ArrayList<>();
        clientParams.add(paramClientCiphers);

        Parameter paramServerCiphers = new Parameter("ciphers", serverCiphers);
        List<Parameter> serverParams = new ArrayList<>();
        serverParams.add(paramServerCiphers);

        TransportsConfiguration transportsConfiguration = TestUtil
                .getConfiguration("/simple-test-config" + File.separator + "netty-transports.yml");
        Set<SenderConfiguration> senderConfig = transportsConfiguration.getSenderConfigurations();
        senderConfig.forEach(config -> {
            config.setKeyStoreFile(keyStoreFile);
            config.setKeyStorePassword(password);
            config.setParameters(clientParams);
        });

        HttpWsConnectorFactory factory = new HttpWsConnectorFactoryImpl();
        ListenerConfiguration listenerConfiguration = ListenerConfiguration.getDefault();
        listenerConfiguration.setPort(serverPort);
        listenerConfiguration.setVerifyClient(verifyClient);
        listenerConfiguration.setTrustStoreFile(trustStoreFile);
        listenerConfiguration.setKeyStoreFile(keyStoreFile);
        listenerConfiguration.setTrustStorePass(password);
        listenerConfiguration.setKeyStorePass(password);
        listenerConfiguration.setScheme(scheme);
        listenerConfiguration.setParameters(serverParams);

        ServerConnector serverConnector = factory
                .createServerConnector(ServerBootstrapConfiguration.getInstance(), listenerConfiguration);
        ServerConnectorFuture future = serverConnector.start();
        future.setHttpConnectorListener(new EchoMessageListener());
        future.sync();

        httpClientConnector = factory
                .createHttpClientConnector(HTTPConnectorUtil.getTransportProperties(transportsConfiguration),
                        HTTPConnectorUtil.getSenderConfiguration(transportsConfiguration, Constants.HTTPS_SCHEME));

        testCiphersuites(hasException, serverPort);
        serverConnector.stop();
        httpClientConnector.close();
    }

    public void testCiphersuites(boolean hasException, int serverPort) {
        try {
            ByteBuffer byteBuffer = ByteBuffer.wrap(testValue.getBytes(Charset.forName("UTF-8")));
            HTTPCarbonMessage msg = new HTTPCarbonMessage(
                    new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, ""));
            msg.setProperty("PORT", serverPort);
            msg.setProperty("PROTOCOL", scheme);
            msg.setProperty("HOST", TestUtil.TEST_HOST);
            msg.setProperty("HTTP_METHOD", "GET");
            msg.addHttpContent(new DefaultLastHttpContent(Unpooled.wrappedBuffer(byteBuffer)));

            CountDownLatch latch = new CountDownLatch(1);
            SSLConnectorListener listener = new SSLConnectorListener(latch);
            HttpResponseFuture responseFuture = httpClientConnector.send(msg);
            responseFuture.setHttpConnectorListener(listener);
            latch.await(5, TimeUnit.SECONDS);
            HTTPCarbonMessage response = listener.getHttpResponseMessage();

            if (hasException) {
                assertNotNull(listener.getThrowables());
                boolean hasSSLException = false;
                for (Throwable throwable : listener.getThrowables()) {
                    if (throwable.getMessage() != null && throwable.getMessage().contains("handshake_failure")) {
                        hasSSLException = true;
                        break;
                    }
                }
                assertTrue(hasSSLException);
            } else {
                assertNotNull(response);
                String result = new BufferedReader(
                        new InputStreamReader(new HttpMessageDataStreamer(response).getInputStream())).lines()
                        .collect(Collectors.joining("\n"));
                assertEquals(testValue, result);
            }
        } catch (Exception e) {
            TestUtil.handleException("Exception occurred while running testCiphersuites", e);
        }
    }
}

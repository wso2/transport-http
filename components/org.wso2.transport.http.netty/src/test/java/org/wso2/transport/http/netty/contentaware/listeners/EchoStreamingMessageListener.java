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

package org.wso2.transport.http.netty.contentaware.listeners;

import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wso2.transport.http.netty.common.Constants;
import org.wso2.transport.http.netty.contract.HttpConnectorListener;
import org.wso2.transport.http.netty.message.HTTPCarbonMessage;
import org.wso2.transport.http.netty.message.HttpCarbonResponse;
import org.wso2.transport.http.netty.message.HttpMessageDataStreamer;
import org.wso2.transport.http.netty.util.TestUtil;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * A Message processor which echos the incoming message
 */
public class EchoStreamingMessageListener implements HttpConnectorListener {
    private static final Logger logger = LoggerFactory.getLogger(EchoStreamingMessageListener.class);

    private ExecutorService executor = Executors.newSingleThreadExecutor();

    @Override
    public void onMessage(HTTPCarbonMessage httpRequest) {
        executor.execute(() -> {
            try {
                HTTPCarbonMessage httpResponse = new HttpCarbonResponse(new DefaultHttpResponse(HttpVersion.HTTP_1_1,
                        HttpResponseStatus.OK));
                httpResponse.setHeader(HttpHeaderNames.CONNECTION.toString(), HttpHeaderValues.KEEP_ALIVE.toString());
                httpResponse.setHeader(HttpHeaderNames.CONTENT_TYPE.toString(), Constants.TEXT_PLAIN);

                HttpMessageDataStreamer httpMessageDataStreamer = new HttpMessageDataStreamer(httpResponse);
                String inboundPayload = TestUtil
                        .getStringFromInputStream(new HttpMessageDataStreamer(httpRequest).getInputStream());

                httpRequest.respond(httpResponse);
                httpMessageDataStreamer.getOutputStream().write(inboundPayload.getBytes());
                httpMessageDataStreamer.getOutputStream().close();
            } catch (Exception e) {
                logger.error("Error occurred during message notification: " + e.getMessage());
            }
        });
    }

    @Override
    public void onError(Throwable throwable) {

    }
}

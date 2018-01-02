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

package org.wso2.transport.http.netty.contentaware.listeners;

import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpResponseStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wso2.transport.http.netty.common.Constants;
import org.wso2.transport.http.netty.contract.HttpConnectorListener;
import org.wso2.transport.http.netty.contract.ServerConnectorException;
import org.wso2.transport.http.netty.message.HTTPCarbonMessage;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * A Message processor which echos the incoming message
 */
public class EchoMessageListener implements HttpConnectorListener {
    private static final Logger logger = LoggerFactory.getLogger(EchoMessageListener.class);

    private ExecutorService executor = Executors.newSingleThreadExecutor();

    @Override
    public void onMessage(HTTPCarbonMessage httpRequest) {
        executor.execute(() -> {
            try {
                int length = httpRequest.getFullMessageLength();
                HTTPCarbonMessage cMsg = httpRequest.cloneCarbonMessageWithData();
                cMsg.setHeader(HttpHeaderNames.CONNECTION.toString(), HttpHeaderValues.KEEP_ALIVE.toString());
                cMsg.setHeader(HttpHeaderNames.CONTENT_LENGTH.toString(), String.valueOf(length));
                cMsg.setHeader(HttpHeaderNames.CONTENT_TYPE.toString(), Constants.TEXT_PLAIN);
                cMsg.setProperty(Constants.HTTP_STATUS_CODE, HttpResponseStatus.OK.code());
                httpRequest.respond(cMsg);
            } catch (ServerConnectorException e) {
                logger.error("Error occurred during message notification: " + e.getMessage());
            } finally {
                // Calling the release method to make sure that there won't be any memory leaks from netty
                httpRequest.release();
            }
        });
    }

    @Override
    public void onError(Throwable throwable) {

    }
}

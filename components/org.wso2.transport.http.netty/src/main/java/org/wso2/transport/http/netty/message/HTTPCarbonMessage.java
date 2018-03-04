/*
 * Copyright (c) 2015, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.wso2.transport.http.netty.message;

import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMessage;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import org.wso2.transport.http.netty.common.Constants;
import org.wso2.transport.http.netty.contract.HttpResponseFuture;
import org.wso2.transport.http.netty.contract.ServerConnectorException;
import org.wso2.transport.http.netty.contract.ServerConnectorFuture;
import org.wso2.transport.http.netty.contractimpl.DefaultHttpResponseFuture;
import org.wso2.transport.http.netty.contractimpl.HttpWsServerConnectorFuture;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * HTTP based representation for HTTPCarbonMessage.
 */
public class HTTPCarbonMessage {

    protected HttpMessage httpMessage;
    private EntityCollector blockingEntityCollector;
    private Map<String, Object> properties = new HashMap<>();

    private MessageFuture messageFuture;
    private final ServerConnectorFuture httpOutboundRespFuture = new HttpWsServerConnectorFuture();
    private final DefaultHttpResponseFuture httpOutboundRespStatusFuture = new DefaultHttpResponseFuture();

    public HTTPCarbonMessage(HttpMessage httpMessage) {
        this.httpMessage = httpMessage;
        setBlockingEntityCollector(new BlockingEntityCollector(Constants.ENDPOINT_TIMEOUT));
    }

    public HTTPCarbonMessage(HttpMessage httpMessage, int maxWaitTime) {
        this.httpMessage = httpMessage;
        setBlockingEntityCollector(new BlockingEntityCollector(maxWaitTime));
    }

    /**
     * Add http content to HttpCarbonMessage.
     *
     * @param httpContent chunks of the payload.
     */
    public synchronized void addHttpContent(HttpContent httpContent) {
        if (this.messageFuture != null) {
            this.messageFuture.notifyMessageListener(httpContent);
        } else {
            this.blockingEntityCollector.addHttpContent(httpContent);
        }
    }

    /**
     * Get the available content of HttpCarbonMessage.
     *
     * @return HttpContent.
     */
    public HttpContent getHttpContent() {
        return this.blockingEntityCollector.getHttpContent();
    }

    public synchronized MessageFuture getHttpContentAsync() {
        this.messageFuture = new MessageFuture(this);
        return this.messageFuture;
    }

    @Deprecated
    public ByteBuf getMessageBody() {
        return blockingEntityCollector.getMessageBody();
    }

    /**
     * Check if the payload empty.
     *
     * @return true or false.
     */
    public boolean isEmpty() {
        return blockingEntityCollector.isEmpty();
    }

    /**
     * Return the length of entire payload. This is a blocking method.
     * @return the length.
     */
    public int getFullMessageLength() {
        return blockingEntityCollector.getFullMessageLength();
    }

    @Deprecated
    public void addMessageBody(ByteBuffer msgBody) {
        blockingEntityCollector.addMessageBody(msgBody);
    }

    public void completeMessage() {
        blockingEntityCollector.completeMessage();
    }

    /**
     * Returns the header map of the request.
     *
     * @return all headers.
     */
    public HttpHeaders getHeaders() {
        return this.httpMessage.headers();
    }

    /**
     * Return the value of the given header name.
     *
     * @param key name of the header.
     * @return value of the header.
     */
    public String getHeader(String key) {
        return httpMessage.headers().get(key);
    }

    /**
     * Set the header value for the given name.
     *
     * @param key header name.
     * @param value header value.
     */
    public void setHeader(String key, String value) {
        this.httpMessage.headers().set(key, value);
    }

    /**
     * Let you set a set of headers.
     *
     * @param httpHeaders set of headers that needs to be set.
     */
    public void setHeaders(HttpHeaders httpHeaders) {
        this.httpMessage.headers().setAll(httpHeaders);
    }

    /**
     * Adds the user provided headers to the Carbon message. Duplicate headers are allowed.
     *
     * @param httpHeaders Set of headers to be added.
     */
    public void addHeaders(HttpHeaders httpHeaders) {
        this.httpMessage.headers().add(httpHeaders);
    }

    /**
     * Remove the header using header name.
     *
     * @param key header name.
     */
    public void removeHeader(String key) {
        httpMessage.headers().remove(key);
    }

    public Object getProperty(String key) {
        if (properties != null) {
            return properties.get(key);
        } else {
            return null;
        }
    }

    public Map<String, Object> getProperties() {
        return properties;
    }

    public void setProperty(String key, Object value) {
        properties.put(key, value);
    }

    public void removeProperty(String key) {
        properties.remove(key);
    }

    private void setBlockingEntityCollector(BlockingEntityCollector blockingEntityCollector) {
        this.blockingEntityCollector = blockingEntityCollector;
    }

    /**
     * Returns the future responsible for sending back the response.
     *
     * @return httpOutboundRespFuture.
     */
    public ServerConnectorFuture getHttpResponseFuture() {
        return this.httpOutboundRespFuture;
    }

    /**
     * Returns the future responsible for notifying the response status.
     *
     * @return httpOutboundRespStatusFuture.
     */
    public HttpResponseFuture getHttpOutboundRespStatusFuture() {
        return httpOutboundRespStatusFuture;
    }

    public HttpResponseFuture respond(HTTPCarbonMessage httpCarbonMessage) throws ServerConnectorException {
        httpOutboundRespFuture.notifyHttpListener(httpCarbonMessage);
        return httpOutboundRespStatusFuture;
    }

    /**
     * Copy Message properties and transport headers.
     *
     * @return HTTPCarbonMessage.
     */
    public HTTPCarbonMessage cloneCarbonMessageWithOutData() {
        HTTPCarbonMessage newCarbonMessage = getNewHttpCarbonMessage();

        Map<String, Object> propertiesMap = this.getProperties();
        propertiesMap.forEach(newCarbonMessage::setProperty);

        return newCarbonMessage;
    }

    private HTTPCarbonMessage getNewHttpCarbonMessage() {
        HttpMessage newHttpMessage;
        HttpHeaders httpHeaders;
        if (this.httpMessage instanceof HttpRequest) {
            HttpRequest httpRequest = (HttpRequest) this.httpMessage;
            newHttpMessage = new DefaultHttpRequest(this.httpMessage.protocolVersion(),
                    ((HttpRequest) this.httpMessage).method(), httpRequest.uri());

            httpHeaders = new DefaultHttpHeaders();
            List<Map.Entry<String, String>> headerList = this.httpMessage.headers().entries();
            for (Map.Entry<String, String> entry : headerList) {
                httpHeaders.add(entry.getKey(), entry.getValue());
            }
        } else {
            HttpResponse httpResponse = (HttpResponse) this.httpMessage;
            newHttpMessage = new DefaultFullHttpResponse(this.httpMessage.protocolVersion(), httpResponse.status());

            httpHeaders = new DefaultHttpHeaders();
            List<Map.Entry<String, String>> headerList = this.httpMessage.headers().entries();
            for (Map.Entry<String, String> entry : headerList) {
                httpHeaders.add(entry.getKey(), entry.getValue());
            }
        }
        HTTPCarbonMessage httpCarbonMessage = new HTTPCarbonMessage(newHttpMessage);
        httpCarbonMessage.getHeaders().set(httpHeaders);
        return httpCarbonMessage;
    }

    /**
     * Wait till the entire payload is received. This is important to avoid data corruption.
     * Before a set a new set of payload, we need remove the existing ones.
     */
    public void waitAndReleaseAllEntities() {
        blockingEntityCollector.waitAndReleaseAllEntities();
    }

    public EntityCollector getBlockingEntityCollector() {
        return blockingEntityCollector;
    }

    public synchronized void removeHttpContentAsyncFuture() {
        this.messageFuture = null;
    }

    /**
     * Gives the underling netty request message.
     * @return netty request message
     */
    public HttpRequest getNettyHttpRequest() {
        return (HttpRequest) this.httpMessage;
    }

    /**
     * Gives the underling netty response message.
     * @return netty response message
     */
    public HttpResponse getNettyHttpResponse() {
        return (HttpResponse) this.httpMessage;
    }
}

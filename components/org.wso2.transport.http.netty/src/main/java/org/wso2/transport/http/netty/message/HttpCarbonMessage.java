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
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.DefaultLastHttpContent;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMessage;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.LastHttpContent;
import org.wso2.transport.http.netty.contract.Constants;
import org.wso2.transport.http.netty.contract.HttpResponseFuture;
import org.wso2.transport.http.netty.contract.ServerConnectorException;
import org.wso2.transport.http.netty.contract.ServerConnectorFuture;
import org.wso2.transport.http.netty.contractimpl.DefaultHttpResponseFuture;
import org.wso2.transport.http.netty.contractimpl.HttpWsServerConnectorFuture;
import org.wso2.transport.http.netty.contractimpl.common.Util;
import org.wso2.transport.http.netty.contractimpl.common.states.MessageStateContext;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;

/**
 * HTTP based representation for HttpCarbonMessage.
 */
public class HttpCarbonMessage {

    protected HttpMessage httpMessage;
    private EntityCollector blockingEntityCollector;
    private Map<String, Object> properties = new HashMap<>();

    private MessageFuture messageFuture;
    private final ServerConnectorFuture httpOutboundRespFuture = new HttpWsServerConnectorFuture();
    private final DefaultHttpResponseFuture httpOutboundRespStatusFuture = new DefaultHttpResponseFuture();
    private final Observable contentObservable = new DefaultObservable();
    private Semaphore writingBlocker;
    private IOException ioException;
    private MessageStateContext messageStateContext;


    private long sequenceId; //Keep track of request/response order
    private ChannelHandlerContext sourceContext;
    private ChannelHandlerContext targetContext;
    private HttpPipeliningFuture pipeliningFuture;
    private boolean keepAlive;
    private boolean pipeliningNeeded;
    private boolean passthrough = false;

    public HttpCarbonMessage(HttpMessage httpMessage, Listener contentListener) {
        this.httpMessage = httpMessage;
        setBlockingEntityCollector(new BlockingEntityCollector(Constants.ENDPOINT_TIMEOUT));
        this.contentObservable.setListener(contentListener);
    }

    public HttpCarbonMessage(HttpMessage httpMessage, int maxWaitTime, Listener contentListener) {
        this.httpMessage = httpMessage;
        setBlockingEntityCollector(new BlockingEntityCollector(maxWaitTime));
        this.contentObservable.setListener(contentListener);
    }

    public HttpCarbonMessage(HttpMessage httpMessage) {
        this.httpMessage = httpMessage;
        setBlockingEntityCollector(new BlockingEntityCollector(Constants.ENDPOINT_TIMEOUT));
    }
    /**
     * Add http content to HttpCarbonMessage.
     *
     * @param httpContent chunks of the payload.
     */
    public synchronized void addHttpContent(HttpContent httpContent) {
        contentObservable.notifyAddListener(httpContent);
        if (messageFuture != null) {
            if (ioException != null) {
                blockingEntityCollector.addHttpContent(new DefaultLastHttpContent());
                messageFuture.notifyMessageListener(blockingEntityCollector.getHttpContent());
                removeMessageFuture();
                throw new RuntimeException(this.getIoException());
            }
            blockingEntityCollector.addHttpContent(httpContent);
            if (messageFuture.isMessageListenerSet() && isWritableDuringPasssthrough()) {
                notifyListener();
            }
        } else {
            if (ioException != null) {
                blockingEntityCollector.addHttpContent(new DefaultLastHttpContent());
                throw new RuntimeException(this.getIoException());
            } else {
                blockingEntityCollector.addHttpContent(httpContent);
            }
        }
    }

    /**
     * Checks if it is a passthrough and writingBlocker and targetContext are not null before checking for writability.
     *
     * @return false if the channel is not writable in a passthrough scenario or if targetContext is not null or when
     * the {@link org.wso2.transport.http.netty.contractimpl.common.OutboundThrottlingHandler} is not in the pipeline.
     */
    private boolean isWritableDuringPasssthrough() {
        return !passthrough || writingBlocker == null || targetContext == null || targetContext.channel().isWritable();
    }

    /**
     * To notify the listener of the messageFuture to write the data to the socket.
     */
    public synchronized void notifyListener() {
        //we shouldn't retrieve http content if messageFuture is null
        if (messageFuture != null) {
            HttpContent httpContent = getHttpContent();
            messageFuture.notifyMessageListener(httpContent);
            if (httpContent instanceof LastHttpContent) {
                removeMessageFuture();
                passthrough = false;
            }
        }
    }

    /**
     * Get the available content of HttpCarbonMessage.
     *
     * @return HttpContent.
     */
    public HttpContent getHttpContent() {
        HttpContent httpContent = this.blockingEntityCollector.getHttpContent();
        this.contentObservable.notifyGetListener(httpContent);
        return httpContent;
    }

    public synchronized MessageFuture getHttpContentAsync() {
        this.messageFuture = new MessageFuture(this);
        return this.messageFuture;
    }

    /**
     * @deprecated
     * @return the message body.
     */
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
     * Count the message length till the given message length and returns.
     * If the message length is shorter than the given length it returns with the
     * available message size. This method is blocking function. Hence, use with care.
     * @param maxLength is the maximum length to count
     * @return counted length
     */
    public long countMessageLengthTill(long maxLength) {
        return this.blockingEntityCollector.countMessageLengthTill(maxLength);
    }

    /**
     * Return the length of entire payload. This is a blocking method.
     * @return the length.
     */
    public long getFullMessageLength() {
        return blockingEntityCollector.getFullMessageLength();
    }

    /**
     * @deprecated
     * @param msgBody the message body.
     */
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
     * Set the header value for the given name.
     *
     * @param key header name.
     * @param value header value as object.
     */
    public void setHeader(String key, Object value) {
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

    /**
     * Removes the messageFuture when the message has reached it life time. If there is a need using the same message.
     * again, future has to be set again and life-cycle restarted.
     */
    public synchronized void removeMessageFuture() {
        messageFuture = null;
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

    public HttpResponseFuture respond(HttpCarbonMessage httpCarbonMessage) throws ServerConnectorException {
        httpOutboundRespFuture.notifyHttpListener(httpCarbonMessage);
        //Copies the source context from the request to the response. Required for outbound throttling.
        httpCarbonMessage.setSourceContext(sourceContext);
        Util.setHttpCarbonMessageToOutboundThrottlingHandler(httpCarbonMessage, sourceContext);
        return httpOutboundRespStatusFuture;
    }

    /**
     * Sends a push response message back to the client.
     *
     * @param httpCarbonMessage the push response message
     * @param pushPromise       the push promise associated with the push response message
     * @return HttpResponseFuture which gives the status of the operation
     * @throws ServerConnectorException if there is an error occurs while doing the operation
     */
    public HttpResponseFuture pushResponse(HttpCarbonMessage httpCarbonMessage, Http2PushPromise pushPromise)
            throws ServerConnectorException {
        httpOutboundRespFuture.notifyHttpListener(httpCarbonMessage, pushPromise);
        return httpOutboundRespStatusFuture;
    }

    /**
     * Sends a push promise message back to the client.
     *
     * @param pushPromise the push promise message
     * @return HttpResponseFuture which gives the status of the operation
     * @throws ServerConnectorException if there is an error occurs while doing the operation
     */
    public HttpResponseFuture pushPromise(Http2PushPromise pushPromise)
            throws ServerConnectorException {
        httpOutboundRespFuture.notifyHttpListener(pushPromise);
        return httpOutboundRespStatusFuture;
    }

    /**
     * Copy Message properties and transport headers.
     *
     * @return HttpCarbonMessage.
     */
    public HttpCarbonMessage cloneCarbonMessageWithOutData() {
        HttpCarbonMessage newCarbonMessage = getNewHttpCarbonMessage();

        Map<String, Object> propertiesMap = this.getProperties();
        propertiesMap.forEach(newCarbonMessage::setProperty);

        return newCarbonMessage;
    }

    private HttpCarbonMessage getNewHttpCarbonMessage() {
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
        HttpCarbonMessage httpCarbonMessage = new HttpCarbonMessage(newHttpMessage);
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

    /**
     * Gives the underling netty request message.
     * @return netty request message
     */
    public HttpRequest getNettyHttpRequest() {
        if (httpMessage instanceof HttpRequest) {
            return (HttpRequest) this.httpMessage;
        }
        return null;
    }

    /**
     * Gives the underling netty response message.
     * @return netty response message
     */
    public HttpResponse getNettyHttpResponse() {
        if (httpMessage instanceof HttpResponse) {
            return (HttpResponse) this.httpMessage;
        }
        return null;
    }

    public synchronized IOException getIoException() {
        return ioException;
    }

    public synchronized void setIoException(IOException ioException) {
        this.ioException = ioException;
    }

    public MessageStateContext getMessageStateContext() {
        return messageStateContext;
    }

    public void setMessageStateContext(MessageStateContext messageStateContext) {
        this.messageStateContext = messageStateContext;
    }

    public long getSequenceId() {
        return sequenceId;
    }

    public void setSequenceId(long sequenceId) {
        this.sequenceId = sequenceId;
    }

    /**
     * @return the source handler context for this message.
     */
    public ChannelHandlerContext getSourceContext() {
        return sourceContext;
    }

    /**
     * @param sourceContext the source handler context for this message.
     */
    public void setSourceContext(ChannelHandlerContext sourceContext) {
        this.sourceContext = sourceContext;
    }

    /**
     * @return the target handler context for this message.
     */
    public ChannelHandlerContext getTargetContext() {
        return targetContext;
    }

    /**
     * @param targetContext The target handler context.
     */
    public void setTargetContext(ChannelHandlerContext targetContext) {
        this.targetContext = targetContext;
    }

    /**
     * @return the sourceContext if this message is {@link HttpCarbonRequest} and targetContext if this message is
     * {@link HttpCarbonResponse}
     */
    public ChannelHandlerContext getChannelContext() {
        if (httpMessage instanceof HttpRequest) {
            return targetContext;
        } else if (httpMessage instanceof HttpResponse) {
            return sourceContext;
        }
        return null;
    }

    public boolean isKeepAlive() {
        return keepAlive;
    }

    public void setKeepAlive(boolean keepAlive) {
        this.keepAlive = keepAlive;
    }

    public boolean isPipeliningNeeded() {
        return pipeliningNeeded;
    }

    public void setPipeliningNeeded(boolean pipeliningNeeded) {
        this.pipeliningNeeded = pipeliningNeeded;
    }

    public HttpPipeliningFuture getPipeliningFuture() {
        return pipeliningFuture;
    }

    public void setPipeliningFuture(HttpPipeliningFuture pipeliningFuture) {
        this.pipeliningFuture = pipeliningFuture;
    }

    /**
     * @return the semaphore used for outbound throttling.
     */
    public Semaphore getWritingBlocker() {
        return writingBlocker;
    }

    /**
     * This semaphore is set in the
     * {@link org.wso2.transport.http.netty.contractimpl.common.OutboundThrottlingHandler} otherwise it should be null.
     *
     * @param writingBlocker the semaphore used for outbound throttling.
     */
    public void setWritingBlocker(Semaphore writingBlocker) {
        this.writingBlocker = writingBlocker;
    }

    /**
     * @return true if it is a passthrough(when message body is not built).
     */
    public synchronized boolean isPassthrough() {
        return passthrough;
    }

    /**
     * This value is to be set when the message is to be sent to the consumer without building/processing the message
     * in the application layer.
     *
     * @param passthrough if the message is a passthrough.
     */
    public synchronized void setPassthrough(boolean passthrough) {
        this.passthrough = passthrough;
    }
}

/*
 *  Copyright (c) 2015 WSO2 Inc. (http://wso2.com) All Rights Reserved.
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
package org.wso2.transport.http.netty.internal;

import io.netty.channel.EventLoopGroup;
import org.osgi.framework.BundleContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wso2.carbon.messaging.CarbonMessageProcessor;
import org.wso2.carbon.messaging.TransportListenerManager;
import org.wso2.transport.http.netty.config.ListenerConfiguration;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * DataHolder for the HTTP transport.
 */
public class HTTPTransportContextHolder {
    private static final Logger log = LoggerFactory.getLogger(HTTPTransportContextHolder.class);

    private static HTTPTransportContextHolder instance = new HTTPTransportContextHolder();
    private BundleContext bundleContext;
    private HandlerExecutor handlerExecutor;
    private Map<String, ListenerConfiguration> listenerConfigurations = new HashMap<>();
    private TransportListenerManager manager;
    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private CarbonMessageProcessor singleCarbonMessageProcessor;
    private Map<String, CarbonMessageProcessor> messageProcessorMap = new ConcurrentHashMap<>();
    public EventLoopGroup getBossGroup() {
        return bossGroup;
    }

    public void setBossGroup(EventLoopGroup bossGroup) {
        this.bossGroup = bossGroup;
    }

    public EventLoopGroup getWorkerGroup() {
        return workerGroup;
    }

    public void setWorkerGroup(EventLoopGroup workerGroup) {
        this.workerGroup = workerGroup;
    }

    public ListenerConfiguration getListenerConfiguration(String id) {
        return listenerConfigurations.get(id);
    }

    public void setListenerConfiguration(String id, ListenerConfiguration config) {
        listenerConfigurations.put(id, config);
    }

    private HTTPTransportContextHolder() {

    }

    public static HTTPTransportContextHolder getInstance() {
        return instance;
    }

    public void setBundleContext(BundleContext bundleContext) {
        this.bundleContext = bundleContext;
    }

    public BundleContext getBundleContext() {
        return this.bundleContext;
    }

    public CarbonMessageProcessor getMessageProcessor(String id) {
        CarbonMessageProcessor carbonMessageProcessor = singleCarbonMessageProcessor;
        if (carbonMessageProcessor != null) {
            return carbonMessageProcessor;
        } else if (id == null) {
            log.error("More than one message processor has registered and cannot proceed with 'null' message " +
                    "processor ID.");
            return null;
        } else {
            return messageProcessorMap.get(id);
        }
    }

    public void setMessageProcessor(CarbonMessageProcessor carbonMessageProcessor) {
        if (carbonMessageProcessor.getId() == null) {
            throw new IllegalStateException("Message processor ID cannot be 'null'");
        }
        messageProcessorMap.put(carbonMessageProcessor.getId(), carbonMessageProcessor);
        if (messageProcessorMap.size() == 1) {
            singleCarbonMessageProcessor = carbonMessageProcessor;
        } else {
            singleCarbonMessageProcessor = null;
        }
    }

    public void removeMessageProcessor(CarbonMessageProcessor carbonMessageProcessor) {
        if (messageProcessorMap.size() > 1) {
            messageProcessorMap.remove(carbonMessageProcessor.getId());
        } else {
            messageProcessorMap.clear();
            singleCarbonMessageProcessor = null;
        }
    }

    public TransportListenerManager getManager() {
        return manager;
    }

    public void removeManager() {
        manager = null;
    }

    public void setManager(TransportListenerManager manager) {
        this.manager = manager;
    }

    public void setHandlerExecutor(HandlerExecutor handlerExecutor) {
        this.handlerExecutor = handlerExecutor;
    }

    public HandlerExecutor getHandlerExecutor() {
        return handlerExecutor;
    }
}

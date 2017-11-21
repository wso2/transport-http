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

package org.wso2.transport.http.netty.contractimpl.websocket;

import org.wso2.transport.http.netty.contract.websocket.HandshakeFuture;
import org.wso2.transport.http.netty.contract.websocket.WebSocketClientConnector;
import org.wso2.transport.http.netty.contract.websocket.WebSocketConnectorListener;
import org.wso2.transport.http.netty.contract.websocket.WsClientConnectorConfig;
import org.wso2.transport.http.netty.sender.websocket.WebSocketClient;

import java.util.Map;

/**
 * Implementation of WebSocket client connector.
 */
public class WebSocketClientConnectorImpl implements WebSocketClientConnector {

    private final String remoteUrl;
    private final String subProtocols;
    private final String target;
    private final int idleTimeout;
    private final Map<String, String> customHeaders;

    public WebSocketClientConnectorImpl(WsClientConnectorConfig clientConnectorConfig) {
        this.remoteUrl = clientConnectorConfig.getRemoteAddress();
        this.target = clientConnectorConfig.getTarget();
        this.subProtocols = clientConnectorConfig.getSubProtocolsAsCSV();
        this.customHeaders = clientConnectorConfig.getHeaders();
        this.idleTimeout = clientConnectorConfig.getIdleTimeoutInMillis();
    }

    @Override
    public HandshakeFuture connect(WebSocketConnectorListener connectorListener) {
        WebSocketClient webSocketClient = new WebSocketClient(remoteUrl, target, subProtocols, idleTimeout,
                                                              customHeaders, connectorListener);
        return webSocketClient.handshake();
    }
}

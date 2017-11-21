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

package org.wso2.transport.http.netty.contract;

import org.wso2.transport.http.netty.message.HTTPCarbonMessage;

/**
 * Allows to send outbound messages.
 */
public interface HttpClientConnector {
    /**
     * Creates the connection to the back-end.
     * @return the future that can be used to get future events of the connection.
     */
    HttpResponseFuture connect();

    /**
     * Send httpMessages to the back-end in asynchronous manner.
     *
     * @param httpCarbonMessage {@link HTTPCarbonMessage} which should be sent to the remote server.
     * @return returns the status of the asynchronous send action.
     */
    HttpResponseFuture send(HTTPCarbonMessage httpCarbonMessage);

    /**
     * Close the connection related to this connector.
     * @return return the status of the close action.
     */
    boolean close();
}

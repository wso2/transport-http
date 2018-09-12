/*
 *  Copyright (c) 2018, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 *  WSO2 Inc. licenses this file to you under the Apache License,
 *  Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied.  See the License for the
 *  specific language governing permissions and limitations
 *  under the License.
 */

package org.wso2.transport.http.netty.contractimpl.common;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wso2.transport.http.netty.message.HttpCarbonMessage;

import java.util.concurrent.Semaphore;

/**
 * The class for handling outbound throttling.
 * The class overrides the channelWritabilityChanged method to check the writability of the channel which is needed
 * for outbound throttling. Further if this handler is not in the pipeline then the writingBlocker semaphore will not
 * be set in the carbonMessage.
 */
public class OutboundThrottlingHandler extends ChannelInboundHandlerAdapter {
    private static final Logger LOG = LoggerFactory.getLogger(OutboundThrottlingHandler.class);

    private HttpCarbonMessage httpCarbonMessage = null;

    @Override
    public void channelWritabilityChanged(ChannelHandlerContext ctx) {
        if (ctx.channel().isWritable() && httpCarbonMessage != null) {
            if (!httpCarbonMessage.isPassthrough()) {
                releaseWritingBlocker();
            } else {
                while (!httpCarbonMessage.isEmpty() && ctx.channel().isWritable()) {
                    httpCarbonMessage.notifyListener();
                }
            }
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        if (httpCarbonMessage != null && !httpCarbonMessage.isPassthrough()) {
            releaseWritingBlocker();
        }
        ctx.fireChannelInactive();
    }

    /**
     * Sets the {@link HttpCarbonMessage} in the pipeline and creates a new semaphore the the message.
     *
     * @param httpCarbonMessage the {@link HttpCarbonMessage} in the pipeline
     */
    public void setHttpCarbonMessage(HttpCarbonMessage httpCarbonMessage) {
        this.httpCarbonMessage = httpCarbonMessage;
        httpCarbonMessage.setWritingBlocker(new Semaphore(0));
    }

    /**
     * Releases the semaphore in the httpCarbonMessage.
     */
    private void releaseWritingBlocker() {
        httpCarbonMessage.getWritingBlocker().release();
        if (LOG.isDebugEnabled() && httpCarbonMessage.getChannelContext() != null) {
            LOG.debug("Semaphore released for channel {}.", httpCarbonMessage.getChannelContext().channel().id());
        }
    }
}

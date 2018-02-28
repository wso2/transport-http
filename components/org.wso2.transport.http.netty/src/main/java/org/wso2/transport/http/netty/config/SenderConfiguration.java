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
package org.wso2.transport.http.netty.config;

import org.wso2.transport.http.netty.common.ProxyServerConfiguration;
import org.wso2.transport.http.netty.common.Util;
import org.wso2.transport.http.netty.common.ssl.SSLConfig;
import org.wso2.transport.http.netty.sender.channel.pool.PoolConfiguration;

import java.util.ArrayList;
import java.util.List;
import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import javax.xml.bind.annotation.XmlAttribute;
import javax.xml.bind.annotation.XmlElement;
import javax.xml.bind.annotation.XmlElementWrapper;


/**
 * JAXB representation of the Netty transport sender configuration.
 */
@SuppressWarnings("unused")
@XmlAccessorType(XmlAccessType.FIELD)
public class SenderConfiguration {

    private static final String DEFAULT_KEY = "netty";

    @Deprecated
    public static SenderConfiguration getDefault() {
        SenderConfiguration defaultConfig;
        defaultConfig = new SenderConfiguration(DEFAULT_KEY);
        return defaultConfig;
    }

    @XmlAttribute(required = true)
    private String id = DEFAULT_KEY;

    @XmlAttribute
    private String scheme = "http";

    @XmlAttribute
    private String keyStoreFile;

    @XmlAttribute
    private String keyStorePassword;

    @XmlAttribute
    private String trustStoreFile;

    @XmlAttribute
    private String trustStorePass;

    @XmlAttribute
    private String certPass;

    @XmlAttribute
    private int socketIdleTimeout = 60000;

    @XmlAttribute
    private boolean httpTraceLogEnabled;

    private ChunkConfig chunkingConfig = ChunkConfig.AUTO;

    @XmlAttribute
    private String sslProtocol;

    @XmlElementWrapper(name = "parameters")
    @XmlElement(name = "parameter")
    private List<Parameter> parameters = new ArrayList<>();

    @XmlAttribute
    private boolean followRedirect;

    @XmlAttribute
    private int maxRedirectCount;

    @XmlAttribute
    private boolean isKeepAlive = true;

    @XmlAttribute
    private boolean skipHttpToHttp2Upgrade = false;

    @XmlAttribute
    private int http2MaxActiveStreams = Integer.MAX_VALUE;

    private String tlsStoreType;
    private String httpVersion = "1.1";
    private ProxyServerConfiguration proxyServerConfiguration;
    private PoolConfiguration poolConfiguration;
    private boolean validateCertEnabled;
    private int cacheSize = 50;
    private int cacheValidityPeriod = 15;
    private boolean hostNameVerificationEnabled = true;

    public SenderConfiguration() {
        this.poolConfiguration = new PoolConfiguration();
    }

    public SenderConfiguration(String id) {
        this.id = id;
    }

    public void setSSLProtocol(String sslProtocol) {
        this.sslProtocol = sslProtocol;
    }

    public String getSSLProtocol() {
        return sslProtocol;
    }

    public String getCertPass() {
        return certPass;
    }

    public String getTLSStoreType() {
        return tlsStoreType;
    }

    public void setTLSStoreType(String storeType) {
        this.tlsStoreType = storeType;
    }

    public void setCertPass(String certPass) {
        this.certPass = certPass;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getKeyStoreFile() {
        return keyStoreFile;
    }

    public void setKeyStoreFile(String keyStoreFile) {
        this.keyStoreFile = keyStoreFile;
    }

    public String getKeyStorePassword() {
        return keyStorePassword;
    }

    public void setKeyStorePassword(String keyStorePassword) {
        this.keyStorePassword = keyStorePassword;
    }

    public String getScheme() {
        return scheme;
    }

    public void setScheme(String scheme) {
        this.scheme = scheme;
    }

    public List<Parameter> getParameters() {
        return parameters;
    }

    public void setParameters(List<Parameter> parameters) {
        this.parameters = parameters;
    }

    public String getTrustStoreFile() {
        return trustStoreFile;
    }

    public void setTrustStoreFile(String trustStoreFile) {
        this.trustStoreFile = trustStoreFile;
    }

    public String getTrustStorePass() {
        return trustStorePass;
    }

    public void setTrustStorePass(String trustStorePass) {
        this.trustStorePass = trustStorePass;
    }

    public SSLConfig getSSLConfig() {
        if (scheme == null || !scheme.equalsIgnoreCase("https")) {
            return null;
        }
        return Util.getSSLConfigForSender(certPass, keyStorePassword, keyStoreFile, trustStoreFile, trustStorePass,
                                          parameters, sslProtocol, tlsStoreType);
    }

    public int getSocketIdleTimeout(int defaultValue) {
        if (socketIdleTimeout == 0) {
            return defaultValue;
        }
        return socketIdleTimeout;
    }

    public void setSocketIdleTimeout(int socketIdleTimeout) {
        this.socketIdleTimeout = socketIdleTimeout;
    }

    public boolean isHttpTraceLogEnabled() {
        return httpTraceLogEnabled;
    }

    public void setHttpTraceLogEnabled(boolean httpTraceLogEnabled) {
        this.httpTraceLogEnabled = httpTraceLogEnabled;
    }

    public ChunkConfig getChunkingConfig() {
        return chunkingConfig;
    }

    public void setChunkingConfig(ChunkConfig chunkingConfig) {
        this.chunkingConfig = chunkingConfig;
    }

    public boolean isFollowRedirect() {
        return followRedirect;
    }

    public void setFollowRedirect(boolean followRedirect) {
        this.followRedirect = followRedirect;
    }

    public int getMaxRedirectCount(int defaultValue) {
        if (maxRedirectCount == 0) {
            return defaultValue;
        }
        return maxRedirectCount;
    }

    public void setMaxRedirectCount(int maxRedirectCount) {
        this.maxRedirectCount = maxRedirectCount;
    }

    public boolean isKeepAlive() {
        return isKeepAlive;
    }

    public void setKeepAlive(boolean keepAlive) {
        isKeepAlive = keepAlive;
    }

    public void setProxyServerConfiguration(ProxyServerConfiguration proxyServerConfiguration) {
        this.proxyServerConfiguration = proxyServerConfiguration;
    }

    public ProxyServerConfiguration getProxyServerConfiguration() {
        return proxyServerConfiguration;
    }

    public String getHttpVersion() {
        return httpVersion;
    }

    public void setHttpVersion(String httpVersion) {
        if (!httpVersion.isEmpty()) {
            this.httpVersion = httpVersion;
        }
    }

    public boolean skipHttpToHttp2Upgrade() {
        return skipHttpToHttp2Upgrade;
    }

    public void setSkipHttpToHttp2Upgrade(boolean skipHttpToHttp2Upgrade) {
        this.skipHttpToHttp2Upgrade = skipHttpToHttp2Upgrade;
    }

    public int getHttp2MaxActiveStreams() {
        return http2MaxActiveStreams;
    }

    public void setHttp2MaxActiveStreams(int http2MaxActiveStreams) {
        this.http2MaxActiveStreams = http2MaxActiveStreams;
    }

    public void setValidateCertEnabled(boolean validateCertEnabled) {
        this.validateCertEnabled = validateCertEnabled;
    }

    public void setCacheSize(int cacheSize) {
        this.cacheSize = cacheSize;
    }

    public void setCacheValidityPeriod(int cacheValidityPeriod) {
        this.cacheValidityPeriod = cacheValidityPeriod;
    }

    public boolean validateCertEnabled() {
        return validateCertEnabled;
    }

    public int getCacheSize() {
        return cacheSize;
    }

    public void setHostNameVerificationEnabled(boolean hostNameVerificationEnabled) {
        this.hostNameVerificationEnabled = hostNameVerificationEnabled;
    }

    public boolean hostNameVerificationEnabled() {
        return hostNameVerificationEnabled;
    }

    public int getCacheValidityPeriod() {
        return cacheValidityPeriod;
    }

    public PoolConfiguration getPoolConfiguration() {
        return poolConfiguration;
    }

    public void setPoolConfiguration(PoolConfiguration poolConfiguration) {
        this.poolConfiguration = poolConfiguration;
    }
}

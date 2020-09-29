/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.nifi.security.util;

import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Optional;
import java.util.stream.Collectors;
import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import org.apache.commons.lang3.builder.ToStringBuilder;
import org.apache.commons.lang3.builder.ToStringStyle;
import org.apache.nifi.util.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A factory for creating SSL contexts using the application's security properties. By requiring callers to bundle
 * the properties in a {@link TlsConfiguration} container object, much better validation and property matching can
 * occur. The {@code public} methods are designed for easy use, while the {@code protected} methods provide more
 * granular (but less common) access to intermediate objects if required.
 */
public final class SslContextFactory {
    private static final Logger logger = LoggerFactory.getLogger(SslContextFactory.class);

    /**
     * This enum is used to indicate the three possible options for a server requesting a client certificate during TLS handshake negotiation.
     */
    public enum ClientAuth {
        WANT("Want", "Requests the client certificate on handshake and validates if present but does not require it"),
        REQUIRED("Required", "Requests the client certificate on handshake and rejects the connection if it is not present and valid"),
        NONE("None", "Does not request the client certificate on handshake");

        private final String type;
        private final String description;

        ClientAuth(String type, String description) {
            this.type = type;
            this.description = description;
        }

        public String getType() {
            return this.type;
        }

        public String getDescription() {
            return this.description;
        }

        @Override
        public String toString() {
            final ToStringBuilder builder = new ToStringBuilder(this);
            ToStringBuilder.setDefaultStyle(ToStringStyle.SHORT_PREFIX_STYLE);
            builder.append("Type", type);
            builder.append("Description", description);
            return builder.toString();
        }

        public static boolean isValidClientAuthType(String type) {
            if (StringUtils.isBlank(type)) {
                return false;
            }
            return (Arrays.stream(values()).map(ca -> ca.getType().toLowerCase()).collect(Collectors.toList()).contains(type.toLowerCase()));
        }
    }

    /**
     * Returns a configured {@link SSLContext} from the provided TLS configuration. Hardcodes the
     * client auth setting to {@link ClientAuth#REQUIRED} because this method is usually used when
     * creating a context for a client, which ignores the setting anyway.
     *
     * @param tlsConfiguration the TLS configuration container object
     * @return the configured SSLContext
     * @throws TlsException if there is a problem configuring the SSLContext
     */
    public static SSLContext createSslContext(TlsConfiguration tlsConfiguration) throws TlsException {
        return createSslContext(tlsConfiguration, ClientAuth.REQUIRED);
    }

    /**
     * Returns a configured {@link SSLContext} from the provided TLS configuration.
     *
     * @param tlsConfiguration the TLS configuration container object
     * @param clientAuth       the {@link ClientAuth} setting
     * @return the configured SSLContext
     * @throws TlsException if there is a problem configuring the SSLContext
     */
    public static SSLContext createSslContext(TlsConfiguration tlsConfiguration, ClientAuth clientAuth) throws TlsException {
        // If the object is null or neither keystore nor truststore properties are present, return null
        if (TlsConfiguration.isEmpty(tlsConfiguration)) {
            logger.debug("Cannot create SSLContext from empty TLS configuration; returning null");
            return null;
        }

        // If the keystore properties are present, truststore properties are required to be present as well
        if (tlsConfiguration.isKeystorePopulated() && !tlsConfiguration.isTruststorePopulated()) {
            logger.error("The TLS config keystore properties were populated but the truststore properties were not");
            if (logger.isDebugEnabled()) {
                logger.debug("Provided TLS config: {}", tlsConfiguration);
            }
            throw new TlsException("Truststore properties are required if keystore properties are present");
        }

        if (clientAuth == null) {
            clientAuth = ClientAuth.REQUIRED;
            logger.debug("ClientAuth was null so defaulting to {}", clientAuth);
        }

        // Create the keystore components
        KeyManager[] keyManagers = getKeyManagers(tlsConfiguration);

        // Create the truststore components
        TrustManager[] trustManagers = getTrustManagers(tlsConfiguration);

        // Initialize the ssl context
        return initializeSSLContext(tlsConfiguration, clientAuth, keyManagers, trustManagers);
    }

    /**
     * Returns a configured {@link X509TrustManager} for the provided configuration. Useful for
     * constructing HTTP clients which require their own trust management rather than an
     * {@link SSLContext}. Filters and removes any trust managers that are not
     * {@link javax.net.ssl.X509TrustManager} implementations, and returns the <em>first</em>
     * X.509 trust manager.
     *
     * @param tlsConfiguration the TLS configuration container object
     * @return an X.509 TrustManager (can be {@code null})
     * @throws TlsException if there is a problem reading the truststore to create the trust managers
     */
    public static X509TrustManager getX509TrustManager(TlsConfiguration tlsConfiguration) throws TlsException {
        TrustManager[] trustManagers = getTrustManagers(tlsConfiguration);
        if (trustManagers == null) {
            return null;
        }
        Optional<X509TrustManager> x509TrustManager = Arrays.stream(trustManagers)
                .filter(tm -> tm instanceof X509TrustManager)
                .map(tm -> (X509TrustManager) tm)
                .findFirst();
        return x509TrustManager.orElse(null);
    }

    /**
     * Convenience method to return the {@link SSLSocketFactory} from the created {@link SSLContext}
     * because that is what most callers of {@link #createSslContext(TlsConfiguration, ClientAuth)}
     * actually need and don't know what to provide for the {@link ClientAuth} parameter.
     *
     * @param tlsConfiguration the TLS configuration container object
     * @return the configured SSLSocketFactory (can be {@code null})
     * @throws TlsException if there is a problem creating the SSLContext or SSLSocketFactory
     */
    public static SSLSocketFactory createSSLSocketFactory(TlsConfiguration tlsConfiguration) throws TlsException {
        SSLContext sslContext = createSslContext(tlsConfiguration, ClientAuth.REQUIRED);
        if (sslContext == null) {
            // Only display an error in the log if the provided config wasn't empty
            if (!TlsConfiguration.isEmpty(tlsConfiguration)) {
                logger.error("The SSLContext could not be formed from the provided TLS configuration. Check the provided keystore and truststore properties");
            }
            return null;
        }
        return sslContext.getSocketFactory();
    }

    /**
     * Returns an array of {@link KeyManager}s for the provided configuration. Useful for constructing
     * HTTP clients which require their own key management rather than an {@link SSLContext}. The result can be
     * {@code null} or empty. If an empty configuration is provided, {@code null} is returned. However, if a partially-populated
     * but invalid configuration is provided, a {@link TlsException} is thrown.
     *
     * @param tlsConfiguration the TLS configuration container object with keystore properties
     * @return an array of KeyManagers (can be {@code null})
     * @throws TlsException if there is a problem reading the keystore to create the key managers
     */
    @SuppressWarnings("RedundantCast")
    protected static KeyManager[] getKeyManagers(TlsConfiguration tlsConfiguration) throws TlsException {
        KeyManager[] keyManagers = null;
        if (tlsConfiguration.isKeystoreValid()) {
            KeyManagerFactory keyManagerFactory = KeyStoreUtils.loadKeyManagerFactory(tlsConfiguration);
            keyManagers = keyManagerFactory.getKeyManagers();
        } else {
            // If some keystore properties were populated but the key managers are empty, throw an exception to inform the caller
            if (tlsConfiguration.isAnyKeystorePopulated()) {
                logger.warn("Some keystore properties are populated ({}, {}, {}, {}) but not valid", (Object[]) tlsConfiguration.getKeystorePropertiesForLogging());
                throw new TlsException("The keystore properties are not valid");
            } else {
                // If they are empty, the caller was not expecting a valid response
                logger.debug("The keystore properties are not populated");
            }
        }
        return keyManagers;
    }

    /**
     * Returns an array of {@link TrustManager} implementations based on the provided truststore configurations. The result can be
     * {@code null} or empty. If an empty configuration is provided, {@code null} is returned. However, if a partially-populated
     * but invalid configuration is provided, a {@link TlsException} is thrown.
     * <p>
     * Most callers do not need the full array and can use {@link #getX509TrustManager(TlsConfiguration)} directly.
     *
     * @param tlsConfiguration the TLS configuration container object with truststore properties
     * @return the loaded trust managers
     * @throws TlsException if there is a problem reading from the truststore
     */
    @SuppressWarnings("RedundantCast")
    protected static TrustManager[] getTrustManagers(TlsConfiguration tlsConfiguration) throws TlsException {
        TrustManager[] trustManagers = null;
        if (tlsConfiguration.isTruststoreValid()) {
            TrustManagerFactory trustManagerFactory = KeyStoreUtils.loadTrustManagerFactory(tlsConfiguration);
            trustManagers = trustManagerFactory.getTrustManagers();
        } else {
            // If some truststore properties were populated but the trust managers are empty, throw an exception to inform the caller
            if (tlsConfiguration.isAnyTruststorePopulated()) {
                logger.warn("Some truststore properties are populated ({}, {}, {}) but not valid", (Object[]) tlsConfiguration.getTruststorePropertiesForLogging());
                throw new TlsException("The truststore properties are not valid");
            } else {
                // If they are empty, the caller was not expecting a valid response
                logger.debug("The truststore properties are not populated");
            }
        }
        return trustManagers;
    }

    private static SSLContext initializeSSLContext(TlsConfiguration tlsConfiguration, ClientAuth clientAuth, KeyManager[] keyManagers, TrustManager[] trustManagers) throws TlsException {
        final SSLContext sslContext;
        try {
            sslContext = SSLContext.getInstance(tlsConfiguration.getProtocol());
            sslContext.init(keyManagers, trustManagers, new SecureRandom());
            switch (clientAuth) {
                case REQUIRED:
                    sslContext.getDefaultSSLParameters().setNeedClientAuth(true);
                    break;
                case WANT:
                    sslContext.getDefaultSSLParameters().setWantClientAuth(true);
                    break;
                case NONE:
                default:
                    sslContext.getDefaultSSLParameters().setWantClientAuth(false);
            }

            return sslContext;
        } catch (NoSuchAlgorithmException | KeyManagementException e) {
            logger.error("Encountered an error creating SSLContext from TLS configuration ({}): {}", tlsConfiguration.toString(), e.getLocalizedMessage());
            throw new TlsException("Error creating SSL context", e);
        }
    }
}

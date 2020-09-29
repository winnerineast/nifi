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
package org.apache.nifi.web.server

import org.apache.log4j.AppenderSkeleton
import org.apache.log4j.spi.LoggingEvent
import org.apache.nifi.bundle.Bundle
import org.apache.nifi.properties.StandardNiFiProperties
import org.apache.nifi.security.util.CertificateUtils
import org.apache.nifi.security.util.TlsConfiguration
import org.apache.nifi.util.NiFiProperties
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.eclipse.jetty.server.Connector
import org.eclipse.jetty.server.HttpConfiguration
import org.eclipse.jetty.server.Server
import org.eclipse.jetty.server.ServerConnector
import org.eclipse.jetty.server.SslConnectionFactory
import org.eclipse.jetty.util.ssl.SslContextFactory
import org.junit.After
import org.junit.AfterClass
import org.junit.Assume
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.contrib.java.lang.system.Assertion
import org.junit.contrib.java.lang.system.ExpectedSystemExit
import org.junit.contrib.java.lang.system.SystemErrRule
import org.junit.contrib.java.lang.system.SystemOutRule
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import java.nio.charset.StandardCharsets
import java.security.Security

@RunWith(JUnit4.class)
class JettyServerGroovyTest extends GroovyTestCase {
    private static final Logger logger = LoggerFactory.getLogger(JettyServerGroovyTest.class)

    @Rule
    public final ExpectedSystemExit exit = ExpectedSystemExit.none()

    @Rule
    public final SystemOutRule systemOutRule = new SystemOutRule().enableLog()

    @Rule
    public final SystemErrRule systemErrRule = new SystemErrRule().enableLog()

    private static final int HTTPS_PORT = 8443
    private static final String HTTPS_HOSTNAME = "localhost"

    private static final String KEYSTORE_PATH = "src/test/resources/keystore.jks"
    private static final String TRUSTSTORE_PATH = "src/test/resources/truststore.jks"
    private static final String STORE_PASSWORD = "passwordpassword"
    private static final String STORE_TYPE = "JKS"

    private static final String TLS_1_2_PROTOCOL = "TLSv1.2"
    private static final String TLS_1_3_PROTOCOL = "TLSv1.3"
    private static final List<String> TLS_1_3_CIPHER_SUITES = ["TLS_AES_128_GCM_SHA256"]

    // Depending if the test is run on Java 8 or Java 11, these values change (TLSv1.2 vs. TLSv1.3)
    private static final CURRENT_TLS_PROTOCOL_VERSION = CertificateUtils.getHighestCurrentSupportedTlsProtocolVersion()
    private static final List<String> CURRENT_TLS_PROTOCOL_VERSIONS = CertificateUtils.getCurrentSupportedTlsProtocolVersions()

    // These protocol versions should not ever be supported
    static private final List<String> LEGACY_TLS_PROTOCOLS = ["TLS", "TLSv1", "TLSv1.1", "SSL", "SSLv2", "SSLv2Hello", "SSLv3"]

    NiFiProperties httpsProps = new StandardNiFiProperties(rawProperties: new Properties([
            (NiFiProperties.WEB_HTTPS_PORT)            : HTTPS_PORT as String,
            (NiFiProperties.WEB_HTTPS_HOST)            : HTTPS_HOSTNAME,
            (NiFiProperties.SECURITY_KEYSTORE)         : KEYSTORE_PATH,
            (NiFiProperties.SECURITY_KEYSTORE_PASSWD)  : STORE_PASSWORD,
            (NiFiProperties.SECURITY_KEYSTORE_TYPE)    : STORE_TYPE,
            (NiFiProperties.SECURITY_TRUSTSTORE)       : TRUSTSTORE_PATH,
            (NiFiProperties.SECURITY_TRUSTSTORE_PASSWD): STORE_PASSWORD,
            (NiFiProperties.SECURITY_TRUSTSTORE_TYPE)  : STORE_TYPE,
    ]))

    @BeforeClass
    static void setUpOnce() throws Exception {
        Security.addProvider(new BouncyCastleProvider())

        logger.metaClass.methodMissing = { String name, args ->
            logger.info("[${name?.toUpperCase()}] ${(args as List).join(" ")}")
        }

        TestAppender.reset()
    }

    @AfterClass
    static void tearDownOnce() throws Exception {

    }

    @Before
    void setUp() throws Exception {

    }

    @After
    void tearDown() throws Exception {
        TestAppender.reset()
    }

    @Test
    void testShouldDetectHttpAndHttpsConfigurationsBothPresent() {
        // Arrange
        Map badProps = [
                (NiFiProperties.WEB_HTTP_HOST) : "localhost",
                (NiFiProperties.WEB_HTTPS_HOST): "secure.host.com",
                (NiFiProperties.WEB_THREADS)   : NiFiProperties.DEFAULT_WEB_THREADS
        ]
        NiFiProperties mockProps = [
                getPort    : { -> 8080 },
                getSslPort : { -> 8443 },
                getProperty: { String prop ->
                    String value = badProps[prop] ?: "no_value"
                    logger.mock("getProperty(${prop}) -> ${value}")
                    value
                },
        ] as StandardNiFiProperties

        // Act
        boolean bothConfigsPresent = JettyServer.bothHttpAndHttpsConnectorsConfigured(mockProps)
        logger.info("Both configs present: ${bothConfigsPresent}")
        def log = TestAppender.getLogLines()

        // Assert
        assert bothConfigsPresent
        assert !log.isEmpty()
        assert log.first() =~ "Both the HTTP and HTTPS connectors are configured in nifi.properties. Only one of these connectors should be configured. See the NiFi Admin Guide for more details"
    }

    @Test
    void testDetectHttpAndHttpsConfigurationsShouldAllowEither() {
        // Arrange
        Map httpMap = [
                (NiFiProperties.WEB_HTTP_HOST) : "localhost",
                (NiFiProperties.WEB_HTTPS_HOST): null,
        ]
        NiFiProperties httpProps = [
                getPort    : { -> 8080 },
                getSslPort : { -> null },
                getProperty: { String prop ->
                    String value = httpMap[prop] ?: "no_value"
                    logger.mock("getProperty(${prop}) -> ${value}")
                    value
                },
        ] as StandardNiFiProperties

        Map httpsMap = [
                (NiFiProperties.WEB_HTTP_HOST) : null,
                (NiFiProperties.WEB_HTTPS_HOST): "secure.host.com",
        ]
        NiFiProperties httpsProps = [
                getPort    : { -> null },
                getSslPort : { -> 8443 },
                getProperty: { String prop ->
                    String value = httpsMap[prop] ?: "no_value"
                    logger.mock("getProperty(${prop}) -> ${value}")
                    value
                },
        ] as StandardNiFiProperties

        // Act
        boolean bothConfigsPresentForHttp = JettyServer.bothHttpAndHttpsConnectorsConfigured(httpProps)
        logger.info("Both configs present for HTTP properties: ${bothConfigsPresentForHttp}")

        boolean bothConfigsPresentForHttps = JettyServer.bothHttpAndHttpsConnectorsConfigured(httpsProps)
        logger.info("Both configs present for HTTPS properties: ${bothConfigsPresentForHttps}")
        def log = TestAppender.getLogLines()

        // Assert
        assert !bothConfigsPresentForHttp
        assert !bothConfigsPresentForHttps

        // Verifies that the warning was not logged
        assert log.size() == 2
        assert log.first() == "Both configs present for HTTP properties: false"
        assert log.last() == "Both configs present for HTTPS properties: false"
    }

    @Test
    void testShouldFailToStartWithHttpAndHttpsConfigurationsBothPresent() {
        // Arrange
        Map badProps = [
                (NiFiProperties.WEB_HTTP_HOST) : "localhost",
                (NiFiProperties.WEB_HTTPS_HOST): "secure.host.com",
        ]
        NiFiProperties mockProps = [
                getPort            : { -> 8080 },
                getSslPort         : { -> 8443 },
                getProperty        : { String prop ->
                    String value = badProps[prop] ?: "no_value"
                    logger.mock("getProperty(${prop}) -> ${value}")
                    value
                },
                getWebThreads      : { -> NiFiProperties.DEFAULT_WEB_THREADS },
                getWebMaxHeaderSize: { -> NiFiProperties.DEFAULT_WEB_MAX_HEADER_SIZE },
                isHTTPSConfigured  : { -> true }
        ] as StandardNiFiProperties

        // The web server should fail to start and exit Java
        exit.expectSystemExitWithStatus(1)
        exit.checkAssertionAfterwards(new Assertion() {
            void checkAssertion() {
                final String standardErr = systemErrRule.getLog()
                List<String> errLines = standardErr.split("\n")

                assert errLines.any { it =~ "Failed to start web server: " }
                assert errLines.any { it =~ "Shutting down..." }
            }
        })

        // Act
        JettyServer jettyServer = new JettyServer(mockProps, [] as Set<Bundle>)

        // Assert

        // Assertions defined above
    }

    @Test
    void testShouldConfigureHTTPSConnector() {
        // Arrange
        final String externalHostname = "secure.host.com"

        NiFiProperties httpsProps = new StandardNiFiProperties(rawProperties: new Properties([
                (NiFiProperties.WEB_HTTPS_PORT): HTTPS_PORT as String,
                (NiFiProperties.WEB_HTTPS_HOST): externalHostname,
        ]))

        Server internalServer = new Server()
        JettyServer jetty = new JettyServer(internalServer, httpsProps)

        // Act
        jetty.configureHttpsConnector(internalServer, new HttpConfiguration())
        List<Connector> connectors = Arrays.asList(internalServer.connectors)

        // Assert

        // Set the expected TLS protocols to null because no actual keystore/truststore is loaded here
        assertServerConnector(connectors, "TLS", null, null, externalHostname, HTTPS_PORT)
    }

    @Test
    void testShouldSupportTLSv1_3OnJava11() {
        // Arrange
        Assume.assumeTrue("This test should only run on Java 11+", CertificateUtils.getJavaVersion() >= 11)

        Server internalServer = new Server()
        JettyServer jetty = new JettyServer(internalServer, httpsProps)

        jetty.configureConnectors(internalServer)
        List<Connector> connectors = Arrays.asList(internalServer.connectors)
        internalServer.start()

        // Create a (client) socket which only supports TLSv1.3
        TlsConfiguration tls13ClientConf = TlsConfiguration.fromNiFiProperties(httpsProps)
        SSLSocketFactory socketFactory = org.apache.nifi.security.util.SslContextFactory.createSSLSocketFactory(tls13ClientConf)

        SSLSocket socket = (SSLSocket) socketFactory.createSocket(HTTPS_HOSTNAME, HTTPS_PORT)
        socket.setEnabledProtocols([TLS_1_3_PROTOCOL] as String[])
        socket.setEnabledCipherSuites(TLS_1_3_CIPHER_SUITES as String[])

        // Act
        String response = makeTLSRequest(socket, "This is a TLS 1.3 request")

        // Assert
        assert response =~ "HTTP/1.1 400"

        // Assert that the connector prefers TLSv1.3 but the JVM supports TLSv1.2 as well
        assertServerConnector(connectors, "TLS", [CURRENT_TLS_PROTOCOL_VERSION], CURRENT_TLS_PROTOCOL_VERSIONS)

        // Clean up
        internalServer.stop()
    }

    @Test
    void testShouldNotSupportTLSv1_3OnJava8() {
        // Arrange
        Assume.assumeTrue("This test should only run on Java 8", CertificateUtils.getJavaVersion() <= 8)

        Server internalServer = new Server()
        JettyServer jetty = new JettyServer(internalServer, httpsProps)

        jetty.configureConnectors(internalServer)
        List<Connector> connectors = Arrays.asList(internalServer.connectors)
        internalServer.start()

        TlsConfiguration tlsConfiguration = TlsConfiguration.fromNiFiProperties(httpsProps)

        // Create a "default" (client) socket (which supports TLSv1.2)
        SSLSocketFactory defaultSocketFactory = org.apache.nifi.security.util.SslContextFactory.createSSLSocketFactory(tlsConfiguration)
        SSLSocket defaultSocket = (SSLSocket) defaultSocketFactory.createSocket(HTTPS_HOSTNAME, HTTPS_PORT)

        // Act
        String tls12Response = makeTLSRequest(defaultSocket, "This is a default socket request")

        def msg = shouldFail(IllegalArgumentException) {
            // Create a (client) socket which only supports TLSv1.3
            SSLSocketFactory tls13SocketFactory = org.apache.nifi.security.util.SslContextFactory.createSSLSocketFactory(tlsConfiguration)

            SSLSocket tls13Socket = (SSLSocket) tls13SocketFactory.createSocket(HTTPS_HOSTNAME, HTTPS_PORT)
            tls13Socket.setEnabledProtocols([TLS_1_3_PROTOCOL] as String[])
            tls13Socket.setEnabledCipherSuites(TLS_1_3_CIPHER_SUITES as String[])

            String tls13Response = makeTLSRequest(tls13Socket, "This is a TLSv1.3 socket request")
        }
        // The IAE message is just the invalid argument (i.e. "TLSv1.3")
        logger.expected(msg)

        // Assert
        assert tls12Response =~ "HTTP"
        assert msg == "TLSv1.3"

        // Assert that the connector only accepts TLSv1.2
        assertServerConnector(connectors, "TLS", [CURRENT_TLS_PROTOCOL_VERSION], CURRENT_TLS_PROTOCOL_VERSIONS)

        // Clean up
        internalServer.stop()
    }

    /**
     * Returns the server's response body as a String. Closes the socket connection.
     *
     * @param socket
     * @param requestMessage
     * @return
     */
    private static String makeTLSRequest(Socket socket, String requestMessage) {
        InputStream socketInputStream = new BufferedInputStream(socket.getInputStream())
        OutputStream socketOutputStream = new BufferedOutputStream(socket.getOutputStream())

        socketOutputStream.write(requestMessage.getBytes())
        socketOutputStream.flush()

        byte[] data = new byte[2048]
        int len = socketInputStream.read(data)
        if (len <= 0) {
            throw new IOException("no data received")
        }
        final String trimmedResponse = new String(data, 0, len, StandardCharsets.UTF_8)
        logger.info("Client received ${len} bytes from server: \n${trimmedResponse}\n----End of response----")
        socket.close()
        trimmedResponse
    }

    private static void assertServerConnector(List<Connector> connectors,
                                              String EXPECTED_TLS_PROTOCOL = "TLS",
                                              List<String> EXPECTED_INCLUDED_PROTOCOLS = CertificateUtils.getCurrentSupportedTlsProtocolVersions(),
                                              List<String> EXPECTED_SELECTED_PROTOCOLS = CertificateUtils.getCurrentSupportedTlsProtocolVersions(),
                                              String EXPECTED_HOSTNAME = HTTPS_HOSTNAME,
                                              int EXPECTED_PORT = HTTPS_PORT) {
        // Assert the server connector is correct
        assert connectors.size() == 1
        ServerConnector connector = connectors.first() as ServerConnector
        assert connector.host == EXPECTED_HOSTNAME
        assert connector.port == EXPECTED_PORT
        assert connector.getProtocols() == ['ssl', 'http/1.1']

        // This kind of testing is not ideal as it breaks encapsulation, but is necessary to enforce verification of the TLS protocol versions specified
        SslConnectionFactory connectionFactory = connector.getConnectionFactory("ssl") as SslConnectionFactory
        SslContextFactory sslContextFactory = connectionFactory._sslContextFactory as SslContextFactory
        logger.debug("SSL Context Factory: ${sslContextFactory.dump()}")

        // Using the getters is subject to NPE due to blind array copies
        assert sslContextFactory._sslProtocol == EXPECTED_TLS_PROTOCOL
        assert sslContextFactory._includeProtocols.containsAll(EXPECTED_INCLUDED_PROTOCOLS ?: Collections.emptySet())
        assert (sslContextFactory._excludeProtocols as List<String>).containsAll(LEGACY_TLS_PROTOCOLS)
        assert sslContextFactory._selectedProtocols == EXPECTED_SELECTED_PROTOCOLS as String[]
    }
}

class TestAppender extends AppenderSkeleton {
    static final List<LoggingEvent> events = new ArrayList<>()

    @Override
    protected void append(LoggingEvent e) {
        synchronized (events) {
            events.add(e)
        }
    }

    static void reset() {
        synchronized (events) {
            events.clear()
        }
    }

    @Override
    void close() {
    }

    @Override
    boolean requiresLayout() {
        return false
    }

    static List<String> getLogLines() {
        synchronized (events) {
            events.collect { LoggingEvent le -> le.getRenderedMessage() }
        }
    }
}
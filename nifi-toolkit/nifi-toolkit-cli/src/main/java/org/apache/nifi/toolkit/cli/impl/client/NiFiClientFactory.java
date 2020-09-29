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
package org.apache.nifi.toolkit.cli.impl.client;

import org.apache.commons.cli.MissingOptionException;
import org.apache.commons.lang3.StringUtils;
import org.apache.nifi.registry.security.util.KeystoreType;
import org.apache.nifi.toolkit.cli.api.ClientFactory;
import org.apache.nifi.toolkit.cli.impl.client.nifi.ConnectionClient;
import org.apache.nifi.toolkit.cli.impl.client.nifi.ControllerClient;
import org.apache.nifi.toolkit.cli.impl.client.nifi.ControllerServicesClient;
import org.apache.nifi.toolkit.cli.impl.client.nifi.CountersClient;
import org.apache.nifi.toolkit.cli.impl.client.nifi.FlowClient;
import org.apache.nifi.toolkit.cli.impl.client.nifi.InputPortClient;
import org.apache.nifi.toolkit.cli.impl.client.nifi.NiFiClient;
import org.apache.nifi.toolkit.cli.impl.client.nifi.NiFiClientConfig;
import org.apache.nifi.toolkit.cli.impl.client.nifi.OutputPortClient;
import org.apache.nifi.toolkit.cli.impl.client.nifi.ParamContextClient;
import org.apache.nifi.toolkit.cli.impl.client.nifi.PoliciesClient;
import org.apache.nifi.toolkit.cli.impl.client.nifi.ProcessGroupClient;
import org.apache.nifi.toolkit.cli.impl.client.nifi.ProcessorClient;
import org.apache.nifi.toolkit.cli.impl.client.nifi.ProvenanceClient;
import org.apache.nifi.toolkit.cli.impl.client.nifi.RemoteProcessGroupClient;
import org.apache.nifi.toolkit.cli.impl.client.nifi.ReportingTasksClient;
import org.apache.nifi.toolkit.cli.impl.client.nifi.TemplatesClient;
import org.apache.nifi.toolkit.cli.impl.client.nifi.TenantsClient;
import org.apache.nifi.toolkit.cli.impl.client.nifi.VersionsClient;
import org.apache.nifi.toolkit.cli.impl.client.nifi.impl.JerseyNiFiClient;
import org.apache.nifi.toolkit.cli.impl.command.CommandOption;

import java.io.IOException;
import java.util.Properties;

/**
 * Factory for obtaining an instance of NiFiClient from the given properties.
 */
public class NiFiClientFactory implements ClientFactory<NiFiClient> {

    @Override
    public NiFiClient createClient(final Properties properties) throws MissingOptionException {
        final String url = properties.getProperty(CommandOption.URL.getLongName());
        if (StringUtils.isBlank(url)) {
            throw new MissingOptionException("Missing required option '" + CommandOption.URL.getLongName() + "'");
        }

        final String keystore = properties.getProperty(CommandOption.KEYSTORE.getLongName());
        final String keystoreType = properties.getProperty(CommandOption.KEYSTORE_TYPE.getLongName());
        final String keystorePasswd = properties.getProperty(CommandOption.KEYSTORE_PASSWORD.getLongName());
        final String keyPasswd = properties.getProperty(CommandOption.KEY_PASSWORD.getLongName());

        final String truststore = properties.getProperty(CommandOption.TRUSTSTORE.getLongName());
        final String truststoreType = properties.getProperty(CommandOption.TRUSTSTORE_TYPE.getLongName());
        final String truststorePasswd = properties.getProperty(CommandOption.TRUSTSTORE_PASSWORD.getLongName());

        final String proxiedEntity = properties.getProperty(CommandOption.PROXIED_ENTITY.getLongName());
        final String protocol = properties.getProperty(CommandOption.PROTOCOL.getLongName());

        final boolean secureUrl = url.startsWith("https");

        if (secureUrl && (StringUtils.isBlank(truststore)
                || StringUtils.isBlank(truststoreType)
                || StringUtils.isBlank(truststorePasswd))
                ) {
            throw new MissingOptionException(CommandOption.TRUSTSTORE.getLongName() + ", " + CommandOption.TRUSTSTORE_TYPE.getLongName()
                    + ", and " + CommandOption.TRUSTSTORE_PASSWORD.getLongName() + " are required when using an https url");
        }

        final NiFiClientConfig.Builder clientConfigBuilder = new NiFiClientConfig.Builder()
                .baseUrl(url);

        if (secureUrl) {
            if (!StringUtils.isBlank(keystore)) {
                clientConfigBuilder.keystoreFilename(keystore);
            }
            if (!StringUtils.isBlank(keystoreType)) {
                clientConfigBuilder.keystoreType(KeystoreType.valueOf(keystoreType.toUpperCase()));
            }
            if (!StringUtils.isBlank(keystorePasswd)) {
                clientConfigBuilder.keystorePassword(keystorePasswd);
            }
            if (!StringUtils.isBlank(keyPasswd)) {
                clientConfigBuilder.keyPassword(keyPasswd);
            }
            if (!StringUtils.isBlank(truststore)) {
                clientConfigBuilder.truststoreFilename(truststore);
            }
            if (!StringUtils.isBlank(truststoreType)) {
                clientConfigBuilder.truststoreType(KeystoreType.valueOf(truststoreType.toUpperCase()));
            }
            if (!StringUtils.isBlank(truststorePasswd)) {
                clientConfigBuilder.truststorePassword(truststorePasswd);
            }
            if (!StringUtils.isBlank(protocol)) {
                clientConfigBuilder.protocol(protocol);
            }
        }

        final NiFiClient client = new JerseyNiFiClient.Builder().config(clientConfigBuilder.build()).build();

        // if a proxied entity was specified then return a wrapped client, otherwise return the regular client
        if (!StringUtils.isBlank(proxiedEntity)) {
            return new NiFiClientFactory.ProxiedNiFiClient(client, proxiedEntity);
        } else {
            return client;
        }
    }

    /**
     * Wraps a NiFiClient and ensures that all methods to obtain a more specific client will
     * call the proxied-entity variation so that callers don't have to care if proxying is taking place.
     */
    private static class ProxiedNiFiClient implements NiFiClient {

        private final String proxiedEntity;
        private final NiFiClient wrappedClient;

        public ProxiedNiFiClient(final NiFiClient wrappedClient, final String proxiedEntity) {
            this.proxiedEntity = proxiedEntity;
            this.wrappedClient = wrappedClient;
        }

        @Override
        public ControllerClient getControllerClient() {
            return wrappedClient.getControllerClientForProxiedEntities(proxiedEntity);
        }

        @Override
        public ControllerClient getControllerClientForProxiedEntities(String... proxiedEntity) {
            return wrappedClient.getControllerClientForProxiedEntities(proxiedEntity);
        }

        @Override
        public ControllerClient getControllerClientForToken(String token) {
            return wrappedClient.getControllerClientForToken(token);
        }

        @Override
        public ControllerServicesClient getControllerServicesClient() {
            return wrappedClient.getControllerServicesClientForProxiedEntities(proxiedEntity);
        }

        @Override
        public ControllerServicesClient getControllerServicesClientForProxiedEntities(String... proxiedEntity) {
            return wrappedClient.getControllerServicesClientForProxiedEntities(proxiedEntity);
        }

        @Override
        public ControllerServicesClient getControllerServicesClientForToken(String token) {
            return wrappedClient.getControllerServicesClientForToken(token);
        }

        @Override
        public FlowClient getFlowClient() {
            return wrappedClient.getFlowClientForProxiedEntities(proxiedEntity);
        }

        @Override
        public FlowClient getFlowClientForProxiedEntities(String... proxiedEntity) {
            return wrappedClient.getFlowClientForProxiedEntities(proxiedEntity);
        }

        @Override
        public FlowClient getFlowClientForToken(String token) {
            return wrappedClient.getFlowClientForToken(token);
        }

        @Override
        public ProcessGroupClient getProcessGroupClient() {
            return wrappedClient.getProcessGroupClientForProxiedEntities(proxiedEntity);
        }

        @Override
        public ProcessGroupClient getProcessGroupClientForProxiedEntities(String... proxiedEntity) {
            return wrappedClient.getProcessGroupClientForProxiedEntities(proxiedEntity);
        }

        @Override
        public ProcessGroupClient getProcessGroupClientForToken(String token) {
            return wrappedClient.getProcessGroupClientForToken(token);
        }

        @Override
        public ProcessorClient getProcessorClient() {
            return wrappedClient.getProcessorClientForProxiedEntities(proxiedEntity);
        }

        @Override
        public ProcessorClient getProcessorClientForProxiedEntities(final String... proxiedEntity) {
            return wrappedClient.getProcessorClientForProxiedEntities(proxiedEntity);
        }

        @Override
        public ProcessorClient getProcessorClientForToken(final String token) {
            return wrappedClient.getProcessorClientForToken(token);
        }

        @Override
        public VersionsClient getVersionsClient() {
            return wrappedClient.getVersionsClientForProxiedEntities(proxiedEntity);
        }

        @Override
        public VersionsClient getVersionsClientForProxiedEntities(String... proxiedEntity) {
            return wrappedClient.getVersionsClientForProxiedEntities(proxiedEntity);
        }

        @Override
        public VersionsClient getVersionsClientForToken(String token) {
            return wrappedClient.getVersionsClientForToken(token);
        }

        @Override
        public TenantsClient getTenantsClient() {
            return wrappedClient.getTenantsClientForProxiedEntities(proxiedEntity);
        }

        @Override
        public TenantsClient getTenantsClientForProxiedEntities(String... proxiedEntity) {
            return wrappedClient.getTenantsClientForProxiedEntities(proxiedEntity);
        }

        @Override
        public TenantsClient getTenantsClientForToken(String token) {
            return wrappedClient.getTenantsClientForToken(token);
        }

        @Override
        public PoliciesClient getPoliciesClient() {
            return wrappedClient.getPoliciesClientForProxiedEntities(proxiedEntity);
        }

        @Override
        public PoliciesClient getPoliciesClientForProxiedEntities(String... proxiedEntity) {
            return wrappedClient.getPoliciesClientForProxiedEntities(proxiedEntity);
        }

        @Override
        public PoliciesClient getPoliciesClientForToken(String token) {
            return wrappedClient.getPoliciesClientForToken(token);
        }

        @Override
        public TemplatesClient getTemplatesClient() {
            return wrappedClient.getTemplatesClientForProxiedEntities(proxiedEntity);
        }

        @Override
        public TemplatesClient getTemplatesClientForProxiedEntities(String... proxiedEntity) {
            return wrappedClient.getTemplatesClientForProxiedEntities(proxiedEntity);
        }

        @Override
        public TemplatesClient getTemplatesClientForToken(String token) {
            return wrappedClient.getTemplatesClientForToken(token);
        }

        @Override
        public ReportingTasksClient getReportingTasksClient() {
            return wrappedClient.getReportingTasksClientForProxiedEntities(proxiedEntity);
        }

        @Override
        public ReportingTasksClient getReportingTasksClientForProxiedEntities(String... proxiedEntity) {
            return wrappedClient.getReportingTasksClientForProxiedEntities(proxiedEntity);
        }

        @Override
        public ReportingTasksClient getReportingTasksClientForToken(String token) {
            return wrappedClient.getReportingTasksClientForToken(token);
        }

        @Override
        public ParamContextClient getParamContextClient() {
            return wrappedClient.getParamContextClientForProxiedEntities(proxiedEntity);
        }

        @Override
        public ParamContextClient getParamContextClientForProxiedEntities(String... proxiedEntity) {
            return wrappedClient.getParamContextClientForProxiedEntities(proxiedEntity);
        }

        @Override
        public ParamContextClient getParamContextClientForToken(String token) {
            return wrappedClient.getParamContextClientForToken(token);
        }

        @Override
        public CountersClient getCountersClient() {
            return wrappedClient.getCountersClientForProxiedEntities(proxiedEntity);
        }

        @Override
        public CountersClient getCountersClientForProxiedEntities(final String... proxiedEntity) {
            return wrappedClient.getCountersClientForProxiedEntities(proxiedEntity);
        }

        @Override
        public CountersClient getCountersClientForToken(final String token) {
            return wrappedClient.getCountersClientForToken(token);
        }

        @Override
        public ConnectionClient getConnectionClient() {
            return wrappedClient.getConnectionClientForProxiedEntities(proxiedEntity);
        }

        @Override
        public ConnectionClient getConnectionClientForProxiedEntities(final String... proxiedEntity) {
            return wrappedClient.getConnectionClientForProxiedEntities(proxiedEntity);
        }

        @Override
        public ConnectionClient getConnectionClientForToken(final String token) {
            return wrappedClient.getConnectionClientForToken(token);
        }

        @Override
        public RemoteProcessGroupClient getRemoteProcessGroupClient() {
            return wrappedClient.getRemoteProcessGroupClientForProxiedEntities(proxiedEntity);
        }

        @Override
        public RemoteProcessGroupClient getRemoteProcessGroupClientForProxiedEntities(final String... proxiedEntity) {
            return wrappedClient.getRemoteProcessGroupClientForProxiedEntities(proxiedEntity);
        }

        @Override
        public RemoteProcessGroupClient getRemoteProcessGroupClientForToken(final String token) {
            return wrappedClient.getRemoteProcessGroupClientForToken(token);
        }

        @Override
        public InputPortClient getInputPortClient() {
            return wrappedClient.getInputPortClientForProxiedEntities(proxiedEntity);
        }

        @Override
        public InputPortClient getInputPortClientForProxiedEntities(final String... proxiedEntity) {
            return wrappedClient.getInputPortClientForProxiedEntities(proxiedEntity);
        }

        @Override
        public InputPortClient getInputPortClientForToken(final String token) {
            return wrappedClient.getInputPortClientForToken(proxiedEntity);
        }

        @Override
        public OutputPortClient getOutputPortClient() {
            return wrappedClient.getOutputPortClientForProxiedEntities(proxiedEntity);
        }

        @Override
        public OutputPortClient getOutputPortClientForProxiedEntities(final String... proxiedEntity) {
            return wrappedClient.getOutputPortClientForProxiedEntities(proxiedEntity);
        }

        @Override
        public OutputPortClient getOutputPortClientForToken(final String token) {
            return wrappedClient.getOutputPortClientForToken(proxiedEntity);
        }

        @Override
        public ProvenanceClient getProvenanceClient() {
            return wrappedClient.getProvenanceClient();
        }

        @Override
        public ProvenanceClient getProvenanceClientForProxiedEntities(final String... proxiedEntity) {
            return wrappedClient.getProvenanceClientForProxiedEntities(proxiedEntity);
        }

        @Override
        public ProvenanceClient getProvenanceClientForToken(final String token) {
            return wrappedClient.getProvenanceClientForToken(token);
        }

        @Override
        public void close() throws IOException {
            wrappedClient.close();
        }
    }
}

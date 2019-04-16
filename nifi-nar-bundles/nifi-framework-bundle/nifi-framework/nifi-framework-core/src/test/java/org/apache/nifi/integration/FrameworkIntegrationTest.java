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
package org.apache.nifi.integration;

import org.apache.nifi.admin.service.AuditService;
import org.apache.nifi.authorization.Authorizer;
import org.apache.nifi.bundle.Bundle;
import org.apache.nifi.bundle.BundleCoordinate;
import org.apache.nifi.components.state.StateProvider;
import org.apache.nifi.components.validation.ValidationStatus;
import org.apache.nifi.connectable.Connection;
import org.apache.nifi.connectable.StandardConnection;
import org.apache.nifi.controller.ControllerService;
import org.apache.nifi.controller.FileSystemSwapManager;
import org.apache.nifi.controller.FlowController;
import org.apache.nifi.controller.ProcessorNode;
import org.apache.nifi.controller.StandardSnippet;
import org.apache.nifi.controller.flow.StandardFlowManager;
import org.apache.nifi.controller.queue.ConnectionEventListener;
import org.apache.nifi.controller.queue.FlowFileQueue;
import org.apache.nifi.controller.queue.FlowFileQueueFactory;
import org.apache.nifi.controller.queue.LoadBalanceStrategy;
import org.apache.nifi.controller.queue.StandardFlowFileQueue;
import org.apache.nifi.controller.repository.ContentRepository;
import org.apache.nifi.controller.repository.FileSystemRepository;
import org.apache.nifi.controller.repository.FlowFileEvent;
import org.apache.nifi.controller.repository.FlowFileEventRepository;
import org.apache.nifi.controller.repository.FlowFileRecord;
import org.apache.nifi.controller.repository.FlowFileRepository;
import org.apache.nifi.controller.repository.FlowFileSwapManager;
import org.apache.nifi.controller.repository.QueueProvider;
import org.apache.nifi.controller.repository.RepositoryContext;
import org.apache.nifi.controller.repository.RepositoryStatusReport;
import org.apache.nifi.controller.repository.WriteAheadFlowFileRepository;
import org.apache.nifi.controller.repository.claim.ResourceClaimManager;
import org.apache.nifi.controller.repository.claim.StandardResourceClaimManager;
import org.apache.nifi.controller.repository.metrics.RingBufferEventRepository;
import org.apache.nifi.controller.scheduling.RepositoryContextFactory;
import org.apache.nifi.controller.scheduling.SchedulingAgent;
import org.apache.nifi.controller.scheduling.StandardProcessScheduler;
import org.apache.nifi.controller.scheduling.TimerDrivenSchedulingAgent;
import org.apache.nifi.controller.service.ControllerServiceNode;
import org.apache.nifi.controller.service.ControllerServiceProvider;
import org.apache.nifi.controller.service.StandardControllerServiceProvider;
import org.apache.nifi.controller.state.providers.local.WriteAheadLocalStateProvider;
import org.apache.nifi.controller.status.history.ComponentStatusRepository;
import org.apache.nifi.controller.status.history.VolatileComponentStatusRepository;
import org.apache.nifi.encrypt.StringEncryptor;
import org.apache.nifi.engine.FlowEngine;
import org.apache.nifi.events.VolatileBulletinRepository;
import org.apache.nifi.flowfile.FlowFile;
import org.apache.nifi.groups.ProcessGroup;
import org.apache.nifi.integration.processor.BiConsumerProcessor;
import org.apache.nifi.nar.ExtensionManager;
import org.apache.nifi.nar.SystemBundle;
import org.apache.nifi.processor.ProcessContext;
import org.apache.nifi.processor.ProcessSession;
import org.apache.nifi.processor.Processor;
import org.apache.nifi.processor.Relationship;
import org.apache.nifi.provenance.ProvenanceEventRecord;
import org.apache.nifi.provenance.ProvenanceEventRepository;
import org.apache.nifi.provenance.ProvenanceEventType;
import org.apache.nifi.provenance.ProvenanceRepository;
import org.apache.nifi.provenance.WriteAheadProvenanceRepository;
import org.apache.nifi.registry.VariableRegistry;
import org.apache.nifi.registry.flow.FlowRegistryClient;
import org.apache.nifi.reporting.BulletinRepository;
import org.apache.nifi.scheduling.SchedulingStrategy;
import org.apache.nifi.util.FileUtils;
import org.apache.nifi.util.NiFiProperties;
import org.junit.After;
import org.junit.Before;
import org.junit.rules.Timeout;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;

import static org.junit.Assert.assertEquals;

public class FrameworkIntegrationTest {
    //@Rule
    public Timeout globalTimeout = Timeout.seconds(20);

    private ResourceClaimManager resourceClaimManager;
    private StandardProcessScheduler processScheduler;

    private FlowEngine flowEngine;
    private FlowController flowController;
    private FlowRegistryClient flowRegistryClient = null;
    private ProcessorNode nopProcessor;
    private ProcessorNode terminateProcessor;
    private ProcessorNode terminateAllProcessor;
    private FlowFileQueueFactory flowFileQueueFactory;
    private FlowFileSwapManager flowFileSwapManager;
    private DirectInjectionExtensionManager extensionManager;
    private ProcessGroup rootProcessGroup;
    private Bundle systemBundle;

    public static final Relationship REL_SUCCESS = new Relationship.Builder().name("success").build();

    @Before
    public void setup() throws IOException {
        cleanup();
        initialize(null);
    }

    protected String getNiFiPropertiesFilename() {
        return "src/test/resources/int-tests/default-nifi.properties";
    }

    protected Map<String, String> getNiFiPropertiesOverrides() {
        return Collections.emptyMap();
    }

    protected void injectExtensionTypes(final DirectInjectionExtensionManager extensionManager) {
        // Placeholder for subclasses.
    }

    protected final void initialize(final QueueProvider queueProvider) throws IOException {
        final NiFiProperties nifiProperties = NiFiProperties.createBasicNiFiProperties(getNiFiPropertiesFilename(), getNiFiPropertiesOverrides());
        initialize(nifiProperties, queueProvider);
    }

    protected final void initialize(final NiFiProperties nifiProperties, final QueueProvider queueProvider) throws IOException {
        final FlowFileEventRepository flowFileEventRepository = new RingBufferEventRepository(5);
        resourceClaimManager = new StandardResourceClaimManager();

        final BulletinRepository bulletinRepo = new VolatileBulletinRepository();
        flowEngine = new FlowEngine(4, "unit test flow engine");
        extensionManager = new DirectInjectionExtensionManager();

        extensionManager.injectExtensionType(FlowFileRepository.class, WriteAheadFlowFileRepository.class);
        extensionManager.injectExtensionType(ContentRepository.class, FileSystemRepository.class);
        extensionManager.injectExtensionType(ProvenanceRepository.class, WriteAheadProvenanceRepository.class);
        extensionManager.injectExtensionType(StateProvider.class, WriteAheadLocalStateProvider.class);
        extensionManager.injectExtensionType(ComponentStatusRepository.class, VolatileComponentStatusRepository.class);
        extensionManager.injectExtensionType(FlowFileSwapManager.class, FileSystemSwapManager.class);
        extensionManager.injectExtensionType(Processor.class, BiConsumerProcessor.class);

        injectExtensionTypes(extensionManager);
        systemBundle = SystemBundle.create(nifiProperties);
        extensionManager.discoverExtensions(systemBundle, Collections.emptySet());

        final StringEncryptor encryptor = StringEncryptor.createEncryptor("PBEWITHMD5AND256BITAES-CBC-OPENSSL", "BC", "unit-test");
        final Authorizer authorizer = new AlwaysAuthorizedAuthorizer();
        final AuditService auditService = new NopAuditService();

        flowController = FlowController.createStandaloneInstance(flowFileEventRepository, nifiProperties, authorizer, auditService, encryptor, bulletinRepo,
            VariableRegistry.ENVIRONMENT_SYSTEM_REGISTRY, flowRegistryClient, extensionManager);

        processScheduler = new StandardProcessScheduler(flowEngine, flowController, encryptor, flowController.getStateManagerProvider(), nifiProperties);

        final RepositoryContextFactory repositoryContextFactory = flowController.getRepositoryContextFactory();
        final SchedulingAgent timerDrivenSchedulingAgent = new TimerDrivenSchedulingAgent(flowController, flowEngine, repositoryContextFactory, encryptor, nifiProperties);
        processScheduler.setSchedulingAgent(SchedulingStrategy.TIMER_DRIVEN, timerDrivenSchedulingAgent);

        final ControllerServiceProvider controllerServiceProvider = new StandardControllerServiceProvider(flowController, processScheduler, bulletinRepo);

        rootProcessGroup = flowController.getFlowManager().createProcessGroup(UUID.randomUUID().toString());
        ((StandardFlowManager) flowController.getFlowManager()).setRootGroup(rootProcessGroup);

        nopProcessor = createProcessorNode((context, session) -> {});

        terminateProcessor = createProcessorNode((context, session) -> {
            FlowFile flowFile = session.get();
            if (flowFile == null) {
                return;
            }

            session.remove(flowFile);
        });

        terminateAllProcessor = createProcessorNode((context, session) -> {
            FlowFile flowFile;
            while ((flowFile = session.get()) != null) {
                session.remove(flowFile);
            }
        });

        flowFileSwapManager = flowController.createSwapManager();
        flowFileQueueFactory = new FlowFileQueueFactory() {
            @Override
            public FlowFileQueue createFlowFileQueue(final LoadBalanceStrategy loadBalanceStrategy, final String partitioningAttribute, final ConnectionEventListener connectionEventListener) {
                return FrameworkIntegrationTest.this.createFlowFileQueue(UUID.randomUUID().toString());
            }
        };

        if (queueProvider == null) {
            flowController.initializeFlow();
        } else {
            flowController.initializeFlow(queueProvider);
        }
    }

    @After
    public final void shutdown() {
        flowController.shutdown(true);
        flowEngine.shutdownNow();
        processScheduler.shutdown();
    }

    @After
    public final void cleanup() throws IOException {
        deleteDirectory(new File("target/int-tests"));
    }

    private void deleteDirectory(final File dir) throws IOException {
        if (!dir.exists()) {
            return;
        }

        FileUtils.deleteFile(dir, true);
    }

    protected FlowFileQueue createFlowFileQueue(final String uuid) {
        final RepositoryContext repoContext = getRepositoryContext();
        return new StandardFlowFileQueue(uuid, ConnectionEventListener.NOP_EVENT_LISTENER, repoContext.getFlowFileRepository(), repoContext.getProvenanceRepository(),
            resourceClaimManager, processScheduler, flowFileSwapManager, flowController.createEventReporter(), 20000, 10000L, "1 GB");
    }

    protected final ProcessorNode createProcessorNode(final Class<? extends Processor> processorType) {
        return createProcessorNode(processorType.getName());
    }

    protected final ProcessorNode createProcessorNode(final String processorType) {
        final String uuid = getSimpleTypeName(processorType) + "-" + UUID.randomUUID().toString();
        final BundleCoordinate bundleCoordinate = SystemBundle.SYSTEM_BUNDLE_COORDINATE;
        final ProcessorNode procNode = flowController.getFlowManager().createProcessor(processorType, uuid, bundleCoordinate, Collections.emptySet(), true, true);
        rootProcessGroup.addProcessor(procNode);

        return procNode;
    }

    protected final ControllerServiceNode createControllerServiceNode(final Class<? extends ControllerService> controllerServiceType) {
        return createControllerServiceNode(controllerServiceType.getName());
    }

    protected final ControllerServiceNode createControllerServiceNode(final String controllerServiceType) {
        final String uuid = getSimpleTypeName(controllerServiceType) + "-" + UUID.randomUUID().toString();
        final BundleCoordinate bundleCoordinate = SystemBundle.SYSTEM_BUNDLE_COORDINATE;
        final ControllerServiceNode serviceNode = flowController.getFlowManager().createControllerService(controllerServiceType, uuid, bundleCoordinate, Collections.emptySet(), true, true);
        rootProcessGroup.addControllerService(serviceNode);
        return serviceNode;
    }

    private String getSimpleTypeName(final String className) {
        final int index = className.lastIndexOf(".");
        if (index >= 0 && index < className.length()) {
            return className.substring(index + 1);
        } else {
            return "";
        }
    }

    protected ProcessGroup getRootGroup() {
        return rootProcessGroup;
    }

    /**
     * Creates a Processor that is responsible for generating a FlowFile of the given size and routing to "success".
     *
     * @param contentSize the number of bytes for the content
     *
     * @return the ProcessorNode
     */
    protected final ProcessorNode createGenerateProcessor(final int contentSize) {
        return createGenerateProcessor(contentSize, null);
    }

    /**
     * Creates a Processor that is responsible for generating a FlowFile of the given size and routing to "success". The generated FlowFile is set in the given AtomicReference
     *
     * @param contentSize the number of bytes for the content
     * @param flowFileReference an AtomicReference to hold the flowfile
     *
     * @return the ProcessorNode
     */
    protected final ProcessorNode createGenerateProcessor(final int contentSize, final AtomicReference<FlowFileRecord> flowFileReference) {
        return createProcessorNode((context, session) -> {
            FlowFile flowFile = session.create();
            flowFile = session.write(flowFile, out -> out.write(new byte[contentSize]));

            if (flowFileReference != null) {
                flowFileReference.set((FlowFileRecord) flowFile);
            }

            session.transfer(flowFile, REL_SUCCESS);
        }, REL_SUCCESS);
    }

    protected final ProcessorNode createProcessorNode(final BiConsumer<ProcessContext, ProcessSession> trigger, final Relationship... relationships) {
        final Set<Relationship> relationshipSet = new HashSet<>(Arrays.asList(relationships));

        final ProcessorNode processorNode = createProcessorNode(BiConsumerProcessor.class.getName());
        final BiConsumerProcessor biConsumerProcessor = (BiConsumerProcessor) processorNode.getProcessor();
        biConsumerProcessor.setRelationships(relationshipSet);
        biConsumerProcessor.setTrigger(trigger);

        return processorNode;
    }

    protected final void connect(final ProcessorNode source, final ProcessorNode destination, final Relationship relationship) {
        connect(source, destination, Collections.singleton(relationship));
    }

    protected final void connect(final ProcessorNode source, final ProcessorNode destination, final Collection<Relationship> relationships) {
        final Connection connection = new StandardConnection.Builder(processScheduler)
            .source(source)
            .destination(destination)
            .relationships(relationships)
            .id(UUID.randomUUID().toString())
            .clustered(false)
            .flowFileQueueFactory(flowFileQueueFactory)
            .build();

        source.addConnection(connection);
        destination.addConnection(connection);
        rootProcessGroup.addConnection(connection);
    }

    protected final Future<Void> start(final ProcessorNode procNode) {
        final ValidationStatus validationStatus = procNode.performValidation();
        if (validationStatus != ValidationStatus.VALID) {
            throw new IllegalStateException("Processor is invalid: " + procNode + ": " + procNode.getValidationErrors());
        }

        return rootProcessGroup.startProcessor(procNode, true);
    }

    protected final Future<Void> stop(final ProcessorNode procNode) {
        return rootProcessGroup.stopProcessor(procNode);
    }

    protected final FlowFileQueue getDestinationQueue(final ProcessorNode procNode, final Relationship relationship) {
        return procNode.getConnections(relationship).stream()
            .map(Connection::getFlowFileQueue)
            .findAny()
            .orElseThrow(() -> new IllegalArgumentException("Could not find queue for relationship with name <" + relationship + ">"));
    }

    protected final FlowFileRepository getFlowFileRepository() {
        return getRepositoryContext().getFlowFileRepository();
    }

    protected Bundle getSystemBundle() {
        return systemBundle;
    }

    protected final ContentRepository getContentRepository() {
        return getRepositoryContext().getContentRepository();
    }

    protected final ProvenanceEventRepository getProvenanceRepository() {
        return getRepositoryContext().getProvenanceRepository();
    }

    private RepositoryContext getRepositoryContext() {
        return flowController.getRepositoryContextFactory().newProcessContext(nopProcessor, new AtomicLong(0L));
    }

    protected final ProcessorNode getNopProcessor() {
        return nopProcessor;
    }

    protected final ProcessorNode getTerminateProcessor() {
        return terminateProcessor;
    }

    protected final ProcessorNode getTerminateAllProcessor() {
        return terminateAllProcessor;
    }

    protected final FlowController getFlowController() {
        return flowController;
    }

    protected void assertProvenanceEventCount(final ProvenanceEventType eventType, final int count) throws IOException {
        int encountered = 0;

        for (final ProvenanceEventRecord event : getProvenanceRepository().getEvents(0L, 100_000_000)) {
            if (event.getEventType() == eventType) {
                encountered++;
            }
        }

        assertEquals("Expected to encounter " + count + " Provenance Events of type " + eventType + " but encountered " + encountered, count, encountered);
    }

    protected void triggerOnce(final ProcessorNode processor) throws ExecutionException, InterruptedException {
        final String schedulingPeriod = processor.getSchedulingPeriod();
        try {
            final FlowFileEvent initialReport = getStatusReport(processor);
            final int initialInvocations = (initialReport == null) ? 0 : initialReport.getInvocations();

            processor.setScheduldingPeriod("1 hour");

            // We will only trigger the Processor to run once per hour. So we need to ensure that
            // we don't trigger the Processor while it's yielded. So if its yield expiration is in the future,
            // wait until the yield expires.
            while (processor.getYieldExpiration() > System.currentTimeMillis()) {
                Thread.sleep(1L);
            }

            start(processor).get();

            int totalInvocations = initialInvocations;
            while (totalInvocations < initialInvocations + 1) {
                final FlowFileEvent currentReport = getStatusReport(processor);
                totalInvocations = currentReport == null ? 0 : currentReport.getInvocations();
            }

            stop(processor).get();
        } finally {
            processor.setScheduldingPeriod(schedulingPeriod);
        }
    }

    protected FlowFileEvent getStatusReport(final ProcessorNode processor) {
        final FlowFileEventRepository repository = getRepositoryContext().getFlowFileEventRepository();
        RepositoryStatusReport statusReport = repository.reportTransferEvents(0L);
        return statusReport.getReportEntry(processor.getIdentifier());
    }

    protected void moveProcessor(final ProcessorNode processor, final ProcessGroup destination) {
        final StandardSnippet snippet = new StandardSnippet();
        snippet.setParentGroupId(processor.getProcessGroupIdentifier());
        snippet.addProcessors(Collections.singletonMap(processor.getIdentifier(), null));

        processor.getProcessGroup().move(snippet, destination);
    }

    protected ExtensionManager getExtensionManager() {
        return extensionManager;
    }
}

/*
* Copyright 2016 Bull Atos.  All Rights Reserved.
* DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
* See the NOTICE file distributed with this work for additional information
* regarding copyright ownership.
*/
package alien4cloud.plugin.Janus;

import java.util.*;
import java.util.Map.Entry;
import java.util.UUID;

import javax.annotation.Resource;
import javax.inject.Inject;

import alien4cloud.orchestrators.plugin.IOrchestratorPlugin;
import alien4cloud.paas.exception.MaintenanceModeException;
import alien4cloud.paas.exception.OperationExecutionException;
import alien4cloud.paas.model.PaaSWorkflowMonitorEvent;
import alien4cloud.paas.model.PaaSWorkflowStepMonitorEvent;
import alien4cloud.plugin.Janus.rest.JanusRestException;
import alien4cloud.plugin.Janus.rest.Response.DeployInfosResponse;
import alien4cloud.plugin.Janus.rest.Response.NodeInfosResponse;
import alien4cloud.utils.MapUtil;
import alien4cloud.dao.IGenericSearchDAO;
import alien4cloud.model.deployment.Deployment;
import alien4cloud.paas.IPaaSCallback;
import alien4cloud.paas.exception.PluginConfigurationException;
import alien4cloud.paas.model.AbstractMonitorEvent;
import alien4cloud.paas.model.DeploymentStatus;
import alien4cloud.paas.model.InstanceInformation;
import alien4cloud.paas.model.InstanceStatus;
import alien4cloud.paas.model.NodeOperationExecRequest;
import alien4cloud.paas.model.PaaSDeploymentContext;
import alien4cloud.paas.model.PaaSDeploymentLog;
import alien4cloud.paas.model.PaaSDeploymentLogLevel;
import alien4cloud.paas.model.PaaSDeploymentStatusMonitorEvent;
import alien4cloud.paas.model.PaaSInstancePersistentResourceMonitorEvent;
import alien4cloud.paas.model.PaaSInstanceStateMonitorEvent;
import alien4cloud.paas.model.PaaSMessageMonitorEvent;
import alien4cloud.paas.model.PaaSTopologyDeploymentContext;
import alien4cloud.paas.plan.ToscaNodeLifecycleConstants;
import alien4cloud.plugin.Janus.rest.Response.AttributeResponse;
import alien4cloud.plugin.Janus.rest.Response.Event;
import alien4cloud.plugin.Janus.rest.Response.EventResponse;
import alien4cloud.plugin.Janus.rest.Response.InstanceInfosResponse;
import alien4cloud.plugin.Janus.rest.Response.Link;
import alien4cloud.plugin.Janus.rest.Response.LogEvent;
import alien4cloud.plugin.Janus.rest.Response.LogResponse;
import alien4cloud.plugin.Janus.rest.RestClient;
import alien4cloud.tosca.ToscaUtils;
import alien4cloud.tosca.normative.NormativeBlockStorageConstants;
import alien4cloud.tosca.normative.NormativeComputeConstants;
import lombok.extern.slf4j.Slf4j;
import org.alien4cloud.tosca.catalog.index.IToscaTypeSearchService;
import org.alien4cloud.tosca.catalog.repository.ICsarRepositry;
import org.alien4cloud.tosca.exporter.ArchiveExportService;
import org.alien4cloud.tosca.model.templates.NodeTemplate;
import org.alien4cloud.tosca.model.templates.Topology;
import org.alien4cloud.tosca.model.types.NodeType;
import org.alien4cloud.tosca.model.types.RelationshipType;
import org.elasticsearch.common.collect.Maps;

/**
 * a4c janus plugin
 * This class is abstract since it extends JanusOrchestrator
 */
@Slf4j
public abstract class JanusPaaSProvider implements IOrchestratorPlugin<ProviderConfig> {

    private final Map<String, JanusRuntimeDeploymentInfo> runtimeDeploymentInfos = Maps.newConcurrentMap();
    private final List<AbstractMonitorEvent> toBeDeliveredEvents = new ArrayList<>();
    private ProviderConfig providerConfiguration;
    private Map<String, String> a4cDeploymentIds = Maps.newHashMap();

    @Inject
    private IToscaTypeSearchService toscaTypeSearchService;

    @Resource(name = "alien-monitor-es-dao")
    private IGenericSearchDAO alienMonitorDao;

    //
    private RestClient restClient = new RestClient();

    private TaskManager taskManager;

    private final int JANUS_OPE_TIMEOUT = 1000 * 3600 * 4;  // 4 hours

    /**
     * Default constructor
     */
    public JanusPaaSProvider() {
        // Start the TaskManager
        // TODO make sizes configurable
        taskManager = new TaskManager(3, 120, 3600);

    }

    public RestClient getRestClient() {
        return restClient;
    }

    /**
     * Add a task in the task manager
     * @param task
     */
    public void addTask(AlienTask task) {
        taskManager.addTask(task);
    }

    public void putDeploymentId(String paasId, String alienId) {
        a4cDeploymentIds.put(paasId, alienId);
    }

    public String getDeploymentId(String paasId) {
        return a4cDeploymentIds.get(paasId);
    }

    public void putDeploymentInfo(String paasId, JanusRuntimeDeploymentInfo jrdi) {
        runtimeDeploymentInfos.put(paasId, jrdi);
    }

    public JanusRuntimeDeploymentInfo getDeploymentInfo(String paasId) {
        return runtimeDeploymentInfos.get(paasId);
    }

    public void removeDeploymentInfo(String paasId) {
        runtimeDeploymentInfos.remove(paasId);
        // TODO Stop threads listening log and events (maybe nothing to do)
    }

    public void saveLog(PaaSDeploymentLog pdlog) {
        log.debug(pdlog.toString());
        alienMonitorDao.save(pdlog);
    }

    // ------------------------------------------------------------------------------------------------------
    // IPaaSProvider implementation
    // ------------------------------------------------------------------------------------------------------

    /**
     * This method is called by Alien in order to restore the state of the paaS provider after a restart.
     * The provider must implement this method in order to restore its state
     *
     * @param activeDeployments the currently active deployments that Alien has
     */
    @Override
    public void init(Map<String, PaaSTopologyDeploymentContext> activeDeployments) {
        log.info("Init plugin for " + activeDeployments.size() + " active deployments");

        // Update deployment info for all active deployments
        for (Map.Entry<String, PaaSTopologyDeploymentContext> entry : activeDeployments.entrySet()) {
            String key = entry.getKey();
            PaaSTopologyDeploymentContext ctx = entry.getValue();
            log.info("Active deployment: " + key);
            doUpdateDeploymentInfo(ctx);
        }
    }

    /**
     * Get status of a deployment
     *
     * @param ctx      the deployment context
     * @param callback callback when the status is available
     */
    @Override
    public void getStatus(PaaSDeploymentContext ctx, IPaaSCallback<DeploymentStatus> callback) {
        DeploymentStatus status;
        String paasId = ctx.getDeploymentPaaSId();
        JanusRuntimeDeploymentInfo jrdi = runtimeDeploymentInfos.get(paasId);
        if (jrdi == null) {
            status = DeploymentStatus.UNDEPLOYED;
        } else {
            status = jrdi.getStatus();
        }
        callback.onSuccess(status);
    }

    /**
     * Deploy a topology
     *
     * @param ctx the PaaSTopologyDeploymentContext of the deployment
     * @param callback to call when deployment is done or has failed.
     */
    @Override
    public void deploy(PaaSTopologyDeploymentContext ctx, IPaaSCallback<?> callback) {
        addTask(new DeployTask(ctx, this, callback));
    }

    /**
     * Undeploy a given topology.
     * @param ctx the context of the un-deployment
     * @param callback
     */
    @Override
    public void undeploy(PaaSDeploymentContext ctx, IPaaSCallback<?> callback) {
        addTask(new UndeployTask(ctx, this, callback));
    }

    /**
     * Scale up/down a node
     *
     * @param ctx  the deployment context
     * @param node id of the compute node to scale up
     * @param nbi  the number of instances to be added (if positive) or removed (if negative)
     * @param callback
     */
    @Override
    public void scale(PaaSDeploymentContext ctx, String node, int nbi, IPaaSCallback<?> callback) {
        addTask(new ScaleTask(ctx, this, node, nbi, callback));
    }

    /**
     * Launch a workflow.
     *
     * @param ctx the deployment context
     * @param workflowName      the workflow to launch
     * @param inputs            the workflow params
     * @param callback
     */
    @Override
    public void launchWorkflow(PaaSDeploymentContext ctx, String workflowName, Map<String, Object> inputs, IPaaSCallback<?> callback) {
        addTask(new WorkflowTask(ctx, this, workflowName, inputs, callback));
    }

    /**
     * Trigger a custom command on a node
     *
     * @param ctx      the deployment context
     * @param request  An object of type {@link NodeOperationExecRequest} describing the operation's execution request
     * @param callback the callback that will be triggered when the operation's result become available
     * @throws OperationExecutionException
     */
    @Override
    public void executeOperation(PaaSTopologyDeploymentContext ctx, NodeOperationExecRequest request, IPaaSCallback<Map<String, String>> callback) throws OperationExecutionException {
        addTask(new OperationTask(ctx, this, request, callback));
    }

    /**
     * Get instance information of a topology from the PaaS
     *
     * @param ctx      the deployment context
     * @param callback callback when the information is available
     */
    @Override
    public void getInstancesInformation(PaaSTopologyDeploymentContext ctx, IPaaSCallback<Map<String, Map<String, InstanceInformation>>> callback) {
        String paasId = ctx.getDeploymentPaaSId();
        JanusRuntimeDeploymentInfo jrdi = runtimeDeploymentInfos.get(paasId);
        log.debug(paasId + " getInstancesInformation");
        if (jrdi != null) {
            callback.onSuccess(jrdi.getInstanceInformations());
        } else {
            log.warn("No information about this deployment: " + paasId);
            log.warn("Assuming that it has been undeployed");
            callback.onSuccess(Maps.newHashMap());
        }
    }

    /**
     * Get all audit events that occurred since the given date.
     * The events must be ordered by date as we could use this method to iterate
     * through events in case of many events.
     *
     * @param date      The start date since which we should retrieve events.
     * @param maxEvents The maximum number of events to return.
     * @param callback
     * @return An array of time ordered audit events with a maximum size of maxEvents.
     */
    @Override
    public void getEventsSince(Date date, int maxEvents, IPaaSCallback<AbstractMonitorEvent[]> callback) {
        // TODO parameters date and maxevents should be considered
        synchronized(toBeDeliveredEvents) {
            AbstractMonitorEvent[] events = toBeDeliveredEvents.toArray(new AbstractMonitorEvent[toBeDeliveredEvents.size()]);
            callback.onSuccess(events);
            toBeDeliveredEvents.clear();
        }
    }

    /**
     * Switch the maintenance mode for this deployed topology.
     *
     * @param  ctx the deployment context
     * @param  maintenanceModeOn
     * @throws MaintenanceModeException
     */
    @Override
    public void switchMaintenanceMode(PaaSDeploymentContext ctx, boolean maintenanceModeOn) throws MaintenanceModeException {
        String paasId = ctx.getDeploymentPaaSId();
        JanusRuntimeDeploymentInfo jrdi = runtimeDeploymentInfos.get(paasId);
        log.debug(paasId + " switchMaintenanceMode");
        if (jrdi == null) {
            log.error(paasId + " switchMaintenanceMode: No Deployment Information");
            throw new MaintenanceModeException("No Deployment Information");
        }

        Topology topology = jrdi.getDeploymentContext().getDeploymentTopology();
        Map<String, Map<String, InstanceInformation>> nodes = jrdi.getInstanceInformations();
        if (nodes == null || nodes.isEmpty()) {
            log.error(paasId + " switchMaintenanceMode: No Node found");
            throw new MaintenanceModeException("No Node found");
        }
        for (Entry<String, Map<String, InstanceInformation>> nodeEntry : nodes.entrySet()) {
            String node = nodeEntry.getKey();
            Map<String, InstanceInformation> nodeInstances = nodeEntry.getValue();
            if (nodeInstances != null && !nodeInstances.isEmpty()) {
                NodeTemplate nodeTemplate = topology.getNodeTemplates().get(node);
                NodeType nodeType = toscaTypeSearchService.getRequiredElementInDependencies(NodeType.class, nodeTemplate.getType(),
                        topology.getDependencies());
                if (ToscaUtils.isFromType(NormativeComputeConstants.COMPUTE_TYPE, nodeType)) {
                    for (Entry<String, InstanceInformation> nodeInstanceEntry : nodeInstances.entrySet()) {
                        String instance = nodeInstanceEntry.getKey();
                        InstanceInformation iinfo = nodeInstanceEntry.getValue();
                        if (iinfo != null) {
                            doSwitchInstanceMaintenanceMode(paasId, node, instance, iinfo, maintenanceModeOn);
                        }
                    }
                }
            }
        }

    }

    /**
     * Switch the maintenance mode for a given node instance of this deployed topology.
     *
     * @param ctx the deployment context
     * @param node
     * @param instance
     * @param mode
     * @throws MaintenanceModeException
     */
    @Override
    public void switchInstanceMaintenanceMode(PaaSDeploymentContext ctx, String node, String instance, boolean mode) throws MaintenanceModeException {
        String paasId = ctx.getDeploymentPaaSId();
        JanusRuntimeDeploymentInfo jrdi = runtimeDeploymentInfos.get(paasId);
        log.debug(paasId + " switchInstanceMaintenanceMode");
        if (jrdi == null) {
            log.error(paasId + " switchInstanceMaintenanceMode: No Deployment Information");
            throw new MaintenanceModeException("No Deployment Information");
        }
        final Map<String, Map<String, InstanceInformation>> existingInformations = jrdi.getInstanceInformations();
        if (existingInformations != null && existingInformations.containsKey(node)
                && existingInformations.get(node).containsKey(instance)) {
            InstanceInformation iinfo = existingInformations.get(node).get(instance);
            doSwitchInstanceMaintenanceMode(paasId, node, instance, iinfo, mode);
        }
    }


    // ------------------------------------------------------------------------------------------------------
    // IConfigurablePaaSProvider implementation
    // ------------------------------------------------------------------------------------------------------

    /**
     * Set / apply a configuration for a PaaS provider
     *
     * @param configuration The configuration object as edited by the user.
     * @throws PluginConfigurationException In case the PaaS provider configuration is incorrect.
     */
    @Override
    public void setConfiguration(ProviderConfig configuration) throws PluginConfigurationException {
        log.info("set config for JanusPaaSProvider");
        providerConfiguration = configuration;
        restClient.setProviderConfiguration(providerConfiguration);
    }

    /**
     * Change status of the deployment in JanusRuntimeDeploymentInfo
     * @param paasId
     * @param status
     */
    public void changeStatus(final String paasId, final DeploymentStatus status) {
        JanusRuntimeDeploymentInfo jrdi = runtimeDeploymentInfos.get(paasId);
        if (jrdi == null) {
            log.error("JanusRuntimeDeploymentInfo is null. paasId=" + paasId);
            return;
        }
        synchronized (jrdi) {
            doChangeStatus(paasId, status);
        }
    }

    /**
     * Actually change the status of the deployment in JanusRuntimeDeploymentInfo
     * Must be called with lock on jrdi
     * @param paasId
     * @param status
     */
    public void doChangeStatus(String paasId, DeploymentStatus status) {
        JanusRuntimeDeploymentInfo jrdi = runtimeDeploymentInfos.get(paasId);
        if (jrdi == null) {
            log.error("JanusRuntimeDeploymentInfo is null for paasId " + paasId);
            return;
        }
        DeploymentStatus oldDeploymentStatus = jrdi.getStatus();
        log.debug("Deployment [" + paasId + "] moved from status [" + oldDeploymentStatus + "] to [" + status + "]");
        jrdi.setStatus(status);

        PaaSDeploymentStatusMonitorEvent event = new PaaSDeploymentStatusMonitorEvent();
        event.setDeploymentStatus(status);
        postEvent(event, paasId);
    }

    // ------------------------------------------------------------------------------------------------------
    // private methods
    // ------------------------------------------------------------------------------------------------------

    /**
     * Notify a4c that a workflow has been started
     * @param paasId
     * @param workflow
     * @param subworkflow
     */
    protected void workflowStarted(String paasId, String workflow, String subworkflow) {
        PaaSWorkflowMonitorEvent event = new PaaSWorkflowMonitorEvent();
        // TODO
        event.setSubworkflow(subworkflow);
        event.setWorkflowId(workflow);
        postEvent(event, paasId);
    }

    /**
     * Notify a4c that a workflow has reached a step
     * @param paasId
     * @param workflow
     * @param node
     * @param step
     * @param stage
     */
    protected void workflowStep(String paasId, String workflow, String node, String step, String stage) {
        PaaSWorkflowStepMonitorEvent event = new PaaSWorkflowStepMonitorEvent();
        // TODO
        event.setWorkflowId(workflow);
        event.setStepId(step);
        event.setStage(stage);
        event.setNodeId(node);
        postEvent(event, paasId);
    }

    /**
     * Update Instance State and notify alien4cloud if needed
     * @param paasId Deployment PaaS Id
     * @param nodeId
     * @param instanceId
     * @param iinfo
     * @param state
     */
    public void updateInstanceState(String paasId, String nodeId, String instanceId, InstanceInformation iinfo, String state) {
        log.debug("paasId=" + paasId + " : set instance state:  " + instanceId + "=" + state);

        // update InstanceInformation
        InstanceStatus status = getInstanceStatusFromState(state);
        iinfo.setState(state);
        iinfo.setInstanceStatus(status);

        // Notify a4c
        PaaSInstanceStateMonitorEvent event = new PaaSInstanceStateMonitorEvent();
        event.setInstanceId(instanceId);
        event.setInstanceState(state);
        event.setInstanceStatus(status);
        event.setNodeTemplateId(nodeId);
        event.setRuntimeProperties(iinfo.getRuntimeProperties());
        event.setAttributes(iinfo.getAttributes());
        postEvent(event, paasId);
    }

    /**
     * Deliver a PaaSMessageMonitorEvent to alien4cloud
     * @param paasId
     * @param message
     */
    protected void sendMessage(final String paasId, final String message) {
        PaaSMessageMonitorEvent event = new PaaSMessageMonitorEvent();
        event.setMessage(message);
        postEvent(event, paasId);
    }

    /**
     * Update Deployment Info from Janus information
     * Called at init, for each active deployment.
     * @param ctx
     */
    protected void doUpdateDeploymentInfo(PaaSTopologyDeploymentContext ctx) {
        String paasId = ctx.getDeploymentPaaSId();
        a4cDeploymentIds.put(paasId, ctx.getDeploymentId());
        String deploymentUrl = "/deployments/" + paasId;
        log.debug("update deployment info " + paasId);

        // Create the JanusRuntimeDeploymentInfo for this deployment
        Map<String, Map<String, InstanceInformation>> nodemap = Maps.newHashMap();
        JanusRuntimeDeploymentInfo jrdi = new JanusRuntimeDeploymentInfo(ctx, DeploymentStatus.UNKNOWN, nodemap, deploymentUrl);
        runtimeDeploymentInfos.put(paasId, jrdi);

        DeploymentStatus ds = null;
        try {
            ds = updateNodeInfo(ctx);
        } catch (Exception e) {
            log.error(paasId + " : Cannot update DeploymentInfo ", e);
        }

        // Restart threads listening to janus log and events
        if (ds != DeploymentStatus.UNDEPLOYED ) {
            taskManager.addTask(new EventListenerTask(ctx, this));
            taskManager.addTask(new LogListenerTask(ctx, this));
        }
    }

    /**
     * Update nodeInformation in the JanusRuntimeDeploymentInfo
     * This is needed to let a4c know all about the nodes and their instances
     * Information is got from Janus using the REST API
     *
     * @param ctx PaaSDeploymentContext to be updated
     * @return deployment status
     *
     * @throws
     */
    private DeploymentStatus updateNodeInfo(PaaSDeploymentContext ctx) throws Exception {
        String paasId = ctx.getDeploymentPaaSId();
        String deploymentUrl = "/deployments/" + paasId;
        log.debug("updateNodeInfo " + paasId);

        // Assumes JanusRuntimeDeploymentInfo already created.
        JanusRuntimeDeploymentInfo jrdi = runtimeDeploymentInfos.get(paasId);
        if (jrdi == null) {
            log.error("No JanusRuntimeDeploymentInfo");
            return DeploymentStatus.FAILURE;
        }

        // Find the deployment info from Janus
        DeployInfosResponse deployRes = restClient.getDeploymentInfosFromJanus(deploymentUrl);
        DeploymentStatus ds = getDeploymentStatusFromString(deployRes.getStatus());
        jrdi.setStatus(ds);

        Map<String, Map<String, InstanceInformation>> nodemap = jrdi.getInstanceInformations();

        // Look every node we want to update.
        for (Link nodeLink : deployRes.getLinks()) {
            if (nodeLink.getRel().equals("node")) {

                // Find the node info from Janus
                NodeInfosResponse nodeRes = restClient.getNodesInfosFromJanus(nodeLink.getHref());
                String node = nodeRes.getName();

                Map<String, InstanceInformation> instanceMap = nodemap.get(node);
                if (instanceMap == null) {
                    // This node was unknown. Create it.
                    instanceMap = Maps.newHashMap();
                    nodemap.put(node, instanceMap);
                }
                // Find information about all the node instances from Janus
                for (Link instanceLink : nodeRes.getLinks()) {
                    if (instanceLink.getRel().equals("instance")) {

                        // Find the instance info from Janus
                        InstanceInfosResponse instRes = restClient.getInstanceInfosFromJanus(instanceLink.getHref());

                        String inb = instRes.getId();
                        InstanceInformation iinfo = instanceMap.get(inb);
                        if (iinfo == null) {
                            // This instance was unknown. create it.
                            iinfo = newInstance(new Integer(inb));
                            instanceMap.put(inb, iinfo);
                        }
                        for (Link link : instRes.getLinks()) {
                            if (link.getRel().equals("attribute")) {
                                // Get the attribute from Janus
                                AttributeResponse attrRes = restClient.getAttributeFromJanus(link.getHref());
                                iinfo.getAttributes().put(attrRes.getName(), attrRes.getValue());
                                log.debug("Attribute: " + attrRes.getName() + "=" + attrRes.getValue());
                            }
                        }
                        // Let a4c know the instance state
                        updateInstanceState(paasId, node, inb, iinfo, instRes.getStatus());
                    }
                }
            }
        }
        return ds;
    }

    /**
     * Ask Janus the values of all attributes for this node/instance
     * This method should never throw Exception.
     * @param ctx
     * @param node
     * @param instance
     */
    public void updateInstanceAttributes(PaaSDeploymentContext ctx, InstanceInformation iinfo, String node, String instance) {
        String paasId = ctx.getDeploymentPaaSId();
        String url = "/deployments/" + paasId + "/nodes/" + node + "/instances/" + instance;
        InstanceInfosResponse instInfoRes;
        try {
            instInfoRes = restClient.getInstanceInfosFromJanus(url);
        } catch (Exception e) {
            log.error("Could not get instance info: ", e);
            sendMessage(paasId, "Could not get instance info: " + e.getMessage());
            return;
        }
        for (Link link : instInfoRes.getLinks()) {
            if (link.getRel().equals("attribute")) {
                try {
                    // Get the attribute from Janus
                    AttributeResponse attrRes = restClient.getAttributeFromJanus(link.getHref());
                    iinfo.getAttributes().put(attrRes.getName(), attrRes.getValue());
                    log.debug("Attribute: " + attrRes.getName() + "=" + attrRes.getValue());
                } catch (Exception e) {
                    log.error("Error getting instance attribute " + link.getHref());
                    sendMessage(paasId, "Error getting instance attribute " + link.getHref());
                }
            }
        }
    }

    /**
     * Switch Instance Maintenance Mode for this instance
     * TODO This is inefficient: No call to janus !
     * @param paasId
     * @param node
     * @param instance
     * @param iinfo
     * @param on
     */
    private void doSwitchInstanceMaintenanceMode(String paasId, String node, String instance, InstanceInformation iinfo, boolean on) {
        if (on && iinfo.getInstanceStatus() == InstanceStatus.SUCCESS) {
            log.debug(String.format("switching instance MaintenanceMode ON for node <%s>, instance <%s>", node, instance));
            updateInstanceState(paasId, node, instance, iinfo, "maintenance");
        } else if (!on && iinfo.getInstanceStatus() == InstanceStatus.MAINTENANCE) {
            log.debug(String.format("switching instance MaintenanceMode OFF for node <%s>, instance <%s>", node, instance));
            updateInstanceState(paasId, node, instance, iinfo, "started");
        }
    }

    public RelationshipType getRelationshipType(String typeName) {
        return toscaTypeSearchService.findMostRecent(RelationshipType.class, typeName);
    }

    public InstanceInformation newInstance(int i) {
        Map<String, String> attributes = Maps.newHashMap();
        Map<String, String> runtimeProperties = Maps.newHashMap();
        Map<String, String> outputs = Maps.newHashMap();
        return new InstanceInformation(ToscaNodeLifecycleConstants.INITIAL, InstanceStatus.PROCESSING, attributes, runtimeProperties, outputs);
    }

    /**
     * return Instance Status from the instance state
     * See janus/tosca/states.go (_NodeState_name) but other states may exist for custom commands
     * @param state
     * @return
     */
    private static InstanceStatus getInstanceStatusFromState(String state) {
        switch (state) {
            case "started":
            case "published":
            case "finished":
            case "done":
                return InstanceStatus.SUCCESS;
            case "deleted":
                return null;
            case "error":
                return InstanceStatus.FAILURE;
            default:
                return InstanceStatus.PROCESSING;
        }
    }

    /**
     * Post an Event for alien4cloud
     * @param event AbstractMonitorEvent
     * @param paasId
     */
    protected void postEvent(AbstractMonitorEvent event, String paasId) {
        event.setDate((new Date()).getTime());
        event.setDeploymentId(a4cDeploymentIds.get(paasId));
        event.setOrchestratorId(paasId);
        if (event.getDeploymentId() == null) {
            log.error("Must provide an Id for this Event: " + event.toString());
            Thread.dumpStack();
            return;
        }
        synchronized (toBeDeliveredEvents) {
            toBeDeliveredEvents.add(event);
        }
    }

    /**
     * Maps janus DeploymentStatus in alien4cloud DeploymentStatus.
     * See janus/deployments/structs.go to see all possible values
     * @param state
     * @return
     */
    private static DeploymentStatus getDeploymentStatusFromString(String state) {
        switch (state) {
            case "DEPLOYED":
                return DeploymentStatus.DEPLOYED;
            case "UNDEPLOYED":
                return DeploymentStatus.UNDEPLOYED;
            case "DEPLOYMENT_IN_PROGRESS":
            case "SCALING_IN_PROGRESS":
                return DeploymentStatus.DEPLOYMENT_IN_PROGRESS;
            case "UNDEPLOYMENT_IN_PROGRESS":
                return DeploymentStatus.UNDEPLOYMENT_IN_PROGRESS;
            case "INITIAL":
                return DeploymentStatus.INIT_DEPLOYMENT;
            case "DEPLOYMENT_FAILED":
            case "UNDEPLOYMENT_FAILED":
                return DeploymentStatus.FAILURE;
            default:
                return DeploymentStatus.UNKNOWN;
        }
    }

}

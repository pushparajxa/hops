/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with this
 * work for additional information regarding copyright ownership. The ASF
 * licenses this file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package org.apache.hadoop.yarn.server.resourcemanager.rmnode;

import com.google.common.annotations.VisibleForTesting;
import io.hops.ha.common.TransactionState;
import io.hops.ha.common.TransactionStateImpl;
import io.hops.metadata.util.RMUtilities;
import io.hops.metadata.yarn.TablesDef;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.classification.InterfaceAudience.Private;
import org.apache.hadoop.classification.InterfaceStability.Unstable;
import org.apache.hadoop.net.NetUtils;
import org.apache.hadoop.net.Node;
import org.apache.hadoop.security.UserGroupInformation;
import org.apache.hadoop.yarn.api.records.ApplicationId;
import org.apache.hadoop.yarn.api.records.ContainerId;
import org.apache.hadoop.yarn.api.records.ContainerState;
import org.apache.hadoop.yarn.api.records.ContainerStatus;
import org.apache.hadoop.yarn.api.records.NodeId;
import org.apache.hadoop.yarn.api.records.NodeState;
import org.apache.hadoop.yarn.api.records.Resource;
import org.apache.hadoop.yarn.api.records.ResourceOption;
import org.apache.hadoop.yarn.event.EventHandler;
import org.apache.hadoop.yarn.factories.RecordFactory;
import org.apache.hadoop.yarn.factory.providers.RecordFactoryProvider;
import org.apache.hadoop.yarn.server.api.protocolrecords.NodeHeartbeatResponse;
import org.apache.hadoop.yarn.server.api.records.NodeHealthStatus;
import org.apache.hadoop.yarn.server.resourcemanager.ClusterMetrics;
import org.apache.hadoop.yarn.server.resourcemanager.NodesListManagerEvent;
import org.apache.hadoop.yarn.server.resourcemanager.NodesListManagerEventType;
import org.apache.hadoop.yarn.server.resourcemanager.RMContext;
import org.apache.hadoop.yarn.server.resourcemanager.recovery.RMStateStore;
import org.apache.hadoop.yarn.server.resourcemanager.scheduler.event.NodeAddedSchedulerEvent;
import org.apache.hadoop.yarn.server.resourcemanager.scheduler.event.NodeRemovedSchedulerEvent;
import org.apache.hadoop.yarn.server.resourcemanager.scheduler.event.NodeUpdateSchedulerEvent;
import org.apache.hadoop.yarn.server.utils.BuilderUtils.ContainerIdComparator;
import org.apache.hadoop.yarn.state.InvalidStateTransitonException;
import org.apache.hadoop.yarn.state.MultipleArcTransition;
import org.apache.hadoop.yarn.state.SingleArcTransition;
import org.apache.hadoop.yarn.state.StateMachine;
import org.apache.hadoop.yarn.state.StateMachineFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock.ReadLock;
import java.util.concurrent.locks.ReentrantReadWriteLock.WriteLock;

/**
 * This class is used to keep track of all the applications/containers running
 * on a node.
 */
@Private
@Unstable
@SuppressWarnings("unchecked")
public class RMNodeImpl implements RMNode, EventHandler<RMNodeEvent> {


  public boolean getNextHeartbeat() {
    return this.nextHeartBeat;
  }

  public String getCurrentState() {
    return this.stateMachine.getCurrentState().toString();
  }

  //Used for testing and setting objects when retrieving RMNode from NDB
  public Map<ContainerId, ContainerStatus> getJustLaunchedContainers() {
    return this.justLaunchedContainers;
  }

  public void setJustLaunchedContainers(
      Map<ContainerId, ContainerStatus> justLaunchedContainers) {
    this.justLaunchedContainers.putAll(justLaunchedContainers);
  }

  public void setFinishedApplications(
      List<ApplicationId> finishedApplications) {
    this.finishedApplications.addAll(finishedApplications);
  }

  public void setContainersToClean(Set<ContainerId> containersToClean) {
    this.containersToClean.addAll(containersToClean);
  }

  public void setLatestNodeHBResponse(NodeHeartbeatResponse hb) {
    this.latestNodeHeartBeatResponse = hb;
  }

  public void setUpdatedContainerInfo(
      ConcurrentLinkedQueue<UpdatedContainerInfo> nodeUpdateQueue) {
    this.nodeUpdateQueue.addAll(nodeUpdateQueue);
  }

  public void setUpdatedContainerInfoId(int updatedContainerInfoId) {
    this.updatedContainerInfoId = updatedContainerInfoId;
  }

  public int getUpdatedContainerInfoId() {
    return this.updatedContainerInfoId;
  }

  /**
   * Recovers state from NDB into RMNode impl.
   *
   * @param state
   */
  @Override
  public void recover(RMStateStore.RMState state) {
    //Retrieve RMNode justLaunchedContainers
    this.justLaunchedContainers
        .putAll(state.getRMNodeJustLaunchedContainers(this.nodeId.toString()));
    //Retrieve RMNode containersToClean
    this.containersToClean.addAll(state.getContainersToClean(this.nodeId.
        toString()));

    //Retrieve RMNode finishedApplications
    this.finishedApplications.addAll(state.getFinishedApplications(this.nodeId.
        toString()));

    //Retrieve NodeUpdateQueue
    List<UpdatedContainerInfo> nodeUpdateToRecover = state.
        getUpdatedContainerInfo(this.nodeId.toString());
    this.nodeUpdateQueue.addAll(nodeUpdateToRecover);
    if (!nodeUpdateToRecover.isEmpty()) {
      updatedContainerInfoId = nodeUpdateToRecover.get(nodeUpdateToRecover.
          size() - 1).getUpdatedContainerInfoId();
    }
    //Retrieve latestNodeHeartBeatResponse
    if (state.getNodeHeartBeatResponse(this.nodeId.toString()) != null) {
      this.latestNodeHeartBeatResponse =
          state.getNodeHeartBeatResponse(this.nodeId.toString());
    }

  }

  public void setState(String state) {
    this.stateMachine.setCurrentState(NodeState.valueOf(state));
  }

  private static final Log LOG = LogFactory.getLog(RMNodeImpl.class);
  private static final RecordFactory recordFactory =
      RecordFactoryProvider.getRecordFactory(null);
  private final ReadLock readLock;
  private final WriteLock writeLock;
  private final ConcurrentLinkedQueue<UpdatedContainerInfo> nodeUpdateQueue;
      //recovered
  private int updatedContainerInfoId = 0;
  private volatile boolean nextHeartBeat = true; //recovered
  private volatile boolean persisted = true;
  private final NodeId nodeId; //recovered
  private final RMContext context; //recovered
  private final String hostName; //recovered
  private final int commandPort; //recovered
  private final int httpPort; //recovered
  private final String nodeAddress; // The containerManager address //recovered
  private final String httpAddress; //recovered
  private volatile ResourceOption resourceOption; //recovered
  private final Node node; //recovered
  private String healthReport; //recovered
  private long lastHealthReportTime; //recovered
  private final String nodeManagerVersion; //recovered
  private static boolean isDistributedRTEnabled = false;
  

  /*
   * set of containers that have just launched
   */
  private final Map<ContainerId, ContainerStatus> justLaunchedContainers =
      new HashMap<ContainerId, ContainerStatus>(); // recovered

  /*
   * set of containers that need to be cleaned
   */
  private final Set<ContainerId> containersToClean =
      new TreeSet<ContainerId>(new ContainerIdComparator()); // recovered

  /*
   * the list of applications that have finished and need to be purged
   */
  private final List<ApplicationId> finishedApplications =
      new ArrayList<ApplicationId>();//recovered
  private NodeHeartbeatResponse latestNodeHeartBeatResponse =
      recordFactory.newRecordInstance(NodeHeartbeatResponse.class); //recovered
  private static final StateMachineFactory<RMNodeImpl, NodeState, RMNodeEventType, RMNodeEvent>
      stateMachineFactory =
      new StateMachineFactory<RMNodeImpl, NodeState, RMNodeEventType, RMNodeEvent>(
          NodeState.NEW)
          //Transitions from NEW state
          .addTransition(NodeState.NEW, NodeState.RUNNING,
              RMNodeEventType.STARTED, new AddNodeTransition())
              //Transitions from RUNNING state
          .addTransition(NodeState.RUNNING,
              EnumSet.of(NodeState.RUNNING, NodeState.UNHEALTHY),
              RMNodeEventType.STATUS_UPDATE,
              new StatusUpdateWhenHealthyTransition())
          .addTransition(NodeState.RUNNING, NodeState.DECOMMISSIONED,
              RMNodeEventType.DECOMMISSION,
              new DeactivateNodeTransition(NodeState.DECOMMISSIONED))
          .addTransition(NodeState.RUNNING, NodeState.LOST,
              RMNodeEventType.EXPIRE,
              new DeactivateNodeTransition(NodeState.LOST))
          .addTransition(NodeState.RUNNING, NodeState.REBOOTED,
              RMNodeEventType.REBOOTING,
              new DeactivateNodeTransition(NodeState.REBOOTED))
          .addTransition(NodeState.RUNNING, NodeState.RUNNING,
              RMNodeEventType.CLEANUP_APP, new CleanUpAppTransition())
          .addTransition(NodeState.RUNNING, NodeState.RUNNING,
              RMNodeEventType.CLEANUP_CONTAINER,
              new CleanUpContainerTransition()).addTransition(NodeState.RUNNING,
          NodeState.RUNNING, RMNodeEventType.RECONNECTED,
          new ReconnectNodeTransition())
          //Transitions from UNHEALTHY state
          .addTransition(NodeState.UNHEALTHY,
              EnumSet.of(NodeState.UNHEALTHY, NodeState.RUNNING),
              RMNodeEventType.STATUS_UPDATE,
              new StatusUpdateWhenUnHealthyTransition())
          .addTransition(NodeState.UNHEALTHY, NodeState.DECOMMISSIONED,
              RMNodeEventType.DECOMMISSION,
              new DeactivateNodeTransition(NodeState.DECOMMISSIONED))
          .addTransition(NodeState.UNHEALTHY, NodeState.LOST,
              RMNodeEventType.EXPIRE,
              new DeactivateNodeTransition(NodeState.LOST))
          .addTransition(NodeState.UNHEALTHY, NodeState.REBOOTED,
              RMNodeEventType.REBOOTING,
              new DeactivateNodeTransition(NodeState.REBOOTED))
          .addTransition(NodeState.UNHEALTHY, NodeState.UNHEALTHY,
              RMNodeEventType.RECONNECTED, new ReconnectNodeTransition())
          .addTransition(NodeState.UNHEALTHY, NodeState.UNHEALTHY,
              RMNodeEventType.CLEANUP_APP,
              new CleanUpAppTransition()).addTransition(NodeState.UNHEALTHY,
          NodeState.UNHEALTHY, RMNodeEventType.CLEANUP_CONTAINER,
          new CleanUpContainerTransition())
          // create the topology tables
          .installTopology();
  private final StateMachine<NodeState, RMNodeEventType, RMNodeEvent>
      stateMachine; //recovered

  public RMNodeImpl(NodeId nodeId, RMContext context, String hostName,
      int cmPort, int httpPort, Node node, ResourceOption resourceOption,
      String nodeManagerVersion, boolean isDistributedRTEnabled) {
    this.nodeId = nodeId;
    this.context = context;
    this.hostName = hostName;
    this.commandPort = cmPort;
    this.httpPort = httpPort;
    this.resourceOption = resourceOption;
    this.nodeAddress = hostName + ":" + cmPort;
    this.httpAddress = hostName + ":" + httpPort;
    this.node = node;
    this.healthReport = "Healthy";
    this.lastHealthReportTime = System.currentTimeMillis();
    this.nodeManagerVersion = nodeManagerVersion;

    this.latestNodeHeartBeatResponse.setResponseId(0);

    ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    this.readLock = lock.readLock();
    this.writeLock = lock.writeLock();

    this.stateMachine = stateMachineFactory.make(this);

    this.nodeUpdateQueue = new ConcurrentLinkedQueue<UpdatedContainerInfo>();
    
    this.isDistributedRTEnabled = isDistributedRTEnabled;
    updatedContainerInfoId = 0;
  }

  public RMNodeImpl(NodeId nodeId, RMContext context, String hostName,
      int cmPort, int httpPort, Node node, ResourceOption resourceOption,
      String nodeManagerVersion, String healthReport, long lastHealthReportTime,
      boolean nextHeartBeat, boolean isDistributedRTEnabled) {
    this(nodeId, context, hostName, cmPort, httpPort, node, resourceOption,
        nodeManagerVersion, isDistributedRTEnabled);
    this.healthReport = healthReport;
    this.lastHealthReportTime = lastHealthReportTime;
    this.nextHeartBeat = nextHeartBeat;
  }

  @Override
  public String toString() {
    return this.nodeId.toString();
  }

  @Override
  public String getHostName() {
    return hostName;
  }

  @Override
  public int getCommandPort() {
    return commandPort;
  }

  @Override
  public int getHttpPort() {
    return httpPort;
  }

  @Override
  public NodeId getNodeID() {
    return this.nodeId;
  }

  @Override
  public String getNodeAddress() {
    return this.nodeAddress;
  }

  @Override
  public String getHttpAddress() {
    return this.httpAddress;
  }

  @Override
  public Resource getTotalCapability() {
    return this.resourceOption.getResource();
  }

  @Override
  public void setResourceOption(ResourceOption resourceOption) {
    this.resourceOption = resourceOption;
  }

  @Override
  public ResourceOption getResourceOption() {
    return this.resourceOption;
  }

  @Override
  public String getRackName() {
    return node.getNetworkLocation();
  }

  @Override
  public Node getNode() {
    return this.node;
  }

  @Override
  public String getHealthReport() {
    this.readLock.lock();

    try {
      return this.healthReport;
    } finally {
      this.readLock.unlock();
    }
  }

  public void setHealthReport(String healthReport) {
    this.writeLock.lock();

    try {
      this.healthReport = healthReport;
    } finally {
      this.writeLock.unlock();
    }
  }

  public void setLastHealthReportTime(long lastHealthReportTime) {
    this.writeLock.lock();

    try {
      this.lastHealthReportTime = lastHealthReportTime;
    } finally {
      this.writeLock.unlock();
    }
  }

  @Override
  public long getLastHealthReportTime() {
    this.readLock.lock();

    try {
      return this.lastHealthReportTime;
    } finally {
      this.readLock.unlock();
    }
  }

  @Override
  public String getNodeManagerVersion() {
    return nodeManagerVersion;
  }

  @Override
  public NodeState getState() {
    this.readLock.lock();

    try {
      return this.stateMachine.getCurrentState();
    } finally {
      this.readLock.unlock();
    }
  }

  @Override
  public List<ApplicationId> getAppsToCleanup() {
    this.readLock.lock();

    try {
      return new ArrayList<ApplicationId>(this.finishedApplications);
    } finally {
      this.readLock.unlock();
    }

  }

  @Override
  public List<ContainerId> getContainersToCleanUp() {

    this.readLock.lock();

    try {
      return new ArrayList<ContainerId>(this.containersToClean);
    } finally {
      this.readLock.unlock();
    }
  }

  @Override
  public void updateNodeHeartbeatResponseForCleanup(
      NodeHeartbeatResponse response, TransactionState transactionState) {
    this.writeLock.lock();
    if (isDistributedRTEnabled &&
        !this.context.getRMGroupMembershipService().isLeader()) {
      try {
        LOG.
            debug(
                "HOP :: retrieving containersToClean and FinishedApplications");
        RMUtilities.setContainersToCleanAndFinishedApplications(this.nodeId.
            toString(), this.containersToClean, this.finishedApplications);
      } catch (IOException ex) {
        LOG.error(ex);
      }
    }
    LOG.debug("HOP :: containersToClean=" + containersToClean +
        ", finishedApplications=" + finishedApplications);
    //Fetch containersToClean, FinishedApplications from NDB
    try {
      response.addAllContainersToCleanup(
          new ArrayList<ContainerId>(this.containersToClean));
      response.addAllApplicationsToCleanup(this.finishedApplications);
      LOG.debug(
          "HOP :: containersToClean.clear(), finishedApplications.clear() on node " +
              this.nodeId.toString());
      //HOP :: Remove containers from state
      if (transactionState instanceof TransactionStateImpl) {
        for (ContainerId cid : this.containersToClean) {
          ((TransactionStateImpl) transactionState).getRMNodeInfo(this.nodeId.
              toString()).toRemoveContainerToClean(cid.toString());
        }
        for (ApplicationId appId : this.finishedApplications) {
          ((TransactionStateImpl) transactionState).getRMNodeInfo(this.nodeId.
              toString()).toRemoveFinishedApplications(appId.toString());
        }
      }
      this.containersToClean.clear();
      this.finishedApplications.clear();

    } finally {
      this.writeLock.unlock();
    }
  }

  @Override
  public NodeHeartbeatResponse getLastNodeHeartBeatResponse() {

    this.readLock.lock();

    try {
      return this.latestNodeHeartBeatResponse;
    } finally {
      this.readLock.unlock();
    }
  }

  public void handle(RMNodeEvent event) {
    LOG.debug(
        "Processing " + event.getNodeId() + " of type " + event.getType());
    try {
      writeLock.lock();
      NodeState oldState = getState();
      try {
        stateMachine.doTransition(event.getType(), event);
        if (event.getTransactionState() != null) {
          ((TransactionStateImpl) event.getTransactionState())
              .toUpdateRMNode(this);
        }
      } catch (InvalidStateTransitonException e) {
        LOG.error("Can't handle this event at current state", e);
        LOG.error(
            "Invalid event " + event.getType() + " on Node  " + this.nodeId);
      }

      if (oldState != getState()) {
        LOG.info(nodeId + " Node Transitioned from " + oldState + " to " +
            getState());
      }
    } finally {
      writeLock.unlock();
    }
  }

  private void updateMetricsForRejoinedNode(NodeState previousNodeState) {
    ClusterMetrics metrics = ClusterMetrics.getMetrics();
    metrics.incrNumActiveNodes();

    switch (previousNodeState) {
      case LOST:
        metrics.decrNumLostNMs();
        break;
      case REBOOTED:
        metrics.decrNumRebootedNMs();
        break;
      case DECOMMISSIONED:
        metrics.decrDecommisionedNMs();
        break;
      case UNHEALTHY:
        metrics.decrNumUnhealthyNMs();
        break;
    }
  }

  private void updateMetricsForDeactivatedNode(NodeState initialState,
      NodeState finalState) {
    ClusterMetrics metrics = ClusterMetrics.getMetrics();

    switch (initialState) {
      case RUNNING:
        metrics.decrNumActiveNodes();
        break;
      case UNHEALTHY:
        metrics.decrNumUnhealthyNMs();
        break;
    }

    // Decomissioned NMs equals to the nodes missing in include list (if
    // include list not empty) or the nodes listed in excluded list.
    // DecomissionedNMs as per exclude list is set upfront when the
    // exclude list is read so that RM restart can also reflect the
    // decomissionedNMs. Note that RM is still not able to know decomissionedNMs
    // as per include list after it restarts as they are known when those nodes
    // come for registration.
    // DecomissionedNMs as per include list is incremented in this transition.
    switch (finalState) {
      case DECOMMISSIONED:
        Set<String> ecludedHosts =
            context.getNodesListManager().getHostsReader().
                getExcludedHosts();
        if (!ecludedHosts.contains(hostName) &&
            !ecludedHosts.contains(NetUtils.normalizeHostName(hostName))) {
          metrics.incrDecommisionedNMs();
        }
        break;
      case LOST:
        metrics.incrNumLostNMs();
        break;
      case REBOOTED:
        metrics.incrNumRebootedNMs();
        break;
      case UNHEALTHY:
        metrics.incrNumUnhealthyNMs();
        break;
    }
  }

  public static class AddNodeTransition
      implements SingleArcTransition<RMNodeImpl, RMNodeEvent> {

    @Override
    public void transition(RMNodeImpl rmNode, RMNodeEvent event) {
      // Inform the scheduler
      LOG.debug("HOP :: Transition AddNodeTransition");
      //If distributedRT is enabled and if HA is disabled or HA is enabled
      // and I am not Leader, persist event
      if (event.getTransactionState() != null && isDistributedRTEnabled &&
          !rmNode.context.getRMGroupMembershipService().isLeader()) {
        //Add NodeAddedSchedulerEvent to TransactionState
        LOG.debug("HOP :: Added Pending event to TransactionState");
        ((TransactionStateImpl) event.getTransactionState()).
            addPendingEventToAdd(rmNode.getNodeID().toString(),
                TablesDef.PendingEventTableDef.NODE_ADDED, TablesDef.PendingEventTableDef.NEW);
      } else {
        rmNode.context.getDispatcher().getEventHandler().handle(
            new NodeAddedSchedulerEvent(rmNode, event.getTransactionState()));
      }

      rmNode.context.getDispatcher().getEventHandler().handle(
          new NodesListManagerEvent(NodesListManagerEventType.NODE_USABLE,
              rmNode, event.
              getTransactionState()));

      String host = rmNode.nodeId.getHost();
      if (rmNode.context.getInactiveRMNodes().containsKey(host)) {
        // Old node rejoining
        RMNode previouRMNode = rmNode.context.getInactiveRMNodes().get(host);
        rmNode.context.getInactiveRMNodes().remove(host);
        //Remove entry from NDB
        ((TransactionStateImpl) event.getTransactionState()).getRMContextInfo().
            toRemoveInactiveRMNode(previouRMNode.getNodeID());
        rmNode.updateMetricsForRejoinedNode(previouRMNode.getState());
      } else {
        // Increment activeNodes explicitly because this is a new node.
        ClusterMetrics.getMetrics().incrNumActiveNodes();
        //TODO: Check if we need to include this in the TS. 
      }
    }
  }

  public static class ReconnectNodeTransition
      implements SingleArcTransition<RMNodeImpl, RMNodeEvent> {

    @Override
    public void transition(RMNodeImpl rmNode, RMNodeEvent event) {
      // Kill containers since node is rejoining.
      LOG.debug("HOP :: Transition ReconnectNodeTransition");
      LOG.debug("HOP :: nodeUpdateQueue.clear(), size=" +
          rmNode.nodeUpdateQueue.size());
      ((TransactionStateImpl) event.getTransactionState())
          .getRMNodeInfo(rmNode.nodeId.toString())
          .toRemoveNodeUpdateQueue(rmNode.nodeUpdateQueue);
      rmNode.nodeUpdateQueue.clear();
      if (isDistributedRTEnabled &&
          !rmNode.context.getRMGroupMembershipService().isLeader()) {
        //Add NodeRemovedSchedulerEvent to TransactionState
        LOG.debug("HOP :: Added Pending event to TransactionState");
        ((TransactionStateImpl) event.getTransactionState()).
            addPendingEventToAdd(rmNode.getNodeID().toString(),
                TablesDef.PendingEventTableDef.NODE_REMOVED, TablesDef.PendingEventTableDef.NEW);
      } else {
        rmNode.context.getDispatcher().getEventHandler()
            .handle(new NodeRemovedSchedulerEvent(rmNode, event.
                    getTransactionState()));
      }

      RMNode newNode = ((RMNodeReconnectEvent) event).getReconnectedNode();
      if (rmNode.getTotalCapability().equals(newNode.getTotalCapability()) &&
          rmNode.getHttpPort() == newNode.getHttpPort()) {
        // Reset heartbeat ID since node just restarted.
        rmNode.getLastNodeHeartBeatResponse().setResponseId(0);
        if (rmNode.getState() != NodeState.UNHEALTHY) {
          // Only add new node if old state is not UNHEALTHY
          if (isDistributedRTEnabled &&
              !rmNode.context.getRMGroupMembershipService().isLeader()) {
            //Add NodeAddedSchedulerEvent to TransactionState
            LOG.debug("HOP :: Added Pending event to TransactionState");
            ((TransactionStateImpl) event.getTransactionState()).
                addPendingEventToAdd(rmNode.getNodeID().toString(),
                    TablesDef.PendingEventTableDef.NODE_ADDED, TablesDef.PendingEventTableDef.NEW);

          } else {
            rmNode.context.getDispatcher().getEventHandler()
                .handle(new NodeAddedSchedulerEvent(rmNode, event.
                        getTransactionState()));
          }

        }
      } else {
        // Reconnected node differs, so replace old node and start new node
        //TODO: Update metrics in TransactionState
        switch (rmNode.getState()) {
          case RUNNING:
            ClusterMetrics.getMetrics().decrNumActiveNodes();
            break;
          case UNHEALTHY:
            ClusterMetrics.getMetrics().decrNumUnhealthyNMs();
            break;
        }
        //HOP :: This will update the current rows in NDB
        ((TransactionStateImpl) event.getTransactionState()).getRMContextInfo().
            toAddActiveRMNode(newNode.getNodeID(), newNode);
        ((TransactionStateImpl) event.getTransactionState())
            .getRMNodeInfo(newNode.getNodeID().toString())
            .toAddNextHeartbeat(newNode.
                    getNodeID().toString(),
                ((RMNodeImpl) rmNode).getNextHeartbeat());
        rmNode.context.getActiveRMNodes().put(newNode.getNodeID(), newNode);
        rmNode.context.getDispatcher().getEventHandler().handle(
            new RMNodeEvent(newNode.getNodeID(), RMNodeEventType.STARTED,
                event.getTransactionState()));
      }
      //TODO: Add this event to TransactionState
      rmNode.context.getDispatcher().getEventHandler().handle(
          new NodesListManagerEvent(NodesListManagerEventType.NODE_USABLE,
              rmNode, event.
              getTransactionState()));
    }
  }

  public static class CleanUpAppTransition
      implements SingleArcTransition<RMNodeImpl, RMNodeEvent> {

    @Override
    public void transition(RMNodeImpl rmNode, RMNodeEvent event) {
      LOG.debug("HOP :: Transition CleanUpAppTransition  add " +
          ((RMNodeCleanAppEvent) event).getAppId() + " on node " +
          rmNode.nodeId.toString());
      rmNode.finishedApplications.add(((RMNodeCleanAppEvent) event).getAppId());
      ((TransactionStateImpl) event.getTransactionState())
          .getRMNodeInfo(rmNode.nodeId.toString())
          .toAddFinishedApplications(((RMNodeCleanAppEvent) event).getAppId());
    }
  }

  public static class CleanUpContainerTransition
      implements SingleArcTransition<RMNodeImpl, RMNodeEvent> {

    @Override
    public void transition(RMNodeImpl rmNode, RMNodeEvent event) {
      LOG.debug("HOP :: Transition CleanUpContainerTransition");
      rmNode.containersToClean.add(((RMNodeCleanContainerEvent) event).
          getContainerId());
      if (event.getTransactionState() != null &&
          event.getTransactionState() instanceof TransactionStateImpl) {
        ((TransactionStateImpl) event.getTransactionState())
            .getRMNodeInfo(rmNode.nodeId.toString()).toAddContainerToClean(
            ((RMNodeCleanContainerEvent) event).getContainerId());
      }
      LOG.debug("HOP :: containerToClean.add");
    }
  }

  public static class DeactivateNodeTransition
      implements SingleArcTransition<RMNodeImpl, RMNodeEvent> {

    private final NodeState finalState;

    public DeactivateNodeTransition(NodeState finalState) {
      this.finalState = finalState;
    }

    @Override
    public void transition(RMNodeImpl rmNode, RMNodeEvent event) {
      // Inform the scheduler
      LOG.debug("HOP :: Transition DeactivateNodeTransition");
      LOG.debug("HOP :: nodeUpdateQueue.clear(), size=" +
          rmNode.nodeUpdateQueue.size());
      ((TransactionStateImpl) event.getTransactionState())
          .getRMNodeInfo(rmNode.nodeId.toString())
          .toRemoveNodeUpdateQueue(rmNode.nodeUpdateQueue);
      rmNode.nodeUpdateQueue.clear();
      // If the current state is NodeState.UNHEALTHY
      // Then node is already been removed from the
      // Scheduler
      NodeState initialState = rmNode.getState();
      if (!initialState.equals(NodeState.UNHEALTHY)) {
        if (isDistributedRTEnabled &&
            !rmNode.context.getRMGroupMembershipService().isLeader()) {
          //Add NodeRemovedSchedulerEvent to TransactionState
          LOG.debug("HOP :: Added Pending event to TransactionState");
          ((TransactionStateImpl) event.getTransactionState()).
              addPendingEventToAdd(rmNode.getNodeID().toString(),
                  TablesDef.PendingEventTableDef.NODE_REMOVED, TablesDef.PendingEventTableDef.NEW);

        } else {
          rmNode.context.getDispatcher().getEventHandler()
              .handle(new NodeRemovedSchedulerEvent(rmNode, event.
                  getTransactionState()));
        }

      }
      rmNode.context.getDispatcher().getEventHandler().handle(
          new NodesListManagerEvent(NodesListManagerEventType.NODE_UNUSABLE,
              rmNode, event.
              getTransactionState()));

      // Deactivate the node
      rmNode.context.getActiveRMNodes().remove(rmNode.nodeId);
      LOG.info(
          "Deactivating Node " + rmNode.nodeId + " as it is now " + finalState);
      rmNode.context.getInactiveRMNodes().put(rmNode.nodeId.getHost(), rmNode);
      //HOP :: Update TransactionState
      ((TransactionStateImpl) event.getTransactionState()).getRMContextInfo().
          toRemoveActiveRMNode(rmNode.getNodeID());
      ((TransactionStateImpl) event.getTransactionState()).getRMContextInfo().
          toAddInactiveRMNode(rmNode.nodeId, rmNode);

      //Update the metrics
      rmNode.updateMetricsForDeactivatedNode(initialState, finalState);
    }
  }

  public static class StatusUpdateWhenHealthyTransition
      implements MultipleArcTransition<RMNodeImpl, RMNodeEvent, NodeState> {

    @Override
    public NodeState transition(RMNodeImpl rmNode, RMNodeEvent event) {

      LOG.debug("HOP :: Transition StatusUpdateWhenHealthyTransition - START");
      RMNodeStatusEvent statusEvent = (RMNodeStatusEvent) event;

      // Switch the last heartbeatresponse.
      rmNode.latestNodeHeartBeatResponse = statusEvent.getLatestResponse();
      //Update TransactionState with heartbeat
      ((TransactionStateImpl) event.getTransactionState())
          .getRMNodeInfo(rmNode.nodeId.toString())
          .toAddLatestNodeHeartBeatResponse(rmNode.latestNodeHeartBeatResponse);

      NodeHealthStatus remoteNodeHealthStatus =
          statusEvent.getNodeHealthStatus();
      rmNode.setHealthReport(remoteNodeHealthStatus.getHealthReport());
      rmNode.setLastHealthReportTime(remoteNodeHealthStatus.
          getLastHealthReportTime());
      //HOP :: Update TransactionState
      ((TransactionStateImpl) event.getTransactionState()).
          toUpdateRMNode(rmNode);

      if (!remoteNodeHealthStatus.getIsNodeHealthy()) {
        LOG.info(
            "Node " + rmNode.nodeId + " reported UNHEALTHY with details: " +
                remoteNodeHealthStatus.getHealthReport());
        LOG.debug("HOP :: nodeUpdateQueue.clear(), size=" +
            rmNode.nodeUpdateQueue.size());
        ((TransactionStateImpl) event.getTransactionState())
            .getRMNodeInfo(rmNode.nodeId.toString())
            .toRemoveNodeUpdateQueue(rmNode.nodeUpdateQueue);
        rmNode.nodeUpdateQueue.clear();
        // Inform the scheduler
        if (isDistributedRTEnabled &&
            !rmNode.context.getRMGroupMembershipService().isLeader()) {
          //Add NodeRemovedSchedulerEvent to TransactionState
          LOG.debug("HOP :: Added Pending event to TransactionState");
          ((TransactionStateImpl) event.getTransactionState()).
              addPendingEventToAdd(rmNode.getNodeID().toString(),
                  TablesDef.PendingEventTableDef.NODE_REMOVED, TablesDef.PendingEventTableDef.NEW);

        } else {
          rmNode.context.getDispatcher().getEventHandler()
              .handle(new NodeRemovedSchedulerEvent(rmNode, event.
                      getTransactionState()));
        }

        rmNode.context.getDispatcher().getEventHandler().handle(
            new NodesListManagerEvent(NodesListManagerEventType.NODE_UNUSABLE,
                rmNode, event.
                getTransactionState()));
        // Update metrics
        rmNode.updateMetricsForDeactivatedNode(rmNode.getState(),
            NodeState.UNHEALTHY);
        return NodeState.UNHEALTHY;
      }

      // Filter the map to only obtain just launched containers and finished
      // containers.
      List<ContainerStatus> newlyLaunchedContainers =
          new ArrayList<ContainerStatus>();
      List<ContainerStatus> completedContainers =
          new ArrayList<ContainerStatus>();
      for (ContainerStatus remoteContainer : statusEvent.getContainers()) {
        ContainerId containerId = remoteContainer.getContainerId();

        // Don't bother with containers already scheduled for cleanup, or for
        // applications already killed. The scheduler doens't need to know any
        // more about this container
        //Retrieve containersToClean from NDB
        if (isDistributedRTEnabled &&
            !rmNode.context.getRMGroupMembershipService().isLeader()) {
          try {
            LOG.debug(
                "HOP :: retrieving containersToClean and FinishedApplications");
            RMUtilities.setContainersToCleanAndFinishedApplications(rmNode.
                    getNodeID().toString(), rmNode.containersToClean,
                rmNode.finishedApplications);
          } catch (IOException ex) {
            LOG.error(
                "HOP :: Error retrieveing updateNodeHeartbeatResponseForCleanup data:",
                ex);
          }
        }
        if (rmNode.containersToClean.contains(containerId)) {
          LOG.info("Container " + containerId + " already scheduled for " +
              "cleanup, no further processing");
          continue;
        }
        if (rmNode.finishedApplications.contains(
            containerId.getApplicationAttemptId().getApplicationId())) {
          LOG.info("Container " + containerId +
              " belongs to an application that is already killed," +
              " no further processing");
          continue;
        }
        LOG.debug("HOP :: remoteContainer.getState-" + remoteContainer.
            getState());
        // Process running containers
        if (remoteContainer.getState() == ContainerState.RUNNING) {
          if (!rmNode.justLaunchedContainers.containsKey(containerId)) {
            // Just launched container. RM knows about it the first time.
            LOG.debug("HOP :: justlaunched put containerId=" + containerId);
            rmNode.justLaunchedContainers.put(containerId, remoteContainer);
            ((TransactionStateImpl) event.getTransactionState())
                .getRMNodeInfo(rmNode.nodeId.toString())
                .toAddJustLaunchedContainers(containerId, remoteContainer);
            newlyLaunchedContainers.add(remoteContainer);
          }
        } else {
          // A finished container
          LOG.debug(
              "HOP :: justlaunched remove containerId (finished container)=" +
                  containerId);
          if (rmNode.justLaunchedContainers.remove(containerId) != null) {
            ((TransactionStateImpl) event.getTransactionState())
                .getRMNodeInfo(rmNode.nodeId.toString())
                .toRemoveJustLaunchedContainers(containerId.toString());
          }
          completedContainers.add(remoteContainer);

        }
      }
      if (newlyLaunchedContainers.size() != 0 ||
          completedContainers.size() != 0) {
        UpdatedContainerInfo uci =
            new UpdatedContainerInfo(newlyLaunchedContainers,
                completedContainers, rmNode.updatedContainerInfoId++);
        LOG.debug("HOP :: nodeUpdateQueue.add-" + uci);
        rmNode.nodeUpdateQueue.add(uci);
        //
        ((TransactionStateImpl) event.getTransactionState())
            .getRMNodeInfo(rmNode.nodeId.toString()).toAddNodeUpdateQueue(uci);

      }
      
      if (isDistributedRTEnabled &&
          !rmNode.context.getRMGroupMembershipService().isLeader()) {
        try {
          if (rmNode.persisted && !rmNode.nextHeartBeat) {
            rmNode.nextHeartBeat =
                RMUtilities.getNextHeartbeat(rmNode.nodeId.toString());
            LOG.debug(
                "HOP :: rmNode.heartbeat NDB:" + rmNode.nextHeartBeat + " " +
                    rmNode.nodeId);
          }
        } catch (IOException ex) {
          LOG.debug("HOP :: Error while retrieving nextHeartBeat for rmNode:" +
              rmNode.nodeId, ex);
        }
      }
      //HOP :: Get nextHeartBeat from NDB
      LOG.debug(
          "HOP :: next herbeat node " + rmNode.nextHeartBeat + " nexthb " +
              rmNode.nextHeartBeat);
      if (rmNode.nextHeartBeat) {
        LOG.debug(
            "HOP :: rmNode.heartbeat-set to false:" + rmNode.nodeId.toString());
        rmNode.nextHeartBeat = false;
        rmNode.persisted = false;
        ((TransactionStateImpl) event.getTransactionState())
            .getRMNodeInfo(rmNode.nodeId.toString())
            .toAddNextHeartbeat(rmNode.nodeId.
                toString(), rmNode.nextHeartBeat);
        if (isDistributedRTEnabled &&
            !rmNode.context.getRMGroupMembershipService().isLeader()) {
          //Add NodeUpdatedSchedulerEvent to TransactionState
          LOG.debug(
              "HOP :: StatusUpdateWhenHealthyTransition-Added Pending event to TransactionState");
          ((TransactionStateImpl) event.getTransactionState()).
              addPendingEventToAdd(rmNode.getNodeID().toString(),
                  TablesDef.PendingEventTableDef.NODE_UPDATED, TablesDef.PendingEventTableDef.NEW,
                  rmNode);
        } else {
          LOG.debug(
              "HOP :: StatusUpdateWhenHealthyTransition- handle NodeUpdate");
          rmNode.context.getDispatcher().getEventHandler()
              .handle(new NodeUpdateSchedulerEvent(rmNode, event.
                      getTransactionState()));
        }
      }

      //TODO: Consider adding this to TransactionState
      // Update DTRenewer in secure mode to keep these apps alive. Today this is
      // needed for log-aggregation to finish long after the apps are gone.
      if (UserGroupInformation.isSecurityEnabled()) {
        rmNode.context.getDelegationTokenRenewer()
            .updateKeepAliveApplications(statusEvent.getKeepAliveAppIds());
      }
      return NodeState.RUNNING;
    }
  }

  public void setPersisted(boolean persisted) {
    this.persisted = persisted;
    LOG.debug("seting persisted to " + persisted + " heartbeat node " +
        this.nodeId.toString());
  }
  
  public boolean getPersisted() {
    return this.persisted;
  }
  
  public static class StatusUpdateWhenUnHealthyTransition
      implements MultipleArcTransition<RMNodeImpl, RMNodeEvent, NodeState> {

    @Override
    public NodeState transition(RMNodeImpl rmNode, RMNodeEvent event) {

      LOG.debug("HOP :: Transition StatusUpdateWhenUnHealthyTransition");
      RMNodeStatusEvent statusEvent = (RMNodeStatusEvent) event;

      // Switch the last heartbeatresponse.
      rmNode.latestNodeHeartBeatResponse = statusEvent.getLatestResponse();
      //Update TransactionState with heartbeat
      ((TransactionStateImpl) event.getTransactionState())
          .getRMNodeInfo(rmNode.nodeId.toString())
          .toAddLatestNodeHeartBeatResponse(rmNode.latestNodeHeartBeatResponse);

      NodeHealthStatus remoteNodeHealthStatus = statusEvent.
          getNodeHealthStatus();
      rmNode.setHealthReport(remoteNodeHealthStatus.getHealthReport());
      rmNode.setLastHealthReportTime(remoteNodeHealthStatus.
          getLastHealthReportTime());
      //HOP :: Update TransactionState
      ((TransactionStateImpl) event.getTransactionState()).
          toUpdateRMNode(rmNode);

      if (remoteNodeHealthStatus.getIsNodeHealthy()) {
        if (isDistributedRTEnabled &&
            !rmNode.context.getRMGroupMembershipService().isLeader()) {
          //Add NodeAddedSchedulerEvent to TransactionState
          LOG.debug("HOP :: Added Pending event to TransactionState");
          ((TransactionStateImpl) event.getTransactionState()).
              addPendingEventToAdd(rmNode.getNodeID().toString(),
                  TablesDef.PendingEventTableDef.NODE_ADDED, TablesDef.PendingEventTableDef.NEW);

        } else {
          rmNode.context.getDispatcher().getEventHandler()
              .handle(new NodeAddedSchedulerEvent(rmNode, event.
                      getTransactionState()));
        }

        rmNode.context.getDispatcher().getEventHandler().handle(
            new NodesListManagerEvent(NodesListManagerEventType.NODE_USABLE,
                rmNode, event.
                getTransactionState()));
        // ??? how about updating metrics before notifying to ensure that
        // notifiers get update metadata because they will very likely query it
        // upon notification
        // Update metrics
        rmNode.updateMetricsForRejoinedNode(NodeState.UNHEALTHY);
        return NodeState.RUNNING;
      }
      return NodeState.UNHEALTHY;
    }
  }

  @Override
  public List<UpdatedContainerInfo> pullContainerUpdates(TransactionState ts) {
    List<UpdatedContainerInfo> latestContainerInfoList =
        new ArrayList<UpdatedContainerInfo>();
    while (nodeUpdateQueue.peek() != null) {
      LOG.debug("HOP :: pullContainerUpdates:" + nodeUpdateQueue);
      //HOP :: Update TransactionState
      if (ts != null) {
        ((TransactionStateImpl) ts).getRMNodeInfo(this.nodeId.toString()).
            toRemoveNodeUpdateQueue(nodeUpdateQueue.peek());
        latestContainerInfoList.add(nodeUpdateQueue.poll());
      }
    }
    LOG.debug("HOP :: rmNode.heartbeat-set to true:" + this.nodeId.toString());
    this.nextHeartBeat = true;

    //HOP :: Update RMNode
    if (ts != null) {
      ((TransactionStateImpl) ts).getRMNodeInfo(this.nodeId.toString())
          .toAddNextHeartbeat(this.nodeId.
              toString(), this.nextHeartBeat);
      //((TransactionStateImpl) ts).toUpdateRMNode(this);
    }
    LOG.debug("HOP :: latestContainerInfoList:" + latestContainerInfoList);
    return latestContainerInfoList;
  }

  @VisibleForTesting
  public void setNextHeartBeat(boolean nextHeartBeat) {
    this.nextHeartBeat = nextHeartBeat;
  }

  @VisibleForTesting
  public int getQueueSize() {
    return nodeUpdateQueue.size();
  }

  @VisibleForTesting
  public ConcurrentLinkedQueue<UpdatedContainerInfo> getQueue() {
    return nodeUpdateQueue;
  }
}

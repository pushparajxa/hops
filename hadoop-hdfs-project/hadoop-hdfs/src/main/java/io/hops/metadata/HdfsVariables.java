/*
 * Copyright (C) 2015 hops.io.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.hops.metadata;

import io.hops.common.CountersQueue;
import io.hops.exception.StorageException;
import io.hops.exception.TransactionContextException;
import io.hops.leaderElection.VarsRegister;
import io.hops.metadata.common.entity.*;
import io.hops.metadata.hdfs.dal.VariableDataAccess;
import io.hops.metadata.hdfs.snapshots.SnapShotConstants;
import io.hops.transaction.handler.HDFSOperationType;
import io.hops.transaction.handler.LightWeightRequestHandler;
import org.apache.commons.digester.substitution.VariableAttributes;
import org.apache.commons.io.output.ByteArrayOutputStream;
import org.apache.hadoop.hdfs.security.token.block.BlockKey;
import org.apache.hadoop.hdfs.server.common.StorageInfo;
import org.apache.hadoop.hdfs.server.namenode.RemoveSnapshotManager;
import org.apache.hadoop.hdfs.server.namenode.RollBackManager;
import org.apache.hadoop.hdfs.server.namenode.SnapShotManager;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class HdfsVariables {

  public static CountersQueue.Counter incrementBlockIdCounter(
      final int increment) throws IOException {
    return (CountersQueue.Counter) new LightWeightRequestHandler(
        HDFSOperationType.UPDATE_BLOCK_ID_COUNTER) {
      @Override
      public Object performTask() throws IOException {
        return incrementCounter(Variable.Finder.BlockID, increment);
      }
    }.handle();
  }

  public static CountersQueue.Counter incrementINodeIdCounter(
      final int increment) throws IOException {
    return (CountersQueue.Counter) new LightWeightRequestHandler(
        HDFSOperationType.UPDATE_INODE_ID_COUNTER) {
      @Override
      public Object performTask() throws IOException {
        return incrementCounter(Variable.Finder.INodeID, increment);
      }
    }.handle();
  }

  public static CountersQueue.Counter incrementQuotaUpdateIdCounter(
      final int increment) throws IOException {
    return (CountersQueue.Counter) new LightWeightRequestHandler(
        HDFSOperationType.UPDATE_INODE_ID_COUNTER) {
      @Override
      public Object performTask() throws IOException {
        return incrementCounter(Variable.Finder.QuotaUpdateID, increment);
      }
    }.handle();
  }

  private static CountersQueue.Counter incrementCounter(Variable.Finder
      finder, int increment)
      throws StorageException {
    VariableDataAccess<Variable, Variable.Finder> vd = (VariableDataAccess)
        HdfsStorageFactory.getDataAccess(VariableDataAccess.class);
    HdfsStorageFactory.getConnector().writeLock();
    Variable variable =  vd.getVariable(finder);
    if(variable instanceof IntVariable){
      int oldValue = ((IntVariable) vd.getVariable(finder)).getValue();
      int newValue = oldValue + increment;
      vd.setVariable(new IntVariable(finder, newValue));
      HdfsStorageFactory.getConnector().readCommitted();
      return new CountersQueue.Counter(oldValue, newValue);

    }else if(variable instanceof LongVariable){
      long oldValue = ((LongVariable) variable).getValue();
      long newValue = oldValue + increment;
      vd.setVariable(new LongVariable(finder, newValue));
      HdfsStorageFactory.getConnector().readCommitted();
      return new CountersQueue.Counter(oldValue, newValue);
    }
    throw new IllegalStateException("Cannot increment Varaible of type " +
        variable.getClass().getSimpleName());
  }

  public static void resetMisReplicatedIndex() throws IOException {
    incrementMisReplicatedIndex(0);
  }

  public static Long incrementMisReplicatedIndex(final int increment)
      throws IOException {
    return (Long) new LightWeightRequestHandler(
        HDFSOperationType.INCREMENT_MIS_REPLICATED_FILES_INDEX) {
      @Override
      public Object performTask() throws IOException {
        VariableDataAccess vd = (VariableDataAccess) HdfsStorageFactory
            .getDataAccess(VariableDataAccess.class);
        HdfsStorageFactory.getConnector().writeLock();
        LongVariable var = (LongVariable) vd
            .getVariable(Variable.Finder.MisReplicatedFilesIndex);
        long oldValue = var == null ? 0 : var.getValue();
        long newValue = increment == 0 ? 0 : oldValue + increment;
        vd.setVariable(new LongVariable(Variable.Finder.MisReplicatedFilesIndex,
            newValue));
        HdfsStorageFactory.getConnector().readCommitted();
        return newValue;
      }
    }.handle();
  }
  
  public static void enterClusterSafeMode() throws IOException {
    new LightWeightRequestHandler(HDFSOperationType.ENTER_CLUSTER_SAFE_MODE) {
      @Override
      public Object performTask() throws IOException {
        VariableDataAccess vd = (VariableDataAccess) HdfsStorageFactory
            .getDataAccess(VariableDataAccess.class);
        HdfsStorageFactory.getConnector().writeLock();
        vd.setVariable(new IntVariable(Variable.Finder.ClusterInSafeMode, 1));
        HdfsStorageFactory.getConnector().readCommitted();
        return null;
      }
    }.handle();
  }
  
  public static void exitClusterSafeMode() throws IOException {
    new LightWeightRequestHandler(HDFSOperationType.EXIT_CLUSTER_SAFE_MODE) {
      @Override
      public Object performTask() throws IOException {
        VariableDataAccess vd = (VariableDataAccess) HdfsStorageFactory
            .getDataAccess(VariableDataAccess.class);
        HdfsStorageFactory.getConnector().writeLock();
        vd.setVariable(new IntVariable(Variable.Finder.ClusterInSafeMode, 0));
        HdfsStorageFactory.getConnector().readCommitted();
        return null;
      }
    }.handle();
  }

  public static boolean isClusterInSafeMode() throws IOException {
    Boolean safemode = (Boolean) new LightWeightRequestHandler(
        HDFSOperationType.GET_CLUSTER_SAFE_MODE) {
      @Override
      public Object performTask() throws IOException {
        VariableDataAccess vd = (VariableDataAccess) HdfsStorageFactory
            .getDataAccess(VariableDataAccess.class);
        HdfsStorageFactory.getConnector().readLock();
        IntVariable var =
            (IntVariable) vd.getVariable(Variable.Finder.ClusterInSafeMode);
        HdfsStorageFactory.getConnector().readCommitted();
        return var.getValue() == 1;
      }
    }.handle();
    return safemode;
  }

  public static void setReplicationIndex(List<Integer> indeces)
      throws StorageException, TransactionContextException {
    Variables.updateVariable(
        new ArrayVariable(Variable.Finder.ReplicationIndex, indeces));
  }

  public static List<Integer> getReplicationIndex()
      throws StorageException, TransactionContextException {
    return (List<Integer>) ((ArrayVariable) Variables
        .getVariable(Variable.Finder.ReplicationIndex)).getVarsValue();
  }

  public static void setStorageInfo(StorageInfo storageInfo)
      throws StorageException, TransactionContextException {
    List<Object> vals = new ArrayList<Object>();
    vals.add(storageInfo.getLayoutVersion());
    vals.add(storageInfo.getNamespaceID());
    vals.add(storageInfo.getClusterID());
    vals.add(storageInfo.getCTime());
    vals.add(storageInfo.getBlockPoolId());
    Variables
        .updateVariable(new ArrayVariable(Variable.Finder.StorageInfo, vals));
  }

  public static StorageInfo getStorageInfo()
      throws StorageException, TransactionContextException {
    ArrayVariable var =
        (ArrayVariable) Variables.getVariable(Variable.Finder.StorageInfo);
    List<Object> vals = (List<Object>) var.getVarsValue();
    return new StorageInfo((Integer) vals.get(0), (Integer) vals.get(1),
        (String) vals.get(2), (Long) vals.get(3), (String) vals.get(4));
  }

  
  public static void updateBlockTokenKeys(BlockKey curr, BlockKey next)
      throws IOException {
    updateBlockTokenKeys(curr, next, null);
  }
  
  public static void updateBlockTokenKeys(BlockKey curr, BlockKey next,
      BlockKey simple) throws IOException {
    ArrayVariable arr = new ArrayVariable(Variable.Finder.BlockTokenKeys);
    arr.addVariable(serializeBlockKey(curr, Variable.Finder.BTCurrKey));
    arr.addVariable(serializeBlockKey(next, Variable.Finder.BTNextKey));
    if (simple != null) {
      arr.addVariable(serializeBlockKey(simple, Variable.Finder.BTSimpleKey));
    }
    Variables.updateVariable(arr);
  }
  
  public static Map<Integer, BlockKey> getAllBlockTokenKeysByID()
      throws IOException {
    return getAllBlockTokenKeys(true, false);
  }

  public static Map<Integer, BlockKey> getAllBlockTokenKeysByType()
      throws IOException {
    return getAllBlockTokenKeys(false, false);
  }

  public static Map<Integer, BlockKey> getAllBlockTokenKeysByIDLW()
      throws IOException {
    return getAllBlockTokenKeys(true, true);
  }

  public static Map<Integer, BlockKey> getAllBlockTokenKeysByTypeLW()
      throws IOException {
    return getAllBlockTokenKeys(false, true);
  }

  public static int getSIdCounter()
      throws StorageException, TransactionContextException {
    return (Integer) Variables.getVariable(Variable.Finder.SIdCounter)
        .getValue();
  }

  public static void setSIdCounter(int sid)
      throws StorageException, TransactionContextException {
    Variables.updateVariable(new IntVariable(Variable.Finder.SIdCounter, sid));
  }
 public static long getMaxNNID()throws StorageException, TransactionContextException {
      return (Long) Variables.getVariable(Variable.Finder.MaxNNID).getValue();
  }
  
  public static void setMaxNNID(long val) throws StorageException, TransactionContextException {
      Variables.updateVariable(new LongVariable(Variable.Finder.MaxNNID, val));
  }

    public static String getRollBackStatus() throws StorageException, TransactionContextException {
        return (String) Variables.getVariable(Variable.Finder.RollBackStatus).getValue();
    }

    public static void setRollBackStatus(String status)  throws StorageException, TransactionContextException {

        Variables.updateVariable(new  StringVariable(Variable.Finder.RollBackStatus, status));
    }

    public static String getRollBackStatus2()  throws IOException {
        return (String) new LightWeightRequestHandler(HDFSOperationType.GET_ROLLBACK_STATUS) {
            @Override
            public Object performTask() throws  IOException {
                VariableDataAccess vd = (VariableDataAccess) HdfsStorageFactory.getDataAccess(VariableDataAccess.class);
                HdfsStorageFactory.getConnector().readCommitted();
                return  ((StringVariable) vd.getVariable(Variable.Finder.RollBackStatus)).getValue();
            }
        }.handle();

    }

    public static boolean setRollBackStatus2(final RollBackManager.RollBackStatus rollBackStatus)  throws IOException {
        return (Boolean) new LightWeightRequestHandler(HDFSOperationType.SET_ROLLBACK_STATUS) {
            @Override
            public Object performTask() throws  IOException {
                VariableDataAccess vd = (VariableDataAccess) HdfsStorageFactory.getDataAccess(VariableDataAccess.class);
                HdfsStorageFactory.getConnector().writeLock();
                vd.setVariable(new StringVariable(Variable.Finder.RollBackStatus, rollBackStatus.toString()));
                return true;
            }
        }.handle();

    }

    private static Map<Integer, BlockKey> getAllBlockTokenKeys(boolean useKeyId,
      boolean leightWeight) throws IOException {
    List<Variable> vars = (List<Variable>) (leightWeight ?
        getVariableLightWeight(Variable.Finder.BlockTokenKeys).getValue() :
        Variables.getVariable(Variable.Finder.BlockTokenKeys).getValue());
    Map<Integer, BlockKey> keys = new HashMap<Integer, BlockKey>();
    for (Variable var : vars) {
      BlockKey key = deserializeBlockKey((ByteArrayVariable) var);
      int mapKey = useKeyId ? key.getKeyId() : key.getKeyType().ordinal();
      keys.put(mapKey, key);
    }
    return keys;
  }
  
  private static ByteArrayVariable serializeBlockKey(BlockKey key,
      Variable.Finder keyType) throws IOException {
    ByteArrayOutputStream os = new ByteArrayOutputStream();
    DataOutputStream dos = new DataOutputStream(os);
    key.write(dos);
    dos.flush();
    return new ByteArrayVariable(keyType, os.toByteArray());
  }

  private static BlockKey deserializeBlockKey(ByteArrayVariable var)
      throws IOException {
    ByteArrayInputStream is = new ByteArrayInputStream((byte[]) var.getValue());
    DataInputStream dis = new DataInputStream(is);
    BlockKey key = new BlockKey();
    key.readFields(dis);
    switch (var.getType()) {
      case BTCurrKey:
        key.setKeyType(BlockKey.KeyType.CurrKey);
        break;
      case BTNextKey:
        key.setKeyType(BlockKey.KeyType.NextKey);
        break;
      case BTSimpleKey:
        key.setKeyType(BlockKey.KeyType.SimpleKey);
    }
    return key;
  }
  
  private static Variable getVariableLightWeight(final Variable.Finder varType)
      throws IOException {
    return (Variable) new LightWeightRequestHandler(
        HDFSOperationType.GET_VARIABLE) {
      @Override
      public Object performTask() throws IOException {
        VariableDataAccess vd = (VariableDataAccess) HdfsStorageFactory
            .getDataAccess(VariableDataAccess.class);
        return vd.getVariable(varType);
      }
    }.handle();
  }
  public static void registerDefaultValues() {
    Variable.registerVariableDefaultValue(Variable.Finder.BlockID,
        new LongVariable(0).getBytes());
    Variable.registerVariableDefaultValue(Variable.Finder.INodeID,
        new IntVariable(3)
            .getBytes()); // 2 is taken by the root and  1 is parent of the root
    Variable.registerVariableDefaultValue(Variable.Finder.ReplicationIndex,
        new ArrayVariable(Arrays.asList(0, 0, 0, 0, 0)).getBytes());
    Variable.registerVariableDefaultValue(Variable.Finder.SIdCounter,
        new IntVariable(0).getBytes());
    Variable
        .registerVariableDefaultValue(Variable.Finder.MisReplicatedFilesIndex,
            new LongVariable(0).getBytes());
    Variable.registerVariableDefaultValue(Variable.Finder.ClusterInSafeMode,
        new IntVariable(1).getBytes());
    Variable.registerVariableDefaultValue(Variable.Finder.QuotaUpdateID,
        new IntVariable(0).getBytes());
    VarsRegister.registerHdfsDefaultValues();
    // This is a workaround that is needed until HA-YARN has its own format command
    VarsRegister.registerYarnDefaultValues();
    Variable.registerVariableDefaultValue(Variable.Finder.RollBackStatus,new StringVariable(Variable.Finder.RollBackStatus,RollBackManager.RollBackStatus.NOT_STARTED.toString()).getBytes());
    Variable.registerVariableDefaultValue(Variable.Finder.RollBackRequestStatus,new IntVariable(Variable.Finder.RollBackRequestStatus, SnapShotConstants.snapShotNotTaken).getBytes());
    Variable.registerVariableDefaultValue(Variable.Finder.SnapShotStatus,new StringVariable(Variable.Finder.SnapShotStatus, SnapShotManager.SnapshotStatus.NO_SNAPSHOT.toString()).getBytes());
    Variable.registerVariableDefaultValue(Variable.Finder.SnapshotRequestStatus,new StringVariable(Variable.Finder.SnapshotRequestStatus, SnapShotManager.SnapshotRequestStatus.NOT_REQUESTED.toString()).getBytes());
  }

  public static String getSnapShotStatus() throws IOException{
    return (String) new LightWeightRequestHandler(HDFSOperationType.GET_SNAPSHOT_STATUS) {
      @Override
      public Object performTask() throws  IOException {
        VariableDataAccess vd = (VariableDataAccess) HdfsStorageFactory.getDataAccess(VariableDataAccess.class);
        HdfsStorageFactory.getConnector().readCommitted();
        return   vd.getVariable(Variable.Finder.SnapShotStatus);
      }
    }.handle();

  }

  public static boolean setSnapShotStatus(final SnapShotManager.SnapshotStatus snapShotTakenStatus )  throws IOException {
    return (Boolean) new LightWeightRequestHandler(HDFSOperationType.SET_SNAPSHOT_STATUS) {
      @Override
      public Object performTask() throws  IOException {
        VariableDataAccess vd = (VariableDataAccess) HdfsStorageFactory.getDataAccess(VariableDataAccess.class);
        HdfsStorageFactory.getConnector().writeLock();
        vd.setVariable(new StringVariable(Variable.Finder.SnapShotStatus, snapShotTakenStatus.toString()));
        return true;
      }
    }.handle();

  }

  public static String getSnapShotRequestStatus() throws IOException{
    return (String) new LightWeightRequestHandler(HDFSOperationType.GET_SNAPSHOT_REQUEST_STATUS) {
      @Override
      public Object performTask() throws  IOException {
        VariableDataAccess vd = (VariableDataAccess) HdfsStorageFactory.getDataAccess(VariableDataAccess.class);
        HdfsStorageFactory.getConnector().readCommitted();
        return   vd.getVariable(Variable.Finder.SnapshotRequestStatus);
      }
    }.handle();

  }

  public static boolean setSnapShotRequestStatus(final SnapShotManager.SnapshotRequestStatus snapShotRequestStatus )  throws IOException {
    return (Boolean) new LightWeightRequestHandler(HDFSOperationType.SET_SNAPSHOT_REQUEST_STATUS) {
      @Override
      public Object performTask() throws  IOException {
        VariableDataAccess vd = (VariableDataAccess) HdfsStorageFactory.getDataAccess(VariableDataAccess.class);
        HdfsStorageFactory.getConnector().writeLock();
        vd.setVariable(new StringVariable(Variable.Finder.SnapshotRequestStatus, snapShotRequestStatus.toString()));
        return true;
      }
    }.handle();

  }
  public static String getRollBackRequestStatus() throws IOException{
    return (String) new LightWeightRequestHandler(HDFSOperationType.GET_ROLLBACK_REQUEST_STATUS) {
      @Override
      public Object performTask() throws  IOException {
        VariableDataAccess vd = (VariableDataAccess) HdfsStorageFactory.getDataAccess(VariableDataAccess.class);
        HdfsStorageFactory.getConnector().readCommitted();
        return   vd.getVariable(Variable.Finder.RollBackRequestStatus);
      }
    }.handle();

  }

  public static boolean setRollBackRequestStatus(final RollBackManager.RollBackRequestStatus rollBackRequestStatus )  throws IOException {
    return (Boolean) new LightWeightRequestHandler(HDFSOperationType.SET_ROLLBACK_REQUEST_STATUS) {
      @Override
      public Object performTask() throws  IOException {
        VariableDataAccess vd = (VariableDataAccess) HdfsStorageFactory.getDataAccess(VariableDataAccess.class);
        HdfsStorageFactory.getConnector().writeLock();
        vd.setVariable(new StringVariable(Variable.Finder.RollBackRequestStatus, rollBackRequestStatus.toString()));
        return true;
      }
    }.handle();

  }

  public static String getRemoveSnapshotStatus() throws IOException{
    return (String) new LightWeightRequestHandler(HDFSOperationType.GET_REMOVE_SNAPSHOT_STATUS) {
      @Override
      public Object performTask() throws  IOException {
        VariableDataAccess vd = (VariableDataAccess) HdfsStorageFactory.getDataAccess(VariableDataAccess.class);
        HdfsStorageFactory.getConnector().readCommitted();
        return   vd.getVariable(Variable.Finder.RemoveSnapshotStatus);
      }
    }.handle();

  }

  public static boolean setRemoveSnapshotStatus(final RemoveSnapshotManager.ExecutionStatus removeSnapshotStatus )  throws IOException {
    return (Boolean) new LightWeightRequestHandler(HDFSOperationType.SET_REMOVE_SNAPSHOT_STATUS) {
      @Override
      public Object performTask() throws  IOException {
        VariableDataAccess vd = (VariableDataAccess) HdfsStorageFactory.getDataAccess(VariableDataAccess.class);
        HdfsStorageFactory.getConnector().writeLock();
        vd.setVariable(new StringVariable(Variable.Finder.RemoveSnapshotStatus, removeSnapshotStatus.toString()));
        return true;
      }
    }.handle();

  }

}

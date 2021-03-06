/*
 * Copyright 2015 Higher Frequency Trading
 *
 *  http://www.higherfrequencytrading.com
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */


package net.openhft.chronicle.engine.map;

import net.openhft.chronicle.bytes.BytesStore;
import net.openhft.chronicle.core.values.IntValue;
import net.openhft.chronicle.engine.api.EngineReplication;
import net.openhft.chronicle.engine.api.map.KeyValueStore;
import net.openhft.lang.collection.ATSDirectBitSet;
import net.openhft.lang.collection.DirectBitSet;
import net.openhft.lang.io.DirectStore;
import net.openhft.lang.model.Copyable;
import net.openhft.lang.model.DataValueClasses;
import net.openhft.lang.model.constraints.MaxSize;
import org.jetbrains.annotations.NotNull;

import java.io.Closeable;
import java.io.IOException;
import java.util.Iterator;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.function.Consumer;

import static net.openhft.chronicle.engine.map.ChronicleMapBackedEngineReplication.ReplicationData.*;

public class ChronicleMapBackedEngineReplication<Store> implements EngineReplication, Closeable {

    public static final int RESERVED_MOD_ITER = 8;
    public static final int MAX_MODIFICATION_ITERATORS = 127 + RESERVED_MOD_ITER;
    // a long word serve 64 bits
    public static final int DIRTY_WORD_COUNT = (MAX_MODIFICATION_ITERATORS + 63) / 64;

    private static int idToInt(byte identifier) {
        // if we consider > 127 ids, we should treat ids positively
        return identifier & 0xFF;
    }

    interface ChangeApplier<Store> {
        void applyChange(Store store, ReplicationEntry replicationEntry);
    }

    interface GetValue<Store> {
        BytesStore getValue(Store store, BytesStore key);
    }

    interface ReplicationData extends Copyable<ReplicationData> {
        boolean getDeleted();
        void setDeleted(boolean deleted);

        long getTimestamp();
        void setTimestamp(long timestamp);

        byte getIdentifier();
        void setIdentifier(byte identifier);

        long getDirtyWord(@MaxSize(DIRTY_WORD_COUNT) int index);
        void setDirtyWord(@MaxSize(DIRTY_WORD_COUNT) int index, long word);

        static void dropChange(ReplicationData replicationData) {
            for (int i = 0; i < DIRTY_WORD_COUNT; i++) {
                replicationData.setDirtyWord(i, 0);
            }
        }

        static void raiseChange(ReplicationData replicationData) {
            for (int i = 0; i < DIRTY_WORD_COUNT; i++) {
                replicationData.setDirtyWord(i, ~0L);
            }
        }

        static void clearChange(ReplicationData replicationData, int identifier) {
            int index = identifier / 64;
            long bit = 1L << (identifier % 64);
            replicationData.setDirtyWord(index, replicationData.getDirtyWord(index) ^ bit);
        }

        static void setChange(ReplicationData replicationData, int identifier) {
            int index = identifier / 64;
            long bit = 1L << (identifier % 64);
            replicationData.setDirtyWord(index, replicationData.getDirtyWord(index) | bit);
        }

        static boolean isChanged(ReplicationData replicationData, int identifier) {
            int index = identifier / 64;
            long bit = 1L << (identifier % 64);
            return (replicationData.getDirtyWord(index) & bit) != 0L;
        }
    }

    interface RemoteNodeReplicationState extends Copyable<RemoteNodeReplicationState> {
        long getNextBootstrapTimestamp();
        void setNextBootstrapTimestamp(long nextBootstrapTimestamp);

        long getLastBootstrapTimestamp();
        void setLastBootstrapTimestamp(long lastBootstrapTimestamp);

        long getLastModificationTime();
        void setLastModificationTime(long lastModificationTime);
    }

    private static ATSDirectBitSet createModIterBitSet() {
        return new ATSDirectBitSet(new DirectStore(null, DIRTY_WORD_COUNT * 8, true).bytes());
    }

    static class Instances {
        final IntValue identifier = DataValueClasses.newInstance(IntValue.class);

        RemoteNodeReplicationState usingState = null;
        final RemoteNodeReplicationState copyState =
                DataValueClasses.newInstance(RemoteNodeReplicationState.class);
        RemoteNodeReplicationState zeroState =
                DataValueClasses.newInstance(RemoteNodeReplicationState.class);

        ReplicationData usingData = null;
        ReplicationData newData = DataValueClasses.newInstance(ReplicationData.class);
        ReplicationData zeroData = DataValueClasses.newInstance(ReplicationData.class);
    }

    private static void initZeroStateForAllPossibleRemoteIdentifiers(
            KeyValueStore<IntValue, RemoteNodeReplicationState, RemoteNodeReplicationState> modIterState) {
        Instances i = threadLocalInstances.get();
        for (int id = 0; id < 256; id++) {
            i.identifier.setValue(id);
            modIterState.put(i.identifier, i.zeroState);
        }
    }

    private static ThreadLocal<Instances> threadLocalInstances =
            ThreadLocal.withInitial(Instances::new);

    private KeyValueStore<BytesStore, ReplicationData, ReplicationData> keyReplicationData;
    private KeyValueStore<IntValue, RemoteNodeReplicationState, RemoteNodeReplicationState> modIterState;

    private final byte identifier;
    private final Store store;
    private final ChangeApplier<Store> changeApplier;
    private final GetValue<Store> getValue;
    private final AtomicReferenceArray<ChronicleMapBackedModificationIterator> modificationIterators =
            new AtomicReferenceArray<>(127 + RESERVED_MOD_ITER);
    private final DirectBitSet modificationIteratorsRequiringSettingBootstrapTimestamp =
            createModIterBitSet();
    private final DirectBitSet modIterSet = createModIterBitSet();

    public ChronicleMapBackedEngineReplication(
            KeyValueStore<BytesStore, ReplicationData, ReplicationData> keyReplicationData,
            KeyValueStore<IntValue, RemoteNodeReplicationState, RemoteNodeReplicationState> modIterState,
            byte identifier,
            Store store, ChangeApplier<Store> changeApplier, GetValue<Store> getValue) {

        this.keyReplicationData = keyReplicationData;
        this.modIterState = modIterState;
        initZeroStateForAllPossibleRemoteIdentifiers(modIterState);

        this.identifier = identifier;
        this.store = store;
        this.changeApplier = changeApplier;
        this.getValue = getValue;
    }

    @Override
    public byte identifier() {
        return identifier;
    }

    ////////////////
    // Method for working with modIterState

    private void resetNextBootstrapTimestamp(int remoteIdentifier) {
        Instances i = threadLocalInstances.get();
        i.identifier.setValue(remoteIdentifier);
        while (true) {
            i.usingState = modIterState.getUsing(i.identifier, i.usingState);
            i.copyState.copyFrom(i.usingState);
            i.copyState.setNextBootstrapTimestamp(0);
            if (modIterState.replaceIfEqual(i.identifier, i.usingState, i.copyState))
                return;
        }
    }

    private boolean setNextBootstrapTimestamp(int remoteIdentifier, long timestamp) {
        Instances i = threadLocalInstances.get();
        i.identifier.setValue(remoteIdentifier);
        while (true) {
            i.usingState = modIterState.getUsing(i.identifier, i.usingState);
            if (i.usingState.getNextBootstrapTimestamp() != 0)
                return false;
            i.copyState.copyFrom(i.usingState);
            i.copyState.setNextBootstrapTimestamp(0);
            if (modIterState.replaceIfEqual(i.identifier, i.usingState, i.copyState))
                return true;
        }
    }

    private void resetLastBootstrapTimestamp(int remoteIdentifier) {
        Instances i = threadLocalInstances.get();
        i.identifier.setValue(remoteIdentifier);
        while (true) {
            i.usingState = modIterState.getUsing(i.identifier, i.usingState);
            i.copyState.copyFrom(i.usingState);
            i.copyState.setLastBootstrapTimestamp(0);
            if (modIterState.replaceIfEqual(i.identifier, i.usingState, i.copyState))
                return;
        }
    }

    private long bootstrapTimestamp(int remoteIdentifier) {
        Instances i = threadLocalInstances.get();
        i.identifier.setValue(remoteIdentifier);
        while (true) {
            i.usingState = modIterState.getUsing(i.identifier, i.usingState);
            long nextBootstrapTs = i.usingState.getNextBootstrapTimestamp();
            if (nextBootstrapTs == 0) {
                return i.usingState.getLastBootstrapTimestamp();
            } else {
                i.copyState.copyFrom(i.usingState);
                i.copyState.setLastBootstrapTimestamp(nextBootstrapTs);
                if (modIterState.replaceIfEqual(i.identifier, i.usingState, i.copyState))
                    return nextBootstrapTs;
            }
        }
    }

    @Override
    public long lastModificationTime(byte remoteIdentifier) {
        return lastModificationTime(idToInt(remoteIdentifier));
    }

    private long lastModificationTime(int remoteIdentifier) {
        Instances i = threadLocalInstances.get();
        i.identifier.setValue(remoteIdentifier);
        i.usingState = modIterState.getUsing(i.identifier, i.usingState);
        return i.usingState.getLastModificationTime();
    }

    @Override
    public void setLastModificationTime(byte identifier, long timestamp) {
        setLastModificationTime(idToInt(identifier), timestamp);
    }

    private void setLastModificationTime(int identifier, long timestamp) {
        Instances i = threadLocalInstances.get();
        i.identifier.setValue(identifier);
        while (true) {
            i.usingState = modIterState.getUsing(i.identifier, i.usingState);
            if (i.usingState.getLastModificationTime() < timestamp) {
                i.copyState.copyFrom(i.usingState);
                i.copyState.setLastModificationTime(timestamp);
                if (modIterState.replaceIfEqual(i.identifier, i.usingState, i.copyState))
                    return;
            } else {
                return;
            }
        }
    }

    private static boolean shouldApplyRemoteModification(
            ReplicationEntry remoteEntry, ReplicationData localReplicationData) {
        long remoteTimestamp = remoteEntry.timestamp();
        long originTimestamp = localReplicationData.getTimestamp();
        return remoteTimestamp > originTimestamp || (remoteTimestamp == originTimestamp &&
                remoteEntry.identifier() <= localReplicationData.getIdentifier());
    }

    @Override
    public void applyReplication(@NotNull ReplicationEntry replicatedEntry) {
        Instances i = threadLocalInstances.get();
        BytesStore key = replicatedEntry.key();
        while (true) {
            ReplicationData data = keyReplicationData.getUsing(key, i.usingData);
            if (data != null)
                i.usingData = data;
            boolean shouldApplyRemoteModification = data == null ||
                    shouldApplyRemoteModification(replicatedEntry, data);
            if (shouldApplyRemoteModification) {
                i.newData.copyFrom(data != null ? data : i.zeroData);
                changeApplier.applyChange(store, replicatedEntry);
                i.newData.setDeleted(replicatedEntry.isDeleted());
                i.newData.setIdentifier(replicatedEntry.identifier());
                i.newData.setTimestamp(replicatedEntry.timestamp());
                if (data == null) {
                    if (keyReplicationData.putIfAbsent(key, i.newData) == null)
                        return;
                } else {
                    dropChange(i.newData);
                    if (keyReplicationData.replaceIfEqual(key, data, i.newData))
                        return;
                }
            }
        }
    }

    @Override
    public ModificationIterator acquireModificationIterator(byte id) {
        int remoteIdentifier = idToInt(id);

        ModificationIterator modificationIterator = modificationIterators.get(remoteIdentifier);
        if (modificationIterator != null)
            return modificationIterator;

        synchronized (modificationIterators) {
            modificationIterator = modificationIterators.get(remoteIdentifier);

            if (modificationIterator != null)
                return modificationIterator;

            final ChronicleMapBackedModificationIterator newModificationIterator =
                    new ChronicleMapBackedModificationIterator(remoteIdentifier);
            modificationIteratorsRequiringSettingBootstrapTimestamp.set(remoteIdentifier);
            resetNextBootstrapTimestamp(remoteIdentifier);
            // in ChMap 2.1 currentTime() is set as a default lastBsTs; set to 0 here; TODO review
            resetLastBootstrapTimestamp(remoteIdentifier);

            modificationIterators.set(remoteIdentifier, newModificationIterator);
            modIterSet.set(remoteIdentifier);
            return newModificationIterator;
        }
    }

    public void onPut(BytesStore key, long putTimestamp) {
        onChange(key, false, putTimestamp);
    }

    public void onRemove(BytesStore key, long remoteTimestamp) {
        onChange(key, true, remoteTimestamp);
    }

    private void onChange(BytesStore key, boolean deleted, long changeTimestamp) {
        Instances i = threadLocalInstances.get();
        while (true) {
            ReplicationData data = keyReplicationData.getUsing(key, i.usingData);
            if (data != null)
                i.usingData = data;
            i.newData.copyFrom(data != null ? data : i.zeroData);
            i.newData.setDeleted(deleted);
            long entryTimestamp = i.newData.getTimestamp();
            if (entryTimestamp > changeTimestamp)
                changeTimestamp = entryTimestamp + 1;
            i.newData.setTimestamp(changeTimestamp);
            i.newData.setIdentifier(identifier);
            raiseChange(i.newData);

            boolean successfulUpdate = data == null ?
                    (keyReplicationData.putIfAbsent(key, i.newData) == null) :
                    (keyReplicationData.replaceIfEqual(key, data, i.newData));
            if (successfulUpdate) {
                for (long next = modIterSet.nextSetBit(0L); next > 0L;
                     next = modIterSet.nextSetBit(next + 1L)) {
                    ChronicleMapBackedModificationIterator modIter =
                            modificationIterators.get((int) next);
                    modIter.modNotify();
                    if (modificationIteratorsRequiringSettingBootstrapTimestamp.clearIfSet(next)) {
                        if (!setNextBootstrapTimestamp((int) next, changeTimestamp))
                            throw new AssertionError();
                    }
                }
                return;
            }
        }
    }

    class ChronicleMapBackedModificationIterator implements ModificationIterator, ReplicationEntry {

        private final int identifier;

        ChronicleMapBackedModificationIterator(int identifier) {
            this.identifier = identifier;
        }

        long forEachEntryCount;

        @Override
        public void forEach(@NotNull Consumer<ReplicationEntry> consumer)  {
            forEachEntryCount = 0;
            Instances i = threadLocalInstances.get();
            keyReplicationData.keySetIterator().forEachRemaining(key -> {
                i.usingData = keyReplicationData.getUsing(key, i.usingData);
                if (isChanged(i.usingData, identifier)) {
                    this.key = key;
                    this.replicationData = i.usingData;
                    try {
                        consumer.accept(this);
                        i.newData.copyFrom(i.usingData);
                        clearChange(i.newData, identifier);
                        if (!keyReplicationData.replaceIfEqual(key, i.usingData, i.newData))
                            throw new AssertionError();
                        forEachEntryCount++;
                    } finally {
                        this.key = null;
                        this.replicationData = null;
                    }
                }
            });
            if (forEachEntryCount == 0) {
                modificationIteratorsRequiringSettingBootstrapTimestamp.set(identifier);
                resetNextBootstrapTimestamp(identifier);
            }
        }

        @Override
        public boolean hasNext() {
            Instances i = threadLocalInstances.get();
            for (Iterator<BytesStore> keyIt = keyReplicationData.keySetIterator();
                 keyIt.hasNext(); ) {
                BytesStore key = keyIt.next();
                i.usingData = keyReplicationData.getUsing(key, i.usingData);
                if (isChanged(i.usingData, identifier))
                    return true;
            }
            return false;
        }

        @Override
        public void dirtyEntries(long fromTimeStamp) throws InterruptedException {
            Instances i = threadLocalInstances.get();
            keyReplicationData.keySetIterator().forEachRemaining(key -> {
                i.usingData = keyReplicationData.getUsing(key, i.usingData);
                if (i.usingData.getTimestamp() >= fromTimeStamp) {
                    i.newData.copyFrom(i.usingData);
                    setChange(i.newData, identifier);
                    if (!keyReplicationData.replaceIfEqual(key, i.usingData, i.newData))
                        throw new AssertionError();
                }
            });
        }

        ModificationNotifier modificationNotifier;

        @Override
        public void setModificationNotifier(@NotNull ModificationNotifier modificationNotifier) {
            this.modificationNotifier = modificationNotifier;
        }

        public void modNotify() {
            if (modificationNotifier != null)
                modificationNotifier.onChange();
        }

        // Below methods and fields that implement ModIter as ReplicationEntry
        BytesStore key;
        ReplicationData replicationData;

        @Override
        public BytesStore key() {
            return key;
        }

        @Override
        public BytesStore value() {
            return getValue.getValue(store, key);
        }

        @Override
        public long timestamp() {
            return replicationData.getTimestamp();
        }

        @Override
        public byte identifier() {
            return replicationData.getIdentifier();
        }

        @Override
        public boolean isDeleted() {
            return replicationData.getDeleted();
        }

        /**
         * @return the timestamp  that the remote client should bootstrap from when there has been a
         * disconnection, this time maybe later than the message time as event are not send in
         * chronological order from the bit set.
         */
        @Override
        public long bootStrapTimeStamp() {
            return bootstrapTimestamp(identifier);
        }
    }

    @Override
    public void close() throws IOException {
        try {
            keyReplicationData.close();
        } finally {
            modIterState.close();
        }
    }
}


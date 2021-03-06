/*
 *     Copyright (C) 2015  higherfrequencytrading.com
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU Lesser General Public License as published by
 *     the Free Software Foundation, either version 3 of the License.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU Lesser General Public License for more details.
 *
 *     You should have received a copy of the GNU Lesser General Public License
 *     along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package net.openhft.chronicle.engine.server.internal;

/**
 * Created by Rob Austin
 */

import net.openhft.chronicle.core.pool.StringBuilderPool;
import net.openhft.chronicle.engine.api.map.KeyValueStore;
import net.openhft.chronicle.engine.api.tree.RequestContext;
import net.openhft.chronicle.engine.map.RemoteKeyValueStore;
import net.openhft.chronicle.map.ChronicleMap;
import net.openhft.chronicle.network.connection.CoreFields;
import net.openhft.chronicle.wire.*;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.StreamCorruptedException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.function.Function;

import static net.openhft.chronicle.engine.server.internal.MapWireHandler.EventId.*;
import static net.openhft.chronicle.engine.server.internal.MapWireHandler.Params.*;
import static net.openhft.chronicle.network.connection.CoreFields.reply;

/**
 * @author Rob Austin.
 */
public class MapWireHandler<K, V> extends AbstractHandler {

    private static final StringBuilderPool SBP = new StringBuilderPool();
    private static final Logger LOG = LoggerFactory.getLogger(MapWireHandler.class);
    final StringBuilder eventName = new StringBuilder();
    final StringBuilder cpsBuff = new StringBuilder();

    @NotNull
    private final Map<Long, String> cidToCsp = new HashMap<>();
    @NotNull
    private final Map<String, Long> cspToCid = new HashMap<>();
    private final AtomicLong cid = new AtomicLong();

    private BiConsumer<ValueOut, V> vToWire;
    @Nullable
    private Function<ValueIn, K> wireToK;
    @Nullable
    private Function<ValueIn, V> wireToV;
    private RequestContext requestContext;


    @Nullable
    private WireIn inWire = null;
    @Nullable
    private KeyValueStore<K, V, V> map;
    private boolean charSequenceValue;
    private long tid;
    private final BiConsumer<WireIn, Long> dataConsumer = new BiConsumer<WireIn, Long>() {

        @Override
        public void accept(WireIn wireIn, Long inputTid) {

            try {
                eventName.setLength(0);
                final ValueIn valueIn = inWire.readEventName(eventName);

                if (put.contentEquals(eventName)) {
                    valueIn.marshallable(wire -> {
                        final Params[] params = put.params();
                        final K key = wireToK.apply(wire.read(params[0]));
                        final V value = wireToV.apply(wire.read(params[1]));
                        nullCheck(key);
                        nullCheck(value);
                        map.put(key, value);
                    });
                    return;
                }

                if (remove.contentEquals(eventName)) {
                    final K key = wireToK.apply(valueIn);
                    nullCheck(key);
                    map.remove(key);
                    return;
                }

                outWire.writeDocument(true, wire -> outWire.writeEventName(CoreFields.tid).int64(tid));

                writeData(inWire.bytes(), out -> {

                    if (clear.contentEquals(eventName)) {
                        map.clear();
                        return;
                    }

                    if (putAll.contentEquals(eventName)) {
                        valueIn.sequence(v -> {
                            while (v.hasNextSequenceItem()) {
                                valueIn.marshallable(wire -> map.put(
                                        wireToK.apply(wire.read(put.params()[0])),
                                        wireToV.apply(wire.read(put.params()[1]))));
                            }
                        });
                        return;
                    }

                    if (EventId.putIfAbsent.contentEquals(eventName)) {
                        valueIn.marshallable(wire -> {
                            final Params[] params = putIfAbsent.params();
                            final K key = wireToK.apply(wire.read(params[0]));
                            final V newValue = wireToV.apply(wire.read(params[1]));
                            final V result = map.putIfAbsent(key, newValue);

                            nullCheck(key);
                            nullCheck(newValue);

                            vToWire.accept(outWire.writeEventName(reply), result);
                        });
                        return;
                    }

                    if (size.contentEquals(eventName)) {
                        outWire.writeEventName(reply).int64(map.longSize());
                        return;
                    }


                    if (keySet.contentEquals(eventName) ||
                            values.contentEquals(eventName) ||
                            entrySet.contentEquals(eventName)) {
                        createProxy(eventName.toString());
                        return;
                    }

                    if (containsKey.contentEquals(eventName)) {
                        final K key = wireToK.apply(valueIn);
                        nullCheck(key);
                        outWire.writeEventName(reply)
                                .bool(map.containsKey(key));
                        return;
                    }

                    if (containsValue.contentEquals(eventName)) {
                        final V value = wireToV.apply(valueIn);
                        nullCheck(value);
                        final boolean aBoolean = map.containsValue(value);
                        outWire.writeEventName(reply).bool(
                                aBoolean);
                        return;
                    }

                    if (get.contentEquals(eventName)) {
                        final K key = wireToK.apply(valueIn);
                        nullCheck(key);

                        if (charSequenceValue) {
                            StringBuilder sb = SBP.acquireStringBuilder();
                            vToWire.accept(outWire.writeEventName(reply), (V) ((ChronicleMap) map).getUsing(key, sb));

                        } else
                            vToWire.accept(outWire.writeEventName(reply), map.get(key));

                        return;
                    }

                    if (getAndPut.contentEquals(eventName)) {
                        valueIn.marshallable(wire -> {

                            final Params[] params = getAndPut.params();
                            final K key = wireToK.apply(wire.read(params[0]));
                            final V value = wireToV.apply(wire.read(params[1]));

                            nullCheck(key);
                            nullCheck(value);

                            vToWire.accept(outWire.writeEventName(reply),
                                    map.getAndPut(key, value));
                        });
                        return;
                    }

                    if (getAndRemove.contentEquals(eventName)) {
                        final K key = wireToK.apply(valueIn);
                        nullCheck(key);
                        vToWire.accept(outWire.writeEventName(reply), map.getAndRemove(key));
                        return;
                    }

                    if (replace.contentEquals(eventName)) {
                        valueIn.marshallable(wire -> {
                            final Params[] params = replace.params();
                            final K key = wireToK.apply(wire.read(params[0]));
                            final V value = wireToV.apply(wire.read(params[1]));
                            nullCheck(key);
                            nullCheck(value);
                            vToWire.accept(outWire.writeEventName(reply),
                                    map.replace(key, value));
                        });
                        return;
                    }

                    if (replaceForOld.contentEquals(eventName)) {
                        valueIn.marshallable(wire -> {
                            final Params[] params = replaceForOld.params();
                            final K key = wireToK.apply(wire.read(params[0]));
                            V oldValue = wireToV.apply(wire.read(params[1]));
                            if (charSequenceValue)
                                oldValue = (V) oldValue.toString();
                            final V newValue = wireToV.apply(wire.read(params[2]));
                            nullCheck(key);
                            nullCheck(oldValue);
                            nullCheck(newValue);
                            outWire.writeEventName(reply).bool(map.replaceIfEqual(key, oldValue, newValue));
                        });
                        return;
                    }

                    if (putIfAbsent.contentEquals(eventName)) {
                        valueIn.marshallable(wire -> {
                            final Params[] params = putIfAbsent.params();
                            final K key = wireToK.apply(wire.read(params[0]));
                            final V value = wireToV.apply(wire.read(params[1]));
                            nullCheck(key);
                            nullCheck(value);
                            vToWire.accept(outWire.writeEventName(reply),
                                    map.putIfAbsent(key, value));
                        });

                        return;
                    }

                    if (removeWithValue.contentEquals(eventName)) {
                        valueIn.marshallable(wire -> {
                            final Params[] params = removeWithValue.params();
                            final K key = wireToK.apply(wire.read(params[0]));
                            final V value = wireToV.apply(wire.read(params[1]));
                            nullCheck(key);
                            nullCheck(value);
                            outWire.writeEventName(reply).bool(map.removeIfEqual(key, value));
                        });
                    }

                    if (hashCode.contentEquals(eventName)) {
                        outWire.writeEventName(reply).int32(map.hashCode());
                        return;
                    }

                    throw new IllegalStateException("unsupported event=" + eventName);
                });
            } catch (Exception e) {
                LOG.error("", e);
            } finally {
               /* if (Jvm.isDebug() && YamlLogging.showServerWrites) {
                    final Bytes<?> outBytes = outWire.bytes();
                    long len = outBytes.position() - CollectionWireHandler.SIZE_OF_SIZE;
                    if (len == 0) {
                        LOG.info("--------------------------------------------\n" +
                                "server writes:\n\n<EMPTY>");

                    } else {
                        LOG.info("--------------------------------------------\n" +
                                "server writes:\n\n" +
                                Wires.fromSizePrefixedBlobs(outBytes, CollectionWireHandler.SIZE_OF_SIZE, len));
                    }
                }*/
            }
        }
    };



    /**
     * @param in             the data the has come in from network
     * @param out            the data that is going out to network
     * @param map            the map that is being processed
     * @param tid            the transaction id of the event
     * @param wireAdapter    adapts keys and values to and from wire
     * @param requestContext the uri of the event
     * @throws StreamCorruptedException
     */
    public void process(@NotNull final WireIn in,
                        @NotNull final WireOut out,
                        @NotNull KeyValueStore map,
                        long tid,
                        @NotNull final WireAdapter wireAdapter,
                        @NotNull final RequestContext requestContext) throws
            StreamCorruptedException {
        this.vToWire = wireAdapter.valueToWire();
        this.wireToK = wireAdapter.wireToKey();
        this.wireToV = wireAdapter.wireToValue();
        this.requestContext = requestContext;

        setOutWire(outWire);

        try {
            this.inWire = in;
            this.outWire = out;
            this.map = map;
            charSequenceValue = map instanceof ChronicleMap &&
                    CharSequence.class == ((ChronicleMap) map).valueClass();
            assert !(map instanceof RemoteKeyValueStore) : "the server should not be a " +
                    "remove " +
                    "map";
            this.tid = tid;
            dataConsumer.accept(in, tid);
        } catch (Exception e) {
            LOG.error("", e);
        }
    }

    /**
     * create a new cid if one does not already exist for this csp
     *
     * @param csp the csp we wish to check for a cid
     * @return the cid for this csp
     */
    private long createCid(@NotNull CharSequence csp) {
        final long newCid = cid.incrementAndGet();
        String cspStr = csp.toString();
        final Long aLong = cspToCid.putIfAbsent(cspStr, newCid);

        if (aLong != null)
            return aLong;

        cidToCsp.put(newCid, cspStr);
        return newCid;
    }

    private void createProxy(final String type) {
        outWire.writeEventName(reply).type("set-proxy")
                .marshallable(w -> {

                    cpsBuff.setLength(0);
                    cpsBuff.append("/").append(requestContext.name());
                    cpsBuff.append("?");
                    cpsBuff.append("view=").append(type);

                    final Class keyType = requestContext.keyType();
                    if (keyType != null)
                        cpsBuff.append("&keyType=").append(keyType.getName());
                    final Class valueType = requestContext.valueType();
                    if (valueType != null)
                        cpsBuff.append("&valueType=").append(valueType.getName());

                    w.writeEventName(CoreFields.csp).text(cpsBuff);
                    w.writeEventName(CoreFields.cid).int64(createCid(cpsBuff));
                });
    }


    public CharSequence getCspForCid(long cid) {
        return cidToCsp.get(cid);
    }

    public enum Params implements WireKey {
        key,
        value,
        oldValue,
        eventType,
        newValue,
        timestamp,
        identifier,
        entry
    }

    public enum EventId implements ParameterizeWireKey {
        size,
        containsKey(key),
        containsValue(value),
        get(key),
        getAndPut(key, value),
        put(key, value),
        getAndRemove(key),
        remove(key),
        clear,
        keySet,
        values,
        entrySet,
        replace(key, value),
        replaceForOld(key, oldValue, newValue),
        putIfAbsent(key, value),
        removeWithValue(key, value),
        toString,
        putAll,
        hashCode,
        createChannel,
        entrySetRestricted,
        mapForKey,
        putMapped,
        keyBuilder,
        valueBuilder,
        remoteIdentifier,
        numberOfSegments,
        bootstap;

        private final WireKey[] params;

        <P extends WireKey> EventId(P... params) {
            this.params = params;
        }

        @NotNull
        public <P extends WireKey> P[] params() {
            return (P[]) this.params;
        }
    }
}

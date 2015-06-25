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

package net.openhft.chronicle.engine.map;

import net.openhft.chronicle.core.Jvm;
import net.openhft.chronicle.engine.api.EngineReplication;
import net.openhft.chronicle.engine.api.EngineReplication.ModificationIterator;
import net.openhft.chronicle.engine.api.tree.RequestContext;
import net.openhft.chronicle.engine.api.tree.View;
import net.openhft.chronicle.engine.map.replication.Bootstrap;
import net.openhft.chronicle.network.connection.AbstractStatelessClient;
import net.openhft.chronicle.network.connection.TcpChannelHub;
import net.openhft.chronicle.threads.HandlerPriority;
import net.openhft.chronicle.threads.api.EventHandler;
import net.openhft.chronicle.threads.api.EventLoop;
import net.openhft.chronicle.wire.ValueIn;
import net.openhft.chronicle.wire.ValueOut;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Function;

import static net.openhft.chronicle.engine.collection.CollectionWireHandler.SetEventId.identifier;
import static net.openhft.chronicle.engine.server.internal.MapWireHandler.EventId.bootstap;
import static net.openhft.chronicle.engine.server.internal.ReplicationHandler.EventId.*;

/**
 * Created by Rob Austin
 */
public class ReplicationHub extends AbstractStatelessClient implements View {
    private static final Logger LOG = LoggerFactory.getLogger(ChronicleMapKeyValueStore.class);
    private final EventLoop eventLoop;
    private final AtomicBoolean isClosed;

    public ReplicationHub(RequestContext context, @NotNull final TcpChannelHub hub, EventLoop eventLoop, AtomicBoolean isClosed) {
        super(hub, (long) 0, toUri(context));

        this.eventLoop = eventLoop;
        this.isClosed = isClosed;
    }

    private static String toUri(final RequestContext context) {
        final StringBuilder uri = new StringBuilder("/" + context.name()
                + "?view=" + "Replication");

        if (context.keyType() != String.class)
            uri.append("&keyType=").append(context.keyType().getName());

        if (context.valueType() != String.class)
            uri.append("&valueType=").append(context.valueType().getName());

        return uri.toString();
    }

    public void bootstrap(EngineReplication replication, int localIdentifer) throws InterruptedException {

        final byte remoteIdentifier = proxyReturnByte(identifierReply, identifier);
        final ModificationIterator mi = replication.acquireModificationIterator(remoteIdentifier);
        final long lastModificationTime = replication.lastModificationTime(remoteIdentifier);

        final Bootstrap bootstrap = new Bootstrap();
        bootstrap.lastUpdatedTime(lastModificationTime);
        bootstrap.identifier((byte) localIdentifer);

        final AtomicLong tid = new AtomicLong();
        final Function<ValueIn, Bootstrap> typedMarshallable = ValueIn::typedMarshallable;
        final Consumer<ValueOut> valueOutConsumer = o -> o.typedMarshallable(bootstrap);
        final Bootstrap b = (Bootstrap) proxyReturnWireConsumerInOut(
                bootstap, bootstrapReply, valueOutConsumer, typedMarshallable, tid::set);

        try {

            mi.setModificationNotifier(() -> {
                eventLoop.unpause();
            });

            AtomicLong tid0 = new AtomicLong();

            // send replication events
            this.hub.outBytesLock().lock();
            try {
                tid0.set(writeMetaDataStartTime(System.currentTimeMillis()));

                mi.forEach(e -> this.hub.outWire().writeDocument(false, wireOut ->
                        wireOut.writeEventName(replicationEvent).typedMarshallable(e)));

                // receives replication events
                this.hub.asyncReadSocket(tid.get(), d -> {
                    d.readDocument(null, w -> replication.applyReplication(
                            w.read(replicactionReply).typedMarshallable()));
                });

                this.hub.writeSocket(this.hub.outWire());
            } finally {
                this.hub.outBytesLock().unlock();
            }

            mi.dirtyEntries(b.lastUpdatedTime());

            eventLoop.addHandler(new EventHandler() {
                @Override
                public boolean runOnce() {

                    TcpChannelHub hub = ReplicationHub.this.hub;
                    hub.outBytesLock().lock();
                    try {
                        ReplicationHub.this.writeMetaDataForKnownTID(tid0.get());

                        mi.forEach(e -> hub.outWire().writeDocument(false, wireOut ->
                                wireOut.writeEventName(replicationEvent).typedMarshallable(e)));
                        hub.writeSocket(hub.outWire());
                    } catch (InterruptedException e) {
                        throw Jvm.rethrow(e);
                    } finally {
                        hub.outBytesLock().unlock();
                    }

                    return !isClosed.get();

                }

                @Override
                public HandlerPriority priority() {
                    return HandlerPriority.MEDIUM;
                }
            });

        } catch (Throwable t) {
            LOG.error("", t);
        }

    }


}
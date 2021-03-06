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

import net.openhft.chronicle.engine.api.EngineReplication;
import net.openhft.chronicle.engine.api.EngineReplication.ModificationIterator;
import net.openhft.chronicle.engine.api.tree.RequestContext;
import net.openhft.chronicle.engine.api.tree.View;
import net.openhft.chronicle.engine.map.replication.Bootstrap;
import net.openhft.chronicle.network.connection.AbstractAsyncSubscription;
import net.openhft.chronicle.network.connection.AbstractStatelessClient;
import net.openhft.chronicle.network.connection.TcpChannelHub;
import net.openhft.chronicle.threads.HandlerPriority;
import net.openhft.chronicle.threads.api.EventHandler;
import net.openhft.chronicle.threads.api.EventLoop;
import net.openhft.chronicle.threads.api.InvalidEventHandlerException;
import net.openhft.chronicle.wire.ValueIn;
import net.openhft.chronicle.wire.ValueOut;
import net.openhft.chronicle.wire.WireIn;
import net.openhft.chronicle.wire.WireOut;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;

import static net.openhft.chronicle.engine.server.internal.MapWireHandler.EventId.bootstap;
import static net.openhft.chronicle.engine.server.internal.ReplicationHandler.EventId.*;

/**
 * Created by Rob Austin
 */
public class ReplicationHub extends AbstractStatelessClient implements View {
    private static final Logger LOG = LoggerFactory.getLogger(ChronicleMapKeyValueStore.class);
    private final EventLoop eventLoop;
    private final AtomicBoolean isClosed;

    public ReplicationHub(@NotNull RequestContext context, @NotNull final TcpChannelHub hub, EventLoop eventLoop, AtomicBoolean isClosed) {
        super(hub, (long) 0, toUri(context));

        this.eventLoop = eventLoop;
        this.isClosed = isClosed;
    }

    private static String toUri(@NotNull final RequestContext context) {
        final StringBuilder uri = new StringBuilder(context.fullName()
                + "?view=" + "Replication");

        if (context.keyType() != String.class)
            uri.append("&keyType=").append(context.keyType().getName());

        if (context.valueType() != String.class)
            uri.append("&valueType=").append(context.valueType().getName());

        return uri.toString();
    }

    public void bootstrap(@NotNull EngineReplication replication, int localIdentifer) throws InterruptedException {

        final byte remoteIdentifier = proxyReturnByte(identifierReply, identifier);
        final ModificationIterator mi = replication.acquireModificationIterator(remoteIdentifier);
        final long lastModificationTime = replication.lastModificationTime(remoteIdentifier);

        final Bootstrap bootstrap = new Bootstrap();
        bootstrap.lastUpdatedTime(lastModificationTime);
        bootstrap.identifier((byte) localIdentifer);

        final Function<ValueIn, Bootstrap> typedMarshallable = ValueIn::typedMarshallable;
        final Consumer<ValueOut> valueOutConsumer = o -> o.typedMarshallable(bootstrap);
        final Bootstrap b = (Bootstrap) proxyReturnWireConsumerInOut(
                bootstap, bootstrapReply, valueOutConsumer, typedMarshallable);

        try {

            // subscribes to updates - receives the replication events
            subscribe(replication, localIdentifer);

            // publishes changes - pushes the replication events
            publish(mi, b);

        } catch (Throwable t) {
            LOG.error("", t);
        }

    }

    /**
     * publishes changes - pushes the replication events
     *
     * @param mi     the modification iterator that notifies us of changes
     * @param remote details about the remote connection
     * @throws InterruptedException
     */
    private void publish(@NotNull final ModificationIterator mi, @NotNull final Bootstrap remote) throws InterruptedException {

        final TcpChannelHub hub = this.hub;
        mi.setModificationNotifier(eventLoop::unpause);

        eventLoop.addHandler(new EventHandler() {
            @Override
            public boolean action() throws InvalidEventHandlerException {
                
                if (!mi.hasNext())
                     return false;
                 
                if (isClosed.get())
                    throw new InvalidEventHandlerException();

                hub.lock(() -> mi.forEach(e -> sendEventAsyncWithoutLock(replicationEvent,
                        (Consumer<ValueOut>) v -> v.typedMarshallable(e))));

                return true;
            }

            @NotNull
            @Override
            public HandlerPriority priority() {
                return HandlerPriority.MEDIUM;
            }
        });

        mi.dirtyEntries(remote.lastUpdatedTime());
    }



    /**
     * subscribes to updates
     * @param replication    the event will be applied to the EngineReplication
     * @param localIdentifier our local identifier
     */
    private void subscribe(@NotNull final EngineReplication replication, final int localIdentifier) {

        hub.subscribe(new AbstractAsyncSubscription(hub,csp) {
            @Override
            public void onSubscribe(@NotNull final WireOut wireOut) {
                wireOut.writeEventName(replicationSubscribe).int8(localIdentifier);
            }

            @Override
            public void onConsumer(@NotNull final WireIn d) {
                d.readDocument(null, w -> replication.applyReplication(
                        w.read(replicactionReply).typedMarshallable()));
            }

        });

    }

}
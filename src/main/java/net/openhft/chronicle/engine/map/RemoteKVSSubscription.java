package net.openhft.chronicle.engine.map;

import net.openhft.chronicle.engine.api.map.KeyValueStore;
import net.openhft.chronicle.engine.api.map.MapEvent;
import net.openhft.chronicle.engine.api.map.MapEventListener;
import net.openhft.chronicle.engine.api.pubsub.InvalidSubscriberException;
import net.openhft.chronicle.engine.api.pubsub.Subscriber;
import net.openhft.chronicle.engine.api.pubsub.TopicSubscriber;
import net.openhft.chronicle.engine.api.tree.Asset;
import net.openhft.chronicle.engine.api.tree.RequestContext;
import net.openhft.chronicle.engine.server.internal.MapWireHandler;
import net.openhft.chronicle.network.connection.AbstractStatelessClient;
import net.openhft.chronicle.network.connection.TcpConnectionHub;
import net.openhft.chronicle.wire.CoreFields;
import net.openhft.chronicle.wire.ValueIn;
import net.openhft.chronicle.wire.Wire;
import net.openhft.chronicle.wire.WireIn;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;

import static net.openhft.chronicle.engine.server.internal.MapWireHandler.EventId.subscribe;

/**
 * Created by daniel on 10/06/15.
 */
public class RemoteKVSSubscription<K, MV, V> extends AbstractStatelessClient implements ObjectKVSSubscription<K, MV, V> {

    private final Class<V> valueType;
    private final Executor eventLoop = Executors.newSingleThreadExecutor();
    private final Class<K> keyType;
    private KeyValueStore<K, MV, V> kvStore;

    public RemoteKVSSubscription(RequestContext context, Asset asset) {
        super(TcpConnectionHub.hub(context, asset), (long) 0, toUri(context));
        valueType = context.valueType();
        keyType = context.keyType();
    }

    @Override
    public boolean needsPrevious() {
        throw new UnsupportedOperationException("todo");
    }

    @Override
    public void setKvStore(KeyValueStore<K, MV, V> store) {
        this.kvStore = store;
    }

    @Override
    public void notifyEvent(MapEvent<K, V> mpe) {
        throw new UnsupportedOperationException("todo");
    }

    @Override
    public void registerSubscriber(RequestContext rc, Subscriber<MapEvent<K, V>> subscriber) {

        final long startTime = System.currentTimeMillis();
        long tid1;

        if (hub.outBytesLock().isHeldByCurrentThread())
            throw new IllegalStateException("Cannot view map while debugging");

        hub.outBytesLock().lock();
        try {
            tid1 = writeMetaData(startTime);
            hub.outWire().writeDocument(false, wireOut -> {
                wireOut.writeEventName(subscribe);
            });

            hub.writeSocket(hub.outWire());
        } finally {
            hub.outBytesLock().unlock();
        }

        assert !hub.outBytesLock().isHeldByCurrentThread();
        final long timeoutTime = Long.MAX_VALUE;

        eventLoop.execute(() -> {
            // receive
            while(true) {
                hub.inBytesLock().lock();
                try {
                    final Wire wire = hub.proxyReply(timeoutTime, tid1);
                    checkIsData(wire);
                    readReplyConsumer(wire, CoreFields.reply, (Consumer<ValueIn>) valueIn -> valueIn.marshallable(r -> onEvent(r, subscriber)));
                } finally {
                    hub.inBytesLock().unlock();
                }
            }
        });
    }

    @Override
    public void registerKeySubscriber(RequestContext rc, Subscriber<K> kSubscriber) {
        throw new UnsupportedOperationException("todo");
    }

    @Override
    public void unregisterKeySubscriber(Subscriber<K> kSubscriber) {
        throw new UnsupportedOperationException("todo");
    }

    @Override
    public void registerTopicSubscriber(RequestContext rc, TopicSubscriber<K, V> subscriber) {
        throw new UnsupportedOperationException("todo");
    }

    @Override
    public void unregisterTopicSubscriber(TopicSubscriber subscriber) {
        throw new UnsupportedOperationException("todo");
    }

    @Override
    public void registerDownstream(EventConsumer<K, V> subscription) {
        throw new UnsupportedOperationException("todo");
    }

    @Override
    public void unregisterSubscriber(Subscriber<MapEvent<K, V>> subscriber) {
        throw new UnsupportedOperationException("todo");
    }

    @NotNull
    private static String toUri(@NotNull final RequestContext context) {
        return "/" + context.name()
                + "?view=" + "map&keyType=" + context.keyType().getName() + "&valueType=" + context.valueType()
                .getName();
    }

    private void onEvent(WireIn r, Subscriber<MapEvent<K, V>> subscriber) {
        byte eventType = r.read().int8();
        K key = r.read(MapWireHandler.Params.key).object(keyType);


        try {
            subscriber.onMessage(new MapEvent<K, V>() {
                @Override
                public String assetName() {
                    throw new UnsupportedOperationException("todo");
                }

                @Override
                public K key() {
                    return key;
                }

                @Override
                public V value() {
                    throw new UnsupportedOperationException("todo");
                }

                @Override
                public V oldValue() {
                    throw new UnsupportedOperationException("todo");
                }

                @Override
                public void apply(MapEventListener listener) {
                    if(eventType==1) {
                        V newValue = r.read(MapWireHandler.Params.newValue).object(valueType);
                        listener.insert(key, newValue);
                    }else if(eventType==2){
                        V oldValue = r.read(MapWireHandler.Params.oldValue).object(valueType);
                        V newValue = r.read(MapWireHandler.Params.newValue).object(valueType);
                        listener.update(key, oldValue, newValue);
                    }else if(eventType==3){
                        V oldValue = r.read(MapWireHandler.Params.oldValue).object(valueType);
                        listener.remove(key, oldValue);
                    }else{
                        throw new AssertionError("Event type " + eventType + " not supported");
                    }
                }

                @Override
                public MapEvent translate(BiFunction keyFunction, BiFunction valueFunction) {
                    throw new UnsupportedOperationException("todo");
                }

                @Override
                public MapEvent<K, V> translate(Function keyFunction, Function valueFunction) {
                    throw new UnsupportedOperationException("todo");
                }
            });
        } catch (InvalidSubscriberException e) {
            e.printStackTrace();
        }
    }
}

package net.openhft.chronicle.engine.server.internal;

import net.openhft.chronicle.engine.api.pubsub.InvalidSubscriberException;
import net.openhft.chronicle.engine.api.pubsub.TopicPublisher;
import net.openhft.chronicle.engine.api.pubsub.TopicSubscriber;
import net.openhft.chronicle.wire.*;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Queue;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;

import static net.openhft.chronicle.engine.server.internal.MapWireHandler.nullCheck;
import static net.openhft.chronicle.engine.server.internal.TopicPublisherHandler.EventId.publish;
import static net.openhft.chronicle.engine.server.internal.TopicPublisherHandler.EventId.registerTopicSubscriber;
import static net.openhft.chronicle.engine.server.internal.TopicPublisherHandler.Params.message;
import static net.openhft.chronicle.engine.server.internal.TopicPublisherHandler.Params.topic;
import static net.openhft.chronicle.network.connection.CoreFields.reply;
import static net.openhft.chronicle.network.connection.CoreFields.tid;

/**
 * Created by Rob Austin
 */
public class TopicPublisherHandler<T, M> extends AbstractHandler {

    private final StringBuilder eventName = new StringBuilder();

    private Queue<Consumer<Wire>> publisher;
    private TopicPublisher<T, M> view;
    @Nullable
    private Function<ValueIn, T> wireToT;
    @Nullable
    private Function<ValueIn, M> wireToM;
    private final BiConsumer<WireIn, Long> dataConsumer = new BiConsumer<WireIn, Long>() {

        @Override
        public void accept(@NotNull final WireIn inWire, Long inputTid) {

            eventName.setLength(0);
            final ValueIn valueIn = inWire.readEventName(eventName);

            if (registerTopicSubscriber.contentEquals(eventName)) {

                final TopicSubscriber listener = new TopicSubscriber() {

                    @Override
                    public void onMessage(final Object topic, final Object message) throws InvalidSubscriberException {

                        publisher.add(publish -> {
                            publish.writeDocument(true, wire -> wire.writeEventName(tid).int64
                                    (inputTid));
                            publish.writeNotReadyDocument(false, wire -> wire.writeEventName(reply)
                                    .marshallable(m -> {
                                        m.write(() -> "topic").object(topic);
                                        m.write(() -> "message").object(message);
                                    }));
                        });
                    }

                    public void onEndOfSubscription() {
                        publisher.add(publish -> {
                            publish.writeDocument(true, wire -> wire.writeEventName(tid).int64
                                    (inputTid));
                            publish.writeNotReadyDocument(false, wire -> wire.writeEventName
                                    (EventId.onEndOfSubscription).text(""));

                        });
                    }

                };

                valueIn.marshallable(m -> view.registerTopicSubscriber(listener));
                return;
            }

            if (publish.contentEquals(eventName)) {

                valueIn.marshallable(wire -> {
                    final Params[] params = publish.params();
                    final T topic = wireToT.apply(wire.read(params[0]));
                    final M message = wireToM.apply(wire.read(params[1]));
                    nullCheck(topic);
                    nullCheck(message);
                    view.publish(topic, message);
                });

            }
        }
    };

    void process(@NotNull final WireIn inWire,
                 final Queue<Consumer<Wire>> publisher,
                 final long tid,
                 final Wire outWire,
                 final TopicPublisher view,
                 final @NotNull WireAdapter wireAdapter) {

        setOutWire(outWire);
        final Wire outWire1 = outWire;
        this.view = view;
        this.publisher = publisher;
        this.wireToT = wireAdapter.wireToKey();
        this.wireToM = wireAdapter.wireToValue();
        dataConsumer.accept(inWire, tid);
    }

    public enum Params implements WireKey {
        topic,
        message,
    }

    public enum EventId implements ParameterizeWireKey {
        publish(topic, message),
        onEndOfSubscription,
        registerTopicSubscriber(topic, message);

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

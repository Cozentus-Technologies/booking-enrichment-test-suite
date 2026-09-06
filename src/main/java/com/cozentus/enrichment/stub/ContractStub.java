package com.cozentus.enrichment.stub;

import com.cozentus.enrichment.stub.Fixture.Route;
import com.cozentus.enrichment.stub.StubRouter.Emission;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.errors.WakeupException;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;

/**
 * C-1. A stand-in for the enrichment service that replays a fixture.
 *
 * <p>A suite that has never been seen to fail proves nothing, and the only way
 * to see this one fail was to break the service - which is out of bounds here
 * and impossible against a service somebody else operates. The stub takes the
 * service's place: it speaks the same three topics, honours the same command
 * line, answers the same readiness endpoint, and decides nothing for itself.
 * Breaking one rule is then a one-line edit to a JSON file in this repository.
 *
 * <p>It is selected purely by {@code -Dservice.jar.path=target/contract-stub.jar};
 * the suite is not aware it exists. Which fixture to replay comes from the
 * {@code STUB_FIXTURE} environment variable rather than a new flag, because
 * {@code ServiceController} owns the command line and must not change to
 * accommodate a test double - the moment the suite knows about the stub, the
 * stub stops being a fair substitute for the service.
 */
public final class ContractStub {

    private static final Duration POLL = Duration.ofMillis(50);

    /**
     * How long a single output is held back when the reordering mutation is
     * active, waiting to see whether a second output shares its key. Well
     * inside the suite's await timeout, and long enough to span the gap between
     * two messages the suite publishes back to back.
     */
    private static final Duration REORDER_HOLD = Duration.ofMillis(750);

    private ContractStub() {
    }

    public static void main(String[] args) throws Exception {
        Map<String, String> flags = parseFlags(args);
        Fixture fixture = Fixture.load(System.getenv().getOrDefault("STUB_FIXTURE", "baseline"));
        StubRouter router = new StubRouter(fixture,
                Fixture.Cities.of(flags.get("cities.source")));

        String rawTopic = required(flags, "topic.raw");
        String enrichedTopic = required(flags, "topic.enriched");
        String flaggedTopic = required(flags, "topic.flagged");

        System.out.printf("contract stub: fixture=%s cities=%s raw=%s%n",
                fixture.name(), Fixture.Cities.of(flags.get("cities.source")), rawTopic);

        int readinessPort = Integer.parseInt(required(flags, "readiness.port"));
        AtomicReference<HttpServer> readiness = new AtomicReference<>();

        try (KafkaConsumer<String, String> consumer = consumer(flags);
             KafkaProducer<String, String> producer = producer(flags)) {

            consumer.subscribe(List.of(rawTopic));
            shutDownCleanly(readiness, consumer, Thread.currentThread());

            // Ready is claimed from inside the poll loop, the first time this
            // consumer actually owns partitions. Claiming it at subscribe time
            // would let the suite publish into a topic nobody holds, and the lost
            // message would surface as a routing failure rather than as the race
            // it is. Waiting for assignment in a separate loop beforehand is
            // worse still: those polls fetch records and drop them, and the next
            // commit then acknowledges work that was never done.
            new Loop(router, producer, enrichedTopic, flaggedTopic)
                    .run(consumer, () -> readiness.set(readinessEndpoint(readinessPort)));
        }
    }

    // --- the consume/produce loop ---------------------------------------

    /** Holds the little cross-record state two of the mutations need. */
    private static final class Loop {

        private final StubRouter router;
        private final KafkaProducer<String, String> producer;
        private final String enrichedTopic;
        private final String flaggedTopic;

        private final AtomicBoolean enrichedAlreadySwapped = new AtomicBoolean();
        private final AtomicBoolean flaggedAlreadySwapped = new AtomicBoolean();

        /** Key to the output being held back, for the reordering mutation. */
        private final Map<String, Held> held = new LinkedHashMap<>();

        private record Held(Emission emission, long dueAt) {
        }

        Loop(StubRouter router, KafkaProducer<String, String> producer,
             String enrichedTopic, String flaggedTopic) {
            this.router = router;
            this.producer = producer;
            this.enrichedTopic = enrichedTopic;
            this.flaggedTopic = flaggedTopic;
        }

        void run(KafkaConsumer<String, String> consumer, Runnable announceReady) {
            boolean announced = false;
            try {
                while (true) {
                    var records = consumer.poll(POLL);
                    if (!announced && !consumer.assignment().isEmpty()) {
                        announceReady.run();
                        announced = true;
                    }
                    records.forEach(this::handle);
                    releaseExpired();
                    // Committed after producing, never before: a restart with
                    // work still in flight must redeliver it, which is what
                    // TC-56 exists to check.
                    consumer.commitSync();
                }
            } catch (WakeupException stopping) {
                // close() was called.
            }
        }

        private void handle(ConsumerRecord<String, String> record) {
            List<Emission> emissions = router.route(record.value(), record.key(), headersOf(record));
            for (Emission emission : emissions) {
                dispatch(applySwap(emission));
            }
        }

        /**
         * The routing mutation that leaves every total intact: the first booking
         * bound for each topic is sent to the other one. Counts still match, so
         * only an assertion comparing identities rather than sizes can see it.
         */
        private Emission applySwap(Emission emission) {
            if (!router.fixture().behaviour().swapOneRoutePair()) {
                return emission;
            }
            return switch (emission.route()) {
                case ENRICHED -> enrichedAlreadySwapped.compareAndSet(false, true)
                        ? emission.on(Route.FLAGGED) : emission;
                case FLAGGED -> flaggedAlreadySwapped.compareAndSet(false, true)
                        ? emission.on(Route.ENRICHED) : emission;
            };
        }

        /**
         * The reordering mutation. A single output is held briefly; if a second
         * output arrives on the same key while it waits, the newer one is
         * published first. Ordering only means anything within a partition, and
         * a shared key is the only way two outputs land on one, so this is the
         * smallest change that reverses an order the contract fixes.
         */
        private void dispatch(Emission emission) {
            if (!router.fixture().behaviour().reverseSharedKeyOutputs() || emission.key() == null) {
                publish(emission);
                return;
            }
            Held waiting = held.remove(emission.key());
            if (waiting == null) {
                held.put(emission.key(),
                        new Held(emission, System.currentTimeMillis() + REORDER_HOLD.toMillis()));
                return;
            }
            publish(emission);
            publish(waiting.emission());
        }

        /** Nothing shared its key after all, so the held output goes out unchanged. */
        private void releaseExpired() {
            long now = System.currentTimeMillis();
            held.values().removeIf(waiting -> {
                if (waiting.dueAt() > now) {
                    return false;
                }
                publish(waiting.emission());
                return true;
            });
        }

        private void publish(Emission emission) {
            String topic = emission.route() == Route.ENRICHED ? enrichedTopic : flaggedTopic;
            ProducerRecord<String, String> record =
                    new ProducerRecord<>(topic, null, emission.key(), emission.payload());
            emission.headers().forEach((name, value) -> record.headers()
                    .add(new RecordHeader(name, value == null
                            ? null : value.getBytes(StandardCharsets.UTF_8))));
            producer.send(record);
            producer.flush();
        }

        private static Map<String, String> headersOf(ConsumerRecord<String, String> record) {
            Map<String, String> headers = new HashMap<>();
            record.headers().forEach(header -> headers.put(header.key(),
                    header.value() == null ? null : new String(header.value(), StandardCharsets.UTF_8)));
            return headers;
        }
    }

    // --- wiring ---------------------------------------------------------

    /**
     * Leaves the consumer group on SIGTERM instead of being timed out of it.
     *
     * <p>Without this the group coordinator waits out {@code session.timeout.ms}
     * - 45 seconds by default - before giving the partitions to anybody else, so
     * a replacement instance on the same group cannot reach readiness inside the
     * suite's timeout. TC-56 restarts the service on the same group deliberately,
     * and would fail as a startup timeout rather than as anything about
     * redelivery. The hook wakes the poll loop and waits for it to close the
     * consumer, because closing one from another thread is not safe.
     */
    private static void shutDownCleanly(AtomicReference<HttpServer> readiness,
                                        KafkaConsumer<String, String> consumer,
                                        Thread loop) {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            HttpServer server = readiness.get();
            if (server != null) {
                server.stop(0);
            }
            consumer.wakeup();
            try {
                loop.join(Duration.ofSeconds(5));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }));
    }

    private static HttpServer readinessEndpoint(int port) {
        HttpServer server;
        try {
            server = HttpServer.create(new InetSocketAddress(port), 0);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not bind the readiness port " + port, e);
        }
        server.createContext("/ready", exchange -> {
            byte[] body = "ready".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (var out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        return server;
    }

    private static KafkaConsumer<String, String> consumer(Map<String, String> flags) {
        Properties properties = new Properties();
        properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, required(flags, "kafka.bootstrap.servers"));
        properties.put(ConsumerConfig.GROUP_ID_CONFIG, required(flags, "kafka.consumer.group"));
        properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        properties.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        return new KafkaConsumer<>(properties);
    }

    private static KafkaProducer<String, String> producer(Map<String, String> flags) {
        Properties properties = new Properties();
        properties.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, required(flags, "kafka.bootstrap.servers"));
        properties.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        properties.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        properties.put(ProducerConfig.ACKS_CONFIG, "all");
        return new KafkaProducer<>(properties);
    }

    /** {@code --name=value}, which is exactly what {@code ServiceController} passes. */
    private static Map<String, String> parseFlags(String[] args) {
        Map<String, String> flags = new LinkedHashMap<>();
        for (String arg : args) {
            if (!arg.startsWith("--")) {
                continue;
            }
            int split = arg.indexOf('=');
            if (split > 2) {
                flags.put(arg.substring(2, split), arg.substring(split + 1));
            }
        }
        return flags;
    }

    private static String required(Map<String, String> flags, String name) {
        String value = flags.get(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing --" + name + "; supplied flags: " + flags.keySet());
        }
        return value;
    }
}

/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package se.sics.kompics.network.test;

import java.io.IOException;
import java.io.Serializable;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import se.sics.kompics.Channel;
import se.sics.kompics.Component;
import se.sics.kompics.ComponentDefinition;
import se.sics.kompics.ControlPort;
import se.sics.kompics.Event;
import se.sics.kompics.Fault;
import se.sics.kompics.Fault.ResolveAction;
import se.sics.kompics.Handler;
import se.sics.kompics.Init;
import se.sics.kompics.Init.None;
import se.sics.kompics.Kompics;
import se.sics.kompics.KompicsEvent;
import se.sics.kompics.Negative;
import se.sics.kompics.Port;
import se.sics.kompics.PortType;
import se.sics.kompics.Positive;
import se.sics.kompics.Start;
import se.sics.kompics.network.Address;
import se.sics.kompics.network.MessageNotify;
import se.sics.kompics.network.Network;
import se.sics.kompics.network.Transport;

/**
 *
 * @author Lars Kroll <lkroll@sics.se>
 */
public class NetworkTest {

    private static final Logger LOG = LoggerFactory.getLogger(NetworkTest.class);
    private static final int SEED = 0;
    //private static final String STARTED = "STARTED";
    private static final String STOPPED = "STOPPED";
    private static final String SENDING = "SENDING";
    private static final String RECEIVED = "RECEIVED";
    private static final String ACKED = "ACKED";
    private static final String SENT = "SENT";
    private static final String FAIL = "FAIL";
    private static final int NUM_MESSAGES = 100;
    private static final int NUM_FR_MESSAGES = 10;
    private static final boolean sendFakes = true;
    private static final int BATCH_SIZE = 10;
    private static final AtomicInteger WAIT_FOR = new AtomicInteger(NUM_MESSAGES);
    private static NetworkGenerator nGen;
    private static int numNodes;
    private static final AtomicInteger msgId = new AtomicInteger(0);
    private static final ConcurrentSkipListMap<Integer, String> messageStatus = new ConcurrentSkipListMap<Integer, String>();
    //private static int startPort = 33000;
    private static Transport[] protos;

    public static synchronized void runTests(NetworkGenerator nGen, int numNodes, Transport[] protos) {
        LOG.info("\n******************** Running All Test ********************\n");
        NetworkTest.nGen = nGen;
        NetworkTest.numNodes = numNodes;
        NetworkTest.protos = protos;
        WAIT_FOR.set(NUM_MESSAGES);

        msgId.set(0);
        messageStatus.clear();
        TestUtil.reset("Stream test (" + Arrays.toString(NetworkTest.protos) + ") Nodes #" + numNodes, 100000); //10 sec timeout for all the connections to be dropped properly

        Kompics.createAndStart(LauncherComponent.class, 8, 50);

        for (int i = 0; i < numNodes; i++) {
            LOG.info("Waiting for {}/{} STOPPED.", i + 1, numNodes);
            try {
                TestUtil.waitFor(STOPPED);
            } finally {
                StringBuilder sb = new StringBuilder();
                sb.append("MessageStatus {\n");
                for (Entry<Integer, String> e : messageStatus.entrySet()) {
                    sb.append(e.getKey());
                    sb.append(" -> ");
                    sb.append(e.getValue());
                    sb.append("\n");
                }
                sb.append("}");
                LOG.debug(sb.toString());
            }
            LOG.info("Got {}/{} STOPPED.", i + 1, numNodes);
        }

        LOG.info("\n******************** Shutting Down Kompics ********************\n");
        Kompics.shutdown();

        assertEquals(NUM_MESSAGES * numNodes, messageStatus.size());
        for (String s : messageStatus.values()) {
            assertEquals(ACKED, s);
        }
        LOG.info("\n******************** All Test Done ********************\n");
        for (Transport proto : protos) {
            LOG.info("\n******************** Running Fail-Recovery Test for {} ********************\n", proto);
            messageStatus.clear();
            NetworkTest.protos = new Transport[]{proto};

            TestUtil.reset("FR test (" + Arrays.toString(NetworkTest.protos) + ")", 100000); //10 sec timeout for all the connections to be dropped properly

            Kompics.createAndStart(FRLauncher.class, 8, 50);

            TestUtil.waitFor(STOPPED);

            LOG.info("\n******************** Shutting Down Kompics ********************\n");
            Kompics.shutdown();
            assertEquals(NUM_FR_MESSAGES, messageStatus.size());
            for (String s : messageStatus.values()) {
                assertEquals(ACKED, s);
            }
            LOG.info("\n******************** Fail-Recovery Test for {} done ********************\n", proto);
        }
        if (protos.length < 2) { // technically try all combinations, but I'm too lazy to generalise now
            return; // just stop here
        }
        Transport[][] protoMatrix = new Transport[][]{{protos[0], protos[1]}, {protos[1], protos[0]}};
        for (Transport[] protoRow : protoMatrix) {
            LOG.info("\n******************** Running Fail-Recovery Test for {} -> {} ********************\n", protoRow[0], protoRow[1]);
            messageStatus.clear();
            NetworkTest.protos = protoRow;
            TestUtil.reset("FR test (" + Arrays.toString(NetworkTest.protos) + ")", 100000); //10 sec timeout for all the connections to be dropped properly

            Kompics.createAndStart(FRLauncher.class, 8, 50);

            TestUtil.waitFor(STOPPED);

            LOG.info("\n******************** Shutting Down Kompics ********************\n");
            Kompics.shutdown();
            assertEquals(NUM_FR_MESSAGES, messageStatus.size());
            for (String s : messageStatus.values()) {
                assertEquals(ACKED, s);
            }
            LOG.info("\n******************** Fail-Recovery Test for {} -> {} done ********************\n", protoRow[0], protoRow[1]);
        }
    }

    public static synchronized void runAtLeastTests(NetworkGenerator nGen, int numNodes, Transport[] protos) {
        LOG.info("\n******************** Running AT LEAST Test ********************\n");
        NetworkTest.nGen = nGen;
        NetworkTest.numNodes = numNodes;
        NetworkTest.protos = protos;
        WAIT_FOR.set(1);

        msgId.set(0);
        messageStatus.clear();
        TestUtil.reset("Datagram test (" + Arrays.toString(NetworkTest.protos) + ") Nodes #" + numNodes, 10000); //10 sec timeout for all the connections to be dropped properly

        Kompics.createAndStart(LauncherComponent.class, 8, 50);

        for (int i = 0; i < numNodes; i++) {
            LOG.info("Waiting for {}/{} STOPPED.", i + 1, numNodes);
            TestUtil.waitFor(STOPPED);
            LOG.info("Got {}/{} STOPPED.", i + 1, numNodes);
        }

        LOG.info("\n******************** Shutting Down Kompics ********************\n");
        Kompics.shutdown();

        assertTrue(numNodes <= messageStatus.size());
        LOG.info("\n******************** AT LEAST Test Done ********************\n");
    }

    public static class LauncherComponent extends ComponentDefinition {

        private Random rand = new Random(SEED);

        public LauncherComponent() {
            TestAddress[] nodes = new TestAddress[numNodes];
            TestAddress[] fakeNodes = new TestAddress[numNodes]; // these are used to test that the network doesn't crash on connection errors
            for (int i = 0; i < numNodes; i++) {
                try {
                    byte[] ipB = new byte[4];
                    rand.nextBytes(ipB);
                    InetAddress ip = InetAddress.getByAddress(ipB);
                    int port = rand.nextInt(65535 - 49152) + 49152;
                    fakeNodes[i] = new TestAddress(ip, port);
                } catch (UnknownHostException ex) {
                    LOG.error("Aborting test.", ex);
                    System.exit(1);
                }
            }
            InetAddress ip = null;
            try {
                ip = InetAddress.getByName("127.0.0.1");
            } catch (UnknownHostException ex) {
                LOG.error("Aborting test.", ex);
                System.exit(1);
            }
            List<ServerSocket> sockets = new LinkedList<ServerSocket>();
            for (int i = 0; i < numNodes; i++) {
                int port = -1;
                try {
                    ServerSocket s = new ServerSocket(0); // try to find a free port for each address
                    sockets.add(s);
                    port = s.getLocalPort();
                } catch (IOException ex) {
                    LOG.error("Could not find any free ports: {}", ex);
                    System.exit(1);
                }
                if (port < 0) {
                    LOG.error("Could not find enough free ports!");
                    System.exit(1);
                }
                nodes[i] = new TestAddress(ip, port);
                Component net = nGen.generate(myProxy, nodes[i]);
                Component scen = create(ScenarioComponent.class, new ScenarioInit(nodes[i], nodes, fakeNodes));
                connect(net.provided(Network.class), scen.required(Network.class));
            }
            // check that all ports are unique
            Set<Integer> portSet = new TreeSet<Integer>();
            for (Address addr : nodes) {
                portSet.add(addr.getPort());
            }
            if (portSet.size() != nodes.length) {
                LOG.error("Some ports do not appear to be unique! \n {} \n");
                System.exit(1);
            }
            for (ServerSocket s : sockets) {
                try {
                    s.close();
                } catch (IOException ex) {
                    LOG.error("Could not close port: {}", ex);
                    System.exit(1);
                }
            }
        }
        private final ComponentProxy myProxy = new ComponentProxy() {
            @Override
            public <P extends PortType> void trigger(Event e, Port<P> p) {
                LauncherComponent.this.trigger(e, p);
            }

            @Override
            public <T extends ComponentDefinition> Component create(Class<T> definition, Init<T> initEvent) {
                return LauncherComponent.this.create(definition, initEvent);
            }

            @Override
            public <T extends ComponentDefinition> Component create(Class<T> definition, None initEvent) {
                return LauncherComponent.this.create(definition, initEvent);
            }

            @Override
            public void destroy(Component component) {
                LauncherComponent.this.destroy(component);
            }

            @Override
            public <P extends PortType> Channel<P> connect(Positive<P> positive, Negative<P> negative) {
                return LauncherComponent.this.connect(positive, negative);
            }

            @Override
            public <P extends PortType> Channel<P> connect(Negative<P> negative, Positive<P> positive) {
                return LauncherComponent.this.connect(negative, positive);
            }

            @Override
            public <P extends PortType> void disconnect(Negative<P> negative, Positive<P> positive) {
                LauncherComponent.this.disconnect(negative, positive);
            }

            @Override
            public <P extends PortType> void disconnect(Positive<P> positive, Negative<P> negative) {
                LauncherComponent.this.disconnect(positive, negative);
            }

            @Override
            public Negative<ControlPort> getControlPort() {
                return LauncherComponent.this.control;
            }
        };
    }

    public static class ScenarioComponent extends ComponentDefinition {

        public final TestAddress self;
        public final TestAddress[] nodes;
        public final TestAddress[] fakeNodes;
        private final Positive<Network> net = requires(Network.class);
        private int msgCount = 0;
        private int ackCount = 0;
        private Random rand = new Random(SEED);
        private Map<UUID, Integer> pending = new TreeMap<UUID, Integer>();

        public ScenarioComponent(ScenarioInit init) {
            self = init.self;
            nodes = init.nodes;
            fakeNodes = init.fakeNodes;

            Handler<Start> startHandler = new Handler<Start>() {
                @Override
                public void handle(Start event) {
                    for (int i = 0; i < BATCH_SIZE; i++) {
                        sendMessage();
                    }
                }
            };
            subscribe(startHandler, control);

            Handler<Ack> ackHandler = new Handler<Ack>() {
                @Override
                public void handle(Ack event) {
                    LOG.debug("Got Ack {}", event);
                    messageStatus.put(event.msgId, ACKED);
                    ackCount++;

                    if (ackCount >= WAIT_FOR.get()) {
                        LOG.info("Scenario Component {} is done.", self);
                        TestUtil.submit(STOPPED);
                        return;
                    }

                    if (msgCount < NUM_MESSAGES) {
                        for (int i = 0; i < BATCH_SIZE; i++) {
                            sendMessage();
                        }
                    }
                }
            };
            subscribe(ackHandler, net);

            Handler<TestMessage> msgHandler = new Handler<TestMessage>() {
                @Override
                public void handle(TestMessage event) {
                    LOG.debug("Got message {}", event);
                    messageStatus.put(event.msgId, RECEIVED);
                    trigger(event.ack(), net);
                }
            };
            subscribe(msgHandler, net);

            Handler<MessageNotify.Resp> notifyHandler = new Handler<MessageNotify.Resp>() {

                @Override
                public void handle(MessageNotify.Resp event) {
                    Integer msgId = pending.remove(event.msgId);
                    assertNotNull(msgId);
                    messageStatus.replace(msgId, SENDING, SENT);
                    LOG.debug("Message {} was sent.", msgId);
                }
            };
            subscribe(notifyHandler, net);
        }

        private void sendMessage() {
            int id = msgId.getAndIncrement();
            if (messageStatus.putIfAbsent(id, SENDING) != null) {
                LOG.error("Key {} was already present in messageStatus!", id);
                TestUtil.submit(FAIL);
            }
            Transport proto = NetworkTest.protos[rand.nextInt(NetworkTest.protos.length)];
            TestAddress dest = nodes[rand.nextInt(nodes.length)];
            //while (dest.sameHostAs(self)) {
            //    dest = nodes[rand.nextInt(nodes.length)];
            //}
            TestMessage msg = new TestMessage(self, dest, id, proto);
            MessageNotify.Req req = MessageNotify.create(msg);
            pending.put(req.getMsgId(), id);
            trigger(req, net);
            if (sendFakes) {
                TestMessage fakemsg = new TestMessage(self, fakeNodes[rand.nextInt(nodes.length)], id, proto); // send this as well
                trigger(fakemsg, net); // see fakeNodes in LauncherComponent
            }
            msgCount++;
        }
    }

    public static class ScenarioInit extends Init<ScenarioComponent> {

        public final TestAddress self;
        public final TestAddress[] nodes;
        public final TestAddress[] fakeNodes;

        public ScenarioInit(TestAddress self, TestAddress[] nodes, TestAddress[] fakeNodes) {
            this.self = self;
            this.nodes = nodes;
            this.fakeNodes = fakeNodes;
        }
    }

    public static class FRLauncher extends ComponentDefinition {

        private final FRInit init;

        public FRLauncher() {

            InetAddress ip = null;
            try {
                ip = InetAddress.getByName("127.0.0.1");
            } catch (UnknownHostException ex) {
                LOG.error("Aborting test.", ex);
                System.exit(1);
            }
            List<ServerSocket> sockets = new LinkedList<ServerSocket>();
            TestAddress[] nodes = new TestAddress[2];
            for (int i = 0; i < nodes.length; i++) {
                int port = -1;
                try {
                    ServerSocket s = new ServerSocket(0); // try to find a free port for each address
                    sockets.add(s);
                    port = s.getLocalPort();
                } catch (IOException ex) {
                    LOG.error("Could not find any free ports: {}", ex);
                    System.exit(1);
                }
                if (port < 0) {
                    LOG.error("Could not find enough free ports!");
                    System.exit(1);
                }
                nodes[i] = new TestAddress(ip, port);
            }
            // check that all ports are unique
            Set<Integer> portSet = new TreeSet<Integer>();
            for (Address addr : nodes) {
                portSet.add(addr.getPort());
            }
            if (portSet.size() != nodes.length) {
                LOG.error("Some ports do not appear to be unique! \n {} \n");
                System.exit(1);
            }
            for (ServerSocket s : sockets) {
                try {
                    s.close();
                } catch (IOException ex) {
                    LOG.error("Could not close port: {}", ex);
                    System.exit(1);
                }
            }

            init = new FRInit(nodes[0], nodes[1]);
            Component acker = create(Acker.class, init);

            Component net = nGen.generate(myProxy, nodes[1]);
            connect(net.provided(Network.class), acker.required(Network.class));

            createFRComponent();

            subscribe(recoverHandler, loopback);
        }

        private Component createFRComponent() {
            Component fr = create(FRComponent.class, init);
            return fr;
        }

        Handler<Recover> recoverHandler = new Handler<Recover>() {

            @Override
            public void handle(Recover event) {
                long diff = System.currentTimeMillis() - event.timestamp;
                if (diff < 100) {
                    try {
                        LOG.debug("Waiting for connections to shutdown...");
                        Thread.sleep(10);
                        trigger(event, onSelf);
                        return;
                    } catch (InterruptedException ex) {
                        LOG.error("Error while waiting to recover.", ex);
                        System.exit(1);
                    }
                }
                LOG.info("Recovering...");
                Component fr = createFRComponent();
                trigger(Start.event, fr.control());
            }

        };

        @Override
        public ResolveAction handleFault(Fault fault) {
            trigger(new Recover(System.currentTimeMillis()), onSelf);

            return ResolveAction.DESTROY;
        }

        private final ComponentProxy myProxy = new ComponentProxy() {
            @Override
            public <P extends PortType> void trigger(Event e, Port<P> p) {
                FRLauncher.this.trigger(e, p);
            }

            @Override
            public <T extends ComponentDefinition> Component create(Class<T> definition, Init<T> initEvent) {
                return FRLauncher.this.create(definition, initEvent);
            }

            @Override
            public <T extends ComponentDefinition> Component create(Class<T> definition, None initEvent) {
                return FRLauncher.this.create(definition, initEvent);
            }

            @Override
            public void destroy(Component component) {
                FRLauncher.this.destroy(component);
            }

            @Override
            public <P extends PortType> Channel<P> connect(Positive<P> positive, Negative<P> negative) {
                return FRLauncher.this.connect(positive, negative);
            }

            @Override
            public <P extends PortType> Channel<P> connect(Negative<P> negative, Positive<P> positive) {
                return FRLauncher.this.connect(negative, positive);
            }

            @Override
            public <P extends PortType> void disconnect(Negative<P> negative, Positive<P> positive) {
                FRLauncher.this.disconnect(negative, positive);
            }

            @Override
            public <P extends PortType> void disconnect(Positive<P> positive, Negative<P> negative) {
                FRLauncher.this.disconnect(positive, negative);
            }

            @Override
            public Negative<ControlPort> getControlPort() {
                return FRLauncher.this.control;
            }
        };
    }

    public static class FRComponent extends ComponentDefinition {

        private final Positive<Network> net = requires(Network.class);

        private final TestAddress self;
        private final TestAddress acker;

        public FRComponent(FRInit init) {
            self = init.frAddr;
            acker = init.ackerAddr;

            Component netw = nGen.generate(myProxy, self);
            connect(netw.provided(Network.class), net.getPair());

            subscribe(startHandler, control);
            subscribe(ackHandler, net);
        }

        Handler<Start> startHandler = new Handler<Start>() {

            @Override
            public void handle(Start event) {
                Integer lastid;
                if (messageStatus.isEmpty()) {
                    lastid = -1;
                } else {
                    lastid = messageStatus.lastKey();
                }
                LOG.info("Starting new FRComponent. Last key was {}", lastid);
                Integer msgid = lastid + 1;
                if (msgid >= NUM_FR_MESSAGES) {
                    TestUtil.submit(STOPPED);
                    LOG.info("FRComponent is done.");
                    return;
                }
                if (messageStatus.putIfAbsent(msgid, SENDING) != null) {
                    LOG.error("Key {} was already present in messageStatus!", msgid);
                    TestUtil.submit(FAIL);
                }
                TestMessage msg = new TestMessage(self, acker, msgid, protos[0]);
                trigger(msg, net);
            }

        };

        Handler<Ack> ackHandler = new Handler<Ack>() {

            @Override
            public void handle(Ack event) {
                messageStatus.put(event.msgId, ACKED);
                throw new RuntimeException(); // crash this thing
            }
        };

        private final ComponentProxy myProxy = new ComponentProxy() {
            @Override
            public <P extends PortType> void trigger(Event e, Port<P> p) {
                FRComponent.this.trigger(e, p);
            }

            @Override
            public <T extends ComponentDefinition> Component create(Class<T> definition, Init<T> initEvent) {
                return FRComponent.this.create(definition, initEvent);
            }

            @Override
            public <T extends ComponentDefinition> Component create(Class<T> definition, None initEvent) {
                return FRComponent.this.create(definition, initEvent);
            }

            @Override
            public void destroy(Component component) {
                FRComponent.this.destroy(component);
            }

            @Override
            public <P extends PortType> Channel<P> connect(Positive<P> positive, Negative<P> negative) {
                return FRComponent.this.connect(positive, negative);
            }

            @Override
            public <P extends PortType> Channel<P> connect(Negative<P> negative, Positive<P> positive) {
                return FRComponent.this.connect(negative, positive);
            }

            @Override
            public <P extends PortType> void disconnect(Negative<P> negative, Positive<P> positive) {
                FRComponent.this.disconnect(negative, positive);
            }

            @Override
            public <P extends PortType> void disconnect(Positive<P> positive, Negative<P> negative) {
                FRComponent.this.disconnect(positive, negative);
            }

            @Override
            public Negative<ControlPort> getControlPort() {
                return FRComponent.this.control;
            }
        };
    }

    public static class Acker extends ComponentDefinition {

        private final Positive<Network> net = requires(Network.class);

        private final TestAddress self;

        public Acker(FRInit init) {
            self = init.ackerAddr;

            subscribe(msgHandler, net);
        }

        Handler<TestMessage> msgHandler = new Handler<TestMessage>() {

            @Override
            public void handle(TestMessage event) {
                messageStatus.put(event.msgId, RECEIVED);
                trigger(new Ack(self, event.getSource(), event.msgId, (protos.length < 2 ? protos[0] : protos[1])), net);
            }
        };
    }

    public static class FRInit extends Init {

        public final TestAddress frAddr;
        public final TestAddress ackerAddr;

        public FRInit(TestAddress frAddr, TestAddress ackerAddr) {
            this.frAddr = frAddr;
            this.ackerAddr = ackerAddr;
        }
    }

    public static class TestMessage extends Message implements Serializable {

        public final int msgId;

        public TestMessage(TestAddress src, TestAddress dst, int id, Transport p) {
            super(src, dst, p);
            this.msgId = id;
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append(this.getClass().getSimpleName());
            sb.append("(");
            sb.append("SRC: ");
            sb.append(this.getSource());
            sb.append(", DST: ");
            sb.append(this.getDestination());
            sb.append(", PRT: ");
            sb.append(this.getProtocol());
            sb.append(", msgId: ");
            sb.append(this.msgId);
            sb.append(")");
            return sb.toString();
        }

        public Ack ack() {
            return new Ack(this.getDestination(), this.getSource(), msgId, this.getProtocol());
        }
    }

    public static class Ack extends Message implements Serializable {

        public final int msgId;

        public Ack(TestAddress src, TestAddress dst, int id, Transport p) {
            super(src, dst, p);
            this.msgId = id;
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append(this.getClass().getSimpleName());
            sb.append("(");
            sb.append("SRC: ");
            sb.append(this.getSource());
            sb.append(", DST: ");
            sb.append(this.getDestination());
            sb.append(", PRT: ");
            sb.append(this.getProtocol());
            sb.append(", msgId: ");
            sb.append(this.msgId);
            sb.append(")");
            return sb.toString();
        }
    }

    public static class Recover implements KompicsEvent {

        public final long timestamp;

        public Recover(long timestamp) {
            this.timestamp = timestamp;
        }
    }
}

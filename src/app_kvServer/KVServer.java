package app_kvServer;


import app_kvServer.gossip.GossipEventListener;
import app_kvServer.gossip.Gossiper;
import app_kvServer.mapreduce.MapReduceRequestHandler;
import app_kvServer.persistence.CachedDiskStorage;
import app_kvServer.persistence.PersistenceService;
import app_kvServer.sync.Synchronizer;
import common.messages.gossip.ClusterDigest;
import common.utils.HostAndPort;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.ThreadContext;

import javax.management.MBeanServer;
import javax.management.ObjectName;
import java.io.File;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

public class KVServer implements Runnable, SessionRegistry, GossipEventListener {

    private static final Logger LOG = LogManager.getLogger(KVServer.class);

    private final int port;
    private final int cacheSize;
    private final File dataDirectory;
    private final CacheReplacementStrategy cacheStrategy;
    private final int replicationFactor;

    private final Set<ClientConnection> activeSessions;
    private final ServerState serverState;

    private ServerSocket serverSocket;
    private AtomicBoolean running;

    private CleanUpWorker cleanUpWorker;

    /**
     * Entry point for the server.
     * @param args Configuration for the server. In order:
     *             port - defaults to 12345
     *             cache size - defaults to 10000
     *             cache strategy - can be one of FIFO, LRU, LFU
     */
    public static void main(String[] args) {
        int port = 50000;
        int cacheSize = 10000;
        CacheReplacementStrategy strategy = CacheReplacementStrategy.FIFO;

        // make log4j inherit thread contexts from parent thread because we use a lot of workers
        System.setProperty("isThreadContextMapInheritable", "true");
        System.setProperty("log4j2.isThreadContextMapInheritable", "true");

        if (args.length >= 1) {
            try {
                port = Integer.parseUnsignedInt(args[0]);
            } catch (NumberFormatException e) {
                System.err.println("First argument (port) must be a positive integer.");
                System.exit(1);
            }
        }
        if (args.length >= 2) {
            try {
                cacheSize = Integer.parseUnsignedInt(args[1]);
            } catch (NumberFormatException e) {
                System.err.println("Second argument (cache size) must be a positive integer.");
                System.exit(1);
            }
        }
        if (args.length >= 3) {
            try {
                strategy = CacheReplacementStrategy.valueOf(args[2]);
            } catch (IllegalArgumentException e) {
                System.err.println("Third argument (cache strategy) must be FIFO, LRU or LFU.");
                System.exit(1);
            }
        }

        File dataDirectory = new File("./data_" + port);
        KVServer server = new KVServer(port, dataDirectory, cacheSize, strategy);
        // not doing this in a thread by choice
        server.run();
    }

    /**
     * Start KV Server at given port
     *
     * @param port      given port for persistence server to operate
     * @param dataDirectory directory to store data in
     * @param cacheSize specifies how many key-value pairs the server is allowed
     *                  to keep in-memory
     * @param cacheStrategy  specifies the cache replacement strategy in case the cache
     *                  is full and there is a GET- or PUT-request on a key that is
     *                  currently not contained in the cache.
     */
    public KVServer(int port, File dataDirectory, int cacheSize, CacheReplacementStrategy cacheStrategy) {
        this.port = port;
        this.cacheSize = cacheSize;
        this.cacheStrategy = cacheStrategy;
        this.replicationFactor = 3;

        this.activeSessions = new HashSet<>();
        this.dataDirectory = dataDirectory;
        this.running = new AtomicBoolean(false);
        String hostname = System.getenv().getOrDefault("KV_HOSTNAME", "127.0.0.1");
        this.serverState = new ServerState(new HostAndPort(hostname, port));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void run() {
        LOG.info("Binding to {}", serverState.getMyself());
        ThreadContext.put("serverPort", Integer.toString(port));

        Gossiper.initialize(serverState.getMyself());
        Gossiper.getInstance().addListener(this);
        Synchronizer.initialize(serverState);

        // try to register server state as MBean
        try {
            MBeanServer mBeanServer = ManagementFactory.getPlatformMBeanServer();
            ObjectName name = new ObjectName("server:name=State");
            mBeanServer.registerMBean(serverState, name);
            LOG.info("Registered server state MBean: " + name);
        } catch (Exception e) {
            LOG.error("Could not register server state MBean.", e);
        }

        // TODO handle errors more gracefully
        try {
            PersistenceService persistenceService =
                    new CachedDiskStorage(dataDirectory, cacheSize, cacheStrategy);

            this.cleanUpWorker = new CleanUpWorker(serverState.getMyself(), persistenceService,
                    10, 60, replicationFactor);
            this.cleanUpWorker.start();

            ReplicatingDataRequestHandler dataRequestHandler =
                    new ReplicatingDataRequestHandler(serverState, persistenceService, 3);
            MapReduceRequestHandler mapReduceRequestHandler =
                    new MapReduceRequestHandler(serverState.getMyself(), persistenceService);
            Gossiper.getInstance().addListener(dataRequestHandler);

            serverSocket = new ServerSocket(port);
            LOG.info("Server listening on port {}.", port);

            // accept client connections
            running.set(true);
            while (running.get()) {
                Socket clientSocket = serverSocket.accept();

                ClientConnection clientConnection = new ClientConnection(
                        clientSocket,
                        persistenceService,
                        this,
                        serverState,
                        dataRequestHandler,
                        mapReduceRequestHandler);

                clientConnection.start();
            }
        } catch (IOException e) {
            if (running.get()) {
                LOG.error("Error while accepting connections.", e);
            }
            // else: orderly shutdown
        } finally {
            cleanSocketShutdown();
        }
    }

    /**
     * Stop the server.
     */
    public void stop() {
        this.running.set(false);

        if (cleanUpWorker != null) {
            cleanUpWorker.interrupt();
        }

        for (ClientConnection session : activeSessions) {
            session.terminate();
        }

        Gossiper.getInstance().stop();

        cleanSocketShutdown();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void registerSession(ClientConnection session) {
        activeSessions.add(session);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void unregisterSession(ClientConnection session) {
        activeSessions.remove(session);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void requestShutDown() {
        stop();
    }

    private void cleanSocketShutdown() {
        LOG.info("Closing connection.");
        if (serverSocket != null) {
            try {
                serverSocket.close();
            } catch (IOException e) {
                LOG.debug("Error closing connection.", e);
            }
        }
    }

    // TODO this can be removed
    @Override
    public void clusterChanged(ClusterDigest clusterDigest) {
        LOG.info("Cluster changed: {}", clusterDigest);
        cleanUpWorker.reportChange();
        Set<HostAndPort> upNodes = clusterDigest.getCluster().entrySet().stream()
                .filter(node -> node.getValue().getStatus().isParticipating())
                .map(node -> node.getKey())
                .collect(Collectors.toSet());
        LOG.info("Setting alive cluster nodes to: {}", upNodes);
        serverState.setClusterNodes(upNodes);
    }

    @Override
    public void nodeChanged(HostAndPort node, common.messages.gossip.ServerState.Status newState) {
        LOG.debug("Node={} changed to state={}", node, newState);
        if (newState == common.messages.gossip.ServerState.Status.DECOMMISSIONED) {
            LOG.warn("Received DECOMMISSIONED message for node {}", node);
            if (serverState.getMyself().equals(node)) {
                LOG.info("Shutting down because received DECOMMISSIONED message.");
                requestShutDown();
            } else {
                Synchronizer.getInstance().initiateDecommissioning(node);
            }
        }
    }

}

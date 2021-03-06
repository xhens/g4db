package app_kvServer.gossip;

import common.CorrelatedMessage;
import common.Protocol;
import common.messages.gossip.ServerState;
import common.messages.gossip.ClusterDigest;
import common.utils.HostAndPort;
import common.utils.RecordReader;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.ThreadContext;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

import static common.Protocol.RECORD_SEPARATOR;

/**
 * The Gossiper is responsible for server-to-server gossip communication in order to communicate
 * the cluster state.
 *
 * It is a singleton instance per server.
 */
public class Gossiper {

    private static final Logger LOG = LogManager.getLogger(Gossiper.class);
    private static Gossiper instance;

    private final HostAndPort myself;
    private final long generation;
    private long heartbeat;
    private final Map<HostAndPort, ServerState> cluster;
    private final Set<HostAndPort> seedNodes;
    private final ScheduledThreadPoolExecutor executorService;
    private final Set<HostAndPort> deadCandidates;
    private final Random random;
    private final Collection<GossipEventListener> eventListeners;
    private final int gossipIntervalSeconds;

    // only to be accessed via singleton pattern
    private Gossiper(HostAndPort myself) {
        this.myself = myself;
        this.generation = System.currentTimeMillis();
        this.heartbeat = 0;
        this.cluster = new HashMap<>();
        this.seedNodes = new HashSet<>();
        this.executorService = new ScheduledThreadPoolExecutor(5);
        this.deadCandidates = new HashSet<>();
        this.random = new Random();
        this.eventListeners = new HashSet<>();
        this.gossipIntervalSeconds = 2;

        cluster.put(myself,
                new ServerState(generation, heartbeat, ServerState.Status.STOPPED, 1));
    }

    /**
     * Initialize the Gossiper for this server instance.
     *
     * This method is not thread-safe and should be called accordingly.
     *
     * @param myself Address to which the server instance is bound
     */
    public static void initialize(HostAndPort myself) {
        if (instance != null) {
            throw new IllegalStateException("Gossiper already initialized.");
        }

        instance = new Gossiper(myself);
        Arrays.stream(System.getProperty("seedNodes", "").split(",")).forEach(nodeStr -> {
            String[] parts = nodeStr.split(":");
            HostAndPort node = new HostAndPort(parts[0], Integer.parseInt(parts[1]));
            instance.addSeed(node);
        });
        instance.start();
    }

    /**
     * Return the initialized Gossiper instance.
     *
     * @return Gossiper instance
     */
    public static Gossiper getInstance() {
        if (instance == null) {
            throw new IllegalStateException("Gossiper must be initialized first.");
        }
        return instance;
    }

    /**
     * Start the gossiping.
     */
    public void start() {
        LOG.info("Gossiper starting with seed nodes: {}", seedNodes);
        executorService.scheduleAtFixedRate(new GossipTask(), 1, gossipIntervalSeconds, TimeUnit.SECONDS);
    }

    /**
     * Stopping the gossiping.
     */
    public void stop() {
        executorService.shutdownNow();
    }

    /**
     * Set the state of this node.
     * @param ownState State
     */
    public synchronized void setOwnState(ServerState.Status ownState) {
        Map<HostAndPort, ServerState> singletonCluster = new HashMap<>();
        singletonCluster.put(myself, ownState(ownState));
        handleIncomingDigest(new ClusterDigest(singletonCluster));
    }

    /**
     * Add a seed node.
     *
     * These will be contacted first to get information about other nodes in the cluster.
     *
     * @param seedNode
     */
    public void addSeed(HostAndPort seedNode) {
        seedNodes.add(seedNode);
    }

    /**
     * Register a GossipEventListener to be notified when the cluster state changes.
     *
     * @param listener
     */
    public void addListener(GossipEventListener listener) {
        this.eventListeners.add(listener);
    }

    /**
     * Return the current view of the cluster.
     *
     * @return
     */
    public ClusterDigest getClusterDigest() {
        return new ClusterDigest(cluster);
    }

    /**
     * Merge the cluster information we got from some other node with the locally known version.
     * @param other Cluster information from other node
     * @return Updated cluster information
     */
    public synchronized ClusterDigest handleIncomingDigest(ClusterDigest other) {
        Map<HostAndPort, ServerState> otherCluster = other.getCluster();

        // nodes the other one knows and we don't we simply add to our state of the cluster
        Set<HostAndPort> toAdd = new HashSet<>(otherCluster.keySet());
        toAdd.removeAll(cluster.keySet());

        toAdd.stream().forEach(node -> {
            cluster.put(node, otherCluster.get(node));
        });

        // nodes we both know we have to merge accordingly
        Set<HostAndPort> toMerge = new HashSet<>(otherCluster.keySet());
        toMerge.retainAll(cluster.keySet());

        Set<HostAndPort> othersMoreRecent = toMerge.stream()
                .filter(node -> cluster.get(node).compareTo(otherCluster.get(node)) < 0)
                .collect(Collectors.toSet());

//        Set<HostAndPort> oursMoreRecent = toMerge.stream()
//                .filter(node -> cluster.get(node).compareTo(otherCluster.get(node)) > 0)
//                .collect(Collectors.toSet());

        Map<HostAndPort, ServerState.Status> changes = new HashMap<>();

        othersMoreRecent.stream().forEach(node -> {
            ServerState localState = cluster.get(node);
            ServerState remoteState = otherCluster.get(node);
            if (localState.getStatus() != remoteState.getStatus()) {
                changes.put(node, remoteState.getStatus());
            }
            cluster.put(node, remoteState);
        });

        // handle event listeners
        LOG.debug("Cluster: {}", cluster);
        for (GossipEventListener listener : eventListeners) {
            for (HostAndPort node : toAdd) {
                listener.nodeAdded(node, otherCluster.get(node).getStatus());
            }

            for (Map.Entry<HostAndPort, ServerState.Status> change : changes.entrySet()) {
                listener.nodeChanged(change.getKey(), change.getValue());
            }

            if (!toAdd.isEmpty() || !changes.isEmpty()) {
                listener.clusterChanged(new ClusterDigest(cluster));
            }
        }

        return new ClusterDigest(cluster);
    }

    // the efficiency of of gossiping improves if we select the nodes we contact with some care
    private Collection<HostAndPort> selectGossipTargets() {
        // parameters
        final int fanOut = 1;
        final double contactSeedNodeChance = 0.3;
        final double contactDeadNodeChance = 0.3;

        // we try to contact <fanOut> regular nodes in each round
        List<HostAndPort> candidates = new ArrayList<>(cluster.keySet());
        Collections.shuffle(candidates);
        Set<HostAndPort> targets = candidates.stream()
                .filter(node -> !myself.equals(node) && !deadCandidates.contains(node))
                .limit(fanOut)
                .collect(Collectors.toSet());

        // seed node handling
        // if we don't know about other nodes we definitely contact a seed node
        // else we probabilistically contact a seed node additionally to have some information hotspots
        if (targets.isEmpty() || random.nextFloat() < contactSeedNodeChance) {
            List<HostAndPort> seeds = seedNodes.stream()
                    .filter(node -> !myself.equals(node))
                    .collect(Collectors.toList());
            Collections.shuffle(seeds);
            targets.add(seeds.get(0));
        }

        // dead node handling
        // we probabilistically retry to reach a potentially dead node to find out if they came back to life
        if (!deadCandidates.isEmpty() && random.nextFloat() < contactDeadNodeChance) {
            List<HostAndPort> deadNodes = deadCandidates.stream()
                    .filter(node -> !myself.equals(node))
                    .collect(Collectors.toList());
            Collections.shuffle(deadNodes);
            targets.add(deadNodes.get(0));
        }

        return targets;
    }

    private synchronized ServerState ownState(ServerState.Status overwrite) {
        ServerState.Status newStatus = Optional
                .ofNullable(overwrite)
                .orElse(cluster.get(myself).getStatus());
        long newStatusVersion = Optional
                .ofNullable(cluster.get(myself))
                .map(ServerState::getStateVersion)
                .orElse(1l);
        if (overwrite != null) {
            newStatusVersion++;
        }
        heartbeat = System.currentTimeMillis() - generation;
        return new ServerState(generation, heartbeat, newStatus, newStatusVersion);
    }

    private class GossipTask implements Runnable {
        @Override
        public void run() {
            ThreadContext.put("serverPort", Integer.toString(myself.getPort()));

            // update our own state
            cluster.put(myself, ownState(null));
            ClusterDigest digest = new ClusterDigest(cluster);

            Collection<HostAndPort> gossipTargets = selectGossipTargets();
            LOG.debug("Gossiping digest {} to targets {}", digest, gossipTargets);

            // initiate a gossip exchange with a small number of cluster nodes
            Map<HostAndPort, Future<ClusterDigest>> inFlightRequests = new HashMap<>();
            for (HostAndPort target : gossipTargets) {
                inFlightRequests.put(target,
                        executorService.schedule(
                                new GossipExchange(target), 0, TimeUnit.SECONDS));
            }

            // resolve the outcomes of the gossip exchanges
            for (Map.Entry<HostAndPort, Future<ClusterDigest>> request : inFlightRequests.entrySet()) {
                try {
                    digest = request.getValue().get(1, TimeUnit.SECONDS);
                    handleIncomingDigest(digest);
                    deadCandidates.remove(request.getKey());
                } catch (InterruptedException | ExecutionException | TimeoutException e) {
                    LOG.debug("Could not gossip with node " + request.getKey(), e);
                    deadCandidates.add(request.getKey());
                }
            }
        }
    }

    private class GossipExchange implements Callable<ClusterDigest> {

        private final HostAndPort target;

        public GossipExchange(HostAndPort target) {
            this.target = target;
        }

        @Override
        public ClusterDigest call() throws Exception {
            Socket socket = new Socket(target.getHost(), target.getPort());
            LOG.debug("Gossip call {}", socket.getLocalSocketAddress());

            try {
                // send the message
                OutputStream os = socket.getOutputStream();
                byte[] payloadOut = Protocol.encode(new ClusterDigest(cluster), heartbeat);
                os.write(payloadOut);
                os.write(RECORD_SEPARATOR);

                // receive the reply
                InputStream is = socket.getInputStream();
                RecordReader recordReader = new RecordReader(is, RECORD_SEPARATOR);
                byte[] payloadIn = recordReader.read();
                CorrelatedMessage correlatedMessage = Protocol.decode(payloadIn);

                return (ClusterDigest) correlatedMessage.getGossipMessage();
            } finally {
                socket.close();
            }
        }

    }

}

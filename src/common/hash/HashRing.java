package common.hash;

import common.utils.HostAndPort;

import java.util.Collection;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/**
 * Consistent Hashing distributes a key range and nodes across a ring
 * such that each key belongs to one node and the assignments change
 * as little as possible when nodes are added or deleted.
 */
public class HashRing {
    private final TreeMap<Integer, HostAndPort> circle = new TreeMap<>();

    /**
     * Default constructor.
     *
     * Creates an empty ring.
     */
    public HashRing() {
    }

    /**
     * Constructor.
     *
     * Creates a ring and populates it with given nodes.
     * @param nodes Nodes
     */
    public HashRing(Collection<HostAndPort> nodes) {
        nodes.stream().forEach(node -> circle.put(getHash(addressToString(node)), node));
    }

    /**
     * Add a node to the hash ring.
     * @param node Address of the node
     */
    private String addressToString(HostAndPort node){
        return String.format("%s:%d", node.getHost(), node.getPort());

    }

    public void addNode(HostAndPort node) {
        int nodeKey = getHash(addressToString(node));
        circle.put(nodeKey, node);
    }

    /**
     * Remove a node from the hash ring.
     * @param node Address of the node
     */
    public void removeNode(HostAndPort node) {
        int nodeKey = getHash(addressToString(node));
        circle.remove(nodeKey);
    }

    /**
     * Return all nodes on the ring.
     * @return Server nodes
     */
    public Collection<HostAndPort> getNodes() {
        // TODO
        return circle.values();
    }

    /**
     * Return the range of hash values a node is responsible for.
     * @param node Address of the node
     * @return {@link Range} of hash values
     * @throws IllegalArgumentException if the node does not exist
     */
    public Range getAssignedRange(HostAndPort node) {
        int upperBound = getHash(addressToString(node));
        int lowerBound = Optional
                .ofNullable(circle.lowerKey(upperBound))
                .orElse(circle.lastKey());
        return new Range(lowerBound, upperBound);
    }

    /**
     * Return the node responsible for the given value.
     * @param val The value
     * @return Address of the responsible node
     */
    public HostAndPort getResponsibleNode(String val) {
        // TODO
        int hashVal = getHash(val);
        for (int key : circle.navigableKeySet()) {
            if (hashVal <= key)
                return circle.get(key);
        }
        //In case the predecessor node has a higher position than the actual server
        return circle.firstEntry().getValue();
    }

    /**
     * Return the node after the given one if it will be added.
     *
     * This is the node who's value range will change.
     *
     * @param node The node that potentially will be added
     * @return Successor node on the ring
     */
    public HostAndPort getSuccessor(HostAndPort node) {
        int nodeKey = getHash(addressToString(node));
        if (circle.higherKey(nodeKey) == null)
            return circle.firstEntry().getValue();
        return circle.higherEntry(nodeKey).getValue();

    }

    /**
     * Return nth node after the given one if it will be added
     * n == 1 -> first successor (same as using getSuccessor())
     * n == 2 -> second successor
     * @param node The node that will be added
     * @param positions number of positions
     * @return nth successor node on the ring
     */
    public HostAndPort getNthSuccessor(HostAndPort node, int positions) {
        HostAndPort successor = node;
        for (int i = 0; i < positions; i++) {
            int currentKey = getHash(addressToString(successor));

            successor = Optional
                    .ofNullable(circle.higherEntry(currentKey))
                    .orElse(circle.firstEntry())
                    .getValue();
        }
        return successor;
    }

    /**
     * Return the predecessor of given node.
     * @param node Current node
     * @param positions Number of positions we traverse the ring in direction of smaller values
     * @return Predecessor node
     */
    public HostAndPort getPredecessor(HostAndPort node, int positions) {
        HostAndPort current = node;

        for (int i = 0; i < positions; i++) {
            current = Optional
                    .ofNullable(circle.lowerEntry(getHash(addressToString(current))))
                    .orElse(circle.lastEntry())
                    .getValue();
        }

        return current;
    }

    /**
     * Traverse the ring from given start by specified amount of positions.
     *
     * If positions is positive the ring will be traversed in positive direction,
     * if it is negative it will be traversed in negative direction.
     *
     * @param start Node to start traversal from
     * @param positions Number of nodes to hop, may be positive or negative
     * @return The reached node
     */
    public HostAndPort traverse(HostAndPort start, int positions) {
        if (positions < 0) {
            return getPredecessor(start, -positions);
        } else {
            return getNthSuccessor(start, positions);
        }
    }

    /**
     * Return the number of positions target comes after reference.
     * @param reference Reference node
     * @param target Target node
     * @return Number of positions
     */
    public int findSuccessorNumber(HostAndPort reference, HostAndPort target) {
        if (!contains(reference) || !contains(target)) {
            throw new IllegalArgumentException(String.format(
                    "Both reference (%s) and target (%s) must be contained in ring (%s)",
                    reference,
                    target,
                    getNodes()));
        }

        HostAndPort current = reference;
        int successorNumber = 0;
        while (!current.equals(target)) {
            successorNumber++;
            int currentKey = getHash(addressToString(current));

            current = Optional
                    .ofNullable(circle.higherEntry(currentKey))
                    .orElse(circle.firstEntry())
                    .getValue();
        }

        return successorNumber;
    }

    /**
     * Return if the hash ring contains given node.
     * @param node Node
     * @return True if contained
     */
    public boolean contains(HostAndPort node) {
        return circle.containsValue(node);
    }

    /**
     * Return if the hash ring is empty.
     * @return True if empty
     */
    public boolean isEmpty() {
        return circle.isEmpty();
    }

    // dynamic wrapper to we can override it
    protected int getHash(String val) {
        return hash(val);
    }

    /**
     * Return the hash for given value.
     * @param val The value
     * @return The hash
     */
    public static int hash(String val) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] d = md.digest(val.getBytes());
            //Since it is easier to only look at the first 4 Byte
            int hash = d[0] << 24 | (d[1] & 0xff) << 16 | (d[2] & 0xff) << 8 | (d[3] & 0xff);
            return hash;
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError("Won't happen.");
        }
    }

    @Override
    public String toString() {
        StringJoiner nodeJoiner = new StringJoiner("\n", "Ring[\n", "]");
        getNodes().forEach(node -> {
            Range assignedRange = getAssignedRange(node);
            double ownershipFraction = 100.0 * assignedRange.getExtent() / ((long) Integer.MAX_VALUE - Integer.MIN_VALUE);
            nodeJoiner.add(String.format("%s : %s (%.2f%%)", node, assignedRange, ownershipFraction));
        });
        return nodeJoiner.toString();
    }
}

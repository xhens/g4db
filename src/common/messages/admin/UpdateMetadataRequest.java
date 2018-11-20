package common.messages.admin;

import java.net.InetSocketAddress;
import java.util.*;

/**
 * Requests the server to update it's metadata table to the one
 * provided in this message.
 */
public class UpdateMetadataRequest extends AdminMessage {

    /** The type code for serialization. */
    public static final byte TYPE_CODE = 0x02;

    private final Set<InetSocketAddress> nodes = new HashSet<>();

    /**
     * Add a node to the message.
     * @param node The node
     */
    public void addNode(InetSocketAddress node) {
        nodes.add(node);
    }

    /**
     * Return all nodes contained in this message.
     * @return Collection of nodes
     */
    public Collection<InetSocketAddress> getNodes() {
        return nodes;
    }

}
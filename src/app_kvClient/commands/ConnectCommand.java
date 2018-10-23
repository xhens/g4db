package app_kvClient.commands;

import app_kvClient.KVClient;
import client.Client;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

/**
 * The ConnectCommand is responsible for establishing a connection between
 * client and server.
 */
public class ConnectCommand implements Command {

    /** ID of this command. */
    public static final String ID = "connect";

    private String host;
    private int port;

    /** {@inheritDoc} */
    @Override
    public String getID() {
        return ID;
    }

    /** {@inheritDoc} */
    @Override
    public List<Argument> getArguments() {
        return Arrays.asList(
            new Argument("host", "Host name of the server"),
            new Argument("port", "Port to connect to")
        );
    }

    /** {@inheritDoc} */
    @Override
    public String getDescription() {
        return "Connect to the specified server.";
    }

    /** {@inheritDoc} */
    @Override
    public void init(String[] args) throws CommandException {
        // host name
        if (args.length != 2) {
            throw new CommandException("Wrong number of arguments", this);
        }

        this.host = args[0];

        // port number
        try {
            this.port = Integer.parseUnsignedInt(args[1]);
        } catch (NumberFormatException e) {
            throw new CommandException("Port not parsable", this);
        }
    }

    /** {@inheritDoc} */
    @Override
    public String run(KVClient cli) throws CommandException {
        Client client = cli.getClient();

        if (client.isConnected()) {
            throw new CommandException("Already connected.", this);
        }

        String result = null;
        try {
            result = client.connect(host, port);
        } catch (IOException e) {
            throw new CommandException("Could not connect socket.", this, e);
        }
        return result;
    }

}

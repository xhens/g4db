package client;

import client.exceptions.ClientException;
import client.exceptions.ConnectionException;
import common.CorrelatedMessage;
import common.Protocol;
import common.exceptions.ProtocolException;
import common.messages.Message;
import common.utils.ContextPreservingThread;
import common.utils.FutureUtils;
import common.utils.HostAndPort;
import common.utils.RecordReader;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.ThreadContext;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.UnknownHostException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Asynchronous client-side communication module.
 *
 * Transparently handles reconnecting and resending messages if the connection is lost.
 * Messages might be duplicated in that case.
 */
public class CommunicationModule {

    private final static Logger LOG = LogManager.getLogger(CommunicationModule.class);
    private final static int DEFAULT_BUFFER_SIZE = 1000;
    private final static byte RECORD_SEPARATOR = 0x1e;

    private final HostAndPort address;
    private final boolean reconnecting;
    private final AtomicBoolean terminated;
    private final AtomicBoolean restarting;
    private volatile boolean running;
    private final AtomicLong messageCounter;
    private final BlockingDeque<AcceptedMessage> outstandingRequests;
    private final Map<Long, AcceptedMessage> correlatedRequests;

    private Socket socket;
    private WriterThread writerThread;
    private ReaderThread readerThread;

    /**
     * Constructor.
     *
     * @param address Server address to connect against
     */
    public CommunicationModule(HostAndPort address) {
        this(address, true, DEFAULT_BUFFER_SIZE);
    }

    /**
     * Constructor.
     *
     * @param address Server address to connect against
     * @param reconnecting If true, module tries to reestablish lost connections
     * @param bufferCapacity Maximum number of messages in the outgoing buffer
     */
    public CommunicationModule(HostAndPort address, boolean reconnecting, int bufferCapacity) {
        this.address = address;
        this.reconnecting = reconnecting;
        this.terminated = new AtomicBoolean(false);
        this.restarting = new AtomicBoolean(false);
        this.messageCounter = new AtomicLong(0);
        this.outstandingRequests = new LinkedBlockingDeque<>();
        this.correlatedRequests = new ConcurrentHashMap<>(bufferCapacity);
        this.running = false;
        ThreadContext.put("connection", address.toString());
    }

    /**
     * Send a message to the server and get a reference to the future reply.
     *
     * @param msg The message to send
     * @return {@link CompletableFuture} the future reply
     */
    public CompletableFuture<CorrelatedMessage> send(Message msg) {
        long correlationId = messageCounter.addAndGet(1);
        CompletableFuture<CorrelatedMessage> future = new CompletableFuture<>();
        AcceptedMessage acceptedMessage = new AcceptedMessage(correlationId, msg, future);
        correlatedRequests.put(correlationId, acceptedMessage);
        try {
            outstandingRequests.putLast(acceptedMessage);
        } catch (InterruptedException e) {
            LOG.error("Error adding request to queue.", e);
            correlatedRequests.remove(correlationId);
            future.completeExceptionally(e);
        }
        return future;
    }

    /**
     * Start the client.
     */
    public void start() throws ClientException {
        start(true);
    }

    /**
     * Start the client.
     *
     * @param retry If true, establishing the connection is retried with exponential backoff.
     */
    public void start(boolean retry) throws ClientException {
        terminated.set(false);
        restartIfNotTerminated(retry);
        this.running = true;
    }

    /**
     * Stop the client.
     */
    public void stop() {
        terminated.set(true);
        shutDownClient();
        this.running = false;
    }

    /**
     * Get address to which this communication module is bound.
     * @return The address
     */
    public HostAndPort getAddress() {
        return address;
    }

    /**
     * Return if the client is currently running.
     * The client is also considered running when it's currently trying to reconnect.
     * @return Running state
     */
    public boolean isRunning() {
        return !terminated.get();
    }

    /**
     * Triggers a single request-response reply with target.
     *
     * @param target Address of target.
     * @param request Request to send.
     * @param <T> Type of message.
     * @return Future reply.
     */
    public static <T extends Message> CompletableFuture<CorrelatedMessage> oneOffMessage(HostAndPort target, T request) {
        try {
            CommunicationModule communicationModule = new CommunicationModule(target, false, 1);
            communicationModule.start();
            return communicationModule.send(request).whenComplete((res, exc) -> communicationModule.stop());
        } catch (ClientException e) {
            return FutureUtils.failedFuture(e);
        }
    }

    // message that has been accepted into the sending queue
    private static class AcceptedMessage {
        public final long correlationId;
        public final Message message;
        public final CompletableFuture<CorrelatedMessage> futureResult;

        public AcceptedMessage(long correlationId, Message message, CompletableFuture<CorrelatedMessage> futureResult) {
            this.correlationId = correlationId;
            this.message = message;
            this.futureResult = futureResult;
        }
    }

    // takes messages out of the sending queue and sends them
    private class WriterThread extends ContextPreservingThread {

        private final OutputStream outputStream;

        public WriterThread(OutputStream outputStream) {
            this.outputStream = outputStream;
        }

        @Override
        public void run() {
            this.setUpThreadContext();
            try {
                while (!terminated.get() && !restarting.get()) {
                    AcceptedMessage msg = outstandingRequests.pollFirst(100, TimeUnit.MILLISECONDS);
                    if (msg == null) {
                        // no message, check socket health proactively
                        outputStream.write(RECORD_SEPARATOR);
                    } else {
                        ThreadContext.put("correlation", Long.toUnsignedString(msg.correlationId));

                        byte[] payload = Protocol.encode(msg.message, msg.correlationId);
                        outputStream.write(payload, 0, payload.length);
                        outputStream.write(RECORD_SEPARATOR);
                    }
                }
            } catch (IOException | InterruptedException e) {
                LOG.debug("Closing writer thread.", e);
                try {
                    outputStream.close();
                } catch (IOException e2) {
                    LOG.warn("Could not close output stream.", e2);
                }
                if (restarting.compareAndSet(false, true)) {
                    // when both reader and writer realize the broken connection, the first one wins
                    LOG.debug("Writer thread triggered the restart.");
                    try {
                        restartIfNotTerminated(reconnecting);
                    } catch (ClientException e1) {
                        LOG.error("Could not restart connection.", e1);
                    }
                }
            }
        }
    }

    // reads from the incoming stream and resolves given promises about replies
    private class ReaderThread extends ContextPreservingThread {

        private final InputStream inputStream;
        private final RecordReader recordReader;

        public ReaderThread(InputStream inputStream) {
            this.inputStream = inputStream;
            this.recordReader = new RecordReader(inputStream, RECORD_SEPARATOR);
        }

        @Override
        public void run() {
            this.setUpThreadContext();
            try {
                while (!terminated.get() && !restarting.get()) {
                    byte[] payload = recordReader.read();

                    if (payload == null) {
                        //LOG.info("Restarting client because no more input from socket.");
                        //break;
                        continue;
                    }
                    if (Arrays.equals(Protocol.SHUTDOWN_CMD, payload)) {
                        // input stream has been shutdown externally, we can stop
                        LOG.info("Shutting down client because received connection closed.");
                        terminated.set(true);
                        break;
                    }

                    try {
                        CorrelatedMessage result = Protocol.decode(payload);

                        ThreadContext.put("correlation", Long.toUnsignedString(result.getCorrelationNumber()));

                        if (!correlatedRequests.containsKey(result.getCorrelationNumber())) {
                            // TODO we could implement observers for this case
                            LOG.warn("Uncorrelated message received: {}", result);
                            continue;
                        }

                        CompletableFuture<CorrelatedMessage> future =
                                correlatedRequests.get(result.getCorrelationNumber()).futureResult;

                        future.complete(result);
                        correlatedRequests.remove(result.getCorrelationNumber());
                    } catch (ProtocolException e) {
                        LOG.error("Could not decode message.", e);
                    }
                }
            } catch (IOException e) {
                LOG.debug("Closing reader thread.", e);
                try {
                    inputStream.close();
                } catch (IOException e2) {
                    LOG.warn("Could not close input stream.", e2);
                }
                if (restarting.compareAndSet(false, true)) {
                    // when both reader and writer realize the broken connection, the first one wins
                    LOG.debug("Reader thread triggered the restart.");
                    try {
                        restartIfNotTerminated(reconnecting);
                    } catch (ClientException e1) {
                        LOG.error("Could not restart connection.", e1);
                    }
                }
            }
        }

    }

    // orderly shutdown this client
    private void shutDownClient() {
        if (terminated.get()) {
            LOG.info("Shutting down client.");
        }
        if (socket != null) {
            try {
                socket.shutdownInput();
                socket.shutdownOutput();
                // will also close the associated input and output streams
                socket.close();
            } catch (IOException e) {
                LOG.debug("Could not close socket.", e);
            }
        }

        // make sure both old worker threads are done
        if (writerThread != null && writerThread != Thread.currentThread()) {
            try {
                writerThread.join();
            } catch (InterruptedException e) {
                // ignore
            }
        }

        if (readerThread != null && readerThread != Thread.currentThread()) {
            try {
                readerThread.join();
            } catch (InterruptedException e) {
                // ignore
            }
        }

        cleanUpOutstandingRequests(false);
    }

    // try to restart the client
    private void restartIfNotTerminated(boolean retry) throws ClientException {
        if (terminated.get()) {
            return;
        }

        restarting.set(true);
        shutDownClient();

        LOG.info("Initiating client (re)start.");

        // try to recreate the socket and the worker threads
        socket = null;
        writerThread = null;
        readerThread = null;
        int reconnectWait = 1;
        int reconnectTries = 0;
        Exception lastException = null;
        while (socket == null && reconnectTries < 5) {
            try {
                // client could get disconnected while we're in this reconnecting loop
                if (terminated.get()) {
                    return;
                }

                socket = new Socket(address.getHost(), address.getPort());
                ThreadContext.put("client", socket.getLocalSocketAddress().toString());
                ThreadContext.put("server", socket.getRemoteSocketAddress().toString());
                writerThread = new WriterThread(socket.getOutputStream());
                readerThread = new ReaderThread(socket.getInputStream());
            } catch (UnknownHostException e) {
                throw new ConnectionException("Connection failed because host is not known.", e);
            } catch (IOException e) {
                lastException = e;
                reconnectTries++;
                // exponential backoff
                if (reconnectWait < 16) {
                    reconnectWait *= 2;
                }

                if (!reconnecting) {
                    break;
                }

                LOG.warn("Could not establish connection on {}. try. Retrying in {} seconds.",
                        reconnectTries, reconnectWait);
                try {
                    Thread.sleep(reconnectWait * 1000);
                } catch (InterruptedException e2) {
                    LOG.warn("Could not wait for restart try.", e);
                }
            }
        }

        // clean up outstanding requests
        if (socket == null) {
            cleanUpOutstandingRequests(false);
            running = false;
            throw new ClientException(String.format("Could not connect to %s to after %d tries.", address, reconnectTries),
                    lastException);
        } else {
            cleanUpOutstandingRequests(true);
        }

        // start the new worker threads
        restarting.set(false);
        writerThread.start();
        readerThread.start();

        // phew, we're done :)
        LOG.info("Client started successfully.");
    }

    private void cleanUpOutstandingRequests(boolean requeue) {
        if (requeue) {
            // requeue outstanding requests
            List<AcceptedMessage> acceptedMessages = new ArrayList<>(correlatedRequests.values());
            // sort by correlation ID descending
            Collections.sort(acceptedMessages, (o1, o2) -> Long.signum(o2.correlationId - o1.correlationId));
            for (AcceptedMessage acceptedMessage : acceptedMessages) {
                try {
                    outstandingRequests.putFirst(acceptedMessage);
                } catch (InterruptedException e) {
                    LOG.error("Could not requeue message.", e);
                }
            }
        } else {
            // cancel all outstanding futures
            Exception cancellationException = new RuntimeException("Client was restarted before this request completed.");
            for (AcceptedMessage acceptedMessage: correlatedRequests.values()) {
                acceptedMessage.futureResult.completeExceptionally(cancellationException);
            }
            correlatedRequests.clear();
        }
    }

}

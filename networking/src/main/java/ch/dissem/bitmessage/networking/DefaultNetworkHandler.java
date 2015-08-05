/*
 * Copyright 2015 Christian Basler
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ch.dissem.bitmessage.networking;

import ch.dissem.bitmessage.InternalContext;
import ch.dissem.bitmessage.InternalContext.ContextHolder;
import ch.dissem.bitmessage.entity.valueobject.InventoryVector;
import ch.dissem.bitmessage.entity.valueobject.NetworkAddress;
import ch.dissem.bitmessage.ports.NetworkHandler;
import ch.dissem.bitmessage.utils.Collections;
import ch.dissem.bitmessage.utils.Property;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static ch.dissem.bitmessage.networking.Connection.Mode.CLIENT;
import static ch.dissem.bitmessage.networking.Connection.Mode.SERVER;
import static ch.dissem.bitmessage.networking.Connection.State.ACTIVE;
import static ch.dissem.bitmessage.networking.Connection.State.DISCONNECTED;
import static ch.dissem.bitmessage.utils.DebugUtils.inc;

/**
 * Handles all the networky stuff.
 */
public class DefaultNetworkHandler implements NetworkHandler, ContextHolder {
    public final static int NETWORK_MAGIC_NUMBER = 8;
    private final static Logger LOG = LoggerFactory.getLogger(DefaultNetworkHandler.class);
    private final ExecutorService pool;
    private final List<Connection> connections = new LinkedList<>();
    private InternalContext ctx;
    private ServerSocket serverSocket;
    private Thread serverThread;
    private Thread connectionManager;

    private ConcurrentMap<InventoryVector, Long> requestedObjects = new ConcurrentHashMap<>();

    public DefaultNetworkHandler() {
        pool = Executors.newCachedThreadPool();
    }

    @Override
    public void setContext(InternalContext context) {
        this.ctx = context;
    }

    @Override
    public void start(final MessageListener listener) {
        if (listener == null) {
            throw new IllegalStateException("Listener must be set at start");
        }
        try {
            serverSocket = new ServerSocket(ctx.getPort());
            serverThread = new Thread(new Runnable() {
                @Override
                public void run() {
                    while (!serverSocket.isClosed()) {
                        try {
                            Socket socket = serverSocket.accept();
                            socket.setSoTimeout(Connection.READ_TIMEOUT);
                            startConnection(new Connection(ctx, SERVER, socket, listener, requestedObjects));
                        } catch (IOException e) {
                            LOG.debug(e.getMessage(), e);
                        }
                    }
                }
            }, "server");
            serverThread.start();
            connectionManager = new Thread(new Runnable() {
                @Override
                public void run() {
                    while (!Thread.interrupted()) {
                        try {
                            int active = 0;
                            synchronized (connections) {
                                for (Iterator<Connection> iterator = connections.iterator(); iterator.hasNext(); ) {
                                    Connection c = iterator.next();
                                    if (c.getState() == DISCONNECTED) {
                                        // Remove the current element from the iterator and the list.
                                        iterator.remove();
                                    }
                                    if (c.getState() == ACTIVE) {
                                        active++;
                                    }
                                }
                            }
                            if (active < NETWORK_MAGIC_NUMBER) {
                                List<NetworkAddress> addresses = ctx.getNodeRegistry().getKnownAddresses(
                                        NETWORK_MAGIC_NUMBER - active, ctx.getStreams());
                                for (NetworkAddress address : addresses) {
                                    startConnection(new Connection(ctx, CLIENT, address, listener, requestedObjects));
                                }
                            }
                            Thread.sleep(30000);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        } catch (Exception e) {
                            LOG.error("Error in connection manager. Ignored.", e);
                        }
                    }
                    LOG.debug("Connection manager shutting down.");
                }
            }, "connection-manager");
            connectionManager.start();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public boolean isRunning() {
        return connectionManager != null && connectionManager.isAlive();
    }

    @Override
    public void stop() {
        connectionManager.interrupt();
        try {
            serverSocket.close();
        } catch (IOException e) {
            LOG.debug(e.getMessage(), e);
        }
        pool.shutdown();
        synchronized (connections) {
            for (Connection c : connections) {
                c.disconnect();
            }
        }
    }

    private void startConnection(Connection c) {
        synchronized (connections) {
            // prevent connecting twice to the same node
            if (connections.contains(c)) {
                return;
            }
            connections.add(c);
        }
        pool.execute(c);
    }

    @Override
    public void offer(final InventoryVector iv) {
        List<Connection> target = new LinkedList<>();
        synchronized (connections) {
            for (Connection connection : connections) {
                if (connection.getState() == ACTIVE && !connection.knowsOf(iv)) {
                    target.add(connection);
                }
            }
        }
        LOG.debug(target.size() + " connections available to offer " + iv);
        List<Connection> randomSubset = Collections.selectRandom(NETWORK_MAGIC_NUMBER, target);
        for (Connection connection : randomSubset) {
            connection.offer(iv);
        }
    }

    @Override
    public Property getNetworkStatus() {
        TreeSet<Long> streams = new TreeSet<>();
        TreeMap<Long, Integer> incomingConnections = new TreeMap<>();
        TreeMap<Long, Integer> outgoingConnections = new TreeMap<>();

        synchronized (connections) {
            for (Connection connection : connections) {
                if (connection.getState() == ACTIVE) {
                    long stream = connection.getNode().getStream();
                    streams.add(stream);
                    if (connection.getMode() == SERVER) {
                        inc(incomingConnections, stream);
                    } else {
                        inc(outgoingConnections, stream);
                    }
                }
            }
        }
        Property[] streamProperties = new Property[streams.size()];
        int i = 0;
        for (Long stream : streams) {
            int incoming = incomingConnections.containsKey(stream) ? incomingConnections.get(stream) : 0;
            int outgoing = outgoingConnections.containsKey(stream) ? outgoingConnections.get(stream) : 0;
            streamProperties[i] = new Property("stream " + stream,
                    null, new Property("nodes", incoming + outgoing),
                    new Property("incoming", incoming),
                    new Property("outgoing", outgoing)
            );
            i++;
        }
        return new Property("network", null,
                new Property("connectionManager", connectionManager.isAlive() ? "running" : "stopped"),
                new Property("connections", null, streamProperties)
        );
    }
}
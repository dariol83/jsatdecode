package eu.dariolucia.jsatdecode.distribution.tcp.server;

import eu.dariolucia.jsatdecode.api.lifecycle.ComponentState;
import eu.dariolucia.jsatdecode.distribution.tcp.TcpSettings;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/** Owns the listener lifecycle shared by factory-created TCP adapters. */
public final class TcpServerRegistry {
    private static final Map<Integer, TcpServer> serversByPort = new HashMap<>();
    private static final Map<Integer, Integer> referenceCounts = new HashMap<>();

    private TcpServerRegistry() { }

    /** Acquires a construction reference, validating shared transport settings. */
    public static synchronized TcpServer getOrCreateServer(TcpSettings settings) {
        Objects.requireNonNull(settings, "settings");
        TcpServer server = serversByPort.get(settings.port());
        if (server == null) {
            server = new TcpServer(settings);
            serversByPort.put(settings.port(), server);
            referenceCounts.put(settings.port(), 1);
        } else {
            validateCompatible(server.getSettings(), settings);
            referenceCounts.merge(settings.port(), 1, Integer::sum);
        }
        return server;
    }

    private static void validateCompatible(TcpSettings existing, TcpSettings requested) {
        if (existing.port() != requested.port() ||
                existing.keepalive() != requested.keepalive() ||
                existing.maxClients() != requested.maxClients() ||
                existing.sendBufferBytes() != requested.sendBufferBytes() ||
                existing.receiveBufferBytes() != requested.receiveBufferBytes() ||
                existing.tcpNoDelay() != requested.tcpNoDelay() ||
                existing.maxMessageBytes() != requested.maxMessageBytes() ||
                existing.clientQueueBytes() != requested.clientQueueBytes() ||
                existing.sourceId() != requested.sourceId()) {
            throw new IllegalArgumentException("TCP adapters on port " + requested.port() +
                    " must agree on shared transport settings and source_id");
        }
    }

    /** Starts the acquired server once, regardless of adapter start order. */
    public static synchronized void startServer(TcpServer server) throws Exception {
        Objects.requireNonNull(server, "server");
        if (serversByPort.get(server.getPort()) != server) {
            throw new IllegalArgumentException("Server is not acquired from the registry");
        }
        if (server.state() == ComponentState.CONFIGURED) {
            server.start();
        } else if (server.state() != ComponentState.RUNNING) {
            throw new IllegalStateException("Shared server cannot start in state " + server.state());
        }
    }

    /** Releases a construction reference and closes the last shared listener. */
    public static synchronized void releaseServer(int port) {
        Integer count = referenceCounts.get(port);
        if (count == null) {
            return;
        }
        if (count > 1) {
            referenceCounts.put(port, count - 1);
        } else {
            TcpServer server = serversByPort.remove(port);
            referenceCounts.remove(port);
            server.close();
        }
    }

    public static synchronized TcpServer getServer(int port) { return serversByPort.get(port); }
    public static synchronized boolean hasServer(int port) { return serversByPort.containsKey(port); }

    /** Testing cleanup also closes listeners, avoiding orphan sockets. */
    public static synchronized void clearAll() {
        for (TcpServer server : serversByPort.values()) {
            server.close();
        }
        serversByPort.clear();
        referenceCounts.clear();
    }
}

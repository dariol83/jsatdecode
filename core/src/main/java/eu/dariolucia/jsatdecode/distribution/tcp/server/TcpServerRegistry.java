package eu.dariolucia.jsatdecode.distribution.tcp.server;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry for managing shared TCP server instances.
 *
 * <p>This registry allows multiple distributors (FrameDistributor and MonitoringDistributor)
 * to share a single TcpServer instance for the same port. This ensures that a single TCP/IP
 * connection can deliver frames, monitoring snapshots, and events as required by the JTDP v1
 * protocol specification.
 *
 * <p>When the first distributor for a given port is created, it registers its server with this
 * registry. Subsequent distributors for the same port will receive the already-created server
 * instead of creating a new one.
 *
 * <p>The registry uses a ConcurrentHashMap for thread-safe access from multiple distributors
 * that may be initialized concurrently.
 *
 * <h2>Thread Safety</h2>
 * <p>This class is thread-safe. All methods can be called from multiple threads concurrently.
 *
 * <h2>Lifecycle Management</h2>
 * <p>The first distributor to create a server for a port is responsible for starting it.
 * The last distributor to release the server is responsible for stopping it.
 * However, in the current implementation, the JSatDecode runtime manages distributor
 * lifecycle, and the server is stopped when the first distributor that created it is stopped.
 *
 * @see TcpServer
 */
public final class TcpServerRegistry {

    private static final Map<Integer, TcpServer> serversByPort = new ConcurrentHashMap<>();
    private static final Map<Integer, Integer> referenceCounts = new ConcurrentHashMap<>();

    /**
     * Private constructor to prevent instantiation.
     */
    private TcpServerRegistry() {
        // Utility class
    }

    /**
     * Gets or creates a TCP server for the specified port.
     *
     * <p>If a server for the given port already exists in the registry, it is returned.
     * Otherwise, a new server is created using the provided settings and registered.
     *
     * @param settings The TCP settings containing the port
     * @return The TCP server instance for the specified port
     */
    public static synchronized TcpServer getOrCreateServer(eu.dariolucia.jsatdecode.distribution.tcp.TcpSettings settings) {
        int port = settings.port();
        
        TcpServer server = serversByPort.get(port);
        if (server != null) {
            // Server already exists, increment reference count and return it
            referenceCounts.merge(port, 1, Integer::sum);
            return server;
        }

        // Create new server
        server = new TcpServer(settings);
        serversByPort.put(port, server);
        referenceCounts.put(port, 1);
        
        return server;
    }

    /**
     * Releases a reference to a TCP server.
     *
     * <p>When the last reference to a server is released, the server is removed from
     * the registry. Note: The server itself is not stopped by this method; it remains
     * running until explicitly stopped by the runtime.
     *
     * @param port The port of the server to release
     */
    public static synchronized void releaseServer(int port) {
        Integer count = referenceCounts.get(port);
        if (count != null) {
            if (count <= 1) {
                serversByPort.remove(port);
                referenceCounts.remove(port);
            } else {
                referenceCounts.put(port, count - 1);
            }
        }
    }

    /**
     * Gets a registered server for the specified port without creating one.
     *
     * @param port The port to look up
     * @return The registered server, or null if none exists
     */
    public static TcpServer getServer(int port) {
        return serversByPort.get(port);
    }

    /**
     * Checks if a server is registered for the specified port.
     *
     * @param port The port to check
     * @return true if a server is registered for this port
     */
    public static boolean hasServer(int port) {
        return serversByPort.containsKey(port);
    }

    /**
     * Clears all registered servers.
     *
     * <p>This method is intended for testing purposes only. In production, servers
     * should be properly released using {@link #releaseServer(int)}.
     */
    public static void clearAll() {
        serversByPort.clear();
        referenceCounts.clear();
    }
}

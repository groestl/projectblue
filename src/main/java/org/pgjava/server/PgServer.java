package org.pgjava.server;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import org.pgjava.engine.ClusterConfig;
import org.pgjava.jdbc.ClusterRegistry;
import org.pgjava.jdbc.PgJavaCluster;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * PostgreSQL wire-protocol TCP server for a single {@link PgJavaCluster}.
 *
 * <p>Each accepted connection gets its own {@link PgConnectionHandler} instance,
 * which opens a fresh {@link org.pgjava.engine.Session} after authentication.
 *
 * <p>Lifecycle: call {@link #start()} to bind the port, {@link #stop()} to shut down.
 * Both are synchronous. {@link PgJavaCluster} calls these automatically when
 * {@link ClusterConfig#hasServer()} is true.
 *
 * <h3>Standalone mode</h3>
 * {@code ./gradlew run} invokes {@link #main(String[])} which starts a default
 * in-memory cluster on port 5433. Connect with:
 * <pre>
 *   psql -h localhost -p 5433 -U postgres postgres
 * </pre>
 */
public final class PgServer {

    private static final Logger log = LoggerFactory.getLogger(PgServer.class);

    private static final int DEFAULT_PORT = 5433;

    private final int            port;
    private final PgJavaCluster  cluster;

    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private Channel        serverChannel;

    public PgServer(int port, PgJavaCluster cluster) {
        this.port    = port;
        this.cluster = cluster;
    }

    // -------------------------------------------------------------------------
    // Lifecycle

    /**
     * Bind the TCP port and start accepting connections. Synchronous — returns
     * only when the port is bound and the server is ready.
     *
     * @throws RuntimeException if the bind fails
     */
    public void start() {
        bossGroup   = new NioEventLoopGroup(1);
        workerGroup = new NioEventLoopGroup();
        try {
            ServerBootstrap b = new ServerBootstrap();
            b.group(bossGroup, workerGroup)
             .channel(NioServerSocketChannel.class)
             .childHandler(new ChannelInitializer<SocketChannel>() {
                 @Override
                 protected void initChannel(SocketChannel ch) {
                     ch.pipeline()
                       .addLast(new PgWireDecoder())
                       .addLast(new PgConnectionHandler(cluster));
                 }
             });
            serverChannel = b.bind(port).sync().channel();
            log.info("pgjava wire server listening on port {} (cluster={})", port, cluster.name());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            shutdown();
            throw new RuntimeException("server start interrupted", e);
        } catch (Exception e) {
            shutdown();
            throw new RuntimeException("failed to bind port " + port, e);
        }
    }

    /**
     * Stop the server. Closes all connections and releases thread pools.
     * Safe to call multiple times.
     */
    public void stop() {
        if (serverChannel != null) {
            serverChannel.close().awaitUninterruptibly();
            serverChannel = null;
        }
        shutdown();
        log.info("pgjava wire server stopped (port={})", port);
    }

    private void shutdown() {
        if (workerGroup != null) { workerGroup.shutdownGracefully(); workerGroup = null; }
        if (bossGroup   != null) { bossGroup.shutdownGracefully();   bossGroup   = null; }
    }

    // -------------------------------------------------------------------------
    // Standalone main()

    /**
     * Start pgjava as a standalone wire-protocol server on port 5433.
     *
     * <p>Creates a single in-memory cluster named "default". All databases
     * are created on first connection (auto-create semantics).
     *
     * <p>Connect with: {@code psql -h localhost -p 5433 -U postgres postgres}
     */
    public static void main(String[] args) throws Exception {
        int port = DEFAULT_PORT;
        for (int i = 0; i < args.length - 1; i++) {
            if ("--port".equals(args[i]) || "-p".equals(args[i])) {
                port = Integer.parseInt(args[i + 1]);
            }
        }

        PgJavaCluster cluster = PgJavaCluster.create(
                ClusterConfig.builder("default")
                        .port(port)
                        .build()
        ).start();

        // Await termination (until CTRL+C)
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("shutting down...");
            cluster.stop();
        }));

        log.info("pgjava started — connect with: psql -h localhost -p {} -U postgres postgres", port);
        // Block until the server channel closes
        ClusterRegistry.get("default");  // keep reference
        Thread.currentThread().join();
    }
}

package com.nextstep.api.support;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Starts a disposable PostgreSQL cluster for the test JVM using locally installed PostgreSQL binaries.
 * The cluster gets a generated password, listens only on 127.0.0.1 on a free port, and is stopped and deleted
 * (only its own temporary directory) when the JVM exits.
 *
 * Binaries are found through NEXTSTEP_PG_BIN, then PATH, then the newest C:\Program Files\PostgreSQL\N\bin.
 */
public final class TestPostgres {

    private static TestPostgres instance;

    private final String jdbcUrl;
    private final String user;
    private final String password;

    private TestPostgres(String jdbcUrl, String user, String password) {
        this.jdbcUrl = jdbcUrl;
        this.user = user;
        this.password = password;
    }

    public static synchronized TestPostgres get() {
        if (instance == null) {
            try {
                instance = start();
            } catch (Exception ex) {
                throw new IllegalStateException("Could not start the temporary PostgreSQL cluster: " + ex.getMessage(), ex);
            }
        }
        return instance;
    }

    public String jdbcUrl() { return jdbcUrl; }
    public String user() { return user; }
    public String password() { return password; }

    private static TestPostgres start() throws Exception {
        Path bin = findBin();
        Path root = Files.createTempDirectory("nextstep-pgtest-");
        Path data = root.resolve("data");
        Path passwordFile = root.resolve("pw.txt");
        byte[] secret = new byte[24];
        new SecureRandom().nextBytes(secret);
        String password = HexFormat.of().formatHex(secret);
        String user = "nextstep_test";
        Files.writeString(passwordFile, password, StandardCharsets.UTF_8);
        run(root, "initdb", List.of(exe(bin, "initdb"), "-D", data.toString(), "-U", user, "--auth=scram-sha-256",
                "--pwfile=" + passwordFile, "-E", "UTF8", "--locale=C", "--locale-provider=builtin",
                "--builtin-locale=C.UTF-8"), 120);
        Files.delete(passwordFile);

        int port;
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            port = socket.getLocalPort();
        }
        Files.writeString(data.resolve("postgresql.conf"), String.join("\n",
                "", "listen_addresses = '127.0.0.1'", "port = " + port, "fsync = off", "synchronous_commit = off",
                "full_page_writes = off", "max_connections = 60", ""), StandardOpenOption.APPEND);
        run(root, "pg_ctl-start", List.of(exe(bin, "pg_ctl"), "start", "-D", data.toString(),
                "-l", root.resolve("postgres.log").toString(), "-w", "-t", "60"), 90);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                run(root, "pg_ctl-stop", List.of(exe(bin, "pg_ctl"), "stop", "-D", data.toString(), "-m", "immediate", "-w"), 60);
            } catch (Exception ignored) {
                // best effort
            }
            deleteTree(root);
        }));

        String adminUrl = "jdbc:postgresql://127.0.0.1:" + port + "/postgres";
        try (Connection connection = DriverManager.getConnection(adminUrl, user, password);
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE nextstep");
        }
        return new TestPostgres("jdbc:postgresql://127.0.0.1:" + port + "/nextstep", user, password);
    }

    private static void run(Path root, String name, List<String> command, int timeoutSeconds) throws Exception {
        Path out = root.resolve(name + ".out");
        Process process = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .redirectOutput(out.toFile())
                .start();
        if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IllegalStateException(name + " timed out");
        }
        if (process.exitValue() != 0) {
            throw new IllegalStateException(name + " failed: " + Files.readString(out));
        }
    }

    private static String exe(Path bin, String tool) {
        boolean windows = System.getProperty("os.name").toLowerCase().contains("win");
        return bin.resolve(windows ? tool + ".exe" : tool).toString();
    }

    private static Path findBin() throws IOException {
        String configured = System.getenv("NEXTSTEP_PG_BIN");
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured);
        }
        String path = System.getenv("PATH");
        if (path != null) {
            for (String entry : path.split(java.io.File.pathSeparator)) {
                if (entry.isBlank()) {
                    continue;
                }
                Path dir = Path.of(entry.strip());
                if (Files.exists(dir.resolve("initdb")) || Files.exists(dir.resolve("initdb.exe"))) {
                    return dir;
                }
            }
        }
        List<Path> candidates = new ArrayList<>();
        for (String base : List.of("C:\\Program Files\\PostgreSQL", "/usr/lib/postgresql")) {
            Path dir = Path.of(base);
            if (Files.isDirectory(dir)) {
                try (Stream<Path> versions = Files.list(dir)) {
                    versions.map(v -> v.resolve("bin")).filter(Files::isDirectory).forEach(candidates::add);
                }
            }
        }
        return candidates.stream()
                .max(Comparator.comparingInt(TestPostgres::majorVersion))
                .orElseThrow(() -> new IllegalStateException(
                        "PostgreSQL binaries not found. Set NEXTSTEP_PG_BIN to the folder containing initdb and pg_ctl."));
    }

    private static int majorVersion(Path bin) {
        try {
            return Integer.parseInt(bin.getParent().getFileName().toString().split("\\.")[0]);
        } catch (RuntimeException ex) {
            return 0;
        }
    }

    private static void deleteTree(Path root) {
        if (root == null || !root.getFileName().toString().startsWith("nextstep-pgtest-")) {
            return;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // best effort
                }
            });
        } catch (IOException ignored) {
            // best effort
        }
    }
}


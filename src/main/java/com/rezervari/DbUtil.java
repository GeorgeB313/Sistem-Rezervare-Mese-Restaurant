package com.rezervari;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.*;

public class DbUtil {
    public static String resolveDbUrl() {
        String envUrl = System.getenv("RESERVARI_DB_URL");
        if (envUrl != null && !envUrl.isBlank()) {
            return envUrl.trim();
        }
        String envPath = System.getenv("RESERVARI_DB_PATH");
        if (envPath != null && !envPath.isBlank()) {
            Path p = Paths.get(envPath.trim());
            if (Files.isDirectory(p)) {
                p = p.resolve("restaurant.db");
            }
            return "jdbc:sqlite:" + p.toAbsolutePath();
        }

        Path userDir = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        Path userHomeDb = Paths.get(System.getProperty("user.home"), "RezervariRestaurant", "restaurant.db");

        Path jarDir = null;
        try {
            URI uri = DbUtil.class.getProtectionDomain().getCodeSource().getLocation().toURI();
            Path jarPath = Paths.get(uri).toAbsolutePath();
            jarDir = Files.isDirectory(jarPath) ? jarPath : jarPath.getParent();
        } catch (Exception ignored) {
            // fallback to user.dir
        }

        Path[] candidates = new Path[] {
            userDir.resolve("data").resolve("restaurant.db"),
            userDir.resolve("restaurant.db"),
            userDir.getParent() != null ? userDir.getParent().resolve("data").resolve("restaurant.db") : null,
            userDir.getParent() != null ? userDir.getParent().resolve("restaurant.db") : null,
            jarDir != null ? jarDir.resolve("data").resolve("restaurant.db") : null,
            jarDir != null ? jarDir.resolve("restaurant.db") : null,
            jarDir != null && jarDir.getParent() != null ? jarDir.getParent().resolve("data").resolve("restaurant.db") : null,
            jarDir != null && jarDir.getParent() != null ? jarDir.getParent().resolve("restaurant.db") : null,
            userHomeDb
        };

        for (Path p : candidates) {
            if (p == null) continue;
            try {
                if (Files.exists(p) && Files.isRegularFile(p)) {
                    return "jdbc:sqlite:" + p.toAbsolutePath();
                }
            } catch (Exception ignored) {
                // try next candidate
            }
        }

        try {
            Files.createDirectories(userHomeDb.getParent());
        } catch (Exception ignored) {
            // ignore
        }
        return "jdbc:sqlite:" + userHomeDb.toAbsolutePath();
    }

    public static void ensureSchemaAndSeed(Connection conexiune) throws SQLException {
        try (Statement stmt = conexiune.createStatement()) {
            stmt.execute("CREATE TABLE IF NOT EXISTS mese (id INTEGER PRIMARY KEY, nume TEXT, capacitate INTEGER, zona TEXT, pozitie_x INTEGER, pozitie_y INTEGER, langa_fereastra INTEGER, miscarea_blocata INTEGER)");
            stmt.execute("CREATE TABLE IF NOT EXISTS rezervari (id INTEGER PRIMARY KEY AUTOINCREMENT, masa_id INTEGER, nume_client TEXT, nr_persoane INTEGER, data_ora TEXT, preferinta_fereastra INTEGER, status TEXT, FOREIGN KEY(masa_id) REFERENCES mese(id))");
        }

        // upgrade schema pentru baze existente
        try (Statement stmt = conexiune.createStatement()) {
            stmt.execute("ALTER TABLE mese ADD COLUMN miscarea_blocata INTEGER DEFAULT 0");
        } catch (SQLException ignored) {
            // coloana exista deja
        }

        // Seed mese doar dacă tabela este goală
        try (Statement stmt = conexiune.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM mese")) {
            if (rs.next() && rs.getInt(1) == 0) {
                stmt.executeUpdate("INSERT INTO mese (id, nume, capacitate, zona, pozitie_x, pozitie_y, langa_fereastra, miscarea_blocata) VALUES" +
                    " (1, 'M1', 2, 'fereastra', 1, 1, 1, 0)," +
                    " (2, 'M2', 2, 'fereastra', 2, 1, 1, 0)," +
                    " (3, 'M3', 4, 'fereastra', 3, 1, 1, 0)," +
                    " (4, 'M4', 4, 'fereastra', 4, 1, 1, 0)," +
                    " (5, 'M5', 6, 'fereastra', 5, 1, 1, 0)," +
                    " (6, 'M6', 2, 'central', 1, 2, 0, 0)," +
                    " (7, 'M7', 2, 'central', 2, 2, 0, 0)," +
                    " (8, 'M8', 4, 'central', 3, 2, 0, 0)," +
                    " (9, 'M9', 4, 'central', 4, 2, 0, 0)," +
                    " (10, 'M10', 6, 'central', 5, 2, 0, 0)," +
                    " (11, 'M11', 2, 'central', 1, 3, 0, 0)," +
                    " (12, 'M12', 2, 'central', 2, 3, 0, 0)," +
                    " (13, 'M13', 4, 'central', 3, 3, 0, 0)," +
                    " (14, 'M14', 4, 'central', 4, 3, 0, 0)," +
                    " (15, 'M15', 6, 'central', 5, 3, 0, 0)," +
                    " (16, 'M16', 2, 'intrare', 1, 4, 0, 0)," +
                    " (17, 'M17', 2, 'intrare', 2, 4, 0, 0)," +
                    " (18, 'M18', 4, 'intrare', 3, 4, 0, 0)," +
                    " (19, 'M19', 4, 'intrare', 4, 4, 0, 0)," +
                    " (20, 'M20', 6, 'intrare', 5, 4, 0, 0)");
            }
        }
    }
}

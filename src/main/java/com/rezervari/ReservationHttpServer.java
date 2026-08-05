package com.rezervari;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;

public class ReservationHttpServer {
    private final int port;
    private final String dbUrl;
    private HttpServer server;

    public ReservationHttpServer(String dbUrl, int port) {
        this.port = port;
        this.dbUrl = dbUrl;
    }

    public void startAsync() throws IOException {
        server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/rezervari", new RezervareHandler(dbUrl));
        server.createContext("/mese", new RezervareHandler(dbUrl));
        server.createContext("/", new RezervareHandler(dbUrl));
        server.setExecutor(null); // executor implicit
        server.start();
        System.out.println("[HTTP] Server pornit pe portul " + port + " pentru rezervari.");
    }

    static class RezervareHandler implements HttpHandler {
        private final String dbUrl;

        RezervareHandler(String dbUrl) {
            this.dbUrl = dbUrl;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getPath();
            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                send(exchange, 204, "", "text/plain; charset=utf-8");
                return;
            }
            if (path != null && path.toLowerCase().startsWith("/mese")) {
                if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                    send(exchange, 405, "Doar GET");
                    return;
                }
                Map<String, String> q = readQueryParams(exchange.getRequestURI().getQuery());
                String dtParam = q.getOrDefault("datetime", "").trim();
                String dataZi = q.getOrDefault("date", java.time.LocalDate.now().toString());
                LocalDateTime refTime = parseDataOra(dtParam);
                if (refTime == null) {
                    refTime = parseDataOra(dataZi + " 12:00");
                }
                try (Connection conn = DriverManager.getConnection(dbUrl)) {
                    DbUtil.ensureSchemaAndSeed(conn);
                    String json = buildMeseJson(conn, refTime != null ? refTime : LocalDateTime.now());
                    send(exchange, 200, json, "application/json; charset=utf-8");
                } catch (SQLException e) {
                    send(exchange, 500, "Eroare DB: " + e.getMessage());
                }
                return;
            }
            if ("GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                // Serveste formularul local daca exista, altfel un mesaj text
                try {
                    java.nio.file.Path filePath = java.nio.file.Paths.get("rezervare.html").toAbsolutePath();
                    if (!java.nio.file.Files.exists(filePath)) {
                        filePath = java.nio.file.Paths.get("dist-app", "RezervariRestaurant", "rezervare.html").toAbsolutePath();
                    }
                    if (java.nio.file.Files.exists(filePath)) {
                        byte[] bytes = java.nio.file.Files.readAllBytes(filePath);
                        send(exchange, 200, bytes, "text/html; charset=utf-8");
                    } else {
                        send(exchange, 200, "Endpoint rezervari functional. Trimite POST cu nume_client, nr_persoane, data_ora, preferinta_fereastra=1 optional.", "text/plain; charset=utf-8");
                    }
                } catch (IOException io) {
                    send(exchange, 500, "Eroare la servirea formularului: " + io.getMessage(), "text/plain; charset=utf-8");
                }
                return;
            }
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                send(exchange, 405, "Doar POST");
                return;
            }

            Map<String, String> params = readFormParams(exchange.getRequestBody());
            String nume = params.getOrDefault("nume_client", "").trim();
            String nrPersStr = params.get("nr_persoane");
            String dataOraInput = params.getOrDefault("data_ora", "");
            String dataOra = normalizeDataOra(dataOraInput);
            LocalDateTime requestTime = parseDataOra(dataOra);
            boolean preferinta = "1".equals(params.getOrDefault("preferinta_fereastra", "0"));
            String masaIdStr = params.getOrDefault("masa_id", "").trim();
            String dataZi = extrageDataZi(dataOra);

            if (nume.isEmpty() || nrPersStr == null || nrPersStr.isEmpty()) {
                send(exchange, 400, "Campuri obligatorii lipsa");
                return;
            }

            int nrPers;
            try {
                nrPers = Integer.parseInt(nrPersStr);
            } catch (NumberFormatException ex) {
                send(exchange, 400, "Numar persoane invalid");
                return;
            }

            try (Connection conn = DriverManager.getConnection(dbUrl)) {
                DbUtil.ensureSchemaAndSeed(conn);

                if (!masaIdStr.isEmpty()) {
                    try {
                        int masaId = Integer.parseInt(masaIdStr);
                        if (requestTime != null && esteDisponibilaLaOra(conn, masaId, requestTime)) {
                            try (PreparedStatement ps = conn.prepareStatement(
                                    "INSERT INTO rezervari (masa_id, nume_client, nr_persoane, data_ora, preferinta_fereastra, status) VALUES (?, ?, ?, ?, ?, 'confirmata')")) {
                                ps.setInt(1, masaId);
                                ps.setString(2, nume);
                                ps.setInt(3, nrPers);
                                ps.setString(4, dataOra);
                                ps.setInt(5, preferinta ? 1 : 0);
                                ps.executeUpdate();
                            }
                            send(exchange, 200, "Rezervare confirmata la masa " + masaId);
                            return;
                        } else {
                            send(exchange, 409, "Masa selectata nu este disponibila pentru data aleasa.");
                            return;
                        }
                    } catch (NumberFormatException ignored) {
                        // fallback to auto-assign
                    }
                }

                Integer masaId = alegeMasa(conn, nrPers, preferinta, requestTime);
                if (masaId != null) {
                    try (PreparedStatement ps = conn.prepareStatement(
                            "INSERT INTO rezervari (masa_id, nume_client, nr_persoane, data_ora, preferinta_fereastra, status) VALUES (?, ?, ?, ?, ?, 'confirmata')")) {
                        ps.setInt(1, masaId);
                        ps.setString(2, nume);
                        ps.setInt(3, nrPers);
                        ps.setString(4, dataOra);
                        ps.setInt(5, preferinta ? 1 : 0);
                        ps.executeUpdate();
                    }

                    boolean respectaFereastra = preferinta && esteLangaFereastra(conn, masaId);
                    if (preferinta && !respectaFereastra) {
                        send(exchange, 200, "Nu era disponibila masa la fereastra; rezervare confirmata la masa " + masaId);
                    } else {
                        send(exchange, 200, "Rezervare confirmata la masa " + masaId);
                    }
                    return;
                }

                int[] pair = alegePerecheMese(conn, nrPers, preferinta, requestTime);
                if (pair == null) {
                    String debug = infoMeseLibere(conn, preferinta, requestTime);
                    send(exchange, 409, "Nu exista masa disponibila pentru " + nrPers + (preferinta ? " la fereastra" : "") + ". " + debug);
                    return;
                }

                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO rezervari (masa_id, nume_client, nr_persoane, data_ora, preferinta_fereastra, status) VALUES (?, ?, ?, ?, ?, 'confirmata')")) {
                    ps.setInt(1, pair[0]);
                    ps.setString(2, nume);
                    ps.setInt(3, nrPers);
                    ps.setString(4, dataOra);
                    ps.setInt(5, preferinta ? 1 : 0);
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO rezervari (masa_id, nume_client, nr_persoane, data_ora, preferinta_fereastra, status) VALUES (?, ?, ?, ?, ?, 'confirmata')")) {
                    ps.setInt(1, pair[1]);
                    ps.setString(2, nume);
                    ps.setInt(3, nrPers);
                    ps.setString(4, dataOra);
                    ps.setInt(5, preferinta ? 1 : 0);
                    ps.executeUpdate();
                }

                send(exchange, 200, "Rezervare confirmata pe doua mese unite: " + pair[0] + " + " + pair[1]);
            } catch (SQLException e) {
                e.printStackTrace();
                send(exchange, 500, "Eroare DB: " + e.getMessage());
            }
        }

        private Integer alegeMasa(Connection conn, int nrPers, boolean vreaFereastra, LocalDateTime requestTime) throws SQLException {
            Integer masa = cauta(conn, nrPers, vreaFereastra, requestTime);
            if (masa == null && vreaFereastra) {
                masa = cauta(conn, nrPers, false, requestTime);
            }
            return masa;
        }

        private Integer cauta(Connection conn, int nrPers, boolean vreaFereastra, LocalDateTime requestTime) throws SQLException {
            if (requestTime == null) {
                return null;
            }
            String sql = "SELECT id, capacitate, langa_fereastra FROM mese WHERE capacitate >= ? " +
                (vreaFereastra ? "AND langa_fereastra = 1 " : "") +
                "ORDER BY langa_fereastra DESC, capacitate ASC, id ASC";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setInt(1, nrPers);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        int id = rs.getInt("id");
                        if (esteDisponibilaLaOra(conn, id, requestTime)) {
                            return id;
                        }
                    }
                }
            }
            return null;
        }

        private int[] alegePerecheMese(Connection conn, int nrPers, boolean vreaFereastra, LocalDateTime requestTime) throws SQLException {
            int[] best = alegePerecheMese(conn, nrPers, vreaFereastra, requestTime, true);
            if (best == null) {
                best = alegePerecheMese(conn, nrPers, vreaFereastra, requestTime, false);
            }
            if (best == null && vreaFereastra) {
                return alegePerecheMese(conn, nrPers, false, requestTime);
            }
            return best;
        }

        private int[] alegePerecheMese(Connection conn, int nrPers, boolean vreaFereastra, LocalDateTime requestTime, boolean consecutiveOnly) throws SQLException {
            if (requestTime == null) {
                return null;
            }
            java.util.List<int[]> meseLibere = new java.util.ArrayList<>();
            String sql2 = "SELECT id, nume, capacitate, langa_fereastra, pozitie_x, pozitie_y FROM mese" +
                (vreaFereastra ? " WHERE langa_fereastra = 1" : "");
            try (PreparedStatement ps = conn.prepareStatement(sql2)) {
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        int id = rs.getInt("id");
                        if (!esteDisponibilaLaOra(conn, id, requestTime)) {
                            continue;
                        }
                        meseLibere.add(new int[]{
                                id,
                                rs.getInt("capacitate"),
                                rs.getInt("pozitie_x"),
                                rs.getInt("pozitie_y"),
                                parseNumarDinNume(rs.getString("nume"), id)
                        });
                    }
                }
            }

            int bestCap = Integer.MAX_VALUE;
            int[] best = null;
            for (int i = 0; i < meseLibere.size(); i++) {
                int[] a = meseLibere.get(i);
                for (int j = i + 1; j < meseLibere.size(); j++) {
                    int[] b = meseLibere.get(j);
                    if (consecutiveOnly && !suntMeseConsecutive(a[4], b[4])) continue;
                    int total = a[1] + b[1];
                    if (total < nrPers) continue;
                    if (total < bestCap) {
                        bestCap = total;
                        best = new int[]{a[0], b[0]};
                    } else if (total == bestCap && best != null) {
                        int bestMin = Math.min(best[0], best[1]);
                        int bestMax = Math.max(best[0], best[1]);
                        int curMin = Math.min(a[0], b[0]);
                        int curMax = Math.max(a[0], b[0]);
                        if (curMin < bestMin || (curMin == bestMin && curMax < bestMax)) {
                            best = new int[]{a[0], b[0]};
                        }
                    }
                }
            }

            return best;
        }

        private String infoMeseLibere(Connection conn, boolean vreaFereastra, LocalDateTime requestTime) {
            StringBuilder sb = new StringBuilder("Mese libere azi: ");
            String sql = "SELECT id, nume, capacitate FROM mese" +
                (vreaFereastra ? " WHERE langa_fereastra = 1" : "");
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                try (ResultSet rs = ps.executeQuery()) {
                    boolean first = true;
                    while (rs.next()) {
                        int id = rs.getInt("id");
                        if (requestTime != null && !esteDisponibilaLaOra(conn, id, requestTime)) {
                            continue;
                        }
                        if (!first) sb.append(", ");
                        first = false;
                        String nume = rs.getString("nume");
                        sb.append(nume != null ? nume : ("Masa " + id))
                          .append("(")
                          .append(rs.getInt("capacitate"))
                          .append(")");
                    }
                    if (first) sb.append("(niciuna)");
                }
            } catch (SQLException e) {
                return "";
            }
            return sb.toString();
        }

        private boolean suntMeseConsecutive(int n1, int n2) {
            return Math.abs(n1 - n2) == 1;
        }

        private int parseNumarDinNume(String nume, int fallback) {
            if (nume == null) return fallback;
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+)").matcher(nume);
            if (m.find()) {
                try {
                    return Integer.parseInt(m.group(1));
                } catch (NumberFormatException ignored) {
                    return fallback;
                }
            }
            return fallback;
        }

        private String extrageDataZi(String dataOra) {
            if (dataOra == null || dataOra.length() < 10) {
                return java.time.LocalDate.now().toString();
            }
            return dataOra.substring(0, 10);
        }

        private String normalizeDataOra(String input) {
            if (input == null || input.isBlank()) {
                return LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
            }
            String trimmed = input.trim();
            DateTimeFormatter[] formats = new DateTimeFormatter[] {
                DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm"),
                DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"),
                DateTimeFormatter.ISO_LOCAL_DATE_TIME
            };
            for (DateTimeFormatter f : formats) {
                try {
                    LocalDateTime dt = LocalDateTime.parse(trimmed, f);
                    return dt.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
                } catch (Exception ignored) {
                    // try next
                }
            }
            return trimmed;
        }

        private LocalDateTime parseDataOra(String input) {
            if (input == null || input.isBlank()) {
                return null;
            }
            String trimmed = input.trim();
            DateTimeFormatter[] formats = new DateTimeFormatter[] {
                DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm"),
                DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"),
                DateTimeFormatter.ISO_LOCAL_DATE_TIME
            };
            for (DateTimeFormatter f : formats) {
                try {
                    return LocalDateTime.parse(trimmed, f);
                } catch (Exception ignored) {
                    // try next
                }
            }
            return null;
        }

        private boolean esteLangaFereastra(Connection conn, int masaId) throws SQLException {
            try (PreparedStatement ps = conn.prepareStatement("SELECT langa_fereastra FROM mese WHERE id = ?")) {
                ps.setInt(1, masaId);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() && rs.getInt(1) == 1;
                }
            }
        }

        private Map<String, String> readFormParams(InputStream is) throws IOException {
            byte[] data = is.readAllBytes();
            String body = new String(data, StandardCharsets.UTF_8);
            Map<String, String> map = new HashMap<>();
            if (body.isEmpty()) return map;
            for (String pair : body.split("&")) {
                String[] kv = pair.split("=", 2);
                String k = urlDecode(kv[0]);
                String v = kv.length > 1 ? urlDecode(kv[1]) : "";
                map.put(k, v);
            }
            return map;
        }

        private Map<String, String> readQueryParams(String query) throws IOException {
            Map<String, String> map = new HashMap<>();
            if (query == null || query.isBlank()) return map;
            for (String pair : query.split("&")) {
                String[] kv = pair.split("=", 2);
                String k = urlDecode(kv[0]);
                String v = kv.length > 1 ? urlDecode(kv[1]) : "";
                map.put(k, v);
            }
            return map;
        }

        private boolean esteDisponibilaLaOra(Connection conn, int masaId, LocalDateTime requestTime) throws SQLException {
            if (requestTime == null) {
                return false;
            }
            LocalDate day = requestTime.toLocalDate();
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT data_ora FROM rezervari WHERE masa_id = ? AND status = 'confirmata' AND substr(data_ora,1,10) = ?")) {
                ps.setInt(1, masaId);
                ps.setString(2, day.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String raw = rs.getString("data_ora");
                        LocalDateTime existing = parseDataOra(raw);
                        if (existing == null) {
                            return false;
                        }
                        long diff = Math.abs(ChronoUnit.MINUTES.between(existing, requestTime));
                        if (diff < 120) {
                            return false;
                        }
                    }
                }
            }
            return true;
        }

        private String buildMeseJson(Connection conn, LocalDateTime requestTime) throws SQLException {
            StringBuilder sb = new StringBuilder();
            sb.append("[");
            String sql = "SELECT id, nume, capacitate, zona, pozitie_x, pozitie_y, langa_fereastra FROM mese ORDER BY id";
            try (PreparedStatement ps = conn.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
                boolean first = true;
                while (rs.next()) {
                    int id = rs.getInt("id");
                    boolean ocupata = !esteDisponibilaLaOra(conn, id, requestTime);
                    if (!first) sb.append(",");
                    first = false;
                                        String nume = rs.getString("nume");
                                        if (nume == null || nume.isBlank()) {
                                                nume = "Masa " + id;
                                        }
                                        sb.append("{")
                                            .append("\"id\":").append(id).append(",")
                                            .append("\"nume\":\"").append(escapeJson(nume)).append("\",")
                      .append("\"capacitate\":").append(rs.getInt("capacitate")).append(",")
                      .append("\"pozitie_x\":").append(rs.getInt("pozitie_x")).append(",")
                      .append("\"pozitie_y\":").append(rs.getInt("pozitie_y")).append(",")
                      .append("\"langa_fereastra\":").append(rs.getInt("langa_fereastra")).append(",")
                      .append("\"ocupata\":").append(ocupata)
                      .append("}");
                }
            }
            sb.append("]");
            return sb.toString();
        }

        private String escapeJson(String s) {
            if (s == null) return "";
            return s.replace("\\", "\\\\").replace("\"", "\\\"");
        }

        private String urlDecode(String s) {
            return URLDecoder.decode(s, StandardCharsets.UTF_8);
        }

        private void send(HttpExchange ex, int status, String body) throws IOException {
            send(ex, status, body, "text/plain; charset=utf-8");
        }

        private void send(HttpExchange ex, int status, String body, String contentType) throws IOException {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            send(ex, status, bytes, contentType);
        }

        private void send(HttpExchange ex, int status, byte[] bytes, String contentType) throws IOException {
            ex.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
            ex.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
            ex.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type");
            ex.getResponseHeaders().set("Content-Type", contentType);
            ex.sendResponseHeaders(status, bytes.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(bytes);
            }
        }
    }
}

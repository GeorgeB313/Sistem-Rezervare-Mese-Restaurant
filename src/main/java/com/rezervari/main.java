package com.rezervari;

import java.awt.GraphicsEnvironment;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;

public class main {
    public static void main(String[] args) {
        Path logFile = initLogFile();
        log(logFile, "[INFO] Pornire aplicatie.");
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            log(logFile, "[ERROR] Uncaught in " + t.getName() + ": " + stackTrace(e));
        });
        int httpPort = Integer.parseInt(System.getenv().getOrDefault("RESERVARI_HTTP_PORT", "8082"));

        boolean dockerStarted = startDockerComposeIfAvailable(logFile);

        // init DB schema/seed and start HTTP endpoint
        if (!dockerStarted && isPortFree(httpPort)) {
            try {
                new ReservationHttpServer(DbUtil.resolveDbUrl(), httpPort).startAsync();
            } catch (Exception e) {
                log(logFile, "[WARN] Nu am putut porni serverul HTTP: " + e.getMessage());
            }
        } else if (dockerStarted) {
            log(logFile, "[INFO] Docker Compose pornit. Serverul HTTP local nu a fost pornit.");
        } else {
            log(logFile, "[WARN] Portul " + httpPort + " este ocupat. Serverul HTTP local nu a fost pornit.");
        }

        // Evită HeadlessException în containere fără DISPLAY
        if (GraphicsEnvironment.isHeadless()) {
            log(logFile, "[INFO] Mediu headless detectat. Interfata grafica (JavaFX) nu poate fi afisata in container.");
            log(logFile, "[INFO] Ruleaza aplicatia pe host pentru GUI sau configureaza X11 forwarding.");
            // Tine procesul in viata in modul headless ca sa nu se inchida instant containerul
            try {
                while (true) {
                    Thread.sleep(3_600_000); // 1h; suficient pentru debugging/DB
                }
            } catch (InterruptedException ignored) {
                // daca se opreste, iesim linistit
            }
            return;
        }

        try {
            RestaurantGUI.launchApp(20); // 20 mese în exemplu
        } catch (Throwable e) {
            log(logFile, "[ERROR] Pornire GUI esuata: " + stackTrace(e));
        }
    }

    private static Path initLogFile() {
        try {
            Path dir = Paths.get(System.getProperty("user.home"), "RezervariRestaurant");
            Files.createDirectories(dir);
            return dir.resolve("app.log");
        } catch (Exception e) {
            return Paths.get("app.log");
        }
    }

    private static void log(Path logFile, String msg) {
        try {
            String line = LocalDateTime.now() + " " + msg + System.lineSeparator();
            Files.writeString(logFile, line, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception ignored) {
            // no-op
        }
    }

    private static String stackTrace(Throwable t) {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        t.printStackTrace(pw);
        return sw.toString();
    }

    private static boolean startDockerComposeIfAvailable(Path logFile) {
        String enabled = System.getenv().getOrDefault("RESERVARI_DOCKER_AUTO", "1");
        if (!"1".equals(enabled)) {
            log(logFile, "[INFO] Pornire Docker Compose dezactivata.");
            return false;
        }
        Path workDir = locateComposeDir(logFile);
        if (workDir == null) {
            log(logFile, "[INFO] docker-compose.yml nu exista in directorul aplicatiei.");
            return false;
        }
        new Thread(() -> {
            try {
                if (!checkDockerAvailable(logFile)) {
                    return;
                }
                ProcessBuilder pb = new ProcessBuilder("docker", "compose", "up", "-d");
                pb.directory(workDir.toFile());
                pb.redirectErrorStream(true);
                Process p = pb.start();
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        log(logFile, "[DOCKER] " + line);
                    }
                }
                boolean done = p.waitFor(30, TimeUnit.SECONDS);
                if (!done) {
                    log(logFile, "[WARN] Docker Compose timeout.");
                } else {
                    log(logFile, "[INFO] Docker Compose exit code: " + p.exitValue());
                }
            } catch (Exception e) {
                log(logFile, "[WARN] Nu am putut porni Docker Compose: " + e.getMessage());
            }
        }, "docker-compose").start();
        return true;
    }

    private static boolean checkDockerAvailable(Path logFile) {
        try {
            ProcessBuilder pb = new ProcessBuilder("docker", "--version");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            boolean done = p.waitFor(5, TimeUnit.SECONDS);
            if (!done) {
                log(logFile, "[WARN] Docker nu raspunde.");
                return false;
            }
            if (p.exitValue() != 0) {
                log(logFile, "[WARN] Docker nu este disponibil.");
                return false;
            }
            return true;
        } catch (Exception e) {
            log(logFile, "[WARN] Docker nu este disponibil: " + e.getMessage());
            return false;
        }
    }

    private static Path locateComposeDir(Path logFile) {
        Path cwd = Paths.get("").toAbsolutePath();
        Path compose = cwd.resolve("docker-compose.yml");
        if (Files.exists(compose)) return cwd;

        Path appDir = cwd.resolve("app");
        if (Files.exists(appDir.resolve("docker-compose.yml"))) return appDir;

        Path parent = cwd.getParent();
        if (parent != null && Files.exists(parent.resolve("docker-compose.yml"))) return parent;

        return null;
    }

    private static boolean isPortFree(int port) {
        try (java.net.ServerSocket socket = new java.net.ServerSocket()) {
            socket.setReuseAddress(true);
            socket.bind(new java.net.InetSocketAddress("127.0.0.1", port));
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}

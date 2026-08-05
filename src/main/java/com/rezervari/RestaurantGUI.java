package com.rezervari;

import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.Bounds;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.effect.DropShadow;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Ellipse;
import javafx.scene.shape.Rectangle;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.stage.Stage;
import javafx.util.Duration;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

import java.sql.*;
import java.time.LocalDateTime;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class RestaurantGUI extends Application {
    private static int numarMeseImplicit = 20;
    private static final List<String> DEFAULT_TIME_SLOTS = initTimeSlots();

    private List<masa> mese;
    private List<TableTile> tileMese;
    private Button butonRezervare;
    private ProgressBar progresOcupare;
    private Label progresText;
    private Connection conexiune;
    private Timeline autoRefreshTimer;
    private final Set<TableTile> selectedTiles = new HashSet<>();
    private Rectangle selectionRect;
    private Pane panouMese;
    private ContextMenu workspaceMenu;
    private double lastWorkspaceX;
    private double lastWorkspaceY;
    private final Path logFile = initLogFile();

    public static void launchApp(int numarMese) {
        numarMeseImplicit = numarMese;
        Application.launch(RestaurantGUI.class);
    }

    @Override
    public void start(Stage stage) {
        stage.setTitle("Sistem Rezervari Restaurant Vizual");

        initializeazaBazaDate();

        mese = new ArrayList<>();
        incarcaMeseDinDB(numarMeseImplicit);
        incarcaRezervariDinDB();

        panouMese = new Pane();
        panouMese.setPrefHeight(700);
        panouMese.setStyle("-fx-background-color: linear-gradient(to bottom, #0B1020, #0F172A);");
        tileMese = new ArrayList<>();

        selectionRect = new Rectangle();
        selectionRect.setVisible(false);
        selectionRect.setFill(Color.web("#90CAF9", 0.3));
        selectionRect.setStroke(Color.web("#42A5F5"));
        selectionRect.getStrokeDashArray().addAll(6.0, 4.0);
        panouMese.getChildren().add(selectionRect);

        workspaceMenu = new ContextMenu();
        MenuItem addTable = new MenuItem("Adauga masa");
        workspaceMenu.getItems().add(addTable);
        addTable.setOnAction(e -> createNewTableAt(lastWorkspaceX, lastWorkspaceY));

        panouMese.setOnMousePressed(e -> {
            if (e.isPrimaryButtonDown() && (e.getTarget() == panouMese || e.getTarget() == selectionRect)) {
                clearSelection();
                selectionRect.toFront();
                selectionRect.setVisible(true);
                selectionRect.setX(e.getX());
                selectionRect.setY(e.getY());
                selectionRect.setWidth(0);
                selectionRect.setHeight(0);
            }
            if (e.isSecondaryButtonDown() && (e.getTarget() == panouMese || e.getTarget() == selectionRect)) {
                lastWorkspaceX = e.getX();
                lastWorkspaceY = e.getY();
                workspaceMenu.show(panouMese, e.getScreenX(), e.getScreenY());
                e.consume();
            }
        });

        panouMese.setOnMouseDragged(e -> {
            if (!selectionRect.isVisible()) {
                return;
            }
            double x = Math.min(e.getX(), selectionRect.getX());
            double y = Math.min(e.getY(), selectionRect.getY());
            double w = Math.abs(e.getX() - selectionRect.getX());
            double h = Math.abs(e.getY() - selectionRect.getY());
            selectionRect.setX(x);
            selectionRect.setY(y);
            selectionRect.setWidth(w);
            selectionRect.setHeight(h);
            selectionRect.toFront();

            for (TableTile tile : tileMese) {
                boolean intersects = tile.getBoundsInParent().intersects(selectionRect.getBoundsInParent());
                setSelected(tile, intersects);
            }
        });

        panouMese.setOnMouseReleased(e -> {
            if (selectionRect.isVisible()) {
                selectionRect.setVisible(false);
            }
        });

        for (int i = 0; i < mese.size(); i++) {
            masa m = mese.get(i);
            TableTile tile = new TableTile(m);
            tileMese.add(tile);
            double baseX = 20 + (i % 5) * 180;
            double baseY = 20 + (i / 5) * 180;
            double x = m.getPozitieX() > 0 ? m.getPozitieX() : baseX;
            double y = m.getPozitieY() > 0 ? m.getPozitieY() : baseY;
            tile.setLayoutX(x);
            tile.setLayoutY(y);
            panouMese.getChildren().add(tile);
        }

        Button butonRefresh = styledActionButton("Refresh");
        Button butonAutoCuratare = styledActionButton("Auto-curatare");
        butonRezervare = styledActionButton("Rezervare");

        butonRefresh.setOnAction(e -> refreshDinDB());
        butonAutoCuratare.setOnAction(e -> curataRezervari());
        butonRezervare.setOnAction(e -> {
            try {
                openRezervareDialog(false);
            } catch (Exception ex) {
                log("[ERROR] Rezervare dialog crash: " + ex.getClass().getName() + ": " + ex.getMessage());
                showError("Eroare la deschiderea dialogului de rezervare: " + ex.getMessage());
            }
        });

        progresOcupare = new ProgressBar(0);
        progresOcupare.setPrefWidth(320);
        progresOcupare.setPrefHeight(18);
        progresOcupare.setStyle("-fx-accent: #22D3EE; -fx-control-inner-background: #1F2937; -fx-background-radius: 10;");
        progresText = new Label();
        actualizeazaProgres();

        Label title = new Label("Rezervari Restaurant");
        title.setFont(Font.font("Segoe UI", FontWeight.BOLD, 24));
        title.setTextFill(Color.web("#E5E7EB"));
        Label subtitle = new Label("Monitorizare in timp real si rezervari rapide");
        subtitle.setFont(Font.font("Segoe UI", FontWeight.NORMAL, 13));
        subtitle.setTextFill(Color.web("#9CA3AF"));

        VBox titleBox = new VBox(2, title, subtitle);
        titleBox.setAlignment(Pos.CENTER);
        HBox headerActions = new HBox(12, butonRezervare, butonRefresh, butonAutoCuratare);
        headerActions.setAlignment(Pos.CENTER);
        VBox header = new VBox(10, titleBox, headerActions);
        header.setAlignment(Pos.CENTER);

        ScrollPane scrollMese = new ScrollPane(panouMese);
        scrollMese.setFitToWidth(true);
        scrollMese.setFitToHeight(true);
        scrollMese.setHbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        scrollMese.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        scrollMese.setStyle("-fx-background: transparent; -fx-background-color: transparent; -fx-border-color: transparent;");

        scrollMese.viewportBoundsProperty().addListener((obs, oldB, newB) -> {
            if (newB != null) {
                panouMese.setMinHeight(newB.getHeight());
                panouMese.setMinWidth(newB.getWidth());
            }
        });

        VBox cardGrid = new VBox(scrollMese);
        cardGrid.setStyle(cardStyle());
        VBox.setVgrow(scrollMese, Priority.ALWAYS);

        HBox status = new HBox(12, new Label("Ocupare mese"), progresOcupare, progresText);
        status.setAlignment(Pos.CENTER);
        status.setPadding(new Insets(8, 4, 8, 4));
        status.getChildren().get(0).setStyle("-fx-text-fill: #E5E7EB; -fx-font-weight: bold;");
        progresText.setTextFill(Color.web("#A78BFA"));

        VBox topStack = new VBox(8, header);

        BorderPane root = new BorderPane();
        root.setTop(topStack);
        root.setCenter(cardGrid);
        root.setBottom(status);
        root.setPadding(new Insets(12));
        root.setStyle("-fx-background-color: #0B1020;");

        Scene scene = new Scene(root, 1200, 800);
        stage.setScene(scene);
        stage.setMaximized(true);

        stage.setOnCloseRequest(e -> {
            e.consume();
            inchideAplicatia();
        });

        pornesteAutoRefresh();
        stage.show();
    }

    private Button styledActionButton(String text) {
        Button b = new Button(text);
        b.setStyle("-fx-background-color: linear-gradient(to bottom right, #8B5CF6, #22D3EE); -fx-text-fill: white; -fx-font-weight: bold; -fx-font-size: 13px;" +
            "-fx-background-radius: 12; -fx-padding: 8 16; -fx-cursor: hand;" +
            "-fx-effect: dropshadow(gaussian, rgba(0,0,0,0.35), 10, 0.25, 0, 3);");
        b.setOnMouseEntered(e -> b.setStyle("-fx-background-color: linear-gradient(to bottom right, #A78BFA, #38BDF8); -fx-text-fill: white; -fx-font-weight: bold; -fx-font-size: 13px;" +
            "-fx-background-radius: 12; -fx-padding: 8 16; -fx-cursor: hand;" +
            "-fx-effect: dropshadow(gaussian, rgba(0,0,0,0.45), 12, 0.3, 0, 4);"));
        b.setOnMouseExited(e -> b.setStyle("-fx-background-color: linear-gradient(to bottom right, #8B5CF6, #22D3EE); -fx-text-fill: white; -fx-font-weight: bold; -fx-font-size: 13px;" +
            "-fx-background-radius: 12; -fx-padding: 8 16; -fx-cursor: hand;" +
            "-fx-effect: dropshadow(gaussian, rgba(0,0,0,0.35), 10, 0.25, 0, 3);"));
        return b;
    }

    private String cardStyle() {
        return "-fx-background-color: #111827; -fx-border-color: #1F2937; -fx-border-radius: 10; -fx-background-radius: 10; -fx-padding: 6 6 6 6;" +
            "-fx-effect: dropshadow(gaussian, rgba(0,0,0,0.45), 12, 0.25, 0, 3);";
    }

    private void pornesteAutoRefresh() {
        if (autoRefreshTimer != null) {
            autoRefreshTimer.stop();
        }
        autoRefreshTimer = new Timeline(new KeyFrame(Duration.seconds(10), e -> refreshDinDB()));
        autoRefreshTimer.setCycleCount(Timeline.INDEFINITE);
        autoRefreshTimer.play();
    }

    private void initializeazaBazaDate() {
        ensureDbConnection();
    }

    private boolean ensureDbConnection() {
        try {
            if (conexiune != null && !conexiune.isClosed()) {
                return true;
            }
        } catch (SQLException ignored) {
            // reinit
        }
        try {
            conexiune = DriverManager.getConnection(DbUtil.resolveDbUrl());
            DbUtil.ensureSchemaAndSeed(conexiune);
            return true;
        } catch (SQLException e) {
            showError("Eroare la conectarea bazei de date: " + e.getMessage());
            return false;
        }
    }

    private void incarcaMeseDinDB(int fallbackNumarMese) {
        if (!ensureDbConnection()) {
            mese.clear();
            for (int i = 1; i <= fallbackNumarMese; i++) {
                mese.add(new masa(i));
            }
            return;
        }
        try (Statement stmt = conexiune.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT * FROM mese ORDER BY id")) {
            while (rs.next()) {
                mese.add(new masa(
                    rs.getInt("id"),
                    rs.getString("nume"),
                    rs.getInt("capacitate"),
                    rs.getString("zona"),
                    rs.getInt("pozitie_x"),
                    rs.getInt("pozitie_y"),
                    rs.getInt("langa_fereastra") == 1,
                    rs.getInt("miscarea_blocata") == 1
                ));
            }
        } catch (SQLException e) {
            // fallback la mese simple daca nu putem incarca din DB
            mese.clear();
            for (int i = 1; i <= fallbackNumarMese; i++) {
                mese.add(new masa(i));
            }
        }
    }

    private void incarcaRezervariDinDB() {
        // curatare in-memory
        for (masa m : mese) {
            m.anuleaza();
        }
        if (!ensureDbConnection()) {
            return;
        }
           try (Statement stmt = conexiune.createStatement();
               ResultSet rs = stmt.executeQuery("SELECT masa_id, nume_client, data_ora FROM rezervari WHERE status = 'confirmata'")) {
            while (rs.next()) {
                int masaId = rs.getInt("masa_id");
                String nume = rs.getString("nume_client");
                String dataOra = rs.getString("data_ora");
                masa m = gasesteMasaDupaId(masaId);
                if (m != null && esteRezervareAzi(dataOra)) {
                    m.rezerva(nume);
                }
            }
        } catch (SQLException e) {
            showError("Eroare la incarcarea rezervarilor din baza de date.");
        }
    }

    private boolean esteRezervareAzi(String dataOra) {
        if (dataOra == null || dataOra.isBlank()) {
            return false;
        }
        DateTimeFormatter[] fmts = new DateTimeFormatter[] {
            DateTimeFormatter.ISO_LOCAL_DATE_TIME,
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
        };
        for (DateTimeFormatter fmt : fmts) {
            try {
                LocalDate data = LocalDateTime.parse(dataOra.trim(), fmt).toLocalDate();
                return data.equals(LocalDate.now(ZoneId.of("Europe/Bucharest")));
            } catch (Exception ignored) {
                // try next
            }
        }
        return false;
    }

    private void actualizeazaProgres() {
        int ocupate = 0;
        for (masa m : mese) {
            if (m.esteRezervata()) ocupate++;
        }
        int procent = (int)((ocupate * 100.0) / mese.size());
        progresOcupare.setProgress(procent / 100.0);
        progresText.setText("Ocupare: " + procent + "%");
    }

    private void actualizeazaButoane() {
        for (int i = 0; i < mese.size(); i++) {
            masa m = mese.get(i);
            TableTile tile = tileMese.get(i);
            tile.updateFromMasa(m);
        }
    }

    private void clearSelection() {
        for (TableTile tile : selectedTiles) {
            tile.setSelected(false);
        }
        selectedTiles.clear();
    }

    private void setSelected(TableTile tile, boolean selected) {
        if (selected) {
            selectedTiles.add(tile);
        } else {
            selectedTiles.remove(tile);
        }
        tile.setSelected(selected);
    }

    private void rezervareManuala(int numar, String nume, int nrPers, boolean vreaFereastra, LocalDateTime dataOra) {
        // Run on background thread to prevent UI freeze
        Thread reservationThread = new Thread(() -> {
            try {
                if (!ensureDbConnection()) {
                    Platform.runLater(() -> showError("Eroare la conectare baza de date."));
                    return;
                }
                incarcaRezervariDinDB();
                if (numar <= 0) {
                    Platform.runLater(() -> showInfo("Masa inexistenta."));
                    return;
                }
                if (nume.isEmpty()) {
                    Platform.runLater(() -> showInfo("Introdu numele clientului."));
                    return;
                }

                masa m = gasesteMasaDupaId(numar);
                if (m == null) {
                    Platform.runLater(() -> showInfo("Masa inexistenta."));
                    return;
                }
                if (!esteMasaOcupataDB(numar, dataOra)) {
                    m.rezerva(nume);
                    insereazaRezervareDB(m.getNumar(), nume, nrPers, vreaFereastra, dataOra);
                    incarcaRezervariDinDB();
                    Platform.runLater(() -> {
                        animatieProgres();
                        actualizeazaButoane();
                        showInfo("Rezervare facuta cu succes la masa " + numar);
                    });
                } else {
                    Platform.runLater(() -> showInfo("Masa este deja rezervata."));
                }
            } catch (NumberFormatException ex) {
                Platform.runLater(() -> showInfo("Introdu un numar valid pentru masa."));
            } catch (Exception ex) {
                log("[ERROR] Rezervare manuala crash: " + ex.getClass().getName() + ": " + ex.getMessage());
                Platform.runLater(() -> showError("Eroare la rezervare: " + ex.getMessage()));
            }
        });
        reservationThread.setDaemon(true);
        reservationThread.start();
    }

    private void autoRezervaMasa(String nume, int nrPers, boolean vreaFereastra, LocalDateTime dataOra) {
        // Run on background thread to prevent UI freeze
        Thread reservationThread = new Thread(() -> {
            try {
                if (!ensureDbConnection()) {
                    Platform.runLater(() -> showError("Eroare la conectare baza de date."));
                    return;
                }
                incarcaRezervariDinDB();

                if (nume.isEmpty()) {
                    Platform.runLater(() -> showInfo("Introdu numele clientului."));
                    return;
                }

                masa aleasa = alegeMasaAutomat(nrPers, vreaFereastra, dataOra);
                if (aleasa != null) {
                    aleasa.rezerva(nume);
                    insereazaRezervareDB(aleasa.getNumar(), nume, nrPers, vreaFereastra, dataOra);
                    incarcaRezervariDinDB();
                    final int masaNum = aleasa.getNumar();
                    final boolean fereastraFlag = vreaFereastra;
                    Platform.runLater(() -> {
                        animatieProgres();
                        actualizeazaButoane();
                        if (fereastraFlag && !aleasa.isLangaFereastra()) {
                            showInfo("Nu era disponibila masa la fereastra; am rezervat masa " + masaNum);
                        } else {
                            showInfo("Rezervare facuta la masa " + masaNum);
                        }
                    });
                    return;
                }

                List<masa> pereche = alegePerecheMeseAutomat(nrPers, vreaFereastra, dataOra);
                if (pereche == null || pereche.size() != 2) {
                    Platform.runLater(() -> showInfo("Nu exista masa disponibila pentru " + nrPers + " persoane" + (vreaFereastra ? " la fereastra" : "") + "."));
                    return;
                }

                masa m1 = pereche.get(0);
                masa m2 = pereche.get(1);
                m1.rezerva(nume);
                m2.rezerva(nume);
                insereazaRezervareDB(m1.getNumar(), nume, nrPers, vreaFereastra, dataOra);
                insereazaRezervareDB(m2.getNumar(), nume, nrPers, vreaFereastra, dataOra);
                incarcaRezervariDinDB();
                final int masa1 = m1.getNumar();
                final int masa2 = m2.getNumar();
                Platform.runLater(() -> {
                    animatieProgres();
                    actualizeazaButoane();
                    showInfo("Am rezervat doua mese unite: " + masa1 + " + " + masa2);
                });
            } catch (Exception ex) {
                log("[ERROR] Rezervare auto crash: " + ex.getClass().getName() + ": " + ex.getMessage());
                Platform.runLater(() -> showError("Eroare la rezervare: " + ex.getMessage()));
            }
        });
        reservationThread.setDaemon(true);
        reservationThread.start();
    }

    private void refreshDinDB() {
        incarcaRezervariDinDB();
        actualizeazaButoane();
        actualizeazaProgres();
    }

    private masa alegeMasaAutomat(int nrPers, boolean vreaFereastra, LocalDateTime dataOra) {
        masa candidat = null;
        for (masa m : mese) {
            if (esteMasaOcupataDB(m.getNumar(), dataOra)) continue;
            if (m.getCapacitate() < nrPers) continue;
            if (vreaFereastra && !m.isLangaFereastra()) continue;

            if (candidat == null) {
                candidat = m;
            } else {
                // prioritate: langa fereastra daca s-a cerut, apoi capacitate mai mica dar suficienta, apoi id mai mic
                boolean betterWindow = vreaFereastra && m.isLangaFereastra() && !candidat.isLangaFereastra();
                boolean betterCapacity = m.getCapacitate() < candidat.getCapacitate();
                boolean betterId = m.getCapacitate() == candidat.getCapacitate() && m.getNumar() < candidat.getNumar();
                if (betterWindow || betterCapacity || betterId) {
                    candidat = m;
                }
            }
        }

        // fallback: daca nu exista la fereastra, relaxam conditia de fereastra
        if (candidat == null && vreaFereastra) {
            return alegeMasaAutomat(nrPers, false, dataOra);
        }
        return candidat;
    }

    private List<masa> alegePerecheMeseAutomat(int nrPers, boolean vreaFereastra, LocalDateTime dataOra) {
        List<masa> best = null;
        int bestCap = Integer.MAX_VALUE;

        for (int i = 0; i < mese.size(); i++) {
            masa a = mese.get(i);
            if (esteMasaOcupataDB(a.getNumar(), dataOra)) continue;
            if (vreaFereastra && !a.isLangaFereastra()) continue;

            for (int j = i + 1; j < mese.size(); j++) {
                masa b = mese.get(j);
                if (esteMasaOcupataDB(b.getNumar(), dataOra)) continue;
                if (vreaFereastra && !b.isLangaFereastra()) continue;
                if (!suntMeseConsecutive(a, b)) continue;

                int total = a.getCapacitate() + b.getCapacitate();
                if (total < nrPers) continue;

                if (total < bestCap) {
                    bestCap = total;
                    best = List.of(a, b);
                } else if (total == bestCap && best != null) {
                    int bestMinId = Math.min(best.get(0).getNumar(), best.get(1).getNumar());
                    int bestMaxId = Math.max(best.get(0).getNumar(), best.get(1).getNumar());
                    int curMinId = Math.min(a.getNumar(), b.getNumar());
                    int curMaxId = Math.max(a.getNumar(), b.getNumar());
                    if (curMinId < bestMinId || (curMinId == bestMinId && curMaxId < bestMaxId)) {
                        best = List.of(a, b);
                    }
                }
            }
        }

        if (best == null && vreaFereastra) {
            return alegePerecheMeseAutomat(nrPers, false, dataOra);
        }
        return best;
    }

    private boolean suntMeseConsecutive(masa a, masa b) {
        int na = extractTableNumber(a.getNume(), a.getNumar());
        int nb = extractTableNumber(b.getNume(), b.getNumar());
        return Math.abs(na - nb) == 1;
    }

    private int extractTableNumber(String name, int fallback) {
        if (name == null) {
            return fallback;
        }
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+)").matcher(name);
        if (m.find()) {
            try {
                return Integer.parseInt(m.group(1));
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }


    private masa gasesteMasaDupaId(int id) {
        for (masa m : mese) {
            if (m.getNumar() == id) return m;
        }
        return null;
    }

    private void animatieProgres() {
        Platform.runLater(() -> {
            double curent = progresOcupare.getProgress();
            actualizeazaProgres();
            double tinta = progresOcupare.getProgress();
            Timeline timeline = new Timeline();
            int steps = 30;
            for (int i = 1; i <= steps; i++) {
                double t = i / (double) steps;
                double value = curent + (tinta - curent) * t;
                timeline.getKeyFrames().add(new KeyFrame(Duration.millis(i * 10), ev -> {
                    progresOcupare.setProgress(value);
                    progresText.setText("Ocupare: " + (int) Math.round(value * 100) + "%");
                }));
            }
            timeline.play();
        });
    }

    private void inchideAplicatia() {
        if (autoRefreshTimer != null) {
            autoRefreshTimer.stop();
        }
        showInfo("Datele au fost salvate in baza de date. La revedere!");
        Platform.exit();
    }

    private boolean esteMasaOcupataDB(int masaId) {
        return esteMasaOcupataDB(masaId, LocalDateTime.now(ZoneId.of("Europe/Bucharest")));
    }

    private boolean esteMasaOcupataDB(int masaId, LocalDateTime dataOra) {
        if (dataOra == null) {
            return true;
        }
        DateTimeFormatter[] fmts = new DateTimeFormatter[] {
            DateTimeFormatter.ISO_LOCAL_DATE_TIME,
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
        };
        try (PreparedStatement ps = conexiune.prepareStatement(
                "SELECT data_ora FROM rezervari WHERE status = 'confirmata' AND masa_id = ? AND substr(data_ora,1,10) = ?")) {
            ps.setInt(1, masaId);
            ps.setString(2, dataOra.toLocalDate().toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String raw = rs.getString("data_ora");
                    LocalDateTime existing = null;
                    for (DateTimeFormatter fmt : fmts) {
                        try {
                            existing = LocalDateTime.parse(raw, fmt);
                            break;
                        } catch (Exception ignored) {
                            // try next
                        }
                    }
                    if (existing == null) {
                        return true;
                    }
                    long diff = Math.abs(ChronoUnit.MINUTES.between(existing, dataOra));
                    if (diff < 120) {
                        return true;
                    }
                }
            }
        } catch (SQLException e) {
            return true; // in caz de eroare, consideram ocupat ca sa evitam dublura
        }
        return false;
    }

    private void insereazaRezervareDB(int masaId, String nume, int nrPers, boolean preferintaFereastra) {
        try (PreparedStatement ps = conexiune.prepareStatement(
                "INSERT INTO rezervari (masa_id, nume_client, nr_persoane, data_ora, preferinta_fereastra, status) VALUES (?, ?, ?, ?, ?, 'confirmata')")) {
            ps.setInt(1, masaId);
            ps.setString(2, nume);
            ps.setInt(3, nrPers);
            ps.setString(4, LocalDateTime.now().toString());
            ps.setInt(5, preferintaFereastra ? 1 : 0);
            ps.executeUpdate();
        } catch (SQLException e) {
            showError("Eroare la inserarea rezervarii: " + e.getMessage());
        }
    }

    private void insereazaRezervareDB(int masaId, String nume, int nrPers, boolean preferintaFereastra, LocalDateTime dataOra) {
        try (PreparedStatement ps = conexiune.prepareStatement(
                "INSERT INTO rezervari (masa_id, nume_client, nr_persoane, data_ora, preferinta_fereastra, status) VALUES (?, ?, ?, ?, ?, 'confirmata')")) {
            ps.setInt(1, masaId);
            ps.setString(2, nume);
            ps.setInt(3, nrPers);
            ps.setString(4, dataOra.toString());
            ps.setInt(5, preferintaFereastra ? 1 : 0);
            ps.executeUpdate();
        } catch (SQLException e) {
            showError("Eroare la inserarea rezervarii: " + e.getMessage());
        }
    }

    private LocalDateTime parseDataOraRezervare(LocalDate date, String timeText) {
        LocalDate useDate = date != null ? date : LocalDate.now(ZoneId.of("Europe/Bucharest"));
        try {
            LocalTime time = LocalTime.parse(timeText, DateTimeFormatter.ofPattern("H:mm"));
            if (time.getMinute() != 0 && time.getMinute() != 30) {
                throw new IllegalArgumentException("Minute invalide");
            }
            return LocalDateTime.of(useDate, time);
        } catch (Exception ex) {
            LocalTime now = LocalTime.now(ZoneId.of("Europe/Bucharest")).withSecond(0).withNano(0);
            int roundedMinute = now.getMinute() < 30 ? 0 : 30;
            return LocalDateTime.of(useDate, now.withMinute(roundedMinute));
        }
    }

    private void stergeRezervareDB(int masaId) {
        try (PreparedStatement ps = conexiune.prepareStatement(
                "DELETE FROM rezervari WHERE masa_id = ? AND status = 'confirmata'")) {
            ps.setInt(1, masaId);
            ps.executeUpdate();
        } catch (SQLException e) {
            showError("Eroare la stergerea rezervarii: " + e.getMessage());
        }
    }

    private void curataRezervari() {
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle("Confirmare");
        confirm.setHeaderText("Stergi toate rezervarile?");
        confirm.setContentText("Aceasta actiune nu poate fi anulata.");
        confirm.showAndWait().ifPresent(result -> {
            if (result == ButtonType.OK) {
                try (PreparedStatement ps = conexiune.prepareStatement(
                        "DELETE FROM rezervari WHERE status = 'confirmata'");) {
                    ps.executeUpdate();
                    refreshDinDB();
                    showInfo("Toate rezervarile au fost sterse.");
                } catch (SQLException e) {
                    showError("Eroare la curatarea rezervarilor: " + e.getMessage());
                }
            }
        });
    }

    private void stergeMasaCuConfirmare(masa m, TableTile tile) {
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle("Confirmare");
        confirm.setHeaderText("Stergi masa " + m.getNume() + "?");
        confirm.setContentText("Se vor sterge si rezervarile asociate.");
        confirm.showAndWait().ifPresent(result -> {
            if (result == ButtonType.OK) {
                stergeMasaDB(m);
                mese.remove(m);
                tileMese.remove(tile);
                panouMese.getChildren().remove(tile);
                selectedTiles.remove(tile);
                refreshDinDB();
            }
        });
    }

    private void stergeMasaDB(masa m) {
        if (conexiune == null) {
            return;
        }
        try (PreparedStatement ps1 = conexiune.prepareStatement(
                "DELETE FROM rezervari WHERE masa_id = ?")) {
            ps1.setInt(1, m.getNumar());
            ps1.executeUpdate();
        } catch (SQLException e) {
            showError("Eroare la stergerea rezervarilor mesei: " + e.getMessage());
        }

        try (PreparedStatement ps2 = conexiune.prepareStatement(
                "DELETE FROM mese WHERE id = ?")) {
            ps2.setInt(1, m.getNumar());
            ps2.executeUpdate();
        } catch (SQLException e) {
            showError("Eroare la stergerea mesei: " + e.getMessage());
        }
    }

    private void openRezervareDialog(boolean autoAssignOnly) {
        if (!ensureDbConnection()) {
            return;
        }
        log("[INFO] Deschidere dialog rezervare.");
        Dialog<Void> dialog = new Dialog<>();
        dialog.setTitle("Rezervare masa");
        dialog.setHeaderText(null);

        TextField masaField = new TextField();
        masaField.setPrefWidth(100);
        TextField numeField = new TextField();
        Spinner<Integer> persoaneSpinner = new Spinner<>(1, 12, 2);
        persoaneSpinner.setEditable(false);
        CheckBox fereastraCheck = new CheckBox("La fereastra");
        DatePicker dataPicker = new DatePicker(LocalDate.now(ZoneId.of("Europe/Bucharest")));
        ComboBox<String> oraBox = new ComboBox<>(FXCollections.observableArrayList(buildTimeSlots()));
        oraBox.setEditable(false);
        oraBox.setPrefWidth(110);
        oraBox.getSelectionModel().select("19:00");

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(12, 12, 6, 12));

        int row = 0;
        grid.add(new Label("Numar masa:"), 0, row);
        grid.add(masaField, 1, row++);
        grid.add(new Label("Nume client:"), 0, row);
        grid.add(numeField, 1, row++);
        grid.add(new Label("Nr persoane:"), 0, row);
        grid.add(persoaneSpinner, 1, row++);
        grid.add(fereastraCheck, 1, row++);
        grid.add(new Label("Data:"), 0, row);
        grid.add(dataPicker, 1, row++);
        grid.add(new Label("Ora (HH:mm):"), 0, row);
        grid.add(oraBox, 1, row++);

        dialog.getDialogPane().setContent(grid);

        ButtonType okManual = new ButtonType("Rezerva", ButtonBar.ButtonData.OK_DONE);
        ButtonType okAuto = new ButtonType("Auto-assign", ButtonBar.ButtonData.APPLY);
        dialog.getDialogPane().getButtonTypes().addAll(okManual, okAuto, ButtonType.CANCEL);

        Button btnManual = (Button) dialog.getDialogPane().lookupButton(okManual);
        btnManual.addEventFilter(javafx.event.ActionEvent.ACTION, ev -> {
            try {
                int numar = Integer.parseInt(masaField.getText().trim());
                String nume = numeField.getText().trim();
                int nrPers = persoaneSpinner.getValue();
                boolean vreaFereastra = fereastraCheck.isSelected();
                LocalDateTime dataOra = parseDataOraRezervare(dataPicker.getValue(), oraBox.getValue());
                rezervareManuala(numar, nume, nrPers, vreaFereastra, dataOra);
                dialog.close();
            } catch (NumberFormatException ex) {
                showInfo("Introdu un numar valid pentru masa.");
                ev.consume();
            } catch (Exception ex) {
                log("[ERROR] Rezervare manuala crash: " + ex.getClass().getName() + ": " + ex.getMessage());
                showError("Eroare la rezervare: " + ex.getMessage());
                ev.consume();
            }
        });

        Button btnAuto = (Button) dialog.getDialogPane().lookupButton(okAuto);
        btnAuto.addEventFilter(javafx.event.ActionEvent.ACTION, ev -> {
            try {
                String nume = numeField.getText().trim();
                int nrPers = persoaneSpinner.getValue();
                boolean vreaFereastra = fereastraCheck.isSelected();
                LocalDateTime dataOra = parseDataOraRezervare(dataPicker.getValue(), oraBox.getValue());
                autoRezervaMasa(nume, nrPers, vreaFereastra, dataOra);
                dialog.close();
            } catch (Exception ex) {
                log("[ERROR] Rezervare auto crash: " + ex.getClass().getName() + ": " + ex.getMessage());
                showError("Eroare la rezervare: " + ex.getMessage());
                ev.consume();
            }
        });

        dialog.showAndWait();
    }

    private List<String> buildTimeSlots() {
        return new ArrayList<>(DEFAULT_TIME_SLOTS);
    }

    private static List<String> initTimeSlots() {
        List<String> slots = new ArrayList<>();
        LocalTime start = LocalTime.of(10, 0);
        LocalTime end = LocalTime.of(23, 30);
        LocalTime t = start;
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("HH:mm");
        int guard = 0;
        while (!t.isAfter(end) && guard < 100) {
            slots.add(t.format(fmt));
            t = t.plusMinutes(30);
            guard++;
        }
        return slots;
    }

    private void showInfo(String message) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle("Info");
        alert.setHeaderText(null);
        alert.setContentText(message);
        alert.showAndWait();
    }

    private void showError(String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle("Eroare");
        alert.setHeaderText(null);
        alert.setContentText(message);
        alert.showAndWait();
    }

    private Path initLogFile() {
        try {
            Path dir = Paths.get(System.getProperty("user.home"), "RezervariRestaurant");
            Files.createDirectories(dir);
            return dir.resolve("app.log");
        } catch (Exception e) {
            return Paths.get("app.log");
        }
    }

    private void log(String msg) {
        try {
            String line = java.time.LocalDateTime.now() + " " + msg + System.lineSeparator();
            Files.writeString(logFile, line, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception ignored) {
            // no-op
        }
    }

    // --- UI helper classes pentru look modern ---
    private class TableTile extends Pane {
        private final Label badge;
        private final Label label;
        private final Circle tableCircle;
        private final Ellipse tableEllipse;
        private final Ellipse selectionRing;
        private final Pane chairs;
        private final ContextMenu contextMenu;
        private Runnable onSelect;
        private double dragOffsetX;
        private double dragOffsetY;
        private boolean draggable = true;
        private final masa masaRef;
        private boolean selected;
        private double groupStartX;
        private double groupStartY;

        TableTile(masa m) {
            this.masaRef = m;
            setPrefSize(160, 160);
            setPickOnBounds(false);

            badge = new Label();
            badge.setFont(Font.font("Segoe UI", FontWeight.BOLD, 11));
            badge.setTextFill(Color.BLACK);
            badge.setPadding(new Insets(4, 6, 4, 6));
            badge.setLayoutX(20);
            badge.setLayoutY(0);

            tableCircle = new Circle(60);
            tableCircle.setCenterX(80);
            tableCircle.setCenterY(90);
            tableCircle.setEffect(new DropShadow(10, Color.rgb(0, 0, 0, 0.18)));

            tableEllipse = new Ellipse(80, 90, 80, 55);
            tableEllipse.setEffect(new DropShadow(10, Color.rgb(0, 0, 0, 0.18)));
            tableEllipse.setVisible(false);

            selectionRing = new Ellipse(80, 90, 86, 61);
            selectionRing.setFill(Color.TRANSPARENT);
            selectionRing.setStroke(Color.web("#FFB300"));
            selectionRing.setStrokeWidth(3);
            selectionRing.setVisible(false);

            label = new Label();
            label.setTextFill(Color.WHITE);
            label.setFont(Font.font("Segoe UI", FontWeight.BOLD, 13));
            label.setLayoutX(52);
            label.setLayoutY(80);

            chairs = new Pane();
            chairs.setPickOnBounds(false);
            rebuildChairs(m.getCapacitate());

            getChildren().addAll(chairs, selectionRing, tableCircle, tableEllipse, label, badge);
            updateFromMasa(m);

            contextMenu = new ContextMenu();
            MenuItem toggleMove = new MenuItem();
            MenuItem toggleWindow = new MenuItem();
            MenuItem renameTable = new MenuItem("Redenumeste masa");
            MenuItem editSeats = new MenuItem("Modifica locuri");
            MenuItem deleteTable = new MenuItem("Sterge masa");
            contextMenu.getItems().addAll(toggleMove, toggleWindow, renameTable, editSeats, new SeparatorMenuItem(), deleteTable);

            contextMenu.setOnShowing(e -> {
                toggleMove.setText(draggable ? "Blocheaza miscarea" : "Permite miscarea");
                toggleWindow.setText(masaRef.isLangaFereastra() ? "Scoate din fereastra" : "Marcheaza ca masa la fereastra");
            });

            toggleMove.setOnAction(e -> {
                List<TableTile> targets = getSelectedTargets(this);
                for (TableTile tile : targets) {
                    boolean newDraggable = !tile.draggable;
                    tile.draggable = newDraggable;
                    tile.masaRef.setMiscareaBlocata(!newDraggable);
                    actualizeazaMiscareaBlocataDB(tile.masaRef);
                }
            });

            toggleWindow.setOnAction(e -> {
                List<TableTile> targets = getSelectedTargets(this);
                for (TableTile tile : targets) {
                    tile.masaRef.setLangaFereastra(!tile.masaRef.isLangaFereastra());
                    actualizeazaMasaFereastraDB(tile.masaRef);
                    tile.updateFromMasa(tile.masaRef);
                }
            });

            renameTable.setOnAction(e -> {
                TextInputDialog dialog = new TextInputDialog(masaRef.getNume());
                dialog.setTitle("Redenumeste masa");
                dialog.setHeaderText(null);
                dialog.setContentText("Nume nou pentru masa:");
                dialog.showAndWait().ifPresent(newName -> {
                    String trimmed = newName.trim();
                    if (!trimmed.isEmpty()) {
                        List<TableTile> targets = getSelectedTargets(this);
                        for (TableTile tile : targets) {
                            tile.masaRef.setNume(trimmed);
                            actualizeazaNumeMasaDB(tile.masaRef);
                            tile.updateFromMasa(tile.masaRef);
                        }
                    }
                });
            });

            editSeats.setOnAction(e -> {
                TextInputDialog dialog = new TextInputDialog(String.valueOf(masaRef.getCapacitate()));
                dialog.setTitle("Modifica locuri");
                dialog.setHeaderText(null);
                dialog.setContentText("Numar de locuri:");
                dialog.showAndWait().ifPresent(value -> {
                    try {
                        int newCap = Integer.parseInt(value.trim());
                        if (newCap < 1 || newCap > 12) {
                            showInfo("Numarul de locuri trebuie sa fie intre 1 si 12.");
                            return;
                        }
                        List<TableTile> targets = getSelectedTargets(this);
                        for (TableTile tile : targets) {
                            tile.masaRef.setCapacitate(newCap);
                            actualizeazaCapacitateMasaDB(tile.masaRef);
                            tile.updateFromMasa(tile.masaRef);
                        }
                    } catch (NumberFormatException ex) {
                        showInfo("Introdu un numar valid.");
                    }
                });
            });

            deleteTable.setOnAction(e -> stergeMasaCuConfirmare(masaRef, this));

            setOnMousePressed(e -> {
                if (e.isSecondaryButtonDown()) {
                    if (!selected) {
                        clearSelection();
                        RestaurantGUI.this.setSelected(this, true);
                    }
                    contextMenu.show(this, e.getScreenX(), e.getScreenY());
                    e.consume();
                    return;
                }
                if (e.isPrimaryButtonDown()) {
                    if (!selected) {
                        clearSelection();
                        RestaurantGUI.this.setSelected(this, true);
                    }
                    for (TableTile tile : selectedTiles) {
                        tile.groupStartX = tile.getLayoutX();
                        tile.groupStartY = tile.getLayoutY();
                    }
                    dragOffsetX = e.getSceneX() - getLayoutX();
                    dragOffsetY = e.getSceneY() - getLayoutY();
                    if (onSelect != null) {
                        onSelect.run();
                    }
                }
            });

            setOnMouseDragged(e -> {
                if (!draggable || !e.isPrimaryButtonDown()) {
                    return;
                }
                double newX = e.getSceneX() - dragOffsetX;
                double newY = e.getSceneY() - dragOffsetY;

                if (selectedTiles.contains(this) && selectedTiles.size() > 1) {
                    double deltaX = newX - groupStartX;
                    double deltaY = newY - groupStartY;
                    for (TableTile tile : selectedTiles) {
                        if (!tile.draggable) {
                            continue;
                        }
                        tile.setLayoutX(tile.groupStartX + deltaX);
                        tile.setLayoutY(tile.groupStartY + deltaY);
                    }
                } else {
                    setLayoutX(newX);
                    setLayoutY(newY);
                }
            });

            setOnMouseClicked(e -> {
                if (e.getClickCount() == 2 && e.getButton() == javafx.scene.input.MouseButton.PRIMARY) {
                    showRezervariPentruMasa(masaRef);
                }
            });

            setOnMouseReleased(e -> {
                if (!draggable || e.getButton() != javafx.scene.input.MouseButton.PRIMARY) {
                    return;
                }
                int newX = (int) Math.max(0, Math.round(getLayoutX()));
                int newY = (int) Math.max(0, Math.round(getLayoutY()));
                if (selectedTiles.contains(this) && selectedTiles.size() > 1) {
                    for (TableTile tile : selectedTiles) {
                        if (!tile.draggable) {
                            continue;
                        }
                        int tX = (int) Math.max(0, Math.round(tile.getLayoutX()));
                        int tY = (int) Math.max(0, Math.round(tile.getLayoutY()));
                        tile.masaRef.setPozitieX(tX);
                        tile.masaRef.setPozitieY(tY);
                        actualizeazaPozitieMasaDB(tile.masaRef);
                    }
                } else {
                    masaRef.setPozitieX(newX);
                    masaRef.setPozitieY(newY);
                    actualizeazaPozitieMasaDB(masaRef);
                }
            });
        }

        private void rebuildChairs(int capacity) {
            chairs.getChildren().clear();
            int count = Math.max(2, capacity);
            if (count <= 8) {
                double radius = 78;
                for (int i = 0; i < count; i++) {
                    double angle = (2 * Math.PI / count) * i;
                    double cx = 80 + Math.cos(angle) * radius;
                    double cy = 90 + Math.sin(angle) * radius;
                    Circle chair = new Circle(10);
                    chair.setCenterX(cx);
                    chair.setCenterY(cy);
                    chair.setFill(Color.web("#D9E2F5"));
                    chair.setStroke(Color.web("#B7C2DA"));
                    chairs.getChildren().add(chair);
                }
            } else {
                double radiusX = 100;
                double radiusY = 70;
                for (int i = 0; i < count; i++) {
                    double angle = (2 * Math.PI / count) * i;
                    double cx = 80 + Math.cos(angle) * radiusX;
                    double cy = 90 + Math.sin(angle) * radiusY;
                    Circle chair = new Circle(9);
                    chair.setCenterX(cx);
                    chair.setCenterY(cy);
                    chair.setFill(Color.web("#D9E2F5"));
                    chair.setStroke(Color.web("#B7C2DA"));
                    chairs.getChildren().add(chair);
                }
            }
        }

        void updateFromMasa(masa m) {
            rebuildChairs(m.getCapacitate());
            boolean large = m.getCapacitate() > 8;
            tableCircle.setVisible(!large);
            tableEllipse.setVisible(large);
            selectionRing.setRadiusX(large ? 86 : 66);
            selectionRing.setRadiusY(large ? 61 : 66);
            String displayName = m.getNume() != null && !m.getNume().isBlank() ? m.getNume() : ("Masa " + m.getNumar());
            label.setText(displayName);
            badge.setText(m.getCapacitate() + " locuri" + (m.isLangaFereastra() ? " • fereastra" : ""));

            Color baseLibre = m.isLangaFereastra() ? Color.web("#26A65B") : Color.web("#428BCA");
            Color baseOcupat = Color.web("#D64541");
            if (large) {
                tableEllipse.setFill(m.esteRezervata() ? baseOcupat : baseLibre);
            } else {
                tableCircle.setFill(m.esteRezervata() ? baseOcupat : baseLibre);
            }

            String badgeBg = m.esteRezervata() ? "#FFEBEE" : "#E8F4FD";
            badge.setStyle("-fx-background-color: " + badgeBg + "; -fx-border-color: #DCE1EB; -fx-border-radius: 6; -fx-background-radius: 6; -fx-text-fill: #000000;");

            draggable = !m.isMiscareaBlocata();
        }

        void setOnSelect(Runnable onSelect) {
            this.onSelect = onSelect;
        }

        masa getMasaRef() {
            return masaRef;
        }

        void setSelected(boolean selected) {
            this.selected = selected;
            selectionRing.setVisible(selected);
        }
    }

    private List<TableTile> getSelectedTargets(TableTile clicked) {
        if (selectedTiles.isEmpty() || !selectedTiles.contains(clicked)) {
            List<TableTile> single = new ArrayList<>();
            single.add(clicked);
            return single;
        }
        return new ArrayList<>(selectedTiles);
    }

    private void actualizeazaPozitieMasaDB(masa m) {
        if (conexiune == null) {
            return;
        }
        try (PreparedStatement ps = conexiune.prepareStatement(
                "UPDATE mese SET pozitie_x = ?, pozitie_y = ? WHERE id = ?")) {
            ps.setInt(1, m.getPozitieX());
            ps.setInt(2, m.getPozitieY());
            ps.setInt(3, m.getNumar());
            ps.executeUpdate();
        } catch (SQLException e) {
            showError("Eroare la salvarea pozitiei mesei: " + e.getMessage());
        }
    }

    private void insereazaMasaDB(masa m) {
        if (conexiune == null) {
            return;
        }
        try (PreparedStatement ps = conexiune.prepareStatement(
                "INSERT INTO mese (id, nume, capacitate, zona, pozitie_x, pozitie_y, langa_fereastra, miscarea_blocata) VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {
            ps.setInt(1, m.getNumar());
            ps.setString(2, m.getNume());
            ps.setInt(3, m.getCapacitate());
            ps.setString(4, m.getZona());
            ps.setInt(5, m.getPozitieX());
            ps.setInt(6, m.getPozitieY());
            ps.setInt(7, m.isLangaFereastra() ? 1 : 0);
            ps.setInt(8, m.isMiscareaBlocata() ? 1 : 0);
            ps.executeUpdate();
        } catch (SQLException e) {
            showError("Eroare la inserarea mesei: " + e.getMessage());
        }
    }

    private int nextTableNumber() {
        int max = 0;
        for (masa m : mese) {
            if (m.getNumar() > max) {
                max = m.getNumar();
            }
        }
        return max + 1;
    }

    private void createNewTableAt(double x, double y) {
        int numarNou = nextTableNumber();
        masa m = new masa(numarNou, "Masa " + numarNou, 4, "custom", (int) Math.round(x), (int) Math.round(y), false, false);
        mese.add(m);
        insereazaMasaDB(m);

        TableTile tile = new TableTile(m);
        tileMese.add(tile);
        tile.setLayoutX(m.getPozitieX());
        tile.setLayoutY(m.getPozitieY());
        panouMese.getChildren().add(tile);
        selectionRect.toFront();
    }

    private void actualizeazaMasaFereastraDB(masa m) {
        if (conexiune == null) {
            return;
        }
        try (PreparedStatement ps = conexiune.prepareStatement(
                "UPDATE mese SET langa_fereastra = ? WHERE id = ?")) {
            ps.setInt(1, m.isLangaFereastra() ? 1 : 0);
            ps.setInt(2, m.getNumar());
            ps.executeUpdate();
        } catch (SQLException e) {
            showError("Eroare la salvarea preferintei de fereastra: " + e.getMessage());
        }
    }

    private void actualizeazaCapacitateMasaDB(masa m) {
        if (conexiune == null) {
            return;
        }
        try (PreparedStatement ps = conexiune.prepareStatement(
                "UPDATE mese SET capacitate = ? WHERE id = ?")) {
            ps.setInt(1, m.getCapacitate());
            ps.setInt(2, m.getNumar());
            ps.executeUpdate();
        } catch (SQLException e) {
            showError("Eroare la salvarea capacitatii: " + e.getMessage());
        }
    }

    private void showRezervariPentruMasa(masa m) {
        if (conexiune == null) {
            showInfo("Nu exista conexiune la baza de date.");
            return;
        }
        DateTimeFormatter[] inputFmts = new DateTimeFormatter[] {
            DateTimeFormatter.ISO_LOCAL_DATE_TIME,
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
        };
        DateTimeFormatter outputFmt = DateTimeFormatter.ofPattern("HH:mm");
        List<RezervareRow> allRows = new ArrayList<>();
        try (PreparedStatement ps = conexiune.prepareStatement(
                "SELECT nume_client, nr_persoane, data_ora, status FROM rezervari WHERE masa_id = ? ORDER BY data_ora DESC")) {
            ps.setInt(1, m.getNumar());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String rawData = rs.getString("data_ora");
                    String formattedTime = rawData;
                    LocalDate dayKey = null;
                    for (DateTimeFormatter fmt : inputFmts) {
                        try {
                            LocalDateTime parsed = LocalDateTime.parse(rawData, fmt);
                            formattedTime = parsed.format(outputFmt);
                            dayKey = parsed.toLocalDate();
                            break;
                        } catch (Exception ignored) {
                            // try next
                        }
                    }
                    allRows.add(new RezervareRow(
                            dayKey,
                            formattedTime,
                            rs.getString("nume_client"),
                            rs.getInt("nr_persoane") + " pers",
                            rs.getString("status")
                    ));
                }
            }
        } catch (SQLException e) {
            showError("Eroare la incarcarea rezervarilor: " + e.getMessage());
            return;
        }

        Dialog<Void> dialog = new Dialog<>();
        dialog.setTitle("Rezervari pentru " + m.getNume());
        dialog.setHeaderText(null);

        if (allRows.isEmpty()) {
            Label empty = new Label("Nu exista rezervari pentru aceasta masa.");
            empty.setPadding(new Insets(12));
            dialog.getDialogPane().setContent(empty);
        } else {
            DatePicker picker = new DatePicker(LocalDate.now());

            TableView<RezervareRow> tableView = new TableView<>();
            TableColumn<RezervareRow, String> colTime = new TableColumn<>("Ora");
            colTime.setCellValueFactory(data -> data.getValue().dataOraProperty());

            TableColumn<RezervareRow, String> colNume = new TableColumn<>("Client");
            colNume.setCellValueFactory(data -> data.getValue().numeProperty());

            TableColumn<RezervareRow, String> colNr = new TableColumn<>("Persoane");
            colNr.setCellValueFactory(data -> data.getValue().nrPersoaneProperty());

            TableColumn<RezervareRow, String> colStatus = new TableColumn<>("Status");
            colStatus.setCellValueFactory(data -> data.getValue().statusProperty());

            tableView.getColumns().addAll(colTime, colNume, colNr, colStatus);
            tableView.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
            applyColumnSeparators(colTime, colNume, colNr, colStatus);
            tableView.setPrefHeight(280);
            tableView.setPlaceholder(new Label("Nu exista rezervari"));

            Runnable refresh = () -> {
                LocalDate selected = picker.getValue();
                ObservableList<RezervareRow> filtered = FXCollections.observableArrayList();
                for (RezervareRow row : allRows) {
                    if (row.getDate() != null && row.getDate().equals(selected)) {
                        filtered.add(row);
                    }
                }
                tableView.setItems(filtered);
            };

            picker.valueProperty().addListener((obs, oldV, newV) -> refresh.run());
            refresh.run();

            HBox header = new HBox(10, new Label("Selecteaza ziua:"), picker);
            header.setAlignment(Pos.CENTER_LEFT);
            header.setPadding(new Insets(8, 8, 0, 8));

            VBox content = new VBox(8, header, tableView);
            dialog.getDialogPane().setContent(content);
        }

        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        dialog.showAndWait();
    }

    @SafeVarargs
    private final void applyColumnSeparators(TableColumn<RezervareRow, String>... columns) {
        for (int i = 0; i < columns.length; i++) {
            boolean last = i == columns.length - 1;
            columns[i].setCellFactory(col -> new TableCell<>() {
                @Override
                protected void updateItem(String item, boolean empty) {
                    super.updateItem(item, empty);
                    if (empty) {
                        setText(null);
                        setStyle(null);
                    } else {
                        setText(item);
                        if (last) {
                            setStyle("-fx-padding: 6 10 6 10;");
                        } else {
                            setStyle("-fx-padding: 6 10 6 10; -fx-border-color: transparent #E2E8F0 transparent transparent; -fx-border-width: 0 1 0 0;");
                        }
                    }
                }
            });
        }
    }

    private static class RezervareRow {
        private final LocalDate date;
        private final javafx.beans.property.SimpleStringProperty dataOra;
        private final javafx.beans.property.SimpleStringProperty nume;
        private final javafx.beans.property.SimpleStringProperty nrPersoane;
        private final javafx.beans.property.SimpleStringProperty status;

        RezervareRow(LocalDate date, String dataOra, String nume, String nrPersoane, String status) {
            this.date = date;
            this.dataOra = new javafx.beans.property.SimpleStringProperty(dataOra);
            this.nume = new javafx.beans.property.SimpleStringProperty(nume);
            this.nrPersoane = new javafx.beans.property.SimpleStringProperty(nrPersoane);
            this.status = new javafx.beans.property.SimpleStringProperty(status);
        }

        LocalDate getDate() { return date; }
        javafx.beans.property.SimpleStringProperty dataOraProperty() { return dataOra; }
        javafx.beans.property.SimpleStringProperty numeProperty() { return nume; }
        javafx.beans.property.SimpleStringProperty nrPersoaneProperty() { return nrPersoane; }
        javafx.beans.property.SimpleStringProperty statusProperty() { return status; }
    }

    private void actualizeazaMiscareaBlocataDB(masa m) {
        if (conexiune == null) {
            return;
        }
        try (PreparedStatement ps = conexiune.prepareStatement(
                "UPDATE mese SET miscarea_blocata = ? WHERE id = ?")) {
            ps.setInt(1, m.isMiscareaBlocata() ? 1 : 0);
            ps.setInt(2, m.getNumar());
            ps.executeUpdate();
        } catch (SQLException e) {
            showError("Eroare la salvarea blocarii mesei: " + e.getMessage());
        }
    }

    private void actualizeazaNumeMasaDB(masa m) {
        if (conexiune == null) {
            return;
        }
        try (PreparedStatement ps = conexiune.prepareStatement(
                "UPDATE mese SET nume = ? WHERE id = ?")) {
            ps.setString(1, m.getNume());
            ps.setInt(2, m.getNumar());
            ps.executeUpdate();
        } catch (SQLException e) {
            showError("Eroare la salvarea numelui mesei: " + e.getMessage());
        }
    }

}

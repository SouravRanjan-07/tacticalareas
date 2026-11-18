package com.c2.airspace;

import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.ResourceBundle;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.image.Image;
import javafx.scene.layout.AnchorPane;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;

public class TacticalController implements Initializable {

    @FXML
    private TableView<AirAsset> assetTable;

    @FXML
    private TableColumn<AirAsset, String> idCol;

    @FXML
    private TableColumn<AirAsset, String> callsignCol;

    @FXML
    private TableColumn<AirAsset, Integer> altCol;

    @FXML
    private TableColumn<AirAsset, String> statusCol;

    @FXML
    private AnchorPane mapContainer;

    @FXML
    private Canvas mapCanvas;

    // Tactical geographic scope (India subcontinent)
    private final double minLon = 66.0;
    private final double maxLon = 98.0;
    private final double minLat = 6.0;
    private final double maxLat = 37.0;

    // Airspace layer from QGIS
    private final List<double[]> airspacePolygon = new ArrayList<>();

    // Assets layer
    private final ObservableList<AirAsset> trackedAssets = FXCollections.observableArrayList();

    // Satellite base layer image
    private Image satelliteImage;

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        System.out.println("Tactical C2 Pure Java Engine Initialized.");

        // 1. Table Setup
        idCol.setCellValueFactory(new PropertyValueFactory<>("id"));
        callsignCol.setCellValueFactory(new PropertyValueFactory<>("callsign"));
        altCol.setCellValueFactory(new PropertyValueFactory<>("altitude"));
        statusCol.setCellValueFactory(new PropertyValueFactory<>("status"));
        assetTable.setItems(trackedAssets);

        // 2. Responsive Canvas Sizing
        mapCanvas.widthProperty().bind(mapContainer.widthProperty());
        mapCanvas.heightProperty().bind(mapContainer.heightProperty());

        mapCanvas.widthProperty().addListener((obs, oldV, newV) -> renderDisplay());
        mapCanvas.heightProperty().addListener((obs, oldV, newV) -> renderDisplay());

        // 3. Load QGIS Airspace
        loadPolygonData("data/restricted_airspace.geojson", airspacePolygon);

        // 4. Load Sample Assets
        trackedAssets.add(new AirAsset("UAV-01", "HERON-ALPHA", 73.5, 22.0, 4200, 45, 180, "CLEAR"));
        trackedAssets.add(new AirAsset("UAV-02", "PREDATOR-X", 78.0, 18.0, 2800, 190, 220, "BREACH"));
        trackedAssets.add(new AirAsset("FTR-09", "VIPER-LEAD", 84.0, 14.5, 9500, 290, 640, "CLEAR"));
        trackedAssets.add(new AirAsset("RPT-03", "SHADOW-3", 75.8, 11.5, 1500, 90, 110, "CAUTION"));

        // 5. Fetch Satellite Map via NASA WMS API
        fetchSatelliteMap();

        // 6. Initial render
        renderDisplay();
    }

    private void fetchSatelliteMap() {
        // NASA GIBS WMS API (Web Map Service)
        // Requests satellite imagery covering exact bbox: [minLat, minLon, maxLat, maxLon]
        String apiUrl = String.format(
            "https://gibs.earthdata.nasa.gov/wms/epsg4326/best/wms.cgi?" +
            "SERVICE=WMS&REQUEST=GetMap&VERSION=1.3.0&LAYERS=BlueMarble_NextGeneration&" +
            "FORMAT=image/jpeg&CRS=EPSG:4326&BBOX=%.2f,%.2f,%.2f,%.2f&WIDTH=1200&HEIGHT=900",
            minLat, minLon, maxLat, maxLon
        );

        System.out.println("Fetching satellite map from API: " + apiUrl);

        // Background asynchronous load (true = non-blocking)
        satelliteImage = new Image(apiUrl, true);

        // Redraw automatically once the image finishes downloading
        satelliteImage.progressProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal.doubleValue() >= 1.0) {
                System.out.println("Satellite map downloaded successfully.");
                renderDisplay();
            }
        });
    }

    private void loadPolygonData(String filePathString, List<double[]> targetList) {
        try {
            Path path = Paths.get(filePathString);
            if (!Files.exists(path)) {
                System.err.println("File not found: " + path.toAbsolutePath());
                return;
            }

            String content = Files.readString(path);
            targetList.clear();

            Pattern pattern = Pattern.compile("\\[\\s*(-?\\d+\\.?\\d*)\\s*,\\s*(-?\\d+\\.?\\d*)\\s*\\]");
            Matcher matcher = pattern.matcher(content);

            while (matcher.find()) {
                double lon = Double.parseDouble(matcher.group(1));
                double lat = Double.parseDouble(matcher.group(2));
                targetList.add(new double[]{lon, lat});
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void renderDisplay() {
        double w = mapCanvas.getWidth();
        double h = mapCanvas.getHeight();

        if (w <= 0 || h <= 0) return;

        GraphicsContext gc = mapCanvas.getGraphicsContext2D();

        // ---------------------------------------------------------
        // LAYER 0: Satellite Imagery (From API) or Fallback Fill
        // ---------------------------------------------------------
        if (satelliteImage != null && !satelliteImage.isError() && satelliteImage.getProgress() >= 1.0) {
            gc.drawImage(satelliteImage, 0, 0, w, h);
            // Dark tactical wash over the imagery for defense console contrast
            gc.setFill(Color.rgb(10, 15, 20, 0.35));
            gc.fillRect(0, 0, w, h);
        } else {
            gc.setFill(Color.web("#0e1318"));
            gc.fillRect(0, 0, w, h);
            gc.setFill(Color.web("#607d8b"));
            gc.setFont(Font.font("Monospaced", 12.0));
            gc.fillText("ACQUIRING SATELLITE FEED VIA API...", 20, 30);
        }

        // ---------------------------------------------------------
        // LAYER 1: Tactical Coordinate Graticules
        // ---------------------------------------------------------
        gc.setStroke(Color.rgb(100, 140, 180, 0.25));
        gc.setLineWidth(1.0);
        gc.setFill(Color.web("#80d8ff"));
        gc.setFont(Font.font("Monospaced", 10.0));	

        for (double lon = Math.ceil(minLon); lon <= maxLon; lon += 4.0) {
            double x = lonToScreenX(lon, w);
            gc.strokeLine(x, 0, x, h);
            gc.fillText(String.format("%.0f°E", lon), x + 4, h - 8);
        }

        for (double lat = Math.ceil(minLat); lat <= maxLat; lat += 4.0) {
            double y = latToScreenY(lat, h);
            gc.strokeLine(0, y, w, y);
            gc.fillText(String.format("%.0f°N", lat), 8, y - 4);
        }

        // ---------------------------------------------------------
        // LAYER 2: QGIS Restricted Airspace
        // ---------------------------------------------------------
        if (airspacePolygon.size() >= 3) {
            int n = airspacePolygon.size();
            double[] xPoints = new double[n];
            double[] yPoints = new double[n];

            for (int i = 0; i < n; i++) {
                double[] pt = airspacePolygon.get(i);
                xPoints[i] = lonToScreenX(pt[0], w);
                yPoints[i] = latToScreenY(pt[1], h);
            }

            gc.setFill(Color.rgb(255, 30, 30, 0.28));
            gc.fillPolygon(xPoints, yPoints, n);

            gc.setStroke(Color.web("#ff3333"));
            gc.setLineWidth(2.0);
            gc.strokePolygon(xPoints, yPoints, n);

            gc.setFill(Color.web("#ff8888"));
            for (int i = 0; i < n; i++) {
                gc.fillOval(xPoints[i] - 3, yPoints[i] - 3, 6, 6);
            }

            gc.setFill(Color.web("#ff4d4d"));
            gc.setFont(Font.font("Monospaced", 11.0));
            gc.fillText("ZONE: SECTOR_RED_ALPHA [PROHIBITED]", xPoints[0] + 10, yPoints[0] - 8);
        }

        // ---------------------------------------------------------
        // LAYER 3: Live Airborne Asset Symbols
        // ---------------------------------------------------------
        for (AirAsset asset : trackedAssets) {
            double ax = lonToScreenX(asset.getLongitude(), w);
            double ay = latToScreenY(asset.getLatitude(), h);

            Color targetColor;
            if ("BREACH".equals(asset.getStatus())) {
                targetColor = Color.web("#ff3333");
            } else if ("CAUTION".equals(asset.getStatus())) {
                targetColor = Color.web("#ffbb33");
            } else {
                targetColor = Color.web("#00ff88");
            }

            gc.setStroke(targetColor);
            gc.setLineWidth(2.0);
            double s = 7.0;
            double[] diamondX = { ax, ax + s, ax, ax - s };
            double[] diamondY = { ay - s, ay, ay + s, ay };
            gc.strokePolygon(diamondX, diamondY, 4);

            double rad = Math.toRadians(asset.getHeading() - 90);
            double vLen = 22.0;
            double hx = ax + vLen * Math.cos(rad);
            double hy = ay + vLen * Math.sin(rad);
            gc.strokeLine(ax, ay, hx, hy);

            gc.setFill(targetColor);
            gc.setFont(Font.font("Monospaced", 11.0));
            gc.fillText(asset.getCallsign(), ax + 12, ay - 4);

            gc.setFill(Color.web("#cfd8dc"));
            gc.setFont(Font.font("Monospaced", 9.0));
            gc.fillText(asset.getAltitude() + "m | " + asset.getSpeed() + "kt", ax + 12, ay + 8);
        }

        // Reticle
        gc.setStroke(Color.rgb(100, 140, 180, 0.4));
        gc.strokeLine(w / 2.0 - 20, h / 2.0, w / 2.0 + 20, h / 2.0);
        gc.strokeLine(w / 2.0, h / 2.0 - 20, w / 2.0, h / 2.0 + 20);
    }

    private double lonToScreenX(double lon, double width) {
        return ((lon - minLon) / (maxLon - minLon)) * width;
    }

    private double latToScreenY(double lat, double height) {
        return (1.0 - ((lat - minLat) / (maxLat - minLat))) * height;
    }
}
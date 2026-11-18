package airspace;

import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.ResourceBundle;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Label;
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

    @FXML
    private Label corridorInfoLabel;

    // Tactical geographic scope (India subcontinent)
    private final double minLon = 66.0;
    private final double maxLon = 98.0;
    private final double minLat = 6.0;
    private final double maxLat = 37.0;

    // Airspace layer from QGIS
    private final List<double[]> airspacePolygon = new ArrayList<>();

    // Air corridors layer from GeoJSON
    private final List<double[]> airCorridor = new ArrayList<>();
    private String airCorridorName = "AIR CORRIDOR";
    private String airCorridorMinAltitude = "";

    // Radar sites are WGS84 points; buffer ranges are read from the processed buffer layer.
    private final List<RadarSite> radarSites = new ArrayList<>();
    private final List<double[]> corridorZoneIntersection = new ArrayList<>();
    private String intersectionLabel = "CORRIDOR / RESTRICTED ZONE INTERSECTION";

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
        loadCorridorData("data/air_corridors.geojson");
        loadRadarData("data/radar_sites.geojson", "data/Buffered.geojson");
        loadIntersectionData("data/intersection.geojson");

        // 4. Load Sample Assets
        trackedAssets.add(new AirAsset("UAV-01", "HERON-ALPHA", 73.5, 22.0, 4200, 45, 180, "CLEAR"));
        trackedAssets.add(new AirAsset("UAV-02", "PREDATOR-X", 78.0, 18.0, 2800, 190, 220, "BREACH"));
        trackedAssets.add(new AirAsset("FTR-09", "VIPER-LEAD", 84.0, 14.5, 9500, 290, 640, "CLEAR"));
        trackedAssets.add(new AirAsset("RPT-03", "SHADOW-3", 75.8, 11.5, 1500, 90, 110, "CAUTION"));

        assetTable.getSelectionModel().selectedItemProperty().addListener(
                (obs, oldAsset, selectedAsset) -> updateCorridorInfo(selectedAsset));
        if (!trackedAssets.isEmpty()) {
            assetTable.getSelectionModel().selectFirst();
        }

        // 5. Fetch Satellite Map via NASA WMS API
        fetchSatelliteMap();

        // 6. Initial render
        renderDisplay();
    }

    private void fetchSatelliteMap() {
        // NASA GIBS WMS API (Web Map Service)
        // Requests satellite imagery covering exact bbox: [minLat, minLon, maxLat,
        // maxLon]
        String apiUrl = String.format(
                "https://gibs.earthdata.nasa.gov/wms/epsg4326/best/wms.cgi?" +
                        "SERVICE=WMS&REQUEST=GetMap&VERSION=1.3.0&LAYERS=BlueMarble_NextGeneration&" +
                        "FORMAT=image/jpeg&CRS=EPSG:4326&BBOX=%.2f,%.2f,%.2f,%.2f&WIDTH=1200&HEIGHT=900",
                minLat, minLon, maxLat, maxLon);

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
                targetList.add(new double[] { lon, lat });
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void loadRadarData(String sitePathString, String bufferPathString) {
        Map<String, Double> rangeBySite = new HashMap<>();
        try {
            for (String feature : readGeoJsonFeatures(bufferPathString)) {
                String siteName = stringProperty(feature, "site_name");
                Double rangeKm = numberProperty(feature, "radar_range_km");
                if (siteName != null && rangeKm != null) {
                    rangeBySite.put(siteName, rangeKm);
                }
            }
        } catch (IOException e) {
            System.err.println("Unable to read radar buffer data: " + e.getMessage());
        }

        try {
            for (String feature : readGeoJsonFeatures(sitePathString)) {
                String siteName = stringProperty(feature, "site_name");
                List<double[]> coordinates = coordinatePairs(feature);
                if (siteName == null || coordinates.isEmpty()) {
                    System.err.println("Skipping radar feature with missing site name or coordinates.");
                    continue;
                }

                Double bufferRangeKm = rangeBySite.get(siteName);
                Double sourceRangeKm = numberProperty(feature, "radar_range_km");
                if (bufferRangeKm == null) {
                    System.err.println("No processed buffer range found for radar site " + siteName + ".");
                    bufferRangeKm = 0.0;
                } else if (sourceRangeKm != null && !bufferRangeKm.equals(sourceRangeKm)) {
                    System.err.println("Using processed buffer range " + bufferRangeKm + " km for " + siteName
                            + " instead of source-site value " + sourceRangeKm + " km.");
                }

                double[] point = coordinates.get(0);
                radarSites.add(new RadarSite(siteName, point[0], point[1], bufferRangeKm));
            }
        } catch (IOException e) {
            System.err.println("Unable to read radar site data: " + e.getMessage());
        }
    }

    private void loadIntersectionData(String filePathString) {
        try {
            for (String feature : readGeoJsonFeatures(filePathString)) {
                corridorZoneIntersection.addAll(coordinatePairs(feature));
                String corridorName = stringProperty(feature, "corridor_name");
                String zoneName = stringProperty(feature, "zone_name");
                if (corridorName != null && zoneName != null) {
                    intersectionLabel = corridorName + " / " + zoneName;
                }
            }
        } catch (IOException e) {
            System.err.println("Unable to read corridor/zone intersection data: " + e.getMessage());
        }
    }

    private List<String> readGeoJsonFeatures(String filePathString) throws IOException {
        Path path = Paths.get(filePathString);
        if (!Files.exists(path)) {
            throw new IOException("File not found: " + path.toAbsolutePath());
        }

        String content = Files.readString(path);
        int featuresKey = content.indexOf("\"features\"");
        int arrayStart = featuresKey < 0 ? -1 : content.indexOf('[', featuresKey);
        if (arrayStart < 0) {
            throw new IOException("GeoJSON has no features array: " + path.toAbsolutePath());
        }

        List<String> features = new ArrayList<>();
        int objectStart = -1;
        int objectDepth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = arrayStart + 1; i < content.length(); i++) {
            char current = content.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (current == '\\') {
                    escaped = true;
                } else if (current == '"') {
                    inString = false;
                }
                continue;
            }

            if (current == '"') {
                inString = true;
            } else if (current == '{') {
                if (objectDepth == 0) {
                    objectStart = i;
                }
                objectDepth++;
            } else if (current == '}') {
                objectDepth--;
                if (objectDepth == 0 && objectStart >= 0) {
                    features.add(content.substring(objectStart, i + 1));
                    objectStart = -1;
                }
            } else if (current == ']' && objectDepth == 0) {
                break;
            }
        }
        return features;
    }

    private String stringProperty(String feature, String propertyName) {
        Matcher matcher = Pattern.compile("\"" + Pattern.quote(propertyName) + "\"\\s*:\\s*\"([^\"]*)\"")
                .matcher(feature);
        return matcher.find() ? matcher.group(1) : null;
    }

    private Double numberProperty(String feature, String propertyName) {
        String number = "-?(?:\\d+(?:\\.\\d*)?|\\.\\d+)(?:[eE][+-]?\\d+)?";
        Matcher matcher = Pattern.compile("\"" + Pattern.quote(propertyName) + "\"\\s*:\\s*(" + number + ")")
                .matcher(feature);
        return matcher.find() ? Double.valueOf(matcher.group(1)) : null;
    }

    private List<double[]> coordinatePairs(String feature) {
        String number = "-?(?:\\d+(?:\\.\\d*)?|\\.\\d+)(?:[eE][+-]?\\d+)?";
        Pattern pattern = Pattern.compile(
                "\\[\\s*(" + number + ")\\s*,\\s*(" + number + ")(?:\\s*,\\s*" + number + ")?\\s*\\]");
        Matcher matcher = pattern.matcher(feature);
        List<double[]> coordinates = new ArrayList<>();
        while (matcher.find()) {
            coordinates.add(new double[] {
                    Double.parseDouble(matcher.group(1)),
                    Double.parseDouble(matcher.group(2))
            });
        }
        return coordinates;
    }

    private void loadCorridorData(String filePathString) {
        try {
            Path path = Paths.get(filePathString);
            if (!Files.exists(path)) {
                System.err.println("File not found: " + path.toAbsolutePath());
                return;
            }

            String content = Files.readString(path);
            Matcher nameMatcher = Pattern.compile("\"corridor_name\"\\s*:\\s*\"([^\"]+)\"").matcher(content);
            if (nameMatcher.find()) {
                airCorridorName = nameMatcher.group(1);
            }

            Matcher altitudeMatcher = Pattern.compile("\"min_alt\"\\s*:\\s*(-?\\d+(?:\\.\\d+)?)").matcher(content);
            if (altitudeMatcher.find()) {
                airCorridorMinAltitude = altitudeMatcher.group(1);
            }

            Pattern coordinatePattern = Pattern.compile(
                    "\\[\\s*(-?(?:\\d+(?:\\.\\d*)?|\\.\\d+)(?:[eE][+-]?\\d+)?)\\s*,\\s*" +
                    "(-?(?:\\d+(?:\\.\\d*)?|\\.\\d+)(?:[eE][+-]?\\d+)?)(?:\\s*,\\s*" +
                    "-?(?:\\d+(?:\\.\\d*)?|\\.\\d+)(?:[eE][+-]?\\d+)?)?\\s*\\]");
            Matcher coordinateMatcher = coordinatePattern.matcher(content);
            while (coordinateMatcher.find()) {
                double[] point = {
                        Double.parseDouble(coordinateMatcher.group(1)),
                        Double.parseDouble(coordinateMatcher.group(2))
                };
                if (airCorridor.isEmpty() || !samePoint(airCorridor.get(airCorridor.size() - 1), point)) {
                    airCorridor.add(point);
                }
            }
        } catch (IOException e) {
            System.err.println("Unable to read air corridor data: " + e.getMessage());
        }
    }

    private boolean samePoint(double[] first, double[] second) {
        return Double.compare(first[0], second[0]) == 0 && Double.compare(first[1], second[1]) == 0;
    }

    private void updateCorridorInfo(AirAsset asset) {
        if (corridorInfoLabel == null) {
            return;
        }
        if (asset == null) {
            corridorInfoLabel.setText("Select an asset to inspect corridor proximity.");
        } else if (airCorridor.size() < 2) {
            corridorInfoLabel.setText("No air corridor line is available.");
        } else {
            CorridorProximity proximity = findNearestCorridorPoint(asset);
            String altitudeText = airCorridorMinAltitude.isEmpty()
                    ? ""
                    : " | min_alt: " + airCorridorMinAltitude;
            String radarText = radarSitesFor(asset);
            String intersectionText = corridorZoneIntersection.size() >= 2
                    ? "Route/zone intersection highlighted: " + intersectionLabel
                    : "No corridor/zone intersection data available.";
            corridorInfoLabel.setText(String.format(
                    "%s → %s: %.1f km from centerline%s%nInside radar buffers: %s%n%s",
                    asset.getCallsign(), airCorridorName, proximity.distanceKm, altitudeText,
                    radarText, intersectionText));
        }
        renderDisplay();
    }

    private String radarSitesFor(AirAsset asset) {
        List<String> containingSites = new ArrayList<>();
        for (RadarSite site : radarSites) {
            if (site.rangeKm > 0
                    && haversineKm(asset.getLongitude(), asset.getLatitude(), site.longitude, site.latitude)
                            <= site.rangeKm) {
                containingSites.add(site.name);
            }
        }
        return containingSites.isEmpty() ? "none" : String.join(", ", containingSites);
    }

    private CorridorProximity findNearestCorridorPoint(AirAsset asset) {
        double cosLatitude = Math.cos(Math.toRadians(asset.getLatitude()));
        double assetX = asset.getLongitude() * cosLatitude;
        double assetY = asset.getLatitude();
        double nearestLongitude = airCorridor.get(0)[0];
        double nearestLatitude = airCorridor.get(0)[1];
        double minimumSquaredDistance = Double.POSITIVE_INFINITY;

        for (int i = 0; i < airCorridor.size() - 1; i++) {
            double[] first = airCorridor.get(i);
            double[] second = airCorridor.get(i + 1);
            double firstX = first[0] * cosLatitude;
            double firstY = first[1];
            double deltaX = (second[0] * cosLatitude) - firstX;
            double deltaY = second[1] - firstY;
            double segmentLengthSquared = deltaX * deltaX + deltaY * deltaY;
            double fraction = segmentLengthSquared == 0
                    ? 0
                    : ((assetX - firstX) * deltaX + (assetY - firstY) * deltaY) / segmentLengthSquared;
            fraction = Math.max(0, Math.min(1, fraction));

            double candidateX = firstX + fraction * deltaX;
            double candidateY = firstY + fraction * deltaY;
            double offsetX = assetX - candidateX;
            double offsetY = assetY - candidateY;
            double squaredDistance = offsetX * offsetX + offsetY * offsetY;
            if (squaredDistance < minimumSquaredDistance) {
                minimumSquaredDistance = squaredDistance;
                nearestLongitude = candidateX / cosLatitude;
                nearestLatitude = candidateY;
            }
        }

        double distanceKm = haversineKm(
                asset.getLongitude(), asset.getLatitude(), nearestLongitude, nearestLatitude);
        return new CorridorProximity(nearestLongitude, nearestLatitude, distanceKm);
    }

    private double haversineKm(double longitude1, double latitude1, double longitude2, double latitude2) {
        double latitudeDelta = Math.toRadians(latitude2 - latitude1);
        double longitudeDelta = Math.toRadians(longitude2 - longitude1);
        double a = Math.sin(latitudeDelta / 2) * Math.sin(latitudeDelta / 2)
                + Math.cos(Math.toRadians(latitude1)) * Math.cos(Math.toRadians(latitude2))
                * Math.sin(longitudeDelta / 2) * Math.sin(longitudeDelta / 2);
        return 6371.0 * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    private double[][] coverageBoundary(RadarSite site) {
        int steps = 72;
        double[][] boundary = new double[steps][2];
        double angularDistance = site.rangeKm / 6371.0;
        double latitude = Math.toRadians(site.latitude);
        double longitude = Math.toRadians(site.longitude);

        for (int i = 0; i < steps; i++) {
            double bearing = Math.toRadians(i * 360.0 / steps);
            double destinationLatitude = Math.asin(
                    Math.sin(latitude) * Math.cos(angularDistance)
                            + Math.cos(latitude) * Math.sin(angularDistance) * Math.cos(bearing));
            double destinationLongitude = longitude + Math.atan2(
                    Math.sin(bearing) * Math.sin(angularDistance) * Math.cos(latitude),
                    Math.cos(angularDistance) - Math.sin(latitude) * Math.sin(destinationLatitude));
            boundary[i][0] = Math.toDegrees(destinationLongitude);
            boundary[i][1] = Math.toDegrees(destinationLatitude);
        }
        return boundary;
    }

    private static final class CorridorProximity {
        private final double longitude;
        private final double latitude;
        private final double distanceKm;

        private CorridorProximity(double longitude, double latitude, double distanceKm) {
            this.longitude = longitude;
            this.latitude = latitude;
            this.distanceKm = distanceKm;
        }
    }

    private static final class RadarSite {
        private final String name;
        private final double longitude;
        private final double latitude;
        private final double rangeKm;

        private RadarSite(String name, double longitude, double latitude, double rangeKm) {
            this.name = name;
            this.longitude = longitude;
            this.latitude = latitude;
            this.rangeKm = rangeKm;
        }
    }

    private void renderDisplay() {
        double w = mapCanvas.getWidth();
        double h = mapCanvas.getHeight();

        if (w <= 0 || h <= 0)
            return;

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

        // The source buffers are projected in UTM; draw geodesic rings from their ranges
        // around the WGS84 radar-site points used by this longitude/latitude canvas.
        for (RadarSite site : radarSites) {
            if (site.rangeKm <= 0) {
                continue;
            }
            double[][] boundary = coverageBoundary(site);
            double[] xPoints = new double[boundary.length];
            double[] yPoints = new double[boundary.length];
            for (int i = 0; i < boundary.length; i++) {
                xPoints[i] = lonToScreenX(boundary[i][0], w);
                yPoints[i] = latToScreenY(boundary[i][1], h);
            }
            gc.setFill(Color.rgb(0, 170, 255, 0.08));
            gc.fillPolygon(xPoints, yPoints, boundary.length);
            gc.setStroke(Color.rgb(0, 217, 255, 0.55));
            gc.setLineWidth(1.0);
            gc.strokePolygon(xPoints, yPoints, boundary.length);
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
        // LAYER 3: Air corridor centerline
        // ---------------------------------------------------------
        if (airCorridor.size() >= 2) {
            gc.setStroke(Color.web("#00d9ff"));
            gc.setLineWidth(3.0);
            for (int i = 0; i < airCorridor.size() - 1; i++) {
                double[] first = airCorridor.get(i);
                double[] second = airCorridor.get(i + 1);
                gc.strokeLine(
                        lonToScreenX(first[0], w), latToScreenY(first[1], h),
                        lonToScreenX(second[0], w), latToScreenY(second[1], h));
            }

            double[] start = airCorridor.get(0);
            double[] end = airCorridor.get(airCorridor.size() - 1);
            gc.setFill(Color.web("#00d9ff"));
            gc.fillOval(lonToScreenX(start[0], w) - 4, latToScreenY(start[1], h) - 4, 8, 8);
            gc.fillOval(lonToScreenX(end[0], w) - 4, latToScreenY(end[1], h) - 4, 8, 8);
            gc.setFont(Font.font("Monospaced", 11.0));
            gc.fillText(airCorridorName, lonToScreenX(start[0], w) + 8, latToScreenY(start[1], h) - 8);
        }

        if (corridorZoneIntersection.size() >= 2) {
            gc.setStroke(Color.web("#ff8c42"));
            gc.setLineWidth(4.0);
            for (int i = 0; i < corridorZoneIntersection.size() - 1; i++) {
                double[] first = corridorZoneIntersection.get(i);
                double[] second = corridorZoneIntersection.get(i + 1);
                gc.strokeLine(
                        lonToScreenX(first[0], w), latToScreenY(first[1], h),
                        lonToScreenX(second[0], w), latToScreenY(second[1], h));
            }
            double[] point = corridorZoneIntersection.get(0);
            gc.setFill(Color.web("#ff8c42"));
            gc.setFont(Font.font("Monospaced", 10.0));
            gc.fillText("RESTRICTED-ZONE CROSSING", lonToScreenX(point[0], w) + 6,
                    latToScreenY(point[1], h) - 6);
        }

        // ---------------------------------------------------------
        // LAYER 5: Radar site markers
        // ---------------------------------------------------------
        for (RadarSite site : radarSites) {
            double x = lonToScreenX(site.longitude, w);
            double y = latToScreenY(site.latitude, h);
            gc.setStroke(Color.web("#b3ecff"));
            gc.setLineWidth(2.0);
            gc.strokeOval(x - 5, y - 5, 10, 10);
            gc.strokeLine(x - 8, y, x + 8, y);
            gc.strokeLine(x, y - 8, x, y + 8);
            gc.setFill(Color.web("#b3ecff"));
            gc.setFont(Font.font("Monospaced", 9.0));
            gc.fillText(site.name + (site.rangeKm > 0 ? " " + (int) site.rangeKm + "km" : ""),
                    x + 8, y - 8);
        }

        // ---------------------------------------------------------
        // LAYER 6: Live Airborne Asset Symbols
        // ---------------------------------------------------------
        AirAsset selectedAsset = assetTable == null ? null : assetTable.getSelectionModel().getSelectedItem();
        if (selectedAsset != null && airCorridor.size() >= 2) {
            CorridorProximity proximity = findNearestCorridorPoint(selectedAsset);
            gc.setStroke(Color.web("#ffd166"));
            gc.setLineWidth(1.5);
            gc.setLineDashes(6, 4);
            gc.strokeLine(
                    lonToScreenX(selectedAsset.getLongitude(), w),
                    latToScreenY(selectedAsset.getLatitude(), h),
                    lonToScreenX(proximity.longitude, w),
                    latToScreenY(proximity.latitude, h));
            gc.setLineDashes();
            gc.setFill(Color.web("#ffd166"));
            gc.fillOval(lonToScreenX(proximity.longitude, w) - 3, latToScreenY(proximity.latitude, h) - 3, 6, 6);
        }

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
package airspace;

public class AirAsset {

    private String id;
    private String callsign;
    private double longitude;
    private double latitude;
    private int altitude; // in meters
    private double heading; // degrees (0 - 360)
    private int speed; // knots / km/h
    private String status; // "CLEAR", "CAUTION", "BREACH"

    public AirAsset(String id, String callsign, double longitude, double latitude, int altitude, double heading,
            int speed, String status) {
        this.id = id;
        this.callsign = callsign;
        this.longitude = longitude;
        this.latitude = latitude;
        this.altitude = altitude;
        this.heading = heading;
        this.speed = speed;
        this.status = status;
    }

    // Getters and Setters (Required by JavaFX TableView PropertyValueFactory)
    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getCallsign() {
        return callsign;
    }

    public void setCallsign(String callsign) {
        this.callsign = callsign;
    }

    public double getLongitude() {
        return longitude;
    }

    public void setLongitude(double longitude) {
        this.longitude = longitude;
    }

    public double getLatitude() {
        return latitude;
    }

    public void setLatitude(double latitude) {
        this.latitude = latitude;
    }

    public int getAltitude() {
        return altitude;
    }

    public void setAltitude(int altitude) {
        this.altitude = altitude;
    }

    public double getHeading() {
        return heading;
    }

    public void setHeading(double heading) {
        this.heading = heading;
    }

    public int getSpeed() {
        return speed;
    }

    public void setSpeed(int speed) {
        this.speed = speed;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }
}
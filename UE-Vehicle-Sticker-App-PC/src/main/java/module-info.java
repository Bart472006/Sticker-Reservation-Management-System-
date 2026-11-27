module com.ue.vehiclestickersystem {
    requires javafx.controls;
    requires javafx.fxml;
    requires java.sql;
    requires java.desktop;

    opens com.ue.vehiclestickersystem to javafx.fxml;
    exports com.ue.vehiclestickersystem;
}
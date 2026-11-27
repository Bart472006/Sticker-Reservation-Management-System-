package com.ue.vehiclestickersystem;

import com.ue.vehiclestickersystem.Main.DatabaseManager;
import com.ue.vehiclestickersystem.Main.User;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.input.MouseEvent;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class LoginController {

    private static final Path REMEMBER_FILE = Paths.get(System.getProperty("java.io.tmpdir"), "ue_campus_pass_login.txt");

    @FXML private TextField txtEmail;
    @FXML private PasswordField txtPassword;
    @FXML private TextField txtPasswordVisible;
    @FXML private Button btnTogglePassword;
    @FXML private Button btnSignIn;
    @FXML private CheckBox chkRememberMe;

    private boolean passwordShown = false;
    private boolean authInProgress = false;

    @FXML
    public void initialize() {
        if (txtPassword != null && txtPasswordVisible != null) {
            txtPasswordVisible.textProperty().bindBidirectional(txtPassword.textProperty());
        }
        Main.runAsync(() -> {
            if (Files.exists(REMEMBER_FILE)) return Files.readString(REMEMBER_FILE).trim();
            return "";
        }, savedEmail -> {
            if (savedEmail != null && !savedEmail.isEmpty() && txtEmail != null) {
                txtEmail.setText(savedEmail);
                if (chkRememberMe != null) chkRememberMe.setSelected(true);
            }
        }, ignored -> {});
    }

    @FXML
    private void handleTogglePassword(ActionEvent event) {
        passwordShown = !passwordShown;
        txtPasswordVisible.setVisible(passwordShown);
        txtPasswordVisible.setManaged(passwordShown);
        txtPassword.setVisible(!passwordShown);
        txtPassword.setManaged(!passwordShown);
        if (btnTogglePassword != null) {
            btnTogglePassword.setStyle("-fx-background-color: transparent; -fx-font-size: 14px; -fx-padding: 2 4; -fx-cursor: hand; -fx-text-fill: " + (passwordShown ? "#b71c1c;" : "#64748b;"));
        }
    }

    @FXML
    private void handleForgotPassword(MouseEvent event) {
        showAlert(Alert.AlertType.INFORMATION, "Forgot Password",
                "Please visit Gate 1 Security Office (Window 2) or contact the UE IT Department with your valid UE ID to reset your password.");
    }

    @FXML
    private void handleLoginSubmit(ActionEvent event) {
        if (authInProgress) return;

        String email = txtEmail.getText() != null ? txtEmail.getText().trim() : "";
        String pwd = txtPassword.getText() != null ? txtPassword.getText().trim() : "";

        if (email.isEmpty() || pwd.isEmpty()) {
            showAlert(Alert.AlertType.WARNING, "Missing Credentials", "Please enter both your email and password.");
            return;
        }

        boolean remember = chkRememberMe != null && chkRememberMe.isSelected();
        setLoginBusy(true);

        Main.runAsync(() -> {
            User authenticated = DatabaseManager.authenticate(email, pwd);
            if (authenticated != null) {
                try {
                    if (remember) Files.writeString(REMEMBER_FILE, email);
                    else Files.deleteIfExists(REMEMBER_FILE);
                } catch (Exception ignored) {}
            }
            return authenticated;
        }, user -> {
            setLoginBusy(false);
            if (user != null) {
                Main.setCurrentUser(user);
                if (user.getRole().equalsIgnoreCase("ADMIN")) {
                    Main.loadScene("AdminView.fxml", 1600, 900);
                } else {
                    Main.loadScene("UserView.fxml", 1600, 900);
                }
            } else {
                showAlert(Alert.AlertType.ERROR, "Login Failed", "Invalid email or password. Please try again.");
            }
        }, err -> {
            setLoginBusy(false);
            showAlert(Alert.AlertType.ERROR, "Connection Error", "Unable to complete login request: " + err.getMessage());
        });
    }

    private void setLoginBusy(boolean busy) {
        authInProgress = busy;
        if (txtEmail != null) txtEmail.setDisable(busy);
        if (txtPassword != null) txtPassword.setDisable(busy);
        if (txtPasswordVisible != null) txtPasswordVisible.setDisable(busy);
        if (chkRememberMe != null) chkRememberMe.setDisable(busy);
        if (btnSignIn != null) {
            btnSignIn.setDisable(busy);
            btnSignIn.setText(busy ? "Connecting to Server..." : "Sign In  ➔");
        }
    }

    private void showAlert(Alert.AlertType type, String title, String content) {
        Alert alert = new Alert(type);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(content);
        alert.showAndWait();
    }
}
package com.ue.vehiclestickersystem;

import javafx.application.Application;
import javafx.application.HostServices;
import javafx.beans.property.*;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.concurrent.Task;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;
import java.io.IOException;
import java.sql.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

public class Main extends Application {

    private static Stage primaryStage;
    private static HostServices appHostServices;
    private static volatile User currentUser;

    // Daemon thread pool for all non-UI Tailscale HTTP & MySQL background tasks
    private static final ExecutorService BG_EXECUTOR = Executors.newFixedThreadPool(
            Math.max(4, Runtime.getRuntime().availableProcessors()),
            r -> {
                Thread t = new Thread(r, "ue-campus-pass-bg");
                t.setDaemon(true);
                return t;
            }
    );

    @FunctionalInterface
    public interface CheckedSupplier<T> {
        T get() throws Exception;
    }

    @FunctionalInterface
    public interface CheckedRunnable {
        void run() throws Exception;
    }

    public static <T> Task<T> runAsync(CheckedSupplier<T> backgroundWork, Consumer<T> onSuccess, Consumer<Throwable> onError) {
        Task<T> task = new Task<>() {
            @Override
            protected T call() throws Exception {
                return backgroundWork.get();
            }
        };
        task.setOnSucceeded(e -> {
            if (onSuccess != null) onSuccess.accept(task.getValue());
        });
        task.setOnFailed(e -> {
            Throwable ex = task.getException();
            if (onError != null) onError.accept(ex);
            else if (ex != null) ex.printStackTrace();
        });
        BG_EXECUTOR.submit(task);
        return task;
    }

    public static Task<Void> runAsync(CheckedRunnable backgroundWork, Runnable onSuccess, Consumer<Throwable> onError) {
        return runAsync(() -> {
            backgroundWork.run();
            return null;
        }, v -> {
            if (onSuccess != null) onSuccess.run();
        }, onError);
    }

    public static void main(String[] args) { launch(args); }

    @Override
    public void start(Stage stage) {
        primaryStage = stage;
        appHostServices = getHostServices();
        primaryStage.setTitle("UE Campus Pass - Vehicle Sticker System");
        showLoginView();
        runAsync(DatabaseManager::initDatabase, null, null);
    }

    @Override
    public void stop() {
        BG_EXECUTOR.shutdownNow();
    }

    public static Stage getPrimaryStage() { return primaryStage; }
    public static User getCurrentUser() { return currentUser; }
    public static void setCurrentUser(User user) { currentUser = user; }
    public static void showLoginView() { loadScene("LoginView.fxml", 950, 650); }

    public static void openExternalDocument(String uri) {
        if (appHostServices != null && uri != null) appHostServices.showDocument(uri);
    }

    public static void loadScene(String fxmlFile, double width, double height) {
        try {
            Parent root = new FXMLLoader(Main.class.getResource(fxmlFile)).load();
            if (primaryStage.getScene() == null) {
                primaryStage.setScene(new Scene(root, width, height));
            } else {
                primaryStage.getScene().setRoot(root);
            }
            primaryStage.setWidth(width);
            primaryStage.setHeight(height);
            primaryStage.centerOnScreen();
            primaryStage.show();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    // =========================================================================
    // SHARED DATA MODELS
    // =========================================================================
    public static class User {
        private final String id, name, email, role, department;

        public User(String id, String name, String email, String role, String department) {
            this.id = id;
            this.name = name;
            this.email = email;
            this.role = role;
            this.department = department;
        }

        public String getId() { return id; }
        public String getName() { return name; }
        public String getEmail() { return email; }
        public String getRole() { return role; }
        public String getDepartment() { return department; }
    }

    public static class InboxMessage {
        public final String category, sender, recipient, subject, body, shortDate, fullTimestamp;

        public InboxMessage(String category, String sender, String recipient, String subject, String body, String shortDate, String fullTimestamp) {
            this.category = category;
            this.sender = sender;
            this.recipient = recipient;
            this.subject = subject;
            this.body = body;
            this.shortDate = shortDate;
            this.fullTimestamp = fullTimestamp;
        }
    }

    public static class VehicleApplication {
        private static final DateTimeFormatter EXPIRY_FMT = DateTimeFormatter.ofPattern("MMM dd, yyyy");

        private final IntegerProperty id = new SimpleIntegerProperty();
        private final StringProperty ownerName = new SimpleStringProperty();
        private final StringProperty vehicleModel = new SimpleStringProperty();
        private final StringProperty plateNumber = new SimpleStringProperty();
        private final StringProperty passType = new SimpleStringProperty();
        private final StringProperty status = new SimpleStringProperty();
        private final String ownerId, make, model, year, color, ownership;
        private volatile String approvalDate;
        private volatile LocalDateTime createdAt;

        public VehicleApplication(int id, String ownerId, String ownerName, String rawVehicle, String plateNumber, String passType, String ownership, String status, String approvalDate) {
            this(id, ownerId, ownerName, rawVehicle, plateNumber, passType, ownership, status, approvalDate, LocalDateTime.now());
        }

        public VehicleApplication(int id, String ownerId, String ownerName, String rawVehicle, String plateNumber, String passType, String ownership, String status, String approvalDate, LocalDateTime createdAt) {
            this.id.set(id);
            this.ownerId = ownerId;
            this.ownerName.set(ownerName);
            this.plateNumber.set(plateNumber);
            this.passType.set(passType);
            this.ownership = ownership != null ? ownership : "My Own Vehicle";
            this.status.set(status);
            this.approvalDate = approvalDate;
            this.createdAt = createdAt != null ? createdAt : LocalDateTime.now();

            if (rawVehicle != null && rawVehicle.contains("|")) {
                String[] p = rawVehicle.split("\\|", -1);
                this.make = p.length > 0 ? p[0] : "Vehicle";
                this.model = p.length > 1 ? p[1] : "";
                this.year = p.length > 2 ? p[2] : "2024";
                this.color = p.length > 3 ? p[3] : "Standard";
                this.vehicleModel.set((this.make + " " + this.model).trim());
            } else {
                String clean = rawVehicle != null ? rawVehicle.trim() : "Vehicle";
                int sp = clean.indexOf(' ');
                this.make = sp > 0 ? clean.substring(0, sp) : clean;
                this.model = sp > 0 ? clean.substring(sp + 1) : "Standard";
                this.year = "2024";
                this.color = "Standard";
                this.vehicleModel.set(clean);
            }
        }

        public String getOwnerId() { return ownerId; }
        public String getMake() { return make; }
        public String getModel() { return model; }
        public String getYear() { return year; }
        public String getColor() { return color; }
        public String getOwnership() { return ownership; }
        public String getApprovalDate() { return approvalDate; }
        public LocalDateTime getCreatedAt() { return createdAt; }
        public void setApprovalTimestamp(LocalDateTime ts, String formatted) {
            this.createdAt = ts;
            this.approvalDate = formatted;
        }
        public String getStatus() { return status.get(); }

        // Returns true if status is explicitly EXPIRED or if an APPROVED sticker has reached/exceeded 1 year
        public boolean isExpired() {
            String st = getStatus();
            if ("EXPIRED".equalsIgnoreCase(st)) return true;
            if (!"APPROVED".equalsIgnoreCase(st)) return false;
            return createdAt != null && !LocalDateTime.now().isBefore(createdAt.plusYears(1));
        }

        public String getExpirationDateFormatted() {
            return createdAt != null ? createdAt.plusYears(1).format(EXPIRY_FMT) : "1 Year from Approval";
        }

        public IntegerProperty idProperty() { return id; }
        public StringProperty ownerNameProperty() { return ownerName; }
        public StringProperty vehicleModelProperty() { return vehicleModel; }
        public StringProperty plateNumberProperty() { return plateNumber; }
        public StringProperty passTypeProperty() { return passType; }
        public StringProperty statusProperty() { return status; }
    }

    // =========================================================================
    // MYSQL DATABASE MANAGER
    // =========================================================================

    public static class DatabaseManager {
        // Connect your XAMPP MySQL database here:
        // - Keep "localhost" if XAMPP MySQL is running on this same PC.
        // - Or replace "localhost" with your host PC's LAN IP (e.g., "192.168.1.10") or Tailscale IP if connecting from another PC.
        // - Ensure your database in phpMyAdmin is named "ue_sticker_db" and update USER / PASS if your MySQL credentials differ.
        private static final String URL = "jdbc:mysql://localhost:3306/ue_sticker_db?useSSL=false&allowPublicKeyRetrieval=true&connectTimeout=5000&socketTimeout=10000";
        private static final String USER = "root";
        private static final String PASS = "";

        private static final List<VehicleApplication> fallbackApps = Collections.synchronizedList(new ArrayList<>());
        private static final List<InboxMessage> fallbackMessages = Collections.synchronizedList(new ArrayList<>());
        private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("M/d/yyyy, h:mm:ss a");
        private static final DateTimeFormatter SHORT_FMT = DateTimeFormatter.ofPattern("MMM dd, yyyy");
        private static final DateTimeFormatter FULL_FMT = DateTimeFormatter.ofPattern("MMM dd, yyyy 'at' hh:mm a");

        private static final String DEMO_EXPIRED_ID = "2022-10452";
        private static final String DEMO_EXPIRED_NAME = "Maria Clara Santos";
        private static final String DEMO_EXPIRED_EMAIL = "maria.santos@ue.edu.ph";
        private static final String DEMO_EXPIRED_DEPT = "College of Engineering";

        static {
            LocalDateTime expiredApprovedAt = LocalDateTime.now().minusDays(380);
            fallbackApps.add(new VehicleApplication(
                    901, DEMO_EXPIRED_ID, DEMO_EXPIRED_NAME,
                    "Toyota|Vios|2022|Pearl White", "NCI-4821",
                    "Parking Sticker", "My Own Vehicle", "APPROVED",
                    expiredApprovedAt.format(DATE_FMT), expiredApprovedAt
            ));
            fallbackMessages.add(new InboxMessage(
                    "NOTICE", "Security Office", DEMO_EXPIRED_ID,
                    "Sticker Validity Expired — Renewal Required (NCI-4821)",
                    "Your UE Campus Vehicle Sticker for Toyota Vios (NCI-4821) has exceeded its 1-year validity period. Please open your dashboard and click 'Sticker expired Click here to reapply' to upload updated documents.",
                    LocalDateTime.now().minusDays(15).format(SHORT_FMT),
                    LocalDateTime.now().minusDays(15).format(FULL_FMT)
            ));
            fallbackMessages.add(new InboxMessage(
                    "ANNOUNCEMENT", "Security Office", "ALL", "AY 2026-2027 Campus Parking Guidelines",
                    "Please ensure your sticker is properly placed on the upper-left windshield (cars) or front mudguard (motorcycles). Vehicles parked in non-designated zones will be clamped.",
                    "Oct 28, 2026", "Oct 28, 2026 at 08:00 AM"
            ));
        }

        public static Connection getConnection() throws SQLException {
            return DriverManager.getConnection(URL, USER, PASS);
        }

        private static void ensureDemoExpiredAccount(Connection conn) {
            try {
                try (PreparedStatement chkUser = conn.prepareStatement("SELECT 1 FROM users WHERE id = ? OR LOWER(email) = LOWER(?) LIMIT 1")) {
                    chkUser.setString(1, DEMO_EXPIRED_ID);
                    chkUser.setString(2, DEMO_EXPIRED_EMAIL);
                    if (!chkUser.executeQuery().next()) {
                        try (PreparedStatement insUser = conn.prepareStatement(
                                "INSERT INTO users (id, name, email, password, role, department) VALUES (?, ?, ?, ?, 'STUDENT', ?)")) {
                            insUser.setString(1, DEMO_EXPIRED_ID);
                            insUser.setString(2, DEMO_EXPIRED_NAME);
                            insUser.setString(3, DEMO_EXPIRED_EMAIL);
                            insUser.setString(4, "student123");
                            insUser.setString(5, DEMO_EXPIRED_DEPT);
                            insUser.executeUpdate();
                        }
                    }
                }
                try (PreparedStatement chkApp = conn.prepareStatement("SELECT 1 FROM vehicle_applications WHERE owner_id = ? LIMIT 1")) {
                    chkApp.setString(1, DEMO_EXPIRED_ID);
                    if (!chkApp.executeQuery().next()) {
                        try (PreparedStatement insApp = conn.prepareStatement(
                                "INSERT INTO vehicle_applications (owner_id, owner_name, vehicle_model, plate_number, pass_type, ownership, status, created_at) " +
                                "VALUES (?, ?, 'Toyota|Vios|2022|Pearl White', 'NCI-4821', 'Parking Sticker', 'My Own Vehicle', 'APPROVED', DATE_SUB(NOW(), INTERVAL 380 DAY))")) {
                            insApp.setString(1, DEMO_EXPIRED_ID);
                            insApp.setString(2, DEMO_EXPIRED_NAME);
                            insApp.executeUpdate();
                        }
                    }
                }
                try (PreparedStatement chkMsg = conn.prepareStatement("SELECT 1 FROM messages WHERE recipient_id = ? LIMIT 1")) {
                    chkMsg.setString(1, DEMO_EXPIRED_ID);
                    if (!chkMsg.executeQuery().next()) {
                        try (PreparedStatement insMsg = conn.prepareStatement(
                                "INSERT INTO messages (sender_name, recipient_id, subject, body, is_announcement) VALUES ('Security Office', ?, ?, ?, false)")) {
                            insMsg.setString(1, DEMO_EXPIRED_ID);
                            insMsg.setString(2, "Sticker Validity Expired — Renewal Required (NCI-4821)");
                            insMsg.setString(3, "Your UE Campus Vehicle Sticker for Toyota Vios (NCI-4821) has exceeded its 1-year validity period. Please open your dashboard and click 'Sticker expired Click here to reapply' to upload updated documents.");
                            insMsg.executeUpdate();
                        }
                    }
                }
            } catch (SQLException ignored) {}
        }

        public static void initDatabase() {
            try (Connection conn = getConnection(); Statement stmt = conn.createStatement()) {
                try {
                    stmt.executeUpdate("DELETE m1 FROM messages m1 INNER JOIN messages m2 WHERE m1.id > m2.id AND m1.subject = m2.subject AND m1.body = m2.body AND m1.recipient_id = m2.recipient_id");
                } catch (SQLException ignored) {}
                ensureDemoExpiredAccount(conn);
                System.out.println("Connected to XAMPP MySQL Database.");
            } catch (SQLException e) {
                System.err.println("XAMPP MySQL offline. Running in local fallback mode.");
            }
        }

        public static User authenticate(String email, String password) {
            try (Connection conn = getConnection()) {
                if (DEMO_EXPIRED_EMAIL.equalsIgnoreCase(email) && "student123".equals(password)) {
                    ensureDemoExpiredAccount(conn);
                }
                try (PreparedStatement ps = conn.prepareStatement("SELECT * FROM users WHERE LOWER(email) = LOWER(?) AND password = ?")) {
                    ps.setString(1, email);
                    ps.setString(2, password);
                    ResultSet rs = ps.executeQuery();
                    if (rs.next()) {
                        return new User(rs.getString("id"), rs.getString("name"), rs.getString("email"), rs.getString("role"), rs.getString("department"));
                    }
                }
                if (DEMO_EXPIRED_EMAIL.equalsIgnoreCase(email) && "student123".equals(password)) {
                    return new User(DEMO_EXPIRED_ID, DEMO_EXPIRED_NAME, DEMO_EXPIRED_EMAIL, "STUDENT", DEMO_EXPIRED_DEPT);
                }
            } catch (SQLException e) {
                if ("admin@ue.edu.ph".equalsIgnoreCase(email) && "admin123".equals(password))
                    return new User("ADMIN-001", "Security Desk Admin", email, "ADMIN", "Campus Security & Safety Office");
                if ("juan.delacruz@ue.edu.ph".equalsIgnoreCase(email) && "student123".equals(password))
                    return new User("2021-09876", "Juan Dela Cruz", email, "STUDENT", "College of Computer Studies & Engineering");
                if (DEMO_EXPIRED_EMAIL.equalsIgnoreCase(email) && "student123".equals(password))
                    return new User(DEMO_EXPIRED_ID, DEMO_EXPIRED_NAME, DEMO_EXPIRED_EMAIL, "STUDENT", DEMO_EXPIRED_DEPT);
            }
            return null;
        }

        public static String getUserIdByEmail(String email) {
            if (email == null || email.isBlank()) return "";
            String clean = email.trim();
            String res = querySingleString("SELECT id FROM users WHERE LOWER(email) = LOWER(?) LIMIT 1", clean);
            if (res != null) return res;
            if ("juan.delacruz@ue.edu.ph".equalsIgnoreCase(clean)) return "2021-09876";
            if (DEMO_EXPIRED_EMAIL.equalsIgnoreCase(clean)) return DEMO_EXPIRED_ID;
            return clean;
        }

        public static String getUserEmailById(String userIdOrEmail) {
            if (userIdOrEmail == null || userIdOrEmail.isBlank()) return "";
            String clean = userIdOrEmail.trim();
            if (clean.contains("@")) return clean;
            String res = querySingleString("SELECT email FROM users WHERE id = ? LIMIT 1", clean);
            if (res != null) return res;
            if ("2021-09876".equalsIgnoreCase(clean)) return "juan.delacruz@ue.edu.ph";
            if (DEMO_EXPIRED_ID.equalsIgnoreCase(clean)) return DEMO_EXPIRED_EMAIL;
            return clean;
        }

        public static String getRejectionReason(int appId, String ownerId) {
            try (Connection conn = getConnection();
                 PreparedStatement ps = conn.prepareStatement("SELECT rejection_reason FROM vehicle_applications WHERE id = ? OR owner_id = ? ORDER BY id DESC LIMIT 1")) {
                ps.setInt(1, appId);
                ps.setString(2, ownerId);
                ResultSet rs = ps.executeQuery();
                if (rs.next() && rs.getString("rejection_reason") != null && !rs.getString("rejection_reason").isBlank()) {
                    return rs.getString("rejection_reason");
                }
            } catch (SQLException ignored) {}
            return PortalController.getFallbackRejectionReason(ownerId);
        }

        public static VehicleApplication getUserApplication(String userId) {
            try (Connection conn = getConnection();
                 PreparedStatement ps = conn.prepareStatement("SELECT * FROM vehicle_applications WHERE owner_id = ? ORDER BY id DESC LIMIT 1")) {
                ps.setString(1, userId);
                ResultSet rs = ps.executeQuery();
                if (rs.next()) return mapApplication(rs);
            } catch (SQLException e) {
                synchronized (fallbackApps) {
                    return fallbackApps.stream().filter(a -> a.getOwnerId().equals(userId)).findFirst().orElse(null);
                }
            }
            return null;
        }

        public static void createApplication(String ownerId, String ownerName, String packedVehicle, String plate, String passType, String ownership) {
            try (Connection conn = getConnection();
                 PreparedStatement ps = conn.prepareStatement("INSERT INTO vehicle_applications (owner_id, owner_name, vehicle_model, plate_number, pass_type, ownership) VALUES (?, ?, ?, ?, ?, ?)")) {
                ps.setString(1, ownerId);
                ps.setString(2, ownerName);
                ps.setString(3, packedVehicle);
                ps.setString(4, plate);
                ps.setString(5, passType);
                ps.setString(6, ownership);
                ps.executeUpdate();
            } catch (SQLException e) {
                LocalDateTime now = LocalDateTime.now();
                synchronized (fallbackApps) {
                    fallbackApps.add(0, new VehicleApplication(fallbackApps.size() + 1, ownerId, ownerName, packedVehicle, plate, passType, ownership, "PENDING", now.format(DATE_FMT), now));
                }
            }
        }

        public static void reapplyApplication(int oldAppId, String ownerId, String ownerName, String packedVehicle, String plate, String passType, String ownership) {
            deleteApplication(oldAppId, ownerId);
            createApplication(ownerId, ownerName, packedVehicle, plate, passType, ownership);
        }

        public static void deleteApplication(int appId, String ownerId) {
            if (!execUpdate("DELETE FROM vehicle_applications WHERE id = ? OR owner_id = ?", ps -> { ps.setInt(1, appId); ps.setString(2, ownerId); })) {
                synchronized (fallbackApps) {
                    fallbackApps.removeIf(a -> a.idProperty().get() == appId || a.getOwnerId().equals(ownerId));
                }
            }
        }

        public static void updateApplicationStatus(int appId, String status, String reason) {
            boolean updated;
            if ("APPROVED".equalsIgnoreCase(status)) {
                updated = execUpdate("UPDATE vehicle_applications SET status = ?, rejection_reason = ?, created_at = CURRENT_TIMESTAMP WHERE id = ?",
                        ps -> { ps.setString(1, status); ps.setString(2, reason); ps.setInt(3, appId); });
                if (!updated) {
                    updated = execUpdate("UPDATE vehicle_applications SET status = ?, rejection_reason = ? WHERE id = ?",
                            ps -> { ps.setString(1, status); ps.setString(2, reason); ps.setInt(3, appId); });
                }
            } else {
                updated = execUpdate("UPDATE vehicle_applications SET status = ?, rejection_reason = ? WHERE id = ?",
                        ps -> { ps.setString(1, status); ps.setString(2, reason); ps.setInt(3, appId); });
            }
            if (!updated) {
                LocalDateTime now = LocalDateTime.now();
                synchronized (fallbackApps) {
                    fallbackApps.stream().filter(a -> a.idProperty().get() == appId).forEach(a -> {
                        a.statusProperty().set(status);
                        if ("APPROVED".equalsIgnoreCase(status)) a.setApprovalTimestamp(now, now.format(DATE_FMT));
                    });
                }
            }
        }

        public static ObservableList<VehicleApplication> getAllApplications() {
            ObservableList<VehicleApplication> list = FXCollections.observableArrayList();
            try (Connection conn = getConnection(); Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT * FROM vehicle_applications ORDER BY id DESC")) {
                while (rs.next()) list.add(mapApplication(rs));
                return list;
            } catch (SQLException e) {
                synchronized (fallbackApps) {
                    list.addAll(fallbackApps);
                }
                return list;
            }
        }

        public static void sendMessage(String sender, String recipient, String subject, String body, boolean isAnnouncement) {
            String target = (!isAnnouncement && recipient != null && recipient.contains("@")) ? getUserIdByEmail(recipient) : recipient;
            if (!execUpdate("INSERT INTO messages (sender_name, recipient_id, subject, body, is_announcement) VALUES (?, ?, ?, ?, ?)", ps -> {
                ps.setString(1, sender);
                ps.setString(2, target);
                ps.setString(3, subject);
                ps.setString(4, body);
                ps.setBoolean(5, isAnnouncement);
            })) {
                String cat = isAnnouncement ? "ANNOUNCEMENT" : ("ADMIN".equalsIgnoreCase(target) ? "INQUIRY"
                        : (subject.toLowerCase().contains("approved") ? "APPROVAL" : (subject.toLowerCase().contains("rejected") ? "REJECTION" : "NOTICE")));
                LocalDateTime now = LocalDateTime.now();
                fallbackMessages.add(0, new InboxMessage(cat, sender, target, subject, body, now.format(SHORT_FMT), now.format(FULL_FMT)));
            }
        }

        public static List<InboxMessage> getUserMessages(String userId, String userEmail) {
            String safeEmail = userEmail != null ? userEmail : "";
            return queryMessages(
                    "SELECT sender_name, recipient_id, subject, body, is_announcement, created_at FROM messages WHERE recipient_id = ? OR LOWER(recipient_id) = LOWER(?) OR recipient_id = 'ALL' ORDER BY id DESC",
                    ps -> { ps.setString(1, userId); ps.setString(2, safeEmail); },
                    rs -> rs.getBoolean("is_announcement") ? "ANNOUNCEMENT" : (rs.getString("subject").toLowerCase().contains("approved") ? "APPROVAL" : (rs.getString("subject").toLowerCase().contains("rejected") ? "REJECTION" : "NOTICE")),
                    m -> m.category + "|" + m.subject.trim() + "|" + m.body.trim(),
                    m -> m.recipient.equalsIgnoreCase(userId) || m.recipient.equalsIgnoreCase(safeEmail) || "ALL".equalsIgnoreCase(m.recipient)
            );
        }

        public static List<InboxMessage> getUserSentMessages(String userId, String userName) {
            return queryMessages(
                    "SELECT sender_name, recipient_id, subject, body, created_at FROM messages WHERE recipient_id = 'ADMIN' AND (sender_name LIKE ? OR sender_name = ?) ORDER BY id DESC",
                    ps -> { ps.setString(1, "%" + userId + "%"); ps.setString(2, userName); },
                    rs -> "SENT LETTER",
                    m -> "SENT|" + m.subject.trim() + "|" + m.body.trim(),
                    m -> "ADMIN".equalsIgnoreCase(m.recipient) && (m.sender.contains(userId) || m.sender.equalsIgnoreCase(userName))
            );
        }

        public static List<InboxMessage> getAdminReceivedMessages() {
            return queryMessages(
                    "SELECT sender_name, recipient_id, subject, body, created_at FROM messages WHERE recipient_id = 'ADMIN' ORDER BY id DESC",
                    null,
                    rs -> "INQUIRY",
                    m -> "INQ|" + m.sender + "|" + m.subject.trim() + "|" + m.body.trim(),
                    m -> "ADMIN".equalsIgnoreCase(m.recipient)
            );
        }

        public static List<InboxMessage> getAdminSentMessages() {
            return queryMessages(
                    "SELECT sender_name, recipient_id, subject, body, is_announcement, created_at FROM messages WHERE recipient_id != 'ADMIN' ORDER BY id DESC",
                    null,
                    rs -> rs.getBoolean("is_announcement") ? "ANNOUNCEMENT" : "SENT NOTICE",
                    m -> m.category + "|" + m.recipient + "|" + m.subject.trim(),
                    m -> !"ADMIN".equalsIgnoreCase(m.recipient)
            );
        }

        // --- Private JDBC Helpers ---

        @FunctionalInterface
        private interface SqlConsumer { void accept(PreparedStatement ps) throws SQLException; }

        @FunctionalInterface
        private interface SqlFunction<R> { R apply(ResultSet rs) throws SQLException; }

        private static VehicleApplication mapApplication(ResultSet rs) throws SQLException {
            Timestamp ts = rs.getTimestamp("created_at");
            LocalDateTime ldt = ts != null ? ts.toLocalDateTime() : LocalDateTime.now();
            String date = ldt.format(DATE_FMT);
            return new VehicleApplication(rs.getInt("id"), rs.getString("owner_id"), rs.getString("owner_name"),
                    rs.getString("vehicle_model"), rs.getString("plate_number"), rs.getString("pass_type"),
                    rs.getString("ownership"), rs.getString("status"), date, ldt);
        }

        private static String querySingleString(String sql, String param) {
            try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, param);
                ResultSet rs = ps.executeQuery();
                if (rs.next()) return rs.getString(1);
            } catch (SQLException ignored) {}
            return null;
        }

        private static boolean execUpdate(String sql, SqlConsumer binder) {
            try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
                if (binder != null) binder.accept(ps);
                ps.executeUpdate();
                return true;
            } catch (SQLException e) {
                return false;
            }
        }

        private static List<InboxMessage> queryMessages(String sql, SqlConsumer binder, SqlFunction<String> catFn, Function<InboxMessage, String> dedupeKey, Predicate<InboxMessage> fallbackFilter) {
            Map<String, InboxMessage> unique = new LinkedHashMap<>();
            try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
                if (binder != null) binder.accept(ps);
                ResultSet rs = ps.executeQuery();
                while (rs.next()) {
                    Timestamp ts = rs.getTimestamp("created_at");
                    LocalDateTime ldt = ts != null ? ts.toLocalDateTime() : LocalDateTime.now();
                    InboxMessage msg = new InboxMessage(catFn.apply(rs), rs.getString("sender_name"), rs.getString("recipient_id"),
                            rs.getString("subject"), rs.getString("body"), ldt.format(SHORT_FMT), ldt.format(FULL_FMT));
                    unique.putIfAbsent(dedupeKey.apply(msg), msg);
                }
            } catch (SQLException e) {
                synchronized (fallbackMessages) {
                    fallbackMessages.stream().filter(fallbackFilter).forEach(m -> unique.putIfAbsent(dedupeKey.apply(m), m));
                }
            }
            return new ArrayList<>(unique.values());
        }
    }
}
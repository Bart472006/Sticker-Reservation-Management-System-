package com.ue.vehiclestickersystem;
import com.ue.vehiclestickersystem.Main.*;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.effect.GaussianBlur;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.*;
import javafx.scene.shape.Rectangle;
import javafx.stage.FileChooser;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class PortalController {

    // =========================================================================
    // STATE, CONSTANTS & FXML INJECTIONS
    // =========================================================================
    private static volatile int officeStickerStock = 498;
    private static final Map<String, Map<String, File>> userUploadedDocs = new ConcurrentHashMap<>();
    private static final Map<String, String> userContactNumbers = new ConcurrentHashMap<>();
    private static final Map<String, String> userRejectionReasons = new ConcurrentHashMap<>();
    private static final Map<String, String> userApprovalTimestamps = new ConcurrentHashMap<>();
    private static final Map<String, CachedRemoteDocs> remoteDocListCache = new ConcurrentHashMap<>();
    private static final long REMOTE_DOC_CACHE_TTL_MS = 8_000L;
    private static final DateTimeFormatter APPROVAL_FMT = DateTimeFormatter.ofPattern("M/d/yyyy, h:mm:ss a");
    private static final String ATTACHMENT_MARKER = "\n[[ATTACHMENT:";
    // Connect your XAMPP server/database host here:
    // - Keep "localhost" if XAMPP is running on this same PC (uploads will save to C:\xampp\htdocs\ue_sticker_DB\uploads).
    // - Or replace "localhost" with your host PC's LAN IP (e.g., "192.168.1.10") or Tailscale IP to upload/fetch files via api.php from another PC.
    private static final String SERVER_IP = "localhost";

    private static final class CachedRemoteDocs {
        final List<String> files;
        final long fetchedAtMs;
        CachedRemoteDocs(List<String> files) {
            this.files = List.copyOf(files);
            this.fetchedAtMs = System.currentTimeMillis();
        }
        boolean isFresh() {
            return System.currentTimeMillis() - fetchedAtMs < REMOTE_DOC_CACHE_TTL_MS;
        }
    }

    private static final Map<String, List<String>> DOC_ALIASES = Map.of(
            "CR / OR Document", List.of("cr_or", "or_cr", "cror", "orcr", "cr___or_document", "cr_or_doc", "cr_or_document", "cr", "or"),
            "Driver's License", List.of("drivers_license", "driver_license", "driver_s_license", "license", "drivers_license_doc", "driver", "dl", "licen"),
            "UE Registration Form", List.of("ue_registration_form", "registration_form", "reg_form", "regform", "ue_reg_form", "ue_reg", "reg", "form"),
            "Vehicle Photo", List.of("vehicle_photo", "vehiclephoto", "car_photo", "vehicle", "photo", "car"),
            "Owner's Valid ID & Approval Letter", List.of("owner_s_valid_id___approval_letter", "owner_id", "approval_letter", "authorization", "auth_letter", "owner_auth", "owner_valid_id", "valid_id", "owner", "auth", "letter")
    );

    @FXML private Button btnNavDashboard, btnNavInbox, btnNavSupport;
    @FXML private ScrollPane userScrollPane;
    @FXML private VBox userContentRoot;
    @FXML private Label lblWelcome, lblUserInfo, lblStockRemaining;
    @FXML private HBox vboxVehicleContainer;

    @FXML private Button btnAdminNavDashboard, btnAdminNavRecords, btnAdminNavInbox;
    @FXML private Label lblRecordsBadge, lblInboxBadge;
    @FXML private ScrollPane adminScrollPane;
    @FXML private VBox adminContentRoot;

    private String adminRecordsFilter = "ALL";
    private String adminRecordsSearch = "";
    private Integer selectedRecordAppId = null;
    private List<VehicleApplication> cachedAdminApps = Collections.emptyList();
    private VehicleApplication cachedUserApp = null;

    // Generation tokens to discard stale async callbacks if user switches tabs quickly
    private int userNavToken = 0;
    private int adminNavToken = 0;
    private int inspectorToken = 0;

    public static String getFallbackRejectionReason(String ownerId) {
        return userRejectionReasons.getOrDefault(ownerId, "");
    }

    // Initializes either the Student or Security Admin dashboard depending on the loaded FXML
    @FXML
    public void initialize() {
        if (userContentRoot != null && Main.getCurrentUser() != null) renderDashboardView();
        if (adminContentRoot != null) {
            updateAdminSidebarBadges();
            renderAdminDashboardTab();
        }
    }

    @FXML
    private void handleLogout() {
        userNavToken++;
        adminNavToken++;
        inspectorToken++;
        cachedUserApp = null;
        cachedAdminApps = Collections.emptyList();
        Main.setCurrentUser(null);
        Main.showLoginView();
    }

    // =========================================================================
    // REUSABLE UI & MODAL BUILDERS
    // =========================================================================

    // Wraps any dialog card inside a blurred full-window overlay scrim and returns its close callback
    private static Runnable openInAppModal(VBox dialogCard, Runnable onCloseCallback) {
        Scene scene = Main.getPrimaryStage().getScene();
        if (scene == null) return () -> {};

        Parent currentRoot = scene.getRoot();
        StackPane hostStack;
        Node backgroundLayer;
        if (currentRoot instanceof StackPane && "appOverlayHost".equals(currentRoot.getId())) {
            hostStack = (StackPane) currentRoot;
            backgroundLayer = hostStack.getChildren().get(0);
        } else {
            hostStack = new StackPane();
            hostStack.setId("appOverlayHost");
            backgroundLayer = currentRoot;
            scene.setRoot(hostStack);
            hostStack.getChildren().add(backgroundLayer);
        }

        dialogCard.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        dialogCard.setStyle(dialogCard.getStyle() + "; -fx-background-radius: 16; -fx-border-radius: 16; -fx-effect: dropshadow(three-pass-box, rgba(15,23,42,0.32), 28, 0, 0, 10);");
        Rectangle clip = new Rectangle();
        clip.setArcWidth(32);
        clip.setArcHeight(32);
        clip.widthProperty().bind(dialogCard.widthProperty());
        clip.heightProperty().bind(dialogCard.heightProperty());
        dialogCard.setClip(clip);

        StackPane scrim = new StackPane(dialogCard);
        scrim.setAlignment(Pos.CENTER);
        scrim.setPadding(new Insets(24));
        scrim.setStyle("-fx-background-color: rgba(15, 23, 42, 0.45);");
        backgroundLayer.setEffect(new GaussianBlur(10));
        hostStack.getChildren().add(scrim);

        final boolean[] closed = {false};
        return () -> {
            if (closed[0]) return;
            closed[0] = true;
            hostStack.getChildren().remove(scrim);
            if (hostStack.getChildren().size() <= 1) backgroundLayer.setEffect(null);
            if (onCloseCallback != null) onCloseCallback.run();
        };
    }

    // Builds a standard crimson-header modal shell with an 'X' close button
    private VBox buildModalShell(double width, String titleText, Runnable onClose) {
        VBox root = new VBox();
        root.setPrefWidth(width);
        root.setStyle("-fx-background-color: #ffffff;");
        if (PortalController.class.getResource("styles.css") != null) {
            root.getStylesheets().add(PortalController.class.getResource("styles.css").toExternalForm());
        }
        Button btnX = styledButton("✕", "-fx-background-color: transparent; -fx-text-fill: #ffffff; -fx-font-size: 15px; -fx-font-weight: bold; -fx-cursor: hand;", e -> onClose.run());
        HBox header = new HBox(10, styledLabel(titleText, "-fx-text-fill: #ffffff; -fx-font-size: 15px; -fx-font-weight: bold;", false), hSpacer(), btnX);
        header.setAlignment(Pos.CENTER_LEFT);
        header.setPadding(new Insets(16, 22, 16, 22));
        header.setStyle("-fx-background-color: #b71c1c;");
        root.getChildren().add(header);
        return root;
    }

    private static Label styledLabel(String text, String style, boolean wrap) {
        Label l = new Label(text);
        l.setStyle(style);
        l.setWrapText(wrap);
        return l;
    }

    private static Button styledButton(String text, String style, javafx.event.EventHandler<javafx.event.ActionEvent> action) {
        Button b = new Button(text);
        b.setStyle(style);
        b.setMinWidth(Region.USE_PREF_SIZE);
        if (action != null) b.setOnAction(action);
        return b;
    }

    private static Region hSpacer() {
        Region r = new Region();
        HBox.setHgrow(r, Priority.ALWAYS);
        return r;
    }

    private static Region hLine() {
        Region line = new Region();
        line.setPrefHeight(1);
        line.setMinHeight(1);
        line.setMaxHeight(1);
        line.setMaxWidth(Double.MAX_VALUE);
        line.setStyle("-fx-background-color: #e2e8f0;");
        return line;
    }

    private static ImageView coverImageView(Image img, double boxW, double boxH) {
        ImageView iv = new ImageView(img);
        double scale = Math.max(boxW / img.getWidth(), boxH / img.getHeight());
        iv.setFitWidth(img.getWidth() * scale);
        iv.setFitHeight(img.getHeight() * scale);
        iv.setPreserveRatio(true);
        iv.setSmooth(true);
        return iv;
    }

    private static void showAlert(Alert.AlertType type, String title, String content) {
        Alert alert = new Alert(type);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(content);
        alert.showAndWait();
    }

    private String capitalize(String text) {
        return (text == null || text.isEmpty()) ? "" : text.substring(0, 1).toUpperCase() + text.substring(1).toLowerCase();
    }

    private VBox createLoadingCard(String message) {
        ProgressIndicator spinner = new ProgressIndicator();
        spinner.setMaxSize(34, 34);
        spinner.setPrefSize(34, 34);
        spinner.setStyle("-fx-progress-color: #b71c1c;");
        VBox card = new VBox(12, spinner,
                styledLabel(message, "-fx-font-size: 13px; -fx-font-weight: bold; -fx-text-fill: #334155;", false),
                styledLabel("Fetching data asynchronously in the background...", "-fx-font-size: 11.5px; -fx-text-fill: #94a3b8;", false));
        card.setAlignment(Pos.CENTER);
        card.setPadding(new Insets(46, 24, 46, 24));
        card.getStyleClass().add("header-card");
        return card;
    }

    private HBox createInlineLoadingRow(String message) {
        ProgressIndicator spinner = new ProgressIndicator();
        spinner.setMaxSize(20, 20);
        spinner.setPrefSize(20, 20);
        spinner.setStyle("-fx-progress-color: #b71c1c;");
        HBox box = new HBox(10, spinner, styledLabel(message, "-fx-font-size: 12px; -fx-font-weight: bold; -fx-text-fill: #64748b;", false));
        box.setAlignment(Pos.CENTER);
        box.setPadding(new Insets(28, 16, 28, 16));
        return box;
    }

    // =========================================================================
    // FILE STORAGE, REMOTE TAILSCALE UPLOAD/FETCH & ATTACHMENT HELPERS
    // =========================================================================

    private static boolean isRemoteHost() {
        return SERVER_IP != null && !SERVER_IP.trim().isEmpty()
                && !"localhost".equalsIgnoreCase(SERVER_IP.trim()) && !"127.0.0.1".equals(SERVER_IP.trim());
    }

    private static String getBaseServerUrl() {
        return "http://" + SERVER_IP.trim() + "/ue_sticker_DB";
    }

    private static Path getLocalCacheDirectory() throws IOException {
        Path dir = Paths.get(System.getProperty("java.io.tmpdir"), "ue_sticker_uploads_cache");
        Files.createDirectories(dir);
        return dir;
    }

    // Resolves local XAMPP htdocs/ue_sticker_DB/uploads or falls back to a temp cache folder
    private static Path getUploadsDirectory() throws IOException {
        if (isRemoteHost()) return getLocalCacheDirectory();
        for (String drive : new String[]{"C:", "D:", "E:", "F:"}) {
            Path candidate = Paths.get(drive, "xampp", "htdocs", "ue_sticker_DB", "uploads");
            if (Files.exists(candidate)) return candidate;
        }
        for (String drive : new String[]{"C:", "D:", "E:", "F:"}) {
            Path htdocs = Paths.get(drive, "xampp", "htdocs");
            if (Files.exists(htdocs)) {
                Path candidate = htdocs.resolve("ue_sticker_DB").resolve("uploads");
                Files.createDirectories(candidate);
                return candidate;
            }
        }
        return getLocalCacheDirectory();
    }

    private static Path getMessageAttachmentsDirectory() throws IOException {
        Path dir = getUploadsDirectory().resolve("msg_attachments");
        Files.createDirectories(dir);
        return dir;
    }

    private static String sanitizeFileNamePart(String input) {
        return input.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    // Saves an uploaded credential locally to XAMPP uploads or POSTs it to api.php over Tailscale
    private static File saveToUploadsFolder(String userId, String docKey, File sourceFile) throws IOException {
        remoteDocListCache.remove(userId.trim());
        if (isRemoteHost()) {
            String remoteName = uploadFileToRemoteServer(userId, docKey, sourceFile);
            Path cached = getLocalCacheDirectory().resolve(remoteName);
            Files.copy(sourceFile.toPath(), cached, StandardCopyOption.REPLACE_EXISTING);
            userUploadedDocs.computeIfAbsent(userId, k -> new ConcurrentHashMap<>()).put(docKey, cached.toFile());
            remoteDocListCache.remove(userId.trim());
            return cached.toFile();
        }
        Path uploadDir = getUploadsDirectory();
        String prefix = sanitizeFileNamePart(userId) + "_" + sanitizeFileNamePart(docKey) + "_";
        try (Stream<Path> existing = Files.list(uploadDir)) {
            existing.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase().startsWith(prefix.toLowerCase()))
                    .forEach(p -> { try { Files.deleteIfExists(p); } catch (IOException ignored) {} });
        }
        Path target = uploadDir.resolve(prefix + sanitizeFileNamePart(sourceFile.getName()));
        Files.copy(sourceFile.toPath(), target, StandardCopyOption.REPLACE_EXISTING);
        userUploadedDocs.computeIfAbsent(userId, k -> new ConcurrentHashMap<>()).put(docKey, target.toFile());
        return target.toFile();
    }

    // Sends a multipart/form-data HTTP POST to api.php?action=upload_doc for remote PCs
    private static String uploadFileToRemoteServer(String userId, String docKey, File sourceFile) throws IOException {
        String boundary = "----UEStickerBoundary" + System.currentTimeMillis();
        HttpURLConnection conn = (HttpURLConnection) URI.create(getBaseServerUrl() + "/api.php?action=upload_doc").toURL().openConnection();
        conn.setDoOutput(true);
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(8000);
        conn.setReadTimeout(20000);
        conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);

        try (DataOutputStream out = new DataOutputStream(conn.getOutputStream())) {
            out.writeBytes("--" + boundary + "\r\nContent-Disposition: form-data; name=\"user_id\"\r\n\r\n" + userId + "\r\n");
            out.writeBytes("--" + boundary + "\r\nContent-Disposition: form-data; name=\"doc_key\"\r\n\r\n" + docKey + "\r\n");
            out.writeBytes("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"" + sanitizeFileNamePart(sourceFile.getName()) + "\"\r\nContent-Type: application/octet-stream\r\n\r\n");
            Files.copy(sourceFile.toPath(), out);
            out.writeBytes("\r\n--" + boundary + "--\r\n");
        }

        int status = conn.getResponseCode();
        InputStream stream = (status >= 200 && status < 300) ? conn.getInputStream() : conn.getErrorStream();
        String resp = stream != null ? new String(stream.readAllBytes(), StandardCharsets.UTF_8) : "";
        if (status < 200 || status >= 300 || !resp.contains("\"success\":true")) throw new IOException("Remote upload failed: " + resp);

        int idx = resp.indexOf("\"file\":\"");
        if (idx >= 0 && resp.indexOf("\"", idx + 8) > idx + 8) return resp.substring(idx + 8, resp.indexOf("\"", idx + 8));
        return sanitizeFileNamePart(userId) + "_" + sanitizeFileNamePart(docKey) + "_" + sanitizeFileNamePart(sourceFile.getName());
    }

    // Message attachment persistence & body tag parsing helpers
    private static File saveMessageAttachment(File source) throws IOException {
        Path target = getMessageAttachmentsDirectory().resolve(System.currentTimeMillis() + "_" + sanitizeFileNamePart(source.getName()));
        Files.copy(source.toPath(), target, StandardCopyOption.REPLACE_EXISTING);
        return target.toFile();
    }

    private static File resolveMessageAttachment(String storedName) {
        if (storedName == null || storedName.trim().isEmpty()) return null;
        try {
            Path p = getMessageAttachmentsDirectory().resolve(storedName.trim());
            return Files.exists(p) ? p.toFile() : null;
        } catch (IOException e) { return null; }
    }

    private static String attachFileToMessageBody(String body, File attachment) {
        return attachment == null ? body : body + ATTACHMENT_MARKER + attachment.getName() + "]]";
    }

    private static String extractCleanMessageBody(String rawBody) {
        if (rawBody == null) return "";
        int idx = rawBody.indexOf(ATTACHMENT_MARKER);
        return idx >= 0 ? rawBody.substring(0, idx).trim() : rawBody;
    }

    private static String extractAttachmentFileName(String rawBody) {
        if (rawBody == null) return null;
        int idx = rawBody.indexOf(ATTACHMENT_MARKER);
        int end = idx >= 0 ? rawBody.indexOf("]]", idx + ATTACHMENT_MARKER.length()) : -1;
        return end > idx ? rawBody.substring(idx + ATTACHMENT_MARKER.length(), end).trim() : null;
    }

    private HBox createMessageAttachmentCard(String storedFileName) {
        File file = resolveMessageAttachment(storedFileName);
        int u = storedFileName != null ? storedFileName.indexOf('_') : -1;
        String displayName = (u > 0 && u < storedFileName.length() - 1) ? storedFileName.substring(u + 1) : storedFileName;

        VBox textCol = new VBox(2,
                styledLabel("Attached File: " + displayName, "-fx-font-size: 12.5px; -fx-font-weight: bold; -fx-text-fill: #881337;", true),
                styledLabel(file != null ? "Saved in uploads/msg_attachments • Click Open to view" : "Attachment reference", "-fx-font-size: 11px; -fx-text-fill: #9f1239;", false));
        HBox.setHgrow(textCol, Priority.ALWAYS);

        Button btnOpen = styledButton("Open File", "-fx-background-color: #b71c1c; -fx-text-fill: white; -fx-font-weight: bold; -fx-font-size: 11.5px; -fx-padding: 6 14; -fx-background-radius: 7; -fx-cursor: hand;", null);
        btnOpen.setOnAction(e -> {
            if (file == null || !file.exists()) {
                showAlert(Alert.AlertType.WARNING, "Attachment Not Found", "Could not locate attached file: " + displayName);
                return;
            }
            btnOpen.setDisable(true);
            btnOpen.setText("Opening...");
            Main.runAsync(() -> loadImageFromFile(file), img -> {
                btnOpen.setDisable(false);
                btnOpen.setText("Open File");
                if (img != null) {
                    showLoadedDocumentPreview("Attachment: " + displayName, file, img);
                } else {
                    try { Main.openExternalDocument(file.toURI().toString()); }
                    catch (Exception ex) { showAlert(Alert.AlertType.INFORMATION, "Attachment Location", "File: " + displayName + "\nPath: " + file.getAbsolutePath()); }
                }
            }, err -> {
                btnOpen.setDisable(false);
                btnOpen.setText("Open File");
                showAlert(Alert.AlertType.ERROR, "Attachment Error", err.getMessage());
            });
        });

        HBox card = new HBox(12, styledLabel("📎", "-fx-font-size: 16px;", false), textCol, btnOpen);
        card.setAlignment(Pos.CENTER_LEFT);
        card.setPadding(new Insets(12, 16, 12, 16));
        card.setStyle("-fx-background-color: #fff1f2; -fx-border-color: #fecdd3; -fx-border-radius: 10; -fx-background-radius: 10;");
        return card;
    }

    // Matches uploaded filenames on disk or remote server to a specific document slot
    private static String matchFileNameForDocKey(List<String> fileNames, String userPrefixLower, String docKey) {
        List<String> aliases = new ArrayList<>();
        aliases.add(sanitizeFileNamePart(docKey).toLowerCase() + "_");
        DOC_ALIASES.getOrDefault(docKey, List.of()).forEach(a -> aliases.add(a + "_"));

        // Pass 1: Exact prefix match
        for (String name : fileNames) {
            String lower = name.toLowerCase();
            if (lower.startsWith(userPrefixLower)) {
                String rem = lower.substring(userPrefixLower.length());
                if (aliases.stream().anyMatch(rem::startsWith)) return name;
            }
        }
        // Pass 2: Keyword substring match
        List<String> keywords = DOC_ALIASES.getOrDefault(docKey, List.of());
        for (String name : fileNames) {
            String lower = name.toLowerCase();
            if (lower.startsWith(userPrefixLower)) {
                String rem = lower.substring(userPrefixLower.length());
                if (keywords.stream().anyMatch(rem::contains)) return name;
            }
        }
        // Pass 3: Positional fallback for generic filenames
        List<String> slots = new ArrayList<>(DOC_ALIASES.keySet());
        int slotIdx = slots.indexOf(docKey);
        if (slotIdx >= 0) {
            List<String> generic = fileNames.stream()
                    .filter(n -> n.toLowerCase().startsWith(userPrefixLower))
                    .filter(n -> {
                        String rem = n.toLowerCase().substring(userPrefixLower.length());
                        return DOC_ALIASES.values().stream().flatMap(List::stream).noneMatch(rem::contains);
                    }).collect(Collectors.toList());
            if (slotIdx < generic.size()) return generic.get(slotIdx);
        }
        return null;
    }

    private static List<String> fetchRemoteUserDocNames(String userId) {
        String key = userId.trim();
        CachedRemoteDocs cached = remoteDocListCache.get(key);
        if (cached != null && cached.isFresh()) return cached.files;

        List<String> remoteNames = new ArrayList<>();
        try {
            String enc = URLEncoder.encode(key, StandardCharsets.UTF_8);
            HttpURLConnection conn = (HttpURLConnection) URI.create(getBaseServerUrl() + "/api.php?action=get_user_docs&user_id=" + enc).toURL().openConnection();
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(8000);
            if (conn.getResponseCode() == 200) {
                String json = new String(conn.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                int s = json.indexOf('[', json.indexOf("\"files\"")), e = json.indexOf(']', s);
                if (s >= 0 && e > s) {
                    for (String p : json.substring(s + 1, e).split(",")) {
                        String clean = p.trim().replaceAll("^\"|\"$", "");
                        if (!clean.isEmpty()) remoteNames.add(clean);
                    }
                }
                remoteDocListCache.put(key, new CachedRemoteDocs(remoteNames));
            }
        } catch (Exception ignored) {}
        return remoteNames;
    }

    private static File findUploadedFile(String userId, String docKey) {
        if (userId == null || userId.trim().isEmpty() || docKey == null) return null;
        String prefix = (sanitizeFileNamePart(userId.trim()) + "_").toLowerCase();

        if (isRemoteHost()) {
            List<String> remoteNames = fetchRemoteUserDocNames(userId);
            String matched = matchFileNameForDocKey(remoteNames, prefix, docKey);
            if (matched != null) {
                try {
                    Path target = getLocalCacheDirectory().resolve(matched);
                    if (!Files.exists(target) || Files.size(target) == 0) {
                        String encFile = URLEncoder.encode(matched, StandardCharsets.UTF_8).replace("+", "%20");
                        HttpURLConnection dl = (HttpURLConnection) URI.create(getBaseServerUrl() + "/uploads/" + encFile).toURL().openConnection();
                        dl.setConnectTimeout(5000);
                        dl.setReadTimeout(15000);
                        if (dl.getResponseCode() == 200) try (InputStream in = dl.getInputStream()) { Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING); }
                    }
                    if (Files.exists(target) && Files.size(target) > 0) {
                        userUploadedDocs.computeIfAbsent(userId, k -> new ConcurrentHashMap<>()).put(docKey, target.toFile());
                        return target.toFile();
                    }
                } catch (Exception ignored) {}
            }
        } else {
            try (Stream<Path> stream = Files.list(getUploadsDirectory())) {
                List<String> localNames = stream.filter(Files::isRegularFile)
                        .sorted(Comparator.comparingLong((Path p) -> p.toFile().lastModified()).reversed())
                        .map(p -> p.getFileName().toString()).collect(Collectors.toList());
                String matched = matchFileNameForDocKey(localNames, prefix, docKey);
                if (matched != null) {
                    File found = getUploadsDirectory().resolve(matched).toFile();
                    userUploadedDocs.computeIfAbsent(userId, k -> new ConcurrentHashMap<>()).put(docKey, found);
                    return found;
                }
            } catch (IOException ignored) {}
        }
        Map<String, File> cached = userUploadedDocs.get(userId);
        return (cached != null && cached.containsKey(docKey) && cached.get(docKey).exists()) ? cached.get(docKey) : null;
    }

    // Loads standard JavaFX images with an ImageIO fallback for CMYK/progressive JPEGs
    private static Image loadImageFromFile(File file) {
        if (file == null || !file.exists() || !file.isFile()) return null;
        String lower = file.getName().toLowerCase();
        if (lower.endsWith(".pdf") || lower.endsWith(".doc") || lower.endsWith(".docx")) return null;
        try (InputStream is = Files.newInputStream(file.toPath())) {
            Image img = new Image(is);
            if (!img.isError() && img.getWidth() > 0) return img;
        } catch (Exception ignored) {}
        try {
            BufferedImage buf = ImageIO.read(file);
            if (buf != null) {
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                ImageIO.write(buf, "png", baos);
                Image conv = new Image(new ByteArrayInputStream(baos.toByteArray()));
                if (!conv.isError() && conv.getWidth() > 0) return conv;
            }
        } catch (Exception ignored) {}
        return null;
    }

    private static void deleteUserUploadedFiles(String userId) {
        if (userId == null) return;
        userUploadedDocs.remove(userId);
        remoteDocListCache.remove(userId.trim());
        userRejectionReasons.remove(userId);
        userApprovalTimestamps.remove(userId);
        try (Stream<Path> stream = Files.list(getUploadsDirectory())) {
            String prefix = (sanitizeFileNamePart(userId) + "_").toLowerCase();
            stream.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase().startsWith(prefix))
                    .forEach(p -> { try { Files.deleteIfExists(p); } catch (IOException ignored) {} });
        } catch (IOException ignored) {}
    }

    // Maps admin rejection reason keywords back to specific document slots
    private static Set<String> determineRejectedDocuments(String reason, List<String> allDocs) {
        Set<String> flagged = new LinkedHashSet<>();
        if (reason == null || reason.trim().isEmpty()) { flagged.addAll(allDocs); return flagged; }
        String lower = reason.toLowerCase();
        if (lower.contains("cr/or") || lower.contains("cr / or") || lower.contains("plate")) flagged.add("CR / OR Document");
        if (lower.contains("license")) flagged.add("Driver's License");
        if (lower.contains("registration")) flagged.add("UE Registration Form");
        if (lower.contains("vehicle photo")) flagged.add("Vehicle Photo");
        if ((lower.contains("authorization") || lower.contains("owner's valid id")) && allDocs.contains("Owner's Valid ID & Approval Letter")) {
            flagged.add("Owner's Valid ID & Approval Letter");
        }
        if (flagged.isEmpty()) flagged.addAll(allDocs);
        return flagged;
    }

    private List<String> getRequiredDocKeys(VehicleApplication app) {
        List<String> keys = new ArrayList<>(List.of("CR / OR Document", "Driver's License", "UE Registration Form", "Vehicle Photo"));
        if ("Another Person's Vehicle".equalsIgnoreCase(app.getOwnership())) keys.add("Owner's Valid ID & Approval Letter");
        return keys;
    }

    // =========================================================================
    // ADMIN PORTAL: NAVIGATION, OVERVIEW DASHBOARD & RECORDS INSPECTOR
    // =========================================================================

    private void setActiveAdminNav(Button activeBtn) {
        if (btnAdminNavDashboard == null) return;
        for (Button b : List.of(btnAdminNavDashboard, btnAdminNavRecords, btnAdminNavInbox)) b.getStyleClass().setAll("admin-nav-btn");
        if (activeBtn != null) activeBtn.getStyleClass().setAll("admin-nav-active");
        refreshAdminBadgeStyles();
        updateAdminSidebarBadges();
    }

    private void refreshAdminBadgeStyles() {
        if (lblRecordsBadge != null) {
            boolean act = btnAdminNavRecords != null && btnAdminNavRecords.getStyleClass().contains("admin-nav-active");
            lblRecordsBadge.getStyleClass().setAll(act ? "sidebar-pill-badge-white" : "sidebar-pill-badge");
        }
        if (lblInboxBadge != null) {
            boolean act = btnAdminNavInbox != null && btnAdminNavInbox.getStyleClass().contains("admin-nav-active");
            lblInboxBadge.getStyleClass().setAll(act ? "sidebar-pill-badge-white" : "sidebar-pill-badge-red");
        }
    }

    private void updateAdminSidebarBadges() {
        refreshAdminBadgeStyles();
        Main.runAsync(() -> new int[]{
                DatabaseManager.getAllApplications().size(),
                DatabaseManager.getAdminReceivedMessages().size()
        }, counts -> {
            if (Main.getCurrentUser() == null) return;
            if (lblRecordsBadge != null) lblRecordsBadge.setText(String.valueOf(counts[0]));
            if (lblInboxBadge != null) lblInboxBadge.setText(String.valueOf(counts[1]));
            refreshAdminBadgeStyles();
        }, ignored -> {});
    }

    @FXML public void showAdminDashboard() {
        if (adminContentRoot != null) { setActiveAdminNav(btnAdminNavDashboard); renderAdminDashboardTab(); }
        else Main.loadScene("AdminView.fxml", 1200, 740);
    }
    @FXML public void showAdminRecords() { if (adminContentRoot != null) { setActiveAdminNav(btnAdminNavRecords); renderAdminRecordsTab(); } }
    @FXML public void showAdminInbox() { if (adminContentRoot != null) { setActiveAdminNav(btnAdminNavInbox); renderInboxWorkspace(true, "RECEIVED", null); } }

    // Renders Admin Overview: KPI cards, Pending Queue, Quota Tracker & Recent Approvals asynchronously
    private void renderAdminDashboardTab() {
        final int token = ++adminNavToken;
        if (adminScrollPane != null) adminScrollPane.setFitToHeight(false);
        adminContentRoot.setMaxHeight(Region.USE_COMPUTED_SIZE);
        adminContentRoot.getChildren().clear();
        updateAdminSidebarBadges();

        VBox titleBox = new VBox(4,
                styledLabel("Security Office Overview", "-fx-font-size: 21px; -fx-font-weight: bold; -fx-text-fill: #0f172a;", true),
                styledLabel("Logged in as: Administrator", "-fx-font-size: 13px; -fx-text-fill: #64748b;", false));
        HBox.setHgrow(titleBox, Priority.ALWAYS);

        Button btnNotice = styledButton("✈  Send Notice to Student",
                "-fx-background-color: #b71c1c; -fx-text-fill: white; -fx-font-weight: bold; -fx-font-size: 13px; -fx-padding: 11 20; -fx-background-radius: 10; -fx-cursor: hand;",
                e -> showComposeModal(true, null, null));
        HBox headerCard = new HBox(16, titleBox, btnNotice);
        headerCard.setAlignment(Pos.CENTER_LEFT);
        headerCard.getStyleClass().add("header-card");

        VBox loadingCard = createLoadingCard("Loading Security Office overview...");
        adminContentRoot.getChildren().addAll(headerCard, loadingCard);

        Main.runAsync(DatabaseManager::getAllApplications, allApps -> {
            if (token != adminNavToken || Main.getCurrentUser() == null) return;
            cachedAdminApps = new ArrayList<>(allApps);

            long pending = allApps.stream().filter(a -> "PENDING".equalsIgnoreCase(a.getStatus())).count();
            long approved = allApps.stream().filter(a -> "APPROVED".equalsIgnoreCase(a.getStatus()) && !a.isExpired()).count();
            long rejected = allApps.stream().filter(a -> "REJECTED".equalsIgnoreCase(a.getStatus())).count();

            HBox kpiRow = new HBox(18,
                    createKpiCard("🚗", "#f1f5f9", "#475569", "TOTAL\nSUBMISSIONS", "#64748b", String.valueOf(allApps.size())),
                    createKpiCard("🕒", "#fef3c7", "#d97706", "PENDING\nREVIEW", "#d97706", String.valueOf(pending)),
                    createKpiCard("✓", "#d1fae5", "#059669", "APPROVED", "#059669", String.valueOf(approved)),
                    createKpiCard("⚠", "#fee2e2", "#dc2626", "REJECTED", "#dc2626", String.valueOf(rejected)));

            // Pending Applications Queue (Left Column)
            VBox leftQueueCol = new VBox(14);
            HBox.setHgrow(leftQueueCol, Priority.ALWAYS);
            HBox queueBar = new HBox(10,
                    styledLabel("●", "-fx-font-size: 11px; -fx-text-fill: #f59e0b;", false),
                    styledLabel("Pending Applications Queue", "-fx-font-size: 15px; -fx-font-weight: bold; -fx-text-fill: #0f172a;", false),
                    hSpacer(),
                    styledLabel(pending + " awaiting verification", "-fx-font-size: 12px; -fx-text-fill: #64748b;", false));
            queueBar.setAlignment(Pos.CENTER_LEFT);
            queueBar.getStyleClass().add("header-card");
            queueBar.setPadding(new Insets(14, 20, 14, 20));

            VBox queueCard = new VBox(12);
            queueCard.getStyleClass().add("header-card");
            queueCard.setPadding(new Insets(16, 20, 16, 20));
            List<VehicleApplication> pendingApps = allApps.stream().filter(a -> "PENDING".equalsIgnoreCase(a.getStatus())).collect(Collectors.toList());
            if (pendingApps.isEmpty()) {
                VBox empty = new VBox(styledLabel("No pending applications in queue.", "-fx-font-size: 13px; -fx-text-fill: #94a3b8;", false));
                empty.setAlignment(Pos.CENTER);
                empty.setPadding(new Insets(36, 20, 36, 20));
                queueCard.getChildren().add(empty);
            } else {
                for (int i = 0; i < pendingApps.size(); i++) {
                    queueCard.getChildren().add(createPendingQueueRow(pendingApps.get(i)));
                    if (i < pendingApps.size() - 1) queueCard.getChildren().add(new Separator());
                }
            }
            leftQueueCol.getChildren().addAll(queueBar, queueCard);

            // Quota Tracker & Recent Approvals Log (Right Column)
            VBox rightSidebarCol = new VBox(18, createQuotaTrackerCard(), createRecentApprovalsCard(allApps, approved));
            rightSidebarCol.setMinWidth(320);
            rightSidebarCol.setPrefWidth(345);
            rightSidebarCol.setMaxWidth(365);

            HBox lowerSplit = new HBox(20, leftQueueCol, rightSidebarCol);
            lowerSplit.setAlignment(Pos.TOP_LEFT);
            adminContentRoot.getChildren().setAll(headerCard, kpiRow, lowerSplit);
        }, err -> {
            if (token != adminNavToken) return;
            adminContentRoot.getChildren().setAll(headerCard, styledLabel("Unable to load dashboard records: " + err.getMessage(), "-fx-text-fill: #dc2626;", true));
        });
    }

    private HBox createKpiCard(String icon, String bg, String fg, String title, String titleColor, String count) {
        StackPane iconBox = new StackPane(styledLabel(icon, "-fx-font-size: 18px; -fx-font-weight: bold; -fx-text-fill: " + fg + ";", false));
        iconBox.setStyle("-fx-background-color: " + bg + "; -fx-background-radius: 12; -fx-min-width: 48; -fx-min-height: 48; -fx-max-width: 48; -fx-max-height: 48;");
        VBox textCol = new VBox(4,
                styledLabel(title, "-fx-font-size: 10.5px; -fx-font-weight: bold; -fx-text-fill: " + titleColor + ";", false),
                styledLabel(count, "-fx-font-size: 26px; -fx-font-weight: bold; -fx-text-fill: #0f172a;", false));
        HBox card = new HBox(16, iconBox, textCol);
        card.setAlignment(Pos.CENTER_LEFT);
        card.getStyleClass().add("header-card");
        card.setPadding(new Insets(18, 20, 18, 20));
        HBox.setHgrow(card, Priority.ALWAYS);
        return card;
    }

    private HBox createPendingQueueRow(VehicleApplication app) {
        Label platePill = styledLabel(app.plateNumberProperty().get(), "-fx-background-color: #f1f5f9; -fx-border-color: #e2e8f0; -fx-border-radius: 6; -fx-background-radius: 6; -fx-text-fill: #334155; -fx-font-weight: bold; -fx-font-size: 11px; -fx-padding: 2 8;", false);
        HBox topLine = new HBox(8,
                styledLabel((app.getMake() + " " + app.getModel()).trim(), "-fx-font-size: 14px; -fx-font-weight: bold; -fx-text-fill: #0f172a;", false),
                platePill,
                styledLabel(app.getYear() + " • " + app.getColor(), "-fx-font-size: 12px; -fx-text-fill: #64748b;", false));
        topLine.setAlignment(Pos.CENTER_LEFT);

        boolean isOther = "Another Person's Vehicle".equalsIgnoreCase(app.getOwnership());
        Label ownPill = styledLabel(isOther ? "●  " + app.getOwnership() : app.getOwnership(), isOther
                ? "-fx-background-color: #fffbeb; -fx-border-color: #fde68a; -fx-border-radius: 12; -fx-background-radius: 12; -fx-text-fill: #b45309; -fx-font-weight: bold; -fx-font-size: 11px; -fx-padding: 3 10;"
                : "-fx-background-color: #f1f5f9; -fx-border-color: #e2e8f0; -fx-border-radius: 8; -fx-background-radius: 8; -fx-text-fill: #475569; -fx-font-weight: bold; -fx-font-size: 11px; -fx-padding: 3 10;", false);
        HBox bottomLine = new HBox(8,
                styledLabel(app.ownerNameProperty().get(), "-fx-font-size: 12.5px; -fx-font-weight: bold; -fx-text-fill: #1e293b;", false),
                styledLabel("•", "-fx-font-size: 12px; -fx-text-fill: #94a3b8;", false),
                styledLabel("ID: " + app.getOwnerId(), "-fx-font-size: 12px; -fx-text-fill: #64748b;", false),
                ownPill);
        bottomLine.setAlignment(Pos.CENTER_LEFT);

        VBox detailsCol = new VBox(6, topLine, bottomLine);
        HBox.setHgrow(detailsCol, Priority.ALWAYS);

        Button btnInspect = styledButton("📄 Inspect (" + getRequiredDocKeys(app).size() + ")",
                "-fx-background-color: #ffffff; -fx-text-fill: #1e293b; -fx-border-color: #cbd5e1; -fx-border-radius: 8; -fx-background-radius: 8; -fx-font-weight: bold; -fx-font-size: 12px; -fx-padding: 8 14; -fx-cursor: hand;",
                e -> showAdminInspectApplicantModal(app));
        Button btnGoRecords = styledButton("Review in Records →",
                "-fx-background-color: #b71c1c; -fx-text-fill: #ffffff; -fx-font-weight: bold; -fx-font-size: 12px; -fx-padding: 8 16; -fx-background-radius: 8; -fx-cursor: hand;",
                e -> { adminRecordsFilter = "PENDING"; selectedRecordAppId = app.idProperty().get(); showAdminRecords(); });

        HBox row = new HBox(14, detailsCol, new HBox(10, btnInspect, btnGoRecords));
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new Insets(8, 4, 8, 4));
        return row;
    }

    private VBox createQuotaTrackerCard() {
        int total = 500, avail = Math.max(0, Math.min(total, officeStickerStock)), issued = total - avail;
        VBox titleCol = new VBox(2,
                styledLabel("Sticker Quota Tracker", "-fx-font-size: 14.5px; -fx-font-weight: bold; -fx-text-fill: #0f172a;", false),
                styledLabel("AY 2026-2027 Campus Allocation", "-fx-font-size: 11.5px; -fx-text-fill: #64748b;", false));
        HBox.setHgrow(titleCol, Priority.ALWAYS);

        Label pctBadge = styledLabel(String.format(Locale.US, "%.1f%% Left", (avail * 100.0) / total),
                "-fx-background-color: #ecfdf5; -fx-border-color: #a7f3d0; -fx-border-radius: 12; -fx-background-radius: 12; -fx-text-fill: #059669; -fx-font-weight: bold; -fx-font-size: 11px; -fx-padding: 4 10;", false);
        HBox headerRow = new HBox(10, titleCol, pctBadge);
        headerRow.setAlignment(Pos.CENTER_LEFT);

        HBox nums = new HBox(6,
                styledLabel(String.valueOf(avail), "-fx-font-size: 24px; -fx-font-weight: bold; -fx-text-fill: #0f172a;", false),
                styledLabel("/ " + total + " Available", "-fx-font-size: 12px; -fx-text-fill: #64748b;", false),
                hSpacer(),
                styledLabel(issued + " Issued", "-fx-font-size: 11.5px; -fx-font-weight: bold; -fx-text-fill: #b71c1c;", false));
        nums.setAlignment(Pos.BASELINE_LEFT);

        StackPane barTrack = new StackPane();
        barTrack.setAlignment(Pos.CENTER_LEFT);
        barTrack.setPrefHeight(8);
        barTrack.setStyle("-fx-background-color: #e2e8f0; -fx-background-radius: 4;");
        Region barFill = new Region();
        barFill.setPrefHeight(8);
        barFill.setStyle("-fx-background-color: #10b981; -fx-background-radius: 4;");
        double ratio = Math.max(0.0, Math.min(1.0, (double) avail / total));
        barFill.prefWidthProperty().bind(barTrack.widthProperty().multiply(ratio));
        barFill.maxWidthProperty().bind(barTrack.widthProperty().multiply(ratio));
        barTrack.getChildren().add(barFill);

        HBox foot = new HBox(8,
                styledLabel("Strict 1-vehicle quota per student", "-fx-font-size: 10.5px; -fx-text-fill: #64748b;", false),
                hSpacer(),
                styledLabel("Main Gate Stock", "-fx-font-size: 10.5px; -fx-text-fill: #64748b;", false));
        VBox innerBox = new VBox(10, nums, barTrack, foot);
        innerBox.setPadding(new Insets(14, 16, 14, 16));
        innerBox.setStyle("-fx-background-color: #f8fafc; -fx-border-color: #e2e8f0; -fx-border-radius: 12; -fx-background-radius: 12;");

        VBox card = new VBox(14, headerRow, innerBox);
        card.getStyleClass().add("header-card");
        card.setPadding(new Insets(18, 20, 18, 20));
        return card;
    }

    private VBox createRecentApprovalsCard(ObservableList<VehicleApplication> allApps, long approvedCount) {
        VBox titleCol = new VBox(2,
                styledLabel("Recent Approvals Log", "-fx-font-size: 14.5px; -fx-font-weight: bold; -fx-text-fill: #0f172a;", false),
                styledLabel("Verified campus passes", "-fx-font-size: 11.5px; -fx-text-fill: #64748b;", false));
        HBox.setHgrow(titleCol, Priority.ALWAYS);
        Label badge = styledLabel(approvedCount + " Active", "-fx-background-color: #ecfdf5; -fx-border-color: #a7f3d0; -fx-border-radius: 12; -fx-background-radius: 12; -fx-text-fill: #059669; -fx-font-weight: bold; -fx-font-size: 11px; -fx-padding: 4 10;", false);

        VBox card = new VBox(12, new HBox(10, titleCol, badge));
        card.getStyleClass().add("header-card");
        card.setPadding(new Insets(18, 20, 16, 20));

        List<VehicleApplication> approvedApps = allApps.stream().filter(a -> "APPROVED".equalsIgnoreCase(a.getStatus()) && !a.isExpired()).collect(Collectors.toList());
        if (approvedApps.isEmpty()) {
            VBox empty = new VBox(styledLabel("No approved passes yet.", "-fx-font-size: 12px; -fx-text-fill: #94a3b8;", false));
            empty.setAlignment(Pos.CENTER);
            empty.setPadding(new Insets(24, 12, 24, 12));
            card.getChildren().add(empty);
        } else {
            card.getChildren().add(hLine());
            VBox logItems = new VBox(10);
            for (int i = 0; i < approvedApps.size(); i++) {
                VehicleApplication app = approvedApps.get(i);
                String ts = app.getApprovalDate();
                VBox info = new VBox(3,
                        styledLabel(app.ownerNameProperty().get(), "-fx-font-size: 12.5px; -fx-font-weight: bold; -fx-text-fill: #0f172a;", true),
                        styledLabel((app.getMake() + " " + app.getModel()).trim() + " • " + app.plateNumberProperty().get(), "-fx-font-size: 11.5px; -fx-text-fill: #475569;", true),
                        styledLabel("Approved: " + ((ts != null && !ts.isEmpty()) ? ts : "Verified"), "-fx-font-size: 10.5px; -fx-text-fill: #94a3b8;", false));
                HBox.setHgrow(info, Priority.ALWAYS);
                Button btnView = styledButton("View in Records",
                        "-fx-background-color: #f1f5f9; -fx-border-color: #e2e8f0; -fx-border-radius: 7; -fx-background-radius: 7; -fx-text-fill: #334155; -fx-font-weight: bold; -fx-font-size: 11px; -fx-padding: 6 12; -fx-cursor: hand;",
                        e -> { adminRecordsFilter = "APPROVED"; selectedRecordAppId = app.idProperty().get(); showAdminRecords(); });
                HBox row = new HBox(10, info, btnView);
                row.setAlignment(Pos.CENTER_LEFT);
                logItems.getChildren().add(row);
                if (i < approvedApps.size() - 1) logItems.getChildren().add(hLine());
            }
            ScrollPane scroll = new ScrollPane(logItems);
            scroll.setFitToWidth(true);
            scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
            scroll.setPrefHeight(265);
            scroll.setMaxHeight(265);
            scroll.setStyle("-fx-background-color: transparent; -fx-background: transparent; -fx-border-color: transparent;");
            card.getChildren().add(scroll);
        }
        return card;
    }

    // Renders Admin Records Master-Detail database inspector with instant cached filtering & async refresh
    private void renderAdminRecordsTab() {
        final int token = ++adminNavToken;
        if (adminScrollPane != null) adminScrollPane.setFitToHeight(false);
        adminContentRoot.setMaxHeight(Region.USE_COMPUTED_SIZE);
        adminContentRoot.getChildren().clear();
        updateAdminSidebarBadges();

        VBox titleBox = new VBox(4,
                styledLabel("Vehicle Sticker Records Database", "-fx-font-size: 21px; -fx-font-weight: bold; -fx-text-fill: #0f172a;", true),
                styledLabel("Filter, search, and inspect uploaded applicant documents", "-fx-font-size: 13px; -fx-text-fill: #64748b;", true));
        HBox.setHgrow(titleBox, Priority.ALWAYS);

        TextField txtSearch = new TextField(adminRecordsSearch);
        txtSearch.setPromptText("Search by plate, name, model...");
        txtSearch.getStyleClass().add("search-pill-field");
        HBox.setHgrow(txtSearch, Priority.ALWAYS);
        HBox searchBox = new HBox(8, styledLabel("🔍", "-fx-text-fill: #94a3b8; -fx-font-size: 13px;", false), txtSearch);
        searchBox.setAlignment(Pos.CENTER_LEFT);
        searchBox.getStyleClass().add("search-pill-box");
        searchBox.setPrefWidth(270);

        Button btnRefresh = styledButton("↻ Refresh", "-fx-background-color: #f8fafc; -fx-border-color: #cbd5e1; -fx-border-radius: 10; -fx-background-radius: 10; -fx-text-fill: #334155; -fx-font-weight: bold; -fx-font-size: 12px; -fx-padding: 8 14; -fx-cursor: hand;", e -> renderAdminRecordsTab());

        HBox headerCard = new HBox(12, titleBox, searchBox, btnRefresh);
        headerCard.setAlignment(Pos.CENTER_LEFT);
        headerCard.getStyleClass().add("header-card");

        VBox leftList = new VBox(createInlineLoadingRow("Loading records..."));
        leftList.setMinWidth(320);
        leftList.setPrefWidth(340);
        leftList.setMaxWidth(355);
        leftList.setStyle("-fx-background-color: #ffffff; -fx-background-radius: 14; -fx-border-color: #e2e8f0; -fx-border-radius: 14; -fx-effect: dropshadow(three-pass-box, rgba(0,0,0,0.04), 8, 0, 0, 2);");

        VBox rightInspector = new VBox(16, createInlineLoadingRow("Syncing applicant database..."));
        rightInspector.setAlignment(Pos.CENTER);
        rightInspector.setMinHeight(420);
        HBox.setHgrow(rightInspector, Priority.ALWAYS);
        rightInspector.setPadding(new Insets(22, 24, 22, 24));
        rightInspector.setStyle("-fx-background-color: #ffffff; -fx-background-radius: 14; -fx-border-color: #e2e8f0; -fx-border-radius: 14; -fx-effect: dropshadow(three-pass-box, rgba(0,0,0,0.04), 8, 0, 0, 2);");

        Map<String, Button> filterBtns = new LinkedHashMap<>();
        filterBtns.put("ALL", new Button("All Records"));
        filterBtns.put("PENDING", new Button("Pending"));
        filterBtns.put("APPROVED", new Button("Approved"));
        filterBtns.put("REJECTED", new Button("Rejected"));
        filterBtns.put("EXPIRED", new Button("Expired"));

        Runnable refreshLocal = () -> {
            filterBtns.forEach((key, btn) -> btn.getStyleClass().setAll(key.equals(adminRecordsFilter) ? "filter-pill-active" : "filter-pill"));
            populateAdminRecordsMasterDetail(leftList, rightInspector);
        };
        filterBtns.forEach((key, btn) -> {
            btn.setMinWidth(Region.USE_PREF_SIZE);
            btn.setOnAction(e -> { adminRecordsFilter = key; refreshLocal.run(); });
        });
        filterBtns.forEach((key, btn) -> btn.getStyleClass().setAll(key.equals(adminRecordsFilter) ? "filter-pill-active" : "filter-pill"));

        txtSearch.textProperty().addListener((obs, o, n) -> {
            adminRecordsSearch = n != null ? n.trim().toLowerCase() : "";
            populateAdminRecordsMasterDetail(leftList, rightInspector);
        });

        HBox filterRow = new HBox(10);
        filterRow.getChildren().addAll(filterBtns.values());

        HBox split = new HBox(20, leftList, rightInspector);
        split.setAlignment(Pos.TOP_LEFT);
        adminContentRoot.getChildren().addAll(headerCard, filterRow, split);

        Main.runAsync(DatabaseManager::getAllApplications, apps -> {
            if (token != adminNavToken || Main.getCurrentUser() == null) return;
            cachedAdminApps = new ArrayList<>(apps);
            refreshLocal.run();
        }, err -> {
            if (token != adminNavToken) return;
            leftList.getChildren().setAll(styledLabel("Failed to load records.", "-fx-padding: 20; -fx-text-fill: #dc2626;", false));
        });
    }

    private String effectiveStatus(VehicleApplication app) {
        return app.isExpired() ? "EXPIRED" : app.getStatus().toUpperCase();
    }

    private void populateAdminRecordsMasterDetail(VBox leftList, VBox rightInspector) {
        leftList.getChildren().clear();
        List<VehicleApplication> filtered = cachedAdminApps.stream()
                .filter(a -> "ALL".equalsIgnoreCase(adminRecordsFilter) || effectiveStatus(a).equalsIgnoreCase(adminRecordsFilter))
                .filter(a -> adminRecordsSearch.isEmpty() || (a.plateNumberProperty().get() + " " + a.ownerNameProperty().get() + " " + a.getOwnerId() + " " + a.getMake() + " " + a.getModel()).toLowerCase().contains(adminRecordsSearch))
                .collect(Collectors.toList());

        HBox listHeader = new HBox(8,
                styledLabel(capitalize(adminRecordsFilter) + " Applicants (" + filtered.size() + ")", "-fx-font-size: 13.5px; -fx-font-weight: bold; -fx-text-fill: #0f172a;", false),
                hSpacer(),
                styledLabel("Click to inspect", "-fx-font-size: 11px; -fx-text-fill: #64748b;", false));
        listHeader.setPadding(new Insets(16, 18, 14, 18));
        listHeader.setStyle("-fx-border-color: #e2e8f0; -fx-border-width: 0 0 1 0;");
        leftList.getChildren().add(listHeader);

        if (filtered.isEmpty()) {
            VBox empty = new VBox(styledLabel("No vehicle records found.", "-fx-font-size: 13px; -fx-text-fill: #94a3b8;", false));
            empty.setAlignment(Pos.CENTER);
            empty.setPadding(new Insets(42, 20, 42, 20));
            leftList.getChildren().add(empty);

            rightInspector.getChildren().setAll(styledLabel("Select an applicant from the left list to inspect credentials.", "-fx-font-size: 13px; -fx-text-fill: #94a3b8;", false));
            rightInspector.setAlignment(Pos.CENTER);
            rightInspector.setMinHeight(420);
            return;
        }

        VehicleApplication selected = filtered.stream().filter(a -> Objects.equals(selectedRecordAppId, a.idProperty().get())).findFirst().orElse(filtered.get(0));
        selectedRecordAppId = selected.idProperty().get();

        VBox itemsCol = new VBox();
        for (int i = 0; i < filtered.size(); i++) {
            VehicleApplication app = filtered.get(i);
            boolean isSel = app.idProperty().get() == selected.idProperty().get();
            Label nameLbl = styledLabel(app.ownerNameProperty().get(), "-fx-font-size: 13px; -fx-font-weight: bold; -fx-text-fill: #0f172a;", false);
            HBox.setHgrow(nameLbl, Priority.ALWAYS);
            Label docsBadge = styledLabel(getRequiredDocKeys(app).size() + " Docs", isSel
                    ? "-fx-background-color: #b71c1c; -fx-text-fill: #ffffff; -fx-font-weight: bold; -fx-font-size: 10.5px; -fx-padding: 3 9; -fx-background-radius: 6;"
                    : "-fx-background-color: #f1f5f9; -fx-border-color: #e2e8f0; -fx-border-radius: 6; -fx-background-radius: 6; -fx-text-fill: #334155; -fx-font-weight: bold; -fx-font-size: 10.5px; -fx-padding: 3 9;", false);

            boolean isOther = "Another Person's Vehicle".equalsIgnoreCase(app.getOwnership());
            Label ownPill = styledLabel(isOther ? "●  " + app.getOwnership() : app.getOwnership(), isOther
                    ? "-fx-background-color: #fffbeb; -fx-border-color: #fde68a; -fx-border-radius: 10; -fx-background-radius: 10; -fx-text-fill: #b45309; -fx-font-weight: bold; -fx-font-size: 10.5px; -fx-padding: 3 9;"
                    : "-fx-background-color: #f1f5f9; -fx-border-color: #e2e8f0; -fx-border-radius: 8; -fx-background-radius: 8; -fx-text-fill: #475569; -fx-font-weight: bold; -fx-font-size: 10.5px; -fx-padding: 3 9;", false);

            VBox itemCard = new VBox(6,
                    new HBox(8, nameLbl, docsBadge),
                    styledLabel((app.getMake() + " " + app.getModel()).trim() + " • " + app.plateNumberProperty().get(), "-fx-font-size: 11.5px; -fx-text-fill: #64748b;", false),
                    new HBox(8, ownPill, hSpacer(), styledLabel("ID: " + app.getOwnerId(), "-fx-font-size: 10.5px; -fx-text-fill: #94a3b8;", false)));
            itemCard.setPadding(new Insets(14, 16, 14, 16));
            itemCard.setStyle("-fx-background-color: " + (isSel ? "#fff1f2" : "#ffffff") + "; "
                    + (i < filtered.size() - 1 ? "-fx-border-color: #f1f5f9; -fx-border-width: 0 0 1 0;" : "-fx-background-radius: 0 0 14 14;")
                    + " -fx-cursor: hand;");
            itemCard.setOnMouseClicked(e -> { selectedRecordAppId = app.idProperty().get(); populateAdminRecordsMasterDetail(leftList, rightInspector); });
            itemsCol.getChildren().add(itemCard);
        }
        leftList.getChildren().add(itemsCol);
        renderAdminRecordInspectorPane(rightInspector, selected);
    }

    private static final class InspectorAsyncData {
        final Image vehiclePhoto;
        final Set<String> rejectedDocs;
        final Set<String> availableDocs;
        InspectorAsyncData(Image vehiclePhoto, Set<String> rejectedDocs, Set<String> availableDocs) {
            this.vehiclePhoto = vehiclePhoto;
            this.rejectedDocs = rejectedDocs;
            this.availableDocs = availableDocs;
        }
    }

    // Populates the right-side Vehicle Photo, Spec Tiles, Interactive Doc Tabs, and Approve/Reject Footer non-blockingly
    private void renderAdminRecordInspectorPane(VBox inspectorCard, VehicleApplication app) {
        final int token = ++inspectorToken;
        inspectorCard.getChildren().clear();
        inspectorCard.setAlignment(Pos.TOP_LEFT);
        inspectorCard.setMinHeight(Region.USE_COMPUTED_SIZE);

        // Vehicle Photo Hero Box (renders placeholder immediately, swaps photo in when async load completes)
        StackPane photoMediaHolder = new StackPane(styledLabel("🏍", "-fx-font-size: 46px; -fx-text-fill: #334155;", false));
        StackPane photoCard = new StackPane(photoMediaHolder);
        photoCard.setPrefSize(240, 142);
        photoCard.setMinSize(240, 142);
        photoCard.setMaxSize(240, 142);
        photoCard.setStyle("-fx-background-color: #f8fafc; -fx-border-color: #e2e8f0; -fx-border-radius: 12; -fx-background-radius: 12;");
        Rectangle clip = new Rectangle(240, 142);
        clip.setArcWidth(24);
        clip.setArcHeight(24);
        photoCard.setClip(clip);

        Label yrBadge = styledLabel(app.getYear() + " • " + app.getColor(), "-fx-background-color: rgba(15, 23, 42, 0.85); -fx-text-fill: #ffffff; -fx-font-size: 10px; -fx-font-weight: bold; -fx-padding: 4 10; -fx-background-radius: 10;", false);
        StackPane.setAlignment(yrBadge, Pos.TOP_LEFT);
        StackPane.setMargin(yrBadge, new Insets(10, 0, 0, 10));
        Label plBadge = styledLabel(app.plateNumberProperty().get(), "-fx-background-color: #ffffff; -fx-border-color: #cbd5e1; -fx-border-radius: 7; -fx-background-radius: 7; -fx-text-fill: #0f172a; -fx-font-size: 10.5px; -fx-font-weight: bold; -fx-padding: 3 9;", false);
        StackPane.setAlignment(plBadge, Pos.BOTTOM_RIGHT);
        StackPane.setMargin(plBadge, new Insets(0, 10, 10, 0));
        photoCard.getChildren().addAll(yrBadge, plBadge);

        // Vehicle & Applicant Metadata
        String stUpper = effectiveStatus(app);
        String stStyle = "APPROVED".equals(stUpper) ? "-fx-background-color: #d1fae5; -fx-border-color: #a7f3d0; -fx-text-fill: #059669;"
                : (("REJECTED".equals(stUpper) || "EXPIRED".equals(stUpper)) ? "-fx-background-color: #fee2e2; -fx-border-color: #fecaca; -fx-text-fill: #dc2626;"
                : "-fx-background-color: #fef3c7; -fx-border-color: #fde68a; -fx-text-fill: #b45309;");
        Label statusBadge = styledLabel("●  " + capitalize(stUpper), stStyle + " -fx-border-radius: 12; -fx-background-radius: 12; -fx-font-weight: bold; -fx-font-size: 11px; -fx-padding: 3 10;", false);

        boolean isOther = "Another Person's Vehicle".equalsIgnoreCase(app.getOwnership());
        Label ownBadge = styledLabel(isOther ? "●  " + app.getOwnership() : app.getOwnership(), isOther
                ? "-fx-background-color: #fffbeb; -fx-border-color: #fde68a; -fx-border-radius: 10; -fx-background-radius: 10; -fx-text-fill: #b45309; -fx-font-weight: bold; -fx-font-size: 11px; -fx-padding: 4 11;"
                : "-fx-background-color: #f8fafc; -fx-border-color: #e2e8f0; -fx-border-radius: 8; -fx-background-radius: 8; -fx-text-fill: #334155; -fx-font-weight: bold; -fx-font-size: 11px; -fx-padding: 4 11;", false);

        HBox titleRow = new HBox(10, styledLabel((app.getMake() + " " + app.getModel()).trim(), "-fx-font-size: 18px; -fx-font-weight: bold; -fx-text-fill: #0f172a;", false), statusBadge, hSpacer(), ownBadge);
        titleRow.setAlignment(Pos.CENTER_LEFT);

        HBox applicantLine = new HBox(4,
                styledLabel("Applicant:", "-fx-font-size: 12.5px; -fx-text-fill: #64748b;", false),
                styledLabel(app.ownerNameProperty().get(), "-fx-font-size: 12.5px; -fx-font-weight: bold; -fx-text-fill: #0f172a;", false),
                styledLabel("(" + app.getOwnerId() + ")", "-fx-font-size: 12.5px; -fx-text-fill: #94a3b8;", false));

        HBox specTiles = new HBox(10,
                createDetailBox("PLATE", app.plateNumberProperty().get(), "#b71c1c"),
                createDetailBox("YEAR", app.getYear(), "#0f172a"),
                createDetailBox("COLOR", app.getColor(), "#0f172a"),
                createDetailBox("PASS TYPE", app.passTypeProperty().get(), "#0f172a"));

        VBox rightDetails = new VBox(10, titleRow, applicantLine, specTiles);
        HBox.setHgrow(rightDetails, Priority.ALWAYS);

        // Document Tabs & Credential Sheet Viewer
        List<String> docKeys = getRequiredDocKeys(app);
        Set<String> rejectedDocs = Collections.synchronizedSet(new LinkedHashSet<>());
        Set<String> availableDocs = Collections.synchronizedSet(new LinkedHashSet<>());
        FlowPane docTabs = new FlowPane(8, 8);
        VBox docViewer = new VBox(14);
        docViewer.setPadding(new Insets(16, 18, 16, 18));
        docViewer.setStyle("-fx-background-color: #ffffff; -fx-border-color: #e2e8f0; -fx-border-radius: 12; -fx-background-radius: 12;");

        AtomicReference<String> activeDoc = new AtomicReference<>(docKeys.get(0));
        List<Button> tabButtons = new ArrayList<>();
        Runnable refreshTabStyles = () -> {
            for (int i = 0; i < docKeys.size(); i++) {
                String k = docKeys.get(i);
                Button b = tabButtons.get(i);
                boolean act = k.equals(activeDoc.get());
                String dotColor = rejectedDocs.contains(k) ? "#ef4444" : (availableDocs.contains(k) ? "#10b981" : "#f59e0b");
                b.setGraphic(styledLabel("●", "-fx-font-size: 9px; -fx-text-fill: " + (act ? "#ffffff" : dotColor) + ";", false));
                b.setGraphicTextGap(6);
                b.setStyle(act
                        ? "-fx-background-color: #b71c1c; -fx-text-fill: #ffffff; -fx-font-weight: bold; -fx-font-size: 11.5px; -fx-padding: 7 14; -fx-background-radius: 18; -fx-cursor: hand;"
                        : "-fx-background-color: #ffffff; -fx-border-color: #e2e8f0; -fx-border-radius: 18; -fx-background-radius: 18; -fx-text-fill: #334155; -fx-font-weight: bold; -fx-font-size: 11.5px; -fx-padding: 7 14; -fx-cursor: hand;");
            }
        };
        for (String k : docKeys) {
            Button btn = styledButton(k, "", e -> {
                activeDoc.set(k);
                refreshTabStyles.run();
                populateInspectorDocumentSheetAsync(docViewer, app, k, rejectedDocs, token);
            });
            tabButtons.add(btn);
            docTabs.getChildren().add(btn);
        }
        refreshTabStyles.run();
        docViewer.getChildren().setAll(createInlineLoadingRow("Loading applicant credentials from server..."));

        // Bottom Decision Footer
        HBox lockBanner = new HBox(8,
                styledLabel("PENDING".equals(stUpper) ? "🛡" : "🔒", "-fx-font-size: 12px;", false),
                styledLabel("PENDING".equals(stUpper) ? "Verify applicant credentials before issuing a final decision" : "Application status is finalized as " + capitalize(stUpper), "-fx-font-size: 11.5px; -fx-text-fill: #475569;", false));
        lockBanner.setPadding(new Insets(9, 14, 9, 14));
        lockBanner.setStyle("-fx-background-color: #f1f5f9; -fx-border-color: #e2e8f0; -fx-border-radius: 8; -fx-background-radius: 8;");

        HBox footer = new HBox(12, lockBanner, hSpacer());
        footer.setAlignment(Pos.CENTER_LEFT);
        if ("PENDING".equals(stUpper)) {
            Button btnReject = styledButton("✕  Reject", "-fx-background-color: #fff1f2; -fx-text-fill: #b71c1c; -fx-border-color: #fecdd3; -fx-border-radius: 8; -fx-background-radius: 8; -fx-font-weight: bold; -fx-font-size: 12.5px; -fx-padding: 10 18; -fx-cursor: hand;",
                    e -> rejectApplicationAction(app, this::renderAdminRecordsTab));
            Button btnApprove = styledButton("✓  Approve Application", "-fx-background-color: #10b981; -fx-text-fill: #ffffff; -fx-font-weight: bold; -fx-font-size: 12.5px; -fx-padding: 10 20; -fx-background-radius: 8; -fx-cursor: hand;", null);
            btnApprove.setOnAction(e -> approveApplicationAction(app, btnApprove, this::renderAdminRecordsTab));
            footer.getChildren().addAll(btnReject, btnApprove);
        } else {
            boolean isApp = "APPROVED".equals(stUpper);
            String badgeLabel = isApp ? "✓  Application Approved" : ("EXPIRED".equals(stUpper) ? "⌚  Sticker Expired (1 Year Exceeded)" : "✕  Application Rejected");
            footer.getChildren().add(styledLabel(badgeLabel,
                    (isApp ? "-fx-background-color: #d1fae5; -fx-text-fill: #059669;" : "-fx-background-color: #fee2e2; -fx-text-fill: #dc2626;") + " -fx-font-weight: bold; -fx-font-size: 12px; -fx-padding: 9 16; -fx-background-radius: 8;", false));
        }

        inspectorCard.getChildren().addAll(
                new HBox(20, photoCard, rightDetails),
                styledLabel("SUBMITTED APPLICANT DOCUMENTS (" + docKeys.size() + ")", "-fx-font-size: 11px; -fx-font-weight: bold; -fx-text-fill: #64748b;", false),
                docTabs, docViewer, footer);

        // Fetch photo, rejection metadata, and document availability in background
        Main.runAsync(() -> {
            Image photo = loadImageFromFile(findUploadedFile(app.getOwnerId(), "Vehicle Photo"));
            Set<String> rej = "REJECTED".equals(stUpper)
                    ? determineRejectedDocuments(DatabaseManager.getRejectionReason(app.idProperty().get(), app.getOwnerId()), docKeys)
                    : Collections.emptySet();
            Set<String> avail = new LinkedHashSet<>();
            if (isRemoteHost()) {
                List<String> remoteNames = fetchRemoteUserDocNames(app.getOwnerId());
                String pfx = (sanitizeFileNamePart(app.getOwnerId().trim()) + "_").toLowerCase();
                for (String k : docKeys) {
                    if (matchFileNameForDocKey(remoteNames, pfx, k) != null) avail.add(k);
                }
            } else {
                for (String k : docKeys) {
                    if (findUploadedFile(app.getOwnerId(), k) != null) avail.add(k);
                }
            }
            return new InspectorAsyncData(photo, rej, avail);
        }, data -> {
            if (token != inspectorToken || Main.getCurrentUser() == null) return;
            if (data.vehiclePhoto != null) photoMediaHolder.getChildren().setAll(coverImageView(data.vehiclePhoto, 240, 142));
            rejectedDocs.clear();
            rejectedDocs.addAll(data.rejectedDocs);
            availableDocs.clear();
            availableDocs.addAll(data.availableDocs);
            refreshTabStyles.run();
            populateInspectorDocumentSheetAsync(docViewer, app, activeDoc.get(), rejectedDocs, token);
        }, err -> {
            if (token != inspectorToken) return;
            populateInspectorDocumentSheetAsync(docViewer, app, activeDoc.get(), rejectedDocs, token);
        });
    }

    private void populateInspectorDocumentSheetAsync(VBox viewer, VehicleApplication app, String docKey, Set<String> rejectedDocs, int expectedToken) {
        viewer.getChildren().setAll(createInlineLoadingRow("Loading " + docKey + "..."));

        Main.runAsync(() -> {
            File file = findUploadedFile(app.getOwnerId(), docKey);
            Image docImg = loadImageFromFile(file);
            return new AbstractMap.SimpleEntry<>(file, docImg);
        }, entry -> {
            if (expectedToken != inspectorToken || Main.getCurrentUser() == null) return;
            File file = entry.getKey();
            Image docImg = entry.getValue();

            viewer.getChildren().clear();
            HBox topBar = new HBox(10, styledLabel("📄  Scanned Credential Sheet (" + docKey + ")", "-fx-font-size: 12px; -fx-font-weight: bold; -fx-text-fill: #475569;", false), hSpacer());
            if (file != null && file.exists()) topBar.getChildren().add(styledLabel("Click document to open popup", "-fx-font-size: 11px; -fx-text-fill: #94a3b8;", false));
            viewer.getChildren().add(topBar);

            if (docImg != null) {
                ImageView iv = new ImageView(docImg);
                iv.setPreserveRatio(true);
                iv.setSmooth(true);
                iv.setFitWidth(460);
                iv.setFitHeight(260);
                VBox frame = new VBox(iv);
                frame.setAlignment(Pos.CENTER);
                frame.setPadding(new Insets(14));
                frame.setStyle("-fx-background-color: #f8fafc; -fx-border-color: #e2e8f0; -fx-border-radius: 10; -fx-background-radius: 10; -fx-cursor: hand;");
                frame.setOnMouseClicked(e -> showLoadedDocumentPreview(docKey, file, docImg));
                viewer.getChildren().add(frame);
            } else {
                VBox issuerCol = new VBox(2,
                        styledLabel(docKey.contains("UE") ? "UNIVERSITY OF THE EAST • OFFICE OF THE REGISTRAR" : "REPUBLIC OF THE PHILIPPINES • LAND TRANSPORTATION OFFICE", "-fx-font-size: 9.5px; -fx-font-weight: bold; -fx-text-fill: #94a3b8;", false),
                        styledLabel(docKey, "-fx-font-size: 14px; -fx-font-weight: bold; -fx-text-fill: #0f172a;", false));
                HBox.setHgrow(issuerCol, Priority.ALWAYS);
                Label refBadge = styledLabel("UE-" + app.getOwnerId() + "-" + Math.abs(docKey.hashCode() % 9000 + 1000),
                        "-fx-background-color: #ffffff; -fx-border-color: #e2e8f0; -fx-border-radius: 6; -fx-background-radius: 6; -fx-text-fill: #475569; -fx-font-weight: bold; -fx-font-size: 10.5px; -fx-padding: 4 10;", false);

                GridPane grid = createThreeColGrid();
                grid.add(createDetailBox("PLATE NUMBER", app.plateNumberProperty().get(), "#0f172a"), 0, 0);
                grid.add(createDetailBox("MAKE / MODEL", (app.getMake() + " " + app.getModel()).trim(), "#0f172a"), 1, 0);
                grid.add(createDetailBox("YEAR / COLOR", app.getYear() + " • " + app.getColor(), "#0f172a"), 2, 0);
                grid.add(createDetailBox("STUDENT / HOLDER ID", app.getOwnerId(), "#0f172a"), 0, 1);
                grid.add(createDetailBox("STICKER PASS TYPE", app.passTypeProperty().get(), "#0f172a"), 1, 1);
                grid.add(createDetailBox("REGISTERED APPLICANT", app.ownerNameProperty().get(), "#0f172a"), 2, 1);

                VBox sheet = new VBox(14, new HBox(10, issuerCol, refBadge), grid);
                sheet.setPadding(new Insets(18, 20, 16, 20));
                sheet.setStyle("-fx-background-color: #f8fafc; -fx-border-color: #e2e8f0; -fx-border-radius: 10; -fx-background-radius: 10;");
                if (file != null && file.exists()) {
                    sheet.setStyle(sheet.getStyle() + " -fx-cursor: hand;");
                    sheet.setOnMouseClicked(e -> { try { Main.openExternalDocument(file.toURI().toString()); } catch (Exception ex) { showLoadedDocumentPreview(docKey, file, null); } });
                }
                viewer.getChildren().add(sheet);
            }

            boolean isRej = "REJECTED".equalsIgnoreCase(app.getStatus()) && rejectedDocs.contains(docKey);
            Label statusLbl = isRej ? styledLabel("●  Flagged / Rejected Document", "-fx-font-size: 11px; -fx-font-weight: bold; -fx-text-fill: #dc2626;", false)
                    : styledLabel(file != null ? "●  Uploaded & Readable (" + file.getName() + ")" : "●  Pre-screened Record Metadata", "-fx-font-size: 11px; -fx-font-weight: bold; -fx-text-fill: #059669;", false);
            viewer.getChildren().add(new HBox(10, statusLbl, hSpacer(), styledLabel("Ref: " + app.plateNumberProperty().get() + " • " + app.getOwnerId(), "-fx-font-size: 10.5px; -fx-text-fill: #94a3b8;", false)));
        }, ignored -> {});
    }

    // Quick-inspect modal triggered from the Admin Overview queue (opens immediately without blocking on remote files)
    private void showAdminInspectApplicantModal(VehicleApplication app) {
        AtomicReference<Runnable> closeRef = new AtomicReference<>(() -> {});
        VBox root = buildModalShell(640, "Applicant Verification — " + app.ownerNameProperty().get(), () -> closeRef.get().run());

        GridPane grid = createTwoColGrid();
        grid.add(createDetailBox("APPLICANT / STUDENT ID", app.ownerNameProperty().get() + " (" + app.getOwnerId() + ")", "#1e293b"), 0, 0);
        grid.add(createDetailBox("VEHICLE & PLATE", app.getMake() + " " + app.getModel() + " • " + app.plateNumberProperty().get(), "#1e293b"), 1, 0);
        grid.add(createDetailBox("PASS TYPE", app.passTypeProperty().get(), "#1e293b"), 0, 1);
        grid.add(createDetailBox("OWNERSHIP", app.getOwnership(), "#1e293b"), 1, 1);

        VBox docsCard = new VBox();
        docsCard.setStyle("-fx-background-color: #ffffff; -fx-background-radius: 10; -fx-border-color: #e2e8f0; -fx-border-radius: 10;");
        List<String> keys = getRequiredDocKeys(app);
        for (int i = 0; i < keys.size(); i++) {
            String k = keys.get(i);
            Label viewLink = styledLabel("View", "-fx-font-size: 12px; -fx-font-weight: bold; -fx-text-fill: #b71c1c; -fx-cursor: hand;", false);
            viewLink.setOnMouseClicked(e -> fetchAndPreviewDocument(k, app.getOwnerId(), k, viewLink));
            HBox row = new HBox(12,
                    styledLabel(k, "-fx-font-size: 13px; -fx-text-fill: #334155;", false),
                    hSpacer(),
                    styledLabel(capitalize(effectiveStatus(app)), "-fx-font-size: 12px; -fx-font-weight: bold; -fx-text-fill: #d97706;", false),
                    viewLink);
            row.setPadding(new Insets(12, 16, 12, 16));
            docsCard.getChildren().add(row);
            if (i < keys.size() - 1) docsCard.getChildren().add(new Separator());
        }

        HBox footer = new HBox(10);
        footer.setAlignment(Pos.CENTER_RIGHT);
        if ("PENDING".equalsIgnoreCase(app.getStatus())) {
            Button btnGo = styledButton("Proceed to Records to Approve/Reject →", "", e -> {
                closeRef.get().run();
                adminRecordsFilter = "PENDING";
                selectedRecordAppId = app.idProperty().get();
                showAdminRecords();
            });
            btnGo.getStyleClass().add("btn-primary");
            footer.getChildren().add(btnGo);
        }
        Button btnClose = styledButton("Close", "", e -> closeRef.get().run());
        btnClose.getStyleClass().add("btn-secondary");
        footer.getChildren().add(btnClose);

        VBox body = new VBox(16, grid, styledLabel("Uploaded Applicant Documents", "-fx-font-size: 13.5px; -fx-font-weight: bold; -fx-text-fill: #0f172a;", false), docsCard, new Separator(), footer);
        body.setPadding(new Insets(22, 24, 22, 24));
        root.getChildren().add(body);
        closeRef.set(openInAppModal(root, null));
    }

    // Approves an application asynchronously and notifies the student's inbox
    private void approveApplicationAction(VehicleApplication app, Button triggerBtn, Runnable onComplete) {
        if (triggerBtn != null) {
            triggerBtn.setDisable(true);
            triggerBtn.setText("Approving...");
        }
        String ownerId = app.getOwnerId();
        int appId = app.idProperty().get();
        String vehicleDesc = app.vehicleModelProperty().get();
        String plate = app.plateNumberProperty().get();

        Main.runAsync(() -> {
            userRejectionReasons.remove(ownerId);
            userApprovalTimestamps.put(ownerId, LocalDateTime.now().format(APPROVAL_FMT));
            DatabaseManager.updateApplicationStatus(appId, "APPROVED", null);
            DatabaseManager.sendMessage("Security Desk", ownerId,
                    "Sticker Application Approved - " + vehicleDesc,
                    "Congratulations! Your vehicle sticker application for " + vehicleDesc + " (" + plate + ") has been approved. You may now claim your vehicle sticker at Gate 1 Security Office, Window 2.",
                    false);
        }, () -> {
            updateAdminSidebarBadges();
            if (onComplete != null) onComplete.run();
        }, err -> {
            if (triggerBtn != null) {
                triggerBtn.setDisable(false);
                triggerBtn.setText("✓  Approve Application");
            }
            showAlert(Alert.AlertType.ERROR, "Approval Error", "Failed to approve application: " + err.getMessage());
        });
    }

    // Opens the 3-column Reject / Flag Report modal non-blockingly
    private void rejectApplicationAction(VehicleApplication app, Runnable onComplete) {
        AtomicReference<Runnable> closeRef = new AtomicReference<>(() -> {});
        VBox root = buildModalShell(820, "REJECT/ FLAG APPLICATION REPORT", () -> closeRef.get().run());

        // Column 1: Applicant Info Card (resolves email asynchronously)
        Label lblEmailVal = styledLabel(app.getOwnerId(), "-fx-font-size: 12px; -fx-text-fill: #64748b;", true);
        Main.runAsync(() -> DatabaseManager.getUserEmailById(app.getOwnerId()), resolved -> {
            if (resolved != null && !resolved.isEmpty()) lblEmailVal.setText(resolved);
        }, ignored -> {});

        String contact = userContactNumbers.getOrDefault(app.getOwnerId(), "N/A (ID: " + app.getOwnerId() + ")");
        VBox applicantCard = new VBox(16,
                new VBox(3, styledLabel("Name:", "-fx-font-size: 13px; -fx-font-weight: bold; -fx-text-fill: #0f172a;", false),
                        styledLabel(app.ownerNameProperty().get(), "-fx-font-size: 12px; -fx-text-fill: #64748b;", true)),
                new VBox(3, styledLabel("Email:", "-fx-font-size: 13px; -fx-font-weight: bold; -fx-text-fill: #0f172a;", false),
                        lblEmailVal),
                new VBox(3, styledLabel("Contactnumber:", "-fx-font-size: 13px; -fx-font-weight: bold; -fx-text-fill: #0f172a;", false),
                        styledLabel(contact, "-fx-font-size: 12px; -fx-text-fill: #64748b;", true)));
        applicantCard.setPrefWidth(210);
        applicantCard.setMinWidth(210);
        applicantCard.setPadding(new Insets(20, 18, 20, 18));
        applicantCard.setStyle("-fx-background-color: #f3f4f6; -fx-background-radius: 10;");

        // Column 2: Reason of Report (Document Pills)
        String[] docReasons = {
                "CR/OR Document",
                "UE Registration Form",
                "Letter of Approval of Owner",
                "Driver's License",
                "Vehicle Photo"
        };
        Set<String> selectedDocs = new LinkedHashSet<>();
        VBox reasonList = new VBox(10);
        String offStyle = "-fx-background-color: #eaeaea; -fx-border-color: #dcdcdc; -fx-border-radius: 7; -fx-background-radius: 7; -fx-text-fill: #475569; -fx-font-size: 12px; -fx-font-weight: bold; -fx-padding: 11 14; -fx-cursor: hand; -fx-alignment: center;";
        String onStyle = "-fx-background-color: #f3cccc; -fx-border-color: #e5b5b5; -fx-border-radius: 7; -fx-background-radius: 7; -fx-text-fill: #c62828; -fx-font-size: 12px; -fx-font-weight: bold; -fx-padding: 11 14; -fx-cursor: hand; -fx-alignment: center;";

        for (String doc : docReasons) {
            Button pill = new Button(doc);
            pill.setMaxWidth(Double.MAX_VALUE);
            pill.setPrefHeight(42);
            pill.setStyle(offStyle);
            pill.setOnAction(e -> {
                if (selectedDocs.remove(doc)) pill.setStyle(offStyle);
                else { selectedDocs.add(doc); pill.setStyle(onStyle); }
            });
            reasonList.getChildren().add(pill);
        }

        VBox midCol = new VBox(10,
                styledLabel("Reason of Report:", "-fx-font-size: 13.5px; -fx-font-weight: bold; -fx-text-fill: #0f172a;", false),
                reasonList);
        midCol.setPrefWidth(240);
        midCol.setMinWidth(230);

        // Column 3: Details Dropdown, Other TextArea & Submit Button
        ComboBox<String> cmbDetails = new ComboBox<>();
        cmbDetails.getItems().addAll(
                "Missing / Not Uploaded",
                "Expired Document",
                "Blurry / Unreadable Scan",
                "Plate Number / Vehicle Details Mismatch",
                "Invalid / Unsigned Authorization Letter",
                "Duplicate Plate / Existing Active Sticker"
        );
        cmbDetails.setPromptText("Select issue detail...");
        cmbDetails.setMaxWidth(Double.MAX_VALUE);
        cmbDetails.setPrefHeight(38);
        cmbDetails.setStyle("-fx-background-color: #eaeaea; -fx-border-color: #dcdcdc; -fx-border-radius: 7; -fx-background-radius: 7; -fx-font-size: 12px;");

        TextArea txtOther = new TextArea();
        txtOther.setPromptText("Please Specify the details.");
        txtOther.setPrefRowCount(6);
        txtOther.setWrapText(true);
        txtOther.setStyle("-fx-control-inner-background: #eaeaea; -fx-background-color: #eaeaea; -fx-border-color: #dcdcdc; -fx-border-radius: 7; -fx-background-radius: 7; -fx-font-size: 12px;");
        VBox.setVgrow(txtOther, Priority.ALWAYS);

        Button btnSubmit = styledButton("Submit", "-fx-background-color: #b71c1c; -fx-text-fill: #ffffff; -fx-font-weight: bold; -fx-font-size: 13px; -fx-padding: 10 26; -fx-background-radius: 7; -fx-cursor: hand;", null);
        btnSubmit.setOnAction(e -> {
            String detailChoice = cmbDetails.getValue() != null ? cmbDetails.getValue().trim() : "";
            String other = txtOther.getText().trim();

            if (selectedDocs.isEmpty() && detailChoice.isEmpty() && other.isEmpty()) {
                showAlert(Alert.AlertType.WARNING, "Reason Required", "Please select a Reason of Report, choose Details, or specify remarks under Other.");
                return;
            }

            List<String> summaryParts = new ArrayList<>();
            if (!selectedDocs.isEmpty()) {
                String docJoin = String.join(", ", selectedDocs);
                summaryParts.add(!detailChoice.isEmpty() ? docJoin + " (" + detailChoice + ")" : docJoin);
            } else if (!detailChoice.isEmpty()) {
                summaryParts.add(detailChoice);
            }
            if (!other.isEmpty()) summaryParts.add(other);
            String summary = String.join("; ", summaryParts);
            userRejectionReasons.put(app.getOwnerId(), summary);

            StringBuilder body = new StringBuilder("Your vehicle sticker application for ").append(app.getMake()).append(" ").append(app.getModel())
                    .append(" (").append(app.plateNumberProperty().get()).append(") has been rejected/flagged by Gate 1 Security Office.\n\n");
            if (!selectedDocs.isEmpty()) {
                body.append("Flagged Document(s):\n");
                selectedDocs.forEach(d -> body.append("• ").append(d).append("\n"));
            }
            if (!detailChoice.isEmpty()) body.append("\nDetails: ").append(detailChoice).append("\n");
            if (!other.isEmpty()) body.append("\nOther Remarks:\n").append(other).append("\n");
            body.append("\nPlease open your Vehicle Details card on your Dashboard to replace and resubmit the flagged document(s).");

            btnSubmit.setDisable(true);
            btnSubmit.setText("Submitting...");
            int appId = app.idProperty().get();
            String ownerId = app.getOwnerId();
            String ownerName = app.ownerNameProperty().get();
            String reportBody = body.toString();

            Main.runAsync(() -> {
                DatabaseManager.updateApplicationStatus(appId, "REJECTED", summary);
                DatabaseManager.sendMessage("Security Desk", ownerId, "Sticker Application Rejected / Flagged Report", reportBody, false);
            }, () -> {
                officeStickerStock++;
                updateAdminSidebarBadges();
                closeRef.get().run();
                if (onComplete != null) onComplete.run();
                showAlert(Alert.AlertType.INFORMATION, "Rejection Report Sent", "The rejection report has been sent to " + ownerName + "'s inbox.");
            }, err -> {
                btnSubmit.setDisable(false);
                btnSubmit.setText("Submit");
                showAlert(Alert.AlertType.ERROR, "Submission Error", "Failed to submit rejection report: " + err.getMessage());
            });
        });

        HBox submitRow = new HBox(btnSubmit);
        submitRow.setAlignment(Pos.CENTER_RIGHT);

        VBox rightCol = new VBox(10,
                styledLabel("Details:", "-fx-font-size: 13.5px; -fx-font-weight: bold; -fx-text-fill: #0f172a;", false),
                cmbDetails,
                styledLabel("Other:", "-fx-font-size: 13.5px; -fx-font-weight: bold; -fx-text-fill: #0f172a;", false),
                txtOther,
                submitRow);
        HBox.setHgrow(rightCol, Priority.ALWAYS);

        HBox content = new HBox(22, applicantCard, midCol, rightCol);
        content.setPadding(new Insets(24, 26, 22, 26));
        root.getChildren().add(content);
        closeRef.set(openInAppModal(root, null));
    }

    // =========================================================================
    // UNIFIED INBOX & COMPOSE MODAL (SHARED BY ADMIN & STUDENT)
    // =========================================================================
    @FXML private void showUserInbox() { renderInboxWorkspace(false, "RECEIVED", null); }

    private void renderInboxWorkspace(boolean isAdmin, String activeTab, InboxMessage preselected) {
        final int token = isAdmin ? ++adminNavToken : ++userNavToken;
        if (!isAdmin) setActiveUserNav(btnNavInbox);
        else updateAdminSidebarBadges();

        VBox targetRoot = isAdmin ? adminContentRoot : userContentRoot;
        User currentUser = Main.getCurrentUser();
        if (currentUser == null) return;

        targetRoot.getChildren().clear();
        targetRoot.setMaxHeight(Double.MAX_VALUE);
        VBox.setVgrow(targetRoot, Priority.ALWAYS);

        ScrollPane activeScroll = isAdmin ? adminScrollPane : userScrollPane;
        if (activeScroll != null) activeScroll.setFitToHeight(true);

        boolean isSent = "SENT".equalsIgnoreCase(activeTab);
        String activePill = "-fx-background-color: #ffffff; -fx-text-fill: #e11d48; -fx-font-weight: bold; -fx-font-size: 12px; -fx-padding: 6 12; -fx-background-radius: 16; -fx-effect: dropshadow(three-pass-box, rgba(0,0,0,0.1), 4, 0, 0, 1); -fx-cursor: hand;";
        String idlePill = "-fx-background-color: transparent; -fx-text-fill: #64748b; -fx-font-weight: bold; -fx-font-size: 12px; -fx-padding: 6 12; -fx-background-radius: 16; -fx-cursor: hand;";

        Button btnRec = styledButton("📥 Received", isSent ? idlePill : activePill, e -> renderInboxWorkspace(isAdmin, "RECEIVED", null));
        Button btnSent = styledButton("✈ Sent", isSent ? activePill : idlePill, e -> renderInboxWorkspace(isAdmin, "SENT", null));
        btnRec.setMaxWidth(Double.MAX_VALUE);
        btnSent.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(btnRec, Priority.ALWAYS);
        HBox.setHgrow(btnSent, Priority.ALWAYS);

        HBox tabRow = new HBox(4, btnRec, btnSent);
        tabRow.setStyle("-fx-background-color: #f1f5f9; -fx-background-radius: 20; -fx-padding: 4;");
        VBox listHeader = new VBox(16,
                new HBox(10, styledLabel("MESSAGES", "-fx-font-size: 14px; -fx-font-weight: bold; -fx-text-fill: #334155;", false), hSpacer(),
                        styledLabel("0 Unread", "-fx-background-color: #ffe4e6; -fx-text-fill: #e11d48; -fx-font-weight: bold; -fx-font-size: 11px; -fx-padding: 4 10; -fx-background-radius: 12;", false)),
                tabRow);
        listHeader.setPadding(new Insets(18, 18, 14, 18));
        listHeader.setStyle("-fx-border-color: #e2e8f0; -fx-border-width: 0 0 1 0;");

        VBox listItems = new VBox(createInlineLoadingRow("Loading messages..."));
        VBox readerPane = new VBox(20, createInlineLoadingRow("Fetching inbox from server..."));
        readerPane.setAlignment(Pos.CENTER);
        HBox.setHgrow(readerPane, Priority.ALWAYS);
        readerPane.setPadding(new Insets(26, 30, 26, 30));
        readerPane.setStyle("-fx-background-color: #ffffff; -fx-background-radius: 14; -fx-border-color: #e2e8f0; -fx-border-radius: 14;");

        ScrollPane listScroll = new ScrollPane(listItems);
        listScroll.setFitToWidth(true);
        listScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        listScroll.setStyle("-fx-background-color: transparent; -fx-background: transparent;");
        VBox.setVgrow(listScroll, Priority.ALWAYS);

        VBox leftPane = new VBox(listHeader, listScroll);
        leftPane.setMinWidth(340);
        leftPane.setPrefWidth(340);
        leftPane.setMaxWidth(340);
        leftPane.setStyle("-fx-background-color: #ffffff; -fx-background-radius: 14; -fx-border-color: #e2e8f0; -fx-border-radius: 14;");

        HBox split = new HBox(20, leftPane, readerPane);
        split.setMinHeight(560);
        VBox.setVgrow(split, Priority.ALWAYS);

        Button btnCompose = styledButton("✎",
                "-fx-background-color: #b71c1c; -fx-text-fill: white; -fx-font-size: 24px; -fx-background-radius: 30; -fx-min-width: 60; -fx-min-height: 60; -fx-effect: dropshadow(three-pass-box, rgba(0,0,0,0.3), 10, 0, 0, 4); -fx-cursor: hand;",
                e -> showComposeModal(isAdmin, null, null));
        StackPane.setAlignment(btnCompose, Pos.BOTTOM_RIGHT);
        StackPane.setMargin(btnCompose, new Insets(0, 30, 30, 0));

        StackPane stack = new StackPane(split, btnCompose);
        VBox.setVgrow(stack, Priority.ALWAYS);
        targetRoot.getChildren().add(stack);

        final String userId = currentUser.getId();
        final String userName = currentUser.getName();
        final String userEmail = currentUser.getEmail();

        Main.runAsync(() -> isAdmin
                ? (isSent ? DatabaseManager.getAdminSentMessages() : DatabaseManager.getAdminReceivedMessages())
                : (isSent ? DatabaseManager.getUserSentMessages(userId, userName) : DatabaseManager.getUserMessages(userId, userEmail)),
                messages -> {
                    int latestToken = isAdmin ? adminNavToken : userNavToken;
                    if (token != latestToken || Main.getCurrentUser() == null) return;

                    listItems.getChildren().clear();
                    readerPane.getChildren().clear();

                    if (messages.isEmpty()) {
                        VBox emptyList = new VBox(styledLabel("No " + (isSent ? "sent" : "received") + " messages found.", "-fx-font-size: 12px; -fx-text-fill: #94a3b8;", false));
                        emptyList.setAlignment(Pos.CENTER);
                        emptyList.setPadding(new Insets(40, 20, 40, 20));
                        listItems.getChildren().add(emptyList);

                        StackPane circle = new StackPane(styledLabel("✉", "-fx-font-size: 32px; -fx-text-fill: #fda4af;", false));
                        circle.setStyle("-fx-background-color: #fff1f2; -fx-background-radius: 50%; -fx-min-width: 70; -fx-min-height: 70; -fx-max-width: 70; -fx-max-height: 70;");
                        VBox emptyReader = new VBox(16, circle,
                                styledLabel("No Message Selected", "-fx-font-size: 16px; -fx-font-weight: bold; -fx-text-fill: #334155;", false),
                                styledLabel("Select an email from the left pane to view details or click \"Reply\".", "-fx-font-size: 12.5px; -fx-text-fill: #94a3b8;", true));
                        emptyReader.setAlignment(Pos.CENTER);
                        readerPane.setAlignment(Pos.CENTER);
                        readerPane.getChildren().add(emptyReader);
                    } else {
                        List<VBox> cards = new ArrayList<>();
                        InboxMessage selected = preselected != null ? preselected : messages.get(0);
                        for (InboxMessage msg : messages) {
                            String clean = extractCleanMessageBody(msg.body);
                            String snippet = (extractAttachmentFileName(msg.body) != null ? "📎 " : "") + (clean.length() > 85 ? clean.substring(0, 85) + "..." : clean);
                            VBox card = new VBox(5,
                                    new HBox(styledLabel(msg.category.toUpperCase(), "-fx-font-size: 10px; -fx-font-weight: bold; -fx-text-fill: #b71c1c;", false), hSpacer(), styledLabel(msg.shortDate, "-fx-font-size: 11px; -fx-text-fill: #94a3b8;", false)),
                                    styledLabel(msg.subject, "-fx-font-size: 13px; -fx-font-weight: bold; -fx-text-fill: #0f172a;", true),
                                    styledLabel(snippet, "-fx-font-size: 11.5px; -fx-text-fill: #64748b;", true));
                            card.getStyleClass().add(msg == selected ? "inbox-item-active" : "inbox-item");
                            cards.add(card);
                            card.setOnMouseClicked(e -> {
                                cards.forEach(c -> c.getStyleClass().setAll("inbox-item"));
                                card.getStyleClass().setAll("inbox-item-active");
                                renderMessageReader(readerPane, msg, isAdmin, isSent);
                            });
                            listItems.getChildren().add(card);
                        }
                        renderMessageReader(readerPane, selected, isAdmin, isSent);
                    }
                }, err -> {
                    int latestToken = isAdmin ? adminNavToken : userNavToken;
                    if (token != latestToken) return;
                    listItems.getChildren().setAll(styledLabel("Unable to load messages.", "-fx-padding: 20; -fx-text-fill: #dc2626;", true));
                    readerPane.getChildren().setAll(styledLabel("Failed to connect to server: " + err.getMessage(), "-fx-text-fill: #dc2626;", true));
                });
    }

    private void renderMessageReader(VBox readerPane, InboxMessage msg, boolean isAdmin, boolean isSent) {
        readerPane.getChildren().clear();
        readerPane.setAlignment(Pos.TOP_LEFT);

        String initialRoute;
        if (isAdmin) {
            String rec = "ALL".equalsIgnoreCase(msg.recipient) ? "All Students & Faculty" : msg.recipient;
            initialRoute = isSent ? "From: Security Office   •   To: " + rec : "From: " + msg.sender + "   •   To: UE Security Desk";
        } else {
            User cur = Main.getCurrentUser();
            initialRoute = isSent ? "To: Gate 1 Security Office (Admin)   •   From: " + msg.sender : "From: " + msg.sender + "   •   To: " + (cur != null ? cur.getName() : "");
        }
        Label routeLbl = styledLabel(initialRoute, "-fx-font-size: 12px; -fx-text-fill: #64748b;", true);

        if (isAdmin && isSent && !"ALL".equalsIgnoreCase(msg.recipient) && !msg.recipient.contains("@")) {
            Main.runAsync(() -> DatabaseManager.getUserEmailById(msg.recipient),
                    resolved -> routeLbl.setText("From: Security Office   •   To: " + resolved),
                    ignored -> {});
        }

        VBox bodyCard = new VBox(12, styledLabel(extractCleanMessageBody(msg.body), "-fx-font-size: 13.5px; -fx-text-fill: #334155; -fx-line-spacing: 4;", true));
        bodyCard.setPadding(new Insets(20, 22, 20, 22));
        bodyCard.setStyle("-fx-background-color: #f8fafc; -fx-background-radius: 12; -fx-border-color: #f1f5f9; -fx-border-radius: 12;");
        String attachFile = extractAttachmentFileName(msg.body);
        if (attachFile != null) bodyCard.getChildren().add(createMessageAttachmentCard(attachFile));

        VBox actionTexts = new VBox(3,
                styledLabel(!isSent ? (isAdmin ? "Respond to this inquiry?" : "Reply to Security Desk?") : (isAdmin ? "Send another notice?" : "Need further assistance?"), "-fx-font-size: 13px; -fx-font-weight: bold; -fx-text-fill: #0f172a;", false),
                styledLabel(!isSent ? "Send an official response directly." : "Compose a new message or notice.", "-fx-font-size: 12px; -fx-text-fill: #64748b;", true));
        HBox.setHgrow(actionTexts, Priority.ALWAYS);

        String actionLabel = !isSent ? (isAdmin ? "Reply to Student →" : "Reply to Admin →") : (isAdmin ? "Compose Notice →" : "Compose Letter →");
        Button btnAction = styledButton(actionLabel, "", null);
        btnAction.getStyleClass().add("btn-primary");
        btnAction.setOnAction(e -> {
            if (isAdmin && !isSent) {
                if (msg.sender.contains("(") && msg.sender.lastIndexOf(')') > msg.sender.lastIndexOf('(')) {
                    String extractedId = msg.sender.substring(msg.sender.lastIndexOf('(') + 1, msg.sender.lastIndexOf(')'));
                    btnAction.setDisable(true);
                    btnAction.setText("Loading...");
                    Main.runAsync(() -> DatabaseManager.getUserEmailById(extractedId), email -> {
                        btnAction.setDisable(false);
                        btnAction.setText(actionLabel);
                        showComposeModal(true, email, "Re: " + msg.subject);
                    }, err -> {
                        btnAction.setDisable(false);
                        btnAction.setText(actionLabel);
                        showComposeModal(true, extractedId, "Re: " + msg.subject);
                    });
                } else {
                    showComposeModal(true, msg.sender, "Re: " + msg.subject);
                }
            } else {
                showComposeModal(isAdmin, null, null);
            }
        });

        HBox actionBar = new HBox(14, actionTexts, btnAction);
        actionBar.setAlignment(Pos.CENTER_LEFT);
        actionBar.setPadding(new Insets(16, 20, 16, 20));
        actionBar.setStyle("-fx-background-color: #f1f5f9; -fx-background-radius: 12;");

        readerPane.getChildren().addAll(
                new HBox(10, styledLabel(msg.category.toUpperCase(), "-fx-background-color: #fff1f2; -fx-text-fill: #b71c1c; -fx-font-weight: bold; -fx-font-size: 10.5px; -fx-padding: 5 12; -fx-background-radius: 6;", false),
                        styledLabel("•  " + msg.fullTimestamp, "-fx-font-size: 12px; -fx-text-fill: #94a3b8;", false)),
                styledLabel(msg.subject, "-fx-font-size: 20px; -fx-font-weight: bold; -fx-text-fill: #0f172a;", true),
                routeLbl,
                new Separator(), bodyCard, actionBar);
    }

    // Unified Compose Modal for both Admin (Broadcast/Direct Email) and Student (Letter to Admin)
    private void showComposeModal(boolean isAdmin, String defaultEmail, String defaultSubject) {
        AtomicReference<File> attachRef = new AtomicReference<>(null);
        AtomicReference<Runnable> closeRef = new AtomicReference<>(() -> {});
        VBox root = buildModalShell(540, isAdmin ? "✈  Send Notice / Reply to Student" : "✉  Compose Letter to Admin", () -> closeRef.get().run());

        VBox body = new VBox(14);
        body.setPadding(new Insets(20, 24, 22, 24));

        AtomicReference<String> targetMode = new AtomicReference<>((defaultEmail != null && !defaultEmail.isEmpty()) ? "DIRECT" : "ALL");
        TextField txtEmail = new TextField(defaultEmail != null ? defaultEmail : "");
        txtEmail.setPromptText("Enter Student Email (e.g., juan.delacruz@ue.edu.ph)");
        txtEmail.getStyleClass().add("input-field");
        VBox emailBox = new VBox(5, new Label("Student Email"), txtEmail);

        if (isAdmin) {
            Button btnAll = new Button("📢  All Students (Broadcast)");
            Button btnOne = new Button("👤  Specific Student Email");
            btnAll.setMaxWidth(Double.MAX_VALUE);
            btnOne.setMaxWidth(Double.MAX_VALUE);
            HBox.setHgrow(btnAll, Priority.ALWAYS);
            HBox.setHgrow(btnOne, Priority.ALWAYS);

            boolean initDirect = "DIRECT".equals(targetMode.get());
            btnAll.getStyleClass().setAll(initDirect ? "toggle-pill" : "toggle-pill-active");
            btnOne.getStyleClass().setAll(initDirect ? "toggle-pill-active" : "toggle-pill");
            emailBox.setVisible(initDirect);
            emailBox.setManaged(initDirect);

            btnAll.setOnAction(e -> { targetMode.set("ALL"); btnAll.getStyleClass().setAll("toggle-pill-active"); btnOne.getStyleClass().setAll("toggle-pill"); emailBox.setVisible(false); emailBox.setManaged(false); });
            btnOne.setOnAction(e -> { targetMode.set("DIRECT"); btnOne.getStyleClass().setAll("toggle-pill-active"); btnAll.getStyleClass().setAll("toggle-pill"); emailBox.setVisible(true); emailBox.setManaged(true); });
            body.getChildren().addAll(new VBox(6, styledLabel("Recipient Scope", "-fx-font-size: 12px; -fx-font-weight: bold;", false), new HBox(10, btnAll, btnOne)), emailBox);
        } else {
            HBox toRow = new HBox(8, styledLabel("Recipient:", "-fx-font-size: 12px; -fx-font-weight: bold; -fx-text-fill: #475569;", false),
                    styledLabel("Gate 1 Security Desk (Admin)", "-fx-background-color: #fff1f2; -fx-text-fill: #b71c1c; -fx-font-weight: bold; -fx-font-size: 11.5px; -fx-padding: 4 10; -fx-background-radius: 6;", false));
            toRow.setAlignment(Pos.CENTER_LEFT);
            body.getChildren().add(toRow);
        }

        TextField txtSubj = new TextField(defaultSubject != null ? defaultSubject : "");
        txtSubj.setPromptText("Enter subject...");
        txtSubj.getStyleClass().add("input-field");
        TextArea txtMsg = new TextArea();
        txtMsg.setPromptText("Write your message...");
        txtMsg.setPrefRowCount(4);
        txtMsg.setWrapText(true);
        txtMsg.getStyleClass().add("input-field");

        Label lblAttach = styledLabel("No file attached", "-fx-font-size: 12px; -fx-text-fill: #64748b;", false);
        HBox.setHgrow(lblAttach, Priority.ALWAYS);
        Button btnRemAttach = styledButton("✕", "-fx-background-color: transparent; -fx-text-fill: #dc2626; -fx-font-weight: bold; -fx-cursor: hand;", null);
        btnRemAttach.setVisible(false);
        btnRemAttach.setManaged(false);

        Button btnAttach = styledButton("📎 Attach File", "", e -> {
            FileChooser fc = new FileChooser();
            fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("Images, PDF & Docs", "*.png", "*.jpg", "*.jpeg", "*.pdf", "*.doc", "*.docx"));
            File chosen = fc.showOpenDialog(Main.getPrimaryStage());
            if (chosen != null) {
                attachRef.set(chosen);
                lblAttach.setText("✓ " + chosen.getName());
                lblAttach.setStyle("-fx-font-size: 12px; -fx-font-weight: bold; -fx-text-fill: #059669;");
                btnRemAttach.setVisible(true);
                btnRemAttach.setManaged(true);
            }
        });
        btnAttach.getStyleClass().add("btn-secondary");
        btnRemAttach.setOnAction(e -> { attachRef.set(null); lblAttach.setText("No file attached"); lblAttach.setStyle("-fx-font-size: 12px; -fx-text-fill: #64748b;"); btnRemAttach.setVisible(false); btnRemAttach.setManaged(false); });

        Button btnCancel = styledButton("Cancel", "", e -> closeRef.get().run());
        btnCancel.getStyleClass().add("btn-secondary");
        String defaultSendLabel = isAdmin ? "✈  Send Notice" : "✈  Send Letter";
        Button btnSend = styledButton(defaultSendLabel, "", null);
        btnSend.getStyleClass().add("btn-primary");
        btnSend.setOnAction(e -> {
            String subj = txtSubj.getText().trim(), msg = txtMsg.getText().trim();
            boolean broadcast = isAdmin && "ALL".equals(targetMode.get());
            String recipient = isAdmin ? (broadcast ? "ALL" : txtEmail.getText().trim()) : "ADMIN";
            if (subj.isEmpty() || msg.isEmpty() || (isAdmin && !broadcast && !recipient.contains("@"))) {
                showAlert(Alert.AlertType.WARNING, "Invalid Input", "Please complete all fields and enter a valid email address.");
                return;
            }
            User u = Main.getCurrentUser();
            if (u == null) return;
            String senderLabel = isAdmin ? "Security Office" : u.getName() + " (" + u.getId() + ")";
            File chosenAttach = attachRef.get();

            btnSend.setDisable(true);
            btnCancel.setDisable(true);
            btnSend.setText("Sending...");

            Main.runAsync(() -> {
                File savedAttach = chosenAttach != null ? saveMessageAttachment(chosenAttach) : null;
                DatabaseManager.sendMessage(senderLabel, recipient, subj, attachFileToMessageBody(msg, savedAttach), broadcast);
            }, () -> {
                closeRef.get().run();
                renderInboxWorkspace(isAdmin, "SENT", null);
                showAlert(Alert.AlertType.INFORMATION, "Sent", "Your message has been delivered.");
            }, err -> {
                btnSend.setDisable(false);
                btnCancel.setDisable(false);
                btnSend.setText(defaultSendLabel);
                showAlert(Alert.AlertType.ERROR, "Send Error", "Failed to send message: " + err.getMessage());
            });
        });

        HBox footer = new HBox(10, btnCancel, btnSend);
        footer.setAlignment(Pos.CENTER_RIGHT);
        body.getChildren().addAll(new VBox(5, new Label("Subject"), txtSubj), new VBox(5, new Label("Message"), txtMsg),
                new HBox(10, btnAttach, lblAttach, btnRemAttach), new Separator(), footer);
        root.getChildren().add(body);
        closeRef.set(openInAppModal(root, null));
    }

    // =========================================================================
    // STUDENT / USER PORTAL: DASHBOARD, E-PASS, EXPIRATION REAPPLY & SUPPORT
    // =========================================================================
    private void setActiveUserNav(Button activeBtn) {
        if (btnNavDashboard == null) return;
        for (Button b : List.of(btnNavDashboard, btnNavInbox, btnNavSupport)) b.getStyleClass().setAll("nav-button");
        if (activeBtn != null) activeBtn.getStyleClass().setAll("nav-button-active");
    }

    @FXML public void showStudentDashboard() {
        if (userContentRoot != null) { setActiveUserNav(btnNavDashboard); renderDashboardView(); }
        else Main.loadScene("UserView.fxml", 1600, 900);
    }

    // Renders Student Dashboard asynchronously: Expired Reapply View, Active Digital E-Pass, or Pending/Rejected/New Vehicle card
    private void renderDashboardView() {
        final int token = ++userNavToken;
        User user = Main.getCurrentUser();
        if (user == null) return;

        if (userScrollPane != null) userScrollPane.setFitToHeight(false);
        userContentRoot.setMaxHeight(Region.USE_COMPUTED_SIZE);
        userContentRoot.getChildren().clear();

        lblWelcome = styledLabel("Welcome, " + user.getName(), "-fx-font-size: 22px; -fx-font-weight: bold; -fx-text-fill: #ffffff;", false);
        lblUserInfo = styledLabel("Student No: " + user.getId() + "  |  " + user.getDepartment(), "-fx-font-size: 13px; -fx-text-fill: #fee2e2;", false);
        VBox welcomeBox = new VBox(4, lblWelcome, lblUserInfo);
        HBox.setHgrow(welcomeBox, Priority.ALWAYS);

        StackPane iconCircle = new StackPane(styledLabel("🚗", "-fx-text-fill: #ffffff; -fx-font-size: 14px;", false));
        iconCircle.setStyle("-fx-background-color: #b71c1c; -fx-background-radius: 50; -fx-min-width: 34; -fx-min-height: 34;");
        lblStockRemaining = styledLabel(officeStickerStock + " / 500 Available", "-fx-font-size: 13.5px; -fx-font-weight: bold; -fx-text-fill: #b71c1c;", false);
        HBox stockBadge = new HBox(12, iconCircle, new VBox(1, styledLabel("STICKERS REMAINING", "-fx-font-size: 9.5px; -fx-font-weight: bold; -fx-text-fill: #991b1b;", false), lblStockRemaining));
        stockBadge.setAlignment(Pos.CENTER_LEFT);
        stockBadge.setStyle("-fx-background-color: #ffffff; -fx-border-color: #fecdd3; -fx-border-radius: 12; -fx-background-radius: 12; -fx-padding: 10 16; -fx-effect: dropshadow(three-pass-box, rgba(0,0,0,0.12), 6, 0, 0, 2);");

        HBox headerCard = new HBox(welcomeBox, stockBadge);
        headerCard.setAlignment(Pos.CENTER_LEFT);
        headerCard.setStyle("-fx-background-color: #b71c1c; -fx-background-radius: 14; -fx-padding: 22 26; -fx-effect: dropshadow(three-pass-box, rgba(0,0,0,0.08), 8, 0, 0, 2);");

        VBox loadingCard = createLoadingCard("Loading your vehicle pass & sticker status...");
        userContentRoot.getChildren().addAll(headerCard, loadingCard);

        final String userId = user.getId();
        Main.runAsync(() -> {
            VehicleApplication app = DatabaseManager.getUserApplication(userId);
            Image vehiclePhoto = (app != null) ? loadImageFromFile(findUploadedFile(userId, "Vehicle Photo")) : null;
            return new AbstractMap.SimpleEntry<>(app, vehiclePhoto);
        }, entry -> {
            if (token != userNavToken || Main.getCurrentUser() == null) return;
            VehicleApplication app = entry.getKey();
            Image vehiclePhoto = entry.getValue();
            cachedUserApp = app;

            userContentRoot.getChildren().remove(loadingCard);

            if (app != null && app.isExpired()) {
                userContentRoot.getChildren().addAll(
                        buildExpiredPermitDashboard(user, app, vehiclePhoto),
                        buildQuotaBanner(true));
            } else if (app != null && "APPROVED".equalsIgnoreCase(app.getStatus())) {
                userContentRoot.getChildren().addAll(
                        buildApprovedPermitDashboard(user, app, vehiclePhoto),
                        buildQuotaBanner(true));
            } else {
                HBox secBar = new HBox(styledLabel("🚗  My Registered Vehicles", "-fx-font-size: 15px; -fx-font-weight: bold; -fx-text-fill: #1e293b;", false), hSpacer(),
                        styledLabel("Click any vehicle card to view details", "-fx-font-size: 12px; -fx-text-fill: #64748b;", false));
                secBar.setAlignment(Pos.CENTER_LEFT);
                secBar.getStyleClass().add("section-bar");
                vboxVehicleContainer = new HBox(18);
                populateUserVehicleCard(app, vehiclePhoto);
                userContentRoot.getChildren().addAll(secBar, vboxVehicleContainer, buildQuotaBanner(false));
            }
        }, err -> {
            if (token != userNavToken) return;
            userContentRoot.getChildren().setAll(headerCard, styledLabel("Unable to load vehicle pass: " + err.getMessage(), "-fx-padding: 20; -fx-text-fill: #dc2626;", true));
        });
    }

    // Expired Digital Permit View: shows expired E-Pass with a single "Sticker expired Click here to reapply" button
    private HBox buildExpiredPermitDashboard(User user, VehicleApplication app, Image vehiclePhoto) {
        VBox permitTitle = new VBox(2,
                styledLabel("EXPIRED DIGITAL PERMIT", "-fx-font-size: 9.5px; -fx-font-weight: bold; -fx-text-fill: #fecdd3;", false),
                styledLabel("UE CAMPUS E-PASS", "-fx-font-size: 15.5px; -fx-font-weight: bold; -fx-text-fill: #ffffff;", false));
        HBox.setHgrow(permitTitle, Priority.ALWAYS);
        HBox permitHeader = new HBox(10, permitTitle, styledLabel("EXPIRED", "-fx-background-color: #dc2626; -fx-border-color: #fca5a5; -fx-border-radius: 20; -fx-background-radius: 20; -fx-text-fill: #ffffff; -fx-font-size: 10.5px; -fx-font-weight: bold; -fx-padding: 5 12;", false));
        permitHeader.setPadding(new Insets(16, 18, 16, 18));
        permitHeader.setStyle("-fx-background-color: #475569; -fx-background-radius: 15 15 0 0;");

        StackPane photoStack = new StackPane();
        photoStack.setPrefSize(340, 185);
        photoStack.getChildren().add(vehiclePhoto != null ? coverImageView(vehiclePhoto, 340, 185) : styledLabel("🚘", "-fx-font-size: 48px;", false));
        photoStack.setStyle("-fx-background-color: #1e293b; -fx-opacity: 0.78;");
        photoStack.setClip(new Rectangle(340, 185));

        VBox plateBox = new VBox(2,
                styledLabel("REGISTERED PLATE (EXPIRED)", "-fx-font-size: 9.5px; -fx-font-weight: bold; -fx-text-fill: #dc2626;", false),
                styledLabel(app.plateNumberProperty().get(), "-fx-font-size: 20px; -fx-font-weight: bold; -fx-text-fill: #475569;", false),
                styledLabel((app.getMake() + " " + app.getModel()).trim() + " (" + app.getColor() + ")", "-fx-font-size: 11.5px; -fx-text-fill: #64748b;", true));
        plateBox.setPadding(new Insets(13, 15, 13, 15));
        plateBox.setStyle("-fx-background-color: #f8fafc; -fx-border-color: #cbd5e1; -fx-border-radius: 12; -fx-background-radius: 12;");

        Button btnReapplyBar = styledButton("⚠  Sticker expired Click here to reapply",
                "-fx-background-color: #fee2e2; -fx-border-color: #f87171; -fx-border-radius: 10; -fx-background-radius: 10; -fx-text-fill: #b91c1c; -fx-font-weight: bold; -fx-font-size: 11.5px; -fx-padding: 11 14; -fx-cursor: hand;",
                e -> showReapplyVehicleModal(app));
        btnReapplyBar.setMaxWidth(Double.MAX_VALUE);

        Label lblWithdraw = styledLabel("Withdraw Application", "-fx-font-size: 11.5px; -fx-font-weight: bold; -fx-text-fill: #64748b; -fx-cursor: hand;", false);
        lblWithdraw.setOnMouseClicked(e -> withdrawApplicationAction(app, null));

        VBox permitBody = new VBox(12, plateBox, btnReapplyBar, new Separator(),
                new HBox(8, styledLabel("Expired: " + app.getExpirationDateFormatted(), "-fx-font-size: 11px; -fx-font-weight: bold; -fx-text-fill: #dc2626;", false), hSpacer(), lblWithdraw));
        permitBody.setPadding(new Insets(16, 18, 16, 18));

        VBox permitCard = new VBox(permitHeader, photoStack, permitBody);
        permitCard.setPrefWidth(340);
        permitCard.setMinWidth(340);
        permitCard.setStyle("-fx-background-color: #ffffff; -fx-background-radius: 16; -fx-border-color: #fca5a5; -fx-border-width: 1.5; -fx-border-radius: 16;");

        GridPane specsGrid = createThreeColGrid();
        specsGrid.add(createDetailBox("MAKE / BRAND", app.getMake(), "#1e293b"), 0, 0);
        specsGrid.add(createDetailBox("MODEL", app.getModel(), "#1e293b"), 1, 0);
        specsGrid.add(createDetailBox("YEAR", app.getYear(), "#1e293b"), 2, 0);
        specsGrid.add(createDetailBox("COLOR", app.getColor(), "#1e293b"), 0, 1);
        specsGrid.add(createDetailBox("PLATE NUMBER", app.plateNumberProperty().get(), "#1e293b"), 1, 1);
        specsGrid.add(createDetailBox("PASS STATUS", "Expired (> 1 Year)", "#dc2626"), 2, 1);

        VBox specsTitle = new VBox(3,
                styledLabel("Vehicle Specifications", "-fx-font-size: 16px; -fx-font-weight: bold;", false),
                styledLabel("Previous registration exceeded the 1-year validity period", "-fx-font-size: 12px; -fx-text-fill: #dc2626;", false));
        HBox.setHgrow(specsTitle, Priority.ALWAYS);
        VBox specsCard = new VBox(16,
                new HBox(12, specsTitle, styledLabel("Expired on: " + app.getExpirationDateFormatted(), "-fx-background-color: #fee2e2; -fx-border-color: #fecdd3; -fx-border-radius: 16; -fx-background-radius: 16; -fx-text-fill: #b91c1c; -fx-font-size: 11px; -fx-font-weight: bold; -fx-padding: 6 12;", false)),
                specsGrid);
        specsCard.getStyleClass().add("header-card");

        List<String> docNames = getRequiredDocKeys(app);
        GridPane docsGrid = createTwoColGrid();
        for (int i = 0; i < docNames.size(); i++) {
            String doc = docNames.get(i);
            StackPane expIcon = new StackPane(styledLabel("!", "-fx-text-fill: #dc2626; -fx-font-weight: bold; -fx-font-size: 12px;", false));
            expIcon.setStyle("-fx-background-color: #fee2e2; -fx-background-radius: 50; -fx-min-width: 24; -fx-min-height: 24;");
            VBox tCol = new VBox(2,
                    styledLabel(doc, "-fx-font-size: 12px; -fx-font-weight: bold; -fx-text-fill: #334155;", true),
                    styledLabel("Expired — Re-upload Required", "-fx-font-size: 11px; -fx-font-weight: bold; -fx-text-fill: #dc2626;", false));
            HBox.setHgrow(tCol, Priority.ALWAYS);
            HBox tile = new HBox(12, expIcon, tCol);
            tile.setAlignment(Pos.CENTER_LEFT);
            tile.setPadding(new Insets(12, 14, 12, 14));
            tile.setStyle("-fx-background-color: #fff1f2; -fx-border-color: #fecdd3; -fx-border-radius: 10; -fx-background-radius: 10;");
            docsGrid.add(tile, i % 2, i / 2);
        }

        VBox docsTitle = new VBox(3,
                styledLabel("Document Renewal Required", "-fx-font-size: 16px; -fx-font-weight: bold;", false),
                styledLabel("You must upload updated copies of all required documents to renew your campus sticker", "-fx-font-size: 12px; -fx-text-fill: #64748b;", true));
        HBox.setHgrow(docsTitle, Priority.ALWAYS);

        VBox docsCard = new VBox(16, new HBox(12, docsTitle), docsGrid);
        docsCard.getStyleClass().add("header-card");

        VBox rightCol = new VBox(18, specsCard, docsCard);
        HBox.setHgrow(rightCol, Priority.ALWAYS);
        return new HBox(20, permitCard, rightCol);
    }

    private HBox buildApprovedPermitDashboard(User user, VehicleApplication app, Image vehiclePhoto) {
        // Left Digital E-Pass Card
        VBox permitTitle = new VBox(2, styledLabel("OFFICIAL DIGITAL PERMIT", "-fx-font-size: 9.5px; -fx-font-weight: bold; -fx-text-fill: #fecdd3;", false),
                styledLabel("UE CAMPUS E-PASS", "-fx-font-size: 15.5px; -fx-font-weight: bold; -fx-text-fill: #ffffff;", false));
        HBox.setHgrow(permitTitle, Priority.ALWAYS);
        HBox permitHeader = new HBox(10, permitTitle, styledLabel("1-YEAR PASS", "-fx-background-color: rgba(255,255,255,0.16); -fx-border-color: rgba(255,255,255,0.28); -fx-border-radius: 20; -fx-background-radius: 20; -fx-text-fill: #ffffff; -fx-font-size: 10.5px; -fx-font-weight: bold; -fx-padding: 5 11;", false));
        permitHeader.setPadding(new Insets(16, 18, 16, 18));
        permitHeader.setStyle("-fx-background-color: #b71c1c; -fx-background-radius: 15 15 0 0;");

        StackPane photoStack = new StackPane();
        photoStack.setPrefSize(340, 185);
        photoStack.getChildren().add(vehiclePhoto != null ? coverImageView(vehiclePhoto, 340, 185) : styledLabel("🚘", "-fx-font-size: 48px;", false));
        photoStack.setStyle("-fx-background-color: #1e293b;");
        photoStack.setClip(new Rectangle(340, 185));

        VBox plateBox = new VBox(2,
                styledLabel("REGISTERED PLATE", "-fx-font-size: 9.5px; -fx-font-weight: bold; -fx-text-fill: #94a3b8;", false),
                styledLabel(app.plateNumberProperty().get(), "-fx-font-size: 20px; -fx-font-weight: bold; -fx-text-fill: #0f172a;", false),
                styledLabel((app.getMake() + " " + app.getModel()).trim() + " (" + app.getColor() + ")", "-fx-font-size: 11.5px; -fx-text-fill: #64748b;", true));
        plateBox.setPadding(new Insets(13, 15, 13, 15));
        plateBox.setStyle("-fx-background-color: #f8fafc; -fx-border-color: #e2e8f0; -fx-border-radius: 12; -fx-background-radius: 12;");

        HBox statusBar = new HBox(8, styledLabel("●  STATUS: APPROVED", "-fx-font-size: 11.5px; -fx-font-weight: bold; -fx-text-fill: #059669;", false), hSpacer(),
                styledLabel("Valid Until " + app.getExpirationDateFormatted(), "-fx-font-size: 11px; -fx-font-weight: bold; -fx-text-fill: #059669;", false));
        statusBar.setPadding(new Insets(10, 14, 10, 14));
        statusBar.setStyle("-fx-background-color: #d1fae5; -fx-border-color: #a7f3d0; -fx-border-radius: 10; -fx-background-radius: 10;");

        Label lblWithdraw = styledLabel("Withdraw Application", "-fx-font-size: 11.5px; -fx-font-weight: bold; -fx-text-fill: #b71c1c; -fx-cursor: hand;", false);
        lblWithdraw.setOnMouseClicked(e -> withdrawApplicationAction(app, null));

        VBox permitBody = new VBox(12, plateBox, statusBar, new Separator(), new HBox(8, styledLabel("Holder: " + user.getId(), "-fx-font-size: 11px; -fx-text-fill: #94a3b8;", false), hSpacer(), lblWithdraw));
        permitBody.setPadding(new Insets(16, 18, 16, 18));

        VBox permitCard = new VBox(permitHeader, photoStack, permitBody);
        permitCard.setPrefWidth(340);
        permitCard.setMinWidth(340);
        permitCard.setStyle("-fx-background-color: #ffffff; -fx-background-radius: 16; -fx-border-color: #e2e8f0; -fx-border-radius: 16;");

        // Right Vehicle Specifications & Verified Documents
        GridPane specsGrid = createThreeColGrid();
        specsGrid.add(createDetailBox("MAKE / BRAND", app.getMake(), "#1e293b"), 0, 0);
        specsGrid.add(createDetailBox("MODEL", app.getModel(), "#1e293b"), 1, 0);
        specsGrid.add(createDetailBox("YEAR", app.getYear(), "#1e293b"), 2, 0);
        specsGrid.add(createDetailBox("COLOR", app.getColor(), "#1e293b"), 0, 1);
        specsGrid.add(createDetailBox("PLATE NUMBER", app.plateNumberProperty().get(), "#1e293b"), 1, 1);
        specsGrid.add(createDetailBox("STICKER TYPE", app.passTypeProperty().get(), "#1e293b"), 2, 1);

        VBox specsTitle = new VBox(3, styledLabel("Vehicle Specifications", "-fx-font-size: 16px; -fx-font-weight: bold;", false), styledLabel("Official LTO & Campus Security registration details", "-fx-font-size: 12px; -fx-text-fill: #64748b;", false));
        HBox.setHgrow(specsTitle, Priority.ALWAYS);
        VBox specsCard = new VBox(16, new HBox(12, specsTitle, styledLabel("Approved: " + userApprovalTimestamps.getOrDefault(user.getId(), app.getApprovalDate()) + "  •  Expires: " + app.getExpirationDateFormatted(), "-fx-background-color: #f8fafc; -fx-border-color: #e2e8f0; -fx-border-radius: 16; -fx-background-radius: 16; -fx-font-size: 11px; -fx-font-weight: bold; -fx-padding: 6 12;", false)), specsGrid);
        specsCard.getStyleClass().add("header-card");

        List<String> docNames = getRequiredDocKeys(app);
        GridPane docsGrid = createTwoColGrid();
        for (int i = 0; i < docNames.size(); i++) {
            String doc = docNames.get(i);
            StackPane chk = new StackPane(styledLabel("✓", "-fx-text-fill: #059669; -fx-font-weight: bold; -fx-font-size: 11px;", false));
            chk.setStyle("-fx-background-color: #d1fae5; -fx-background-radius: 50; -fx-min-width: 24; -fx-min-height: 24;");
            VBox tCol = new VBox(2, styledLabel(doc, "-fx-font-size: 12px; -fx-font-weight: bold;", true), styledLabel("Passed", "-fx-font-size: 11px; -fx-font-weight: bold; -fx-text-fill: #059669;", false));
            HBox.setHgrow(tCol, Priority.ALWAYS);
            Button btnView = styledButton("View", "-fx-background-color: #ffffff; -fx-border-color: #cbd5e1; -fx-border-radius: 7; -fx-background-radius: 7; -fx-text-fill: #b71c1c; -fx-font-weight: bold; -fx-font-size: 11.5px; -fx-padding: 5 12; -fx-cursor: hand;", null);
            btnView.setOnAction(e -> fetchAndPreviewDocument(doc, user.getId(), doc, btnView));
            HBox tile = new HBox(12, chk, tCol, btnView);
            tile.setAlignment(Pos.CENTER_LEFT);
            tile.setPadding(new Insets(12, 14, 12, 14));
            tile.setStyle("-fx-background-color: #f8fafc; -fx-border-color: #e2e8f0; -fx-border-radius: 10; -fx-background-radius: 10;");
            docsGrid.add(tile, i % 2, i / 2);
        }

        VBox docsTitle = new VBox(3, styledLabel("Verified Documents", "-fx-font-size: 16px; -fx-font-weight: bold;", false), styledLabel("All uploaded credentials have passed security screening", "-fx-font-size: 12px; -fx-text-fill: #64748b;", false));
        HBox.setHgrow(docsTitle, Priority.ALWAYS);
        VBox docsCard = new VBox(16, new HBox(12, docsTitle, styledLabel(docNames.size() + " / " + docNames.size() + " Passed", "-fx-background-color: #d1fae5; -fx-text-fill: #059669; -fx-font-size: 11.5px; -fx-font-weight: bold; -fx-padding: 5 12; -fx-background-radius: 14;", false)), docsGrid);
        docsCard.getStyleClass().add("header-card");

        VBox rightCol = new VBox(18, specsCard, docsCard);
        HBox.setHgrow(rightCol, Priority.ALWAYS);
        return new HBox(20, permitCard, rightCol);
    }

    private HBox buildQuotaBanner(boolean solidBtn) {
        StackPane bang = new StackPane(styledLabel("!", "-fx-text-fill: #b71c1c; -fx-font-weight: bold; -fx-font-size: 15px;", false));
        bang.setStyle("-fx-background-color: #fee2e2; -fx-background-radius: 8; -fx-min-width: 36; -fx-min-height: 36;");
        VBox text = new VBox(2, styledLabel("Strict Single-Sticker Quota (1-Year Validity)", "-fx-font-size: 13px; -fx-font-weight: bold; -fx-text-fill: #1e293b;", false),
                styledLabel("Each student is entitled to 1 registered vehicle sticker valid for 1 year from approval.", "-fx-font-size: 12px; -fx-text-fill: #64748b;", false));
        HBox.setHgrow(text, Priority.ALWAYS);
        Node cta = solidBtn
                ? styledButton("Sticker Pickup & Guidelines  →", "-fx-background-color: #b71c1c; -fx-text-fill: #ffffff; -fx-font-weight: bold; -fx-font-size: 12px; -fx-padding: 10 18; -fx-background-radius: 10; -fx-cursor: hand;", e -> showUserSupport())
                : new Hyperlink("Sticker Pickup & Guidelines →");
        if (cta instanceof Hyperlink) { cta.setStyle("-fx-text-fill: #b71c1c; -fx-font-weight: bold; -fx-font-size: 12px;"); ((Hyperlink) cta).setOnAction(e -> showUserSupport()); }
        HBox banner = new HBox(14, bang, text, cta);
        banner.setAlignment(Pos.CENTER_LEFT);
        banner.getStyleClass().add("header-card");
        return banner;
    }

    private void withdrawApplicationAction(VehicleApplication app, Runnable onModalClose) {
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION, "Withdrawing removes your vehicle record and restores your 1-sticker quota.", ButtonType.OK, ButtonType.CANCEL);
        confirm.setHeaderText("Cancel registration for " + app.getMake() + " " + app.getModel() + " (" + app.plateNumberProperty().get() + ")?");
        if (confirm.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.OK) {
            User cur = Main.getCurrentUser();
            if (cur == null) return;
            boolean wasRejectedOrExpired = "REJECTED".equalsIgnoreCase(app.getStatus()) || app.isExpired();
            int appId = app.idProperty().get();
            String userId = cur.getId();

            Main.runAsync(() -> {
                DatabaseManager.deleteApplication(appId, userId);
                deleteUserUploadedFiles(userId);
            }, () -> {
                if (!wasRejectedOrExpired) officeStickerStock++;
                cachedUserApp = null;
                if (onModalClose != null) onModalClose.run();
                renderDashboardView();
                showAlert(Alert.AlertType.INFORMATION, "Application Withdrawn", "Your sticker application has been cancelled and your quota restored.");
            }, err -> showAlert(Alert.AlertType.ERROR, "Withdraw Failed", "Could not withdraw application: " + err.getMessage()));
        }
    }

    private void populateUserVehicleCard(VehicleApplication app, Image preloadedPhoto) {
        if (vboxVehicleContainer == null) return;
        vboxVehicleContainer.getChildren().clear();

        if (app == null) {
            StackPane plus = new StackPane(styledLabel("+", "-fx-font-size: 22px; -fx-font-weight: bold; -fx-text-fill: #64748b;", false));
            plus.getStyleClass().add("plus-circle");
            VBox addCard = new VBox(8, plus, styledLabel("Add Vehicle", "-fx-font-size: 14px; -fx-font-weight: bold;", false), styledLabel("Register a Vehicle Sticker", "-fx-font-size: 12px; -fx-text-fill: #94a3b8;", false));
            addCard.setAlignment(Pos.CENTER);
            addCard.setPrefSize(320, 240);
            addCard.getStyleClass().add("add-vehicle-card");
            addCard.setOnMouseClicked(e -> showAddVehicleModal());
            vboxVehicleContainer.getChildren().add(addCard);
        } else {
            StackPane banner = new StackPane();
            banner.setPrefSize(340, 145);
            banner.setMinSize(340, 145);
            banner.setMaxSize(340, 145);
            if (preloadedPhoto != null) {
                banner.setStyle("-fx-background-color: #1e293b; -fx-background-radius: 13 13 0 0;");
                banner.getChildren().add(coverImageView(preloadedPhoto, 340, 145));
                Rectangle clip = new Rectangle(340, 145);
                clip.setArcWidth(26);
                clip.setArcHeight(26);
                banner.setClip(clip);
            } else {
                banner.setStyle("-fx-background-color: linear-gradient(to right, #991b1b, #dc2626); -fx-background-radius: 13 13 0 0;");
                banner.getChildren().add(styledLabel("🚘", "-fx-font-size: 42px;", false));
            }
            String st = effectiveStatus(app);
            boolean isExp = "EXPIRED".equals(st);
            String pillText = isExp
                    ? "⚠  Sticker expired Click here to reapply"
                    : "●  " + ("PENDING".equals(st) ? "Pending Approval" : ("REJECTED".equals(st) ? "Rejected — Click to Resubmit" : capitalize(st)));
            String pillColors = (isExp || "REJECTED".equals(st))
                    ? "-fx-background-color: #fee2e2; -fx-text-fill: #dc2626;"
                    : "-fx-background-color: #fef3c7; -fx-text-fill: #d97706;";
            Label pill = styledLabel(pillText, pillColors + " -fx-font-weight: bold; -fx-font-size: 11px; -fx-padding: 6 12; -fx-background-radius: 8;", false);
            pill.setMaxWidth(Double.MAX_VALUE);

            VBox info = new VBox(6, styledLabel(app.getMake() + " " + app.getModel(), "-fx-font-size: 15px; -fx-font-weight: bold; -fx-text-fill: #0f172a;", false),
                    styledLabel(app.plateNumberProperty().get() + "  •  " + app.passTypeProperty().get(), "-fx-font-size: 12px; -fx-text-fill: #64748b;", false), pill);
            info.setPadding(new Insets(14, 16, 16, 16));
            VBox card = new VBox(banner, info);
            card.setPrefWidth(340);
            card.getStyleClass().add("vehicle-card");
            card.setOnMouseClicked(e -> {
                if (isExp) showReapplyVehicleModal(app);
                else showVehicleDetailsModal(app);
            });
            vboxVehicleContainer.getChildren().add(card);
        }
    }

    // Reapplication Modal for Expired Stickers: pre-fills vehicle details & requires newly uploaded documents
    private void showReapplyVehicleModal(VehicleApplication expiredApp) {
        User user = Main.getCurrentUser();
        if (user == null) return;

        AtomicReference<Runnable> closeRef = new AtomicReference<>(() -> {});
        Map<String, File> stagedFiles = new LinkedHashMap<>();

        VBox root = buildModalShell(880, "↻  Sticker Expired — Reapply & Re-upload New Documents", () -> closeRef.get().run());

        String initialPass = "Drop-Off Sticker".equalsIgnoreCase(expiredApp.passTypeProperty().get()) ? "Drop-Off Sticker" : "Parking Sticker";
        String initialOwn = "Another Person's Vehicle".equalsIgnoreCase(expiredApp.getOwnership()) ? "Another Person's Vehicle" : "My Own Vehicle";
        AtomicReference<String> passType = new AtomicReference<>(initialPass);
        AtomicReference<String> ownType = new AtomicReference<>(initialOwn);

        HBox noticeBanner = new HBox(10,
                styledLabel("⚠", "-fx-font-size: 15px; -fx-text-fill: #b91c1c;", false),
                styledLabel("Your previous campus sticker exceeded the 1-year limit. Please review your vehicle details and re-upload fresh copies of ALL required documents below.",
                        "-fx-font-size: 12px; -fx-font-weight: bold; -fx-text-fill: #991b1b;", true));
        noticeBanner.setAlignment(Pos.CENTER_LEFT);
        noticeBanner.setPadding(new Insets(11, 16, 11, 16));
        noticeBanner.setStyle("-fx-background-color: #fff1f2; -fx-border-color: #fecdd3; -fx-border-radius: 10; -fx-background-radius: 10;");

        Button btnPark = new Button("🅿  Parking Sticker"), btnDrop = new Button("↪  Drop-Off Sticker");
        btnPark.getStyleClass().setAll("Parking Sticker".equals(initialPass) ? "toggle-pill-active" : "toggle-pill");
        btnDrop.getStyleClass().setAll("Drop-Off Sticker".equals(initialPass) ? "toggle-pill-active" : "toggle-pill");
        btnPark.setMaxWidth(Double.MAX_VALUE); btnDrop.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(btnPark, Priority.ALWAYS); HBox.setHgrow(btnDrop, Priority.ALWAYS);
        btnPark.setOnAction(e -> { passType.set("Parking Sticker"); btnPark.getStyleClass().setAll("toggle-pill-active"); btnDrop.getStyleClass().setAll("toggle-pill"); });
        btnDrop.setOnAction(e -> { passType.set("Drop-Off Sticker"); btnDrop.getStyleClass().setAll("toggle-pill-active"); btnPark.getStyleClass().setAll("toggle-pill"); });

        VBox extraAuth = createStagedUploadField("Owner's Valid ID & Approval Letter", "📄  Owner's Valid ID & Approval Letter (Required)", "Click to upload new authorization document", stagedFiles);
        boolean isAnotherInit = "Another Person's Vehicle".equals(initialOwn);
        extraAuth.setVisible(isAnotherInit);
        extraAuth.setManaged(isAnotherInit);

        Button btnOwn = new Button("👤  My Own Vehicle"), btnOther = new Button("👥  Another Person's Vehicle");
        btnOwn.getStyleClass().setAll(!isAnotherInit ? "toggle-pill-active" : "toggle-pill");
        btnOther.getStyleClass().setAll(isAnotherInit ? "toggle-pill-active" : "toggle-pill");
        btnOwn.setMaxWidth(Double.MAX_VALUE); btnOther.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(btnOwn, Priority.ALWAYS); HBox.setHgrow(btnOther, Priority.ALWAYS);
        btnOwn.setOnAction(e -> { ownType.set("My Own Vehicle"); btnOwn.getStyleClass().setAll("toggle-pill-active"); btnOther.getStyleClass().setAll("toggle-pill"); extraAuth.setVisible(false); extraAuth.setManaged(false); });
        btnOther.setOnAction(e -> { ownType.set("Another Person's Vehicle"); btnOther.getStyleClass().setAll("toggle-pill-active"); btnOwn.getStyleClass().setAll("toggle-pill"); extraAuth.setVisible(true); extraAuth.setManaged(true); });

        TextField txtName = new TextField(expiredApp.ownerNameProperty().get());
        TextField txtContact = new TextField(userContactNumbers.getOrDefault(user.getId(), ""));
        TextField txtPlate = new TextField(expiredApp.plateNumberProperty().get());
        TextField txtMake = new TextField(expiredApp.getMake());
        TextField txtModel = new TextField(expiredApp.getModel());
        TextField txtYear = new TextField(expiredApp.getYear());
        TextField txtColor = new TextField(expiredApp.getColor());
        txtContact.setPromptText("09xx xxx xxxx"); txtPlate.setPromptText("ABC-1234"); txtMake.setPromptText("Honda"); txtModel.setPromptText("WinnerX"); txtYear.setPromptText("2024"); txtColor.setPromptText("Red");
        for (TextField tf : List.of(txtName, txtContact, txtPlate, txtMake, txtModel, txtYear, txtColor)) tf.getStyleClass().add("input-field");

        VBox leftCol = new VBox(13,
                new VBox(6, styledLabel("Sticker Pass Type", "-fx-font-weight: bold; -fx-font-size: 12px;", false), new HBox(10, btnPark, btnDrop)),
                new VBox(6, styledLabel("Vehicle Ownership Type", "-fx-font-weight: bold; -fx-font-size: 12px;", false), new HBox(10, btnOwn, btnOther)),
                new HBox(12, labeledField("Owner Full Name", txtName), labeledField("Contact Number", txtContact)),
                new HBox(12, labeledField("Plate Number", txtPlate), labeledField("Make / Brand", txtMake)),
                new HBox(10, labeledField("Model", txtModel), labeledField("Year", txtYear), labeledField("Color", txtColor)));
        leftCol.setPrefWidth(400);

        VBox rightCol = new VBox(11,
                createStagedUploadField("CR / OR Document", "📄  New CR / OR Document (Required)", "Click to re-upload updated CR/OR", stagedFiles),
                createStagedUploadField("Driver's License", "🪪  New Driver's License (Required)", "Click to re-upload valid license", stagedFiles),
                createStagedUploadField("UE Registration Form", "📑  New UE Registration Form (Required)", "Click to re-upload current Registration Form", stagedFiles),
                createStagedUploadField("Vehicle Photo", "🖼  New Vehicle Photo (Required)", "Click to re-upload current vehicle photo", stagedFiles),
                extraAuth);
        rightCol.setPrefWidth(400);

        Button btnCancel = styledButton("Cancel", "", e -> closeRef.get().run());
        btnCancel.getStyleClass().add("btn-secondary");
        btnCancel.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(btnCancel, Priority.ALWAYS);

        Button btnSubmit = styledButton("Submit Reapplication", "", null);
        btnSubmit.getStyleClass().add("btn-primary");
        btnSubmit.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(btnSubmit, Priority.ALWAYS);

        btnSubmit.setOnAction(e -> {
            String ownerName = txtName.getText().trim();
            String plate = txtPlate.getText().trim().toUpperCase();
            String make = txtMake.getText().trim();
            String model = txtModel.getText().trim();
            String contact = txtContact.getText().trim();
            if (ownerName.isEmpty() || plate.isEmpty() || make.isEmpty() || model.isEmpty() || contact.isEmpty()) {
                showAlert(Alert.AlertType.WARNING, "Incomplete Form", "Please fill in Owner Name, Contact Number, Plate Number, Make/Brand, and Model.");
                return;
            }

            List<String> requiredDocs = new ArrayList<>(List.of("CR / OR Document", "Driver's License", "UE Registration Form", "Vehicle Photo"));
            if ("Another Person's Vehicle".equalsIgnoreCase(ownType.get())) {
                requiredDocs.add("Owner's Valid ID & Approval Letter");
            }
            List<String> missingDocs = requiredDocs.stream().filter(k -> !stagedFiles.containsKey(k)).collect(Collectors.toList());
            if (!missingDocs.isEmpty()) {
                showAlert(Alert.AlertType.WARNING, "Re-upload Required Documents",
                        "Because your previous sticker expired, please upload new copies of all required documents:\n\n• " + String.join("\n• ", missingDocs));
                return;
            }

            String yr = txtYear.getText().trim().isEmpty() ? "2024" : txtYear.getText().trim();
            String col = txtColor.getText().trim().isEmpty() ? "Standard" : txtColor.getText().trim();
            String selectedPass = passType.get();
            String selectedOwn = ownType.get();
            int oldId = expiredApp.idProperty().get();
            String userId = user.getId();
            String senderTag = user.getName() + " (" + userId + ")";
            Map<String, File> filesToUpload = new LinkedHashMap<>();
            for (String k : requiredDocs) filesToUpload.put(k, stagedFiles.get(k));

            btnSubmit.setDisable(true);
            btnCancel.setDisable(true);
            btnSubmit.setText("Uploading Documents & Submitting...");

            Main.runAsync(() -> {
                deleteUserUploadedFiles(userId);
                for (Map.Entry<String, File> docEntry : filesToUpload.entrySet()) {
                    saveToUploadsFolder(userId, docEntry.getKey(), docEntry.getValue());
                }
                DatabaseManager.reapplyApplication(oldId, userId, ownerName, make + "|" + model + "|" + yr + "|" + col, plate, selectedPass, selectedOwn);
                DatabaseManager.sendMessage(senderTag, "ADMIN",
                        "Expired Sticker Renewal Application — " + plate,
                        "Re-applied for expired campus sticker with newly uploaded documents for " + make + " " + model + " (" + plate + ").",
                        false);
            }, () -> {
                userContactNumbers.put(userId, contact);
                if (officeStickerStock > 0) officeStickerStock--;
                closeRef.get().run();
                renderDashboardView();
                showAlert(Alert.AlertType.INFORMATION, "Reapplication Submitted",
                        "Your new documents have been uploaded and your sticker renewal for " + make + " " + model + " (" + plate + ") is now PENDING approval.");
            }, err -> {
                btnSubmit.setDisable(false);
                btnCancel.setDisable(false);
                btnSubmit.setText("Submit Reapplication");
                showAlert(Alert.AlertType.ERROR, "Reapplication Failed", "Could not upload documents or submit renewal: " + err.getMessage());
            });
        });

        VBox body = new VBox(15, noticeBanner, new HBox(26, leftCol, rightCol), new Separator(), new HBox(14, btnCancel, btnSubmit));
        body.setPadding(new Insets(18, 26, 22, 26));
        root.getChildren().add(body);
        closeRef.set(openInAppModal(root, null));
    }

    // Student Vehicle Details & Rejected Document Replacement/Resubmit Modal (Async)
    private void showVehicleDetailsModal(VehicleApplication app) {
        User user = Main.getCurrentUser();
        if (user == null) return;
        AtomicReference<Runnable> closeRef = new AtomicReference<>(() -> {});
        VBox root = buildModalShell(660, "🚗  Vehicle Details", () -> closeRef.get().run());

        String stUpper = effectiveStatus(app);
        boolean isRejected = "REJECTED".equals(stUpper);
        GridPane grid = createTwoColGrid();
        grid.add(createDetailBox("MAKE / BRAND", app.getMake(), "#1e293b"), 0, 0);
        grid.add(createDetailBox("MODEL", app.getModel(), "#1e293b"), 1, 0);
        grid.add(createDetailBox("YEAR", app.getYear(), "#1e293b"), 0, 1);
        grid.add(createDetailBox("COLOR", app.getColor(), "#1e293b"), 1, 1);
        grid.add(createDetailBox("PLATE NUMBER", app.plateNumberProperty().get(), "#1e293b"), 0, 2);
        grid.add(createDetailBox("STICKER TYPE", app.passTypeProperty().get(), "#1e293b"), 1, 2);
        grid.add(createDetailBox("APPROVAL DATE", isRejected ? "Application Rejected" : "Awaiting Approval", "#1e293b"), 0, 3);
        grid.add(createDetailBox("STATUS", capitalize(stUpper), "#1e293b"), 1, 3);

        List<String> docNames = getRequiredDocKeys(app);
        Set<String> replaced = Collections.synchronizedSet(new LinkedHashSet<>());
        AtomicInteger activeUploads = new AtomicInteger(0);

        VBox docsSec = new VBox(10, styledLabel("Documents", "-fx-font-size: 14px; -fx-font-weight: bold;", false));
        VBox reasonContainer = new VBox();
        VBox docsCard = new VBox();
        docsCard.setStyle("-fx-background-color: #ffffff; -fx-background-radius: 10; -fx-border-color: #e2e8f0; -fx-border-radius: 10;");
        docsSec.getChildren().addAll(reasonContainer, docsCard);

        java.util.function.Consumer<Set<String>> renderRows = rejDocs -> {
            docsCard.getChildren().clear();
            for (int i = 0; i < docNames.size(); i++) {
                String doc = docNames.get(i);
                boolean canReup = isRejected && rejDocs.contains(doc);
                Label stLbl = styledLabel(canReup ? "Rejected" : "Pending", (canReup ? "-fx-text-fill: #dc2626;" : "-fx-text-fill: #d97706;") + " -fx-font-size: 12px; -fx-font-weight: bold;", false);
                Label viewLink = styledLabel("View", "-fx-font-size: 12px; -fx-font-weight: bold; -fx-text-fill: #b71c1c; -fx-cursor: hand;", false);
                viewLink.setOnMouseClicked(e -> fetchAndPreviewDocument(doc, user.getId(), doc, viewLink));

                HBox row = new HBox(10, styledLabel(doc, "-fx-font-size: 13px; -fx-text-fill: #334155;", false), hSpacer(), stLbl);
                row.setAlignment(Pos.CENTER_LEFT);
                row.setPadding(new Insets(11, 16, 11, 16));
                if (canReup) {
                    Button btnRep = styledButton("↻ Upload Replacement", "-fx-background-color: #fff1f2; -fx-text-fill: #b71c1c; -fx-border-color: #fecdd3; -fx-border-radius: 6; -fx-background-radius: 6; -fx-font-weight: bold; -fx-font-size: 11px; -fx-padding: 4 10; -fx-cursor: hand;", null);
                    btnRep.setOnAction(e -> {
                        FileChooser fc = new FileChooser();
                        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("Images & PDF", "*.png", "*.jpg", "*.jpeg", "*.pdf"));
                        File chosen = fc.showOpenDialog(Main.getPrimaryStage());
                        if (chosen != null) {
                            activeUploads.incrementAndGet();
                            btnRep.setDisable(true);
                            btnRep.setText("⏳ Uploading...");
                            stLbl.setText("Uploading...");
                            Main.runAsync(() -> saveToUploadsFolder(user.getId(), doc, chosen), saved -> {
                                activeUploads.decrementAndGet();
                                replaced.add(doc);
                                stLbl.setText("Ready to Resubmit");
                                stLbl.setStyle("-fx-font-size: 12px; -fx-font-weight: bold; -fx-text-fill: #059669;");
                                btnRep.setDisable(false);
                                btnRep.setText("✓ Replaced");
                            }, err -> {
                                activeUploads.decrementAndGet();
                                stLbl.setText("Rejected");
                                btnRep.setDisable(false);
                                btnRep.setText("↻ Upload Replacement");
                                showAlert(Alert.AlertType.ERROR, "Upload Error", err.getMessage());
                            });
                        }
                    });
                    row.getChildren().addAll(btnRep, viewLink);
                } else {
                    row.getChildren().add(viewLink);
                }
                docsCard.getChildren().add(row);
                if (i < docNames.size() - 1) docsCard.getChildren().add(new Separator());
            }
        };

        if (isRejected) {
            docsCard.getChildren().add(createInlineLoadingRow("Loading rejection details..."));
            int appId = app.idProperty().get();
            String userId = user.getId();
            Main.runAsync(() -> DatabaseManager.getRejectionReason(appId, userId), rejText -> {
                if (rejText != null && !rejText.isEmpty()) {
                    HBox alert = new HBox(styledLabel("Flagged Reason: " + rejText, "-fx-font-size: 11.5px; -fx-font-weight: bold; -fx-text-fill: #9f1239;", true));
                    alert.setPadding(new Insets(10, 14, 10, 14));
                    alert.setStyle("-fx-background-color: #fff1f2; -fx-border-color: #fecdd3; -fx-border-radius: 8; -fx-background-radius: 8;");
                    reasonContainer.getChildren().setAll(alert);
                }
                renderRows.accept(determineRejectedDocuments(rejText, docNames));
            }, err -> renderRows.accept(determineRejectedDocuments(null, docNames)));
        } else {
            renderRows.accept(Collections.emptySet());
        }

        Button btnResubmit = styledButton("↻ Resubmit Rejected Document(s)", "-fx-background-color: #b71c1c; -fx-text-fill: #ffffff; -fx-font-weight: bold; -fx-font-size: 12px; -fx-padding: 9 16; -fx-background-radius: 8; -fx-cursor: hand;", null);
        btnResubmit.setOnAction(e -> {
            if (activeUploads.get() > 0) {
                showAlert(Alert.AlertType.WARNING, "Upload in Progress", "Please wait for your replacement document upload to finish.");
                return;
            }
            if (replaced.isEmpty()) {
                showAlert(Alert.AlertType.WARNING, "Upload Replacement First", "Please upload a replacement file before resubmitting.");
                return;
            }
            btnResubmit.setDisable(true);
            btnResubmit.setText("Resubmitting...");
            int appId = app.idProperty().get();
            String userId = user.getId();
            String sender = user.getName() + " (" + userId + ")";
            String plate = app.plateNumberProperty().get();
            String replacedSummary = String.join(", ", replaced);

            Main.runAsync(() -> {
                userRejectionReasons.remove(userId);
                DatabaseManager.updateApplicationStatus(appId, "PENDING", null);
                DatabaseManager.sendMessage(sender, "ADMIN", "Resubmitted Rejected Document(s) — " + plate,
                        "Uploaded replacement(s) for: " + replacedSummary, false);
            }, () -> {
                if (officeStickerStock > 0) officeStickerStock--;
                closeRef.get().run();
                renderDashboardView();
                showAlert(Alert.AlertType.INFORMATION, "Documents Resubmitted", "Your application status is now PENDING review.");
            }, err -> {
                btnResubmit.setDisable(false);
                btnResubmit.setText("↻ Resubmit Rejected Document(s)");
                showAlert(Alert.AlertType.ERROR, "Resubmit Error", err.getMessage());
            });
        });
        btnResubmit.setVisible(isRejected);
        btnResubmit.setManaged(isRejected);

        HBox footer = new HBox(10,
                styledButton("Withdraw / Cancel Application", "-fx-background-color: #fff1f2; -fx-text-fill: #b71c1c; -fx-border-color: #fecdd3; -fx-border-radius: 8; -fx-background-radius: 8; -fx-font-weight: bold; -fx-font-size: 12px; -fx-padding: 9 14; -fx-cursor: hand;", e -> withdrawApplicationAction(app, () -> closeRef.get().run())),
                hSpacer(), btnResubmit,
                styledButton("Close", "-fx-background-color: #f1f5f9; -fx-text-fill: #334155; -fx-font-weight: bold; -fx-background-radius: 8; -fx-padding: 9 18; -fx-cursor: hand;", e -> closeRef.get().run()));
        VBox body = new VBox(16, grid, docsSec, new Separator(), footer);
        body.setPadding(new Insets(20, 24, 20, 24));
        root.getChildren().add(body);
        closeRef.set(openInAppModal(root, null));
    }

    // Student New Vehicle Application Modal (Async Upload & Submission)
    private void showAddVehicleModal() {
        User user = Main.getCurrentUser();
        if (user == null) return;
        if (cachedUserApp != null) {
            if (cachedUserApp.isExpired()) {
                showReapplyVehicleModal(cachedUserApp);
            } else {
                showAlert(Alert.AlertType.WARNING, "Quota Reached", "You already have an active vehicle sticker application.");
            }
            return;
        }
        AtomicReference<Runnable> closeRef = new AtomicReference<>(() -> {});
        Map<String, File> sessionDocs = new ConcurrentHashMap<>();
        AtomicInteger activeUploads = new AtomicInteger(0);
        Runnable cleanup = () -> { sessionDocs.values().forEach(f -> { try { Files.deleteIfExists(f.toPath()); } catch (IOException ignored) {} }); sessionDocs.clear(); };

        VBox root = buildModalShell(880, "+  Add Vehicle — Sticker Application", () -> { cleanup.run(); closeRef.get().run(); });
        AtomicReference<String> passType = new AtomicReference<>("Parking Sticker");
        AtomicReference<String> ownType = new AtomicReference<>("My Own Vehicle");

        Button btnPark = new Button("🅿  Parking Sticker"), btnDrop = new Button("↪  Drop-Off Sticker");
        btnPark.getStyleClass().add("toggle-pill-active");
        btnDrop.getStyleClass().add("toggle-pill");
        btnPark.setMaxWidth(Double.MAX_VALUE); btnDrop.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(btnPark, Priority.ALWAYS); HBox.setHgrow(btnDrop, Priority.ALWAYS);
        btnPark.setOnAction(e -> { passType.set("Parking Sticker"); btnPark.getStyleClass().setAll("toggle-pill-active"); btnDrop.getStyleClass().setAll("toggle-pill"); });
        btnDrop.setOnAction(e -> { passType.set("Drop-Off Sticker"); btnDrop.getStyleClass().setAll("toggle-pill-active"); btnPark.getStyleClass().setAll("toggle-pill"); });

        VBox extraAuth = createAsyncUploadField("Owner's Valid ID & Approval Letter", "📄  Owner's Valid ID & Approval Letter", "Click to upload authorization document", sessionDocs, activeUploads);
        extraAuth.setVisible(false); extraAuth.setManaged(false);
        Button btnOwn = new Button("👤  My Own Vehicle"), btnOther = new Button("👥  Another Person's Vehicle");
        btnOwn.getStyleClass().add("toggle-pill-active");
        btnOther.getStyleClass().add("toggle-pill");
        btnOwn.setMaxWidth(Double.MAX_VALUE); btnOther.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(btnOwn, Priority.ALWAYS); HBox.setHgrow(btnOther, Priority.ALWAYS);
        btnOwn.setOnAction(e -> { ownType.set("My Own Vehicle"); btnOwn.getStyleClass().setAll("toggle-pill-active"); btnOther.getStyleClass().setAll("toggle-pill"); extraAuth.setVisible(false); extraAuth.setManaged(false); });
        btnOther.setOnAction(e -> { ownType.set("Another Person's Vehicle"); btnOther.getStyleClass().setAll("toggle-pill-active"); btnOwn.getStyleClass().setAll("toggle-pill"); extraAuth.setVisible(true); extraAuth.setManaged(true); });

        TextField txtName = new TextField(user.getName()), txtContact = new TextField(), txtPlate = new TextField(), txtMake = new TextField(), txtModel = new TextField(), txtYear = new TextField(), txtColor = new TextField();
        txtContact.setPromptText("09xx xxx xxxx"); txtPlate.setPromptText("ABC-1234"); txtMake.setPromptText("Honda"); txtModel.setPromptText("WinnerX"); txtYear.setPromptText("2023"); txtColor.setPromptText("Red");
        for (TextField tf : List.of(txtName, txtContact, txtPlate, txtMake, txtModel, txtYear, txtColor)) tf.getStyleClass().add("input-field");

        VBox leftCol = new VBox(13,
                new VBox(6, styledLabel("Sticker Pass Type", "-fx-font-weight: bold; -fx-font-size: 12px;", false), new HBox(10, btnPark, btnDrop)),
                new VBox(6, styledLabel("Vehicle Ownership Type", "-fx-font-weight: bold; -fx-font-size: 12px;", false), new HBox(10, btnOwn, btnOther)),
                new HBox(12, labeledField("Owner Full Name", txtName), labeledField("Contact Number", txtContact)),
                new HBox(12, labeledField("Plate Number", txtPlate), labeledField("Make / Brand", txtMake)),
                new HBox(10, labeledField("Model", txtModel), labeledField("Year", txtYear), labeledField("Color", txtColor)));
        leftCol.setPrefWidth(400);

        VBox rightCol = new VBox(11,
                createAsyncUploadField("CR / OR Document", "📄  CR / OR Document", "Click to upload CR/OR image", sessionDocs, activeUploads),
                createAsyncUploadField("Driver's License", "🪪  Driver's License", "Click to upload license image", sessionDocs, activeUploads),
                createAsyncUploadField("UE Registration Form", "📑  UE Registration Form", "Click to upload Registration Form image", sessionDocs, activeUploads),
                createAsyncUploadField("Vehicle Photo", "🖼  Vehicle Photo", "Click to upload vehicle photo", sessionDocs, activeUploads),
                extraAuth);
        rightCol.setPrefWidth(400);

        Button btnCancel = styledButton("Cancel", "", e -> { cleanup.run(); closeRef.get().run(); });
        btnCancel.getStyleClass().add("btn-secondary");
        btnCancel.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(btnCancel, Priority.ALWAYS);

        Button btnSubmit = styledButton("Submit for Approval", "", null);
        btnSubmit.getStyleClass().add("btn-primary");
        btnSubmit.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(btnSubmit, Priority.ALWAYS);

        btnSubmit.setOnAction(e -> {
            if (activeUploads.get() > 0) {
                showAlert(Alert.AlertType.WARNING, "Uploads Still Running", "Please wait a moment for your document upload(s) to finish before submitting.");
                return;
            }
            String ownerName = txtName.getText().trim();
            String plate = txtPlate.getText().trim().toUpperCase(), make = txtMake.getText().trim(), model = txtModel.getText().trim(), contact = txtContact.getText().trim();
            if (plate.isEmpty() || make.isEmpty() || model.isEmpty() || contact.isEmpty()) {
                showAlert(Alert.AlertType.WARNING, "Incomplete Form", "Please fill in Contact Number, Plate Number, Make/Brand, and Model.");
                return;
            }
            String yr = txtYear.getText().trim().isEmpty() ? "2024" : txtYear.getText().trim();
            String col = txtColor.getText().trim().isEmpty() ? "Standard" : txtColor.getText().trim();
            String selectedPass = passType.get();
            String selectedOwn = ownType.get();
            String userId = user.getId();

            btnSubmit.setDisable(true);
            btnCancel.setDisable(true);
            btnSubmit.setText("Submitting Application...");

            Main.runAsync(() -> {
                if (DatabaseManager.getUserApplication(userId) != null) {
                    throw new IllegalStateException("You already have an active vehicle sticker application.");
                }
                DatabaseManager.createApplication(userId, ownerName, make + "|" + model + "|" + yr + "|" + col, plate, selectedPass, selectedOwn);
            }, () -> {
                userContactNumbers.put(userId, contact);
                userUploadedDocs.put(userId, new ConcurrentHashMap<>(sessionDocs));
                if (officeStickerStock > 0) officeStickerStock--;
                closeRef.get().run();
                renderDashboardView();
                showAlert(Alert.AlertType.INFORMATION, "Application Submitted", "Your sticker application for " + make + " " + model + " (" + plate + ") is now PENDING approval.");
            }, err -> {
                btnSubmit.setDisable(false);
                btnCancel.setDisable(false);
                btnSubmit.setText("Submit for Approval");
                showAlert(Alert.AlertType.ERROR, "Submission Failed", err.getMessage());
            });
        });

        VBox body = new VBox(16, new HBox(26, leftCol, rightCol), new Separator(), new HBox(14, btnCancel, btnSubmit));
        body.setPadding(new Insets(20, 26, 22, 26));
        root.getChildren().add(body);
        closeRef.set(openInAppModal(root, null));
    }

    // Student Guidelines, FAQ & Direct Security Desk Inquiry Tab
    @FXML
    private void showUserSupport() {
        ++userNavToken;
        setActiveUserNav(btnNavSupport);
        if (userScrollPane != null) userScrollPane.setFitToHeight(true);
        userContentRoot.setMaxHeight(Double.MAX_VALUE);
        User user = Main.getCurrentUser();
        if (user == null) return;
        userContentRoot.getChildren().clear();

        // Top-Left: Gate 1 Security Office Card
        StackPane qIcon = new StackPane(styledLabel("?", "-fx-text-fill: #b71c1c; -fx-font-weight: bold; -fx-font-size: 15px;", false));
        qIcon.setStyle("-fx-background-color: #fff1f2; -fx-background-radius: 10; -fx-min-width: 38; -fx-min-height: 38; -fx-max-width: 38; -fx-max-height: 38;");
        VBox hours = new VBox(6,
                styledLabel("🕒  Mon - Fri: 8:00 AM - 5:00 PM", "-fx-font-size: 12px; -fx-font-weight: bold; -fx-text-fill: #334155;", false),
                styledLabel("🕒  Sat: 8:00 AM - 12:00 PM", "-fx-font-size: 12px; -fx-font-weight: bold; -fx-text-fill: #334155;", false));
        hours.setPadding(new Insets(12, 14, 12, 14));
        hours.setStyle("-fx-background-color: #f8fafc; -fx-background-radius: 10;");
        VBox officeCard = new VBox(14, qIcon,
                new VBox(3, styledLabel("Gate 1 Security Office", "-fx-font-size: 15px; -fx-font-weight: bold; -fx-text-fill: #0f172a;", false),
                        styledLabel("Window 2 • Sticker Release Counter", "-fx-font-size: 12px; -fx-text-fill: #64748b;", false)),
                hours);
        officeCard.setPrefWidth(330);
        officeCard.setMinWidth(330);
        officeCard.getStyleClass().add("header-card");

        // Top-Right: Sticker Requirements Card
        GridPane reqGrid = createTwoColGrid();
        String[] reqs = {"Valid UE Student/Faculty ID", "Original OR/CR", "Driver's License", "Student Reg Form", "Receipt of the Sticker"};
        for (int i = 0; i < reqs.length; i++) {
            HBox pill = new HBox(10, styledLabel("✓", "-fx-text-fill: #059669; -fx-font-weight: bold;", false), styledLabel(reqs[i], "-fx-font-weight: bold; -fx-font-size: 12px; -fx-text-fill: #065f46;", true));
            pill.setAlignment(Pos.CENTER_LEFT);
            pill.getStyleClass().add("checklist-pill");
            reqGrid.add(pill, i % 2, i / 2, i == 4 ? 2 : 1, 1);
        }
        VBox reqHeader = new VBox(2,
                styledLabel("Sticker Requirements", "-fx-font-size: 15px; -fx-font-weight: bold; -fx-text-fill: #0f172a;", false),
                styledLabel("Documents to bring to avail a vehicle sticker", "-fx-font-size: 12px; -fx-text-fill: #64748b;", false));
        VBox reqCard = new VBox(14, reqHeader, reqGrid);
        reqCard.getStyleClass().add("header-card");
        HBox.setHgrow(reqCard, Priority.ALWAYS);

        HBox topRow = new HBox(20, officeCard, reqCard);

        // Bottom-Left: Frequently Asked Questions Card
        VBox faqList = new VBox(10,
                createFaqAccordion("Can someone else pick up my vehicle sticker on my behalf?", "Yes. Your representative must present a signed Authorization Letter, a photocopy of your valid UE ID, and their government ID at Window 2.", false),
                createFaqAccordion("How long is the vehicle sticker valid?", "Campus vehicle stickers are valid for 1 year from the date of approval. Once expired, you can click 'Sticker expired Click here to reapply' on your dashboard and re-upload updated documents.", false),
                createFaqAccordion("What if I buy a new car or change vehicle plates?", "Visit Gate 1 Security Office (Window 2) with your new OR/CR and old sticker so your campus pass record can be updated.", false),
                createFaqAccordion("Where should the vehicle sticker be installed on my vehicle?", "4-wheelers: Upper-left front windshield. Motorcycles: Front mudguard or headlight casing.", false));
        VBox faqCard = new VBox(14, styledLabel("Frequently Asked Questions", "-fx-font-size: 15px; -fx-font-weight: bold; -fx-text-fill: #0f172a;", false), new Separator(), faqList);
        faqCard.getStyleClass().add("header-card");
        HBox.setHgrow(faqCard, Priority.ALWAYS);

        // Bottom-Right: Contact Security Desk Card with Pinned Hotline Pill
        TextArea txtInquiry = new TextArea();
        txtInquiry.setPromptText("Type your concern here...");
        txtInquiry.setPrefRowCount(4);
        txtInquiry.setWrapText(true);
        txtInquiry.getStyleClass().add("input-field");
        Button btnSend = styledButton("Send Inquiry", "", null);
        btnSend.getStyleClass().add("btn-primary");
        btnSend.setMaxWidth(Double.MAX_VALUE);
        btnSend.setOnAction(e -> {
            String messageText = txtInquiry.getText().trim();
            if (messageText.isEmpty()) { showAlert(Alert.AlertType.WARNING, "Empty Message", "Please type your concern first."); return; }
            String senderTag = user.getName() + " (" + user.getId() + ")";
            btnSend.setDisable(true);
            btnSend.setText("Sending...");
            Main.runAsync(() -> DatabaseManager.sendMessage(senderTag, "ADMIN", "Student Support Inquiry", messageText, false), () -> {
                btnSend.setDisable(false);
                btnSend.setText("Send Inquiry");
                txtInquiry.clear();
                showAlert(Alert.AlertType.INFORMATION, "Inquiry Sent", "Your message has been sent to Gate 1 Security Desk.");
            }, err -> {
                btnSend.setDisable(false);
                btnSend.setText("Send Inquiry");
                showAlert(Alert.AlertType.ERROR, "Send Failed", "Could not send inquiry: " + err.getMessage());
            });
        });

        Region bottomPush = new Region();
        VBox.setVgrow(bottomPush, Priority.ALWAYS);

        HBox hotlinePill = new HBox(8, styledLabel("📞  Security Gate Hotline: (02) 8735 5471 Ext. 112", "-fx-font-size: 11px; -fx-font-weight: bold; -fx-text-fill: #9f1239;", false));
        hotlinePill.setAlignment(Pos.CENTER);
        hotlinePill.setPadding(new Insets(10, 12, 10, 12));
        hotlinePill.setStyle("-fx-background-color: #fff1f2; -fx-border-color: #fecdd3; -fx-border-radius: 10; -fx-background-radius: 10;");

        VBox contactCard = new VBox(12,
                styledLabel("Contact Security Desk", "-fx-font-size: 15px; -fx-font-weight: bold; -fx-text-fill: #0f172a;", false),
                new Separator(),
                styledLabel("Have a question regarding sticker reservation or verification?", "-fx-font-size: 12px; -fx-text-fill: #64748b;", true),
                styledLabel("Inquiry Message", "-fx-font-size: 12px; -fx-font-weight: bold; -fx-text-fill: #1e293b;", false),
                txtInquiry,
                btnSend,
                bottomPush,
                hotlinePill);
        contactCard.setPrefWidth(340);
        contactCard.setMinWidth(340);
        contactCard.getStyleClass().add("header-card");

        HBox bottomRow = new HBox(20, faqCard, contactCard);
        VBox.setVgrow(bottomRow, Priority.ALWAYS);

        userContentRoot.getChildren().addAll(topRow, bottomRow);
    }

    // =========================================================================
    // SHARED WIDGET, ASYNC UPLOAD & PREVIEW HELPERS
    // =========================================================================
    private VBox createFaqAccordion(String q, String a, boolean expanded) {
        Label icon = styledLabel(expanded ? "−" : "+", "-fx-font-size: 16px; -fx-font-weight: bold; -fx-text-fill: #64748b;", false);
        HBox qRow = new HBox(10, styledLabel(q, "-fx-font-size: 13px; -fx-font-weight: bold;", true), hSpacer(), icon);
        VBox aBox = new VBox(8, new Separator(), styledLabel(a, "-fx-font-size: 12.5px; -fx-text-fill: #475569;", true));
        aBox.setVisible(expanded);
        aBox.setManaged(expanded);
        VBox box = new VBox(10, qRow, aBox);
        box.getStyleClass().add("faq-item");
        box.setOnMouseClicked(e -> { boolean next = !aBox.isVisible(); aBox.setVisible(next); aBox.setManaged(next); icon.setText(next ? "−" : "+"); });
        return box;
    }

    private VBox createDetailBox(String labelText, String valueText, String valueColor) {
        VBox box = new VBox(3,
                styledLabel(labelText, "-fx-font-size: 9.5px; -fx-font-weight: bold; -fx-text-fill: #94a3b8;", false),
                styledLabel(valueText != null && !valueText.isEmpty() ? valueText : "—", "-fx-font-size: 12.5px; -fx-font-weight: bold; -fx-text-fill: " + valueColor + ";", true));
        box.setPadding(new Insets(10, 14, 10, 14));
        box.setStyle("-fx-background-color: #f8fafc; -fx-border-color: #e2e8f0; -fx-border-radius: 9; -fx-background-radius: 9;");
        HBox.setHgrow(box, Priority.ALWAYS);
        return box;
    }

    private VBox labeledField(String labelText, Control input) {
        input.setMaxWidth(Double.MAX_VALUE);
        VBox box = new VBox(5, styledLabel(labelText, "-fx-font-weight: bold; -fx-font-size: 12px;", false), input);
        HBox.setHgrow(box, Priority.ALWAYS);
        return box;
    }

    // Uploads chosen files asynchronously in the background while keeping the form responsive
    private VBox createAsyncUploadField(String docKey, String titleText, String placeholder, Map<String, File> sessionDocs, AtomicInteger activeUploads) {
        Label status = styledLabel("☁  " + placeholder, "-fx-text-fill: #64748b; -fx-font-size: 12px;", false);
        HBox zone = new HBox(8, status);
        zone.setAlignment(Pos.CENTER);
        zone.setPrefHeight(50);
        zone.getStyleClass().add("upload-box");
        zone.setOnMouseClicked(e -> {
            User cur = Main.getCurrentUser();
            if (cur == null || zone.isDisabled()) return;
            FileChooser fc = new FileChooser();
            fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("Images & PDF", "*.png", "*.jpg", "*.jpeg", "*.pdf"));
            File chosen = fc.showOpenDialog(Main.getPrimaryStage());
            if (chosen != null) {
                String userId = cur.getId();
                activeUploads.incrementAndGet();
                zone.setDisable(true);
                status.setText("⏳  Uploading " + chosen.getName() + "...");
                status.setStyle("-fx-text-fill: #d97706; -fx-font-weight: bold; -fx-font-size: 12px;");

                Main.runAsync(() -> saveToUploadsFolder(userId, docKey, chosen), saved -> {
                    activeUploads.decrementAndGet();
                    zone.setDisable(false);
                    File prev = sessionDocs.put(docKey, saved);
                    if (prev != null && !prev.equals(saved)) {
                        try { Files.deleteIfExists(prev.toPath()); } catch (IOException ignored) {}
                    }
                    zone.getStyleClass().setAll("upload-box-done");
                    status.setText("✓  Uploaded: " + chosen.getName());
                    status.setStyle("-fx-text-fill: #059669; -fx-font-weight: bold; -fx-font-size: 12px;");
                }, err -> {
                    activeUploads.decrementAndGet();
                    zone.setDisable(false);
                    status.setText("☁  " + placeholder);
                    status.setStyle("-fx-text-fill: #64748b; -fx-font-size: 12px;");
                    showAlert(Alert.AlertType.ERROR, "Upload Error", err.getMessage());
                });
            }
        });
        return new VBox(5, styledLabel(titleText, "-fx-font-weight: bold; -fx-font-size: 12px;", false), zone);
    }

    // Stages newly selected documents locally for expired-sticker reapplication (uploaded atomically on submit)
    private VBox createStagedUploadField(String docKey, String titleText, String placeholder, Map<String, File> stagedFiles) {
        Label status = styledLabel("☁  " + placeholder, "-fx-text-fill: #64748b; -fx-font-size: 12px;", false);
        HBox zone = new HBox(8, status);
        zone.setAlignment(Pos.CENTER);
        zone.setPrefHeight(50);
        zone.getStyleClass().add("upload-box");
        zone.setOnMouseClicked(e -> {
            FileChooser fc = new FileChooser();
            fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("Images & PDF", "*.png", "*.jpg", "*.jpeg", "*.pdf"));
            File chosen = fc.showOpenDialog(Main.getPrimaryStage());
            if (chosen != null) {
                stagedFiles.put(docKey, chosen);
                zone.getStyleClass().setAll("upload-box-done");
                status.setText("✓  Ready to Upload: " + chosen.getName());
                status.setStyle("-fx-text-fill: #059669; -fx-font-weight: bold; -fx-font-size: 12px;");
            }
        });
        return new VBox(5, styledLabel(titleText, "-fx-font-weight: bold; -fx-font-size: 12px;", false), zone);
    }

    // Fetches a remote/local document asynchronously in the background before opening the preview modal
    private static void fetchAndPreviewDocument(String docTitle, String userId, String docKey, Node triggerNode) {
        String origText = (triggerNode instanceof Labeled) ? ((Labeled) triggerNode).getText() : null;
        if (triggerNode != null) {
            triggerNode.setDisable(true);
            if (triggerNode instanceof Labeled) ((Labeled) triggerNode).setText("Loading...");
        }
        Main.runAsync(() -> {
            File file = findUploadedFile(userId, docKey);
            Image img = loadImageFromFile(file);
            return new AbstractMap.SimpleEntry<>(file, img);
        }, entry -> {
            if (triggerNode != null) {
                triggerNode.setDisable(false);
                if (triggerNode instanceof Labeled && origText != null) ((Labeled) triggerNode).setText(origText);
            }
            showLoadedDocumentPreview(docTitle, entry.getKey(), entry.getValue());
        }, err -> {
            if (triggerNode != null) {
                triggerNode.setDisable(false);
                if (triggerNode instanceof Labeled && origText != null) ((Labeled) triggerNode).setText(origText);
            }
            showAlert(Alert.AlertType.ERROR, "Document Load Error", "Failed to load " + docTitle + ": " + err.getMessage());
        });
    }

    // Opens a full-size blurred image preview modal using an already-loaded Image (non-blocking)
    private static void showLoadedDocumentPreview(String docTitle, File file, Image img) {
        if (file == null || !file.exists()) {
            showAlert(Alert.AlertType.INFORMATION, docTitle, "No uploaded file found in uploads for " + docTitle + ".");
            return;
        }
        if (img == null) {
            try { Main.openExternalDocument(file.toURI().toString()); }
            catch (Exception ex) { showAlert(Alert.AlertType.INFORMATION, docTitle, "Saved File: " + file.getName() + "\nFolder: " + file.getParentFile().getAbsolutePath()); }
            return;
        }
        AtomicReference<Runnable> closeRef = new AtomicReference<>(() -> {});
        VBox root = new VBox();
        root.setPrefWidth(760);
        root.setStyle("-fx-background-color: #ffffff;");
        HBox header = new HBox(10, styledLabel("📄  " + docTitle + " — " + file.getName(), "-fx-text-fill: #ffffff; -fx-font-size: 14px; -fx-font-weight: bold;", false),
                hSpacer(), styledButton("✕", "-fx-background-color: transparent; -fx-text-fill: #ffffff; -fx-font-size: 14px; -fx-font-weight: bold; -fx-cursor: hand;", e -> closeRef.get().run()));
        header.setPadding(new Insets(14, 20, 14, 20));
        header.setStyle("-fx-background-color: #b71c1c;");

        ImageView iv = new ImageView(img);
        iv.setPreserveRatio(true);
        iv.setSmooth(true);
        iv.setFitWidth(710);
        iv.setFitHeight(520);
        VBox wrap = new VBox(14, iv);
        wrap.setAlignment(Pos.CENTER);
        wrap.setPadding(new Insets(18));

        HBox footer = new HBox(styledButton("Close", "-fx-background-color: #f1f5f9; -fx-text-fill: #334155; -fx-font-weight: bold; -fx-background-radius: 8; -fx-padding: 8 18; -fx-cursor: hand;", e -> closeRef.get().run()));
        footer.setAlignment(Pos.CENTER_RIGHT);
        footer.setPadding(new Insets(0, 20, 16, 20));

        root.getChildren().addAll(header, wrap, footer);
        closeRef.set(openInAppModal(root, null));
    }

    private static GridPane createTwoColGrid() {
        GridPane g = new GridPane();
        g.setHgap(14);
        g.setVgap(10);
        for (int i = 0; i < 2; i++) {
            ColumnConstraints cc = new ColumnConstraints();
            cc.setPercentWidth(50);
            g.getColumnConstraints().add(cc);
        }
        return g;
    }

    private static GridPane createThreeColGrid() {
        GridPane g = new GridPane();
        g.setHgap(14);
        g.setVgap(12);
        for (int i = 0; i < 3; i++) {
            ColumnConstraints cc = new ColumnConstraints();
            cc.setPercentWidth(33.333);
            g.getColumnConstraints().add(cc);
        }
        return g;
    }
}
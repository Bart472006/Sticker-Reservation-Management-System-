package com.ue.vehiclestickersystem;

import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import okhttp3.*;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ApiClient {
    // Connect your XAMPP database/API server here:
    // - Set USE_EMULATOR = true if testing on the Android Studio Emulator (uses 10.0.2.2 to reach your PC's localhost).
    // - Keep USE_EMULATOR = false for a physical Android phone and replace PC_IP with your host PC's
    //   local Wi-Fi IPv4 address (run `ipconfig` on Windows, e.g., "192.168.1.10") or your own Tailscale IP.
    // - Make sure XAMPP Apache & MySQL are running and C:\xampp\htdocs\ue_sticker_DB\api.php + uploads\ are set up.
    private static final boolean USE_EMULATOR = false;
    private static final String PC_IP = "YOUR_SERVER_IP";
    private static final String HOST = USE_EMULATOR ? "10.0.2.2" : PC_IP;
    public static final String BASE_URL = "http://" + HOST + "/ue_sticker_DB/api.php";
    public static final String UPLOADS_URL = "http://" + HOST + "/ue_sticker_DB/uploads/";

    public static final ExecutorService BACKGROUND_EXECUTOR = Executors.newFixedThreadPool(
            Math.max(4, Runtime.getRuntime().availableProcessors()),
            r -> { Thread t = new Thread(r, "UECampusPass-AsyncWorker"); t.setDaemon(true); return t; });

    private static final OkHttpClient client = new OkHttpClient.Builder()
            .dispatcher(new Dispatcher(Executors.newCachedThreadPool(r -> {
                Thread t = new Thread(r, "UECampusPass-OkHttp"); t.setDaemon(true); return t;
            })))
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .addInterceptor(chain -> chain.proceed(chain.request().newBuilder()
                    .header("Cache-Control", "no-cache, no-store, must-revalidate")
                    .header("Pragma", "no-cache").header("Expires", "0").build()))
            .build();

    private static final Handler mainHandler = new Handler(Looper.getMainLooper());
    public static volatile User currentUser;
    private static final Pattern TS_PATTERN = Pattern.compile("(\\d{10,15})");
    private static final String[] DATE_FORMATS = {
            "yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd'T'HH:mm:ss", "yyyy-MM-dd",
            "MM/dd/yyyy HH:mm:ss", "MM/dd/yyyy", "MMM dd, yyyy hh:mm a", "MMM dd, yyyy", "yyyy/MM/dd"
    };

    public static OkHttpClient getHttpClient() { return client; }
    public static String getAttachmentUrl(String name) { return UPLOADS_URL + Uri.encode(name); }
    public static void evictConnections() {
        BACKGROUND_EXECUTOR.execute(() -> { try { client.connectionPool().evictAll(); } catch (Exception ignored) {} });
    }

    public static Date parseFlexibleDate(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.isEmpty() || "null".equalsIgnoreCase(s) || s.startsWith("0000-00-00")) return null;
        for (String fmt : DATE_FORMATS) {
            try {
                SimpleDateFormat sdf = new SimpleDateFormat(fmt, Locale.US);
                sdf.setLenient(false);
                Date d = sdf.parse(s);
                if (d != null) return d;
            } catch (ParseException ignored) {}
        }
        return null;
    }

    private static String fmtDate(Date d, String pattern) {
        return new SimpleDateFormat(pattern, Locale.US).format(d);
    }

    private static String optStr(JSONObject o, String fallback, String... keys) {
        if (o == null) return fallback;
        for (String k : keys) {
            if (k == null || o.isNull(k)) continue;
            String v = o.optString(k, "").trim();
            if (!v.isEmpty() && !"null".equalsIgnoreCase(v)) return v;
        }
        return fallback;
    }

    private static String errMsg(Throwable e, String fallback) {
        Throwable c = e.getCause() != null ? e.getCause() : e;
        return c.getMessage() != null ? c.getMessage() : fallback;
    }

    private static <T> CompletableFuture<T> async(Callable<T> task) {
        return CompletableFuture.supplyAsync(() -> {
            try { return task.call(); } catch (Exception e) { throw new CompletionException(e); }
        }, BACKGROUND_EXECUTOR);
    }

    private static <T> CompletableFuture<T> asyncSafe(Callable<T> task) {
        return CompletableFuture.supplyAsync(() -> {
            try { return task.call(); } catch (Exception e) { return null; }
        }, BACKGROUND_EXECUTOR);
    }

    private static String httpGet(String url) throws IOException {
        String full = url + (url.contains("?") ? "&" : "?") + "_ts=" + System.currentTimeMillis();
        try (Response r = client.newCall(new Request.Builder().url(full).get().build()).execute()) {
            if (!r.isSuccessful() || r.body() == null) throw new IOException("HTTP " + r.code());
            return r.body().string().trim();
        }
    }

    private static Response httpPost(RequestBody body) throws IOException {
        return client.newCall(new Request.Builder().url(BASE_URL).post(body).build()).execute();
    }

    // --- DATA MODELS ---
    public static class User {
        public final String id, name, email, role, department;
        public User(String id, String name, String email, String role, String department) {
            this.id = id; this.name = name; this.email = email; this.role = role; this.department = department;
        }
    }

    public static class VehicleApplication {
        public final int id;
        public final String ownerId, ownerName, contactNumber, make, model, year, color,
                plateNumber, passType, ownership, status, rejectionReason, approvalDate, createdAt;
        public final Date issueDate, expirationDate;

        public VehicleApplication(JSONObject obj) {
            id = obj.optInt("id");
            ownerId = optStr(obj, "", "owner_id");
            ownerName = optStr(obj, "", "owner_name");
            contactNumber = optStr(obj, "", "contact_number");
            plateNumber = optStr(obj, "", "plate_number");
            passType = optStr(obj, "", "pass_type");
            ownership = optStr(obj, "My Own Vehicle", "ownership");
            status = optStr(obj, "PENDING", "status");
            rejectionReason = optStr(obj, "", "rejection_reason");
            approvalDate = optStr(obj, "", "approval_date", "approved_at", "approved_date");
            createdAt = optStr(obj, "", "created_at", "date_created", "updated_at");

            String[] p = optStr(obj, "Vehicle", "vehicle_model").split("\\|", -1);
            make = p.length > 0 ? p[0] : "Vehicle";
            model = p.length > 1 ? p[1] : "";
            year = p.length > 2 ? p[2] : "";
            color = p.length > 3 ? p[3] : "";

            Date start = parseFlexibleDate(approvalDate);
            if (start == null) start = parseFlexibleDate(createdAt);
            if (start == null && "APPROVED".equalsIgnoreCase(status)) start = new Date();
            issueDate = start;

            Date exp = parseFlexibleDate(optStr(obj, "", "expiration_date", "expiry_date", "expires_at", "valid_until"));
            if (exp == null && issueDate != null) {
                Calendar cal = Calendar.getInstance();
                cal.setTime(issueDate);
                cal.add(Calendar.YEAR, 1);
                exp = cal.getTime();
            }
            expirationDate = exp;
        }

        public boolean isExpired() {
            return "EXPIRED".equalsIgnoreCase(status)
                    || ("APPROVED".equalsIgnoreCase(status) && expirationDate != null && new Date().after(expirationDate));
        }
        public boolean isActiveApproved() { return "APPROVED".equalsIgnoreCase(status) && !isExpired(); }
        public long getDaysUntilExpiration() {
            return expirationDate == null ? 0L : (long) Math.ceil((expirationDate.getTime() - System.currentTimeMillis()) / 86400000.0);
        }
        public String getFormattedApprovalDate() {
            return issueDate != null ? fmtDate(issueDate, "MMM dd, yyyy")
                    : (!approvalDate.isEmpty() ? approvalDate : fmtDate(new Date(), "MMM dd, yyyy"));
        }
        public String getFormattedExpirationDate() {
            return expirationDate != null ? fmtDate(expirationDate, "MMM dd, yyyy") : "1 Year upon approval";
        }
    }

    public static class MessageItem {
        public final int id;
        public final String sender, recipient, subject, rawBody, cleanBody, attachmentFileName, category, shortDate, fullTimestamp;
        public final boolean isSent;

        public MessageItem(int id, String sender, String recipient, String subject,
                           String rawBody, String category, String createdAt, boolean isSent) {
            this.id = id; this.sender = sender; this.recipient = recipient; this.subject = subject;
            this.rawBody = rawBody != null ? rawBody : ""; this.category = category; this.isSent = isSent;
            String marker = "\n[[ATTACHMENT:";
            int idx = this.rawBody.indexOf(marker);
            if (idx >= 0) {
                cleanBody = this.rawBody.substring(0, idx).trim();
                int st = idx + marker.length(), en = this.rawBody.indexOf("]]", st);
                attachmentFileName = en > st ? this.rawBody.substring(st, en).trim() : null;
            } else {
                cleanBody = this.rawBody.trim();
                attachmentFileName = null;
            }
            Date parsed = parseFlexibleDate(createdAt);
            shortDate = parsed != null ? fmtDate(parsed, "MMM dd, yyyy") : (createdAt != null ? createdAt : "");
            fullTimestamp = parsed != null ? fmtDate(parsed, "MMM dd, yyyy 'at' hh:mm a") : shortDate;
        }
    }

    public static class UserPortalSnapshot {
        public final boolean reachable;
        public final VehicleApplication application;
        public final Integer remainingStickers;
        public final Map<String, String> uploadedDocs;
        public final List<MessageItem> receivedMessages, sentMessages;
        public final String error;
        public UserPortalSnapshot(boolean reachable, VehicleApplication app, Integer rem,
                                  Map<String, String> docs, List<MessageItem> rec, List<MessageItem> sent, String err) {
            this.reachable = reachable; this.application = app; this.remainingStickers = rem;
            this.uploadedDocs = docs; this.receivedMessages = rec; this.sentMessages = sent; this.error = err;
        }
    }

    public static class AdminPortalSnapshot {
        public final boolean reachable;
        public final List<VehicleApplication> applications;
        public final List<MessageItem> messages;
        public final Map<String, String> expandedUserDocs;
        public final String error;
        public AdminPortalSnapshot(boolean reachable, List<VehicleApplication> apps,
                                   List<MessageItem> msgs, Map<String, String> docs, String err) {
            this.reachable = reachable; this.applications = apps; this.messages = msgs;
            this.expandedUserDocs = docs; this.error = err;
        }
    }

    public interface LoginCallback { void onResult(User user, String error); }
    public interface AppListCallback { void onResult(List<VehicleApplication> apps, String error); }
    public interface SimpleCallback { void onResult(boolean success, String message); }
    public interface ActionCallback { void onComplete(boolean success, String message); }
    public interface StageFileCallback { void onResult(boolean success, File stagedFile, String message); }
    public interface RenewalCallback { void onResult(boolean success, String message, Map<String, String> uploadedFileNames); }
    public interface MessagesCallback { void onResult(boolean success, List<MessageItem> received, List<MessageItem> sent, String error); }
    public interface AdminMessagesCallback { void onResult(boolean success, List<MessageItem> messages, String error); }
    public interface UserPortalSyncCallback { void onComplete(UserPortalSnapshot snapshot); }
    public interface AdminPortalSyncCallback { void onComplete(AdminPortalSnapshot snapshot); }

    public static Map<String, String> pickNewestDocFiles(JSONArray files) {
        Map<String, String> mapped = new HashMap<>();
        if (files == null || files.length() == 0) return mapped;
        List<String> names = new ArrayList<>();
        for (int i = 0; i < files.length(); i++) {
            String f = files.optString(i, "").trim();
            if (!f.isEmpty()) names.add(f);
        }
        names.sort((a, b) -> {
            long ta = extractTs(a), tb = extractTs(b);
            return ta != tb ? Long.compare(ta, tb) : a.compareToIgnoreCase(b);
        });
        List<String> unmatched = new ArrayList<>();
        for (String f : names) {
            String l = f.toLowerCase(Locale.US);
            if (l.contains("cr_or") || l.contains("or_cr") || l.contains("orcr") || l.contains("cror")) mapped.put("cr_or", f);
            else if (l.contains("license") || l.contains("driver")) mapped.put("license", f);
            else if (l.contains("reg_form") || l.contains("regform") || l.contains("registration") || l.contains("cor")) mapped.put("reg_form", f);
            else if (l.contains("owner") || l.contains("auth") || l.contains("approval") || l.contains("letter") || l.contains("valid_id")) mapped.put("owner_id", f);
            else if (l.contains("vehicle") || l.contains("car") || l.contains("motor") || l.contains("photo")) mapped.put("vehicle_photo", f);
            else unmatched.add(f);
        }
        int idx = 0;
        for (String slot : new String[]{"cr_or", "license", "reg_form", "vehicle_photo", "owner_id"}) {
            if (!mapped.containsKey(slot) && idx < unmatched.size()) mapped.put(slot, unmatched.get(idx++));
        }
        return mapped;
    }

    private static long extractTs(String name) {
        Matcher m = TS_PATTERN.matcher(name);
        long ts = 0L;
        while (m.find()) { try { ts = Long.parseLong(m.group(1)); } catch (NumberFormatException ignored) {} }
        return ts;
    }

    public static void login(String email, String password, LoginCallback cb) {
        BACKGROUND_EXECUTOR.execute(() -> {
            RequestBody b = new FormBody.Builder().add("action", "login").add("email", email).add("password", password).build();
            try (Response res = httpPost(b)) {
                JSONObject json = new JSONObject(res.body() != null ? res.body().string() : "{}");
                if (json.optBoolean("success")) {
                    JSONObject u = json.getJSONObject("user");
                    User user = new User(u.getString("id"), u.getString("name"),
                            u.getString("email"), u.getString("role"), u.optString("department", ""));
                    mainHandler.post(() -> cb.onResult(user, null));
                } else {
                    mainHandler.post(() -> cb.onResult(null, json.optString("message", "Login failed")));
                }
            } catch (Exception e) {
                evictConnections();
                mainHandler.post(() -> cb.onResult(null, "Server unreachable: " + e.getMessage()));
            }
        });
    }

    private static VehicleApplication fetchUserAppSync(String ownerId) throws Exception {
        JSONObject appObj = new JSONObject(httpGet(BASE_URL + "?action=get_user_app&owner_id=" + Uri.encode(ownerId))).optJSONObject("application");
        return appObj != null ? new VehicleApplication(appObj) : null;
    }

    private static boolean createAppSync(String ownerId, String ownerName, String contact,
                                         String make, String model, String year, String color,
                                         String plate, String passType, String ownership) throws Exception {
        RequestBody b = new FormBody.Builder().add("action", "create_app").add("owner_id", ownerId)
                .add("owner_name", ownerName).add("contact_number", contact)
                .add("vehicle_model", make + "|" + model + "|" + year + "|" + color)
                .add("plate_number", plate.toUpperCase(Locale.US)).add("pass_type", passType).add("ownership", ownership).build();
        try (Response r = httpPost(b)) {
            if (!r.isSuccessful()) return false;
            String s = r.body() != null ? r.body().string().trim() : "";
            return !s.startsWith("{") || new JSONObject(s).optBoolean("success", true);
        }
    }

    public static void createApplication(String ownerId, String ownerName, String contact,
                                         String make, String model, String year, String color,
                                         String plate, String passType, String ownership, SimpleCallback cb) {
        BACKGROUND_EXECUTOR.execute(() -> {
            try {
                boolean ok = createAppSync(ownerId, ownerName, contact, make, model, year, color, plate, passType, ownership);
                mainHandler.post(() -> cb.onResult(ok, ok ? "Application submitted" : "Failed to submit application"));
            } catch (Exception e) { evictConnections(); mainHandler.post(() -> cb.onResult(false, e.getMessage())); }
        });
    }

    private static List<VehicleApplication> fetchAllAppsSync() throws Exception {
        JSONArray arr = new JSONObject(httpGet(BASE_URL + "?action=get_all_apps")).optJSONArray("applications");
        List<VehicleApplication> list = new ArrayList<>();
        if (arr != null) for (int i = 0; i < arr.length(); i++) list.add(new VehicleApplication(arr.getJSONObject(i)));
        return list;
    }

    public static void getAllApplications(AppListCallback cb) {
        BACKGROUND_EXECUTOR.execute(() -> {
            try { List<VehicleApplication> l = fetchAllAppsSync(); mainHandler.post(() -> cb.onResult(l, null)); }
            catch (Exception e) { evictConnections(); mainHandler.post(() -> cb.onResult(new ArrayList<>(), e.getMessage())); }
        });
    }

    public static Map<String, String> fetchUserDocsSync(String userId) throws Exception {
        return pickNewestDocFiles(new JSONObject(httpGet(BASE_URL + "?action=get_user_docs&user_id=" + Uri.encode(userId))).optJSONArray("files"));
    }

    public static void updateStatus(int appId, String status, String reason, SimpleCallback cb) {
        BACKGROUND_EXECUTOR.execute(() -> {
            FormBody.Builder fb = new FormBody.Builder().add("action", "update_status").add("id", String.valueOf(appId)).add("status", status);
            if (reason != null) fb.add("rejection_reason", reason);
            if ("APPROVED".equalsIgnoreCase(status)) fb.add("approval_date", fmtDate(new Date(), "yyyy-MM-dd HH:mm:ss"));
            try (Response r = httpPost(fb.build())) {
                boolean ok = r.isSuccessful();
                mainHandler.post(() -> cb.onResult(ok, ok ? "Status updated" : "Failed to update status"));
            } catch (Exception e) { evictConnections(); mainHandler.post(() -> cb.onResult(false, e.getMessage())); }
        });
    }

    private static File compressToTemp(Context ctx, Uri uri, String prefix) throws Exception {
        Bitmap orig;
        try (InputStream in = ctx.getContentResolver().openInputStream(uri)) {
            if (in == null) throw new IOException("Unable to read selected image.");
            orig = BitmapFactory.decodeStream(in);
        }
        if (orig == null) throw new IOException("Invalid image file.");
        int w = orig.getWidth(), h = orig.getHeight(), max = 1600;
        Bitmap scaled = (w > max || h > max)
                ? Bitmap.createScaledBitmap(orig, Math.round(w * Math.min((float) max / w, (float) max / h)),
                Math.round(h * Math.min((float) max / w, (float) max / h)), true) : orig;
        File out = new File(ctx.getCacheDir(), prefix + "_" + System.currentTimeMillis() + ".jpg");
        try (FileOutputStream fos = new FileOutputStream(out)) { scaled.compress(Bitmap.CompressFormat.JPEG, 85, fos); }
        return out;
    }

    private static String uploadDocSync(File file, String userId, String docType) throws Exception {
        String fname = "upload_" + docType + "_" + System.currentTimeMillis() + ".jpg";
        MultipartBody body = new MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("action", "upload_doc").addFormDataPart("user_id", userId)
                .addFormDataPart("doc_type", docType)
                .addFormDataPart("file", fname, RequestBody.create(file, MediaType.parse("image/jpeg"))).build();
        try (Response r = httpPost(body)) {
            JSONObject j = new JSONObject(r.body() != null ? r.body().string() : "{}");
            if (!j.optBoolean("success", false)) throw new IOException(j.optString("message", "Server rejected file"));
            return j.optString("filename", fname);
        }
    }

    public static void uploadDocument(Context ctx, Uri uri, String userId, String docType, ActionCallback cb) {
        Context appCtx = ctx.getApplicationContext();
        BACKGROUND_EXECUTOR.execute(() -> {
            File tmp = null;
            try {
                tmp = compressToTemp(appCtx, uri, "upload_" + docType);
                String stored = uploadDocSync(tmp, userId, docType);
                mainHandler.post(() -> cb.onComplete(true, stored));
            } catch (Exception e) {
                evictConnections();
                mainHandler.post(() -> cb.onComplete(false, errMsg(e, "Upload error")));
            } finally { if (tmp != null && tmp.exists()) tmp.delete(); }
        });
    }

    public static void stageDocumentForRenewal(Context ctx, Uri uri, String docType, StageFileCallback cb) {
        Context appCtx = ctx.getApplicationContext();
        BACKGROUND_EXECUTOR.execute(() -> {
            try {
                File staged = compressToTemp(appCtx, uri, "reapply_stage_" + docType);
                mainHandler.post(() -> cb.onResult(true, staged, "Ready"));
            } catch (Exception e) { mainHandler.post(() -> cb.onResult(false, null, errMsg(e, "Invalid image"))); }
        });
    }

    public static void submitRenewalApplication(String ownerId, String ownerName, String contact,
                                                String make, String model, String year, String color,
                                                String plate, String passType, String ownership,
                                                Map<String, File> stagedFiles, RenewalCallback cb) {
        Map<String, File> copy = new HashMap<>(stagedFiles);
        BACKGROUND_EXECUTOR.execute(() -> {
            try {
                try (Response del = httpPost(new FormBody.Builder().add("action", "delete_app").add("owner_id", ownerId).build())) {
                    if (!del.isSuccessful()) throw new IOException("Unable to clear expired application (HTTP " + del.code() + ")");
                }
                Map<String, CompletableFuture<String>> futs = new HashMap<>();
                for (Map.Entry<String, File> e : copy.entrySet()) {
                    String k = e.getKey(); File f = e.getValue();
                    futs.put(k, async(() -> uploadDocSync(f, ownerId, k)));
                }
                CompletableFuture.allOf(futs.values().toArray(new CompletableFuture[0])).join();
                Map<String, String> uploaded = new HashMap<>();
                for (Map.Entry<String, CompletableFuture<String>> e : futs.entrySet()) uploaded.put(e.getKey(), e.getValue().join());

                if (!createAppSync(ownerId, ownerName, contact, make, model, year, color, plate, passType, ownership))
                    throw new IOException("Server failed to create new renewal application.");
                for (File f : copy.values()) if (f != null && f.exists()) f.delete();
                mainHandler.post(() -> cb.onResult(true, "Renewal application submitted!", uploaded));
            } catch (Exception e) {
                evictConnections();
                mainHandler.post(() -> cb.onResult(false, errMsg(e, "Renewal submission failed"), Collections.emptyMap()));
            }
        });
    }

    private static JSONArray fetchMsgArray(String url) throws Exception {
        String s = httpGet(url);
        if (s.startsWith("[")) return new JSONArray(s);
        if (s.startsWith("{")) {
            JSONObject j = new JSONObject(s);
            for (String k : new String[]{"messages", "data", "sent", "received"}) {
                JSONArray a = j.optJSONArray(k);
                if (a != null) return a;
            }
        }
        return new JSONArray();
    }

    private static List<List<MessageItem>> fetchUserMsgsSync(String userId, String email, String name) throws Exception {
        String id = userId != null ? userId.trim() : "", em = email != null ? email.trim() : "", nm = name != null ? name.trim() : "";
        String q = "&user_id=" + Uri.encode(id) + "&email=" + Uri.encode(em);

        CompletableFuture<List<MessageItem>> recFut = async(() -> {
            JSONArray arr = fetchMsgArray(BASE_URL + "?action=get_messages&mode=USER_RECEIVED" + q);
            List<MessageItem> list = new ArrayList<>();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                String rec = optStr(o, "", "recipient_id", "recipient", "to_user");
                String ann = o.optString("is_announcement", "0");
                String cat = optStr(o, "", "category");
                if (cat.isEmpty()) cat = ("1".equals(ann) || "true".equalsIgnoreCase(ann) || "ALL".equalsIgnoreCase(rec)) ? "ANNOUNCEMENT" : "SECURITY NOTICE";
                list.add(new MessageItem(o.optInt("id"), optStr(o, "Gate 1 Security Desk", "sender_name", "sender", "from_user"),
                        rec, optStr(o, "(No Subject)", "subject", "title"), optStr(o, "", "body", "message", "content"),
                        cat, optStr(o, "", "created_at", "date_sent", "timestamp", "date"), false));
            }
            return list;
        });

        CompletableFuture<List<MessageItem>> sentFut = async(() -> {
            JSONArray arr = fetchMsgArray(BASE_URL + "?action=get_messages&mode=USER_SENT" + q);
            if (arr.length() == 0) arr = fetchMsgArray(BASE_URL + "?action=get_messages&mode=ADMIN" + q);
            List<MessageItem> list = new ArrayList<>();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                String ann = o.optString("is_announcement", "0");
                if ("1".equals(ann) || "true".equalsIgnoreCase(ann)) continue;
                String sender = optStr(o, "", "sender_name", "sender", "from_user");
                String rec = optStr(o, "", "recipient_id", "recipient", "to_user");
                String ls = sender.toLowerCase(Locale.US), lr = rec.toLowerCase(Locale.US);
                boolean fromMe = (!id.isEmpty() && sender.contains(id)) || (!nm.isEmpty() && ls.contains(nm.toLowerCase(Locale.US)))
                        || (!em.isEmpty() && ls.contains(em.toLowerCase(Locale.US)));
                boolean toAdmin = rec.isEmpty() || "ADMIN".equalsIgnoreCase(rec) || lr.contains("admin") || lr.contains("security");
                if (fromMe && toAdmin) {
                    list.add(new MessageItem(o.optInt("id"), sender, rec.isEmpty() ? "ADMIN" : rec,
                            optStr(o, "(No Subject)", "subject", "title"), optStr(o, "", "body", "message", "content"),
                            "TO: GATE 1 SECURITY DESK (ADMIN)", optStr(o, "", "created_at", "date_sent", "timestamp", "date"), true));
                }
            }
            return list;
        });

        CompletableFuture.allOf(recFut, sentFut).join();
        return Arrays.asList(recFut.join(), sentFut.join());
    }

    public static void getMessages(String userId, String email, String name, MessagesCallback cb) {
        BACKGROUND_EXECUTOR.execute(() -> {
            try { List<List<MessageItem>> p = fetchUserMsgsSync(userId, email, name); mainHandler.post(() -> cb.onResult(true, p.get(0), p.get(1), null)); }
            catch (Exception e) { evictConnections(); mainHandler.post(() -> cb.onResult(false, new ArrayList<>(), new ArrayList<>(), errMsg(e, "Unable to load messages"))); }
        });
    }

    public static void sendMessage(Context ctx, String sender, String subject, String body, Uri attachUri, ActionCallback cb) {
        Context appCtx = ctx.getApplicationContext();
        BACKGROUND_EXECUTOR.execute(() -> {
            File tmp = null;
            try {
                String finalBody = body;
                if (attachUri != null) {
                    String name = "attachment_" + System.currentTimeMillis();
                    try (Cursor c = appCtx.getContentResolver().query(attachUri, null, null, null, null)) {
                        if (c != null && c.moveToFirst()) {
                            int idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                            if (idx >= 0) name = c.getString(idx);
                        }
                    }
                    String safeName = name.replaceAll("[^a-zA-Z0-9._-]", "_");
                    tmp = new File(appCtx.getCacheDir(), safeName);
                    try (InputStream in = appCtx.getContentResolver().openInputStream(attachUri);
                         FileOutputStream out = new FileOutputStream(tmp)) {
                        if (in != null) { byte[] buf = new byte[4096]; int n; while ((n = in.read(buf)) > 0) out.write(buf, 0, n); }
                    }
                    String mime = appCtx.getContentResolver().getType(attachUri);
                    MultipartBody up = new MultipartBody.Builder().setType(MultipartBody.FORM)
                            .addFormDataPart("action", "upload_doc")
                            .addFormDataPart("user_id", currentUser != null ? currentUser.id : "student")
                            .addFormDataPart("doc_type", "msg_attachment")
                            .addFormDataPart("file", name, RequestBody.create(tmp, MediaType.parse(mime != null ? mime : "application/octet-stream"))).build();
                    try (Response ur = httpPost(up)) {
                        JSONObject uj = new JSONObject(ur.body() != null ? ur.body().string() : "{}");
                        if (uj.optBoolean("success", false)) finalBody += "\n[[ATTACHMENT:" + uj.optString("filename", name) + "]]";
                    }
                }
                RequestBody fb = new FormBody.Builder().add("action", "send_message").add("sender_name", sender).add("sender", sender)
                        .add("recipient_id", "ADMIN").add("recipient", "ADMIN").add("subject", subject).add("body", finalBody).add("is_announcement", "0").build();
                try (Response r = httpPost(fb)) {
                    String rs = r.body() != null ? r.body().string().trim() : "";
                    boolean ok = r.isSuccessful();
                    String msg = "Message sent to Gate 1 Security Desk!";
                    if (rs.startsWith("{")) {
                        JSONObject j = new JSONObject(rs);
                        ok = j.optBoolean("success", ok);
                        msg = j.optString("message", ok ? msg : "Failed to send message");
                    }
                    boolean fOk = ok; String fMsg = msg;
                    mainHandler.post(() -> cb.onComplete(fOk, fMsg));
                }
            } catch (Exception e) {
                evictConnections();
                mainHandler.post(() -> cb.onComplete(false, errMsg(e, "Error sending message")));
            } finally { if (tmp != null && tmp.exists()) tmp.delete(); }
        });
    }

    private static List<MessageItem> fetchAdminMsgsSync() throws Exception {
        CompletableFuture<List<MessageItem>> recFut = async(() -> {
            JSONArray arr = fetchMsgArray(BASE_URL + "?action=get_messages&mode=ADMIN_RECEIVED");
            List<MessageItem> list = new ArrayList<>();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                list.add(new MessageItem(o.optInt("id"), optStr(o, "Student", "sender_name", "sender"),
                        optStr(o, "ADMIN", "recipient_id", "recipient"), optStr(o, "(No Subject)", "subject"),
                        optStr(o, "", "body"), "STUDENT INQUIRY", optStr(o, "", "created_at", "date_sent", "timestamp"), false));
            }
            return list;
        });
        CompletableFuture<List<MessageItem>> sentFut = async(() -> {
            JSONArray arr = fetchMsgArray(BASE_URL + "?action=get_messages&mode=ADMIN_SENT");
            List<MessageItem> list = new ArrayList<>();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                String sender = optStr(o, "UE Security Desk", "sender_name", "sender");
                if ("Student".equalsIgnoreCase(sender)) sender = "UE Security Desk";
                String rec = optStr(o, "ALL", "recipient_id", "recipient"), subj = optStr(o, "(No Subject)", "subject");
                String ann = o.optString("is_announcement", "0"), sl = subj.toLowerCase(Locale.US);
                String cat = ("1".equals(ann) || "true".equalsIgnoreCase(ann) || "ALL".equalsIgnoreCase(rec)) ? "ANNOUNCEMENT"
                        : sl.contains("approved") ? "APPROVAL NOTICE"
                        : (sl.contains("rejected") || sl.contains("flagged")) ? "REJECTION NOTICE" : "SECURITY NOTICE";
                list.add(new MessageItem(o.optInt("id"), sender, rec, subj, optStr(o, "", "body"),
                        cat, optStr(o, "", "created_at", "date_sent", "timestamp"), true));
            }
            return list;
        });
        CompletableFuture.allOf(recFut, sentFut).join();
        List<MessageItem> all = new ArrayList<>(recFut.join());
        all.addAll(sentFut.join());
        all.sort((a, b) -> Integer.compare(b.id, a.id));
        return all;
    }

    public static void getAdminInboxMessages(AdminMessagesCallback cb) {
        BACKGROUND_EXECUTOR.execute(() -> {
            try { List<MessageItem> l = fetchAdminMsgsSync(); mainHandler.post(() -> cb.onResult(true, l, null)); }
            catch (Exception e) { evictConnections(); mainHandler.post(() -> cb.onResult(false, new ArrayList<>(), errMsg(e, "Inbox error"))); }
        });
    }

    public static void sendAdminMessage(String recipientId, String subject, String body, boolean isAnn, ActionCallback cb) {
        BACKGROUND_EXECUTOR.execute(() -> {
            RequestBody fb = new FormBody.Builder().add("action", "send_message").add("sender_name", "Gate 1 Security Desk")
                    .add("recipient_id", isAnn ? "ALL" : recipientId).add("subject", subject).add("body", body)
                    .add("is_announcement", isAnn ? "1" : "0").build();
            try (Response r = httpPost(fb)) {
                String rs = r.body() != null ? r.body().string().trim() : "";
                boolean ok = rs.startsWith("{") ? new JSONObject(rs).optBoolean("success", r.isSuccessful()) : r.isSuccessful();
                mainHandler.post(() -> cb.onComplete(ok, ok ? "Notice sent!" : "Failed to send notice"));
            } catch (Exception e) { evictConnections(); mainHandler.post(() -> cb.onComplete(false, e.getMessage())); }
        });
    }

    public static void syncUserPortalConcurrently(String userId, String email, String name, UserPortalSyncCallback cb) {
        BACKGROUND_EXECUTOR.execute(() -> {
            CompletableFuture<VehicleApplication> appF = async(() -> fetchUserAppSync(userId));
            CompletableFuture<Integer> quotaF = asyncSafe(() -> {
                int active = 0;
                for (VehicleApplication a : fetchAllAppsSync()) if (a.isActiveApproved()) active++;
                return Math.max(0, 500 - active);
            });
            CompletableFuture<Map<String, String>> docsF = asyncSafe(() -> fetchUserDocsSync(userId));
            CompletableFuture<List<List<MessageItem>>> msgsF = asyncSafe(() -> fetchUserMsgsSync(userId, email, name));
            try {
                VehicleApplication app = appF.join();
                List<List<MessageItem>> m = msgsF.join();
                UserPortalSnapshot snap = new UserPortalSnapshot(true, app, quotaF.join(), docsF.join(),
                        m != null ? m.get(0) : null, m != null ? m.get(1) : null, null);
                mainHandler.post(() -> cb.onComplete(snap));
            } catch (Exception e) {
                evictConnections();
                mainHandler.post(() -> cb.onComplete(new UserPortalSnapshot(false, null, null, null, null, null, errMsg(e, "Server unreachable"))));
            }
        });
    }

    public static void syncAdminPortalConcurrently(String expandedOwnerId, AdminPortalSyncCallback cb) {
        BACKGROUND_EXECUTOR.execute(() -> {
            CompletableFuture<List<VehicleApplication>> appsF = async(ApiClient::fetchAllAppsSync);
            CompletableFuture<List<MessageItem>> msgsF = asyncSafe(ApiClient::fetchAdminMsgsSync);
            CompletableFuture<Map<String, String>> docsF = asyncSafe(() ->
                    (expandedOwnerId == null || expandedOwnerId.isEmpty()) ? null : fetchUserDocsSync(expandedOwnerId));
            try {
                AdminPortalSnapshot snap = new AdminPortalSnapshot(true, appsF.join(), msgsF.join(), docsF.join(), null);
                mainHandler.post(() -> cb.onComplete(snap));
            } catch (Exception e) {
                evictConnections();
                mainHandler.post(() -> cb.onComplete(new AdminPortalSnapshot(false, null, null, null, errMsg(e, "Server unreachable"))));
            }
        });
    }
}

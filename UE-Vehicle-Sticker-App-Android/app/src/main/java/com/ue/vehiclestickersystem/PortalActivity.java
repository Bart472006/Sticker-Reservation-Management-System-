package com.ue.vehiclestickersystem;

import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.net.*;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.*;
import android.widget.*;
import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.cardview.widget.CardView;
import androidx.core.text.HtmlCompat;
import com.bumptech.glide.Glide;
import okhttp3.*;
import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

public class PortalActivity extends AppCompatActivity {
    private static final String[] DOC_KEYS = {"cr_or", "license", "reg_form", "vehicle_photo", "owner_id"};
    private static final String[] DOC_TITLES = {
            "CR / OR Document", "Driver's License", "UE Registration Form", "Vehicle Photo", "Owner's Valid ID & Approval Letter"
    };

    private boolean isAdminRole = false, isReplacementUpload = false, isRenewalReapplyMode = false, isSentTabSelected = false, isAdminSentTab = false;
    private String activeDocKey = "cr_or", lastLoadedVehiclePhotoUrl = null, inboxSearchQuery = "", adminInboxSearchQuery = "";
    private String selectedAdminFilterStatus = "ALL", adminRecordsSearchQuery = "", selectedAdminDocKey = "vehicle_photo", attachedFileName = null;
    private int pendingUploadsCount = 0;
    private Integer expandedAdminAppId = null;

    private TextView activeUploadLabel, activeReplacementStatusLabel, activeComposeAttachmentLabel, btnRefreshUser, btnRefreshAdmin;
    private Uri pendingComposeAttachmentUri = null;
    private ApiClient.MessageItem activeDetailMessage;
    private ApiClient.VehicleApplication currentStudentApp;

    private final AtomicBoolean isSyncInFlight = new AtomicBoolean(false);
    private ScheduledExecutorService autoSyncScheduler;
    private ConnectivityManager connectivityManager;
    private ConnectivityManager.NetworkCallback tailscaleNetworkCallback;

    private List<ApiClient.VehicleApplication> allAppsCache = new ArrayList<>();
    private final Map<String, String> studentUploadedFiles = new HashMap<>();
    private final Map<String, File> stagedRenewalFiles = new HashMap<>();
    private final Set<String> replacedDocKeys = new HashSet<>(), sessionUploadedDocKeys = new HashSet<>();
    private final List<ApiClient.MessageItem> cachedReceivedMsgs = new ArrayList<>(), cachedSentMsgs = new ArrayList<>(), cachedAdminMsgs = new ArrayList<>();
    private final Map<String, Map<String, String>> adminUserDocsMap = new HashMap<>();

    // Student UI views
    private View cardHeaderWelcomeBanner, tabDashboardContainer, tabInboxContainer, tabSupportContainer, inboxListScreen, inboxDetailScreen, layoutDetailAttachment;
    private LinearLayout navDashboard, navInbox, navSupport, cardAddVehicle, cardExpiredReapplyBanner, rowOwnerDocs, userInboxList;
    private LinearLayout cardQuotaNotice, layoutApprovedDetails, layoutSpecExpiryRow, verifiedRowOwnerDoc, detRowOwnerDoc;
    private CardView cardExistingApp;
    private FrameLayout composeModalOverlay, modalApplicationOverlay, modalVehicleDetailsOverlay, adminNoticeOverlay;
    private ImageView imgPassVehiclePhoto;
    private ScrollView scrollDetailBody;
    private Button btnReplyToAdmin, btnSubmitAppView;
    private Spinner spinnerPassType, spinnerOwnership, spinnerAdminFilter;
    private EditText etInboxSearch, etOwnerFullName, etContactNumber, etPlate, etMake, etModel, etYear, etColor, etAdminInboxSearch;
    private TextView txtTopScreenTitle, txtWelcomeName, txtUserSubInfo, txtStickersLeft, txtMyVehicleHeading;
    private TextView txtExpiredBannerTitle, txtExpiredBannerSubtitle, tvUnreadBadge, tabInboxReceived, tabInboxSent;
    private TextView tvDetailShortDate, tvDetailBadge, tvDetailFullDate, tvDetailSubject, tvDetailFrom, tvDetailTo, tvDetailBody, tvDetailAttachmentName;
    private TextView txtSpecApprovedDate, txtSpecExpiryDate, txtSpecDaysLeftBadge, specMake, specModel, specYear, specColor, specPlate, specStickerType, txtPassedCountBadge;
    private TextView txtPassStickerBadge, txtPassPlate, txtPassVehicleDesc, txtPassStatusPill, txtPassSubStatus, txtPassHolderId, txtPassExpiryDate;
    private TextView tvModalAppSubtitle, tvModalAppTitle, tvModalRenewalNotice;
    private TextView detMake, detModel, detYear, detColor, detPlate, detStickerType, detApprovalDate, detStatus, detExpiryDate, detRejectionNote;
    private TextView btnResubmitRejectedDocs, txtDocsFooterNote;
    private TextView[] btnUpViews, docStatusViews, btnReplaceViews;

    // Admin UI views
    private View adminTabDashboardContainer, adminTabRecordsContainer, adminTabInboxContainer, adminInboxListViewContainer, adminMessageDetailContainer, btnAdminReplyUser;
    private LinearLayout navAdminDashboard, navAdminRecords, navAdminInbox, adminPendingQueueList, adminRecentApprovalsList, adminInboxList, adminRecordsContainer;
    private ScrollView scrollPendingQueue;
    private ProgressBar progressStickerQuota;
    private TextView tvNavAdminRecordsLabel, tvNavAdminInboxLabel, txtKpiTotal, txtKpiPending, txtKpiApproved, txtKpiRejected;
    private TextView txtQuotaPercentBadge, txtQuotaAvailableCount, txtQuotaIssuedCount, txtPendingAwaitingCount, txtEmptyPendingQueue, txtRecentApprovedBadge;
    private TextView tvAdminInboxUnreadBadge, tabAdminInboxReceived, tabAdminInboxSent, tvAdminAttachmentName;
    private TextView tvAdminDetailTag, tvAdminDetailShortDate, tvAdminDetailTimestamp, tvAdminDetailSubject, tvAdminDetailFrom, tvAdminDetailTo, tvAdminDetailBody;

    private final ActivityResultLauncher<String> docPickerLauncher =
            registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> {
                if (uri == null || ApiClient.currentUser == null) return;
                if (activeUploadLabel != null) activeUploadLabel.setText("...");
                boolean replacing = isReplacementUpload, renewing = !replacing && isRenewalReapplyMode;
                String key = activeDocKey;
                TextView btn = activeUploadLabel, statusTv = activeReplacementStatusLabel;
                isReplacementUpload = false;
                pendingUploadsCount++;

                if (renewing) {
                    ApiClient.stageDocumentForRenewal(this, uri, key, (ok, staged, msg) -> {
                        pendingUploadsCount = Math.max(0, pendingUploadsCount - 1);
                        if (ok && staged != null) {
                            File prev = stagedRenewalFiles.put(key, staged);
                            if (prev != null && prev.exists()) prev.delete();
                            sessionUploadedDocKeys.add(key);
                            toast("New document ready!");
                            if (btn != null) { btn.setText("✓ New Ready"); btn.setTextColor(c("#15803D")); }
                        } else {
                            Toast.makeText(this, "Invalid document: " + msg, Toast.LENGTH_LONG).show();
                            if (btn != null) btn.setText("Retry");
                        }
                    });
                    return;
                }
                ApiClient.uploadDocument(this, uri, ApiClient.currentUser.id, key, (ok, msg) -> {
                    pendingUploadsCount = Math.max(0, pendingUploadsCount - 1);
                    if (ok) {
                        studentUploadedFiles.put(key, msg);
                        sessionUploadedDocKeys.add(key);
                        if ("vehicle_photo".equals(key)) lastLoadedVehiclePhotoUrl = null;
                        toast(replacing ? "Replacement uploaded!" : "Document uploaded!");
                        if (btn != null) { btn.setText(replacing ? "✓ Replaced" : "✓ Done"); btn.setTextColor(c("#15803D")); }
                        if (replacing) {
                            replacedDocKeys.add(key);
                            if (statusTv != null) { statusTv.setText("Replaced"); statusTv.setTextColor(c("#15803D")); }
                        }
                        fetchStudentUploadedDocs(null);
                    } else {
                        Toast.makeText(this, "Upload failed: " + msg, Toast.LENGTH_LONG).show();
                        if (btn != null) btn.setText("Retry");
                    }
                });
            });

    private final ActivityResultLauncher<String> composeFilePickerLauncher =
            registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> {
                if (uri != null) {
                    pendingComposeAttachmentUri = uri;
                    if (activeComposeAttachmentLabel != null) {
                        activeComposeAttachmentLabel.setText(resolveFileName(uri));
                        activeComposeAttachmentLabel.setTextColor(c("#111827"));
                    }
                }
            });

    private final ActivityResultLauncher<String> adminFilePickerLauncher =
            registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> {
                if (uri != null) {
                    attachedFileName = resolveFileName(uri);
                    if (tvAdminAttachmentName != null) tvAdminAttachmentName.setText(attachedFileName);
                }
            });

    // --- UI HELPERS ---
    private int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }
    private int c(String hex) { return Color.parseColor(hex); }
    private void toast(String msg) { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show(); }

    private GradientDrawable bg(String fillHex, float radiusDp, int strokeDp, String strokeHex) {
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(c(fillHex));
        gd.setCornerRadius(radiusDp * getResources().getDisplayMetrics().density);
        if (strokeDp > 0 && strokeHex != null) gd.setStroke(dp(strokeDp), c(strokeHex));
        return gd;
    }

    private TextView makeTv(String text, float sp, String colorHex, boolean bold) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(sp);
        t.setTextColor(c(colorHex));
        if (bold) t.setTypeface(null, Typeface.BOLD);
        return t;
    }

    private void watchText(EditText et, Consumer<String> cb) {
        if (et == null) return;
        et.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int cnt, int af) {}
            @Override public void onTextChanged(CharSequence s, int st, int bf, int cnt) {
                cb.accept(s != null ? s.toString().trim().toLowerCase(Locale.US) : "");
            }
            @Override public void afterTextChanged(Editable s) {}
        });
    }

    private void onSpinSelect(Spinner sp, IntConsumer cb) {
        sp.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> p, View v, int pos, long id) { cb.accept(pos); }
            @Override public void onNothingSelected(AdapterView<?> p) {}
        });
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(c("#9E0B0B"));
        if (ApiClient.currentUser == null) {
            startActivity(new Intent(this, MainActivity.class));
            finish();
            return;
        }
        isAdminRole = "ADMIN".equalsIgnoreCase(ApiClient.currentUser.role);
        if (isAdminRole) {
            setContentView(R.layout.activity_admin_portal);
            btnRefreshAdmin = findViewById(R.id.btnRefreshAdmin);
            if (btnRefreshAdmin != null) btnRefreshAdmin.setOnClickListener(v -> triggerPortalRefresh(true));
            findViewById(R.id.btnLogout).setOnClickListener(v -> logout());
            setupAdminPortal();
        } else {
            setContentView(R.layout.activity_user_portal);
            setupUserPortal();
        }
        registerTailscaleNetworkMonitor();
    }

    @Override protected void onResume() { super.onResume(); startBackgroundAutoSync(); }
    @Override protected void onPause() { super.onPause(); stopBackgroundAutoSync(); }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopBackgroundAutoSync();
        clearStagedRenewalFiles();
        if (connectivityManager != null && tailscaleNetworkCallback != null) {
            try { connectivityManager.unregisterNetworkCallback(tailscaleNetworkCallback); } catch (Exception ignored) {}
        }
    }

    private void registerTailscaleNetworkMonitor() {
        try {
            connectivityManager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            if (connectivityManager == null) return;
            NetworkRequest req = new NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).build();
            tailscaleNetworkCallback = new ConnectivityManager.NetworkCallback() {
                private void resync() {
                    ApiClient.evictConnections();
                    runOnUiThread(() -> { if (!isFinishing() && !isDestroyed()) triggerPortalRefresh(false); });
                }
                @Override public void onAvailable(@NonNull Network n) { resync(); }
                @Override public void onCapabilitiesChanged(@NonNull Network n, @NonNull NetworkCapabilities caps) {
                    if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) resync();
                }
            };
            connectivityManager.registerNetworkCallback(req, tailscaleNetworkCallback);
        } catch (Exception ignored) {}
    }

    private void startBackgroundAutoSync() {
        stopBackgroundAutoSync();
        autoSyncScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "UECampusPass-AutoSync"); t.setDaemon(true); return t;
        });
        autoSyncScheduler.scheduleWithFixedDelay(() -> runOnUiThread(() -> {
            if (!isFinishing() && !isDestroyed()) triggerPortalRefresh(false);
        }), 0, 6, TimeUnit.SECONDS);
    }

    private void stopBackgroundAutoSync() {
        if (autoSyncScheduler != null) { autoSyncScheduler.shutdownNow(); autoSyncScheduler = null; }
    }

    private void setRefreshBtnBusy(TextView btn, boolean busy) {
        if (btn != null) { btn.setText(busy ? "Syncing..." : "↻ Refresh"); btn.setEnabled(!busy); }
    }

    private void triggerPortalRefresh(boolean manual) {
        if (ApiClient.currentUser == null || isFinishing() || isDestroyed()) return;
        if (manual) {
            ApiClient.evictConnections();
            adminUserDocsMap.clear();
            lastLoadedVehiclePhotoUrl = null;
            setRefreshBtnBusy(btnRefreshUser, true);
            setRefreshBtnBusy(btnRefreshAdmin, true);
            isSyncInFlight.set(true);
        } else if (!isSyncInFlight.compareAndSet(false, true)) return;

        if (isAdminRole) {
            String expOwner = null;
            if (expandedAdminAppId != null) {
                for (ApiClient.VehicleApplication a : allAppsCache) if (a.id == expandedAdminAppId) { expOwner = a.ownerId; break; }
            }
            final String targetOwner = expOwner;
            ApiClient.syncAdminPortalConcurrently(targetOwner, snap -> {
                isSyncInFlight.set(false);
                setRefreshBtnBusy(btnRefreshAdmin, false);
                if (isFinishing() || isDestroyed()) return;
                if (!snap.reachable) {
                    if (manual) Toast.makeText(this, "Tailscale server unreachable (" + snap.error + ")", Toast.LENGTH_LONG).show();
                    return;
                }
                if (targetOwner != null && snap.expandedUserDocs != null) adminUserDocsMap.put(targetOwner, snap.expandedUserDocs);
                if (snap.applications != null) applyAdminApplicationsSnapshot(snap.applications);
                if (snap.messages != null) applyAdminInboxSnapshot(snap.messages);
                if (manual) toast("Synced with server!");
            });
        } else {
            ApiClient.syncUserPortalConcurrently(ApiClient.currentUser.id, ApiClient.currentUser.email, ApiClient.currentUser.name, snap -> {
                isSyncInFlight.set(false);
                setRefreshBtnBusy(btnRefreshUser, false);
                if (isFinishing() || isDestroyed()) return;
                if (!snap.reachable) {
                    if (manual) Toast.makeText(this, "Tailscale server unreachable (" + snap.error + ")", Toast.LENGTH_LONG).show();
                    return;
                }
                if (snap.remainingStickers != null && txtStickersLeft != null) txtStickersLeft.setText(String.valueOf(snap.remainingStickers));
                if (snap.uploadedDocs != null) { studentUploadedFiles.clear(); studentUploadedFiles.putAll(snap.uploadedDocs); }
                applyStudentApplicationState(snap.application);
                if (snap.receivedMessages != null && snap.sentMessages != null) {
                    cachedReceivedMsgs.clear(); cachedReceivedMsgs.addAll(snap.receivedMessages);
                    cachedSentMsgs.clear(); cachedSentMsgs.addAll(snap.sentMessages);
                    updateInboxSubTabUI();
                    if (tabInboxContainer != null && tabInboxContainer.getVisibility() == View.VISIBLE) renderFilteredInboxCards();
                }
                if (manual) toast("Synced with server!");
            });
        }
    }

    private void logout() {
        stopBackgroundAutoSync();
        clearStagedRenewalFiles();
        ApiClient.currentUser = null;
        startActivity(new Intent(this, MainActivity.class));
        finish();
    }

    private void clearStagedRenewalFiles() {
        for (File f : stagedRenewalFiles.values()) if (f != null && f.exists()) f.delete();
        stagedRenewalFiles.clear();
    }

    // =========================================================================
    // 1. STUDENT / USER PORTAL
    // =========================================================================
    private void setupUserPortal() {
        txtTopScreenTitle = findViewById(R.id.txtTopScreenTitle);
        txtWelcomeName = findViewById(R.id.txtWelcomeName);
        txtUserSubInfo = findViewById(R.id.txtUserSubInfo);
        txtStickersLeft = findViewById(R.id.txtStickersLeft);
        txtMyVehicleHeading = findViewById(R.id.txtMyVehicleHeading);
        cardHeaderWelcomeBanner = findViewById(R.id.cardHeaderWelcomeBanner);

        txtWelcomeName.setText(ApiClient.currentUser.name);
        String dept = (ApiClient.currentUser.department == null || ApiClient.currentUser.department.isEmpty()) ? "Student" : ApiClient.currentUser.department;
        txtUserSubInfo.setText("No: " + ApiClient.currentUser.id + " • " + dept);

        btnRefreshUser = findViewById(R.id.btnRefreshUser);
        if (btnRefreshUser != null) btnRefreshUser.setOnClickListener(v -> triggerPortalRefresh(true));
        findViewById(R.id.btnLogoutUser).setOnClickListener(v -> logout());

        tabDashboardContainer = findViewById(R.id.tabDashboardContainer);
        tabInboxContainer = findViewById(R.id.tabInboxContainer);
        tabSupportContainer = findViewById(R.id.tabSupportContainer);
        navDashboard = findViewById(R.id.navDashboard);
        navInbox = findViewById(R.id.navInbox);
        navSupport = findViewById(R.id.navSupport);
        navDashboard.setOnClickListener(v -> switchUserTab(0));
        navInbox.setOnClickListener(v -> switchUserTab(1));
        navSupport.setOnClickListener(v -> switchUserTab(2));

        cardAddVehicle = findViewById(R.id.cardAddVehicle);
        cardExpiredReapplyBanner = findViewById(R.id.cardExpiredReapplyBanner);
        txtExpiredBannerTitle = findViewById(R.id.txtExpiredBannerTitle);
        txtExpiredBannerSubtitle = findViewById(R.id.txtExpiredBannerSubtitle);
        cardExistingApp = findViewById(R.id.cardExistingApp);
        imgPassVehiclePhoto = findViewById(R.id.imgPassVehiclePhoto);
        txtPassStickerBadge = findViewById(R.id.txtPassStickerBadge);
        txtPassPlate = findViewById(R.id.txtPassPlate);
        txtPassVehicleDesc = findViewById(R.id.txtPassVehicleDesc);
        txtPassStatusPill = findViewById(R.id.txtPassStatusPill);
        txtPassSubStatus = findViewById(R.id.txtPassSubStatus);
        txtPassHolderId = findViewById(R.id.txtPassHolderId);
        txtPassExpiryDate = findViewById(R.id.txtPassExpiryDate);

        if (cardExpiredReapplyBanner != null) cardExpiredReapplyBanner.setOnClickListener(v -> openApplicationModal(true));
        cardExistingApp.setOnClickListener(v -> {
            if (currentStudentApp != null && currentStudentApp.isExpired()) openApplicationModal(true);
            else openVehicleDetailsModal();
        });

        cardQuotaNotice = findViewById(R.id.cardQuotaNotice);
        layoutApprovedDetails = findViewById(R.id.layoutApprovedDetails);
        layoutSpecExpiryRow = findViewById(R.id.layoutSpecExpiryRow);
        verifiedRowOwnerDoc = findViewById(R.id.verifiedRowOwnerDoc);
        txtSpecApprovedDate = findViewById(R.id.txtSpecApprovedDate);
        txtSpecExpiryDate = findViewById(R.id.txtSpecExpiryDate);
        txtSpecDaysLeftBadge = findViewById(R.id.txtSpecDaysLeftBadge);
        specMake = findViewById(R.id.specMake); specModel = findViewById(R.id.specModel);
        specYear = findViewById(R.id.specYear); specColor = findViewById(R.id.specColor);
        specPlate = findViewById(R.id.specPlate); specStickerType = findViewById(R.id.specStickerType);
        txtPassedCountBadge = findViewById(R.id.txtPassedCountBadge);

        int[] verifiedViewIds = {R.id.btnVerifiedViewCrOr, R.id.btnVerifiedViewLicense, R.id.btnVerifiedViewRegForm, R.id.btnVerifiedViewVehicle, R.id.btnVerifiedViewOwner};
        int[] detViewIds = {R.id.btnViewDocCrOr, R.id.btnViewDocLicense, R.id.btnViewDocRegForm, R.id.btnViewDocVehicle, R.id.btnViewDocOwner};
        for (int i = 0; i < DOC_KEYS.length; i++) {
            final String k = DOC_KEYS[i], t = DOC_TITLES[i];
            findViewById(verifiedViewIds[i]).setOnClickListener(v -> previewSingleStudentDoc(k, t));
            findViewById(detViewIds[i]).setOnClickListener(v -> previewSingleStudentDoc(k, t));
        }

        // Modal 1: Application Form
        modalApplicationOverlay = findViewById(R.id.modalApplicationOverlay);
        tvModalAppSubtitle = findViewById(R.id.tvModalAppSubtitle);
        tvModalAppTitle = findViewById(R.id.tvModalAppTitle);
        tvModalRenewalNotice = findViewById(R.id.tvModalRenewalNotice);
        cardAddVehicle.setOnClickListener(v -> openApplicationModal(false));
        findViewById(R.id.btnCloseModal).setOnClickListener(v -> { clearStagedRenewalFiles(); modalApplicationOverlay.setVisibility(View.GONE); });

        spinnerPassType = findViewById(R.id.spinnerPassType);
        spinnerOwnership = findViewById(R.id.spinnerOwnership);
        rowOwnerDocs = findViewById(R.id.rowOwnerDocs);
        etOwnerFullName = findViewById(R.id.etOwnerFullName);
        etContactNumber = findViewById(R.id.etContactNumber);
        etOwnerFullName.setText(ApiClient.currentUser.name);
        etMake = findViewById(R.id.etMake); etModel = findViewById(R.id.etModel);
        etYear = findViewById(R.id.etYear); etColor = findViewById(R.id.etColor); etPlate = findViewById(R.id.etPlate);

        spinnerPassType.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, new String[]{"Parking Sticker", "Drop-Off Sticker"}));
        spinnerOwnership.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, new String[]{"My Own Vehicle", "Another Person's Vehicle"}));
        onSpinSelect(spinnerOwnership, pos -> rowOwnerDocs.setVisibility(spinnerOwnership.getItemAtPosition(pos).toString().toLowerCase(Locale.US).contains("another") ? View.VISIBLE : View.GONE));

        int[] upIds = {R.id.btnUpCrOr, R.id.btnUpLicense, R.id.btnUpRegForm, R.id.btnUpVehicle, R.id.btnUpOwnerDocs};
        btnUpViews = new TextView[DOC_KEYS.length];
        for (int i = 0; i < DOC_KEYS.length; i++) {
            final String key = DOC_KEYS[i];
            TextView btn = findViewById(upIds[i]);
            btnUpViews[i] = btn;
            btn.setOnClickListener(v -> { isReplacementUpload = false; activeDocKey = key; activeUploadLabel = btn; docPickerLauncher.launch("image/*"); });
        }
        btnSubmitAppView = findViewById(R.id.btnSubmitApp);
        btnSubmitAppView.setOnClickListener(v -> submitStudentApplication());

        // Modal 2: Vehicle Details
        modalVehicleDetailsOverlay = findViewById(R.id.modalVehicleDetailsOverlay);
        detMake = findViewById(R.id.detMake); detModel = findViewById(R.id.detModel);
        detYear = findViewById(R.id.detYear); detColor = findViewById(R.id.detColor);
        detPlate = findViewById(R.id.detPlate); detStickerType = findViewById(R.id.detStickerType);
        detApprovalDate = findViewById(R.id.detApprovalDate); detStatus = findViewById(R.id.detStatus);
        detExpiryDate = findViewById(R.id.detExpiryDate); detRejectionNote = findViewById(R.id.detRejectionNote);
        detRowOwnerDoc = findViewById(R.id.detRowOwnerDoc);
        btnResubmitRejectedDocs = findViewById(R.id.btnResubmitRejectedDocs);
        txtDocsFooterNote = findViewById(R.id.txtDocsFooterNote);

        int[] statusIds = {R.id.docStatusCrOr, R.id.docStatusLicense, R.id.docStatusRegForm, R.id.docStatusVehicle, R.id.docStatusOwner};
        int[] repIds = {R.id.btnReplaceDocCrOr, R.id.btnReplaceDocLicense, R.id.btnReplaceDocRegForm, R.id.btnReplaceDocVehicle, R.id.btnReplaceDocOwner};
        docStatusViews = new TextView[DOC_KEYS.length];
        btnReplaceViews = new TextView[DOC_KEYS.length];
        for (int i = 0; i < DOC_KEYS.length; i++) {
            final String key = DOC_KEYS[i];
            TextView st = findViewById(statusIds[i]), rb = findViewById(repIds[i]);
            docStatusViews[i] = st; btnReplaceViews[i] = rb;
            rb.setOnClickListener(v -> { isReplacementUpload = true; activeDocKey = key; activeUploadLabel = rb; activeReplacementStatusLabel = st; docPickerLauncher.launch("image/*"); });
        }

        btnResubmitRejectedDocs.setOnClickListener(v -> resubmitRejectedDocuments());
        findViewById(R.id.btnCloseDetailsX).setOnClickListener(v -> modalVehicleDetailsOverlay.setVisibility(View.GONE));
        findViewById(R.id.btnCloseDetailsBottom).setOnClickListener(v -> modalVehicleDetailsOverlay.setVisibility(View.GONE));
        findViewById(R.id.btnWithdrawReapply).setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle("Withdraw Application")
                .setMessage("Are you sure you want to cancel and remove your current vehicle sticker application?")
                .setPositiveButton("Yes, Withdraw", (d, w) -> withdrawStudentApplication())
                .setNegativeButton("Keep", null).show());

        setupInboxTabAndCompose();
        setupPickupAndSupportTab();

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() {
                if (adminNoticeOverlay != null && adminNoticeOverlay.getVisibility() == View.VISIBLE) adminNoticeOverlay.setVisibility(View.GONE);
                else if (composeModalOverlay != null && composeModalOverlay.getVisibility() == View.VISIBLE) { activeComposeAttachmentLabel = null; composeModalOverlay.setVisibility(View.GONE); }
                else if (inboxDetailScreen != null && inboxDetailScreen.getVisibility() == View.VISIBLE) closeMessageDetail();
                else { setEnabled(false); getOnBackPressedDispatcher().onBackPressed(); }
            }
        });

        triggerPortalRefresh(false);
    }

    private void openApplicationModal(boolean forRenewal) {
        clearStagedRenewalFiles();
        sessionUploadedDocKeys.clear();
        for (TextView btn : btnUpViews) if (btn != null) { btn.setText("Upload"); btn.setTextColor(c("#374151")); }

        isRenewalReapplyMode = forRenewal && currentStudentApp != null;
        if (isRenewalReapplyMode) {
            if (tvModalAppSubtitle != null) tvModalAppSubtitle.setText("Expired Sticker Renewal");
            if (tvModalAppTitle != null) tvModalAppTitle.setText("Reapply Vehicle Sticker");
            if (tvModalRenewalNotice != null) tvModalRenewalNotice.setVisibility(View.VISIBLE);
            if (btnSubmitAppView != null) btnSubmitAppView.setText("Submit Renewal Application");

            etOwnerFullName.setText(!currentStudentApp.ownerName.isEmpty() ? currentStudentApp.ownerName : ApiClient.currentUser.name);
            etContactNumber.setText(currentStudentApp.contactNumber);
            etMake.setText(currentStudentApp.make); etModel.setText(currentStudentApp.model);
            etYear.setText(currentStudentApp.year); etColor.setText(currentStudentApp.color);
            etPlate.setText(currentStudentApp.plateNumber);
            spinnerPassType.setSelection(currentStudentApp.passType != null && currentStudentApp.passType.toLowerCase(Locale.US).contains("drop") ? 1 : 0);
            boolean another = isNonOwned(currentStudentApp);
            spinnerOwnership.setSelection(another ? 1 : 0);
            rowOwnerDocs.setVisibility(another ? View.VISIBLE : View.GONE);
        } else {
            if (tvModalAppSubtitle != null) tvModalAppSubtitle.setText("AY 2026-2027 Registration");
            if (tvModalAppTitle != null) tvModalAppTitle.setText("Vehicle Sticker Application");
            if (tvModalRenewalNotice != null) tvModalRenewalNotice.setVisibility(View.GONE);
            if (btnSubmitAppView != null) btnSubmitAppView.setText("Submit Application");
        }
        modalApplicationOverlay.setVisibility(View.VISIBLE);
    }

    private boolean isNonOwned(ApiClient.VehicleApplication app) {
        return app != null && app.ownership != null && app.ownership.toLowerCase(Locale.US).contains("another");
    }

    private void switchUserTab(int idx) {
        closeMessageDetail();
        tabDashboardContainer.setVisibility(idx == 0 ? View.VISIBLE : View.GONE);
        tabInboxContainer.setVisibility(idx == 1 ? View.VISIBLE : View.GONE);
        tabSupportContainer.setVisibility(idx == 2 ? View.VISIBLE : View.GONE);
        updateNavTabVisual(navDashboard, idx == 0);
        updateNavTabVisual(navInbox, idx == 1);
        updateNavTabVisual(navSupport, idx == 2);
        txtTopScreenTitle.setText(idx == 0 ? "Dashboard" : "UE Campus Pass");
        if (cardHeaderWelcomeBanner != null) cardHeaderWelcomeBanner.setVisibility(idx == 2 ? View.GONE : View.VISIBLE);
        if (idx == 1) loadUserMessages();
    }

    private void updateNavTabVisual(LinearLayout tab, boolean selected) {
        if (tab == null) return;
        tab.setBackgroundTintList(null);
        tab.setBackground(selected ? bg("#29FFFFFF", 24f, 0, null) : null);
        tab.setAlpha(selected ? 1f : 0.75f);
        for (int i = 0; i < tab.getChildCount(); i++) {
            View ch = tab.getChildAt(i);
            if (!(ch instanceof ImageView) && !(ch instanceof TextView)) ch.setVisibility(View.GONE);
        }
    }

    private void setPassPill(String pillText, String fgHex, String bgHex, String subText, String subHex, String expText, String expHex) {
        txtPassStatusPill.setText(pillText);
        txtPassStatusPill.setTextColor(c(fgHex));
        txtPassStatusPill.setBackgroundTintList(ColorStateList.valueOf(c(bgHex)));
        txtPassSubStatus.setText(subText);
        txtPassSubStatus.setTextColor(c(subHex));
        if (txtPassExpiryDate != null) { txtPassExpiryDate.setText(expText); txtPassExpiryDate.setTextColor(c(expHex)); }
    }

    private void applyStudentApplicationState(ApiClient.VehicleApplication app) {
        currentStudentApp = app;
        if (app == null) {
            txtMyVehicleHeading.setVisibility(View.VISIBLE);
            cardAddVehicle.setVisibility(View.VISIBLE);
            if (cardExpiredReapplyBanner != null) cardExpiredReapplyBanner.setVisibility(View.GONE);
            cardQuotaNotice.setVisibility(View.VISIBLE);
            cardExistingApp.setVisibility(View.GONE);
            layoutApprovedDetails.setVisibility(View.GONE);
            lastLoadedVehiclePhotoUrl = null;
            return;
        }
        txtMyVehicleHeading.setVisibility(View.GONE);
        cardAddVehicle.setVisibility(View.GONE);
        cardExistingApp.setVisibility(View.VISIBLE);

        txtPassStickerBadge.setText(app.passType);
        txtPassPlate.setText(app.plateNumber);
        txtPassVehicleDesc.setText(app.make + " " + app.model + " (" + app.color + ")");
        txtPassHolderId.setText("Holder : " + app.ownerId);

        boolean expired = app.isExpired();
        if (cardExpiredReapplyBanner != null) cardExpiredReapplyBanner.setVisibility(expired ? View.VISIBLE : View.GONE);

        if (expired) {
            if (txtExpiredBannerTitle != null) txtExpiredBannerTitle.setText("Sticker expired Click here to reapply");
            if (txtExpiredBannerSubtitle != null) txtExpiredBannerSubtitle.setText("Expired on " + app.getFormattedExpirationDate() + " • Tap here to upload new documents.");
            setPassPill("•  EXPIRED", "#DC2626", "#FEE2E2", "Sticker expired Click here to reapply", "#DC2626", "Expired: " + app.getFormattedExpirationDate(), "#DC2626");
            cardQuotaNotice.setVisibility(View.VISIBLE);
            layoutApprovedDetails.setVisibility(View.GONE);
        } else if ("APPROVED".equalsIgnoreCase(app.status)) {
            long days = Math.max(0L, app.getDaysUntilExpiration());
            boolean soon = days <= 30L;
            setPassPill("•  APPROVED", "#15803D", "#DCFCE7",
                    soon ? ("Expires in " + days + (days == 1 ? " day" : " days")) : "Active pass",
                    soon ? "#D97706" : "#6B7280", "Expires: " + app.getFormattedExpirationDate(), soon ? "#D97706" : "#15803D");
            cardQuotaNotice.setVisibility(View.GONE);
            layoutApprovedDetails.setVisibility(View.VISIBLE);

            txtSpecApprovedDate.setText("Approved : " + app.getFormattedApprovalDate());
            if (txtSpecExpiryDate != null) { txtSpecExpiryDate.setText("Sticker Expires : " + app.getFormattedExpirationDate()); txtSpecExpiryDate.setTextColor(c(soon ? "#B45309" : "#15803D")); }
            if (txtSpecDaysLeftBadge != null) { txtSpecDaysLeftBadge.setText(days + (days == 1 ? " day left" : " days left")); txtSpecDaysLeftBadge.setTextColor(c(soon ? "#92400E" : "#166534")); }
            if (layoutSpecExpiryRow != null) layoutSpecExpiryRow.setBackgroundTintList(ColorStateList.valueOf(c(soon ? "#FEF3C7" : "#DCFCE7")));

            specMake.setText(app.make); specModel.setText(app.model); specYear.setText(app.year);
            specColor.setText(app.color); specPlate.setText(app.plateNumber); specStickerType.setText(app.passType);
            boolean another = isNonOwned(app);
            verifiedRowOwnerDoc.setVisibility(another ? View.VISIBLE : View.GONE);
            txtPassedCountBadge.setText(another ? "5/5 PASSED" : "4/4 PASSED");
        } else if ("REJECTED".equalsIgnoreCase(app.status)) {
            setPassPill("•  REJECTED", "#DC2626", "#FEE2E2", "Tap to re-submit files", "#DC2626", "Action required", "#DC2626");
            cardQuotaNotice.setVisibility(View.VISIBLE);
            layoutApprovedDetails.setVisibility(View.GONE);
        } else {
            setPassPill("•  PENDING", "#92400E", "#FEF3C7", "Awaiting screening", "#6B7280", "Validity: 1 Year upon approval", "#6B7280");
            cardQuotaNotice.setVisibility(View.VISIBLE);
            layoutApprovedDetails.setVisibility(View.GONE);
        }
        bindVehiclePassPhotoIfAvailable();
    }

    private void bindVehiclePassPhotoIfAvailable() {
        String img = studentUploadedFiles.get("vehicle_photo");
        if (img != null && !img.isEmpty() && imgPassVehiclePhoto != null) {
            String url = ApiClient.UPLOADS_URL + Uri.encode(img);
            if (!url.equals(lastLoadedVehiclePhotoUrl)) {
                lastLoadedVehiclePhotoUrl = url;
                Glide.with(this).load(url).centerCrop().into(imgPassVehiclePhoto);
            }
        }
    }

    private void fetchStudentUploadedDocs(Runnable onDone) {
        if (ApiClient.currentUser == null) return;
        String uid = (currentStudentApp != null && !currentStudentApp.ownerId.isEmpty()) ? currentStudentApp.ownerId : ApiClient.currentUser.id;
        ApiClient.BACKGROUND_EXECUTOR.execute(() -> {
            try {
                Map<String, String> docs = ApiClient.fetchUserDocsSync(uid);
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    studentUploadedFiles.clear(); studentUploadedFiles.putAll(docs);
                    bindVehiclePassPhotoIfAvailable();
                    if (onDone != null) onDone.run();
                });
            } catch (Exception ignored) { if (onDone != null) runOnUiThread(onDone); }
        });
    }

    private void setAllDocStatuses(String label, String colorHex) {
        for (TextView tv : docStatusViews) { tv.setText(label); tv.setTextColor(c(colorHex)); }
    }

    private void openVehicleDetailsModal() {
        if (currentStudentApp == null) return;
        fetchStudentUploadedDocs(null);

        detMake.setText(currentStudentApp.make); detModel.setText(currentStudentApp.model);
        detYear.setText(currentStudentApp.year); detColor.setText(currentStudentApp.color);
        detPlate.setText(currentStudentApp.plateNumber); detStickerType.setText(currentStudentApp.passType);

        for (TextView rb : btnReplaceViews) rb.setVisibility(View.GONE);
        btnResubmitRejectedDocs.setVisibility(View.GONE);
        btnResubmitRejectedDocs.setText("↻ Resubmit Rejected Document(s)");
        btnResubmitRejectedDocs.setOnClickListener(v -> resubmitRejectedDocuments());

        boolean expired = currentStudentApp.isExpired(), another = isNonOwned(currentStudentApp);
        detRowOwnerDoc.setVisibility(another ? View.VISIBLE : View.GONE);

        if (expired) {
            detApprovalDate.setText(currentStudentApp.getFormattedApprovalDate());
            detStatus.setText("Expired"); detStatus.setTextColor(c("#DC2626"));
            if (detExpiryDate != null) { detExpiryDate.setText("Expired on " + currentStudentApp.getFormattedExpirationDate()); detExpiryDate.setTextColor(c("#DC2626")); }
            detRejectionNote.setVisibility(View.VISIBLE);
            detRejectionNote.setText("1-year sticker validity exceeded. Please reapply and re-upload updated documents.");
            txtDocsFooterNote.setVisibility(View.GONE);
            btnResubmitRejectedDocs.setVisibility(View.VISIBLE);
            btnResubmitRejectedDocs.setText("Sticker expired Click here to reapply");
            btnResubmitRejectedDocs.setOnClickListener(v -> { modalVehicleDetailsOverlay.setVisibility(View.GONE); openApplicationModal(true); });
            setAllDocStatuses("Expired", "#DC2626");
        } else if ("APPROVED".equalsIgnoreCase(currentStudentApp.status)) {
            long days = Math.max(0L, currentStudentApp.getDaysUntilExpiration());
            detApprovalDate.setText(currentStudentApp.getFormattedApprovalDate());
            detStatus.setText("Approved"); detStatus.setTextColor(c("#15803D"));
            if (detExpiryDate != null) {
                detExpiryDate.setText(currentStudentApp.getFormattedExpirationDate() + " (" + days + (days == 1 ? " day remaining)" : " days remaining)"));
                detExpiryDate.setTextColor(c(days <= 30 ? "#D97706" : "#15803D"));
            }
            detRejectionNote.setVisibility(View.GONE);
            txtDocsFooterNote.setVisibility(View.VISIBLE);
            setAllDocStatuses("Passed", "#15803D");
        } else if ("REJECTED".equalsIgnoreCase(currentStudentApp.status)) {
            detApprovalDate.setText("Application Rejected");
            detStatus.setText("Rejected"); detStatus.setTextColor(c("#DC2626"));
            if (detExpiryDate != null) { detExpiryDate.setText("N/A (Rejected)"); detExpiryDate.setTextColor(c("#DC2626")); }
            String reason = (currentStudentApp.rejectionReason == null || currentStudentApp.rejectionReason.trim().isEmpty())
                    ? "Incomplete or invalid documents" : currentStudentApp.rejectionReason.trim();
            detRejectionNote.setVisibility(View.VISIBLE);
            detRejectionNote.setText("Flagged Reason: " + reason);
            txtDocsFooterNote.setVisibility(View.GONE);
            btnResubmitRejectedDocs.setVisibility(View.VISIBLE);

            String l = reason.toLowerCase(Locale.US);
            boolean[] flags = {
                    l.contains("cr / or") || l.contains("cr/or") || l.contains("or/cr") || l.contains("or / cr") || l.contains("cr document") || l.contains("or document"),
                    l.contains("license") || l.contains("driver"),
                    l.contains("registration form") || l.contains("reg form") || l.contains("ue registration") || l.contains("cor"),
                    l.contains("vehicle photo") || l.contains("plate photo") || l.contains("car photo"),
                    l.contains("owner") || l.contains("authorization") || l.contains("approval letter") || l.contains("valid id")
            };
            if (!flags[0] && !flags[1] && !flags[2] && !flags[3] && !flags[4]) { flags[0] = flags[1] = flags[2] = flags[3] = true; flags[4] = another; }
            for (int i = 0; i < 4; i++) applyRejectedDocRowState(DOC_KEYS[i], flags[i], docStatusViews[i], btnReplaceViews[i]);
            if (another || flags[4]) {
                detRowOwnerDoc.setVisibility(View.VISIBLE);
                applyRejectedDocRowState(DOC_KEYS[4], flags[4], docStatusViews[4], btnReplaceViews[4]);
            }
        } else {
            detApprovalDate.setText("Awaiting Approval");
            detStatus.setText("Pending"); detStatus.setTextColor(c("#0F172A"));
            if (detExpiryDate != null) { detExpiryDate.setText("1 Year upon approval"); detExpiryDate.setTextColor(c("#64748B")); }
            detRejectionNote.setVisibility(View.GONE);
            txtDocsFooterNote.setVisibility(View.VISIBLE);
            setAllDocStatuses("Pending", "#D97706");
        }
        modalVehicleDetailsOverlay.setVisibility(View.VISIBLE);
    }

    private void applyRejectedDocRowState(String key, boolean flagged, TextView statusTv, TextView replaceBtn) {
        if (!flagged) {
            statusTv.setText("Passed"); statusTv.setTextColor(c("#15803D"));
            replaceBtn.setVisibility(View.GONE);
        } else if (replacedDocKeys.contains(key)) {
            statusTv.setText("Replaced"); statusTv.setTextColor(c("#15803D"));
            replaceBtn.setVisibility(View.VISIBLE); replaceBtn.setText("✓ Replaced"); replaceBtn.setTextColor(c("#15803D"));
        } else {
            statusTv.setText("Rejected"); statusTv.setTextColor(c("#DC2626"));
            replaceBtn.setVisibility(View.VISIBLE); replaceBtn.setText("↻ Upload Replacement"); replaceBtn.setTextColor(c("#B91C1C"));
        }
    }

    private void resubmitRejectedDocuments() {
        if (currentStudentApp == null) return;
        if (pendingUploadsCount > 0) { toast("Please wait for upload to finish..."); return; }
        if (replacedDocKeys.isEmpty()) { toast("Please upload at least one replacement document first."); return; }
        ApiClient.updateStatus(currentStudentApp.id, "PENDING", null, (ok, msg) -> {
            if (ok) {
                replacedDocKeys.clear();
                modalVehicleDetailsOverlay.setVisibility(View.GONE);
                toast("Documents resubmitted for screening!");
                triggerPortalRefresh(false);
            } else toast("Resubmit failed: " + msg);
        });
    }

    private void previewSingleStudentDoc(String key, String title) {
        String f = studentUploadedFiles.get(key);
        if (f != null && !f.isEmpty()) { showDocPreviewDialog(f, title); return; }
        toast("Loading " + title + "...");
        fetchStudentUploadedDocs(() -> {
            String fetched = studentUploadedFiles.get(key);
            if (fetched == null || fetched.isEmpty()) toast("No uploaded file found for " + title);
            else showDocPreviewDialog(fetched, title);
        });
    }

    private void showDocPreviewDialog(String filename, String title) {
        String url = ApiClient.UPLOADS_URL + Uri.encode(filename);
        if (filename.toLowerCase(Locale.US).endsWith(".pdf")) { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); return; }

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(32, 24, 32, 16);
        box.setGravity(Gravity.CENTER);

        TextView lbl = makeTv(filename, 12f, "#64748B", false);
        lbl.setPadding(0, 0, 0, 16);
        ImageView iv = new ImageView(this);
        iv.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(360)));
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        box.addView(lbl); box.addView(iv);

        Glide.with(this).load(url).override(1080, 1080).fitCenter().into(iv);
        new AlertDialog.Builder(this).setTitle(title).setView(box).setPositiveButton("Close", null).show();
    }

    private void submitStudentApplication() {
        if (pendingUploadsCount > 0) { toast("Please wait for document preparation/upload to finish..."); return; }
        String owner = etOwnerFullName.getText().toString().trim(), contact = etContactNumber.getText().toString().trim();
        String plate = etPlate.getText().toString().trim(), make = etMake.getText().toString().trim(), model = etModel.getText().toString().trim();
        String year = etYear.getText().toString().trim(), color = etColor.getText().toString().trim();
        String passType = spinnerPassType.getSelectedItem().toString(), ownership = spinnerOwnership.getSelectedItem().toString();

        if (owner.isEmpty() || contact.isEmpty() || plate.isEmpty() || make.isEmpty() || model.isEmpty()) {
            toast("Please fill in Owner Name, Contact Number, Make, Model, and Plate No."); return;
        }
        String yr = year.isEmpty() ? "2024" : year, col = color.isEmpty() ? "Standard" : color;

        if (isRenewalReapplyMode) {
            int reqCount = ownership.toLowerCase(Locale.US).contains("another") ? 5 : 4;
            List<String> missing = new ArrayList<>();
            for (int i = 0; i < reqCount; i++) if (!stagedRenewalFiles.containsKey(DOC_KEYS[i])) missing.add(DOC_TITLES[i]);
            if (!missing.isEmpty()) {
                Toast.makeText(this, "Please re-upload new documents to reapply: " + TextUtils.join(", ", missing), Toast.LENGTH_LONG).show();
                return;
            }
            btnSubmitAppView.setEnabled(false);
            btnSubmitAppView.setText("Uploading & Submitting...");
            ApiClient.submitRenewalApplication(ApiClient.currentUser.id, owner, contact, make, model, yr, col, plate, passType, ownership, stagedRenewalFiles,
                    (ok, msg, uploadedNames) -> {
                        btnSubmitAppView.setEnabled(true);
                        btnSubmitAppView.setText("Submit Renewal Application");
                        toast(msg);
                        if (ok) {
                            isRenewalReapplyMode = false;
                            stagedRenewalFiles.clear(); sessionUploadedDocKeys.clear();
                            studentUploadedFiles.clear(); studentUploadedFiles.putAll(uploadedNames);
                            lastLoadedVehiclePhotoUrl = null;
                            modalApplicationOverlay.setVisibility(View.GONE);
                            triggerPortalRefresh(false);
                        }
                    });
            return;
        }

        btnSubmitAppView.setEnabled(false);
        btnSubmitAppView.setText("Submitting...");
        ApiClient.createApplication(ApiClient.currentUser.id, owner, contact, make, model, yr, col, plate, passType, ownership, (ok, msg) -> {
            btnSubmitAppView.setEnabled(true);
            btnSubmitAppView.setText("Submit Application");
            toast(msg);
            if (ok) { modalApplicationOverlay.setVisibility(View.GONE); triggerPortalRefresh(false); }
        });
    }

    private void withdrawStudentApplication() {
        RequestBody b = new FormBody.Builder().add("action", "delete_app").add("owner_id", ApiClient.currentUser.id).build();
        ApiClient.getHttpClient().newCall(new Request.Builder().url(ApiClient.BASE_URL).post(b).build()).enqueue(new Callback() {
            @Override public void onFailure(@NonNull Call call, @NonNull IOException e) {}
            @Override public void onResponse(@NonNull Call call, @NonNull Response response) {
                response.close();
                runOnUiThread(() -> {
                    replacedDocKeys.clear();
                    modalVehicleDetailsOverlay.setVisibility(View.GONE);
                    toast("Application withdrawn.");
                    triggerPortalRefresh(false);
                });
            }
        });
    }

    // =========================================================================
    // INBOX TAB & SUPPORT TAB (STUDENT)
    // =========================================================================
    private void setupInboxTabAndCompose() {
        userInboxList = findViewById(R.id.userInboxList);
        tvUnreadBadge = findViewById(R.id.tvUnreadBadge);
        etInboxSearch = findViewById(R.id.etInboxSearch);
        tabInboxReceived = findViewById(R.id.tabInboxReceived);
        tabInboxSent = findViewById(R.id.tabInboxSent);
        composeModalOverlay = findViewById(R.id.composeModalOverlay);

        inboxListScreen = findViewById(R.id.inboxListScreen);
        inboxDetailScreen = findViewById(R.id.inboxDetailScreen);
        tvDetailShortDate = findViewById(R.id.tvDetailShortDate);
        tvDetailBadge = findViewById(R.id.tvDetailBadge);
        tvDetailFullDate = findViewById(R.id.tvDetailFullDate);
        tvDetailSubject = findViewById(R.id.tvDetailSubject);
        tvDetailFrom = findViewById(R.id.tvDetailFrom);
        tvDetailTo = findViewById(R.id.tvDetailTo);
        scrollDetailBody = findViewById(R.id.scrollDetailBody);
        tvDetailBody = findViewById(R.id.tvDetailBody);
        layoutDetailAttachment = findViewById(R.id.layoutDetailAttachment);
        tvDetailAttachmentName = findViewById(R.id.tvDetailAttachmentName);
        btnReplyToAdmin = findViewById(R.id.btnReplyToAdmin);

        View btnBack = findViewById(R.id.btnBackToMessages);
        if (btnBack != null) btnBack.setOnClickListener(v -> closeMessageDetail());
        if (btnReplyToAdmin != null) btnReplyToAdmin.setOnClickListener(v -> openComposeOverlay(
                activeDetailMessage == null ? "" : (activeDetailMessage.subject.startsWith("Re:") ? activeDetailMessage.subject : "Re: " + activeDetailMessage.subject)));

        if (tabInboxReceived != null) tabInboxReceived.setOnClickListener(v -> { isSentTabSelected = false; updateInboxSubTabUI(); renderFilteredInboxCards(); });
        if (tabInboxSent != null) tabInboxSent.setOnClickListener(v -> { isSentTabSelected = true; updateInboxSubTabUI(); renderFilteredInboxCards(); });
        watchText(etInboxSearch, q -> { inboxSearchQuery = q; renderFilteredInboxCards(); });

        View fab = findViewById(R.id.fabComposeMessage);
        if (fab != null) fab.setOnClickListener(v -> openComposeOverlay(""));
        updateInboxSubTabUI();
    }

    private void updateInboxSubTabUI() {
        if (tabInboxReceived == null || tabInboxSent == null) return;
        if (tvUnreadBadge != null) tvUnreadBadge.setText("0 UNREAD");
        tabInboxReceived.setText(isSentTabSelected ? "Received" : "Received (" + cachedReceivedMsgs.size() + ")");
        tabInboxSent.setText("Sent");
        styleSubTab(tabInboxReceived, !isSentTabSelected, "#A61C1C", 9f, "#1F2937");
        styleSubTab(tabInboxSent, isSentTabSelected, "#A61C1C", 9f, "#1F2937");
    }

    private void styleSubTab(TextView tab, boolean active, String activeHex, float radiusDp, String inactiveHex) {
        if (tab == null) return;
        if (active) { tab.setBackground(bg(activeHex, radiusDp, 0, null)); tab.setTextColor(Color.WHITE); }
        else { tab.setBackgroundColor(Color.TRANSPARENT); tab.setTextColor(c(inactiveHex)); }
    }

    private void loadUserMessages() {
        if (ApiClient.currentUser == null) return;
        ApiClient.getMessages(ApiClient.currentUser.id, ApiClient.currentUser.email, ApiClient.currentUser.name, (ok, rec, sent, err) -> {
            if (ok) {
                cachedReceivedMsgs.clear(); cachedReceivedMsgs.addAll(rec);
                cachedSentMsgs.clear(); cachedSentMsgs.addAll(sent);
                updateInboxSubTabUI(); renderFilteredInboxCards();
            } else if (err != null && tabInboxContainer.getVisibility() == View.VISIBLE) toast("Inbox sync error: " + err);
        });
    }

    private void addEmptyState(LinearLayout container, String text, String colorHex) {
        TextView empty = makeTv(text, 13f, colorHex, false);
        empty.setGravity(Gravity.CENTER);
        empty.setPadding(24, 64, 24, 24);
        container.addView(empty);
    }

    private void addMessageCard(LinearLayout container, ApiClient.MessageItem m, String tagLabel, String cardBgHex, int maxLines, Runnable onClick) {
        CardView card = new CardView(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 0, dp(11));
        card.setLayoutParams(lp);
        card.setRadius(dp(15));
        card.setCardElevation(0f);
        card.setCardBackgroundColor(c(cardBgHex));
        card.setClickable(true); card.setFocusable(true);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int p = dp(15); box.setPadding(p, p, p, p);

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);

        TextView tag = makeTv(tagLabel, 10f, "#B71C1C", true);
        tag.setBackground(bg("#EED2D2", 8f, 0, null));
        tag.setMaxWidth(dp(215));
        tag.setPadding(dp(11), dp(4), dp(11), dp(4));

        View spacer = new View(this);
        spacer.setLayoutParams(new LinearLayout.LayoutParams(0, 1, 1f));

        top.addView(tag); top.addView(spacer); top.addView(makeTv(m.shortDate, 11f, "#6B7280", false));

        TextView subj = makeTv(m.subject, 14.5f, "#111827", true);
        subj.setPadding(0, dp(10), 0, dp(4));

        TextView body = makeTv(m.cleanBody, 12f, "#374151", false);
        body.setMaxLines(maxLines);
        body.setEllipsize(TextUtils.TruncateAt.END);

        box.addView(top); box.addView(subj); box.addView(body);
        card.addView(box);
        card.setOnClickListener(v -> onClick.run());
        container.addView(card);
    }

    private void renderFilteredInboxCards() {
        if (userInboxList == null) return;
        userInboxList.removeAllViews();
        List<ApiClient.MessageItem> filtered = new ArrayList<>();
        for (ApiClient.MessageItem m : (isSentTabSelected ? cachedSentMsgs : cachedReceivedMsgs)) {
            if (inboxSearchQuery.isEmpty() || (m.subject + " " + m.cleanBody + " " + m.sender + " " + m.recipient + " " + m.category)
                    .toLowerCase(Locale.US).contains(inboxSearchQuery)) filtered.add(m);
        }
        if (filtered.isEmpty()) {
            addEmptyState(userInboxList, isSentTabSelected ? "No sent messages found." : "No announcements or security notices found.", "#6B7280");
            return;
        }
        for (ApiClient.MessageItem m : filtered) {
            String tag = isSentTabSelected ? "TO: GATE 1 SECURITY DESK (ADMIN)"
                    : (m.category != null && !m.category.isEmpty() ? m.category.toUpperCase(Locale.US) : "ANNOUNCEMENT");
            addMessageCard(userInboxList, m, tag, "#F6F6F8", 3, () -> openMessageDetail(m));
        }
    }

    private void openMessageDetail(ApiClient.MessageItem msg) {
        if (inboxDetailScreen == null || inboxListScreen == null) return;
        activeDetailMessage = msg;
        inboxListScreen.setVisibility(View.GONE);
        inboxDetailScreen.setVisibility(View.VISIBLE);
        txtTopScreenTitle.setText("UE Campus Pass");
        if (cardHeaderWelcomeBanner != null) cardHeaderWelcomeBanner.setVisibility(View.GONE);

        tvDetailShortDate.setText(msg.shortDate);
        tvDetailFullDate.setText("• " + msg.fullTimestamp);
        tvDetailSubject.setText(msg.subject);
        tvDetailBody.setText(msg.cleanBody);

        boolean isAnn = "ANNOUNCEMENT".equalsIgnoreCase(msg.category) || "ALL".equalsIgnoreCase(msg.recipient);
        tvDetailBadge.setText(msg.isSent ? "SENT INQUIRY" : (isAnn ? "ANNOUNCEMENT" : "SECURITY NOTICE"));
        tvDetailFrom.setText(HtmlCompat.fromHtml("From: <b>" + msg.sender + "</b>", HtmlCompat.FROM_HTML_MODE_LEGACY));
        String toLbl = msg.isSent ? "Gate 1 Security Desk (Admin)" : (isAnn ? "All UE Students & Faculty"
                : (ApiClient.currentUser != null ? ApiClient.currentUser.name : msg.recipient));
        tvDetailTo.setText(HtmlCompat.fromHtml("To: <b>" + toLbl + "</b>", HtmlCompat.FROM_HTML_MODE_LEGACY));
        btnReplyToAdmin.setText(msg.isSent ? "Send Follow-Up ->" : "Reply to Admin ->");

        if (msg.attachmentFileName != null && !msg.attachmentFileName.isEmpty()) {
            layoutDetailAttachment.setVisibility(View.VISIBLE);
            tvDetailAttachmentName.setText(msg.attachmentFileName);
            layoutDetailAttachment.setOnClickListener(v -> startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(ApiClient.getAttachmentUrl(msg.attachmentFileName)))));
        } else layoutDetailAttachment.setVisibility(View.GONE);

        scrollDetailBody.post(() -> {
            int minPx = dp(135), maxPx = dp(290), w = scrollDetailBody.getWidth() - scrollDetailBody.getPaddingEnd();
            tvDetailBody.measure(View.MeasureSpec.makeMeasureSpec(w > 0 ? w : dp(280), View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            int h = tvDetailBody.getMeasuredHeight() + scrollDetailBody.getPaddingTop() + scrollDetailBody.getPaddingBottom();
            ViewGroup.LayoutParams lp = scrollDetailBody.getLayoutParams();
            lp.height = h > maxPx ? maxPx : Math.max(h, minPx);
            scrollDetailBody.setVerticalScrollBarEnabled(h > maxPx);
            scrollDetailBody.setLayoutParams(lp);
            scrollDetailBody.scrollTo(0, 0);
        });
    }

    private void closeMessageDetail() {
        activeDetailMessage = null;
        if (inboxDetailScreen != null) inboxDetailScreen.setVisibility(View.GONE);
        if (inboxListScreen != null) inboxListScreen.setVisibility(View.VISIBLE);
        if (cardHeaderWelcomeBanner != null) cardHeaderWelcomeBanner.setVisibility(View.VISIBLE);
    }

    private void openComposeOverlay(String prefillSubject) {
        if (composeModalOverlay == null) composeModalOverlay = findViewById(R.id.composeModalOverlay);
        if (composeModalOverlay == null) return;
        pendingComposeAttachmentUri = null;

        TextView tvFrom = findViewById(R.id.tvComposeFromInfo), tvAtt = findViewById(R.id.tvComposeAttachmentName);
        EditText etSubj = findViewById(R.id.etComposeSubject), etMsg = findViewById(R.id.etComposeMessage);
        Button btnSend = findViewById(R.id.btnComposeSend);
        activeComposeAttachmentLabel = tvAtt;

        etSubj.setText(prefillSubject != null ? prefillSubject : ""); etSubj.setError(null);
        etMsg.setText(""); etMsg.setError(null);
        tvAtt.setText("no File Attached"); tvAtt.setTextColor(c("#6B7280"));
        tvFrom.setText("From: " + (ApiClient.currentUser != null ? ApiClient.currentUser.name : "Student") + "\nStudent Portal");

        findViewById(R.id.btnCloseCompose).setOnClickListener(v -> { activeComposeAttachmentLabel = null; composeModalOverlay.setVisibility(View.GONE); });
        findViewById(R.id.btnComposeAttachFile).setOnClickListener(v -> composeFilePickerLauncher.launch("*/*"));

        btnSend.setOnClickListener(v -> {
            String subj = etSubj.getText().toString().trim(), body = etMsg.getText().toString().trim();
            if (subj.isEmpty()) { etSubj.setError("Please enter a subject"); etSubj.requestFocus(); return; }
            if (body.isEmpty()) { etMsg.setError("Please enter your message"); etMsg.requestFocus(); return; }
            btnSend.setEnabled(false); btnSend.setText("Sending...");
            String sender = ApiClient.currentUser != null ? ApiClient.currentUser.name + " (" + ApiClient.currentUser.id + ")" : "Student";
            ApiClient.sendMessage(this, sender, subj, body, pendingComposeAttachmentUri, (ok, msg) -> {
                btnSend.setEnabled(true); btnSend.setText("Send");
                if (ok) {
                    activeComposeAttachmentLabel = null;
                    composeModalOverlay.setVisibility(View.GONE);
                    closeMessageDetail();
                    toast("Message sent to Gate 1 Security Desk!");
                    isSentTabSelected = true; updateInboxSubTabUI(); loadUserMessages();
                } else Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
            });
        });
        composeModalOverlay.setVisibility(View.VISIBLE);
    }

    private String resolveFileName(Uri uri) {
        if ("content".equals(uri.getScheme())) {
            try (Cursor c = getContentResolver().query(uri, null, null, null, null)) {
                if (c != null && c.moveToFirst()) {
                    int idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    if (idx >= 0) return c.getString(idx);
                }
            } catch (Exception ignored) {}
        }
        String p = uri.getPath();
        return p != null ? p.substring(p.lastIndexOf('/') + 1) : "attached_file";
    }

    private void setupPickupAndSupportTab() {
        int[][] faqs = {{R.id.faqHeader1, R.id.faqToggle1, R.id.faqAnswer1}, {R.id.faqHeader2, R.id.faqToggle2, R.id.faqAnswer2},
                {R.id.faqHeader3, R.id.faqToggle3, R.id.faqAnswer3}, {R.id.faqHeader4, R.id.faqToggle4, R.id.faqAnswer4}};
        for (int[] f : faqs) {
            View h = findViewById(f[0]); TextView tg = findViewById(f[1]), ans = findViewById(f[2]);
            if (h != null && tg != null && ans != null) h.setOnClickListener(v -> {
                boolean exp = ans.getVisibility() == View.VISIBLE;
                ans.setVisibility(exp ? View.GONE : View.VISIBLE);
                tg.setText(exp ? "+" : "−");
            });
        }
        EditText etInq = findViewById(R.id.etSupportInquiry);
        View btnSend = findViewById(R.id.btnSendSupportInquiry);
        if (btnSend != null && etInq != null) btnSend.setOnClickListener(v -> {
            String msg = etInq.getText().toString().trim();
            if (msg.isEmpty()) { etInq.setError("Please enter your inquiry"); etInq.requestFocus(); return; }
            etInq.setText(""); etInq.setError(null);
            openComposeOverlay("Support Inquiry");
            EditText etComp = findViewById(R.id.etComposeMessage);
            if (etComp != null) etComp.setText(msg);
        });
        View hotline = findViewById(R.id.tvSecurityHotline);
        if (hotline != null) hotline.setOnClickListener(v -> {
            try { startActivity(new Intent(Intent.ACTION_DIAL, Uri.parse("tel:0287354571"))); } catch (Exception ignored) {}
        });
    }

    // =========================================================================
    // 2. SECURITY ADMIN PORTAL
    // =========================================================================
    private void setupAdminPortal() {
        TextView logged = findViewById(R.id.txtAdminLoggedAs);
        if (logged != null && ApiClient.currentUser != null) logged.setText("Logged in as: " + ApiClient.currentUser.name);

        adminTabDashboardContainer = findViewById(R.id.adminTabDashboardContainer);
        adminTabRecordsContainer = findViewById(R.id.adminTabRecordsContainer);
        adminTabInboxContainer = findViewById(R.id.adminTabInboxContainer);
        navAdminDashboard = findViewById(R.id.navAdminDashboard);
        navAdminRecords = findViewById(R.id.navAdminRecords);
        navAdminInbox = findViewById(R.id.navAdminInbox);
        tvNavAdminRecordsLabel = findViewById(R.id.tvNavAdminRecordsLabel);
        tvNavAdminInboxLabel = findViewById(R.id.tvNavAdminInboxLabel);

        txtKpiTotal = findViewById(R.id.txtKpiTotal); txtKpiPending = findViewById(R.id.txtKpiPending);
        txtKpiApproved = findViewById(R.id.txtKpiApproved); txtKpiRejected = findViewById(R.id.txtKpiRejected);
        txtQuotaPercentBadge = findViewById(R.id.txtQuotaPercentBadge);
        txtQuotaAvailableCount = findViewById(R.id.txtQuotaAvailableCount);
        txtQuotaIssuedCount = findViewById(R.id.txtQuotaIssuedCount);
        progressStickerQuota = findViewById(R.id.progressStickerQuota);
        txtPendingAwaitingCount = findViewById(R.id.txtPendingAwaitingCount);
        txtEmptyPendingQueue = findViewById(R.id.txtEmptyPendingQueue);
        scrollPendingQueue = findViewById(R.id.scrollPendingQueue);
        adminPendingQueueList = findViewById(R.id.adminPendingQueueList);
        txtRecentApprovedBadge = findViewById(R.id.txtRecentApprovedBadge);
        adminRecentApprovalsList = findViewById(R.id.adminRecentApprovalsList);
        spinnerAdminFilter = findViewById(R.id.spinnerAdminFilter);
        adminRecordsContainer = findViewById(R.id.adminRecordsContainer);
        adminInboxList = findViewById(R.id.adminInboxList);

        navAdminDashboard.setOnClickListener(v -> switchAdminTab(0));
        navAdminRecords.setOnClickListener(v -> switchAdminTab(1));
        navAdminInbox.setOnClickListener(v -> switchAdminTab(2));

        View btnNotice = findViewById(R.id.btnAdminSendNotice);
        if (btnNotice != null) btnNotice.setOnClickListener(v -> showAdminSendNoticeDialog(null, ""));

        String[] filters = {"ALL", "PENDING", "APPROVED", "REJECTED"};
        spinnerAdminFilter.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, filters));
        onSpinSelect(spinnerAdminFilter, pos -> {
            selectedAdminFilterStatus = filters[pos];
            updateAdminFilterPillsUI();
            renderAdminCards(null);
        });
        watchText(findViewById(R.id.etAdminRecordsSearch), q -> { adminRecordsSearchQuery = q; renderAdminCards(expandedAdminAppId); });

        int[] pillIds = {R.id.btnFilterAll, R.id.btnFilterPending, R.id.btnFilterApproved, R.id.btnFilterRejected};
        for (int i = 0; i < filters.length; i++) {
            final String f = filters[i];
            View p = findViewById(pillIds[i]);
            if (p != null) p.setOnClickListener(v -> { selectedAdminFilterStatus = f; updateAdminFilterPillsUI(); renderAdminCards(expandedAdminAppId); });
        }
        updateAdminFilterPillsUI();

        tvAdminInboxUnreadBadge = findViewById(R.id.tvAdminInboxUnreadBadge);
        etAdminInboxSearch = findViewById(R.id.etAdminInboxSearch);
        tabAdminInboxReceived = findViewById(R.id.tabAdminInboxReceived);
        tabAdminInboxSent = findViewById(R.id.tabAdminInboxSent);
        adminInboxListViewContainer = findViewById(R.id.adminInboxListViewContainer);
        adminMessageDetailContainer = findViewById(R.id.adminMessageDetailContainer);
        tvAdminDetailShortDate = findViewById(R.id.tvAdminDetailShortDate);
        tvAdminDetailTag = findViewById(R.id.tvAdminDetailTag);
        tvAdminDetailTimestamp = findViewById(R.id.tvAdminDetailTimestamp);
        tvAdminDetailSubject = findViewById(R.id.tvAdminDetailSubject);
        tvAdminDetailFrom = findViewById(R.id.tvAdminDetailFrom);
        tvAdminDetailTo = findViewById(R.id.tvAdminDetailTo);
        tvAdminDetailBody = findViewById(R.id.tvAdminDetailBody);
        btnAdminReplyUser = findViewById(R.id.btnAdminReplyUser);

        if (tabAdminInboxReceived != null) tabAdminInboxReceived.setOnClickListener(v -> { isAdminSentTab = false; updateAdminInboxSubTabUI(); loadAdminInbox(); });
        if (tabAdminInboxSent != null) tabAdminInboxSent.setOnClickListener(v -> { isAdminSentTab = true; updateAdminInboxSubTabUI(); loadAdminInbox(); });
        watchText(etAdminInboxSearch, q -> { adminInboxSearchQuery = q; applyAdminInboxSnapshot(new ArrayList<>(cachedAdminMsgs)); });

        View fab = findViewById(R.id.fabAdminComposeMessage);
        if (fab != null) fab.setOnClickListener(v -> showAdminSendNoticeDialog(null, ""));
        View back = findViewById(R.id.btnAdminBackToMessages);
        if (back != null) back.setOnClickListener(v -> {
            if (adminMessageDetailContainer != null) adminMessageDetailContainer.setVisibility(View.GONE);
            if (adminInboxListViewContainer != null) adminInboxListViewContainer.setVisibility(View.VISIBLE);
        });

        switchAdminTab(0);
        triggerPortalRefresh(false);
    }

    private void switchAdminTab(int idx) {
        adminTabDashboardContainer.setVisibility(idx == 0 ? View.VISIBLE : View.GONE);
        adminTabRecordsContainer.setVisibility(idx == 1 ? View.VISIBLE : View.GONE);
        adminTabInboxContainer.setVisibility(idx == 2 ? View.VISIBLE : View.GONE);
        updateNavTabVisual(navAdminDashboard, idx == 0);
        updateNavTabVisual(navAdminRecords, idx == 1);
        updateNavTabVisual(navAdminInbox, idx == 2);
        if (idx == 1) renderAdminCards(null);
        if (idx == 2) loadAdminInbox();
    }

    private void loadAdminApplications() {
        ApiClient.getAllApplications((apps, err) -> { if (err == null) applyAdminApplicationsSnapshot(apps); });
    }

    private void applyAdminApplicationsSnapshot(List<ApiClient.VehicleApplication> apps) {
        allAppsCache = apps;
        List<ApiClient.VehicleApplication> pending = new ArrayList<>(), approved = new ArrayList<>();
        int activeApproved = 0, rejected = 0;
        for (ApiClient.VehicleApplication a : apps) {
            if ("APPROVED".equalsIgnoreCase(a.status)) { approved.add(a); if (a.isActiveApproved()) activeApproved++; }
            else if ("REJECTED".equalsIgnoreCase(a.status)) rejected++;
            else pending.add(a);
        }

        txtKpiTotal.setText(String.valueOf(apps.size()));
        txtKpiPending.setText(String.valueOf(pending.size()));
        txtKpiApproved.setText(String.valueOf(approved.size()));
        txtKpiRejected.setText(String.valueOf(rejected));
        if (tvNavAdminRecordsLabel != null) tvNavAdminRecordsLabel.setText("Records (" + apps.size() + ")");

        int avail = Math.max(0, 500 - activeApproved);
        txtQuotaPercentBadge.setText(String.format(Locale.US, "%.1f%% Left", avail * 100.0 / 500));
        txtQuotaAvailableCount.setText(String.valueOf(avail));
        txtQuotaIssuedCount.setText(activeApproved + " Active");
        progressStickerQuota.setMax(500);
        progressStickerQuota.setProgress(avail);

        renderPendingQueueSection(pending);
        renderRecentApprovalsLog(approved);
        if (adminTabRecordsContainer.getVisibility() == View.VISIBLE) renderAdminCards(expandedAdminAppId);
    }

    private void renderPendingQueueSection(List<ApiClient.VehicleApplication> pending) {
        txtPendingAwaitingCount.setText(pending.size() + " awaiting verification");
        adminPendingQueueList.removeAllViews();
        if (pending.isEmpty()) {
            txtEmptyPendingQueue.setVisibility(View.VISIBLE);
            scrollPendingQueue.setVisibility(View.GONE);
            return;
        }
        txtEmptyPendingQueue.setVisibility(View.GONE);
        scrollPendingQueue.setVisibility(View.VISIBLE);
        ViewGroup.LayoutParams slp = scrollPendingQueue.getLayoutParams();
        slp.height = pending.size() > 2 ? dp(182) : ViewGroup.LayoutParams.WRAP_CONTENT;
        scrollPendingQueue.setLayoutParams(slp);

        for (ApiClient.VehicleApplication app : pending) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setBackgroundResource(R.drawable.bg_compose_input);
            int p = dp(12); row.setPadding(p, p, p, p);
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rlp.setMargins(0, 0, 0, dp(8));
            row.setLayoutParams(rlp);

            LinearLayout col = new LinearLayout(this);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            col.addView(makeTv(app.ownerName.toUpperCase(Locale.US), 12.5f, "#111827", true));
            col.addView(makeTv((app.make + " " + app.model).trim() + " • " + app.plateNumber + " (" + app.passType + ")", 11f, "#4B5563", false));

            TextView btn = makeTv("Review", 11f, "#B71C1C", true);
            btn.setBackgroundResource(R.drawable.bg_outline_view_records);
            btn.setPadding(dp(12), dp(6), dp(12), dp(6));
            btn.setOnClickListener(v -> { switchAdminTab(1); spinnerAdminFilter.setSelection(1); renderAdminCards(app.id); inspectApplicantDocuments(app); });

            row.addView(col); row.addView(btn);
            adminPendingQueueList.addView(row);
        }
    }

    private void renderRecentApprovalsLog(List<ApiClient.VehicleApplication> approved) {
        txtRecentApprovedBadge.setText(approved.size() + " Approved");
        adminRecentApprovalsList.removeAllViews();
        if (approved.isEmpty()) {
            TextView empty = makeTv("No approved vehicle stickers yet.", 12f, "#6B7280", false);
            empty.setPadding(0, 16, 0, 8);
            adminRecentApprovalsList.addView(empty);
            return;
        }
        for (int i = 0; i < approved.size(); i++) {
            ApiClient.VehicleApplication app = approved.get(i);
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(12), 0, dp(12));

            LinearLayout col = new LinearLayout(this);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            col.setPadding(0, 0, dp(8), 0);

            TextView mp = makeTv((app.make + " " + app.model).trim() + " • " + app.plateNumber, 11.5f, "#374151", false);
            mp.setPadding(0, dp(2), 0, 0);
            String val = "Approved: " + app.getFormattedApprovalDate() + (app.isExpired()
                    ? " • EXPIRED (" + app.getFormattedExpirationDate() + ")" : " • Expires: " + app.getFormattedExpirationDate());
            TextView expTv = makeTv(val, 10.5f, app.isExpired() ? "#DC2626" : "#6B7280", false);
            expTv.setPadding(0, dp(1), 0, 0);
            col.addView(makeTv(app.ownerName.toUpperCase(Locale.US), 13f, "#111827", true));
            col.addView(mp); col.addView(expTv);

            TextView btn = makeTv("View in Records", 11f, "#1F2937", true);
            btn.setBackgroundResource(R.drawable.bg_outline_view_records);
            btn.setPadding(dp(12), dp(7), dp(12), dp(7));
            btn.setOnClickListener(v -> { switchAdminTab(1); spinnerAdminFilter.setSelection(0); renderAdminCards(app.id); inspectApplicantDocuments(app); });

            row.addView(col); row.addView(btn);
            adminRecentApprovalsList.addView(row);
            if (i < approved.size() - 1) {
                View div = new View(this);
                div.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));
                div.setBackgroundColor(c("#E5E7EB"));
                adminRecentApprovalsList.addView(div);
            }
        }
    }

    private void updateAdminFilterPillsUI() {
        int[] ids = {R.id.btnFilterAll, R.id.btnFilterPending, R.id.btnFilterApproved, R.id.btnFilterRejected};
        String[] vals = {"ALL", "PENDING", "APPROVED", "REJECTED"};
        for (int i = 0; i < ids.length; i++) {
            TextView p = findViewById(ids[i]);
            if (p == null) continue;
            boolean sel = vals[i].equalsIgnoreCase(selectedAdminFilterStatus);
            p.setBackground(sel ? bg("#B71C1C", 16f, 0, null) : bg("#FFFFFF", 16f, 1, "#D1D5DB"));
            p.setTextColor(sel ? Color.WHITE : c("#374151"));
        }
    }

    private void renderAdminCards(Integer highlightId) {
        if (adminRecordsContainer == null) return;
        adminRecordsContainer.removeAllViews();
        if (highlightId != null) expandedAdminAppId = highlightId;

        List<ApiClient.VehicleApplication> filtered = new ArrayList<>();
        for (ApiClient.VehicleApplication app : allAppsCache) {
            if (!"ALL".equalsIgnoreCase(selectedAdminFilterStatus) && !selectedAdminFilterStatus.equalsIgnoreCase(app.status)) continue;
            if (!adminRecordsSearchQuery.isEmpty() && !(app.ownerName + " " + app.plateNumber + " " + app.make + " " + app.model + " " + app.ownerId)
                    .toLowerCase(Locale.US).contains(adminRecordsSearchQuery)) continue;
            filtered.add(app);
        }

        TextView tvH = findViewById(R.id.tvRecordsSectionHeader);
        if (tvH != null) tvH.setText(("ALL".equalsIgnoreCase(selectedAdminFilterStatus) ? "ALL APPLICANTS" : selectedAdminFilterStatus + " APPLICANTS") + " (" + filtered.size() + ")");
        if (filtered.isEmpty()) { addEmptyState(adminRecordsContainer, "No applicant records found matching search or filter.", "#6B7280"); return; }

        for (ApiClient.VehicleApplication app : filtered) {
            boolean expanded = expandedAdminAppId != null && expandedAdminAppId == app.id;
            boolean nonOwned = isNonOwned(app);

            CardView card = new CardView(this);
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            clp.setMargins(0, 0, 0, dp(14));
            card.setLayoutParams(clp);
            card.setRadius(dp(20)); card.setCardElevation(1f); card.setCardBackgroundColor(Color.WHITE);

            LinearLayout box = new LinearLayout(this);
            box.setOrientation(LinearLayout.VERTICAL);
            int p = dp(16); box.setPadding(p, p, p, p);

            LinearLayout row1 = new LinearLayout(this);
            row1.setOrientation(LinearLayout.HORIZONTAL); row1.setGravity(Gravity.CENTER_VERTICAL);
            TextView tvOwner = makeTv(app.ownerName, 16f, "#111827", true);
            tvOwner.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            TextView tvDocs = makeTv(expanded ? "View Docs" : (nonOwned ? "5 Docs" : "4 Docs"), 11f, "#4B5563", true);
            tvDocs.setBackgroundResource(R.drawable.bg_pill_requirement);
            tvDocs.setPadding(dp(12), dp(5), dp(12), dp(5));
            tvDocs.setOnClickListener(v -> { if (expanded) { expandedAdminAppId = null; renderAdminCards(null); } else expandApplicantCard(app); });
            row1.addView(tvOwner); row1.addView(tvDocs);

            TextView tvVeh = makeTv("", 12.5f, "#4B5563", false);
            tvVeh.setText(HtmlCompat.fromHtml((app.make + " " + app.model).trim() + " - <b>" + app.plateNumber + "</b>", HtmlCompat.FROM_HTML_MODE_LEGACY));
            tvVeh.setPadding(0, dp(4), 0, 0);

            LinearLayout row3 = new LinearLayout(this);
            row3.setOrientation(LinearLayout.HORIZONTAL); row3.setGravity(Gravity.CENTER_VERTICAL);
            row3.setPadding(0, dp(10), 0, 0);
            TextView tvTag = makeTv(nonOwned ? "- Another Person's Vehicle" : "- My Own Vehicle", 11f, nonOwned ? "#D97706" : "#4B5563", true);
            tvTag.setBackground(bg(nonOwned ? "#FEF3C7" : "#F3F4F6", 14f, 0, null));
            tvTag.setPadding(dp(10), dp(4), dp(10), dp(4));

            View sp = new View(this); sp.setLayoutParams(new LinearLayout.LayoutParams(0, 1, 1f));
            row3.addView(tvTag); row3.addView(sp);
            if (!expanded) {
                TextView btnView = makeTv("View Application", 11.5f, "#B71C1C", true);
                btnView.setBackground(bg("#FEE2E2", 10f, 0, null));
                btnView.setPadding(dp(14), dp(7), dp(14), dp(7));
                btnView.setOnClickListener(v -> expandApplicantCard(app));
                row3.addView(btnView);
            }

            box.addView(row1); box.addView(tvVeh); box.addView(row3);
            if (expanded) renderExpandedDocumentView(box, app, nonOwned);
            card.addView(box);
            adminRecordsContainer.addView(card);
        }
    }

    private void expandApplicantCard(ApiClient.VehicleApplication app) {
        expandedAdminAppId = app.id;
        if (selectedAdminDocKey == null || selectedAdminDocKey.isEmpty()) selectedAdminDocKey = "vehicle_photo";
        ApiClient.BACKGROUND_EXECUTOR.execute(() -> {
            try {
                Map<String, String> files = ApiClient.fetchUserDocsSync(app.ownerId);
                runOnUiThread(() -> { if (!isFinishing() && !isDestroyed()) { adminUserDocsMap.put(app.ownerId, files); renderAdminCards(app.id); } });
            } catch (Exception ignored) { runOnUiThread(() -> renderAdminCards(app.id)); }
        });
    }

    private void renderExpandedDocumentView(LinearLayout parentBox, ApiClient.VehicleApplication app, boolean isNonOwned) {
        HorizontalScrollView hsv = new HorizontalScrollView(this);
        hsv.setHorizontalScrollBarEnabled(false); hsv.setOverScrollMode(View.OVER_SCROLL_NEVER);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hlp.setMargins(0, dp(14), 0, 0); hsv.setLayoutParams(hlp);

        LinearLayout chipsRow = new LinearLayout(this);
        chipsRow.setOrientation(LinearLayout.HORIZONTAL); chipsRow.setGravity(Gravity.CENTER_VERTICAL);
        String[] keys = isNonOwned ? new String[]{"license", "reg_form", "vehicle_photo", "cr_or", "owner_id"} : new String[]{"license", "reg_form", "vehicle_photo", "cr_or"};
        String[] titles = isNonOwned ? new String[]{"Driver's License", "UE Registration Form", "Vehicle Photo", "CR / OR Document", "Owner ID & Letter"}
                : new String[]{"Driver's License", "UE Registration Form", "Vehicle Photo", "CR / OR Document"};

        for (int i = 0; i < keys.length; i++) {
            final String k = keys[i];
            boolean sel = k.equalsIgnoreCase(selectedAdminDocKey);
            TextView chip = makeTv(titles[i], 11f, sel ? "#FFFFFF" : "#4B5563", true);
            chip.setBackground(bg(sel ? "#B71C1C" : "#EFEFF4", 14f, 0, null));
            chip.setPadding(dp(12), dp(6), dp(12), dp(6));
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            clp.setMargins(0, 0, dp(6), 0); chip.setLayoutParams(clp);
            chip.setOnClickListener(v -> { selectedAdminDocKey = k; renderAdminCards(app.id); });
            chipsRow.addView(chip);
        }
        hsv.addView(chipsRow); parentBox.addView(hsv);

        LinearLayout slider = new LinearLayout(this);
        slider.setOrientation(LinearLayout.HORIZONTAL); slider.setGravity(Gravity.CENTER_VERTICAL);
        slider.setPadding(dp(4), dp(6), dp(4), 0);
        TextView la = makeTv("◄", 10f, "#6B7280", false), ra = makeTv("►", 10f, "#6B7280", false);
        la.setOnClickListener(v -> hsv.smoothScrollBy(-150, 0)); ra.setOnClickListener(v -> hsv.smoothScrollBy(150, 0));
        View track = new View(this);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, dp(3), 1f);
        tlp.setMargins(dp(8), 0, dp(8), 0); track.setLayoutParams(tlp); track.setBackground(bg("#9CA3AF", 2f, 0, null));
        slider.addView(la); slider.addView(track); slider.addView(ra); parentBox.addView(slider);

        LinearLayout docBox = new LinearLayout(this);
        docBox.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.setMargins(0, dp(10), 0, 0); docBox.setLayoutParams(blp);
        docBox.setBackground(bg("#FFFFFF", 16f, 1, "#FCA5A5"));
        int bp = dp(14); docBox.setPadding(bp, bp, bp, bp);

        LinearLayout hdr = new LinearLayout(this);
        hdr.setOrientation(LinearLayout.HORIZONTAL); hdr.setGravity(Gravity.CENTER_VERTICAL);
        String curTitle = "Scanned credentials Sheet (" + getDocDisplayLabel(selectedAdminDocKey) + ")";
        TextView tvTitle = makeTv(curTitle, 11.5f, "#B71C1C", true), tvPop = makeTv("Tap to popup", 11.5f, "#B71C1C", true);
        tvTitle.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        hdr.addView(tvTitle); hdr.addView(tvPop); docBox.addView(hdr);

        ImageView iv = new ImageView(this);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(180));
        ilp.setMargins(0, dp(10), 0, dp(10)); iv.setLayoutParams(ilp); iv.setScaleType(ImageView.ScaleType.FIT_CENTER);

        Map<String, String> uFiles = adminUserDocsMap.get(app.ownerId);
        String fname = uFiles != null ? uFiles.get(selectedAdminDocKey) : null;
        if (fname != null && !fname.isEmpty()) {
            Glide.with(this).load(ApiClient.UPLOADS_URL + Uri.encode(fname)).fitCenter().into(iv);
            View.OnClickListener pop = v -> showDocPreviewDialog(fname, curTitle);
            tvPop.setOnClickListener(pop); iv.setOnClickListener(pop);
        } else {
            iv.setImageResource(android.R.drawable.ic_menu_gallery);
            tvPop.setOnClickListener(v -> toast("No image uploaded for this requirement."));
        }
        docBox.addView(iv);

        LinearLayout bot = new LinearLayout(this);
        bot.setOrientation(LinearLayout.HORIZONTAL); bot.setGravity(Gravity.CENTER_VERTICAL); bot.setPadding(0, dp(4), 0, 0);
        TextView meta = makeTv(fname != null ? "• Uploaded & Readable (" + fname + ")" : "• No file uploaded", 10f, "#6B7280", false);
        meta.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        bot.addView(meta);

        if ("PENDING".equalsIgnoreCase(app.status)) {
            LinearLayout acts = new LinearLayout(this);
            acts.setOrientation(LinearLayout.HORIZONTAL); acts.setGravity(Gravity.CENTER_VERTICAL);

            TextView btnOk = makeTv("✓", 14f, "#15803D", true);
            btnOk.setGravity(Gravity.CENTER); btnOk.setBackground(bg("#DCFCE7", 10f, 0, null));
            btnOk.setPadding(dp(10), dp(6), dp(10), dp(6));
            btnOk.setOnClickListener(v -> new AlertDialog.Builder(this)
                    .setTitle("Approve Sticker Application")
                    .setMessage("Are you sure you want to approve vehicle sticker application for " + app.ownerName + " (" + app.plateNumber + ")?")
                    .setPositiveButton("Approve", (d, w) -> ApiClient.updateStatus(app.id, "APPROVED", null, (ok, msg) -> {
                        toast(ok ? "Application for " + app.ownerName + " Approved!" : (msg != null ? msg : "Failed to approve"));
                        if (ok) loadAdminApplications();
                    })).setNegativeButton("Cancel", null).show());

            TextView btnNo = makeTv("✕", 14f, "#DC2626", true);
            btnNo.setGravity(Gravity.CENTER); btnNo.setBackground(bg("#FEE2E2", 10f, 0, null));
            btnNo.setPadding(dp(10), dp(6), dp(10), dp(6));
            LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            nlp.setMargins(dp(6), 0, 0, 0); btnNo.setLayoutParams(nlp);
            btnNo.setOnClickListener(v -> showRejectDialog(app));

            acts.addView(btnOk); acts.addView(btnNo); bot.addView(acts);
        }
        docBox.addView(bot); parentBox.addView(docBox);
    }

    private String getDocDisplayLabel(String key) {
        if ("owner_id".equalsIgnoreCase(key)) return "Owner ID & Letter";
        for (int i = 0; i < DOC_KEYS.length; i++) if (DOC_KEYS[i].equalsIgnoreCase(key)) return DOC_TITLES[i];
        return key;
    }

    private void updateAdminInboxSubTabUI() {
        styleSubTab(tabAdminInboxReceived, !isAdminSentTab, "#B71C1C", 16f, "#374151");
        styleSubTab(tabAdminInboxSent, isAdminSentTab, "#B71C1C", 16f, "#374151");
    }

    private String getAdminBadge(ApiClient.MessageItem m) {
        if (!m.isSent) return "STUDENT INQUIRY";
        String sl = m.subject != null ? m.subject.toLowerCase(Locale.US) : "", cu = m.category != null ? m.category.toUpperCase(Locale.US) : "";
        if ("ALL".equalsIgnoreCase(m.recipient) || cu.contains("ANNOUNCEMENT")) return "ANNOUNCEMENT";
        if (sl.contains("approved") || cu.contains("APPROVAL")) return "APPROVAL NOTICE";
        if (sl.contains("rejected") || sl.contains("flagged") || cu.contains("REJECTION")) return "REJECTION NOTICE";
        return "SECURITY NOTICE";
    }

    private void loadAdminInbox() {
        ApiClient.getAdminInboxMessages((ok, msgs, err) -> {
            if (!ok) {
                if (adminInboxList != null && cachedAdminMsgs.isEmpty()) {
                    adminInboxList.removeAllViews();
                    addEmptyState(adminInboxList, "Unable to sync inbox: " + (err != null ? err : "Check server connection"), "#DC2626");
                }
                return;
            }
            applyAdminInboxSnapshot(msgs);
        });
    }

    private void applyAdminInboxSnapshot(List<ApiClient.MessageItem> msgs) {
        cachedAdminMsgs.clear(); cachedAdminMsgs.addAll(msgs);
        int recCnt = 0, sentCnt = 0;
        for (ApiClient.MessageItem m : msgs) { if (m.isSent) sentCnt++; else recCnt++; }
        if (tvAdminInboxUnreadBadge != null) tvAdminInboxUnreadBadge.setText(recCnt + " UNREAD");
        if (tvNavAdminInboxLabel != null) tvNavAdminInboxLabel.setText("Inbox (" + recCnt + ")");
        if (tabAdminInboxReceived != null) tabAdminInboxReceived.setText("Received (" + recCnt + ")");
        if (tabAdminInboxSent != null) tabAdminInboxSent.setText("Sent (" + sentCnt + ")");

        if (adminInboxList == null) return;
        adminInboxList.removeAllViews();
        List<ApiClient.MessageItem> display = new ArrayList<>();
        for (ApiClient.MessageItem m : cachedAdminMsgs) {
            if ((isAdminSentTab == m.isSent) && (adminInboxSearchQuery.isEmpty() || (m.sender + " " + m.recipient + " " + m.subject + " " + m.cleanBody + " " + m.category)
                    .toLowerCase(Locale.US).contains(adminInboxSearchQuery))) display.add(m);
        }
        if (display.isEmpty()) {
            addEmptyState(adminInboxList, isAdminSentTab ? "No sent notices or messages." : "No received student inquiries.", "#6B7280");
            return;
        }
        for (ApiClient.MessageItem m : display) addMessageCard(adminInboxList, m, getAdminBadge(m), "#FFFFFF", 2, () -> showAdminMessageDetail(m));
    }

    private void showAdminMessageDetail(ApiClient.MessageItem m) {
        if (m == null) return;
        if (tvAdminDetailTag != null) tvAdminDetailTag.setText(getAdminBadge(m));
        if (tvAdminDetailShortDate != null) tvAdminDetailShortDate.setText(m.shortDate);
        if (tvAdminDetailTimestamp != null) tvAdminDetailTimestamp.setText("- " + m.fullTimestamp);
        if (tvAdminDetailSubject != null) tvAdminDetailSubject.setText(m.subject);
        if (tvAdminDetailFrom != null) tvAdminDetailFrom.setText("From: " + m.sender);
        if (tvAdminDetailTo != null) {
            String rec = !m.isSent ? "UE Security Desk" : "ALL".equalsIgnoreCase(m.recipient) ? "All UE Students & Faculty"
                    : (m.recipient == null || m.recipient.isEmpty() || "ADMIN".equalsIgnoreCase(m.recipient)) ? "Student Applicant" : m.recipient;
            tvAdminDetailTo.setText("To: " + rec);
        }
        if (tvAdminDetailBody != null) tvAdminDetailBody.setText(m.cleanBody);
        if (btnAdminReplyUser != null) btnAdminReplyUser.setOnClickListener(v -> {
            String tid = m.isSent ? m.recipient : m.sender;
            int op = tid.lastIndexOf('('), cp = tid.lastIndexOf(')');
            if (op >= 0 && cp > op) tid = tid.substring(op + 1, cp).trim();
            showAdminSendNoticeDialog(tid, "Re: " + m.subject);
        });
        if (adminInboxListViewContainer != null) adminInboxListViewContainer.setVisibility(View.GONE);
        if (adminMessageDetailContainer != null) adminMessageDetailContainer.setVisibility(View.VISIBLE);
    }

    private void showAdminSendNoticeDialog(String recipientUserId, String prefillSubject) {
        if (adminNoticeOverlay == null) adminNoticeOverlay = findViewById(R.id.adminNoticeOverlay);
        if (adminNoticeOverlay == null) return;

        EditText etRec = findViewById(R.id.etNoticeRecipient), etSubj = findViewById(R.id.etNoticeSubject), etMsg = findViewById(R.id.etNoticeMessage);
        TextView btnBroad = findViewById(R.id.btnAdminToggleBroadcast), btnClose = findViewById(R.id.btnCloseAdminNotice);
        View btnAttach = findViewById(R.id.btnAdminAttachFile), btnSub = findViewById(R.id.btnSubmitAdminNotice);
        tvAdminAttachmentName = findViewById(R.id.tvAdminAttachmentName);

        boolean[] broadcast = {recipientUserId == null || recipientUserId.isEmpty() || "ALL".equalsIgnoreCase(recipientUserId)};
        Runnable syncBroadcastUI = () -> {
            if (etRec != null) {
                etRec.setText(broadcast[0] ? "ALL (Broadcast to All Users)" : (recipientUserId != null ? recipientUserId : ""));
                etRec.setEnabled(!broadcast[0]);
            }
            if (btnBroad != null) btnBroad.setText(broadcast[0] ? "✓ Broadcast Active" : "📢 Broadcast to All");
        };
        syncBroadcastUI.run();
        if (btnBroad != null) btnBroad.setOnClickListener(v -> { broadcast[0] = !broadcast[0]; syncBroadcastUI.run(); });

        if (etSubj != null) { etSubj.setText(prefillSubject != null ? prefillSubject : ""); etSubj.setError(null); }
        if (etMsg != null) { etMsg.setText(""); etMsg.setError(null); }
        if (tvAdminAttachmentName != null) tvAdminAttachmentName.setText(attachedFileName != null ? attachedFileName : "no File Attached");
        if (btnAttach != null) btnAttach.setOnClickListener(v -> adminFilePickerLauncher.launch("*/*"));
        if (btnClose != null) btnClose.setOnClickListener(v -> adminNoticeOverlay.setVisibility(View.GONE));
        adminNoticeOverlay.setOnClickListener(v -> adminNoticeOverlay.setVisibility(View.GONE));
        if (adminNoticeOverlay.getChildAt(0) != null) adminNoticeOverlay.getChildAt(0).setOnClickListener(v -> {});

        if (btnSub != null) btnSub.setOnClickListener(v -> {
            String rec = etRec != null ? etRec.getText().toString().trim() : "";
            String subj = etSubj != null ? etSubj.getText().toString().trim() : "";
            String body = etMsg != null ? etMsg.getText().toString().trim() : "";
            if (!broadcast[0] && rec.isEmpty() && etRec != null) { etRec.setError("Recipient email/ID is required"); etRec.requestFocus(); return; }
            if (subj.isEmpty() && etSubj != null) { etSubj.setError("Subject is required"); etSubj.requestFocus(); return; }
            if (body.isEmpty() && etMsg != null) { etMsg.setError("Notice message is required"); etMsg.requestFocus(); return; }
            if (attachedFileName != null && !attachedFileName.isEmpty()) body += "\n[[ATTACHMENT:" + attachedFileName + "]]";

            btnSub.setEnabled(false);
            ApiClient.sendAdminMessage(broadcast[0] ? "ALL" : rec, subj, body, broadcast[0], (ok, msg) -> {
                btnSub.setEnabled(true);
                toast(ok ? "Notice Sent!" : msg);
                if (ok) { adminNoticeOverlay.setVisibility(View.GONE); attachedFileName = null; loadAdminInbox(); }
            });
        });
        adminNoticeOverlay.setVisibility(View.VISIBLE);
    }

    private void showRejectDialog(ApiClient.VehicleApplication app) {
        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        int p = dp(20); card.setPadding(p, p, p, p);
        card.setBackground(bg("#FFFFFF", 24f, 0, null));

        LinearLayout hdr = new LinearLayout(this);
        hdr.setOrientation(LinearLayout.HORIZONTAL); hdr.setGravity(Gravity.CENTER_VERTICAL);
        View icon = new View(this);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(dp(38), dp(38));
        ilp.setMargins(0, 0, dp(12), 0); icon.setLayoutParams(ilp); icon.setBackground(bg("#FCA5A5", 10f, 0, null));

        LinearLayout tCol = new LinearLayout(this);
        tCol.setOrientation(LinearLayout.VERTICAL);
        tCol.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        tCol.addView(makeTv("Reject/ flag application", 15.5f, "#111827", true));
        tCol.addView(makeTv("Report", 13.5f, "#111827", true));

        TextView btnX = makeTv("X", 15f, "#111827", true);
        btnX.setPadding(dp(8), dp(4), dp(8), dp(4));
        btnX.setOnClickListener(v -> dialog.dismiss());
        hdr.addView(icon); hdr.addView(tCol); hdr.addView(btnX); card.addView(hdr);

        ScrollView sv = new ScrollView(this);
        sv.setVerticalScrollBarEnabled(false); sv.setOverScrollMode(View.OVER_SCROLL_NEVER);
        LinearLayout.LayoutParams svLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        svLp.setMargins(0, dp(10), 0, 0); sv.setLayoutParams(svLp);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        TextView s1 = makeTv("Reason of Report:", 12.5f, "#111827", true);
        s1.setPadding(0, dp(8), 0, dp(6)); content.addView(s1);

        Set<String> selected = new LinkedHashSet<>();
        for (String opt : new String[]{"CR/OR Document", "UE Registration Form", "Letter of Approval of Owner", "Driver's License", "Vehicle Photo"}) {
            TextView chip = makeTv(opt, 11.5f, "#374151", false);
            chip.setGravity(Gravity.CENTER);
            chip.setPadding(dp(12), dp(9), dp(12), dp(9));
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            clp.setMargins(0, 0, 0, dp(7)); chip.setLayoutParams(clp);
            chip.setBackground(bg("#EFEFF4", 12f, 1, "#D1D5DB"));
            chip.setOnClickListener(v -> {
                boolean sel = !selected.contains(opt);
                if (sel) selected.add(opt); else selected.remove(opt);
                chip.setBackground(sel ? bg("#FEE2E2", 12f, 1, "#FCA5A5") : bg("#EFEFF4", 12f, 1, "#D1D5DB"));
                chip.setTextColor(c(sel ? "#B71C1C" : "#374151"));
                chip.setTypeface(null, sel ? Typeface.BOLD : Typeface.NORMAL);
            });
            content.addView(chip);
        }

        TextView s2 = makeTv("Details:", 12.5f, "#111827", true);
        s2.setPadding(0, dp(8), 0, dp(6)); content.addView(s2);

        Spinner spDet = new Spinner(this);
        ArrayAdapter<String> ad = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, new String[]{"Select details...", "Blurred", "Expired", "Wrong Document"});
        ad.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spDet.setAdapter(ad);
        spDet.setBackground(bg("#EFEFF4", 12f, 1, "#D1D5DB"));
        spDet.setPadding(dp(12), dp(8), dp(12), dp(8));
        content.addView(spDet);

        TextView s3 = makeTv("Other:", 12.5f, "#111827", true);
        s3.setPadding(0, dp(8), 0, dp(6)); content.addView(s3);

        EditText etOther = new EditText(this);
        etOther.setHint("Please Specify the details."); etOther.setHintTextColor(c("#9CA3AF"));
        etOther.setTextColor(c("#111827")); etOther.setTextSize(12f);
        etOther.setGravity(Gravity.TOP | Gravity.START); etOther.setMinLines(4); etOther.setMaxLines(5);
        int ep = dp(12); etOther.setPadding(ep, ep, ep, ep);
        etOther.setBackground(bg("#EFEFF4", 12f, 1, "#D1D5DB"));
        LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        elp.setMargins(0, 0, 0, dp(14)); etOther.setLayoutParams(elp);
        content.addView(etOther);

        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setGravity(Gravity.END);
        TextView btnSend = makeTv("Send Notice", 13f, "#FFFFFF", true);
        btnSend.setPadding(dp(22), dp(10), dp(22), dp(10));
        btnSend.setBackground(bg("#C62828", 14f, 0, null));
        btnSend.setOnClickListener(v -> {
            String det = spDet.getSelectedItemPosition() > 0 ? spDet.getSelectedItem().toString() : "";
            String oth = etOther.getText().toString().trim();
            if (selected.isEmpty() && det.isEmpty() && oth.isEmpty()) { toast("Please select at least one reason or detail for report."); return; }
            StringBuilder sb = new StringBuilder();
            if (!selected.isEmpty()) sb.append(TextUtils.join(", ", selected));
            if (!det.isEmpty()) { if (sb.length() > 0) sb.append(" - "); sb.append("Issue: ").append(det); }
            if (!oth.isEmpty()) { if (sb.length() > 0) sb.append(" | "); sb.append("Note: ").append(oth); }
            ApiClient.updateStatus(app.id, "REJECTED", sb.toString(), (ok, msg) -> {
                toast(ok ? "Notice sent and application rejected!" : (msg != null ? msg : "Failed to send notice"));
                if (ok) { dialog.dismiss(); loadAdminApplications(); }
            });
        });
        btnRow.addView(btnSend); content.addView(btnRow);
        sv.addView(content); card.addView(sv);
        dialog.setContentView(card);
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            dialog.getWindow().setLayout((int) (getResources().getDisplayMetrics().widthPixels * 0.88), ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        dialog.show();
    }

    private void inspectApplicantDocuments(ApiClient.VehicleApplication app) {
        ApiClient.BACKGROUND_EXECUTOR.execute(() -> {
            try {
                Map<String, String> slots = ApiClient.fetchUserDocsSync(app.ownerId);
                runOnUiThread(() -> {
                    if (slots.isEmpty()) { toast("No uploaded documents found for " + app.ownerName); return; }
                    ScrollView sv = new ScrollView(this);
                    LinearLayout box = new LinearLayout(this);
                    box.setOrientation(LinearLayout.VERTICAL); box.setPadding(32, 24, 32, 24);
                    for (Map.Entry<String, String> e : slots.entrySet()) {
                        TextView lbl = makeTv(getDocDisplayLabel(e.getKey()) + " (" + e.getValue() + ")", 13f, "#111827", true);
                        lbl.setPadding(0, 16, 0, 8);
                        ImageView iv = new ImageView(this);
                        iv.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 500));
                        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
                        Glide.with(this).load(ApiClient.UPLOADS_URL + Uri.encode(e.getValue())).into(iv);
                        box.addView(lbl); box.addView(iv);
                    }
                    sv.addView(box);
                    new AlertDialog.Builder(this).setTitle("Documents: " + app.ownerName + " (" + app.plateNumber + ")").setView(sv).setPositiveButton("Close", null).show();
                });
            } catch (Exception ignored) {}
        });
    }
}

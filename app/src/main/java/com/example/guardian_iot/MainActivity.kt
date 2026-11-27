package com.example.guardian_iot

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.hardware.*
import android.location.*
import android.net.Uri
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.*
import android.provider.Settings
import android.telephony.SubscriptionManager
import android.text.*
import android.view.*
import android.view.inputmethod.InputMethodManager
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.core.content.FileProvider
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.lifecycle.lifecycleScope
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.example.deteccioncaidas.CameraFallController
import com.example.deteccioncaidas.PoseOverlayView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : ComponentActivity() {
    private lateinit var store: AppStore
    private lateinit var sessions: EncryptedSessionStore
    private lateinit var backend: SupabaseBackend
    private lateinit var medicationRepository: MedicationRepository
    private lateinit var careFeatures: CareFeatureStore
    private lateinit var frame: FrameLayout
    private lateinit var root: View
    private var tab = 0
    private val navigationHistory = TzindaNavigationHistory()
    private var authMode: AuthMode? = null
    private var recoveryRevision = -1L
    private var authStatus: TextView? = null
    private var authEmail: EditText? = null
    private var authPassword: EditText? = null
    private var authName: EditText? = null
    private var authConfirmation: EditText? = null
    private var authPrivacy: CheckBox? = null
    private var authSubmit: Button? = null
    private var authGoogle: Button? = null
    private var authEmailDraft = ""
    private var authSubmitting = false
    private var finishingGoogleSignIn = false
    private var startupPermissionsVisible = false
    private var startupPermissionInFlight = false
    private var welcomeStep: Int? = null
    private var tutorialStep: Int? = null
    private var tutorialReturnTab: Int? = null
    private var caregiverRefreshInFlight = false
    private var caregiverRefreshAt = 0L
    private var caregiverRefreshScope: CaregiverScope? = null
    private var caregiverRefreshRequest = 0L
    private var caregiverMapRetry: Pair<Long, () -> Unit>? = null
    private var caregiverEventsPageInFlight = false
    private var caregiverEventsHasMore = true
    private var wearerLinkRefreshInFlight = false
    private var wearerLinkRefreshAt = 0L
    private var caregiverLocationRealtime: CaregiverLocationRealtime? = null
    private var caregiverLocationRealtimeDevice = ""
    private var caregiverHealthRealtime: CaregiverHealthRealtime? = null
    private var caregiverHealthRealtimeKey = ""
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var lastCareDataRefreshAt = 0L
    private var locationFreshnessRenderAt = 0L
    private var caregiverLocationUpdater: (() -> Unit)? = null
    private val mapService = lazy { TzindaMapService(this) }
    private val mapLocation = lazy { TzindaMapLocation(this, ::storedMapFix) }
    private var locationMap: TzindaV11? = null
    private var mapLocationPermission: ((MapFix?) -> Unit)? = null
    private var cancelMapFix: (() -> Unit)? = null
    private var showOtherCaregiverAlerts = false
    private var caregiverVitalsUpdater: (() -> Unit)? = null
    private var lastRenderKey: String? = null
    private var profileReturnTab = 0
    private var cameraReturnTab = 0
    private var deviceReturnTab = 0
    private var medicationReturnTab = 0
    private var careReturnTab = 0
    private var supervisionReturnTab = 0
    private var settingsReturnTab = 0
    private var activityFilter = 0
    private var historyOffset = 0
    private var cameraHistoryLimit = 100
    private var careJournalFilter = ""
    private var careSection = 0
    private var contactIndex = -1
    private var contactNameInput: EditText? = null
    private var contactPhoneInput: EditText? = null
    private var contactNameDraft = ""
    private var contactPhoneDraft = ""
    private var selectedEvent: JSONObject? = null
    private var helpIncident = ""
    private var helpSignature: List<String> = emptyList()
    private var helpDispatchActive = false
    private var updateHomeProtection: (() -> Unit)? = null
    private var practiceDialog: Dialog? = null
    private val v5 get() = TzindaV5(this, tz)
    private val v9 get() = TzindaV9(this, v5)
    private var patientPulseValue: TextView? = null
    private var patientSpo2Value: TextView? = null
    private var patientPulseUnit: TextView? = null
    private var patientSpo2Unit: TextView? = null
    private var patientPulseSpark: SparklineView? = null
    private var patientSpo2Spark: SparklineView? = null
    private var patientVitalsStatus: TextView? = null
    private val vitalTileUpdates = mutableListOf<(String) -> Unit>()
    private var updateVitalsCaption: ((Boolean) -> Unit)? = null
    private var caregiverSharedLocationView: TextView? = null
    private var caregiverLocationCoordinatesView: TextView? = null
    private var caregiverLocationFreshnessView: TextView? = null
    private var caregiverSharedStatusView: TextView? = null
    private var caregiverMapButton: View? = null
    private val braceletStatusViews = mutableListOf<TextView>()
    private val braceletStatusPillUpdaters = mutableListOf<(String) -> Unit>()
    private var pendingBraceletAction: String? = null
    private var patientVitalsRefreshAt = 0L
    private var cameraRegistryRenderedRevision = 0L
    private var editingContacts = false
    private val handler = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private var smsPermissionStatus: TextView? = null
    private var smsPermissionButton: Button? = null
    private var smsPermissionPill: TextView? = null
    private var smsRequestedInSession = false
    private var smsRequestInFlight = false
    private var resumeAlertSetup = false
    private var smsHelpDialog: AlertDialog? = null
    private val contactEdits = mutableListOf<Pair<EditText, EditText>>()
    private var contactDraft: List<TrustedContact>? = null
    private val profileEdits = linkedMapOf<String, EditText>()
    private var profileDraft: JSONObject? = null
    private var consentData: Switch? = null
    private var consentLocation: Switch? = null
    private var birthDate = ""
    private var birthLabel: TextView? = null
    private var pendingAction: String? = null
    private var importSlot = -2
    private var readingContacts = false
    private var countdownDialog: Dialog? = null
    private var countdownText: TextView? = null
    private var countdownRing: CountdownRing? = null
    private var cameraController: CameraFallController? = null
    private var cameraPreviewView: PreviewView? = null
    private var cameraRoomBackdrop: ImageView? = null
    private var cameraOverlayView: PoseOverlayView? = null
    private var cameraPlaceholder: TextView? = null
    private var cameraStatusTitle: TextView? = null
    private var cameraStatusDetail: TextView? = null
    private var cameraActionButton: TextView? = null
    private var cameraStateBadge: TextView? = null
    private var cameraControlIcon: ImageView? = null
    private var cameraRequested = false
    private var cameraFallHandled = false
    private var cameraSessionLogged = false
    private var shownIncident: String? = null
    private var locationListener: LocationListener? = null
    private val isCaregiver get() = store.role() == AppStore.ROLE_CAREGIVER
    private val isCameraRole get() = store.role() == AppStore.ROLE_CAMERA
    private val simpleMode get() = store.prefs.getBoolean("simple_accessibility", false)
    private var cachedDesign: TzindaDesign? = null
    private var cachedDesignSimple = false
    private val tz: TzindaDesign
        get() {
            val simple = simpleMode
            if (cachedDesign == null || cachedDesignSimple != simple) {
                cachedDesign = TzindaDesign(this, dark, simple)
                cachedDesignSimple = simple
            }
            return cachedDesign!!
        }
    private val qrScanLauncher = registerForActivityResult(ScanContract()) { result ->
        result.contents?.let(::processPairingCodeOrLink)
    }
    private class MedicationScan(
        val document: MedicationDocument,
        val mode: MedicationScanMode,
        val onResult: (MedicationScanResult?, String?) -> Unit,
        var photo: File? = null,
    )
    private var medicationScan: MedicationScan? = null
    private val medicationCameraLauncher = registerForActivityResult(
        // La app de cámara escribe en el archivo temporal: sin estos permisos el envío falla en
        // varios fabricantes aunque el FileProvider ya esté configurado.
        object : ActivityResultContracts.TakePicture() {
            override fun createIntent(context: Context, input: Uri): Intent =
                super.createIntent(context, input)
                    .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    .apply { clipData = android.content.ClipData.newRawUri("Foto para escanear", input) }
        },
    ) { saved ->
        val scan = medicationScan
        if (scan != null) {
            val file = scan.photo
            when {
                !saved -> finishMedicationScan(scan, null, null)
                file == null || file.length() == 0L -> finishMedicationScan(
                    scan, null, "La cámara no guardó la imagen. Toma otra foto o elígela de la galería.",
                )
                else -> readMedicationLabel(scan, Uri.fromFile(file))
            }
        }
    }
    private val medicationGalleryLauncher = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        val scan = medicationScan
        if (scan != null) {
            if (uri == null) finishMedicationScan(scan, null, null) else readMedicationLabel(scan, uri)
        }
    }
    private val medicationLabelScanner = object : MedicationLabelScanner {
        override val cloudAvailable: Boolean get() = NvidiaMedicationLabelReader.isAvailable(this@MainActivity)

        override fun takePhoto(document: MedicationDocument, mode: MedicationScanMode, onResult: (MedicationScanResult?, String?) -> Unit) {
            val scan = startMedicationScan(document, mode, onResult) ?: return
            if (!has(Manifest.permission.CAMERA)) {
                runCatching { requestPermissions(arrayOf(Manifest.permission.CAMERA), REQUEST_MEDICATION_SCAN) }
                    .onFailure { finishMedicationScan(scan, null, "Autoriza la cámara desde Ajustes o elige una imagen de la galería.") }
                return
            }
            launchMedicationCamera(scan)
        }

        override fun pickFromGallery(document: MedicationDocument, mode: MedicationScanMode, onResult: (MedicationScanResult?, String?) -> Unit) {
            val scan = startMedicationScan(document, mode, onResult) ?: return
            runCatching {
                medicationGalleryLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }.onFailure {
                finishMedicationScan(scan, null, "No se pudo abrir la galería en este teléfono.")
            }
        }
    }
    private val locationTimeout = Runnable {
        if (locationListener != null) { stopLocation(); toast("No se obtuvo una ubicación nueva. Los SMS siguen disponibles.", true) }
    }
    private val dark get() = store.prefs.getBoolean("dark_mode", false)
    private val ink get() = tz.ink
    private val muted get() = tz.muted
    private val accent get() = tz.accent
    private val paper get() = tz.paper
    private val cardColor get() = tz.cardColor
    private val mint get() = tz.mint
    private val softSurface get() = tz.softSurface
    private val outline get() = tz.outline
    private val onAccent get() = tz.onAccent
    private val critical get() = tz.critical

    override fun onCreate(savedInstanceState: Bundle?) {
        store = AppStore(this)
        sessions = EncryptedSessionStore(this)
        sessions.read()?.userId?.takeIf(String::isNotBlank)?.let { owner ->
            if (store.bindLocalDataToUser(owner)) store = AppStore(this)
        }
        val restoredState = savedInstanceState?.takeIf {
            it.getString("account_scope", "") == AccountStorage.key(store.appContext)
        }
        backend = SupabaseBackend(sessions)
        backend.cancelGoogleSignIn()
        medicationRepository = MedicationRepository(this, backend, store)
        careFeatures = CareFeatureStore(this)
        finishingGoogleSignIn = restoredState?.getBoolean("finishing_google_sign_in", false) == true
        authSubmitting = finishingGoogleSignIn
        authMode = restoredState?.getString("auth_mode")?.let { runCatching { AuthMode.valueOf(it) }.getOrNull() }
            ?: if (backend.isConfigured && sessions.read() == null && !store.prefs.getBoolean("local_only_mode", false)) AuthMode.LOGIN else null
        if (backend.hasPasswordRecovery) authMode = AuthMode.RESET
        else if (authMode == AuthMode.RESET) authMode = AuthMode.RECOVER
        val firstWelcome = store.role().isBlank() && sessions.read() == null &&
            !store.prefs.getBoolean("local_only_mode", false) && !store.prefs.getBoolean("v9_intro_seen", false)
        if (firstWelcome && authMode != AuthMode.RESET && restoredState?.containsKey("auth_mode") != true) {
            welcomeStep = store.prefs.getInt("v9_intro_step", 0).coerceIn(0, 2)
            authMode = null
        }
        tutorialReturnTab = restoredState?.getInt("v9_tutorial_return", -1)?.takeIf { it >= 0 }
            ?.let { if (it == RETIRED_ADVANCED_SETTINGS) TzindaTabs.SETTINGS else it }
        val restoredTutorial = restoredState?.getInt("v9_tutorial_page", -1)?.takeIf { it in 0..2 }
        tutorialStep = if (store.role().isBlank()) null else restoredTutorial ?: firstTutorialStep()
        if (dark) setTheme(R.style.AppTheme_Dark)
        super.onCreate(restoredState)
        tab = when (val restoredTab = restoredState?.getInt("tab")) {
            RETIRED_ADVANCED_SETTINGS -> TzindaTabs.SETTINGS
            null, ROLE_TAB -> if (store.role().isBlank()) ROLE_TAB else TzindaTabs.HOME
            else -> restoredTab
        }
        navigationHistory.restore(restoredState?.getIntArray("navigation_back_stack")
            ?.filter { it != RETIRED_ADVANCED_SETTINGS && it != ROLE_TAB }?.toIntArray())
        pendingAction = restoredState?.getString("action")
        profileReturnTab = restoredState?.getInt("profile_return_tab", 0) ?: 0
        cameraReturnTab = restoredState?.getInt("camera_return_tab", 0) ?: 0
        deviceReturnTab = restoredState?.getInt("device_return_tab", 0) ?: 0
        medicationReturnTab = restoredState?.getInt("medication_return_tab", 0) ?: 0
        careReturnTab = restoredState?.getInt("care_return_tab", 0) ?: 0
        supervisionReturnTab = restoredState?.getInt("supervision_return_tab", 0) ?: 0
        activityFilter = restoredState?.getInt("activity_filter", 0) ?: 0
        historyOffset = restoredState?.getInt("history_offset", 0) ?: 0
        selectedEvent = restoredState?.getString("selected_event")?.let { runCatching { JSONObject(it) }.getOrNull() }
        helpIncident = restoredState?.getString("help_incident").orEmpty()
        contactIndex = restoredState?.getInt("contact_index", -1) ?: -1
        contactNameDraft = restoredState?.getString("contact_name_draft").orEmpty()
        contactPhoneDraft = restoredState?.getString("contact_phone_draft").orEmpty()
        careSection = restoredState?.getInt("care_section", 0) ?: 0
        editingContacts = restoredState?.getBoolean("editing_contacts", false) ?: false
        smsRequestInFlight = restoredState?.getBoolean("sms_request_pending") ?: false
        resumeAlertSetup = restoredState?.getBoolean("resume_alert_setup") ?: false
        startupPermissionInFlight = restoredState?.getBoolean("startup_permission_pending") ?: false
        importSlot = restoredState?.getInt("import_slot", -2) ?: -2
        restoredState?.getString("contact_draft")?.let { json ->
            runCatching { val a = JSONArray(json); contactDraft = (0 until a.length()).map { TrustedContact(a.getJSONObject(it).optString("name"), a.getJSONObject(it).optString("phone")) } }
        }
        restoredState?.getString("profile_draft")?.let { runCatching { profileDraft = JSONObject(it) } }
        setContentView(R.layout.activity_main)
        root = findViewById(R.id.root)
        frame = findViewById(R.id.content)
        root.setBackgroundColor(paper)
        val back = findViewById<ImageView>(R.id.app_bar_back)
        back.setOnClickListener { navigateBack() }
        back.isFocusable = true
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (authMode != null) {
                    backFromAccess()
                } else if (welcomeStep != null && welcomeStep!! > 0) {
                    showWelcomeStep(welcomeStep!! - 1)
                } else if (startupPermissionsVisible) {
                    finishStartupPermissions()
                } else if (tutorialStep != null) {
                    previousTutorialStep()
                } else if (isSecondaryScreen(tab)) {
                    navigateBack()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                    isEnabled = true
                }
            }
        })
        root.setOnApplyWindowInsetsListener { view, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val padding = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
                if (tab == CAMERA_TAB && authMode == null && tutorialStep == null) view.setPadding(0, 0, 0, 0)
                else view.setPadding(padding.left, padding.top, padding.right, padding.bottom)
            } else if (tab == CAMERA_TAB) view.setPadding(0, 0, 0, 0)
            else view.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.stableInsetBottom)
            insets
        }
        if (Build.VERSION.SDK_INT >= 30) window.insetsController?.setSystemBarsAppearance(
            if (dark) 0 else WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
            WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS)
        window.statusBarColor = paper
        window.navigationBarColor = paper
        findViewById<LinearLayout>(R.id.navigation).setBackgroundColor(Color.TRANSPARENT)
        render()
        if (sessions.read() != null) SyncScheduler.enqueue(applicationContext)
        registerSyncNetworkCallback()
        if (intent?.data != null) { processAuthCallback(intent); processPairingLink(intent.data) }
        if (has(Manifest.permission.SEND_SMS) && !store.prefs.getBoolean("contacts_offered", false)) handler.post { offerContacts() }
    }

    /** Al volver la conectividad relanza la sincronización encolada y refresca el estado en pantalla. */
    private fun registerSyncNetworkCallback() {
        runCatching {
            val manager = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: android.net.Network) {
                    SyncScheduler.enqueue(applicationContext)
                    runOnUiThread { if (!isFinishing && !isDestroyed) render() }
                }
            }
            manager.registerDefaultNetworkCallback(callback)
            networkCallback = callback
        }
    }
    override fun onSaveInstanceState(out: Bundle) {
        saveDrafts()
        out.putString("account_scope", AccountStorage.key(store.appContext))
        out.putInt("tab", tab); out.putIntArray("navigation_back_stack", navigationHistory.snapshot())
        out.putInt("profile_return_tab", profileReturnTab); out.putInt("camera_return_tab", cameraReturnTab); out.putInt("device_return_tab", deviceReturnTab); out.putInt("medication_return_tab", medicationReturnTab); out.putInt("care_return_tab", careReturnTab); out.putInt("supervision_return_tab", supervisionReturnTab)
        out.putInt("activity_filter", activityFilter)
        out.putInt("history_offset", historyOffset)
        out.putBoolean("editing_contacts", editingContacts)
        out.putString("selected_event", selectedEvent?.toString())
        out.putString("help_incident", helpIncident)
        out.putInt("contact_index", contactIndex)
        out.putString("contact_name_draft", contactNameInput?.text?.toString() ?: contactNameDraft)
        out.putString("contact_phone_draft", contactPhoneInput?.text?.toString() ?: contactPhoneDraft)
        out.putInt("care_section", careSection)
        out.putString("action", pendingAction); out.putInt("import_slot", importSlot)
        out.putBoolean("sms_request_pending", smsRequestInFlight)
        out.putBoolean("resume_alert_setup", resumeAlertSetup)
        out.putBoolean("startup_permission_pending", startupPermissionInFlight)
        authMode?.let { out.putString("auth_mode", it.name) }
        out.putBoolean("finishing_google_sign_in", finishingGoogleSignIn)
        tutorialStep?.let { out.putInt("v9_tutorial_page", it) }
        tutorialReturnTab?.let { out.putInt("v9_tutorial_return", it) }
        contactDraft?.let { rows -> out.putString("contact_draft", JSONArray().apply { rows.forEach { put(JSONObject().put("name", it.name).put("phone", it.phone)) } }.toString()) }
        profileDraft?.let { out.putString("profile_draft", it.toString()) }
        super.onSaveInstanceState(out)
    }
    override fun onResume() {
        super.onResume()
        if (!AccountStorage.isCurrent(store.appContext)) { recreate(); return }
        locationMap?.setForeground(true)
        refreshPasswordRecovery()
        handler.post(live); refreshPermissionUi(); updateServiceIfActive()
        MedicationReminderScheduler.synchronizeAll(this)
        if (tab == CAMERA_TAB && cameraRequested && has(Manifest.permission.CAMERA)) launchCameraDetection()
    }
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && resumeAlertSetup && !smsRequestInFlight && !isFinishing && !isDestroyed) {
            resumeAlertSetup = false
            pendingAction?.let { action -> handler.post { if (!isFinishing && !isDestroyed) requestRemainingAlertPermissions(action) } }
        }
        if (hasFocus && startupPermissionsVisible && !startupPermissionInFlight) {
            handler.post { requestNextStartupPermission() }
        }
    }
    override fun onPause() {
        handler.removeCallbacks(live)
        locationMap?.setForeground(false)
        cancelMapFix?.invoke()
        if (tab == CAMERA_TAB && cameraRequested) cameraController?.stop()
        super.onPause()
    }
    override fun onDestroy() {
        networkCallback?.let { callback ->
            runCatching { (getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager).unregisterNetworkCallback(callback) }
        }
        networkCallback = null
        caregiverLocationRealtime?.stop()
        caregiverLocationRealtime = null
        caregiverHealthRealtime?.stop()
        caregiverHealthRealtime = null
        handler.removeCallbacksAndMessages(null)
        countdownDialog?.dismiss()
        practiceDialog?.dismiss()
        smsHelpDialog?.dismiss()
        locationMap?.close()
        cancelMapFix?.invoke()
        if (mapService.isInitialized()) mapService.value.close()
        stopLocation()
        cameraController?.shutdown()
        cameraController = null
        io.shutdownNow()
        super.onDestroy()
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent); setIntent(intent)
        processAuthCallback(intent)
        processPairingLink(intent.data)
    }
    private fun processAuthCallback(intent: Intent) {
        val callback = intent.data ?: return
        if (!backend.matchesAuthRedirect(callback)) return
        intent.data = null // The code can be exchanged once.
        if (authSubmitting) return
        if (backend.hasGoogleSignIn) {
            processGoogleCallback(callback)
            return
        }
        authCallbackIo.execute {
            val result = runCatching { backend.acceptPasswordRecovery(callback) }
            runOnUiThread {
                if (isDestroyed || isFinishing) return@runOnUiThread
                result.onSuccess { refreshPasswordRecovery() }.onFailure { failure ->
                    if (sessions.read() == null) {
                        authMode = AuthMode.RECOVER
                        render()
                    }
                    toast(failure.message ?: "No se pudo abrir el enlace.", true)
                }
            }
        }
    }

    private fun processGoogleCallback(callback: Uri) {
        if (!googleExchangeRunning.compareAndSet(false, true)) return
        finishingGoogleSignIn = true
        finishWelcome()
        authMode = AuthMode.LOGIN
        updateAuthButtons(true)
        render()
        val callbackBackend = backend
        authCallbackIo.execute {
            val result = runCatching { callbackBackend.acceptGoogleCallback(callback) }
            runOnUiThread {
                googleExchangeRunning.set(false)
                if (!isDestroyed && !isFinishing) finishGoogleSignIn(result.getOrNull(), result.exceptionOrNull())
            }
        }
    }

    private fun finishGoogleSignIn(session: AuthSession?, failure: Throwable? = null) {
        finishingGoogleSignIn = false
        updateAuthButtons(false)
        if (session != null) {
            completeSignIn(session, consented = true)
        } else {
            backend.cancelGoogleSignIn()
            val message = when ((failure as? BackendFailure)?.category) {
                BackendFailure.Category.NETWORK -> "Sin conexión. Conéctate a Internet y vuelve a intentar el acceso con Google."
                BackendFailure.Category.AUTH_RATE_LIMIT -> failure?.message
                else -> null
            } ?: BackendFailure(BackendFailure.Category.GOOGLE_SIGN_IN).message.orEmpty()
            setAuthStatus(message, true)
        }
    }
    private fun processPairingLink(uri: Uri?) {
        if (uri?.scheme != "tzinda" || uri.host != "pair") return
        val type = uri.getQueryParameter("type").orEmpty()
        val code = uri.getQueryParameter("code").orEmpty()
        if (type !in setOf("caregiver", "camera") || code.filter(Char::isLetterOrDigit).length != 8) return
        processPairingCodeOrLink(uri.toString())
    }

    private fun processPairingCodeOrLink(value: String) {
        val uri = runCatching { Uri.parse(value) }.getOrNull()
        val fromLink = uri?.scheme == "tzinda" && uri.host == "pair"
        val type = if (fromLink) uri?.getQueryParameter("type").orEmpty()
            else store.prefs.getString("pairing_expected_type", "").orEmpty().ifBlank { if (isCameraRole) "camera" else "caregiver" }
        val code = (if (fromLink) uri?.getQueryParameter("code").orEmpty() else value)
            .filter(Char::isLetterOrDigit).uppercase(Locale.ROOT)
        if (type !in setOf("camera", "caregiver") || code.length != 8) {
            toast("El QR o código no es válido. Revisa que tenga 8 caracteres.", true); return
        }
        showPairingConfirmation(type, code)
    }

    private fun showPairingConfirmation(type: String, code: String) {
        if (type !in setOf("camera", "caregiver") || code.filter(Char::isLetterOrDigit).length != 8) return
        val camera = type == "camera"
        val requiredRole = if (camera) AppStore.ROLE_CAMERA else AppStore.ROLE_CAREGIVER
        if (sessions.read() != null && store.role().isNotBlank() && store.role() != requiredRole) {
            val roleName = TzindaNavigationPolicy.roleLabel(TzindaNavigationPolicy.role(requiredRole))
            toast("Para usar este código, entra con una cuenta en modo $roleName.", true)
            return
        }
        val deviceName = if (camera) "Cámara independiente" else "Teléfono de la persona protegida"
        val content = column().apply {
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(22), dp(12), dp(22), dp(8))
            addView(text("●  CÓDIGO RECONOCIDO", 12, true, accent).apply {
                gravity = Gravity.CENTER
                background = rounded(mint, 20)
                setPadding(dp(14), dp(9), dp(14), dp(9))
            })
            addView(text(if (camera) "▣" else "♥", 36, true, accent).apply {
                gravity = Gravity.CENTER
                background = rounded(softSurface, 40)
                setPadding(dp(18), dp(12), dp(18), dp(12))
            }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(16) })
            addView(text(deviceName, 22, true).apply { gravity = Gravity.CENTER; setPadding(0, dp(12), 0, dp(4)) })
            addView(text("Vinculación segura", 15, true, accent).apply { gravity = Gravity.CENTER })
            val displayCode = code.filter(Char::isLetterOrDigit).uppercase(Locale.ROOT).chunked(4).joinToString("-")
            addView(text(displayCode, 24, true, ink).apply {
                gravity = Gravity.CENTER
                background = rounded(cardColor, 18)
                setPadding(dp(18), dp(10), dp(18), dp(10))
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
            addView(text("Código de 8 caracteres de un solo uso.", 13, color = muted)
                .apply { gravity = Gravity.CENTER; setPadding(0, dp(8), 0, 0) })
        }
        AlertDialog.Builder(this).setTitle("¿Conectar este dispositivo?").setView(content)
            .setNegativeButton("Ahora no", null)
            .setPositiveButton("Conectar") { _, _ ->
                if (sessions.read() == null || !backend.isConfigured) {
                    store.prefs.edit().putString("pending_pairing_type", type).putString("pending_pairing_code", code).apply()
                    toast("Inicia sesión para terminar la vinculación.")
                    authMode = AuthMode.LOGIN; render()
                } else {
                    if (store.role().isBlank()) {
                        store.saveRole(requiredRole, beginTutorial = true)
                        tutorialStep = 0
                        resetNavigation(TzindaTabs.HOME)
                    }
                    if (camera) acceptCameraPairing(code) else acceptCaregiverPairing(code)
                }
            }.show()
    }

    private fun scanPairingQr(type: String) {
        store.prefs.edit().putString("pairing_expected_type", type).apply()
        qrScanLauncher.launch(ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            .setPrompt(if (type == "camera") "Escanea el QR de la persona protegida" else "Escanea el QR de vinculación")
            .setBeepEnabled(false).setOrientationLocked(false))
    }
    private fun acceptCaregiverPairing(code: String, status: TextView? = null) {
        if (!backend.isConfigured || sessions.read() == null) { status?.text = "Inicia sesión antes de vincular."; return }
        val requestStore = store
        val actor = sessions.read()?.userId ?: return
        val access = CaregiverAccess(requestStore)
        status?.text = "Vinculando cuenta…"
        io.execute {
            val result = runCatching {
                // The RPC already returns the created link. Use that authoritative result
                // instead of requiring a second SELECT that can fail independently.
                backend.acceptCaregiverInvite(code).apply {
                    if (!has("id")) put("id", optString("link_id"))
                }
            }
            runOnUiThread {
                if (store !== requestStore) return@runOnUiThread
                result.onSuccess { link ->
                    if (!access.select(link, actor)) {
                        val message = "La cuenta o el vínculo cambió. Intenta vincular de nuevo."
                        status?.text = message
                        if (status == null) toast(message, true)
                        return@onSuccess
                    }
                    caregiverLocationRealtime?.stop()
                    caregiverLocationRealtime = null
                    caregiverLocationRealtimeDevice = ""
                    caregiverHealthRealtime?.stop()
                    caregiverHealthRealtime = null
                    caregiverHealthRealtimeKey = ""
                    toast("Cuenta vinculada.")
                    resetNavigation(0)
                }.onFailure { status?.text = it.message ?: "No se pudo vincular la cuenta."; if (status == null) toast(it.message ?: "Código inválido o vencido.", true) }
            }
        }
    }

    private fun acceptCameraPairing(code: String, status: TextView? = null) {
        if (!backend.isConfigured || sessions.read() == null) { status?.text = "Inicia sesión antes de vincular."; return }
        status?.text = "Vinculando cámara…"
        io.execute {
            runCatching { backend.unlinkPairedCamera() }
            val result = runCatching { backend.acceptCameraDeviceInvite(code) }
            runOnUiThread {
                result.onSuccess { linked ->
                    val ownerName = linked.optString("owner_name").takeUnless { it.isBlank() || it == "null" }
                    val deviceName = linked.optString("device_name").takeUnless { it.isBlank() || it == "null" } ?: "Cámara vinculada"
                    val editor = store.prefs.edit()
                        .putString("paired_camera_owner_id", linked.optString("owner_user_id"))
                        .putString("paired_camera_device_id", linked.optString("device_id"))
                        .putString("paired_camera_name", deviceName)
                        .remove("pending_pairing_type").remove("pending_pairing_code")
                    if (!ownerName.isNullOrBlank()) {
                        editor.putString("paired_camera_owner_name", ownerName)
                    } else {
                        editor.remove("paired_camera_owner_name")
                    }
                    editor.apply()
                    toast("Cámara vinculada a ${ownerName ?: deviceName}.")
                    resetNavigation(0)
                }.onFailure { status?.text = it.message ?: "No se pudo vincular la cámara."; if (status == null) toast(it.message ?: "Código inválido o vencido.", true) }
            }
        }
    }

    private fun showPairingQr(title: String, type: String, code: String, expiresAt: String) {
        val normalizedCode = code.filter(Char::isLetterOrDigit).uppercase(Locale.ROOT)
        if (normalizedCode.length != 8) {
            toast("Supabase devolvió un código de vinculación incompleto. Inténtalo de nuevo.", true)
            return
        }
        val displayCode = normalizedCode.chunked(4).joinToString("-")
        val payload = Uri.Builder().scheme("tzinda").authority("pair")
            .appendQueryParameter("type", type).appendQueryParameter("code", normalizedCode).build().toString()
        val image = runCatching { createQrBitmap(payload, dp(232)) }.getOrNull()
        val connectionStatus = text("Esperando a que el otro dispositivo se conecte…", 13, color = accent).apply {
            gravity = Gravity.CENTER; setPadding(0, dp(6), 0, 0)
        }
        val content = column().apply {
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(18), dp(8), dp(18), dp(10))
            addView(text("●  LISTO PARA VINCULAR", 12, true, accent).apply {
                gravity = Gravity.CENTER; background = rounded(mint, 20); setPadding(dp(12), dp(8), dp(12), dp(8))
            })
            addView(text(title, 21, true).apply { gravity = Gravity.CENTER; setPadding(0, dp(12), 0, dp(4)) })
            addView(text("La otra persona puede escanear este QR desde AxTarget Care, aunque esté en otra red Wi-Fi o use datos móviles.", 14, color = muted).apply { gravity = Gravity.CENTER })
            if (image != null) addView(ImageView(this@MainActivity).apply {
                setImageBitmap(image); contentDescription = "Código QR para $title"
                background = rounded(Color.WHITE, 24).apply { setStroke(dp(2), accent) }
                setPadding(dp(8), dp(8), dp(8), dp(8))
            }, LinearLayout.LayoutParams(dp(248), dp(248)).apply { topMargin = dp(12); bottomMargin = dp(6) })
            addView(text(displayCode, 30, true, accent).apply {
                gravity = Gravity.CENTER; background = rounded(softSurface, 16); setPadding(dp(16), dp(10), dp(16), dp(10))
            })
            addView(button("Copiar código", false) {
                val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Código de vinculación", normalizedCode))
                toast("Código copiado.")
            })
            addView(connectionStatus)
            addView(text("Código de un solo uso · vence a las $expiresAt\n\nSi no puede escanearlo, puede escribir los 8 caracteres.", 14, color = muted).apply {
                gravity = Gravity.CENTER; setPadding(dp(4), dp(10), dp(4), 0)
            })
        }
        val dialog = AlertDialog.Builder(this).setView(content).setPositiveButton("Listo", null).create()
        dialog.setCancelable(false)
        dialog.setCanceledOnTouchOutside(false)
        val pollHandler = Handler(Looper.getMainLooper())
        var dismissed = false
        var pairingCompleted = false
        val pollRunnable = object : Runnable {
            override fun run() {
                if (dismissed || !backend.isConfigured || sessions.read() == null) return
                io.execute {
                    try {
                        if (type == "camera") {
                            val cams = backend.linkedCamerasForOwner()
                            var newlyLinked = false
                            for (i in 0 until cams.length()) {
                                val item = cams.optJSONObject(i) ?: continue
                                val devId = item.optString("id")
                                val devName = item.optString("device_name").ifBlank { "Cámara" }
                                if (devId.isNotBlank() && !store.registeredCameras().any { it.id == devId }) {
                                    store.upsertRegisteredCamera(devId, devName, enqueueSync = false)
                                    newlyLinked = true
                                }
                            }
                            if (newlyLinked && !dismissed) {
                                runOnUiThread {
                                    if (!dismissed) {
                                        pairingCompleted = true
                                        connectionStatus?.text = "¡Cámara vinculada exitosamente! Pulsa Listo para cerrar."
                                    }
                                }
                                return@execute
                            }
                        } else if (type == "caregiver") {
                            val caregivers = backend.activeCaregiversForOwner()
                            if (caregivers.length() > 0 && !dismissed) {
                                store.prefs.edit().putBoolean("wearer_has_linked_caregiver", true).apply()
                                runOnUiThread {
                                    if (!dismissed) {
                                        pairingCompleted = true
                                        connectionStatus?.text = "¡Cuidador/a vinculado exitosamente! Pulsa Listo para cerrar."
                                    }
                                }
                                return@execute
                            }
                        }
                    } catch (_: Exception) {}
                    if (!dismissed) {
                        pollHandler.postDelayed(this, 2500L)
                    }
                }
            }
        }
        dialog.setOnDismissListener {
            dismissed = true
            pollHandler.removeCallbacksAndMessages(null)
        }
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                dialog.dismiss()
                if (pairingCompleted) {
                    toast(if (type == "camera") "¡Cámara vinculada exitosamente!" else "¡Cuidador/a vinculado exitosamente!")
                    render()
                }
            }
        }
        dialog.show()
        pollHandler.postDelayed(pollRunnable, 2500L)
    }
    private fun createQrBitmap(payload: String, size: Int): android.graphics.Bitmap {
        val matrix = com.google.zxing.qrcode.QRCodeWriter().encode(payload, com.google.zxing.BarcodeFormat.QR_CODE,
            size, size, mapOf(com.google.zxing.EncodeHintType.MARGIN to 1))
        val pixels = IntArray(size * size) { index ->
            if (matrix[index % size, index / size]) Color.BLACK else Color.WHITE
        }
        return android.graphics.Bitmap.createBitmap(pixels, size, size, android.graphics.Bitmap.Config.ARGB_8888)
    }
    private fun refreshPasswordRecovery() {
        val revision = backend.passwordRecoveryRevision
        if (revision == recoveryRevision) return
        recoveryRevision = revision
        if (backend.hasPasswordRecovery && authMode != AuthMode.RESET) {
            authMode = AuthMode.RESET
            render()
        }
    }
    private val live = object : Runnable {
        override fun run() {
            if (finishingGoogleSignIn && !googleExchangeRunning.get()) finishGoogleSignIn(sessions.read())
            refreshPasswordRecovery()
            refreshLive()
            handler.postDelayed(this, 1_000)
        }
    }
    private fun refreshLive() {
        locationMap?.checkAccess()
        if (locationMap?.isShowing() != true) cancelMapFix?.invoke()
        if (tab == DEVICE_TAB) {
            val revision = store.prefs.getLong("camera_registry_revision", 0L)
            if (revision != cameraRegistryRenderedRevision) {
                cameraRegistryRenderedRevision = revision
                render()
                return
            }
        }
        // Refresh the location card in place. Rebuilding the whole screen on a timer made the
        // list jump back to the top while the caregiver was reading or scrolling it.
        if (isCaregiver && (tab == 0 || tab == 1 || tab == DEVICE_TAB || tab == SUPERVISION_TAB) && System.currentTimeMillis() - locationFreshnessRenderAt >= 5_000L) {
            locationFreshnessRenderAt = System.currentTimeMillis()
            caregiverLocationUpdater?.invoke()
        }
        val braceletStatus = store.prefs.getString("arduino_status", "Pulsera sin conectar.").orEmpty()
        braceletStatusViews.forEach { it.text = braceletStatus }
        braceletStatusPillUpdaters.forEach { it(braceletStatus) }
        if (!isCaregiver) refreshPatientVitals()
        updateHomeProtection?.invoke()
        val pendingId = store.prefs.getString("pending_id", "").orEmpty()
        val activeDispatch = store.prefs.getString("dispatch_id", "").orEmpty()
        val dispatchId = activeDispatch.ifBlank {
            shownIncident?.takeIf { pendingId.isBlank() && incidentEntries(it).any { event ->
                val message = event.optString("message")
                message.startsWith("Alerta:") || message.startsWith("SMS solicitado") || message.startsWith("Llamada solicitada")
            } }.orEmpty()
        }
        if (authMode == null && !isCaregiver && !isCameraRole && dispatchId.isNotBlank() && helpIncident != dispatchId) {
            locationMap?.close()
            helpIncident = dispatchId
            tutorialStep = null
            tutorialReturnTab = null
            selectTab(TzindaTabs.HELP_REQUEST)
            return
        }
        if (tab == TzindaTabs.HELP_REQUEST) {
            val latest = incidentEntries(helpIncident).map { it.optString("message") }
            if (latest != helpSignature || helpDispatchActive != (activeDispatch.isNotBlank() && activeDispatch == helpIncident)) {
                render()
                return
            }
        }
        val id = pendingId
        if (id.isNotEmpty()) {
            if (id != shownIncident || countdownDialog?.isShowing != true) {
                countdownDialog?.dismiss()
                shownIncident = id
                showCountdownDialog()
                if (Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(true); setTurnScreenOn(true) }
                else window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
            }
            val left = ((store.prefs.getLong("pending_deadline", 0) - System.currentTimeMillis() + 999) / 1000).coerceAtLeast(0)
            countdownText?.text = left.toString()
            countdownText?.contentDescription = "Quedan $left segundos"
            countdownRing?.progress = (left / 20f).coerceIn(0f, 1f)
            countdownRing?.contentDescription = "Quedan $left segundos"
        } else {
            countdownDialog?.dismiss(); countdownDialog = null; shownIncident = null
            countdownText = null; countdownRing = null
            if (Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(false); setTurnScreenOn(false) }
            else window.clearFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
    }


    private fun showCountdownDialog() {
        if (isFinishing || isDestroyed) return
        locationMap?.close()
        practiceDialog?.dismiss()
        countdownDialog = showV5Countdown(practice = false)
    }

    private fun showV5Countdown(practice: Boolean): Dialog {
        val dialog = Dialog(this, if (dark) android.R.style.Theme_Material_NoActionBar else android.R.style.Theme_Material_Light_NoActionBar)
        val surface = column().apply {
            setBackgroundColor(tz.primaryFill)
            setPadding(dp(20), dp(30), dp(20), 0)
            clipChildren = false
        }
        val bar = v5.horizontal()
        bar.addView(v5.iconButton(R.drawable.v5_back, if (practice) "Volver a la práctica" else "Continuar fuera de esta pantalla",
            0x33FFFFFF, Color.WHITE, glyphSize = 29) {
            if (practice) dialog.dismiss() else moveTaskToBack(true)
        }, LinearLayout.LayoutParams(dp(44), dp(44)))
        v5.push(bar)
        val status = v5.horizontal().apply {
            minimumHeight = dp(28)
            background = rounded(tz.criticalSurface, 99)
            setPadding(dp(14), 0, dp(14), 0)
        }
        if (!practice) {
            status.addView(v5.icon(R.drawable.v5_motion, 24, tz.onCriticalSurface),
                LinearLayout.LayoutParams(dp(24), dp(24)).apply { marginEnd = dp(8) })
        }
        status.addView(v5.label(if (practice) "Simulacro SOS" else "Posible caída", tz.onCriticalSurface))
        bar.addView(status)
        surface.addView(bar)
        gap(surface, 17)
        surface.addView(v5.title("¿Estás bien?", 35, Color.WHITE))
        gap(surface, 8)
        surface.addView(v5.text(
            if (practice) "Esta es una práctica.\nNo se enviarán avisos."
            else "Detectamos un movimiento compatible\ncon una caída.", 14, v5.onBlueMuted,
        ))
        gap(surface, 25)
        surface.addView(v5.label("TIEMPO PARA RESPONDER", v5.onBlueMuted).apply { gravity = Gravity.CENTER })
        gap(surface, 12)
        val ring = CountdownRing(this)
        val dial = FrameLayout(this).apply { clipChildren = false }
        dial.addView(ring, FrameLayout.LayoutParams(-1, dp(282), Gravity.CENTER))
        val seconds = v5.text("20", 68, Color.WHITE).apply {
            gravity = Gravity.CENTER
            fontFeatureSettings = "tnum"
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val digits = column().apply {
            gravity = Gravity.CENTER
            addView(seconds)
            addView(v5.text("segundos", 13, v5.onBlueMuted))
        }
        dial.addView(digits, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        surface.addView(dial, LinearLayout.LayoutParams(-1, dp(184)))
        surface.addView(v5.text("Puedes cancelar el aviso", 12, v5.onBlueMuted).apply { gravity = Gravity.CENTER })
        gap(surface, 16)

        val actions = column().apply {
            background = GradientDrawable().apply {
                setColor(tz.cardColor)
                val radius = dp(31).toFloat()
                cornerRadii = floatArrayOf(radius, radius, radius, radius, 0f, 0f, 0f, 0f)
            }
            setPadding(dp(20), dp(19), dp(20), dp(24))
        }
        actions.addView(v5.title("Confirma cómo te encuentras", 18))
        gap(actions, 24)
        val safe = v5.panel(actions, tz.primaryFill, radius = 22, padding = 0, margin = 11)
        v5.row(safe, "Estoy bien", if (practice) "Terminar la práctica" else "Cancelar la alerta",
            R.drawable.v5_check, fill = 0x33FFFFFF, fg = Color.WHITE, minHeight = 67,
            titleColor = Color.WHITE, subtitleColor = v5.onBlueMuted, emphasis = true) {
            if (practice) {
                dialog.dismiss()
                toast("Práctica terminada")
            } else service(GuardianMonitorService.ACTION_CANCEL)
        }
        v5.action(actions, if (practice) "Practicar pedir ayuda" else "Pedir ayuda ahora",
            R.drawable.v5_phone, tz.imperialRed, Color.WHITE, height = 56, margin = 23) {
            if (practice) {
                dialog.dismiss()
                toast("Práctica terminada. No se enviaron avisos.")
            } else confirmHelp()
        }
        val warning = v5.panel(actions, tz.warningSurface, radius = 22, padding = 14, margin = 19)
        val warningRow = v5.horizontal()
        warningRow.addView(v5.icon(R.drawable.v5_people, 30, tz.warning))
        warningRow.addView(column().apply {
            addView(v5.text("Si no respondes", 13, tz.warning, bold = true))
            gap(this, 5)
            addView(v5.text(if (practice) "La práctica termina cuando vence el tiempo."
                else "Se solicitarán SMS y llamadas\na tus contactos.", 12, tz.warning))
        }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(9) })
        warning.addView(warningRow)
        actions.addView(v5.text(if (practice) "Simulación sin SMS ni llamadas."
            else "Los avisos dependen de permisos y cobertura.", 11, muted).apply { gravity = Gravity.CENTER })
        val page = column().apply {
            setBackgroundColor(tz.primaryFill)
            addView(surface)
            addView(actions, LinearLayout.LayoutParams(-1, -2, 1f))
        }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            addView(page)
        }
        dialog.setCancelable(practice)
        dialog.setCanceledOnTouchOutside(false)
        v5.showFullScreen(dialog, scroll, top = tz.primaryFill, bottom = tz.cardColor, lightStatus = false)
        dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        fun display(left: Long) {
            seconds.text = left.toString()
            ring.progress = left / 20f
            ring.contentDescription = "Quedan $left segundos"
        }
        display(20)
        if (practice) {
            val timer = object : CountDownTimer(20_000, 250) {
                override fun onTick(remaining: Long) = display((remaining + 999) / 1000)
                override fun onFinish() {
                    dialog.dismiss()
                    toast("Práctica terminada. No se enviaron avisos.")
                }
            }
            dialog.setOnDismissListener {
                timer.cancel()
                if (practiceDialog === dialog) practiceDialog = null
            }
            timer.start()
        } else {
            countdownText = seconds
            countdownRing = ring
        }
        return dialog
    }

    private fun practiceScreen(body: LinearLayout) {
        v5.header(body, "Simulacro SOS", "Practica cómo responder con tranquilidad.", gapBelow = 31)
        val hero = v5.panel(body, tz.primaryFill, radius = 28, padding = 20, margin = 26)
        val row = v5.horizontal()
        row.addView(column().apply {
            addView(v5.label("UN MOMENTO\nPARA PREPARARTE", v5.onBlueMuted))
            gap(this, 18)
            addView(v5.title("Ensaya\ncon calma.", 29, Color.WHITE))
        }, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(v5.art(R.drawable.v5_art_sos), LinearLayout.LayoutParams(dp(113), dp(101)).apply { marginEnd = dp(18) })
        hero.addView(row)
        gap(hero, 49)
        hero.addView(v5.text("Acciones de prueba", 12, Color.WHITE))
        v5.section(body, "Así será la práctica")
        val steps = v5.timeline(body, margin = 26)
        v5.row(steps, "Observa la cuenta regresiva", "Tendrás 20 segundos para responder.", R.drawable.v5_clock, minHeight = 80, round = true)
        v5.row(steps, "Prueba «Estoy bien»", "Aprende a cancelar una alerta.", R.drawable.v5_check, minHeight = 80, round = true)
        v5.row(steps, "Termina cuando quieras", "Regresa a Ajustes al finalizar.", R.drawable.v5_shield, minHeight = 80, round = true)
        v5.action(body, "Iniciar práctica", R.drawable.v5_play) { practiceDialog = showV5Countdown(practice = true) }
        v5.action(body, "Volver a Ajustes", fill = tz.cardColor, fg = accent) { navigateBack() }
    }

    private fun selectTab(index: Int) {
        if (index == TzindaTabs.SAFE_ZONES) {
            openSafeZones()
            return
        }
        if (index != TzindaTabs.ACTIVITY || tab != index) showOtherCaregiverAlerts = false
        navigationHistory.record(tab, index, isSecondaryScreen(index))
        showTab(index)
    }
    private fun navigateBack() {
        val destination = navigationHistory.back() ?: returnTabFor(tab)
        showTab(if (destination == tab) returnTabFor(tab) else destination)
    }
    private fun showTab(index: Int) {
        if (tab == CAMERA_TAB && index != CAMERA_TAB) disposeCameraController()
        saveDrafts(); tab = index; hideKeyboard(); render()
    }
    private fun resetNavigation(index: Int) {
        navigationHistory.clear()
        showTab(index)
    }
    private fun chooseRole(role: String) {
        if (store.role().isNotBlank()) return
        if (GuardianMonitorService.monitoring || store.prefs.getBoolean("monitor_enabled", false))
            service(GuardianMonitorService.ACTION_STOP)
        store.saveRole(role, beginTutorial = true)
        tutorialStep = 0
        resetNavigation(TzindaTabs.HOME)
    }
    private fun openCameraScreen() {
        if (tab != CAMERA_TAB) cameraReturnTab = if (isPrimaryTab(tab)) tab else 0
        val continueToCamera = {
            selectTab(CAMERA_TAB)
            if (!cameraRequested && packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)) toggleCameraDetection()
        }
        if (store.prefs.getBoolean("camera_intro_seen", false)) {
            continueToCamera()
            return
        }
        val intro = column().apply { setPadding(dp(2), dp(4), dp(2), dp(4)) }
        val showOnce = tz.switchRow(
            intro,
            "Mostrar esta explicación solo una vez",
            "Puedes volver a leerla desde la pantalla de la cámara.",
            value = true,
            action = {},
        )
        AlertDialog.Builder(this)
            .setTitle("Cámara opcional")
            .setMessage("La cámara solo se activa cuando la abres. La imagen se procesa en este teléfono y no se guarda ni se comparte. La detección puede equivocarse; mantén también la pulsera puesta.")
            .setView(intro)
            .setNegativeButton("Ahora no", null)
            .setPositiveButton("Continuar") { _, _ ->
                if (showOnce.isChecked) store.prefs.edit().putBoolean("camera_intro_seen", true).apply()
                continueToCamera()
            }
            .show()
    }
    private fun openDeviceLinkScreen() {
        if (tab != DEVICE_TAB) deviceReturnTab = if (isPrimaryTab(tab)) tab else 0
        selectTab(DEVICE_TAB)
    }
    private fun openMedicationScreen() {
        if (tab != TzindaTabs.MEDICATION_RECORDS) medicationReturnTab = if (isPrimaryTab(tab)) tab else MEDICATIONS_TAB
        selectTab(TzindaTabs.MEDICATION_RECORDS)
    }
    private fun openCareFeatures() {
        if (tab != CARE_TAB) careReturnTab = if (isPrimaryTab(tab)) tab else 0
        selectTab(CARE_TAB)
    }
    private fun openSupervisionCenter() {
        if (tab != SUPERVISION_TAB) supervisionReturnTab = if (isPrimaryTab(tab)) tab else 0
        selectTab(SUPERVISION_TAB)
    }
    private fun openProfile() {
        if (tab != TzindaTabs.PROFILE) profileReturnTab = if (tab == CAMERA_TAB) cameraReturnTab else tab
        selectTab(TzindaTabs.PROFILE)
    }
    private fun openSettings() {
        if (tab != TzindaTabs.SETTINGS) settingsReturnTab = if (isPrimaryTab(tab)) tab else 0
        selectTab(TzindaTabs.SETTINGS)
    }
    /** The five bottom-nav destinations; everything else is a secondary screen with a back arrow. */
    private fun isPrimaryTab(index: Int): Boolean =
        index in 0..3 || index == MEDICATIONS_TAB || index == SUPERVISION_TAB

    private fun isSecondaryScreen(index: Int): Boolean =
        index == TzindaTabs.PROFILE || index == DEVICE_TAB || index == CARE_TAB ||
            (index == TzindaTabs.CONTACTS && !isCameraRole) ||
            (index == TzindaTabs.SETTINGS && !isCameraRole) ||
            index == CAMERA_TAB || index in TzindaTabs.MEDICATION_RECORDS..TzindaTabs.CARE_SETTINGS

    private fun returnTabFor(index: Int): Int = primaryTabOr(when (index) {
        TzindaTabs.PROFILE -> profileReturnTab
        CAMERA_TAB -> cameraReturnTab
        DEVICE_TAB -> deviceReturnTab
        TzindaTabs.MEDICATION_RECORDS -> medicationReturnTab
        TzindaTabs.PULSE, TzindaTabs.OXYGEN -> MEDICATIONS_TAB
        TzindaTabs.ALERT_HISTORY, TzindaTabs.ALERT_DETAILS, TzindaTabs.BRACELET_ALERT, TzindaTabs.HELP_REQUEST -> TzindaTabs.ACTIVITY
        TzindaTabs.ALERT_PRACTICE, TzindaTabs.EMERGENCY_CARD -> TzindaTabs.SETTINGS
        TzindaTabs.BRACELET_PAIR, TzindaTabs.BRACELET_DIAGNOSTIC, TzindaTabs.SAFE_ZONES -> SUPERVISION_TAB
        TzindaTabs.ADD_CONTACT -> TzindaTabs.CONTACTS
        TzindaTabs.CARE_SETTINGS -> CARE_TAB
        TzindaTabs.REPORT -> TzindaTabs.SETTINGS
        CARE_TAB -> careReturnTab
        SUPERVISION_TAB -> supervisionReturnTab
        TzindaTabs.SETTINGS -> settingsReturnTab
        else -> 0
    })

    private fun primaryTabOr(value: Int): Int = if (isPrimaryTab(value)) value else 0
    private fun saveDrafts() {
        if (tab == 2 && contactEdits.size == 5) contactDraft = contactEdits.map { TrustedContact(it.first.text.toString(), it.second.text.toString()) }
        if (tab == TzindaTabs.PROFILE && profileEdits.isNotEmpty()) profileDraft = collectProfile()
    }
    private fun render() {
        startupPermissionsVisible = false
        // Keep the reading position when the same screen is rebuilt (sync, permissions, toggles).
        // A real navigation changes the key, so those screens still start at the top.
        val scrollBefore = (frame.getChildAt(0) as? ScrollView)?.scrollY ?: 0
        val renderKey = "${store.role()}|$tab"
        frame.removeAllViews()
        updateHomeProtection = null
        contactNameDraft = contactNameInput?.text?.toString() ?: contactNameDraft
        contactPhoneDraft = contactPhoneInput?.text?.toString() ?: contactPhoneDraft
        contactNameInput = null
        contactPhoneInput = null
        patientPulseValue = null; patientSpo2Value = null; patientPulseUnit = null; patientSpo2Unit = null
        patientPulseSpark = null; patientSpo2Spark = null
        patientVitalsStatus = null; patientVitalsRefreshAt = 0L
        vitalTileUpdates.clear()
        updateVitalsCaption = null
        caregiverSharedLocationView = null
        caregiverLocationCoordinatesView = null; caregiverLocationFreshnessView = null; caregiverMapButton = null
        caregiverLocationUpdater = null
        caregiverVitalsUpdater = null
        braceletStatusViews.clear()
        braceletStatusPillUpdaters.clear()
        smsPermissionStatus = null; smsPermissionButton = null; smsPermissionPill = null
        contactEdits.clear(); profileEdits.clear()
        authMode?.let { lastRenderKey = null; renderAuth(it); return }
        welcomeStep?.let { step ->
            val body = entrySurface()
            v9.welcome(body, step,
                next = {
                    if (step < 2) showWelcomeStep(step + 1)
                    else if (backend.isConfigured) openAccess(AuthMode.REGISTER) else useLocally()
                },
                skip = { if (backend.isConfigured) openAccess(AuthMode.LOGIN) else useLocally() },
                login = { openAccess(AuthMode.LOGIN) })
            return
        }
        startupPermissionSetup()?.let { setup ->
            renderStartupPermissions(setup)
            return
        }
        tutorialStep?.let { step ->
            val body = entrySurface()
            v9.tutorial(body, TzindaNavigationPolicy.role(store.role()), step, ::previousTutorialStep,
                next = { if (step < 2) setTutorialStep(step + 1) else finishTutorial() }, skip = ::finishTutorial)
            return
        }
        val role = TzindaNavigationPolicy.role(store.role())
        val appBar = findViewById<View>(R.id.app_bar)
        val navigation = findViewById<LinearLayout>(R.id.navigation)
        val onboarding = tab == ROLE_TAB
        val fullscreenCamera = tab == CAMERA_TAB
        val secondary = isSecondaryScreen(tab)
        appBar.visibility = if (secondary && !fullscreenCamera) View.VISIBLE else View.GONE
        navigation.visibility = if (onboarding || fullscreenCamera || secondary) View.GONE else View.VISIBLE
        // The viewfinder uses the whole display. Every other screen restores the system bars.
        root.setBackgroundColor(if (fullscreenCamera) Color.BLACK else paper)
        window.statusBarColor = if (fullscreenCamera) Color.TRANSPARENT else paper
        window.navigationBarColor = if (fullscreenCamera) Color.TRANSPARENT else paper
        root.requestApplyInsets()
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = !dark && !fullscreenCamera
            isAppearanceLightNavigationBars = !dark && !fullscreenCamera
            show(WindowInsetsCompat.Type.systemBars())
        }
        appBar.setBackgroundColor(paper)
        navigation.background = tz.rounded(tz.cardColor, 36).apply { setStroke(dp(1), outline) }
        navigation.elevation = 0f
        findViewById<ImageView>(R.id.app_bar_back).apply {
            setColorFilter(accent)
            background = tz.rounded(tz.cardColor, 99)
            contentDescription = "Volver a ${TzindaNavigationPolicy.screenName(role, navigationHistory.peek() ?: returnTabFor(tab))}"
        }
        findViewById<TextView>(R.id.app_bar_badge).apply {
            visibility = if (tab == TzindaTabs.ALERT_PRACTICE || tab == TzindaTabs.EMERGENCY_CARD || tab == TzindaTabs.REPORT) View.VISIBLE else View.GONE
            setTextColor(accent)
            background = tz.rounded(tz.brandSoft, 99)
        }
        val navItems = TzindaNavigationPolicy.items(role)
        val selectedIndex = TzindaNavigationPolicy.selectedIndex(role, tab)
        listOf(R.id.nav_home, R.id.nav_activity, R.id.nav_health, R.id.nav_supervision, R.id.nav_settings).forEachIndexed { index, id ->
            val button = findViewById<Button>(id)
            if (index >= navItems.size) {
                button.visibility = View.GONE
                return@forEachIndexed
            }
            button.visibility = View.VISIBLE
            val item = navItems[index]
            val selected = selectedIndex == index
            button.apply {
                text = item.label
                setTextColor(if (selected) onAccent else tz.metaGray)
                textSize = if (simpleMode) 13f else 12f
                typeface = if (selected) tz.boldTypeface() else tz.regularTypeface()
                isSelected = selected
                background = RippleDrawable(
                    ColorStateList.valueOf(tz.colorWithAlpha(tz.accent, .20f)),
                    rounded(if (selected) tz.navSelected else Color.TRANSPARENT, 999),
                    null,
                )
                stateListAnimator = null; elevation = 0f
                setPadding(dp(2), dp(6), dp(2), dp(6))
                minHeight = dp(if (simpleMode) 62 else 56)
                maxLines = 2
                includeFontPadding = false
                val icon = getDrawable(item.iconRes)?.mutate()
                icon?.setTint(if (selected) onAccent else tz.metaGray)
                icon?.setBounds(0, 0, dp(26), dp(26))
                setCompoundDrawables(null, icon, null, null)
                compoundDrawablePadding = dp(3)
                contentDescription = "${item.label}${if (selected) ", seleccionado" else ""}"
                setOnClickListener {
                    selectTab(item.targetTab)
                }
            }
        }
        if (fullscreenCamera) {
            val stage = FrameLayout(this)
            frame.addView(stage, FrameLayout.LayoutParams(-1, -1))
            cameraFullscreen(stage)
            lastRenderKey = renderKey
            refreshLive(); refreshPermissionUi()
            return
        }
        val top = when {
            secondary && tab == TzindaTabs.SETTINGS -> 0
            secondary -> 11
            onboarding -> 28
            tab == TzindaTabs.HOME && role == TzindaRole.WEARER -> 40
            else -> 31
        }
        val body = column().apply { setPadding(dp(20), dp(top), dp(20), dp(if (secondary) 24 else 8)) }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            addView(body)
            tag = "screen_scroll"
        }
        frame.addView(scroll)
        if (renderKey == lastRenderKey) scroll.post { scroll.scrollTo(0, scrollBefore) }
        lastRenderKey = renderKey
        when (tab) {
            ROLE_TAB -> v9.chooseMode(body, ::chooseRole)
            0 -> home(body)
            1 -> activity(body)
            2 -> if (isCameraRole) cameraPairingScreen(body) else contacts(body)
            3 -> settings(body)
            DEVICE_TAB -> deviceLinkScreen(body)
            MEDICATIONS_TAB -> healthScreen(body)
            TzindaTabs.MEDICATION_RECORDS -> {
                val linkedOwner = store.prefs.getString("caregiver_owner_id", "").orEmpty()
                // The link survives an expired session, so cached shared medications stay visible.
                val isLinkedCaregiver = isCaregiver && store.prefs.getString("caregiver_link_id", "").orEmpty().isNotBlank() && linkedOwner.isNotBlank()
                val ownerId = if (isCaregiver) linkedOwner.takeIf { isLinkedCaregiver }.orEmpty() else medicationRepository.currentOwnerScope()
                val currentUserId = sessions.read()?.userId.orEmpty()
                MedicationModuleUi(this, medicationRepository, ownerId, isCaregiver, dark, sessions.read() != null, currentUserId, medicationLabelScanner) {
                    selectTab(primaryTabOr(medicationReturnTab))
                }.render(body)
            }
            CARE_TAB -> careFeaturesScreen(body)
            SUPERVISION_TAB -> if (isCaregiver) supervisionCenter(body) else circleScreen(body)
            TzindaTabs.PULSE -> readingScreen(body, pulse = true)
            TzindaTabs.OXYGEN -> readingScreen(body, pulse = false)
            TzindaTabs.ALERT_HISTORY -> alerts(body, history = true)
            TzindaTabs.ALERT_DETAILS -> alertDetails(body)
            TzindaTabs.HELP_REQUEST -> helpRequest(body)
            TzindaTabs.ALERT_PRACTICE -> practiceScreen(body)
            TzindaTabs.BRACELET_ALERT -> braceletNotice(body)
            TzindaTabs.BRACELET_PAIR -> braceletPair(body)
            TzindaTabs.BRACELET_DIAGNOSTIC -> braceletDiagnosis(body)
            TzindaTabs.SAFE_ZONES -> v5.action(body, "Abrir zonas seguras", R.drawable.v5_pin, action = ::openSafeZones)
            TzindaTabs.ADD_CONTACT -> addContactScreen(body)
            TzindaTabs.EMERGENCY_CARD -> emergencyCard(body)
            TzindaTabs.REPORT -> prepareReport(body)
            TzindaTabs.CARE_SETTINGS -> careSettings(body)
            else -> profile(body)
        }
        refreshLive(); refreshPermissionUi()
    }
    private fun entrySurface(): LinearLayout {
        findViewById<View>(R.id.app_bar).visibility = View.GONE
        findViewById<LinearLayout>(R.id.navigation).visibility = View.GONE
        root.setBackgroundColor(paper)
        window.statusBarColor = paper
        window.navigationBarColor = paper
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
            show(WindowInsetsCompat.Type.systemBars())
        }
        root.requestApplyInsets()
        lastRenderKey = null
        val body = column().apply {
            setPadding(dp(20), dp(28), dp(20), dp(16))
            isFocusableInTouchMode = true
        }
        frame.addView(ScrollView(this).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            addView(body)
        })
        body.requestFocus()
        return body
    }

    private data class StartupPermissionSetup(
        val key: String,
        val attempted: Set<String>,
        val remaining: List<StartupPermissionGroup>,
    )

    private fun startupPermissionSetup(): StartupPermissionSetup? {
        val rawRole = store.role()
        if (authMode != null || welcomeStep != null || tab == ROLE_TAB || rawRole.isBlank()) return null
        val key = "startup_permissions_v1_$rawRole"
        val attempted = store.prefs.getStringSet(key, emptySet()).orEmpty()
        if (STARTUP_PERMISSIONS_FINISHED in attempted) return null
        val owner = store.ownerUserId() ?: return null
        if (sessions.read()?.userId != owner) return null
        val telephony = packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)
        val calling = if (Build.VERSION.SDK_INT >= 33) {
            packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY_CALLING)
        } else telephony
        val role = TzindaNavigationPolicy.role(rawRole)
        val remaining = startupPermissionGroups(
            role = role,
            sdk = Build.VERSION.SDK_INT,
            supportsCamera = packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY),
            usesNearby = store.braceletConnectionMode() == AppStore.BRACELET_MODE_BLE &&
                packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE),
            canRequestSms = role == TzindaRole.WEARER && smsPermissionState() == SmsPermissionPolicy.State.REQUESTABLE,
            supportsCalling = calling,
            supportsSim = telephony,
        ).filter { it.name !in attempted && !it.isGranted(::has) }
        if (remaining.isEmpty() && !startupPermissionInFlight) {
            store.prefs.edit().putStringSet(key, setOf(STARTUP_PERMISSIONS_FINISHED)).apply()
            return null
        }
        return StartupPermissionSetup(key, attempted, remaining)
    }

    private fun renderStartupPermissions(setup: StartupPermissionSetup) {
        startupPermissionsVisible = true
        val body = entrySurface()
        v5.header(body, "Permisos de AxTarget Care", "Configura el acceso para las funciones de este modo.", gapBelow = 20)
        if (setup.attempted.isEmpty()) {
            val panel = v5.panel(body, padding = 17, margin = 16)
            setup.remaining.forEach { group ->
                panel.addView(v5.text(group.purpose, 14))
                v5.gap(panel, 9)
            }
            v5.action(body, "Configurar permisos", R.drawable.v5_shield) { requestNextStartupPermission(begin = true) }
        } else {
            body.addView(v5.text("Responde a las solicitudes de Android para continuar.", 14, muted))
            v5.gap(body, 16)
            handler.post { requestNextStartupPermission() }
        }
        v5.action(body, "Ahora no", fill = tz.cardColor, fg = accent) { finishStartupPermissions() }
    }

    private fun requestNextStartupPermission(begin: Boolean = false) {
        if (!startupPermissionsVisible || startupPermissionInFlight || !hasWindowFocus() || isFinishing || isDestroyed) return
        val setup = startupPermissionSetup() ?: run { render(); return }
        if (!begin && setup.attempted.isEmpty()) return
        val group = setup.remaining.firstOrNull() ?: return
        // Record the request before Android takes focus. A denied permission will be available
        // from its feature later without reopening this setup on each login.
        store.prefs.edit().putStringSet(setup.key, setup.attempted + group.name).apply()
        startupPermissionInFlight = true
        try {
            requestPermissions(group.permissions, REQUEST_STARTUP_PERMISSIONS)
        } catch (_: RuntimeException) {
            startupPermissionInFlight = false
            render()
        }
    }

    private fun finishStartupPermissions() {
        startupPermissionSetup()?.let { setup ->
            store.prefs.edit().putStringSet(setup.key, setOf(STARTUP_PERMISSIONS_FINISHED)).apply()
        }
        render()
    }

    private fun showWelcomeStep(step: Int) {
        welcomeStep = step
        store.prefs.edit().putInt("v9_intro_step", step).apply()
        render()
    }

    private fun finishWelcome() {
        if (welcomeStep == null) return
        welcomeStep = null
        store.prefs.edit().putBoolean("v9_intro_seen", true).remove("v9_intro_step").apply()
    }

    private fun firstTutorialStep(): Int? = store.prefs.getInt(FIRST_TUTORIAL_STEP, -1).takeIf { it in 0..2 }

    private fun setTutorialStep(step: Int) {
        tutorialStep = step
        if (tutorialReturnTab == null) store.prefs.edit().putInt(FIRST_TUTORIAL_STEP, step).apply()
        render()
    }

    private fun previousTutorialStep() {
        val step = tutorialStep ?: return
        if (step > 0) setTutorialStep(step - 1) else finishTutorial()
    }

    private fun finishTutorial() {
        if (tutorialReturnTab == null) store.prefs.edit().remove(FIRST_TUTORIAL_STEP).apply()
        val destination = tutorialReturnTab ?: TzindaTabs.HOME
        tutorialReturnTab = null
        tutorialStep = null
        if (destination == tab) render() else resetNavigation(destination)
    }

    private fun useLocally() {
        if (authSubmitting) return
        backend.cancelGoogleSignIn()
        finishWelcome()
        authMode = null
        authEmailDraft = ""
        store.prefs.edit().putBoolean("local_only_mode", true).apply()
        resetNavigation(if (store.role().isBlank()) ROLE_TAB else TzindaTabs.HOME)
    }

    private fun openAccess(mode: AuthMode) {
        if (authSubmitting) return
        if (authMode != mode) backend.cancelGoogleSignIn()
        if (authMode != null) authEmailDraft = authEmail?.text?.toString()?.trim() ?: authEmailDraft
        finishWelcome()
        authMode = mode
        render()
    }

    private fun backFromAccess() {
        if (authSubmitting) return
        when (authMode) {
            AuthMode.RESET -> {
                backend.cancelPasswordRecovery()
                authMode = null
                render()
            }
            AuthMode.REGISTER, AuthMode.RECOVER -> openAccess(AuthMode.LOGIN)
            AuthMode.LOGIN -> {
                backend.cancelGoogleSignIn()
                authEmailDraft = ""
                when {
                    store.prefs.getBoolean("local_only_mode", false) -> { authMode = null; render() }
                    store.role().isBlank() -> {
                        authMode = null
                        store.prefs.edit().remove("v9_intro_seen").apply()
                        showWelcomeStep(0)
                    }
                    else -> moveTaskToBack(true)
                }
            }
            null -> Unit
        }
    }

    private fun renderAuth(mode: AuthMode) {
        val fields = v9.access(entrySurface(), mode, authEmailDraft, ::backFromAccess, ::openAccess,
            { showPrivacyNotice() }, ::useLocally, ::startGoogleSignIn) { submitAuth(mode) }
        authEmail = fields.email
        authName = fields.name
        authPassword = fields.password
        authConfirmation = fields.confirmation
        authPrivacy = fields.privacy
        authStatus = fields.status
        authSubmit = fields.submit
        authGoogle = fields.google
        updateAuthButtons(authSubmitting)
        if (finishingGoogleSignIn) setAuthStatus("Comprobando tu cuenta de Google…")
    }

    private fun showPrivacyNotice(manageAccount: Boolean = false) {
        val dialog = AlertDialog.Builder(this).setTitle("Privacidad y uso de AxTarget Care")
            .setMessage("Al iniciar sesión, Supabase puede almacenar tu nombre y rol, contactos de confianza con teléfono, eventos de caída y estados técnicos de SMS/llamada, lecturas válidas de pulso/SpO₂ del UNO Q y la ubicación en vivo cuando la autorizas y el monitoreo está activo.\n\nSi registras medicamentos, el nombre, dosis indicada, presentación, horarios, instrucciones e historial de tomas se guardan en este teléfono y se sincronizan con Supabase. Cada medicamento permanece privado hasta que actives “Compartir con mi cuidador/a”; solo una cuenta con vínculo activo podrá consultarlo. El cuidador vinculado puede registrar tomas compartidas; no puede editar el medicamento ni sus horarios. Puedes retirar el permiso de compartir editando el registro. AxTarget Care no recomienda ni modifica dosis. Al escanear una caja o receta puedes elegir procesar la foto en el teléfono sin enviarla. Si eliges la lectura en la nube con sesión iniciada, la foto pasa por Supabase y NVIDIA para extraer texto; AxTarget Care no la almacena y vuelve a la lectura local si falla la nube.\n\nEl domicilio, fecha de nacimiento, video y muestras crudas de movimiento no se sincronizan. Las funciones locales siguen disponibles sin Internet. AxTarget Care es experimental y no sustituye servicios de emergencia ni atención médica. Puedes cerrar sesión o solicitar la eliminación de la cuenta y los datos remotos desde Ajustes.")
            .setPositiveButton("Entendido", null)
        if (manageAccount && sessions.read() != null) {
            dialog.setNeutralButton("Eliminar cuenta") { _, _ -> confirmAccountDeletion() }
        }
        dialog.show()
    }

    private fun setAuthStatus(message: String, error: Boolean = false) {
        authStatus?.apply {
            text = message
            setTextColor(if (error) tz.onCriticalSurface else muted)
            visibility = View.VISIBLE
        }
    }

    private fun updateAuthButtons(busy: Boolean) {
        authSubmitting = busy
        authSubmit?.isEnabled = !busy
        authGoogle?.isEnabled = !busy
    }

    private fun startGoogleSignIn() {
        if (authSubmitting || (authMode != AuthMode.LOGIN && authMode != AuthMode.REGISTER)) return
        if (!backend.isConfigured || !GoogleSignIn.isConfigured) {
            setAuthStatus("Google todavía no está configurado en esta versión. Puedes iniciar sesión con correo.", true)
            return
        }
        if (authMode == AuthMode.REGISTER) {
            if (authPrivacy?.isChecked != true) {
                setAuthStatus("Acepta el aviso de privacidad para continuar con Google.", true)
                return
            }
            signInWithGoogle()
        } else if (store.prefs.getLong("privacy_consent_at", 0L) > 0L) {
            signInWithGoogle()
        } else {
            AlertDialog.Builder(this).setTitle("Continuar con Google")
                .setMessage("Si es tu primera vez, se creará una cuenta de AxTarget Care con tu correo de Google. Revisa el aviso de privacidad antes de continuar.")
                .setNeutralButton("Ver aviso") { _, _ -> showPrivacyNotice() }
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Aceptar y continuar") { _, _ -> signInWithGoogle() }
                .show()
        }
    }

    private fun signInWithGoogle() {
        if (authSubmitting) return
        updateAuthButtons(true)
        setAuthStatus("Elige una cuenta de Google…")
        lifecycleScope.launch {
            try {
                val session = withTimeout(GOOGLE_AUTH_TIMEOUT_MS) {
                    GoogleSignIn.signIn(this@MainActivity, backend)
                }
                updateAuthButtons(false)
                completeSignIn(session, consented = true)
            } catch (_: TimeoutCancellationException) {
                setAuthStatus("Google tardó demasiado en responder. Comprueba tu conexión y vuelve a intentarlo.", true)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: GetCredentialCancellationException) {
                setAuthStatus("Inicio de sesión con Google cancelado.")
            } catch (_: GetCredentialException) {
                setAuthStatus("No se pudo abrir Google. Revisa que exista el cliente OAuth de Android con el paquete y la huella SHA‑1 de esta app; también actualiza Google Play Services.", true)
            } catch (failure: BackendFailure) {
                val message = if (failure.category == BackendFailure.Category.NETWORK) failure.message
                    else "Supabase no pudo validar tu cuenta de Google. Revisa el Client ID Web del proveedor Google."
                setAuthStatus(message.orEmpty(), true)
            } catch (_: Exception) {
                setAuthStatus("No se recibió una credencial válida de Google. Inténtalo de nuevo.", true)
            } finally {
                updateAuthButtons(false)
            }
        }
    }

    private fun completeSignIn(session: AuthSession, consented: Boolean) {
        val pendingType = store.prefs.getString("pending_pairing_type", "").orEmpty()
        val pendingCode = store.prefs.getString("pending_pairing_code", "").orEmpty()
        store.prefs.edit().remove("pending_pairing_type").remove("pending_pairing_code").apply()
        if (store.bindLocalDataToUser(session.userId)) {
            disposeCameraController()
            stopService(Intent(this, GuardianMonitorService::class.java))
            caregiverLocationRealtime?.stop(); caregiverLocationRealtime = null; caregiverLocationRealtimeDevice = ""
            caregiverHealthRealtime?.stop(); caregiverHealthRealtime = null; caregiverHealthRealtimeKey = ""
            MedicationReminderReceiver.dismissAccountNotifications(this)
            store = AppStore(this)
            medicationRepository = MedicationRepository(this, backend, store)
            careFeatures = CareFeatureStore(this)
            contactDraft = null; profileDraft = null; contactEdits.clear(); profileEdits.clear()
            navigationHistory.clear()
            tab = if (store.role().isBlank()) ROLE_TAB else 0
            toast("Cuenta cambiada. Los datos anteriores siguen guardados por separado.")
        }
        store.prefs.edit().putBoolean("local_only_mode", false).apply()
        if (consented) {
            store.prefs.edit().putString("privacy_notice_version", "2026-10-21-v1")
                .putLong("privacy_consent_at", System.currentTimeMillis()).apply()
        }
        store.prepareContactsForUser(session.userId)
        finishWelcome()
        authMode = null
        authEmailDraft = ""
        authPassword = null
        authConfirmation = null
        SyncScheduler.enqueue(applicationContext)
        render()
        if (pendingType in setOf("caregiver", "camera") && pendingCode.isNotBlank()) {
            processPairingCodeOrLink("tzinda://pair?type=$pendingType&code=$pendingCode")
        }
    }

    private fun submitAuth(mode: AuthMode) {
        if (authSubmitting || authMode != mode) return
        val email = authEmail?.text?.toString()?.trim().orEmpty()
        val password = authPassword?.text?.toString().orEmpty()
        val name = authName?.text?.toString()?.trim().orEmpty()
        val confirmation = authConfirmation?.text?.toString().orEmpty()
        val consented = authPrivacy?.isChecked == true
        if (mode != AuthMode.RESET && !AuthInputPolicy.validEmail(email)) { setAuthStatus("Escribe un correo válido.", true); return }
        if (mode == AuthMode.REGISTER && (name.isBlank() || name.length > 120 || !consented)) { setAuthStatus("Completa tu nombre y acepta el aviso de privacidad.", true); return }
        if (mode == AuthMode.LOGIN && password.isBlank()) { setAuthStatus("Escribe tu contraseña.", true); return }
        if (mode == AuthMode.REGISTER || mode == AuthMode.RESET) {
            if (!AuthInputPolicy.validPassword(password)) { setAuthStatus("La contraseña debe tener entre 8 y 128 caracteres.", true); return }
            if (password != confirmation) { setAuthStatus("Las contraseñas no coinciden.", true); return }
        }
        if (!backend.isConfigured) { setAuthStatus("Supabase aún no está configurado. Puedes seguir en modo local.", true); return }
        backend.cancelGoogleSignIn()
        updateAuthButtons(true)
        setAuthStatus("Conectando de forma segura…")
        io.execute {
            val result = runCatching {
                when (mode) {
                    AuthMode.LOGIN -> { backend.signIn(email, password); "Sesión iniciada." }
                    AuthMode.REGISTER -> {
                        if (backend.signUp(email, password, name) == null) {
                            throw BackendFailure(BackendFailure.Category.EMAIL_CONFIRMATION_REQUIRED)
                        }
                        "Cuenta creada."
                    }
                    AuthMode.RECOVER -> { backend.requestPasswordRecovery(email); "Si el correo está registrado, recibirás un enlace para restablecer la contraseña." }
                    AuthMode.RESET -> { backend.updatePassword(password); "Contraseña actualizada. Inicia sesión con la nueva contraseña." }
                }
            }
            runOnUiThread {
                if (isDestroyed || isFinishing) return@runOnUiThread
                updateAuthButtons(false)
                result.onSuccess { message ->
                    setAuthStatus(message)
                    if (mode == AuthMode.RESET) { authMode = AuthMode.LOGIN; render(); setAuthStatus(message) }
                    if (mode == AuthMode.LOGIN || mode == AuthMode.REGISTER) {
                        sessions.read()?.let { session -> completeSignIn(session, mode == AuthMode.REGISTER && consented) }
                    }
                }.onFailure { setAuthStatus(it.message ?: "No se pudo completar la operación.", true) }
            }
        }
    }

    private fun cameraHome(body: LinearLayout) {
        val linked = store.prefs.getString("paired_camera_device_id", "").orEmpty().isNotBlank()
        v5.header(body, "Cámara", "Tu teléfono, atento a esta estancia.", settings = ::openSettings, gapBelow = 30)
        val picture = FrameLayout(this).apply {
            background = rounded(v5.cameraSurface, 28)
            clipToOutline = true
        }
        picture.addView(v5.art(R.drawable.v5_art_camera).apply { scaleType = ImageView.ScaleType.CENTER_CROP },
            FrameLayout.LayoutParams(-1, -1))
        fun capsule(text: String, bg: Int, fg: Int) = v5.text(text, 11, fg, bold = true).apply {
            background = rounded(bg, 99)
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(7), dp(12), dp(7))
        }
        picture.addView(capsule("•  En pausa", Color.WHITE, Color.BLACK), FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply {
            topMargin = dp(16); marginStart = dp(16)
        })
        picture.addView(capsule("Ejemplo", v5.cameraSurface, Color.WHITE), FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply {
            topMargin = dp(16); marginEnd = dp(17)
        })
        picture.addView(v5.iconButton(R.drawable.v5_play, "Iniciar detección", Color.WHITE, tz.persianBlue, 32, ::openCameraScreen),
            FrameLayout.LayoutParams(dp(54), dp(54), Gravity.CENTER).apply { bottomMargin = dp(14) })
        val copy = column().apply {
            addView(v5.title("Lista para detectar", 22, Color.WHITE))
            gap(this, 6)
            addView(v5.text("Activa la cámara cuando la necesites.", 12, 0xFFE7EFF2.toInt()))
        }
        picture.addView(copy, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply {
            marginStart = dp(18); marginEnd = dp(18); bottomMargin = dp(19)
        })
        body.addView(picture, LinearLayout.LayoutParams(-1, dp(222)).apply { bottomMargin = dp(16) })
        v5.action(body, "Iniciar detección", R.drawable.v5_play, height = 56, margin = 16, action = ::openCameraScreen)
        val privacy = v5.panel(body, tz.mint, radius = 19, padding = 13, margin = 28)
        val seal = v5.horizontal()
        seal.addView(v5.icon(R.drawable.v5_shield, 29, tz.positive))
        seal.addView(column().apply {
            addView(v5.text("Tu cámara, bajo tu control", 14, tz.positive, bold = true))
            gap(this, 6)
            addView(v5.text("Procesamiento en este teléfono.", 11, tz.positive))
        }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(14) })
        privacy.addView(seal)
        v5.section(body, "Tu cámara")
        val list = v5.panel(body, padding = 0, margin = 0)
        v5.row(list, if (linked) "Cámara vinculada" else "Vincular cámara", "Comparte detecciones autorizadas",
            R.drawable.v5_link, minHeight = 57, divider = true) { selectTab(TzindaTabs.CONTACTS) }
        v5.row(list, "Historial de detecciones", "Consulta la actividad de la cámara",
            R.drawable.v5_clock, minHeight = 57, divider = true) { selectTab(TzindaTabs.ACTIVITY) }
        v5.row(list, "Privacidad y permisos", "Tú decides cuándo usarla", R.drawable.v5_lock, tz.mint, tz.positive, minHeight = 57) { openSettings() }
    }

    private fun cameraPairingScreen(body: LinearLayout) {
        heading(body, "VINCULAR DISPOSITIVO", "Conecta esta cámara", "Escanea el QR de la persona protegida o escribe su código. Cada teléfono puede estar en una red distinta.")
        if (sessions.read() == null) {
            statusBanner(
                body,
                TzindaCopy(
                    "Inicia sesión para vincular",
                    "El código se valida con tu cuenta. Puedes escanearlo ahora y confirmar después de entrar.",
                    TzindaTone.WARNING,
                ),
                "Iniciar sesión",
            ) { authMode = AuthMode.LOGIN; render() }
        }
        val current = store.prefs.getString("paired_camera_device_id", "").orEmpty()
        val ownerName = store.prefs.getString("paired_camera_owner_name", "").orEmpty()
        if (current.isNotBlank()) {
            val linked = card(body)
            linked.addView(statusPill(TzindaStatusCopy.cameraLink(true, store.prefs.getString("paired_camera_name", "").orEmpty())))
            if (ownerName.isNotBlank()) {
                linked.addView(text("Persona protegida: $ownerName", 15, true, ink).apply { setPadding(0, dp(2), 0, 0) })
            }
            linked.addView(text("Los avisos se sincronizan por Internet cuando este teléfono tiene conexión. La cámara solo se activa cuando la abres.", 14, color = muted).apply { setPadding(0, dp(8), 0, 0) })
            linked.addView(button("Desvincular cámara", false) { unlinkCameraAccess() })
        }
        val connect = card(body)
        connect.addView(text("Conexión con la persona protegida", 20, true))
        connect.addView(text("No necesitan estar en la misma red. Funciona con Wi-Fi o datos móviles si ambos teléfonos llegan a AxTarget Care.", 14, color = muted))
        connect.addView(button("Escanear QR") { scanPairingQr("camera") })
        val code = field(connect, "Código de vinculación", "", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS, "camera_pairing_code")
        code.filters = arrayOf(android.text.InputFilter.LengthFilter(9))
        formatPairingCode(code)
        val status = text("El código se usa una vez y vence pronto.", 14, color = muted).also { connect.addView(it) }
        connect.addView(button("Continuar") {
            val clean = code.text.toString().filter(Char::isLetterOrDigit)
            if (clean.length != 8) status.text = "Escribe los 8 caracteres del código."
            else showPairingConfirmation("camera", clean)
        })
        val privacy = card(body)
        privacy.addView(text("Privacidad de la cámara", 18, true))
        privacy.addView(text("El video se procesa localmente y no se guarda. Solo se comparte el evento técnico autorizado. La cámara no funciona en segundo plano.", 13, color = muted))
    }
    private fun formatPairingCode(code: EditText, maxChars: Int = 8) {
        var formatting = false
        code.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(editable: Editable?) {
                if (formatting) return
                val raw = editable?.toString().orEmpty().uppercase(Locale.ROOT).filter(Char::isLetterOrDigit).take(maxChars)
                val divider = if (raw.length <= 6) 3 else 4
                val formatted = if (raw.length > divider) raw.take(divider) + "-" + raw.drop(divider) else raw
                if (formatted != editable?.toString()) {
                    formatting = true; code.setText(formatted); code.setSelection(formatted.length); formatting = false
                }
            }
        })
    }

    private fun unlinkCameraAccess() {
        if (sessions.read() == null || !backend.isConfigured) {
            toast("Inicia sesión para quitar el vínculo de cámara.", true); return
        }
        io.execute {
            val result = runCatching { backend.unlinkPairedCamera() }
            runOnUiThread {
                result.onSuccess {
                    store.prefs.edit().remove("paired_camera_device_id").remove("paired_camera_owner_id")
                        .remove("paired_camera_name").remove("paired_camera_owner_name").apply()
                    toast("Cámara desvinculada."); render()
                }.onFailure { toast(it.message ?: "No se pudo desvincular la cámara.", true) }
            }
        }
    }

    private fun showCreateCameraInviteDialog() {
        val content = column().apply { setPadding(dp(2), dp(4), dp(2), dp(4)) }
        content.addView(text("Ponle un nombre fácil de reconocer. Después comparte el QR; la cámara puede estar en cualquier red con conexión a AxTarget Care.", 14, color = muted))
        val name = field(content, "Nombre de la cámara", "Cámara", tag = "camera_name", hintText = "Ej. Cámara de sala")
            .apply { setSingleLine(true) }
        AlertDialog.Builder(this).setTitle("Vincular cámara independiente")
            .setView(ScrollView(this).apply { addView(content) })
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Crear QR o código") { _, _ ->
                val deviceName = name.text.toString().trim().ifBlank { "Cámara" }
                showCreateOnlineCameraInviteDialog(deviceName)
            }.show()
    }
    private fun showCreateOnlineCameraInviteDialog(deviceName: String) {
        if (!backend.isConfigured || sessions.read() == null) {
            AlertDialog.Builder(this)
                .setTitle("Necesitas una cuenta")
                .setMessage("El código de la cámara se genera en tu cuenta para que solo tus dispositivos autorizados puedan enviarte eventos. Inicia sesión y vuelve a intentarlo; el nombre que escribiste se conserva en el inventario.")
                .setNegativeButton("Ahora no", null)
                .setPositiveButton("Iniciar sesión") { _, _ -> authMode = AuthMode.LOGIN; render() }
                .show()
            return
        }
        toast("Preparando código seguro para $deviceName…")
        io.execute {
            val result = runCatching { backend.createCameraDeviceInvite(deviceName) }
            runOnUiThread { result.onSuccess { (code, expiry) -> showPairingQr("Conectar $deviceName", "camera", code, expiry) }
                .onFailure { toast(it.message ?: "No se pudo crear el código de cámara.", true) } }
        }
    }

    private fun showCreateCaregiverInvite(parent: LinearLayout) {
        createCaregiverOnlineInvite()
    }
    private fun createCaregiverOnlineInvite() {
        if (!backend.isConfigured || sessions.read() == null) { toast("Inicia sesión para vincular por Internet.", true); return }
        SyncScheduler.enqueue(applicationContext, immediate = true)
        toast("Preparando código seguro para el cuidador/a…")
        io.execute {
            val result = runCatching { backend.createCaregiverAccountInvite() }
            runOnUiThread {
                result.onSuccess { (code, expiry) -> showPairingQr("Vincular cuidador/a", "caregiver", code, expiry) }
                    .onFailure { toast(it.message ?: "No se pudo crear el código.", true) }
            }
        }
    }


    private fun home(body: LinearLayout) {
        if (isCaregiver) { caregiverHome(body); return }
        if (isCameraRole) { cameraHome(body); return }
        refreshWearerLinkedData()
        v5.header(body, "Resumen", eyebrow = "HOY", gapBelow = 18, settings = ::openSettings)

        val hero = v5.panel(body, tz.primaryFill, radius = 28, padding = 16, margin = 16)
        val heading = v5.horizontal()
        heading.addView(v5.badge(R.drawable.v5_shield, Color.WHITE, Color.BLACK, 44, 29, 99))
        val state = v5.text("", 13, v5.onBlueMuted)
        heading.addView(column().apply {
            addView(v5.text("Tu protección", 16, Color.WHITE, bold = true))
            gap(this, 4)
            addView(state)
        }, LinearLayout.LayoutParams(-1, -2).apply { marginStart = dp(12) })
        hero.addView(heading)
        gap(hero, 10)
        val protectionTitle = v5.title("", 27, Color.WHITE)
        protectionTitle.setPadding(dp(4), 0, 0, 0)
        hero.addView(protectionTitle)
        gap(hero, 5)
        hero.addView(v5.text("Tu teléfono te acompaña ante una posible caída.", 13, v5.onBlueMuted).apply {
            setPadding(dp(4), 0, 0, 0)
        })
        gap(hero, 14)
        val control = v5.action(hero, "Activar monitoreo", fill = Color.WHITE, fg = Color.BLACK,
            height = 40, radius = 17, margin = 0) {
            if (GuardianMonitorService.monitoring) service(GuardianMonitorService.ACTION_STOP)
            else prepareAlert(GuardianMonitorService.ACTION_START)
        }.apply { tag = "monitor_toggle" }
        updateHomeProtection = {
            val active = GuardianMonitorService.monitoring
            val action = if (active) "Pausar monitoreo" else "Activar monitoreo"
            if (control.text != action || protectionTitle.text.isEmpty()) {
                state.text = if (active) "Monitoreo activo" else "Monitoreo en pausa"
                protectionTitle.text = if (active) "Tu protección está activa" else "Activa tu protección"
                control.text = action
                control.contentDescription = action
            }
        }
        val device = v5.panel(body, padding = 0, margin = 8)
        val watchRow = v5.row(device, "Pulsera Tzinda", braceletConnectionLabel(), R.drawable.v5_watch,
            round = true, minHeight = 67, inset = 8, iconGap = 6) { selectTab(TzindaTabs.BRACELET_PAIR) }
        val watchLabels = watchRow.getChildAt(1) as LinearLayout
        val watchState = watchLabels.getChildAt(watchLabels.childCount - 1) as TextView
        braceletStatusPillUpdaters.add {
            val connection = braceletConnectionLabel()
            watchState.text = connection
            watchRow.contentDescription = "Pulsera Tzinda. " + connection
        }

        v5.section(body, "Tu salud", "Ver todo") { selectTab(MEDICATIONS_TAB) }
        v5VitalTiles(body, compact = true)
        gap(body, 16)
        val medicine = v5.panel(body, padding = 0, margin = 17)
        v5.row(medicine, "Medicamentos", "Recordatorios y próximas tomas", R.drawable.v5_pill,
            fill = tz.softSurface, fg = tz.warning, minHeight = 67, round = true, inset = 8, iconGap = 6, action = ::openMedicationScreen)
        v5.action(body, "Pedir ayuda", R.drawable.v5_phone, tz.criticalSurface, tz.onCriticalSurface,
            height = 50, radius = 30, margin = 0, centeredIcon = true, action = ::confirmHelp)
    }

    private fun confirmHelp() {
        AlertDialog.Builder(this).setTitle("Pedir ayuda")
            .setMessage("Se solicitarán SMS y llamadas a tus contactos de confianza. La recepción depende de la cobertura, los permisos y tu operador.")
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Pedir ayuda") { _, _ -> prepareAlert(GuardianMonitorService.ACTION_ALERT_NOW) }
            .show()
    }

    private fun braceletConnectionLabel(): String {
        val raw = store.prefs.getString("arduino_status", "").orEmpty()
        return when {
            arduinoConnectionAvailable() -> "Conectada"
            raw.contains("buscando", ignoreCase = true) -> "Buscando pulsera"
            else -> "Sin conexión"
        }
    }

    private fun healthScreen(body: LinearLayout) {
        if (isCaregiver) { caregiverHealth(body); return }
        v5.header(body, "Salud", "Tu bienestar y tus cuidados, en un lugar.", "AXTARGET CARE / SALUD",
            gapBelow = 39, eyebrowGap = 27, settings = ::openSettings)
        val hero = v5.panel(body, tz.primaryFill, radius = 28, padding = 19, margin = 18).apply {
            minimumHeight = dp(163)
            gravity = Gravity.CENTER_VERTICAL
        }
        val row = v5.horizontal()
        val live = arduinoConnectionAvailable()
        row.addView(column().apply {
            addView(v5.label("PULSERA TZINDA", v5.onBlueMuted))
            gap(this, 13)
            addView(v5.title(if (live) "Tu pulsera está conectada" else "Tu próxima lectura empieza aquí.", 25, Color.WHITE))
            gap(this, 17)
            addView(v5.text(if (live) "Revisa tu pulsera" else "Conecta tu pulsera", 13, Color.WHITE, bold = true))
        }, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(v5.art(R.drawable.v5_art_watch), LinearLayout.LayoutParams(dp(64), dp(108)).apply {
            marginStart = dp(16); marginEnd = dp(5)
        })
        hero.addView(row)
        hero.contentDescription = "Conecta tu pulsera"
        v5.activate(hero) { selectTab(TzindaTabs.BRACELET_PAIR) }
        v5VitalTiles(body, compact = false)
        gap(body, 27)
        v5.section(body, "Tus cuidados")
        val care = v5.panel(body, padding = 0)
        v5.row(care, "Medicamentos", "Tomas y recordatorios", R.drawable.v5_pill,
            fill = tz.mint, fg = tz.positive, minHeight = 66, divider = true, action = ::openMedicationScreen)
        v5.row(care, "Bitácora de cuidados", "Tu seguimiento, día a día", R.drawable.v5_journal, minHeight = 66, action = ::openCareFeatures)
    }

    private fun v5VitalTiles(body: LinearLayout, compact: Boolean) {
        val snapshot = vitalSnapshot(false, JSONArray())
        fun tile(isPulse: Boolean): LinearLayout {
            val color = if (isPulse) tz.onCriticalSurface else accent
            val label = if (isPulse) "Frecuencia cardíaca" else "Oxígeno en sangre"
            val tile = column().apply {
                minimumHeight = dp(if (compact) 148 else 174)
                setPadding(dp(16), dp(16), dp(16), dp(13))
                background = rounded(if (isPulse) tz.criticalSurface else tz.brandSoft, 26)
            }
            tile.addView(v5.icon(if (isPulse) R.drawable.v5_pulse else R.drawable.v5_oxygen, if (compact) 21 else 27, color))
            gap(tile, 5)
            val tileLabel = if (compact) label else if (isPulse) "Frecuencia\ncardíaca" else "Oxígeno\nen sangre"
            tile.addView(v5.text(tileLabel, if (compact) 13 else 14, color, bold = true))
            gap(tile, 2)
            val row = v5.horizontal().apply { gravity = Gravity.BOTTOM }
            val reading = v5.text(if (isPulse) snapshot.pulse else snapshot.oxygen, 38, bold = compact)
            val unit = if (isPulse) "lpm" else "%"
            row.addView(reading)
            row.addView(v5.text(unit, 13, muted).apply { setPadding(dp(10), 0, 0, dp(4)) })
            tile.addView(row)
            if (isPulse) patientPulseValue = reading else patientSpo2Value = reading
            if (compact) {
                gap(tile, 10)
                tile.addView(View(this).apply { setBackgroundColor(tz.colorWithAlpha(color, .2f)) },
                    LinearLayout.LayoutParams(-1, dp(1)))
                gap(tile, 5)
                val meta = v5.text("", 12, muted)
                tile.addView(meta)
                vitalTileUpdates.add { latest ->
                    meta.text = if (latest == "—") "En espera de lecturas" else "Última lectura válida"
                    tile.contentDescription = if (latest == "—") "$label. En espera de lecturas" else "$label, $latest $unit"
                }
            } else {
                vitalTileUpdates.add { latest -> tile.contentDescription = if (latest == "—") "$label. Sin lecturas" else "$label, $latest $unit" }
            }
            v5.activate(tile) { selectTab(if (isPulse) TzindaTabs.PULSE else TzindaTabs.OXYGEN) }
            return tile
        }
        v5.twoColumns(body, tile(true), tile(false))
        if (!compact) {
            gap(body, 16)
            val caption = v5.text("", 12, muted)
            body.addView(caption)
            updateVitalsCaption = { available -> caption.text = if (available) "Últimas lecturas válidas" else "En espera de lecturas válidas" }
        }
    }
    private data class VitalSnapshot(
        val pulse: String,
        val oxygen: String,
        val capturedAt: Long,
        val pulseSeries: List<Float>,
        val oxygenSeries: List<Float>,
    )

    private fun vitalNumber(value: Double): String =
        if (value % 1.0 == 0.0) value.toInt().toString() else "%.1f".format(Locale("es", "MX"), value)

    /** Reads the same persisted or authorized samples used by the history screen. */
    private fun vitalSnapshot(caregiver: Boolean, shared: JSONArray): VitalSnapshot {
        if (caregiver) {
            fun rows(type: String): List<Pair<Float, Long>> = (0 until shared.length()).mapNotNull { index ->
                val row = shared.optJSONObject(index) ?: return@mapNotNull null
                if (row.optString("measurement_type") != type || (!row.isNull("is_valid") && !row.optBoolean("is_valid"))) return@mapNotNull null
                val value = row.optDouble("value", Double.NaN)
                val at = row.optString("captured_at").let(::isoMillis)
                value.takeIf { it.isFinite() }?.let { it.toFloat() to at }
            }.take(16).reversed()
            val pulseRows = rows("heart_rate")
            val oxygenRows = rows("spo2")
            return VitalSnapshot(
                pulse = pulseRows.lastOrNull()?.first?.let { vitalNumber(it.toDouble()) } ?: "—",
                oxygen = oxygenRows.lastOrNull()?.first?.let { vitalNumber(it.toDouble()) } ?: "—",
                capturedAt = maxOf(pulseRows.lastOrNull()?.second ?: 0L, oxygenRows.lastOrNull()?.second ?: 0L),
                pulseSeries = pulseRows.map { it.first },
                oxygenSeries = oxygenRows.map { it.first },
            )
        }
        val latest = store.telemetry.latestArduinoVitals()
        val history = store.telemetry.recentArduinoVitalHistory(16).reversed()
        val pulseSeries = history.mapNotNull { it.heartRateBpm?.toFloat() }
        val oxygenSeries = history.mapNotNull { it.spo2Percent?.toFloat() }
        val pulse = if (latest.has("bpm")) vitalNumber(latest.optDouble("bpm")) else "—"
        val oxygen = if (latest.has("spo2")) vitalNumber(latest.optDouble("spo2")) else "—"
        return VitalSnapshot(
            pulse = pulse,
            oxygen = oxygen,
            capturedAt = maxOf(latest.optLong("heart_rate_at"), latest.optLong("spo2_at")),
            pulseSeries = pulseSeries,
            oxygenSeries = oxygenSeries,
        )
    }

    private fun vitalSparkline(points: List<Float>, ringColor: Int): SparklineView = SparklineView(this).apply {
        lineColor = accent
        fillColor = tz.colorWithAlpha(accent, if (dark) .22f else .12f)
        dotRingColor = ringColor
        setPoints(points)
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        contentDescription = null
    }

    private fun addVitalSummary(parent: LinearLayout, caregiver: Boolean) {
        val shared = if (caregiver) runCatching { JSONArray(store.prefs.getString("caregiver_shared_measurements", "[]")) }.getOrDefault(JSONArray()) else JSONArray()
        val snapshot = vitalSnapshot(caregiver, shared)
        val metrics = tz.twoColumns(parent)
        val tileValues = arrayOfNulls<TextView>(2)
        val tileMetas = arrayOfNulls<TextView>(2)
        fun tile(label: String, value: String, unit: String, points: List<Float>, meta: String, isPulse: Boolean, index: Int) {
            val metric = column().apply {
                background = rounded(tz.cardColor, 20).apply { setStroke(dp(1), outline) }
                setPadding(dp(15), dp(13), dp(13), dp(13))
                val labelRow = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                labelRow.addView(tz.iconBadge(
                    if (isPulse) R.drawable.ic_heart else R.drawable.ic_activity,
                    if (isPulse) TzindaDesign.Tone.DANGER else TzindaDesign.Tone.INFO,
                    size = 28,
                ), LinearLayout.LayoutParams(dp(28), dp(28)))
                labelRow.addView(text(label, 14, true, ink).apply { setPadding(dp(8), 0, 0, 0) },
                    LinearLayout.LayoutParams(0, -2, 1f))
                addView(labelRow)
                val valueRow = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.BOTTOM
                    setPadding(0, dp(2), 0, 0)
                }
                val valueView = text(value, 36, true, ink).apply { fontFeatureSettings = "tnum" }
                if (isPulse) patientPulseValue = valueView else patientSpo2Value = valueView
                val unitView = text(unit, 12, true, muted).apply { setPadding(0, 0, 0, dp(5)) }
                if (isPulse) patientPulseUnit = unitView else patientSpo2Unit = unitView
                valueRow.addView(valueView)
                valueRow.addView(unitView)
                addView(valueRow)
                if (points.isNotEmpty()) {
                    val spark = vitalSparkline(points, tz.softSurface)
                    if (isPulse) patientPulseSpark = spark else patientSpo2Spark = spark
                    addView(spark, LinearLayout.LayoutParams(-1, dp(38)).apply { topMargin = dp(4) })
                } else {
                    addView(Space(this@MainActivity), LinearLayout.LayoutParams(1, dp(4)))
                }
                val metaView = statusPill(TzindaCopy(meta, "", if (isPulse) TzindaTone.NEUTRAL else TzindaTone.POSITIVE))
                addView(metaView)
                tileValues[index] = valueView
                tileMetas[index] = metaView
            }
            metrics.addView(metric, LinearLayout.LayoutParams(0, -2, 1f).apply { if (index > 0) leftMargin = dp(8) })
        }
        val hasReading = snapshot.pulse != "—" || snapshot.oxygen != "—"
        val freshness = if (hasReading && snapshot.capturedAt > 0L)
            TzindaStatusCopy.freshness(System.currentTimeMillis() - snapshot.capturedAt) else "Sin lectura válida"
        tile("Pulso", snapshot.pulse, "BPM", snapshot.pulseSeries, freshness, true, 0)
        tile("SpO₂", snapshot.oxygen, "%", snapshot.oxygenSeries, freshness, false, 1)
        val statusCopy = TzindaStatusCopy.vitals(hasReading, snapshot.capturedAt, arduinoConnectionAvailable())
        patientVitalsStatus = text("${statusCopy.title}. ${statusCopy.detail}", 13, false, tz.toneForeground(designTone(statusCopy.tone))).apply {
            setPadding(0, dp(10), 0, 0)
        }
        parent.addView(patientVitalsStatus)
        if (caregiver) {
            // A new measurement used to rebuild the entire screen every few seconds. That reset the
            // reading position while the caregiver was checking the numbers, so only the tiles change.
            caregiverVitalsUpdater = {
                val latest = vitalSnapshot(
                    true,
                    runCatching { JSONArray(store.prefs.getString("caregiver_shared_measurements", "[]")) }
                        .getOrDefault(JSONArray()),
                )
                tileValues[0]?.text = latest.pulse
                tileValues[1]?.text = latest.oxygen
                val hasFreshReading = latest.pulse != "—" || latest.oxygen != "—"
                val freshnessLabel = if (hasFreshReading && latest.capturedAt > 0L)
                    TzindaStatusCopy.freshness(System.currentTimeMillis() - latest.capturedAt)
                else "Sin lectura válida"
                tileMetas.forEach { it?.text = freshnessLabel }
                patientVitalsStatus?.let { view ->
                    val statusCopy = TzindaStatusCopy.vitals(hasFreshReading, latest.capturedAt, arduinoConnectionAvailable())
                    view.text = "${statusCopy.title}. ${statusCopy.detail}"
                }
            }
        }
    }
    private fun caregiverHeader(body: LinearLayout, title: String, detail: String) {
        val heading = FrameLayout(this)
        val labels = column()
        labels.addView(v5.label("AXTARGET CARE / CUIDADOR").apply { setPadding(dp(2), dp(7), 0, 0) })
        gap(labels, 25)
        labels.addView(v5.title(title, 33))
        gap(labels, 7)
        labels.addView(v5.text(detail, 13, muted))
        heading.addView(labels, FrameLayout.LayoutParams(-1, -2))
        heading.addView(v5.iconButton(R.drawable.v5_settings, "Ajustes", fg = if (dark) accent else null,
            glyphSize = 27, action = ::openSettings).apply { translationY = -dp(2).toFloat() },
            FrameLayout.LayoutParams(dp(44), dp(44), Gravity.TOP or Gravity.END))
        body.addView(heading)
        gap(body, 32)
    }

    private fun caregiverLinkAvailable(): Boolean =
        store.prefs.getString("caregiver_link_id", "").orEmpty().isNotBlank() &&
            store.prefs.getString("caregiver_owner_id", "").orEmpty().isNotBlank()

    private fun caregiverMessage(
        body: LinearLayout,
        title: String,
        detail: String,
        icon: Int,
        height: Int,
        margin: Int,
    ) {
        val card = v5.panel(body, tz.brandSoft, radius = 23, padding = 0, margin = margin)
        val row = v5.horizontal().apply {
            minimumHeight = tz.controlScale(height)
            setPadding(dp(17), dp(5), dp(14), dp(16))
        }
        row.addView(v5.icon(icon, if (height > 60) 34 else 22, accent))
        row.addView(column().apply {
            addView(v5.text(title, if (height > 60) 17 else 13, accent, bold = true))
            gap(this, 6)
            addView(v5.text(detail, if (height > 60) 12 else 11, muted))
        }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(13) })
        card.addView(row)
    }

    private fun caregiverHome(body: LinearLayout) {
        refreshCaregiverSharedData()
        val linked = caregiverLinkAvailable()
        val person = store.prefs.getString("caregiver_shared_name", "").orEmpty().ifBlank { "Persona protegida" }
        caregiverHeader(body, "Tu círculo", "Cuidar se siente más cerca.")
        val hero = v5.panel(body, v5.darkHero, radius = 28, padding = 18, margin = 28).apply {
            setPadding(paddingLeft, paddingTop, paddingRight, dp(9))
        }
        val row = v5.horizontal()
        row.addView(column().apply {
            addView(v5.label("CUIDAR ES CONECTAR", v5.onBlueMuted))
            gap(this, 18)
            addView(v5.title("Cerca, incluso\na distancia.", 28, Color.WHITE))
        }, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(v5.art(R.drawable.v8_art_circle), LinearLayout.LayoutParams(dp(97), dp(96)).apply { gravity = Gravity.TOP })
        hero.addView(row)
        gap(hero, 13)
        hero.addView(v5.text(if (linked) "Acompañas a $person." else "Conecta con la persona que quieres cuidar.", 12, v5.onBlueMuted))
        gap(hero, 19)
        v5.action(hero, if (linked) "Revisar vínculo" else "Vincular persona", R.drawable.v5_link,
            Color.WHITE, Color.BLACK, margin = 0) { selectTab(if (linked) SUPERVISION_TAB else TzindaTabs.CONTACTS) }

        v5.section(body, "Tu acompañamiento", titleSize = 19)
        val support = v5.panel(body, padding = 0, margin = 22)
        v5.row(support, "Alertas compartidas", "Avisos y seguimiento del vínculo", R.drawable.v5_bell,
            minHeight = 69, divider = true, titleSize = 14, detailSize = 12) {
            selectTab(TzindaTabs.ACTIVITY)
        }
        v5.row(support, "Salud compartida", "Lecturas y tomas autorizadas", R.drawable.v5_pulse, tz.criticalSurface,
            tz.onCriticalSurface, minHeight = 69, divider = true, titleSize = 14, detailSize = 12) { selectTab(MEDICATIONS_TAB) }
        v5.row(support, "Supervisión y permisos", "Estado de la conexión y los datos", R.drawable.v5_supervision,
            tz.mint, tz.positive, minHeight = 69, titleSize = 14, detailSize = 12, action = ::openSupervisionCenter)
        caregiverMessage(body, "Solo lo que decida compartir.", "La persona conserva el control de sus datos.",
            R.drawable.v5_shield, height = 57, margin = 0)
    }

    private fun caregiverHealth(body: LinearLayout) {
        refreshCaregiverSharedData(renderWhenDone = true)
        val linked = caregiverLinkAvailable()
        caregiverHeader(body, "Salud compartida", "Acompaña su bienestar con información.")
        caregiverMessage(body, "La persona decide", "Solo verás los datos que autorice compartir.",
            R.drawable.v5_shield, height = 86, margin = 18)
        fun measurements() = if (caregiverLinkAvailable()) runCatching { JSONArray(store.prefs.getString("caregiver_shared_measurements", "[]")) }
            .getOrDefault(JSONArray()) else JSONArray()
        val shared = measurements()
        val snapshot = vitalSnapshot(true, shared)
        val tileUpdates = mutableListOf<(VitalSnapshot) -> Unit>()
        fun tile(pulse: Boolean): LinearLayout {
            val color = if (pulse) tz.onCriticalSurface else accent
            val fill = if (pulse) tz.criticalSurface else tz.brandSoft
            val value = if (pulse) snapshot.pulse else snapshot.oxygen
            return column().apply {
                minimumHeight = tz.controlScale(181)
                background = rounded(fill, 26)
                setPadding(dp(16), dp(17), dp(16), dp(13))
                addView(v5.icon(if (pulse) R.drawable.v5_pulse else R.drawable.v5_oxygen, 26, color))
                gap(this, 8)
                addView(v5.text(if (pulse) "Frecuencia\ncardíaca" else "Oxígeno\nen sangre", 15, color, bold = true))
                gap(this, 6)
                val reading = v5.horizontal().apply { gravity = Gravity.BOTTOM }
                val number = v5.text(value, 37, bold = true)
                reading.addView(number)
                reading.addView(v5.text(if (pulse) "lpm" else "SpO₂ · %", 12, muted).apply { setPadding(dp(11), 0, 0, dp(4)) })
                addView(reading)
                gap(this, 10)
                val caption = v5.text(if (value == "—") "Sin datos compartidos" else "Última lectura compartida", 11, muted)
                addView(caption)
                tileUpdates.add { fresh ->
                    val readingValue = if (pulse) fresh.pulse else fresh.oxygen
                    number.text = readingValue
                    caption.text = if (readingValue == "—") "Sin datos compartidos" else "Última lectura compartida"
                }
            }
        }
        v5.twoColumns(body, tile(true), tile(false))
        gap(body, 15)
        v5.section(body, "Cuidados compartidos", titleSize = 19)
        val meds = v5.panel(body, padding = 0, margin = 14)
        val owner = store.prefs.getString("caregiver_owner_id", "").orEmpty()
        val sharedCount = if (linked) medicationRepository.database.medications(owner)
            .count { it.shareWithCaregiver && !it.isDeleted } else 0
        v5.row(meds, "Medicamentos y tomas",
            if (sharedCount == 0) "Sin registros autorizados" else TzindaText.count(sharedCount, "medicamento compartido", "medicamentos compartidos"),
            R.drawable.v5_pill, tz.mint, tz.positive, minHeight = 75, inset = 16, badgeSize = 42, emphasis = true, detailSize = 12)
        meds.addView(v5.text("Los horarios los administra la persona protegida.", 11, muted).apply {
            setPadding(dp(17), 0, dp(14), dp(13))
        })
        v5.activate(meds, ::openMedicationScreen)
        v5.note(body, "Podrás registrar tomas cuando\nla persona habilite ese permiso.", margin = 18,
            fill = tz.cardColor, padding = 10)
        v5.action(body, "Ver permisos del vínculo", R.drawable.v5_lock, tz.cardColor, accent, margin = 0, action = ::openSupervisionCenter)
        if (linked && shared.length() > 0) {
            gap(body, 22)
            v5.section(body, "Historial de lecturas")
            addVitalSummary(v5.panel(body), caregiver = true)
        }
        val refreshHistory = caregiverVitalsUpdater
        caregiverVitalsUpdater = {
            val fresh = vitalSnapshot(true, measurements())
            tileUpdates.forEach { it(fresh) }
            refreshHistory?.invoke()
        }
    }

    private fun supervisionCenter(body: LinearLayout) {
        refreshCaregiverSharedData()
        val linked = caregiverLinkAvailable()
        val name = store.prefs.getString("caregiver_shared_name", "").orEmpty().ifBlank { "Persona protegida" }
        caregiverHeader(body, "Supervisión", "El vínculo y sus permisos, con claridad.")
        val summary = v5.panel(body, tz.primaryFill, radius = 28, padding = 18, margin = 18)
        summary.addView(v5.label("ESTADO DEL VÍNCULO", v5.onBlueMuted))
        gap(summary, 12)
        summary.addView(v5.title(if (linked) name else "Tu círculo\naún no está conectado.", 22, Color.WHITE))
        gap(summary, 16)
        summary.addView(v5.text(if (linked) "Vínculo activo con la persona protegida." else "Vincula a tu persona para comenzar.",
            12, v5.onBlueMuted))
        v5.action(body, if (linked) "Administrar vínculo" else "Vincular persona", R.drawable.v5_link, margin = 22) {
            selectTab(TzindaTabs.CONTACTS)
        }
        body.addView(v5.title("Datos autorizados", 19))
        gap(body, 8)
        body.addView(v5.text(if (linked) "Información compartida por la persona protegida" else "Pendientes de un vínculo activo", 12, muted))
        gap(body, 21)
        val access = v5.panel(body, padding = 0, margin = 12)
        val hasLocation = linked && sharedMapFix() != null
        val deviceCount = if (linked) sharedDevices().size else 0
        val records = if (linked) sharedCareRecords() else emptyList()
        v5.row(access, "Ubicación compartida", if (hasLocation) "Ubicación disponible" else "Sin información autorizada",
            R.drawable.v5_pin, minHeight = 66, divider = true, titleSize = 14, detailSize = 12, action = ::openSharedMap)
        v5.row(access, "Pulsera y dispositivos", if (deviceCount == 0) "Sin datos compartidos" else TzindaText.count(deviceCount, "dispositivo"),
            R.drawable.v5_watch, minHeight = 66, divider = true, titleSize = 14, detailSize = 12)
        v5.row(access, "Cuidados y bitácora", if (records.isEmpty()) "Sin registros compartidos" else TzindaText.count(records.size, "registro compartido", "registros compartidos"),
            R.drawable.v5_journal, tz.mint, tz.positive, minHeight = 67, titleSize = 14, detailSize = 12,
            action = if (linked) ::openCareFeatures else null)
        val consent = v5.panel(body, tz.mint, radius = 20, padding = 13, margin = 0)
        val note = v5.horizontal()
        note.addView(v5.icon(R.drawable.v5_lock, 22, tz.positive))
        note.addView(v5.text("Los permisos los administra\nla persona protegida.", 12, tz.positive),
             LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(14) })
        consent.addView(note)
        if (!linked) return
        gap(body, 24)
        val person = store.prefs.getString("caregiver_shared_name", "").orEmpty().ifBlank { "Persona protegida" }
        val events = sharedEvents()
        val pending = pendingSharedAlerts(events)
        val devices = sharedDevices()
        val fetchedAt = store.prefs.getLong("caregiver_shared_fetched_at", 0L)
        tz.section(body, "Persona vinculada", null, "Actualizar") { refreshCaregiverSharedData(force = true, renderWhenDone = true) }
        val hero = card(body)
        val personRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        personRow.addView(text(initials(person), 18, true, tz.onAccent).apply {
            gravity = Gravity.CENTER
            background = tz.rounded(tz.primaryFill, 999)
            minWidth = dp(56)
            minHeight = dp(56)
        }, LinearLayout.LayoutParams(dp(56), dp(56)))
        personRow.addView(column().apply {
            addView(text(person, 20, true, ink))
            addView(statusPill(TzindaStatusCopy.link(true, person, fetchedAt)))
            addView(text("Última sincronización: ${if (fetchedAt > 0) TzindaStatusCopy.freshness(System.currentTimeMillis() - fetchedAt) else "sin datos todavía"}.", 13, color = muted))
        }, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(12) })
        hero.addView(personRow)
        val sharedMeasurements = runCatching { JSONArray(store.prefs.getString("caregiver_shared_measurements", "[]")) }.getOrDefault(JSONArray())
        val snapshot = vitalSnapshot(true, sharedMeasurements)
        val metrics = tz.twoColumns(hero)
        tz.metricTile(metrics, "Ritmo cardíaco", snapshot.pulse, "lpm", if (snapshot.pulse == "—") "Sin lectura" else "Última lectura", if (snapshot.pulse == "—") TzindaDesign.Tone.NEUTRAL else TzindaDesign.Tone.SUCCESS)
        val alertsTile = tz.metricTile(metrics, "Alertas hoy", pending.size.toString(), "", if (pending.isEmpty()) "Sin pendientes" else "Pendientes", if (pending.isEmpty()) TzindaDesign.Tone.SUCCESS else TzindaDesign.Tone.WARNING)
        (alertsTile.layoutParams as LinearLayout.LayoutParams).leftMargin = dp(8)

        if (pending.isNotEmpty()) {
            tz.section(body, "Alertas que requieren seguimiento", null, "Historial") { selectTab(1) }
            val alerts = card(body)
            pending.take(3).forEachIndexed { index, event ->
                val followup = sharedAlertFollowups()
                    .filter { it.optString("fall_event_id") == event.optString("id") }
                    .maxByOrNull { it.optString("created_at") }
                val detail = buildString {
                    append(TzindaStatusCopy.classification(event.optString("classification")))
                    append(" · ")
                    append(formatSharedMoment(event.optString("occurred_at")))
                    if (followup != null) {
                        append(" · Seguimiento: ")
                        append(TzindaStatusCopy.confirmation(followup.optString("status")))
                    } else append(" · Sin seguimiento registrado")
                }
                tz.actionRow(
                    alerts,
                    TzindaStatusCopy.source(event.optString("source")),
                    detail,
                    leadingIcon = R.drawable.ic_alert,
                    trailingText = TzindaStatusCopy.confirmation(event.optString("confirmation_status")),
                    showDivider = index < minOf(pending.size, 3) - 1,
                ) { recordAlertFollowup(event.optString("id")) }
            }
            alerts.addView(button("Abrir historial completo", false) { selectTab(1) })
        }

        tz.section(body, "Diagnóstico y sensores")
        val deviceCard = card(body)
        if (devices.isEmpty()) {
            deviceCard.addView(text("No hay equipos registrados en la cuenta vinculada.", 14, color = muted))
        } else devices.forEachIndexed { index, device ->
            val copy = sharedDeviceCopy(device)
            val last = deviceLastSignal(device)
            val lastText = if (last > 0L) "Última señal ${TzindaStatusCopy.freshness(System.currentTimeMillis() - last)}" else "Sin señal registrada"
            tz.actionRow(
                deviceCard,
                device.optString("name").ifBlank { "Dispositivo" },
                device.optString("device_type").replace('_', ' ').replaceFirstChar { it.uppercase() } + " · " + lastText,
                leadingIcon = R.drawable.ic_bluetooth,
                // El título completo no cabe junto al nombre del equipo; el detalle ya vive en la fila.
                trailingText = copy.title.substringBefore(" ·"),
                statusTone = designTone(copy.tone),
                showDivider = index < devices.size - 1,
            ) { openDeviceLinkScreen() }
        }

        tz.section(body, "Ubicación y seguridad")
        addSharedLocationCard(body)
        vitalsUnavailableCard(body, caregiver = true)
        addSharedPhoneMotionCard(body)

        tz.section(body, "Bitácora y acciones")
        val actions = tz.twoColumns(body)
        actions.addView(bitacoraTile(R.drawable.ic_book, "Añadir nota", "Observación médica") { openCareFeatures() },
            LinearLayout.LayoutParams(0, -2, 1f))
        actions.addView(bitacoraTile(R.drawable.ic_profile, "Contactos", "Red de apoyo SOS") { selectTab(2) },
            LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(8) })
        val moreCard = card(body)
        tz.actionRow(moreCard, "Medicamentos compartidos", "Consultar y registrar tomas", R.drawable.ic_medication) { openMedicationScreen() }
        tz.actionRow(moreCard, "Persona vinculada", "Administrar o desvincular", R.drawable.ic_profile, showDivider = false) { selectTab(2) }
        addCaregiverSyncControls(body)
    }

    /** Square shortcut tile from the caregiver mockup (icon + title + subtitle). */
    private fun bitacoraTile(iconRes: Int, titleText: String, subtitle: String, action: () -> Unit): LinearLayout =
        column().apply {
            background = tz.rounded(tz.cardColor, 20).apply { setStroke(dp(1), outline) }
            setPadding(dp(15), dp(15), dp(15), dp(15))
            addView(tz.iconBadge(iconRes, TzindaDesign.Tone.BRAND, size = 40), LinearLayout.LayoutParams(dp(40), dp(40)))
            addView(text(titleText, 17, true, ink).apply { setPadding(0, dp(10), 0, 0) })
            addView(text(subtitle, 13, color = muted))
            isClickable = true
            isFocusable = true
            setOnClickListener { action() }
        }

    private fun careFeaturesScreen(body: LinearLayout) {
        v5.header(body, "Bitácora de cuidados", "Un lugar para tu seguimiento diario.",
            settings = { selectTab(TzindaTabs.CARE_SETTINGS) }, gapBelow = 30)
        v5.segments(body, listOf("Bitácora", "Rutinas", "Tareas"), careSection) {
            careSection = it
            lastRenderKey = null
            render()
        }
        gap(body, 22)
        when (careSection) {
            0 -> careJournal(body)
            1 -> careRoutines(body, tasksOnly = false)
            2 -> careRoutines(body, tasksOnly = true)
        }
    }

    private fun careJournal(body: LinearLayout) {
        val journalRowsAll = if (isCaregiver) sharedCareRecords().filter { it.optString("record_kind") == "journal" }
            .mapNotNull { it.optJSONObject("record") } else careFeatures.rows("journal")
        if (journalRowsAll.isEmpty()) {
            val empty = v5.panel(body, radius = 28, padding = 24, margin = 24).apply { gravity = Gravity.CENTER_HORIZONTAL }
            gap(empty, 11)
            empty.addView(v5.art(R.drawable.v5_art_note), LinearLayout.LayoutParams(dp(105), dp(100)))
            gap(empty, 23)
            empty.addView(v5.title("Cada cuidado\ncuenta.", 26).apply { gravity = Gravity.CENTER })
            gap(empty, 21)
            empty.addView(v5.text(if (isCaregiver) "Aún no hay notas compartidas." else "Aún no hay notas de cuidado.", 13, muted).apply {
                gravity = Gravity.CENTER
            })
            gap(empty, 24)
        } else {
            val journalCard = v5.panel(body)
            journalCard.addView(button("Filtrar: ${careJournalFilter.ifBlank { "todos los tipos" }}", false) {
                val available = arrayOf("Todos", "Observación", "Actividad", "Incidencia", "Cambio relevante", "Seguimiento de alerta", "Entrada a zona", "Salida de zona", "Rutina realizada")
                AlertDialog.Builder(this).setTitle("Filtrar registros").setItems(available) { _, choice ->
                    careJournalFilter = when (choice) { 0 -> ""; 6 -> "zona_entrada"; 7 -> "zona_salida"; else -> available[choice].lowercase(Locale.ROOT).replace(' ', '_') }
                    render()
                }.show()
            })
            val journalRows = journalRowsAll.filter { careJournalFilter.isBlank() || it.optString("type") == careJournalFilter }
            if (journalRows.isEmpty()) {
                journalCard.addView(text("Aún no hay registros en este filtro.", 14, color = muted))
            } else journalRows.take(30).forEachIndexed { index, row ->
                val timestamp = row.optLong("occurredAt", row.optLong("updatedAt"))
                val date = if (timestamp > 0) SimpleDateFormat("d MMM yyyy · HH:mm", Locale("es", "MX")).format(Date(timestamp)) else "Fecha no disponible"
                val comment = row.optString("comment").takeIf { it.isNotBlank() }?.let { "\n$it" }.orEmpty()
                journalCard.addView(text(row.optString("type", "Observación").replace('_', ' ').replaceFirstChar { it.uppercase() }, 16, true).apply {
                    setPadding(0, dp(10), 0, 0)
                })
                journalCard.addView(text("${row.optString("description")}$comment\n$date", 14, color = muted))
                journalCard.addView(statusPill(TzindaCopy(
                    if (row.optString("syncState") == "synced") "Sincronizado" else "Pendiente de sincronizar",
                    if (row.optString("syncState") == "synced") "Guardado en este teléfono y en la cuenta." else "Guardado en este teléfono; se enviará al recuperar conexión.",
                    if (row.optString("syncState") == "synced") TzindaTone.POSITIVE else TzindaTone.WARNING,
                )))
                if (index < journalRows.take(30).size - 1) tz.divider(journalCard)
            }


        }
        if (!isCaregiver) {
            v5.action(body, "Agregar nota", R.drawable.ic_plus, margin = 32, action = ::createJournalEntry)
            val tip = v5.panel(body, tz.brandSoft, padding = 0, margin = 18)
            v5.row(tip, "Siempre a mano", "Guarda tus notas en el teléfono.\nSe sincronizan cuando vuelve Internet.",
                R.drawable.v5_sync, minHeight = 96, titleColor = accent, emphasis = true)
            body.addView(v5.text("Tú eliges qué notas compartir.", 12, muted))
            gap(body, 16)
        }
    }

    private fun mapZones(): List<MapZone> {
        if (isCaregiver && CaregiverAccess(store).snapshot() == null) return emptyList()
        val remote = if (isCaregiver) sharedCareRecords().filter { it.optString("record_kind") == "zone" }
            .mapNotNull { it.optJSONObject("record") } else emptyList()
        return (careFeatures.zones() + remote).distinctBy { it.optString("id") }.mapNotNull(MapZone::from)
    }

    private fun storedMapFix(): MapFix? {
        val latitude = store.prefs.getString("latitude", "").orEmpty().toDoubleOrNull() ?: return null
        val longitude = store.prefs.getString("longitude", "").orEmpty().toDoubleOrNull() ?: return null
        val point = MapPoint.of(latitude, longitude) ?: return null
        return MapFix(point, store.prefs.getLong("location_time", 0L))
    }

    private fun requestMapFix(result: (MapFix?) -> Unit) {
        if (!hasLocation()) {
            if (mapLocationPermission == null) {
                mapLocationPermission = result
                requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                    REQUEST_MAP_LOCATION)
            }
            return
        }
        cancelMapFix?.invoke()
        val location = mapLocation.value
        location.latest()?.let { result(it); return }
        fun finish(fix: MapFix?) {
            cancelMapFix?.invoke()
            result(fix)
        }
        val stop = location.observe { location.latest()?.let(::finish) }
        val cached = location.latest()
        if (cached != null || stop == null) { stop?.invoke(); result(cached); return }
        val timeout = Runnable { finish(null) }
        cancelMapFix = {
            stop()
            handler.removeCallbacks(timeout)
            cancelMapFix = null
        }
        handler.postDelayed(timeout, 10_000)
    }

    private fun hasSharedMapCache(): Boolean {
        val scope = CaregiverAccess(store).snapshot() ?: return false
        return store.prefs.getString("caregiver_cache_owner_id", "") == scope.owner &&
            store.prefs.getString("caregiver_cache_link_id", "") == scope.link
    }

    private fun sharedMapFix(): MapFix? {
        if (!hasSharedMapCache()) return null
        val location = runCatching { JSONObject(store.prefs.getString("caregiver_shared_location", "{}").orEmpty()) }.getOrNull() ?: return null
        val point = MapPoint.from(location) ?: return null
        val captured = isoMillis(location.optString("captured_at"))
        val accuracy = location.optDouble("accuracy_m").takeIf { it.isFinite() && it >= 0 }
        return MapFix(point, captured, accuracy).takeIf { it.isRecent() }
    }

    private fun sharedMapPerson(): MapPerson {
        val name = store.prefs.getString("caregiver_shared_name", "").orEmpty()
            .takeIf { hasSharedMapCache() && it.isNotBlank() } ?: "Persona protegida"
        val fix = sharedMapFix()
        val zone = fix?.let { position ->
            mapZones().filter(MapZone::enabled).minByOrNull { item ->
                GeoFencePolicy.distanceMeters(position.point.latitude, position.point.longitude, item.point.latitude, item.point.longitude)
            }
        }
        return MapPerson(name, fix, zone)
    }

    private fun newLocationMap(): TzindaV11 {
        val account = store
        val role = store.role()
        val expectedLink = if (isCaregiver) CaregiverAccess(account).snapshot() else null
        locationMap?.close()
        return TzindaV11(this, v5, mapService.value, mapLocation.value, ::requestMapFix,
            sharedFix = if (isCaregiver) ::sharedMapFix else null) {
            store === account && AccountStorage.isCurrent(account.appContext) && store.role() == role &&
                (!isCaregiver || CaregiverAccess(account).snapshot() == expectedLink)
        }.also { locationMap = it }
    }

    private fun openSafeZones() {
        if (isCameraRole) return
        drawSafeZones(newLocationMap())
    }

    private fun drawSafeZones(map: TzindaV11, back: () -> Unit = map::close) {
        if (isCaregiver) refreshCaregiverSharedData()
        val account = store
        val owner = careFeatures.currentOwner()?.takeIf { !isCaregiver || CaregiverAccess(store).snapshot() != null }
        val write: ((MapZone) -> Boolean)? = owner?.let { expectedOwner ->
            { zone ->
                val saved = store === account && AccountStorage.isCurrent(account.appContext) &&
                    careFeatures.currentOwner() == expectedOwner && persistCare("zone", zone.record(expectedOwner))
                if (saved) SyncScheduler.enqueue(applicationContext, immediate = true)
                saved
            }
        }
        val delete: ((MapZone) -> Boolean)? = owner?.let { expectedOwner ->
            { zone ->
                val removed = store === account && AccountStorage.isCurrent(account.appContext) &&
                    careFeatures.currentOwner() == expectedOwner && runCatching { careFeatures.delete("zone", zone.id, expectedOwner) }
                    .onFailure { toast(it.message ?: "No se pudo eliminar la zona.", true) }.isSuccess
                if (removed) SyncScheduler.enqueue(applicationContext, immediate = true)
                removed
            }
        }
        map.zones(::mapZones, write, delete, back,
            readFix = if (isCaregiver) ::sharedMapFix else mapLocation.value::latest, shared = isCaregiver)
    }

    private fun retryMapLocation(complete: () -> Unit) {
        refreshCaregiverSharedData(force = true, completed = complete)
    }

    private fun openSharedMap(event: LocationAlert? = null) {
        if (!isCaregiver) return
        val map = newLocationMap()
        fun showPerson() {
            map.person(::sharedMapPerson, ::retryMapLocation, { drawSafeZones(map, ::showPerson) })
        }
        if (event == null || event.type == LocationAlertType.ARRIVAL) showPerson()
        else map.incident(::sharedMapPerson, event, ::retryMapLocation)
    }

    private fun careRoutines(body: LinearLayout, tasksOnly: Boolean) {
        val routines = card(body)
        routines.addView(text(if (tasksOnly) "Tareas programadas para hoy" else "Rutinas", 20, true))
        if (!isCaregiver && !tasksOnly) routines.addView(button("Crear rutina", false) { createRoutine() })
        val routineRows = if (isCaregiver) sharedCareRecords().filter { it.optString("record_kind") == "routine" }.mapNotNull { it.optJSONObject("record") }
            else careFeatures.rows("routine")
        var shown = 0
        if (routineRows.isEmpty()) {
            routines.addView(text("Sin rutinas registradas. La app solo mostrará actividades marcadas por la persona.", 14, color = muted))
        } else routineRows.forEach routineLoop@{ routine ->
            val repeatDays = routine.optJSONArray("days")
            val days = if (repeatDays == null || repeatDays.length() == 0) emptySet() else (0 until repeatDays.length()).mapNotNull { repeatDays.optInt(it).takeIf { day -> day in 1..7 } }.toSet()
            val todayMinute = java.time.LocalTime.now().let { it.hour * 60 + it.minute }
            val routineMinute = runCatching { java.time.LocalTime.parse(routine.optString("time")).let { it.hour * 60 + it.minute } }.getOrNull()
            val due = routineMinute?.let { RoutinePolicy.dueToday(days, it, todayMinute) } == true
            if (tasksOnly && !due) return@routineLoop
            shown++
            routines.addView(text(routine.optString("name"), 16, true).apply { setPadding(0, dp(10), 0, 0) })
            routines.addView(text("${routine.optString("time", "hora no definida")} · Días: ${days.sorted().joinToString(", ").ifBlank { "sin horario semanal" }}", 13, color = muted))
            if (due) routines.addView(statusPill(TzindaCopy("Programada hoy", "La persona puede marcarla como realizada.", TzindaTone.INFO)))
            if (!isCaregiver) routines.addView(button("Marcar como realizada", false) {
                if (!persistCare("journal", JSONObject().put("ownerUserId", routine.optString("ownerUserId")).put("type", "rutina_realizada").put("description", routine.optString("name"))
                    .put("occurredAt", System.currentTimeMillis()).put("source", "user_action").put("sharedWithCaregiver", routine.optBoolean("sharedWithCaregiver")))) return@button
                SyncScheduler.enqueue(applicationContext); toast("Actividad registrada."); render()
            })
        }
        if (tasksOnly && routineRows.isNotEmpty() && shown == 0) routines.addView(text("Aún no tienes tareas para hoy.", 14, color = muted))
    }

    private fun careSettings(body: LinearLayout) {
        val linkedCaregiver = isCaregiver && store.prefs.getString("caregiver_link_id", "").orEmpty().isNotBlank()
        heading(body, "CUIDADOS Y ACTIVIDAD", "Ajustes de cuidados", "Registros guardados primero en este teléfono y sincronizados al volver a tener Internet.")
        val careState = careFeatures.syncState()
        val pending = careState.pending
        val sync = card(body)
        sync.addView(text("Sincronización", 20, true))
        val syncCopy = TzindaStatusCopy.sync(pending, careState.failed, hasValidatedInternet(), sessions.read() != null)
        sync.addView(statusPill(syncCopy))
        sync.addView(text(if (isCaregiver) "Vista de solo lectura de los registros compartidos. No puedes crear ni modificar rutinas." else syncCopy.detail, 14, color = muted).apply { setPadding(0, dp(8), 0, 0) })
        val unassigned = careFeatures.unassignedCount()
        if (unassigned > 0) {
            sync.addView(text("Hay $unassigned registros o borrados antiguos sin propietario confirmado. Se conservan en este teléfono y no se asignan automáticamente.", 14, color = muted))
            val actor = sessions.read()?.userId
            if (!isCaregiver && !isCameraRole && !actor.isNullOrBlank() && actor == store.ownerUserId()) {
                sync.addView(button("Revisar registros antiguos", false) { showUnassignedCareRecovery() })
            } else {
                sync.addView(text("Para revisarlos, inicia sesión con la cuenta propietaria en modo Persona protegida.", 13, color = muted))
            }
        }
        if (!isCaregiver) {
            sync.addView(button("Sincronizar ahora", false) {
                SyncScheduler.enqueue(applicationContext, immediate = true)
                toast("Se intentará sincronizar en segundo plano.")
                render()
            })
            tz.switchRow(
                sync,
                "Detectar entradas y salidas de zonas",
                "Usa ubicaciones válidas obtenidas con permiso. No garantiza localización continua.",
                store.prefs.getBoolean("safe_zones_enabled", false),
                action = { enabled ->
                    store.prefs.edit().putBoolean("safe_zones_enabled", enabled).apply()
                    updateServiceIfActive()
                    if (enabled) {
                        if (hasLocation()) requestLocation()
                        else requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), REQUEST_LOCATION)
                    } else toast("Seguimiento de zonas pausado.")
                },
            )
            sync.addView(button("Actualizar ubicación ahora", false) { requestLocation() })
        }


        v5.action(body, "Volver a Bitácora", fill = tz.cardColor, fg = accent) { navigateBack() }
    }

    private fun sharedCareRecords(): List<JSONObject> {
        if (isCaregiver && (store.prefs.getString("caregiver_link_id", "").orEmpty().isBlank() || store.prefs.getString("caregiver_owner_id", "").orEmpty().isBlank())) return emptyList()
        val array = runCatching { JSONArray(store.prefs.getString("caregiver_shared_care_records", "[]")) }.getOrDefault(JSONArray())
        val owner = store.prefs.getString("caregiver_owner_id", "").orEmpty()
        return (0 until array.length()).mapNotNull { array.optJSONObject(it) }.filter { it.optString("owner_user_id") == owner }.map { row ->
            row.optJSONObject("record")?.put("ownerUserId", owner)?.put("id", row.optString("id"))
            row
        }
    }

    private fun showUnassignedCareRecovery() {
        val actor = sessions.read()?.userId ?: run { toast("Inicia sesión con la cuenta propietaria.", true); return }
        if (isCaregiver || isCameraRole || actor != store.ownerUserId()) {
            toast("La revisión requiere la cuenta propietaria en modo Persona protegida.", true)
            return
        }
        val entries = careFeatures.unassignedEntries()
        if (entries.isEmpty()) { toast("No quedan registros antiguos por revisar."); render(); return }
        val labels = entries.map { entry ->
            val type = entry.kind.ifBlank { "sin tipo" }
            val action = if (entry.deletion) "Borrado pendiente" else type.replaceFirstChar { it.uppercase() }
            "$action · ${entry.summary} · …${entry.id.takeLast(8)}"
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Datos sin propietario")
            .setItems(labels) { _, index ->
                val entry = entries.getOrNull(index) ?: return@setItems
                val message = if (entry.deletion) {
                    "${entry.summary}\nID: ${entry.id}\n\nSi confirmas que pertenece a tu cuenta, la próxima sincronización volverá a intentar borrarlo del servidor."
                } else {
                    "${entry.summary}\nID: ${entry.id}\n\nSi confirmas que es tuyo, se asignará a esta cuenta y quedará pendiente de sincronización."
                }
                val detailDialog = AlertDialog.Builder(this)
                    .setTitle(if (entry.deletion) "Confirmar borrado pendiente" else "Confirmar propiedad")
                    .setMessage(message)
                    .setNegativeButton("Dejar sin asignar", null)
                val validId = runCatching { java.util.UUID.fromString(entry.id) }.isSuccess
                if (validId) detailDialog.setPositiveButton(if (entry.deletion) "Confirmar y reintentar" else "Asignar a mi cuenta") { _, _ ->
                        val claimed = runCatching { careFeatures.claimUnassignedEntry(entry, actor, actor) }.getOrDefault(false)
                        if (claimed) {
                            SyncScheduler.enqueue(applicationContext, immediate = true)
                            toast(if (entry.deletion) "Borrado asignado y reintento programado; revisa el estado de sincronización." else "Registro asignado; se sincronizará en segundo plano.")
                        } else toast("El registro cambió o no pudo guardarse. Sigue conservado en el teléfono.", true)
                        render()
                    }
                else detailDialog.setMessage("$message\n\nEste dato no tiene un identificador válido para sincronizar; se conservará sin asignar.")
                detailDialog.show()
            }
            .setNegativeButton("Cerrar", null)
            .show()
    }

    private fun sharedAlertFollowups(): List<JSONObject> {
        if (!isCaregiver || store.prefs.getString("caregiver_link_id", "").orEmpty().isBlank() || store.prefs.getString("caregiver_owner_id", "").orEmpty().isBlank()) return emptyList()
        val array = runCatching { JSONArray(store.prefs.getString("caregiver_shared_alert_followups", "[]")) }.getOrDefault(JSONArray())
        val remote = (0 until array.length()).mapNotNull { array.optJSONObject(it) }
        val local = careFeatures.rows("alert_followup").map { row ->
            JSONObject().put("id", row.optString("id")).put("fall_event_id", row.optString("fallEventId"))
                .put("status", row.optString("status")).put("reason", row.optString("reason"))
                .put("created_at", java.time.Instant.ofEpochMilli(row.optLong("occurredAt")).toString())
        }
        return (remote + local).distinctBy { it.optString("id") }
    }

    private fun recordAlertFollowup(eventId: String) {
        val formOwner = careFeatures.currentOwner() ?: return
        val options = arrayOf("Confirmada", "Atendida", "Descartada con motivo", "Cerrar seguimiento")
        AlertDialog.Builder(this).setTitle("Seguimiento de alerta").setItems(options) { _, selected ->
            val status = when (selected) { 0 -> "confirmed"; 1 -> "attended"; 2 -> "dismissed"; else -> "closed" }
            if (selected == 2) {
                val panel = column().apply { setPadding(dp(2), dp(4), dp(2), dp(4)) }
                val reason = field(
                    panel, "Motivo para descartar", "",
                    InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES,
                    tag = "alert_followup_reason", hintText = "Ej. Falsa alarma, la persona estaba bien",
                ).apply { minLines = 2; setSingleLine(false) }
                val dialog = AlertDialog.Builder(this).setTitle("Motivo requerido")
                    .setView(ScrollView(this).apply { addView(panel) })
                    .setNegativeButton("Cancelar", null).setPositiveButton("Guardar", null).create()
                dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                    val message = reason.text.toString().trim()
                    if (message.isBlank()) {
                        reason.error = "Escribe un motivo para descartar la alerta."
                        reason.requestFocus()
                        return@setOnClickListener
                    }
                    dialog.dismiss()
                    saveAlertFollowup(eventId, status, message, formOwner)
                } }
                dialog.show()
            } else saveAlertFollowup(eventId, status, "", formOwner)
        }.show()
    }

    private fun persistCare(kind: String, values: JSONObject): Boolean = runCatching {
        careFeatures.upsert(kind, values)
    }.onFailure { toast(it.message ?: "No se pudo guardar el cuidado. Vuelve a abrir la pantalla.", true) }.isSuccess

    private fun saveAlertFollowup(eventId: String, status: String, reason: String, expectedOwner: String) {
        if (careFeatures.currentOwner() != expectedOwner) { toast("La persona vinculada cambió. Vuelve a abrir su alerta.", true); return }
        val ownerId = store.prefs.getString("caregiver_owner_id", "").orEmpty()
        // The actor is filled from the active session when the follow-up is synced, so the state
        // can still be changed while the session is being renewed.
        val actorId = sessions.read()?.userId.orEmpty()
        if (ownerId.isBlank() || eventId.isBlank()) { toast("El vínculo o la alerta no están disponibles.", true); return }
        if (!persistCare("alert_followup", JSONObject().put("ownerUserId", expectedOwner).put("fallEventId", eventId).put("actorUserId", actorId)
            .put("status", status).put("reason", reason.take(500)).put("occurredAt", System.currentTimeMillis()))) return
        SyncScheduler.enqueue(applicationContext, immediate = true)
        toast(if (hasValidatedInternet()) "Estado de la alerta guardado; se sincronizará en unos segundos."
            else "Estado de la alerta guardado; se sincronizará cuando haya conexión.")
        render()
    }

    private fun createJournalEntry() {
        val formOwner = careFeatures.currentOwner() ?: return
        val types = arrayOf("Observación", "Actividad", "Incidencia", "Cambio relevante", "Seguimiento de alerta")
        AlertDialog.Builder(this).setTitle("Tipo de registro").setItems(types) { _, which ->
            val panel = column().apply { setPadding(dp(2), dp(4), dp(2), dp(4)) }
            panel.addView(text("El registro se guarda primero en este teléfono. Puedes compartirlo o mantenerlo privado.", 14, color = muted))
            val description = field(
                panel, "Descripción", "",
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES,
                tag = "journal_description", hintText = "Ej. Caminó al parque con su hija",
            ).apply { minLines = 2; setSingleLine(false) }
            val comment = field(
                panel, "Comentario asociado (opcional)", "",
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES,
                hintText = "Ej. Se mostró animada y sin molestias",
            ).apply { minLines = 2; setSingleLine(false) }
            val share = tz.switchRow(
                panel,
                "Compartir con cuidador/a",
                "Solo se comparte si el vínculo está activo.",
                value = false,
                action = {},
            )
            val dialog = AlertDialog.Builder(this).setTitle(types[which])
                .setView(ScrollView(this).apply { addView(panel) })
                .setNegativeButton("Cancelar", null).setPositiveButton("Guardar", null).create()
            dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val value = description.text.toString().trim()
                if (value.isBlank()) {
                    description.error = "Escribe una descripción."
                    description.requestFocus()
                    return@setOnClickListener
                }
                if (!persistCare("journal", JSONObject().put("ownerUserId", formOwner).put("type", types[which].lowercase(Locale.ROOT).replace(' ', '_'))
                    .put("description", value.take(1000)).put("occurredAt", System.currentTimeMillis())
                    .put("comment", comment.text.toString().trim().take(500))
                    .put("author", sessions.read()?.userId.orEmpty()).put("sharedWithCaregiver", share.isChecked))) return@setOnClickListener
            SyncScheduler.enqueue(applicationContext)
                dialog.dismiss()
                toast("Registro guardado en este teléfono.")
                render()
            } }
            dialog.show()
        }.show()
    }

    private fun createRoutine() {
        val formOwner = careFeatures.currentOwner() ?: return
        val panel = column().apply { setPadding(dp(2), dp(4), dp(2), dp(4)) }
        panel.addView(text("Una rutina es un recordatorio descriptivo. AxTarget Care no marca actividades como realizadas automáticamente.", 14, color = muted))
        val name = field(panel, "Nombre de la rutina", "", tag = "routine_name", hintText = "Ej. Caminata matutina")
        val description = field(panel, "Descripción (opcional)", "", hintText = "Ej. Caminar 20 minutos en el parque")
        val time = field(
            panel, "Hora", "09:00",
            InputType.TYPE_CLASS_DATETIME or InputType.TYPE_DATETIME_VARIATION_TIME,
            hintText = "Formato 24 h, ej. 09:00",
        )
        val days = field(
            panel, "Días de la semana", "1,2,3,4,5,6,7",
            tag = "routine_days", hintText = "1 = lunes … 7 = domingo",
            helper = "Escribe los días separados por coma.",
        )
        val share = tz.switchRow(
            panel,
            "Compartir con cuidador/a",
            "El cuidador vinculado podrá consultar la rutina, sin editarla.",
            value = false,
            action = {},
        )
        val dialog = AlertDialog.Builder(this).setTitle("Nueva rutina")
            .setView(ScrollView(this).apply { addView(panel) })
            .setNegativeButton("Cancelar", null).setPositiveButton("Guardar", null).create()
        dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val timeText = time.text.toString().trim()
            val dayValues = days.text.toString().split(',').mapNotNull { it.trim().toIntOrNull() }.toSet()
            var valid = true
            if (name.text.isBlank()) { name.error = "Escribe un nombre para la rutina."; valid = false }
            if (!Regex("^(?:[01]\\d|2[0-3]):[0-5]\\d$").matches(timeText)) { time.error = "Usa el formato HH:mm, por ejemplo 09:00."; valid = false }
            if (dayValues.isEmpty() || dayValues.any { it !in 1..7 }) { days.error = "Escribe días del 1 al 7 separados por coma."; valid = false }
            if (!valid) { tz.announce(name, "Revisa los campos marcados."); return@setOnClickListener }
            if (!persistCare("routine", JSONObject().put("ownerUserId", formOwner).put("name", name.text.toString().trim().take(100)).put("description", description.text.toString().trim().take(500))
                .put("time", timeText).put("days", JSONArray(dayValues.sorted())).put("sharedWithCaregiver", share.isChecked))) return@setOnClickListener
            SyncScheduler.enqueue(applicationContext)
            dialog.dismiss()
            toast("Rutina guardada en este teléfono.")
            render()
        } }
        dialog.show()
    }

    /** Refreshes the protected person's view of the caregiver account and its registered devices. */
    private fun refreshWearerLinkedData(force: Boolean = false) {
        if (isCaregiver || isCameraRole || sessions.read() == null || !backend.isConfigured) return
        val now = System.currentTimeMillis()
        if (wearerLinkRefreshInFlight || !force && now - wearerLinkRefreshAt < 30_000L) return
        wearerLinkRefreshInFlight = true
        wearerLinkRefreshAt = now
        io.execute {
            val result = runCatching {
                val links = backend.linkedCaregivers()
                JSONArray().apply {
                    for (index in 0 until links.length()) {
                        val link = links.optJSONObject(index) ?: continue
                        val caregiverId = link.optString("caregiver_user_id")
                        if (caregiverId.isBlank()) continue
                        val profile = runCatching { backend.sharedProfile(caregiverId) }.getOrNull()
                        val devices = runCatching { backend.linkedDevices(caregiverId) }.getOrDefault(JSONArray())
                        put(JSONObject().put("link_id", link.optString("id"))
                            .put("caregiver_id", caregiverId).put("name", profile?.optString("full_name").orEmpty())
                            .put("role", profile?.optString("role").orEmpty()).put("devices", devices))
                    }
                }
            }
            runOnUiThread {
                wearerLinkRefreshInFlight = false
                result.onSuccess { caregivers ->
                    store.prefs.edit().putString("wearer_linked_caregivers", caregivers.toString())
                        .putLong("wearer_linked_caregivers_fetched_at", System.currentTimeMillis()).apply()
                    if (tab == 0 || tab == DEVICE_TAB) render()
                }.onFailure {
                    if (tab == DEVICE_TAB) toast("No se pudo actualizar el vínculo: ${it.message ?: "sin conexión"}", true)
                }
            }
        }
    }

    /**
     * Fetches the linked person's shared data. Screen builds call it silently: rebuilding the
     * view tree from the network callback would wipe input and fight with the user's scroll.
     * Only explicit “Actualizar” actions ask for a visual re-render when the fetch finishes.
     */
    private fun refreshCaregiverSharedData(
        force: Boolean = false,
        renderWhenDone: Boolean = false,
        completed: (() -> Unit)? = null,
    ) {
        val requestStore = store
        val access = CaregiverAccess(requestStore)
        val scope = access.snapshot() ?: run {
            if (sessions.read() == null) caregiverSharedStatusView?.text = "Sesión no disponible: inicia sesión para actualizar los datos compartidos."
            completed?.invoke()
            return
        }
        val requestBackend = SupabaseBackend(sessions, expectedUserId = scope.actor)
        val now = System.currentTimeMillis()
        if (caregiverRefreshScope == scope && caregiverRefreshInFlight) {
            if (completed != null) caregiverMapRetry = caregiverRefreshRequest to completed
            return
        }
        if (caregiverRefreshScope == scope && !force && now - caregiverRefreshAt < 10_000L) {
            completed?.invoke()
            return
        }
        caregiverRefreshScope = scope
        val requestId = ++caregiverRefreshRequest
        caregiverMapRetry = completed?.let { requestId to it }
        caregiverRefreshInFlight = true
        caregiverRefreshAt = now
        // The first successful fetch may fill an empty screen, so it is safe to draw the result.
        // Later fetches stay silent to avoid rebuilding a screen the caregiver is using.
        val hadSharedData = store.prefs.getLong("caregiver_shared_fetched_at", 0L) > 0L
        io.execute {
            var revoked = false
            val result = runCatching {
                val links = requestBackend.linkedPeople()
                val stillLinked = (0 until links.length()).mapNotNull { links.optJSONObject(it) }
                    .firstOrNull { scope.matches(it) }
                if (stillLinked == null) {
                    revoked = true
                    error("El vínculo ya no está activo. Vuelve a vincular a la persona si desea compartir sus datos.")
                }
                val linkedOwnerId = scope.owner
                val linkedDeviceId = scope.device
                val profile = runCatching { requestBackend.sharedProfile(linkedOwnerId) }.getOrNull()
                val devices = runCatching { requestBackend.linkedDevices(linkedOwnerId) }.getOrDefault(JSONArray())
                val events = requestBackend.sharedEvents(linkedOwnerId, linkedDeviceId)
                val measurements = requestBackend.sharedMeasurements(linkedOwnerId, linkedDeviceId)
                val phoneMotion = runCatching { requestBackend.sharedPhoneMotion(linkedOwnerId, linkedDeviceId) }.getOrNull()
                val phoneDevices = (0 until devices.length()).mapNotNull { devices.optJSONObject(it) }
                    .filter { it.optString("device_type") == "phone" }
                    .sortedByDescending { dev ->
                        val syncStr = dev.optString("last_sync_at").takeIf { it.isNotBlank() && it != "null" }
                            ?: dev.optString("last_connected_at").takeIf { it.isNotBlank() && it != "null" }
                        syncStr?.let(::isoMillis) ?: 0L
                    }
                val locationDevice = linkedDeviceId ?: phoneDevices.firstOrNull()?.optString("id").orEmpty()
                val locationResult = locationDevice.takeIf { it.isNotBlank() }
                    ?.let { runCatching { requestBackend.sharedLocation(linkedOwnerId, it) } }
                val location = locationResult?.getOrNull()
                JSONObject().put("name", profile?.optString("full_name").orEmpty())
                    .put("devices", devices).put("events", events).put("measurements", measurements)
                    .put("phone_motion", phoneMotion ?: JSONObject.NULL)
                    .put("location", location ?: JSONObject.NULL).put("location_fetched", locationResult?.isSuccess == true).put("owner_id", linkedOwnerId)
                    .put("link_device_id", linkedDeviceId ?: "").put("device_id", locationDevice)
            }
            runOnUiThread {
                if (isDestroyed || requestId != caregiverRefreshRequest) return@runOnUiThread
                caregiverRefreshInFlight = false
                val mapRetry = caregiverMapRetry?.takeIf { it.first == requestId }?.second
                caregiverMapRetry = null
                if (store !== requestStore || !access.isCurrent(scope)) return@runOnUiThread
                result.onSuccess { data ->
                    val latestEvents = data.optJSONArray("events") ?: JSONArray()
                    val oldEvents = if (store.prefs.getString("caregiver_cache_owner_id", "") == scope.owner &&
                        store.prefs.getString("caregiver_cache_link_id", "") == scope.link) sharedEventsJson() else JSONArray()
                    val mergedEvents = mergeCaregiverEventPages(latestEvents, oldEvents)
                    if (!access.write(scope) {
                        putString("caregiver_shared_name", data.optString("name"))
                        putString("caregiver_shared_devices", data.optJSONArray("devices")?.toString() ?: "[]")
                        putString("caregiver_shared_events", mergedEvents.toString())
                        putString("caregiver_shared_measurements", data.optJSONArray("measurements")?.toString() ?: "[]")
                        putString("caregiver_location_device_id", data.optString("device_id"))
                        putString("caregiver_shared_status", "Actualizado · ${data.optJSONArray("measurements")?.length() ?: 0} lecturas y ${data.optJSONArray("events")?.length() ?: 0} eventos")
                        putLong("caregiver_shared_fetched_at", System.currentTimeMillis())
                        data.optJSONObject("phone_motion")?.let { putString("caregiver_shared_phone_motion", it.toString()) }
                            ?: remove("caregiver_shared_phone_motion")
                        if (data.optBoolean("location_fetched")) {
                            data.optJSONObject("location")?.let { putString("caregiver_shared_location", it.toString()) }
                                ?: remove("caregiver_shared_location")
                        }
                    }) return@onSuccess
                    ensureCaregiverLocationRealtime(data.optString("device_id"))
                    ensureCaregiverHealthRealtime(data.optString("owner_id"), data.optString("link_device_id").takeIf { it.isNotBlank() })
                    caregiverEventsHasMore = latestEventsPageCouldHaveMore(
                        latestEvents, oldEvents, mergedEvents, caregiverEventsHasMore,
                    )
                    if (renderWhenDone || !hadSharedData) render()
                    else {
                        caregiverLocationUpdater?.invoke()
                        caregiverSharedStatusView?.text = store.prefs.getString("caregiver_shared_status", "").orEmpty()
                    }
                }.onFailure {
                    if (revoked) {
                        access.clear(scope)
                        caregiverLocationRealtime?.stop(); caregiverLocationRealtime = null; caregiverLocationRealtimeDevice = ""
                        caregiverHealthRealtime?.stop(); caregiverHealthRealtime = null; caregiverHealthRealtimeKey = ""
                        toast("La persona dejó de compartir mediante este vínculo.")
                        render()
                        return@onFailure
                    }
                    access.write(scope) { putString("caregiver_shared_status", it.message?.takeIf(String::isNotBlank)
                        ?.let { message -> "No se pudo actualizar: $message" }
                        ?: "No se pudieron actualizar los datos compartidos.") }
                    if (renderWhenDone) render()
                    else caregiverSharedStatusView?.text = store.prefs.getString("caregiver_shared_status", "").orEmpty()
                }
                mapRetry?.invoke()
            }
        }
    }

    private fun latestEventsPageCouldHaveMore(
        page: JSONArray,
        cached: JSONArray,
        merged: JSONArray,
        previouslyHadMore: Boolean,
    ): Boolean = merged.length() < CAREGIVER_EVENT_CACHE_LIMIT &&
        (page.length() >= CAREGIVER_EVENT_PAGE_SIZE || cached.length() > page.length() && previouslyHadMore)

    private fun mergeCaregiverEventPages(latest: JSONArray, cached: JSONArray): JSONArray {
        val byId = LinkedHashMap<String, JSONObject>()
        listOf(latest, cached).forEach { rows ->
            for (index in 0 until rows.length()) {
                val row = rows.optJSONObject(index) ?: continue
                val id = row.optString("id").takeIf { it.isNotBlank() } ?: continue
                byId.putIfAbsent(id, row)
            }
        }
        return JSONArray().apply {
            byId.values.sortedWith(
                compareByDescending<JSONObject> { it.optString("occurred_at") }
                    .thenByDescending { it.optString("id") },
            ).take(CAREGIVER_EVENT_CACHE_LIMIT).forEach(::put)
        }
    }

    private fun sharedEventsJson(): JSONArray = runCatching {
        JSONArray(store.prefs.getString("caregiver_shared_events", "[]"))
    }.getOrDefault(JSONArray())

    private fun loadMoreCaregiverEvents() {
        if (caregiverEventsPageInFlight || !caregiverEventsHasMore) return
        val requestStore = store
        val access = CaregiverAccess(requestStore)
        val scope = access.snapshot() ?: return
        val offset = sharedEvents().size
        if (offset >= CAREGIVER_EVENT_CACHE_LIMIT) {
            caregiverEventsHasMore = false
            render()
            return
        }
        caregiverEventsPageInFlight = true
        io.execute {
            val result = runCatching {
                SupabaseBackend(sessions, expectedUserId = scope.actor).sharedEventPage(
                    scope.owner, scope.device, offset, CAREGIVER_EVENT_PAGE_SIZE,
                )
            }
            runOnUiThread {
                caregiverEventsPageInFlight = false
                if (store !== requestStore || !access.isCurrent(scope)) return@runOnUiThread
                result.onSuccess { page ->
                    val merged = mergeCaregiverEventPages(sharedEventsJson(), page)
                    if (access.write(scope) { putString("caregiver_shared_events", merged.toString()) }) {
                        caregiverEventsHasMore = page.length() >= CAREGIVER_EVENT_PAGE_SIZE &&
                            merged.length() < CAREGIVER_EVENT_CACHE_LIMIT
                        if (page.length() == 0) caregiverEventsHasMore = false
                        render()
                    }
                }.onFailure { failure ->
                    toast("No se pudieron cargar eventos anteriores: ${failure.message ?: "error de conexión"}", true)
                }
            }
        }
    }
    private fun ensureCaregiverLocationRealtime(deviceId: String) {
        val access = CaregiverAccess(store)
        val scope = access.snapshot() ?: return
        if (deviceId.isBlank() || caregiverLocationRealtimeDevice == deviceId) return
        caregiverLocationRealtime?.stop()
        caregiverLocationRealtimeDevice = deviceId
        caregiverLocationRealtime = CaregiverLocationRealtime(backend, sessions, onLocation = { location ->
            runOnUiThread {
                if (store.prefs.getString("caregiver_location_device_id", "").orEmpty() != deviceId) return@runOnUiThread
                if (!access.write(scope) {
                    if (location == null) remove("caregiver_shared_location")
                    else putString("caregiver_shared_location", location.toString())
                }) return@runOnUiThread
                caregiverLocationUpdater?.invoke()
            }
        }, onRealtimeError = { err ->
            runOnUiThread {
                if (!access.write(scope) { putString("caregiver_shared_status", "Aviso Realtime (ubicación): $err") }) return@runOnUiThread
                caregiverSharedStatusView?.text = store.prefs.getString("caregiver_shared_status", "").orEmpty()
            }
        }).also { it.start(deviceId) }
    }
    private fun ensureCaregiverHealthRealtime(ownerId: String, deviceId: String?) {
        val access = CaregiverAccess(store)
        val scope = access.snapshot() ?: return
        if (scope.owner != ownerId) return
        val key = "$ownerId:${deviceId.orEmpty()}"
        if (ownerId.isBlank() || caregiverHealthRealtimeKey == key) return
        caregiverHealthRealtime?.stop()
        caregiverHealthRealtimeKey = key
        caregiverHealthRealtime = CaregiverHealthRealtime(
            backend,
            sessions,
            onMeasurement = { mergeCaregiverMeasurement(it, scope) },
            onFallEvent = { runOnUiThread { if (access.isCurrent(scope)) refreshCaregiverSharedData(force = true, renderWhenDone = true) } },
            onPhoneMotion = { mergeCaregiverPhoneMotion(it, scope) },
            onRealtimeError = { err -> runOnUiThread { access.write(scope) { putString("caregiver_shared_status", "Aviso Realtime: $err") } } },
            onCareDataChange = { runOnUiThread {
                if (access.isCurrent(scope)) {
                    val now = System.currentTimeMillis()
                    if (now - lastCareDataRefreshAt > 5_000L) {
                        lastCareDataRefreshAt = now
                        refreshCaregiverSharedData(force = true, renderWhenDone = true)
                    }
                }
            } },
        ).also { it.start(ownerId, deviceId) }
    }

    private fun mergeCaregiverMeasurement(measurement: JSONObject, scope: CaregiverScope) {
        runOnUiThread {
            val access = CaregiverAccess(store)
            if (!access.isCurrent(scope)) return@runOnUiThread
            val current = runCatching { JSONArray(store.prefs.getString("caregiver_shared_measurements", "[]")) }.getOrDefault(JSONArray())
            val rows = (0 until current.length()).mapNotNull { current.optJSONObject(it) }
                .filterNot { it.optString("id") == measurement.optString("id") }.toMutableList()
            rows.add(measurement)
            val sorted = rows.sortedByDescending { row -> isoMillis(row.optString("captured_at")) }.take(100)
            val merged = JSONArray().apply { sorted.forEach(::put) }
            if (access.write(scope) {
                putString("caregiver_shared_measurements", merged.toString())
                putLong("caregiver_shared_fetched_at", System.currentTimeMillis())
            }) caregiverVitalsUpdater?.invoke()
        }
    }
    private fun mergeCaregiverPhoneMotion(motion: JSONObject, scope: CaregiverScope) {
        runOnUiThread {
            CaregiverAccess(store).write(scope) {
                putString("caregiver_shared_phone_motion", motion.toString())
                putLong("caregiver_shared_fetched_at", System.currentTimeMillis())
            }
        }
    }
    private fun addSharedLocationCard(parent: LinearLayout) {
        val locationCard = card(parent)
        locationCard.addView(text("Ubicación compartida", 20, true))
        locationCard.addView(text("Solo se actualiza cuando la persona comparte su ubicación con el monitoreo activo.", 14, color = muted))
        val statusView = text("Sin datos", 17, true, muted).also { locationCard.addView(it) }
        val coordinatesView = text("—", 15, true).also { locationCard.addView(it) }
        val freshnessView = text("Sin datos de ubicación", 14, color = muted).also { locationCard.addView(it) }
        val mapButton = button("Ver ubicación en el mapa", false) { openSharedMap() }.also { locationCard.addView(it) }
        locationCard.addView(button("Actualizar datos compartidos", false) { refreshCaregiverSharedData(force = true, renderWhenDone = true) })
        locationCard.addView(text("Las coordenadas son aproximadas y dependen de la señal del teléfono de la persona.", 12, color = muted))

        fun applyLocation() {
            val fix = sharedMapFix()
            statusView.text = if (fix == null) "Sin una posición reciente" else "Ubicación compartida"
            statusView.setTextColor(if (fix != null) accent else muted)
            coordinatesView.text = fix?.point?.coordinates() ?: "—"
            freshnessView.text = fix?.freshness() ?: when {
                    store.prefs.getString("caregiver_link_id", "").orEmpty().isBlank() ->
                        "Vincula a la persona para recibir su ubicación."
                    sessions.read() == null ->
                        "Sesión vencida: inicia sesión para actualizar la ubicación."
                    store.prefs.getLong("caregiver_shared_fetched_at", 0L) == 0L ->
                        "Aún no se descargaron datos. Usa «Actualizar datos compartidos»."
                    else ->
                        "La ubicación se comparte con el monitoreo activo y el permiso de ubicación."
            }
            mapButton.text = if (fix != null) "Ver ubicación en el mapa" else "Ver disponibilidad de ubicación"
        }
        caregiverSharedLocationView = statusView
        caregiverLocationCoordinatesView = coordinatesView
        caregiverLocationFreshnessView = freshnessView
        caregiverMapButton = mapButton
        caregiverLocationUpdater = { applyLocation() }
        applyLocation()
    }

    private fun caregiverLocationAlerts(): List<LocationAlert> {
        if (!hasSharedMapCache()) return emptyList()
        val now = System.currentTimeMillis()
        val person = sharedMapPerson()
        val followups = AlertFollowupPolicy.latestByEvent(sharedAlertFollowups())
        val latestHelp = sharedEvents().firstOrNull { event ->
            val age = now - isoMillis(event.optString("occurred_at"))
            event.optString("classification") == "manual" && event.optString("confirmation_status") == "confirmed" &&
                age in 0L..86_400_000L && followups[event.optString("id")]?.optString("status") !in
                setOf("attended", "closed", "dismissed", "false_alarm", "cancelled", "canceled")
        }?.let { event ->
            val timestamp = formatSharedMoment(event.optString("occurred_at"))
            val detail = person.fix?.let { "$timestamp · ${it.freshness()}" } ?: timestamp
            LocationAlert(LocationAlertType.HELP, "${person.firstName} solicitó ayuda", detail)
        }
        val zones = mapZones().filter(MapZone::shared).associateBy(MapZone::name)
        val journal = sharedCareRecords().asSequence()
            .filter { it.optString("record_kind") == "journal" }
            .mapNotNull { it.optJSONObject("record") }
            .filter { now - it.optLong("occurredAt") in 0L..172_800_000L }
            .sortedByDescending { it.optLong("occurredAt") }
            .mapNotNull { entry ->
                val type = when (entry.optString("type")) {
                    "zona_salida" -> LocationAlertType.EXIT
                    "zona_entrada" -> LocationAlertType.ARRIVAL
                    else -> return@mapNotNull null
                }
                val isExit = type == LocationAlertType.EXIT
                val name = entry.optString("description").removePrefix(if (isExit) "Salida de " else "Entrada a ")
                    .trim().ifBlank { "zona segura" }
                val stamp = formatSharedMoment(java.time.Instant.ofEpochMilli(entry.optLong("occurredAt")).toString())
                val verb = if (isExit) "salió de" else "llegó a"
                LocationAlert(type, "${person.firstName} $verb $name", stamp, zones[name])
            }.take(8).toList()
        return listOfNotNull(latestHelp) + journal
    }

    private fun caregiverActivity(body: LinearLayout) {
        refreshCaregiverSharedData()
        val linked = caregiverLinkAvailable()
        val locationAlerts = if (linked) caregiverLocationAlerts() else emptyList()
        if (locationAlerts.isNotEmpty() && !showOtherCaregiverAlerts) {
            TzindaV11(this, v5, mapService.value, mapLocation.value, ::requestMapFix)
                .alertFeed(body, sharedMapPerson(), locationAlerts, { selectTab(TzindaTabs.HOME) }, ::openSharedMap)
            gap(body, 12)
            v5.action(body, "Ver todos los avisos", R.drawable.v5_bell, fill = tz.cardColor, fg = accent) {
                showOtherCaregiverAlerts = true
                render()
            }
            return
        }
        caregiverHeader(body, "Alertas compartidas", "Los avisos de tu círculo, en un lugar.")
        if (locationAlerts.isNotEmpty()) {
            v5.action(body, "Alertas de ubicación", R.drawable.v5_pin, fill = tz.cardColor, fg = accent) {
                showOtherCaregiverAlerts = false
                render()
            }
        }
        val summary = v5.panel(body, tz.primaryFill, radius = 28, padding = 18, margin = 18)
        val hero = v5.horizontal().apply { minimumHeight = dp(98) }
        hero.addView(column().apply {
            addView(v5.label("CENTRO DE ACTIVIDAD", v5.onBlueMuted))
            gap(this, 12)
            addView(v5.title("Atento a lo\nque importa.", 25, Color.WHITE))
        }, LinearLayout.LayoutParams(0, -2, 1f).apply { bottomMargin = dp(4) })
        hero.addView(v5.badge(R.drawable.v5_bell, 0xFF2665BA.toInt(), Color.WHITE, 68, 51, 99),
            LinearLayout.LayoutParams(dp(68), dp(68)).apply { marginEnd = dp(8) })
        summary.addView(hero)

        val person = store.prefs.getString("caregiver_shared_name", "").orEmpty().ifBlank { "Persona protegida" }
        val events = if (linked) sharedEvents() else emptyList()
        val pending = pendingSharedAlerts(events)
        val link = v5.panel(body, padding = 0, margin = 18)
        v5.row(link, if (linked) person else "Sin persona vinculada",
            when {
                !linked -> "Conecta tu círculo para recibir avisos."
                pending.isEmpty() -> "Sin alertas pendientes de seguimiento."
                else -> TzindaText.count(pending.size, "alerta pendiente", "alertas pendientes")
            }, R.drawable.v5_people, minHeight = 72, inset = 16, emphasis = true, badgeSize = 42, titleSize = 14, detailSize = 12)

        v5.section(body, "Actividad reciente", titleSize = 19)
        gap(body, 3)
        val filter = activityFilter.coerceIn(0, 2)
        v5.segments(body, listOf("Todos", "Detecciones", "Dispositivos"), filter) {
            activityFilter = it
            render()
        }
        gap(body, 18)
        val visible = if (filter == 0) events else events.filter {
            val classification = it.optString("classification").lowercase(Locale.ROOT)
            val device = classification.contains("device") || classification.contains("disconnect")
            if (filter == 2) device else !device
        }
        if (visible.isEmpty()) {
            val empty = v5.panel(body, radius = 28, padding = 0, margin = 25)
            val content = v5.horizontal().apply {
                gravity = Gravity.TOP
                minimumHeight = tz.controlScale(109)
                setPadding(dp(27), dp(18), dp(15), dp(12))
            }
            content.addView(v5.badge(R.drawable.v5_bell, tz.brandSoft, accent, 48, 34, 99))
            content.addView(column().apply {
                addView(v5.title(if (filter == 0) "Aún no hay avisos" else "Sin avisos en esta categoría", 19))
                gap(this, 12)
                addView(v5.text("Aquí aparecerán las alertas\nque te autoricen compartir.", 12, muted))
            }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(16) })
            empty.addView(content)
            empty.addView(View(this).apply { setBackgroundColor(tz.outline) },
                LinearLayout.LayoutParams(-1, dp(1)).apply { setMargins(dp(19), 0, dp(19), 0) })
            val foot = v5.horizontal().apply { minimumHeight = dp(40); setPadding(dp(17), dp(4), dp(10), dp(4)) }
            foot.addView(v5.icon(R.drawable.v5_sync, 25, muted))
            foot.addView(v5.text("Se actualiza con conexión en ambos teléfonos.", 11, muted),
                LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(7) })
            empty.addView(foot)
        }
        val latestFollowups = AlertFollowupPolicy.latestByEvent(sharedAlertFollowups())
        var lastDay = ""
        visible.forEach { event ->
            val day = dayLabel(isoMillis(event.optString("occurred_at")))
            if (day != lastDay) {
                lastDay = day
                body.addView(tz.eyebrow(day).apply { setPadding(0, dp(12), 0, dp(2)) })
            }
            val followup = latestFollowups[event.optString("id")]
            val prepared = AlertFollowupPolicy.isPendingSharedAlert(event, latestFollowups)
            val state = followup?.let { TzindaStatusCopy.confirmation(it.optString("status")) }
                ?: TzindaStatusCopy.confirmation(event.optString("confirmation_status"))
            val item = card(body)
            val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            top.addView(text(TzindaStatusCopy.classification(event.optString("classification")), 19, true, if (prepared) tz.critical else ink),
                LinearLayout.LayoutParams(0, -2, 1f))
            top.addView(statusPill(TzindaCopy(
                state,
                "",
                when {
                    prepared -> TzindaTone.CRITICAL
                    followup != null -> TzindaTone.INFO
                    else -> TzindaTone.NEUTRAL
                },
            )))
            item.addView(top)
            item.addView(text("${formatSharedMoment(event.optString("occurred_at"))} · ${TzindaStatusCopy.source(event.optString("source"))}", 14, color = muted))
            event.optString("summary").takeIf { it.isNotBlank() }?.let { item.addView(text(it, 14, color = muted)) }
            followup?.optString("reason")?.takeIf { it.isNotBlank() }?.let {
                item.addView(text("Motivo: $it", 14, color = muted))
            }
            // Estado is append-only history: any alert can be updated again (for example, close it
            // after it was marked attended), not only while it is still pending.
            item.addView(button(if (followup == null) "Registrar seguimiento" else "Cambiar estado · $state", false) {
                recordAlertFollowup(event.optString("id"))
            })
        }
        if (linked && events.isNotEmpty() && caregiverEventsHasMore) {
            body.addView(button(if (caregiverEventsPageInFlight) "Cargando eventos…" else "Cargar 100 eventos anteriores", false) {
                loadMoreCaregiverEvents()
            }.apply { isEnabled = !caregiverEventsPageInFlight })
        } else if (events.size >= CAREGIVER_EVENT_CACHE_LIMIT) {
            body.addView(text("Se muestran hasta $CAREGIVER_EVENT_CACHE_LIMIT eventos en este teléfono.", 13, color = muted))
        }
        v5.action(body, "Revisar vínculo", R.drawable.v5_link, tz.cardColor, accent, margin = 0, action = ::openSupervisionCenter)
        if (linked) {
            gap(body, 18)
            addCaregiverSyncControls(body)
        }
    }
    private fun caregiverPersonScreen(body: LinearLayout) {
        refreshCaregiverSharedData()
        val remoteLinked = caregiverLinkAvailable()
        val person = store.prefs.getString("caregiver_shared_name", "").orEmpty().ifBlank { "Persona protegida" }
        v5.header(body, "Vincular persona", "Un vínculo de confianza, paso a paso.", gapBelow = 26)
        val intro = v5.panel(body, tz.brandSoft, radius = 28, padding = 18, margin = 22).apply { minimumHeight = dp(135) }
        val introRow = v5.horizontal().apply { gravity = Gravity.TOP }
        introRow.addView(v5.badge(R.drawable.v5_link, tz.cardColor, accent, 48, 27, 17))
        introRow.addView(column().apply {
            addView(v5.title("Conecta tu círculo", 20, accent))
            gap(this, 9)
            addView(v5.text("Pide el código a la persona\nque quieres acompañar.", 12, muted))
        }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(17) })
        intro.addView(introRow)
        gap(intro, 18)
        intro.addView(v5.text("Solo accederás a lo que decida compartir.", 11, muted))
        body.addView(v5.title("Código de vinculación", 19))
        gap(body, 23)
        val code = v5.field(body, "Generado en el teléfono de la persona", "", "Escribe el código",
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS, trailingIcon = R.drawable.v5_link,
            tag = "caregiver_invite_code", marginBelow = 20).apply {
            contentDescription = "Código de vinculación"
            minimumHeight = tz.controlScale(58)
            textSize = tz.textScale(16f)
        }
        code.filters = arrayOf(android.text.InputFilter.LengthFilter(9))
        formatPairingCode(code)
        v5.action(body, "Continuar", R.drawable.ic_chevron, margin = 36) {
            val invite = code.text.toString().filter(Char::isLetterOrDigit)
            if (invite.length != 8) {
                code.error = "Escribe los 8 caracteres del código."
                code.requestFocus()
            } else showPairingConfirmation("caregiver", invite)
        }
        v5.section(body, "¿Dónde encuentro el código?", titleSize = 19)
        val instructions = v5.panel(body, padding = 17, margin = 30).apply { minimumHeight = dp(124) }
        listOf("Abre AxTarget Care en el otro teléfono.", "En Dispositivos, genera un código.").forEachIndexed { index, description ->
            val step = v5.horizontal().apply { minimumHeight = dp(42) }
            step.addView(v5.text((index + 1).toString(), 12, accent, bold = true).apply {
                background = rounded(tz.brandSoft, 99)
                gravity = Gravity.CENTER
            }, LinearLayout.LayoutParams(dp(24), dp(24)))
            step.addView(v5.text(description, 13), LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(13) })
            instructions.addView(step)
        }
        v5.note(body, "El vínculo requiere la autorización\nde la persona protegida.", R.drawable.v5_lock, margin = 26)
        val connect = v5.panel(body)
        v5.action(connect, "Escanear QR", R.drawable.v5_link, margin = 0) { scanPairingQr("caregiver") }
        if (remoteLinked) connect.addView(statusPill(TzindaStatusCopy.link(true, person, store.prefs.getLong("caregiver_shared_fetched_at", 0L))))
        if (remoteLinked) connect.addView(button("Desvincular persona", false) {
            AlertDialog.Builder(this).setTitle("Desvincular persona")
                .setMessage("Se dejarán de mostrar sus eventos y mediciones compartidas. Ella conserva sus datos y su monitoreo local.")
                .setNegativeButton("Cancelar", null).setPositiveButton("Desvincular") { _, _ ->
                    val linkId = store.prefs.getString("caregiver_link_id", "").orEmpty()
                    val access = CaregiverAccess(store)
                    val scope = access.snapshot() ?: return@setPositiveButton
                    io.execute {
                        val result = runCatching { backend.unlinkCaregiver(linkId) }
                        runOnUiThread {
                            if (!access.isCurrent(scope)) return@runOnUiThread
                            result.onSuccess {
                                access.clear(scope)
                                caregiverLocationRealtime?.stop(); caregiverLocationRealtime = null; caregiverLocationRealtimeDevice = ""
                                caregiverHealthRealtime?.stop(); caregiverHealthRealtime = null; caregiverHealthRealtimeKey = ""
                                toast("Vínculo desconectado."); render()
                            }.onFailure { toast(it.message ?: "No se pudo desvincular.", true) }
                        }
                    }
                }.show()
        })

        val privacy = card(body)
        privacy.addView(text("Privacidad y permisos", 20, true))
        tz.actionRow(privacy, "Permisos de medicamentos", "Puedes registrar tomas compartidas, sin editar medicamentos ni horarios", R.drawable.ic_lock)
        tz.actionRow(privacy, "Cámara bajo demanda", "Solo se activa cuando la persona la abre", R.drawable.ic_camera)
        tz.actionRow(privacy, "Ubicación autorizada", "Solo mientras el monitoreo está activo y ella lo permite", R.drawable.ic_location, showDivider = false)
        if (remoteLinked) {
            privacy.addView(button("Abrir centro de supervisión", false) { openSupervisionCenter() })
            privacy.addView(button("Abrir medicamentos compartidos", false) { openMedicationScreen() })
        }
        addSharedPhoneMotionCard(body)
        addCaregiverSyncControls(body)
    }
    private fun vitalsUnavailableCard(parent: LinearLayout, caregiver: Boolean) {
        val shared = if (caregiver) runCatching { JSONArray(store.prefs.getString("caregiver_shared_measurements", "[]")) }.getOrDefault(JSONArray()) else JSONArray()
        val snapshot = vitalSnapshot(caregiver, shared)
        val hasReading = snapshot.pulse != "—" || snapshot.oxygen != "—"
        val freshness = if (hasReading && snapshot.capturedAt > 0L)
            TzindaStatusCopy.freshness(System.currentTimeMillis() - snapshot.capturedAt) else "Sin lectura válida"
        val freshnessLabel = when {
            !hasReading -> "Sin lectura"
            snapshot.capturedAt > 0L && System.currentTimeMillis() - snapshot.capturedAt <= 10 * 60_000L -> "Reciente"
            else -> "Actualizado"
        }
        val statusCopy = if (!caregiver) {
            TzindaStatusCopy.vitals(hasReading, snapshot.capturedAt, arduinoConnectionAvailable())
        } else if (hasReading) {
            TzindaCopy(
                "Última lectura $freshness",
                "Datos compartidos informativos; no constituyen diagnóstico médico.",
                TzindaTone.NEUTRAL,
            )
        } else {
            TzindaCopy("Sin lecturas compartidas", "La persona protegida aún no ha compartido signos vitales.", TzindaTone.NEUTRAL)
        }
        val tone = designTone(statusCopy.tone)
        fun detailCard(
            label: String,
            value: String,
            unit: String,
            points: List<Float>,
            iconRes: Int,
            isPulse: Boolean,
        ) {
            val item = card(parent)
            val header = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            header.addView(tz.iconBadge(iconRes, if (isPulse) TzindaDesign.Tone.DANGER else TzindaDesign.Tone.BRAND, 34),
                LinearLayout.LayoutParams(dp(34), dp(34)))
            header.addView(text(label, 17, true, ink), LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(10) })
            header.addView(tz.statusPill(freshnessLabel, tone, withDot = hasReading))
            item.addView(header)
            val valueRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.BOTTOM
                setPadding(0, dp(8), 0, 0)
            }
            val valueView = text(value, 46, true, ink).apply { fontFeatureSettings = "tnum" }
            if (isPulse) patientPulseValue = valueView else patientSpo2Value = valueView
            val unitView = text(unit, 15, true, muted).apply { setPadding(0, 0, 0, dp(8)) }
            if (isPulse) patientPulseUnit = unitView else patientSpo2Unit = unitView
            valueRow.addView(valueView)
            valueRow.addView(unitView)
            item.addView(valueRow)
            if (points.isNotEmpty()) {
                val spark = vitalSparkline(points, tz.cardColor)
                if (isPulse) patientPulseSpark = spark else patientSpo2Spark = spark
                item.addView(spark, LinearLayout.LayoutParams(-1, dp(62)).apply { topMargin = dp(2) })
            }
            item.addView(text(
                if (points.isEmpty()) "Aún no hay lecturas suficientes para mostrar una tendencia."
                else "Tendencia de ${TzindaText.count(points.size, "lectura")} · sin ejes ni valores inventados.",
                12, false, muted,
            ).apply { if (points.isEmpty()) setPadding(0, dp(8), 0, 0) })
        }
        detailCard("Frecuencia cardiaca", snapshot.pulse, "LPM", snapshot.pulseSeries, R.drawable.ic_heart, true)
        detailCard("Oxígeno en sangre (SpO₂)", snapshot.oxygen, "%", snapshot.oxygenSeries, R.drawable.ic_activity, false)
        patientVitalsStatus = text(
            "${statusCopy.title}. ${statusCopy.detail}",
            13,
            false,
            tz.toneForeground(tone),
        ).apply { setPadding(dp(4), dp(8), dp(4), dp(0)) }
        parent.addView(patientVitalsStatus)
        addVitalHistory(parent, caregiver, shared)
    }

    /** Renders a compact graph made only from persisted local samples or authorized shared samples. */
    private fun addVitalHistory(parent: LinearLayout, caregiver: Boolean, shared: JSONArray) {
        val pulse: List<Pair<Double, Long>>
        val oxygen: List<Pair<Double, Long>>
        if (caregiver) {
            fun rows(type: String) = (0 until shared.length()).mapNotNull { index ->
                val row = shared.optJSONObject(index) ?: return@mapNotNull null
                if (row.optString("measurement_type") != type || (!row.isNull("is_valid") && !row.optBoolean("is_valid"))) return@mapNotNull null
                val value = row.optDouble("value", Double.NaN)
                val at = isoMillis(row.optString("captured_at"))
                value.takeIf { it.isFinite() }?.let { it to at }
            }.take(16).reversed()
            pulse = rows("heart_rate")
            oxygen = rows("spo2")
        } else {
            val rows = store.telemetry.recentArduinoVitalHistory(16).reversed()
            pulse = rows.mapNotNull { row -> row.heartRateBpm?.let { it to row.capturedAtMillis } }
            oxygen = rows.mapNotNull { row -> row.spo2Percent?.let { it to row.capturedAtMillis } }
        }
        if (pulse.isEmpty() && oxygen.isEmpty()) return
        val history = card(parent)
        history.addView(text("Historial y tendencia", 19, true))
        history.addView(text("Últimas mediciones válidas recibidas; no son diagnóstico médico.", 14, color = muted))
        fun trend(label: String, values: List<Pair<Double, Long>>, unit: String) {
            if (values.isEmpty()) return
            val series = values.map { it.first }
            val min = series.minOrNull() ?: return
            val max = series.maxOrNull() ?: return
            val latest = values.last()
            fun numeric(value: Double) = if (value % 1.0 == 0.0) value.toInt().toString() else "%.1f".format(Locale("es", "MX"), value)
            val time = latest.second.takeIf { it > 0L }?.let { SimpleDateFormat("HH:mm", Locale("es", "MX")).format(Date(it)) } ?: "hora no disponible"
            val firstTime = values.firstOrNull { it.second > 0L }?.second?.let {
                SimpleDateFormat("HH:mm", Locale("es", "MX")).format(Date(it))
            } ?: "hora no disponible"
            val spokenSummary = "$label: ${series.size} lecturas, mínimo ${numeric(min)}, máximo ${numeric(max)} $unit. " +
                "Última lectura ${numeric(latest.first)} $unit a las $time. Datos desde las $firstTime."
            history.addView(text(label, 17, true, accent).apply { setPadding(0, dp(10), 0, 0) })
            val chart = vitalSparkline(series.map { it.toFloat() }, tz.cardColor)
            history.addView(chart, LinearLayout.LayoutParams(-1, dp(58)).apply { topMargin = dp(6) })
            history.addView(text("Mín. ${numeric(min)} · Máx. ${numeric(max)} $unit", 14, true, ink))
            history.addView(
                text(
                    "Última ${numeric(latest.first)} $unit · $time · ${TzindaText.count(series.size, "lectura")} desde $firstTime",
                    14, color = muted,
                ),
            )
            history.contentDescription = listOfNotNull(history.contentDescription?.toString(), spokenSummary)
                .filter(String::isNotBlank).joinToString(" ")
        }
        trend("Pulso", pulse, "BPM")
        trend("SpO₂", oxygen, "%")
    }

    private fun addCaregiverSyncControls(parent: LinearLayout) {
        if (store.prefs.getString("caregiver_link_id", "").orEmpty().isBlank()) return
        val controls = card(parent)
        val fetchedAt = store.prefs.getLong("caregiver_shared_fetched_at", 0L)
        val now = System.currentTimeMillis()
        val syncAgeMs = if (fetchedAt > 0L) (now - fetchedAt).coerceAtLeast(0L) else Long.MAX_VALUE
        val syncTimeText = when {
            fetchedAt == 0L -> "Sin datos"
            syncAgeMs < 60_000L -> "Última actualización: hace menos de 1 min"
            syncAgeMs < 3_600_000L -> "Última actualización: hace ${syncAgeMs / 60_000L} min"
            syncAgeMs < 86_400_000L -> "Última actualización: hace ${syncAgeMs / 3_600_000L} h"
            else -> "Última actualización: hace más de 24 h"
        }
        controls.addView(text("SINCRONIZACIÓN", 13, true, accent))
        controls.addView(text(syncTimeText, 17, true))
        caregiverSharedStatusView = text(
            store.prefs.getString("caregiver_shared_status", "Conexión lista para actualizar." )?.ifBlank { "Conexión lista para actualizar." }
                ?: "Conexión lista para actualizar.",
            14, color = muted,
        ).also { controls.addView(it) }
        controls.addView(button("Actualizar ahora", false) {
            caregiverSharedStatusView?.text = "Actualizando datos…"
            refreshCaregiverSharedData(force = true, renderWhenDone = true)
        })
    }

    private fun addSharedPhoneMotionCard(parent: LinearLayout) {
        val card = card(parent)
        card.addView(text("Movimiento del teléfono", 19, true))
        val motion = runCatching {
            JSONObject(store.prefs.getString("caregiver_shared_phone_motion", "{}").orEmpty())
        }.getOrDefault(JSONObject())
        val capturedAt = isoMillis(motion.optString("captured_at"))
        val ageMs = if (capturedAt > 0L) (System.currentTimeMillis() - capturedAt).coerceAtLeast(0L) else Long.MAX_VALUE
        val hasMotion = capturedAt > 0L && motion.optInt("sample_count") > 0
        card.addView(text(
            when {
                !hasMotion -> "Esperando datos del sensor de movimiento."
                ageMs <= 90_000L -> "●  Datos recientes del teléfono"
                else -> "Últimos datos recibidos"
            },
            16, true, if (hasMotion && ageMs <= 90_000L) accent else muted,
        ))
        if (hasMotion) {
            val time = SimpleDateFormat("HH:mm:ss", Locale("es", "MX")).format(Date(capturedAt))
            val average = motion.optDouble("average_dynamic_g", Double.NaN)
            val peak = motion.optDouble("peak_dynamic_g", Double.NaN)
            val metrics = if (average.isFinite() && peak.isFinite())
                "Movimiento promedio ${"%.2f".format(Locale.US, average)} g · máximo ${"%.2f".format(Locale.US, peak)} g"
            else "Actividad de movimiento registrada"
            card.addView(text("$metrics\nÚltimo envío $time · ${motion.optInt("sample_count")} muestras", 14, color = muted))
        } else {
            card.addView(text("Activa el monitoreo en el teléfono de la persona para compartir el resumen del sensor.", 14, color = muted))
        }
    }
    private fun refreshPatientVitals() {
        if (patientPulseValue == null && patientSpo2Value == null && patientVitalsStatus == null) return
        val now = SystemClock.elapsedRealtime()
        if (now - patientVitalsRefreshAt < 5_000L) return
        patientVitalsRefreshAt = now
        val vitals = store.telemetry.latestArduinoVitals()
        fun number(key: String): String? {
            if (!vitals.has(key)) return null
            val value = vitals.optDouble(key)
            return if (value % 1.0 == 0.0) value.toInt().toString() else value.toString()
        }
        val hasReading = vitals.has("bpm") || vitals.has("spo2")
        val capturedAt = maxOf(vitals.optLong("heart_rate_at"), vitals.optLong("spo2_at"))
        val history = store.telemetry.recentArduinoVitalHistory(16).reversed()
        patientPulseSpark?.setPoints(history.mapNotNull { it.heartRateBpm?.toFloat() })
        patientSpo2Spark?.setPoints(history.mapNotNull { it.spo2Percent?.toFloat() })
        patientPulseValue?.text = number("bpm") ?: "—"
        patientSpo2Value?.text = number("spo2") ?: "—"
        vitalTileUpdates.getOrNull(0)?.invoke(number("bpm") ?: "—")
        vitalTileUpdates.getOrNull(1)?.invoke(number("spo2") ?: "—")
        updateVitalsCaption?.invoke(hasReading)
        val statusCopy = TzindaStatusCopy.vitals(hasReading, capturedAt, arduinoConnectionAvailable())
        patientVitalsStatus?.text = "${statusCopy.title}. ${statusCopy.detail}"
    }
    private fun refreshRemoteDevicesIfStale() {
        if (!backend.isConfigured || sessions.read() == null || isCaregiver || isCameraRole) return
        val lastCheck = store.prefs.getLong("last_device_remote_sync", 0L)
        if (System.currentTimeMillis() - lastCheck < 5_000L) return
        store.prefs.edit().putLong("last_device_remote_sync", System.currentTimeMillis()).apply()
        io.execute {
            runCatching {
                val devices = backend.linkedCamerasForOwner()
                for (i in 0 until devices.length()) {
                    val dev = devices.optJSONObject(i) ?: continue
                    val id = dev.optString("id")
                    val name = dev.optString("device_name").ifBlank { "Cámara" }
                    if (id.isNotBlank()) store.upsertRegisteredCamera(id, name, enqueueSync = false)
                }
                val caregivers = backend.activeCaregiversForOwner()
                store.prefs.edit().putBoolean("wearer_has_linked_caregiver", caregivers.length() > 0).apply()
            }.onSuccess {
                runOnUiThread {
                    if (tab == DEVICE_TAB) render()
                }
            }
        }
    }


    private fun circleScreen(body: LinearLayout) {
        if (!isCaregiver) refreshWearerLinkedData()
        v5.header(body, "Tu círculo", "Cuidar se siente más cerca.", "AXTARGET CARE / CUIDADOS",
            gapBelow = 37, eyebrowGap = 29, settings = ::openSettings)
        val panel = v5.panel(body, v5.darkHero, radius = 28, padding = 16, margin = 27).apply {
            setPadding(paddingLeft, paddingTop, paddingRight, tz.space(5))
        }
        val hero = v5.horizontal()
        hero.addView(column().apply {
            addView(v5.label("UN VÍNCULO\nDE CONFIANZA", 0xFF90DFC0.toInt()))
            gap(this, 12)
            addView(v5.title("Acompaña\na tu persona.", 27, Color.WHITE))
        }, LinearLayout.LayoutParams(0, -2, 1f))
        hero.addView(v5.art(R.drawable.v5_art_circle), LinearLayout.LayoutParams(dp(101), dp(98)))
        panel.addView(hero)
        gap(panel, 22)
        panel.addView(v5.text("Consulta solo lo que decida compartir.", 12, 0xFFDFE7E6.toInt()))
        gap(panel, 15)
        v5.action(panel, "Vincular persona", R.drawable.v5_people, Color.WHITE, Color.BLACK, margin = 0) {
            if (isCaregiver) selectTab(TzindaTabs.CONTACTS) else showCreateCaregiverInvite(panel)
        }

        v5.section(body, "Dispositivos")
        fun device(label: String, detail: String, icon: Int, fg: Int, open: () -> Unit): LinearLayout =
            column().apply {
                minimumHeight = dp(116)
                background = rounded(tz.cardColor, 24)
                setPadding(dp(15), dp(15), dp(15), dp(15))
                addView(v5.icon(icon, 25, fg))
                gap(this, 10)
                addView(v5.text(label, 14, bold = true))
                gap(this, 6)
                addView(v5.text(detail, 12, muted))
                contentDescription = "$label. $detail"
                v5.activate(this, open)
            }
        v5.twoColumns(body,
            device("Pulsera Tzinda", if (isCaregiver) "Sin conexión" else braceletConnectionLabel(), R.drawable.v5_watch, accent) {
                if (isCaregiver) openDeviceLinkScreen() else selectTab(TzindaTabs.BRACELET_PAIR)
            },
            device("Cámara de estancia", "Detección local", R.drawable.v5_camera, tz.positive) {
                if (isCaregiver) openDeviceLinkScreen() else openCameraScreen()
            })
        gap(body, 24)
        v5.section(body, "Acompañamiento")
        val support = v5.panel(body, padding = 0)
        v5.row(support, "Alertas compartidas", "", R.drawable.v5_bell, tz.criticalSurface, tz.onCriticalSurface,
            minHeight = 58, divider = true) { selectTab(TzindaTabs.ACTIVITY) }
        v5.row(support, "Salud compartida", "", R.drawable.v5_pulse, tz.mint, tz.positive,
            minHeight = 58, divider = true) { selectTab(MEDICATIONS_TAB) }
        v5.row(support, "Supervisión y permisos", "", R.drawable.v5_supervision, minHeight = 58) {
            openDeviceLinkScreen()
        }
        if (!isCaregiver) {
            val additional = v5.panel(body, padding = 0, margin = 0)
            v5.row(additional, "Zonas seguras", "Tus lugares de confianza", R.drawable.v5_pin, divider = true) {
                selectTab(TzindaTabs.SAFE_ZONES)
            }
            v5.row(additional, "Contactos de confianza", "Tu red de ayuda", R.drawable.v5_people) { selectTab(TzindaTabs.CONTACTS) }
        }
    }

    private fun braceletPair(body: LinearLayout) {
        val bluetooth = store.braceletConnectionMode() == AppStore.BRACELET_MODE_BLE
        val channel = if (bluetooth) "Bluetooth LE" else "Wi-Fi"
        v5.header(body, "Vincular pulsera", "Conecta Tzinda por $channel.", gapBelow = 34)
        val art = v5.panel(body, tz.brandSoft, radius = 28, padding = 17, margin = 30).apply { gravity = Gravity.CENTER_HORIZONTAL }
        art.addView(v5.art(R.drawable.v5_art_pair), LinearLayout.LayoutParams(dp(219), dp(156)))
        gap(art, 7)
        art.addView(v5.text("Cerca de ti. Conectada contigo.", 16, accent, bold = true).apply { gravity = Gravity.CENTER })
        v5.section(body, "Antes de conectar")
        val steps = v5.timeline(body, margin = 26)
        if (bluetooth) {
            v5.row(steps, "Activa Bluetooth", "Comprueba los ajustes de tu teléfono.", R.drawable.v5_bluetooth, minHeight = 80, round = true) {
                runCatching { startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
                    .onFailure { toast("No se pudieron abrir los ajustes Bluetooth.") }
            }
            v5.row(steps, "Empareja tu pulsera", "Desde los ajustes Bluetooth del sistema.", R.drawable.v5_watch, minHeight = 80, round = true)
        } else {
            v5.row(steps, "Conecta a la red Wi-Fi", "Usa la misma red que la pulsera.", R.drawable.ic_wifi, minHeight = 80, round = true)
            v5.row(steps, "Revisa la configuración", "Comprueba la dirección de tu dispositivo.", R.drawable.v5_settings, minHeight = 80,
                round = true, action = ::openDeviceLinkScreen)
        }
        v5.row(steps, "Conecta desde AxTarget Care", "Busca la pulsera y consulta su estado.", R.drawable.v5_link, minHeight = 80, round = true)
        v5.action(body, "Buscar pulsera", R.drawable.v5_bluetooth) {
            selectTab(TzindaTabs.BRACELET_DIAGNOSTIC)
            requestBraceletService(GuardianMonitorService.ACTION_BRACELET_CONNECT)
        }
        v5.action(body, "Ver diagnóstico", fill = tz.cardColor, fg = accent) { selectTab(TzindaTabs.BRACELET_DIAGNOSTIC) }
    }

    private fun braceletIllustration(body: LinearLayout): TextView {
        val card = v5.panel(body, radius = 28, padding = 18, margin = 28)
        val state = v5.text(braceletConnectionLabel(), 11, tz.warning, bold = true).apply {
            background = rounded(tz.warningSurface, 99)
            setPadding(dp(12), dp(7), dp(12), dp(7))
        }
        card.addView(state, LinearLayout.LayoutParams(-2, -2))
        gap(card, 7)
        card.addView(v5.art(R.drawable.v5_art_disconnected), LinearLayout.LayoutParams(dp(183), dp(128)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
        })
        gap(card, 9)
        card.addView(v5.text("Acerca tu pulsera al teléfono", 16, bold = true).apply { gravity = Gravity.CENTER })
        return state
    }

    private fun braceletDiagnosis(body: LinearLayout) {
        v5.header(body, "Diagnóstico de pulsera", "Conexión y validez de los datos recibidos.", gapBelow = 31, titleSize = 32)
        val state = braceletIllustration(body)
        v5.section(body, "Estado del dispositivo")
        val entries = v5.panel(body, padding = 0, margin = 25)
        val review = v5.text("Revisar", 12, tz.warning, bold = true)
        val connectionTitle = if (store.braceletConnectionMode() == AppStore.BRACELET_MODE_BLE) "Vínculo Bluetooth" else "Vínculo Wi-Fi"
        val connection = v5.row(entries, connectionTitle, braceletConnectionLabel(), R.drawable.v5_bluetooth,
            tz.warningSurface, tz.warning, minHeight = 68, divider = true, trailing = review) {
            openDeviceLinkScreen()
        }
        val label = (connection.getChildAt(1) as LinearLayout).let { it.getChildAt(it.childCount - 1) as TextView }
        braceletStatusPillUpdaters.add {
            val connected = arduinoConnectionAvailable()
            state.text = braceletConnectionLabel()
            state.setTextColor(if (connected) tz.positive else tz.warning)
            state.background = rounded(if (connected) tz.mint else tz.warningSurface, 99)
            label.text = state.text
            connection.contentDescription = tz.safeDescription(connectionTitle, label.text.toString())
            review.visibility = if (connected) View.GONE else View.VISIBLE
        }
        val snapshot = vitalSnapshot(false, JSONArray())
        val hasReading = snapshot.pulse != "—" || snapshot.oxygen != "—"
        val detail = if (hasReading) snapshot.pulse + " lpm · " + snapshot.oxygen + " %" else "Sin datos válidos"
        v5.row(entries, "Pulso y SpO₂", detail, R.drawable.v5_pulse, tz.criticalSurface, tz.onCriticalSurface,
            minHeight = 68, divider = true, trailing = if (hasReading) null else v5.text("—", 14, tz.onCriticalSurface, bold = true)) {
            selectTab(MEDICATIONS_TAB)
        }
        val timestamp = snapshot.capturedAt.takeIf { it > 0L }?.let {
            SimpleDateFormat("d MMM · HH:mm", Locale("es", "MX")).format(Date(it))
        } ?: "No disponible"
        v5.row(entries, "Última lectura", timestamp, R.drawable.v5_clock, minHeight = 68,
            trailing = if (snapshot.capturedAt > 0L) null else v5.text("—", 14, accent, bold = true))
        v5.note(body, "Calibración manual\nPropuesta visual.")
        v5.action(body, "Buscar de nuevo", R.drawable.v5_bluetooth) { requestBraceletService(GuardianMonitorService.ACTION_BRACELET_RECONNECT) }
        if (arduinoConnectionAvailable()) v5.action(body, "Desconectar pulsera", fill = tz.cardColor, fg = accent) {
            requestBraceletService(GuardianMonitorService.ACTION_BRACELET_DISCONNECT)
        }
    }

    private fun braceletNotice(body: LinearLayout) {
        val event = selectedEvent
        val time = event?.let(::eventTime) ?: "Sin hora disponible"
        val day = event?.let { dayLabel(it.optLong("time")) }
        val moment = listOfNotNull(day, time).joinToString(", ")
        v5.header(body, "Pulsera desconectada", "Aviso de dispositivo · $moment", gapBelow = 29)
        braceletIllustration(body).text = "Sin conexión"
        v5.section(body, "Información del aviso")
        val entries = v5.panel(body, padding = 0, margin = 29)
        v5.row(entries, "Dispositivo", "Pulsera Tzinda", R.drawable.v5_watch, minHeight = 67, divider = true)
        v5.row(entries, "Último intento", moment, R.drawable.v5_clock, minHeight = 67, divider = true)
        val channel = event?.optString("message").orEmpty().let {
            when {
                it.contains("Bluetooth", ignoreCase = true) -> "Bluetooth LE"
                it.contains("Wi-Fi", ignoreCase = true) -> "Wi-Fi"
                else -> "No especificado"
            }
        }
        v5.row(entries, "Canal de conexión", channel, R.drawable.v5_bluetooth, minHeight = 67)
        v5.action(body, "Revisar la pulsera", R.drawable.v5_watch) { selectTab(TzindaTabs.BRACELET_DIAGNOSTIC) }
        v5.action(body, "Volver a Alertas", fill = tz.cardColor, fg = accent) { resetNavigation(TzindaTabs.ACTIVITY) }
    }

    private fun deviceLinkScreen(body: LinearLayout) {
        refreshRemoteDevicesIfStale()
        cameraRegistryRenderedRevision = store.prefs.getLong("camera_registry_revision", 0L)
        if (isCaregiver) {
            refreshCaregiverSharedData()
            val remote = store.prefs.getString("caregiver_link_id", "").orEmpty().isNotBlank()
            val person = store.prefs.getString("caregiver_shared_name", "").orEmpty().ifBlank { "Persona protegida" }
            CaregiverDeviceLinksUi(
                activity = this,
                design = tz,
                linked = remote,
                personName = person,
                fetchedAt = store.prefs.getLong("caregiver_shared_fetched_at", 0L),
                devices = if (remote) sharedDevices() else emptyList(),
                deviceCopy = ::sharedDeviceCopy,
                lastSignal = ::deviceLastSignal,
                onManageLink = { selectTab(2) },
                onOpenCameraEvents = { selectTab(1) },
                onRefresh = { refreshCaregiverSharedData(force = true, renderWhenDone = true) },
                onOpenSupervision = { openSupervisionCenter() },
            ).render(body)
            return
        }
        heading(body, "CONECTAR DISPOSITIVOS", "Dispositivos y enlaces", "Gestiona la pulsera, las cámaras y la vinculación con tu red de apoyo.")
        refreshWearerLinkedData()
        val caregivers = runCatching { JSONArray(store.prefs.getString("wearer_linked_caregivers", "[]")) }.getOrDefault(JSONArray())
        val phoneCard = card(body)
        phoneCard.addView(text("Teléfonos", 20, true))
        phoneCard.addView(text("Este teléfono · ubicación, pulsera y alertas", 15, true))
        if (caregivers.length() == 0) {
            phoneCard.addView(text("Sin cuidador/a vinculado/a. Crea un código para invitar a una persona de confianza.", 14, color = muted))
            phoneCard.addView(button("Crear QR o código para cuidador/a") { showCreateCaregiverInvite(phoneCard) })
        } else {
            for (index in 0 until caregivers.length()) {
                val caregiver = caregivers.optJSONObject(index) ?: continue
                val name = caregiver.optString("name").ifBlank { "Cuidador/a vinculado/a" }
                val caregiverDevices = caregiver.optJSONArray("devices") ?: JSONArray()
                tz.actionRow(
                    phoneCard,
                    name,
                    "Cuenta vinculada · ${TzindaText.count(caregiverDevices.length(), "dispositivo registrado", "dispositivos registrados")}",
                    leadingText = name.take(2),
                    trailingText = "Solo lectura",
                    showDivider = index < caregivers.length() - 1,
                )
            }
            phoneCard.addView(button("Crear otro código de cuidador/a", false) { showCreateCaregiverInvite(phoneCard) })
        }

        val braceletStatus = store.prefs.getString("arduino_status", "").orEmpty()
        val braceletCard = addDeviceRegistryCard(
            body,
            "Pulsera de sensores · UNO Q",
            "Pulso, oxígeno y detección de caídas",
            "wearable",
            store.arduinoDeviceId(),
            when {
                braceletStatus.contains("conectada por Bluetooth", ignoreCase = true) -> "Conectada por Bluetooth · enviando lecturas"
                braceletStatus.contains("conexión activa", ignoreCase = true) -> "Conectada por Wi-Fi · enviando lecturas"
                braceletStatus.isNotBlank() -> braceletStatus
                store.braceletConnectionMode() == AppStore.BRACELET_MODE_BLE -> "Bluetooth elegido · pulsa Conectar para buscarla"
                else -> "Wi-Fi elegido · configura la dirección del UNO Q"
            },
        )
        addBraceletConnectionControls(braceletCard, includeMethodOptions = true)

        val cameras = card(body)
        cameras.addView(text("Cámaras independientes", 20, true))
        cameras.addView(text("La cámara de apoyo se activa bajo demanda. Vincular una cámara no enciende su lente.", 14, color = muted))
        val registered = store.registeredCameras()
        if (registered.isEmpty()) {
            cameras.addView(text("Todavía no hay cámaras agregadas.", 14, color = muted).apply { setPadding(0, dp(8), 0, 0) })
        } else registered.forEachIndexed { index, camera ->
            tz.actionRow(
                cameras,
                camera.name,
                "Cámara independiente · vinculación por Internet",
                leadingIcon = R.drawable.ic_camera,
                showDivider = index < registered.size - 1,
            ) {
                AlertDialog.Builder(this).setTitle("Quitar cámara")
                    .setMessage("Se quitará ${camera.name} de este inventario y se desvinculará del sistema. Los eventos ya guardados no se eliminarán.")
                    .setNegativeButton("Cancelar", null)
                    .setPositiveButton("Quitar") { _, _ ->
                        runCatching {
                            store.removeRegisteredCamera(camera.id)
                            if (backend.isConfigured && sessions.read() != null) {
                                io.execute { runCatching { backend.unlinkCameraDeviceByOwner(camera.id) } }
                            }
                        }
                            .onSuccess { updateServiceIfActive(); toast("Cámara quitada."); render() }
                            .onFailure { toast(it.message ?: "No se pudo quitar la cámara.", true) }
                    }.show()
            }
        }
        cameras.addView(button("Vincular una cámara independiente") { showCreateCameraInviteDialog() })
        cameras.addView(button("Registrar cámara física sin conexión", false) { showAddCameraDialog() })

        val note = card(body)
        note.addView(text("Cómo funciona la vinculación", 18, true))
        note.addView(text("El cuidador/a puede estar en otra red con Internet. El código es de un solo uso y vence pronto. La persona protegida decide qué comparte y puede desvincularse.", 14, color = muted))
        body.addView(text("La pulsera y las cámaras pueden estar sin conexión. AxTarget Care mostrará “sin datos recientes” en lugar de asumir que están activas.", 13, color = muted).apply {
            setPadding(dp(4), dp(12), dp(4), dp(4))
        })
    }
    private fun addBraceletConnectionControls(parent: LinearLayout, includeMethodOptions: Boolean) {
        if (includeMethodOptions) {
            parent.addView(text("Método de conexión", 16, true).apply { setPadding(0, dp(12), 0, dp(4)) })
            val group = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
            val bleOption = RadioButton(this).apply {
                id = View.generateViewId(); text = "Bluetooth · recomendado, funciona sin Internet"
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, tz.textScale(16f))
                setTextColor(ink); buttonTintList = ColorStateList.valueOf(accent); minHeight = tz.controlScale(56)
            }
            val wifiOption = RadioButton(this).apply {
                id = View.generateViewId(); text = "Wi-Fi · red local del UNO Q"
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, tz.textScale(16f))
                setTextColor(ink); buttonTintList = ColorStateList.valueOf(accent); minHeight = tz.controlScale(56)
            }
            group.addView(bleOption); group.addView(wifiOption)
            group.check(if (store.braceletConnectionMode() == AppStore.BRACELET_MODE_WIFI) wifiOption.id else bleOption.id)
            group.setOnCheckedChangeListener { _, checkedId ->
                val selected = if (checkedId == wifiOption.id) AppStore.BRACELET_MODE_WIFI else AppStore.BRACELET_MODE_BLE
                if (selected == store.braceletConnectionMode()) return@setOnCheckedChangeListener
                runCatching { store.saveBraceletConnectionMode(selected) }
                    .onSuccess {
                        val enabled = store.prefs.getBoolean("bracelet_connection_enabled", GuardianMonitorService.monitoring)
                        if (enabled) requestBraceletService(GuardianMonitorService.ACTION_BRACELET_RECONNECT)
                        else store.prefs.edit().putString("arduino_status", if (selected == AppStore.BRACELET_MODE_BLE)
                            "Bluetooth seleccionado. Pulsa Conectar para buscar la pulsera." else "Wi-Fi seleccionado. Configura la dirección y pulsa Conectar.").apply()
                        render()
                    }
                    .onFailure { toast(it.message ?: "No se pudo cambiar el método.", true) }
            }
            parent.addView(group)
        }
        val statusView = text(store.prefs.getString("arduino_status", "Pulsera sin conectar.").orEmpty(), 14, color = muted)
            .apply { setPadding(0, dp(10), 0, dp(4)) }
        braceletStatusViews += statusView
        parent.addView(statusView)
        parent.addView(button("Conectar o volver a conectar", true) {
            requestBraceletService(GuardianMonitorService.ACTION_BRACELET_RECONNECT)
            statusView.text = if (store.braceletConnectionMode() == AppStore.BRACELET_MODE_BLE) "Buscando la pulsera por Bluetooth…" else "Conectando a la pulsera por Wi-Fi…"
        })
        parent.addView(button("Desconectar pulsera", false) {
            service(GuardianMonitorService.ACTION_BRACELET_DISCONNECT)
            statusView.text = "Desconectando la pulsera…"
        })
        if (!includeMethodOptions || store.braceletConnectionMode() != AppStore.BRACELET_MODE_WIFI) return

        val arduinoUrl = field(parent, "Dirección HTTPS del UNO Q", store.arduinoBaseUrl(),
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI, "arduino_base_url")
        parent.addView(text("Huella SHA-256 del certificado HTTPS (64 dígitos hexadecimales). Obtén y confirma esta huella directamente en el UNO Q antes de guardar el token.", 14, color = muted))
        val arduinoFingerprint = field(parent, "Huella del certificado HTTPS", "",
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD, "uno_q_tls_fingerprint")
        parent.addView(text("Token local (mínimo 32 caracteres). Usa el mismo valor que TZINDA_API_TOKEN en el UNO Q. Se guarda cifrado en este teléfono. Nunca lo envíes por HTTP.", 14, color = muted))
        val arduinoToken = field(parent, "Token de la API local", "",
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD, "uno_q_api_token").apply {
                isSaveEnabled = false
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
                }
            }
        parent.addView(button("Guardar conexión segura", false) {
            runCatching {
                store.saveArduinoConnectionSettings(arduinoUrl.text.toString(), arduinoFingerprint.text.toString(), arduinoToken.text.toString())
            }
                .onSuccess { arduinoToken.text?.clear(); toast("Conexión HTTPS, huella y token guardados para la próxima conexión.") }
                .onFailure { toast(it.message ?: "No se pudo guardar la conexión segura.", true) }
        })
        val testStatus = text("", 14, color = muted)
        parent.addView(testStatus)
        parent.addView(button("Probar conexión Wi-Fi", false) {
            testStatus.text = "Validando HTTPS y la huella del UNO Q…"
            io.execute {
                val result = runCatching {
                    val settings = store.arduinoConnectionSettings()
                    val token = settings.token
                        ?: throw IllegalStateException("Guarda la configuración segura del UNO Q.")
                    val connection = ArduinoHttps.openPinnedConnection(
                        "${settings.baseUrl.trimEnd('/')}/api/v1/sensor", settings.certificateFingerprint
                    ).apply {
                        connectTimeout = 3_000; readTimeout = 3_000; requestMethod = "GET"
                        setRequestProperty("Authorization", "Bearer $token")
                    }
                    val code = connection.responseCode
                    val response = (if (code in 200..299) connection.inputStream else connection.errorStream)
                        ?.bufferedReader()?.use { it.readText() }.orEmpty()
                    connection.disconnect()
                    if (code !in 200..299) throw IllegalStateException("El UNO Q respondió HTTP $code.")
                    val root = JSONObject(response)
                    val sample = root.optJSONObject("reading") ?: root.optJSONObject("data") ?: root
                    val bpm = sample.optDouble("bpm", sample.optDouble("heart_rate_bpm", 0.0))
                    val spo2 = sample.optDouble("spo2", sample.optDouble("spo2_percent", 0.0))
                    when {
                        bpm > 0 && spo2 > 0 -> "Pulsera conectada · pulso $bpm BPM · SpO₂ $spo2%."
                        bpm > 0 -> "Pulsera conectada · pulso $bpm BPM · esperando oxígeno."
                        else -> "Pulsera conectada · coloca el dedo en el sensor para medir."
                    }
                }
                runOnUiThread {
                    testStatus.text = result.getOrElse { it.message ?: "No se pudo conectar con la pulsera." }
                    if (result.isFailure) toast(testStatus.text.toString(), true)
                }
            }
        })
    }

    private fun requestBraceletService(action: String) {
        if (store.braceletConnectionMode() == AppStore.BRACELET_MODE_BLE) {
            val required = requiredBraceletBlePermissions()
            val missing = required.filterNot(::has).toTypedArray()
            if (missing.isNotEmpty()) {
                pendingBraceletAction = action
                runCatching { requestPermissions(missing, REQUEST_BRACELET_BLE) }
                    .onFailure { pendingBraceletAction = null; toast("Android no pudo solicitar permiso de Bluetooth.", true) }
                return
            }
        }
        service(action)
        handler.postDelayed({ refreshLive() }, 400)
    }

    private fun requiredBraceletBlePermissions(): Array<String> = if (Build.VERSION.SDK_INT >= 31)
        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

    private fun addDeviceRegistryCard(parent: LinearLayout, name: String, detail: String, type: String, id: String, status: String): LinearLayout {
        val row = card(parent)
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(text(name, 18, true), LinearLayout.LayoutParams(0, -2, 1f))
        val pill = TextView(this).apply {
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = tz.boldTypeface()
            gravity = Gravity.CENTER
            minHeight = dp(32)
            setPadding(dp(10), dp(6), dp(10), dp(6))
            maxLines = 2
        }
        header.addView(pill, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(8) })
        row.addView(header)
        row.addView(text(detail, 14, color = muted))
        row.addView(text("ID ${id.take(8)}", 12, color = muted).apply { setPadding(0, dp(4), 0, 0) })
        val updatePill: (String) -> Unit = { raw ->
            val (label, tone) = deviceStatusPill(raw)
            pill.text = label
            pill.setTextColor(tz.toneForeground(tone))
            pill.background = tz.rounded(tz.toneBackground(tone), 999).apply {
                setStroke(dp(1), tz.colorWithAlpha(tz.toneForeground(tone), 0.20f))
            }
            pill.contentDescription = "Estado del dispositivo: $label"
        }
        updatePill(status)
        if (type == "wearable") braceletStatusPillUpdaters += updatePill
        return row
    }

    /** Short, non-claiming label for a device registry entry; the detailed text stays available below. */
    private fun deviceStatusPill(raw: String): Pair<String, TzindaDesign.Tone> = when {
        raw.contains("error", ignoreCase = true) || raw.contains("no se pudo", ignoreCase = true) ->
            "Revisar conexión" to TzindaDesign.Tone.WARNING
        raw.contains("conectada", ignoreCase = true) && !raw.contains("desconect", ignoreCase = true) ->
            "Conectada" to TzindaDesign.Tone.SUCCESS
        raw.contains("buscando", ignoreCase = true) || raw.contains("conectando", ignoreCase = true) ->
            "Conectando" to TzindaDesign.Tone.INFO
        raw.contains("sin conectar", ignoreCase = true) || raw.contains("desconectada", ignoreCase = true) ->
            "Sin conexión" to TzindaDesign.Tone.NEUTRAL
        raw.contains("elegido", ignoreCase = true) -> "Por conectar" to TzindaDesign.Tone.NEUTRAL
        else -> "Registrada" to TzindaDesign.Tone.INFO
    }

    private fun showAddCameraDialog() {
        val content = column().apply { setPadding(dp(22), dp(4), dp(22), dp(4)) }
        content.addView(text("El registro guarda solo el nombre en este teléfono. El video y la conexión se configuran por separado.", 14, color = muted))
        val input = field(content, "Nombre de la cámara", "", hintText = "Ej. Cámara de sala").apply { setSingleLine(true) }
        val dialog = AlertDialog.Builder(this).setTitle("Agregar cámara independiente")
            .setView(ScrollView(this).apply { addView(content) })
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Agregar", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = input.text.toString().trim()
                if (name.isBlank()) {
                    input.error = "Escribe un nombre para la cámara."
                    input.requestFocus()
                    return@setOnClickListener
                }
                runCatching { store.addRegisteredCamera(name) }
                    .onSuccess {
                        dialog.dismiss()
                        toast("Cámara registrada por separado. Falta configurar su conexión.")
                        render()
                    }
                    .onFailure {
                        input.error = it.message ?: "No se pudo registrar la cámara."
                        input.requestFocus()
                    }
            }
        }
        dialog.show()
    }
    private fun todayPossibleFallCount(): Int {
        val start = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        return store.possibleFallCountSince(start)
    }
    private fun addPeaceSummary(body: LinearLayout, recipients: List<TrustedContact>) {
        val summary = card(body)
        summary.addView(text("Resumen de tranquilidad", 21, true))
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val falls = column().apply { setPadding(0, 0, dp(10), 0) }
        falls.addView(text("${todayPossibleFallCount()}", 28, true, accent))
        falls.addView(text("posibles caídas hoy", 14, color = muted))
        row.addView(falls, LinearLayout.LayoutParams(0, -2, 1f))
        val support = column()
        support.addView(text("${recipients.size}", 28, true, accent))
        support.addView(text("contactos de apoyo", 14, color = muted))
        row.addView(support, LinearLayout.LayoutParams(0, -2, 1f))
        summary.addView(row)
        val last = store.eventHistory(limit = 1).firstOrNull()
        val lastText = last?.let {
            "Último registro · ${SimpleDateFormat("HH:mm", Locale("es", "MX")).format(Date(it.optLong("time")))} · ${it.optString("message")}"
        } ?: "Sin registros todavía · aquí verás el estado de tus alertas."
        summary.addView(text(lastText, 14, color = muted).apply { setPadding(0, dp(12), 0, 0) })

        val caregiver = card(body)
        caregiver.addView(text("Cuidadora principal", 14, true, accent))
        val primary = recipients.firstOrNull()
        caregiver.addView(text(primary?.name?.ifBlank { primary.phone } ?: "Agrega un contacto de confianza", 19, true))
        caregiver.addView(text(primary?.phone ?: "Tu red recibirá avisos si se activa una alerta.", 15, color = muted))
        caregiver.addView(button(if (primary == null) "Administrar contactos" else "Llamar con el teléfono", false) {
            if (primary == null) selectTab(2) else runCatching {
                startActivity(Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", primary.phone, null)))
            }.onFailure { toast("No se pudo abrir el marcador del teléfono.", true) }
        })
    }

    private fun activity(body: LinearLayout) {
        if (isCameraRole) { cameraActivity(body); return }
        if (isCaregiver) caregiverActivity(body) else alerts(body, history = false)
    }

    private enum class EventCategory { DEVICE, FALL, OTHER }

    private fun eventCategory(event: JSONObject): EventCategory {
        val message = event.optString("message").lowercase(Locale.ROOT)
        return when {
            message.contains("caída") || message.startsWith("alerta cancelada") -> EventCategory.FALL
            listOf("pulsera", "bluetooth", "arduino", "conexión").any(message::contains) -> EventCategory.DEVICE
            else -> EventCategory.OTHER
        }
    }

    private fun disconnectedEvent(event: JSONObject): Boolean {
        val copy = event.optString("message").lowercase(Locale.ROOT)
        return eventCategory(event) == EventCategory.DEVICE && (
            "desconect" in copy || "sin conexión" in copy || "no se pudo conectar" in copy
        )
    }

    private fun alerts(body: LinearLayout, history: Boolean) {
        val eventCount = store.eventHistoryCount()
        val offset = if (history) historyOffset else 0
        val records = store.eventHistory(limit = 100, offset = offset)
        if (history) {
            v5.header(body, "Historial de alertas", "Cada evento, con su contexto.", gapBelow = 31)
            val hero = v5.panel(body, tz.primaryFill, radius = 25)
            val row = v5.horizontal()
            row.addView(v5.icon(R.drawable.v5_clock, 39, Color.WHITE))
            row.addView(column().apply {
                addView(v5.text("Tu actividad en orden", 18, Color.WHITE, bold = true))
                gap(this, 8)
                addView(v5.text("Avisos, cancelaciones y dispositivos", 12, v5.onBlueMuted))
            }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(14) })
            hero.addView(row)
        } else {
            v5.header(body, "Alertas", "Tu actividad de protección, en orden.", settings = ::openSettings, gapBelow = 30)
            val hero = v5.panel(body, tz.primaryFill, radius = 27, padding = 18, margin = 18)
            val row = v5.horizontal().apply { minimumHeight = dp(92) }
            row.addView(column().apply {
                addView(v5.label("ACTIVIDAD DE PROTECCIÓN", v5.onBlueMuted))
                val count = v5.horizontal()
                count.addView(v5.text(eventCount.toString(), 52, Color.WHITE, bold = true))
                count.addView(v5.text("eventos\nregistrados", 15, Color.WHITE).apply {
                    setPadding(dp(10), 0, 0, 0)
                })
                addView(count)
                addView(v5.text(TzindaText.count(records.count { eventCategory(it) == EventCategory.DEVICE }, "dispositivo") + " · " +
                    TzindaText.count(records.count { eventCategory(it) == EventCategory.FALL }, "detección", "detecciones"),
                    11, v5.onBlueMuted))
            }, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(v5.art(R.drawable.v5_art_alert), LinearLayout.LayoutParams(dp(88), dp(94)).apply { marginEnd = dp(15) })
            hero.addView(row)
        }
        val categories = if (history) listOf(null, EventCategory.FALL, EventCategory.DEVICE)
            else listOf(null, EventCategory.DEVICE, EventCategory.FALL)
        val filteredBy = categories.indexOf(EventCategory.entries.getOrNull(activityFilter - 1)).coerceAtLeast(0)
        v5.segments(body, if (history) listOf("Todos", "Caídas", "Dispositivos") else listOf("Todo", "Dispositivos", "Caídas"),
            filteredBy, blue = !history) { index ->
            activityFilter = categories[index]?.let { it.ordinal + 1 } ?: 0
            render()
        }
        gap(body, 16)
        val visible = records.filter { candidate -> categories[filteredBy]?.let { it == eventCategory(candidate) } ?: true }
            .let { if (history) it else it.take(2) }
        if (visible.isEmpty()) {
            val empty = v5.panel(body)
            empty.addView(v5.title(if (records.isEmpty()) "Aún no hay alertas" else "Sin eventos en esta categoría", 18))
            gap(empty, 8)
            empty.addView(v5.text("Aquí aparecerán las detecciones y avisos que registre este teléfono.", 13, muted))
        }
        visible.groupBy { dayLabel(it.optLong("time")) }.forEach { (day, events) ->
            body.addView(if (history) v5.title(day, 20) else v5.label(day.uppercase(Locale("es", "MX"))))
            gap(body, 12)
            if (history) {
                val group = v5.panel(body, padding = 0, margin = 20)
                events.forEachIndexed { index, event ->
                    val disconnected = disconnectedEvent(event)
                    val fall = eventCategory(event) == EventCategory.FALL
                    val subtitle = event.optString("message")
                    val title = eventTitle(event)
                    val cancelled = title == "Alerta cancelada"
                    v5.row(group, title, eventTime(event), when {
                        disconnected -> R.drawable.v5_watch
                        cancelled -> R.drawable.v5_check
                        fall -> R.drawable.v5_warning
                        else -> R.drawable.v5_bell
                    }, fill = when {
                        disconnected -> tz.warningSurface
                        cancelled -> tz.mint
                        fall -> tz.criticalSurface
                        else -> tz.brandSoft
                    }, fg = when {
                        disconnected -> tz.warning
                        cancelled -> tz.positive
                        fall -> tz.onCriticalSurface
                        else -> accent
                    }, divider = index < events.lastIndex, minHeight = 78) { openAlertEvent(event) }
                        .contentDescription = "$title. $subtitle"
                }
            } else {
                events.forEach { event -> alertPreview(body, event) }
            }
        }
        if (!history) {
            val link = v5.panel(body, tz.brandSoft, padding = 0, margin = 0)
            v5.row(link, "Historial completo", "Consulta todos tus eventos", R.drawable.v5_clock,
                fill = tz.cardColor, minHeight = 76, titleColor = accent, emphasis = true) { selectTab(TzindaTabs.ALERT_HISTORY) }
        } else {
            if (historyOffset > 0) {
                v5.action(body, "Eventos más recientes", fill = tz.cardColor, fg = accent) {
                    historyOffset = (historyOffset - 100).coerceAtLeast(0)
                    lastRenderKey = null
                    render()
                }
            }
            if (eventCount > historyOffset + 100) {
                v5.action(body, "Cargar eventos anteriores", fill = tz.cardColor, fg = accent) {
                    historyOffset += 100
                    lastRenderKey = null
                    render()
                }
            }
            v5.action(body, "Preparar un reporte", R.drawable.v5_report, tz.cardColor, accent) { selectTab(TzindaTabs.REPORT) }
        }
    }

    private fun alertPreview(body: LinearLayout, event: JSONObject) {
        val category = eventCategory(event)
        val device = category == EventCategory.DEVICE
        val fall = category == EventCategory.FALL
        val fg = when { device -> tz.warning; fall -> tz.onCriticalSurface; else -> accent }
        val tint = when { device -> tz.warningSurface; fall -> tz.criticalSurface; else -> tz.brandSoft }
        val panel = v5.panel(body, padding = 14, margin = 16)
        val row = v5.horizontal()
        row.addView(v5.badge(when { device -> R.drawable.v5_watch; fall -> R.drawable.v5_motion; else -> R.drawable.v5_bell },
            tint, fg, 61, 37, 18))
        val description = column().apply {
            addView(v5.label(when { device -> "DISPOSITIVO"; fall -> "DETECCIÓN"; else -> "ACTIVIDAD" }, fg))
            gap(this, 6)
            addView(v5.title(eventTitle(event), 17))
            gap(this, 6)
            val message = event.optString("message")
            addView(v5.text(message.substringAfter('·', message).trim(), 12, muted).apply {
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
            })
            gap(this, 7)
            addView(v5.text(eventTime(event), 11, muted))
        }
        row.addView(description, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(15) })
        val arrow = v5.badge(R.drawable.ic_chevron, tz.brandSoft, accent, 25, 12, 99)
        row.addView(arrow, LinearLayout.LayoutParams(dp(25), dp(25)).apply { gravity = Gravity.TOP })
        panel.addView(row)
        panel.contentDescription = eventTitle(event) + ". " + eventTime(event)
        v5.activate(panel) { openAlertEvent(event) }
    }

    private fun eventTitle(event: JSONObject): String {
        val message = event.optString("message", "Evento de AxTarget Care")
        return when {
            disconnectedEvent(event) -> "Pulsera desconectada"
            message.startsWith("Posible caída") || message.startsWith("Detección con cámara IA señaló") -> "Posible caída"
            message.startsWith("Alerta cancelada") -> "Alerta cancelada"
            else -> message.substringBefore('·').trim()
        }
    }

    private fun eventTime(event: JSONObject): String = event.optLong("time").takeIf { it > 0L }?.let {
        SimpleDateFormat("HH:mm", Locale("es", "MX")).format(Date(it))
    } ?: "Hora no disponible"

    private fun openAlertEvent(event: JSONObject) {
        selectedEvent = event
        selectTab(if (disconnectedEvent(event)) TzindaTabs.BRACELET_ALERT else TzindaTabs.ALERT_DETAILS)
    }

    private fun incidentEntries(incidentId: String): List<JSONObject> = store.eventHistoryForIncident(incidentId)

    private fun alertDetails(body: LinearLayout) {
        val event = selectedEvent
        val subtitle = event?.let { dayLabel(it.optLong("time")) + " · " + eventTime(it) }.orEmpty()
        v5.header(body, "Detalle de la alerta", subtitle, gapBelow = 31)
        val summary = v5.panel(body, tz.criticalSurface, radius = 29, padding = 18, margin = 22).apply {
            minimumHeight = tz.controlScale(151)
            setPadding(paddingLeft, dp(20), paddingRight, paddingBottom)
        }
        val category = event?.let(::eventCategory)
        val tag = v5.horizontal()
        tag.addView(v5.icon(if (category == EventCategory.FALL) R.drawable.v5_warning else R.drawable.v5_bell, 22, tz.onCriticalSurface))
        tag.addView(v5.label(if (category == EventCategory.FALL) "DETECCIÓN EXPERIMENTAL" else "REGISTRO DE ACTIVIDAD",
            tz.onCriticalSurface), LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(14) })
        summary.addView(tag)
        gap(summary, 14)
        summary.addView(v5.title(event?.let(::eventTitle) ?: "Registro no disponible", 27))
        gap(summary, 14)
        val eventInfo = v5.horizontal()
        eventInfo.addView(v5.text(subtitle, 13, tz.onCriticalSurface), LinearLayout.LayoutParams(0, -2, 1f))
        val source = event?.optString("message")?.takeIf { it.startsWith("Posible caída ·") }
            ?.substringAfter('·')?.trim().orEmpty()
        if (source.isNotEmpty()) {
            eventInfo.addView(v5.text(source, 11, tz.onCriticalSurface, bold = true).apply {
                setPadding(dp(11), dp(5), dp(11), dp(5))
                maxWidth = dp(138)
            })
        }
        summary.addView(eventInfo)
        v5.section(body, "Recorrido del evento")
        val entries = event?.let { incidentEntries(it.optString("incident")).ifEmpty { listOf(it) } }.orEmpty().asReversed()
        if (entries.isNotEmpty()) {
            val timeline = v5.timeline(body, margin = 25)
            entries.forEach { item ->
                val message = item.optString("message")
                val res = when {
                    "Llamada" in message -> R.drawable.v5_phone
                    "SMS" in message -> R.drawable.v5_report
                    else -> R.drawable.v5_motion
                }
                val title = when {
                    message.startsWith("Llamada solicitada") -> "Intento de llamada"
                    message.startsWith("SMS solicitado") -> "Aviso solicitado"
                    else -> eventTitle(item)
                }
                val context = if (title == message.substringBefore('·').trim()) message.substringAfter('·', "").trim() else message
                val detail = if (context.isBlank()) eventTime(item) else eventTime(item) + " · " + context
                v5.row(timeline, title, detail, res, tz.criticalSurface, tz.onCriticalSurface, minHeight = 78, round = true)
            }
        }
        val location = v5.panel(body, padding = 0)
        v5.row(location, "Ubicación del evento", "No hay un dato de ubicación vinculado a este evento.",
            R.drawable.v5_pin, minHeight = 76)
        v5.action(body, "Abrir historial", R.drawable.v5_clock) { selectTab(TzindaTabs.ALERT_HISTORY) }
    }

    private fun helpRequest(body: LinearLayout) {
        val entries = incidentEntries(helpIncident)
        helpSignature = entries.map { it.optString("message") }
        v5.header(body, "Solicitud de ayuda", "Consulta el estado de los avisos solicitados.", gapBelow = 33)
        val summary = v5.panel(body, tz.criticalSurface, radius = 27, padding = 18, margin = 33)
        val row = v5.horizontal()
        row.addView(column().apply {
            addView(v5.label("AVISOS SOLICITADOS", tz.onCriticalSurface))
            gap(this, 13)
            addView(v5.title("Tu red está en el proceso de aviso.", 25))
        }, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(v5.badge(R.drawable.v5_phone, tz.cardColor, tz.onCriticalSurface, 57, 35, 99),
            LinearLayout.LayoutParams(dp(57), dp(57)).apply { gravity = Gravity.TOP })
        summary.addView(row)
        gap(summary, 14)
        summary.addView(v5.text("La recepción aún no está confirmada.", 12, tz.onCriticalSurface))
        v5.section(body, "Estado de los intentos")
        val attempts = v5.timeline(body, margin = 22)
        helpDispatchActive = store.prefs.getString("dispatch_id", "").orEmpty() == helpIncident && helpIncident.isNotBlank()
        fun attempt(label: String, requested: String, icon: Int) {
            val result = helpSignature.firstOrNull { it.startsWith(requested) }
            val title = when {
                result != null -> requested
                helpDispatchActive -> "$label en preparación"
                else -> "$label sin confirmación de solicitud"
            }
            v5.row(attempts, title, result ?: if (helpDispatchActive) "Esperando registro" else "Consulta el registro del evento",
                icon, tz.criticalSurface, tz.onCriticalSurface, minHeight = 80, round = true)
        }
        attempt("SMS", "SMS solicitado", R.drawable.v5_report)
        attempt("Llamada", "Llamada solicitada", R.drawable.v5_phone)
        v5.row(attempts, "Respuesta pendiente", "Sin confirmación disponible", R.drawable.v5_clock,
            tz.criticalSurface, tz.onCriticalSurface, minHeight = 80, round = true)
        v5.note(body, "La entrega depende de cobertura,\npermisos y operador.")
        gap(body, 8)
        v5.action(body, "Ver registro del evento", R.drawable.v5_motion) {
            val event = entries.lastOrNull()
            if (event != null) openAlertEvent(event) else selectTab(TzindaTabs.ALERT_HISTORY)
        }
        v5.action(body, "Volver a Inicio", fill = tz.cardColor, fg = accent) { resetNavigation(TzindaTabs.HOME) }
    }

    private fun readingScreen(body: LinearLayout, pulse: Boolean) {
        val title = if (pulse) "Frecuencia cardíaca" else "Oxígeno en sangre"
        val icon = if (pulse) R.drawable.v5_pulse else R.drawable.v5_oxygen
        val tone = if (pulse) tz.criticalSurface else tz.brandSoft
        val ink = if (pulse) tz.onCriticalSurface else accent
        val history = store.telemetry.recentArduinoVitalHistory(30).reversed()
        val samples = history.mapNotNull {
            val value = if (pulse) it.heartRateBpm else it.spo2Percent
            value?.takeIf(Double::isFinite)?.let { sample -> it.capturedAtMillis to sample }
        }
        val measurements = store.telemetry.latestArduinoVitals()
        val key = if (pulse) "bpm" else "spo2"
        val stamp = measurements.optLong(if (pulse) "heart_rate_at" else "spo2_at")
        val value = measurements.optDouble(key).takeIf { it.isFinite() && it > 0 }
        val latest = if (value != null && stamp > 0L) stamp to value else samples.lastOrNull()
        val chartSamples = if (samples.isEmpty() && latest != null) listOf(latest) else samples
        val unit = if (pulse) "lpm" else "SpO₂ · %"
        val time = latest?.first?.let { SimpleDateFormat("d MMM · HH:mm", Locale("es", "MX")).format(Date(it)) }
            ?: "Sin registros disponibles"
        v5.header(body, title, "Lecturas válidas recibidas de tu pulsera.", gapBelow = 30)
        val graph = v5.panel(body, radius = 29, padding = 18, margin = 25)
        val header = v5.horizontal()
        header.addView(v5.badge(icon, tone, ink, 45, 26))
        header.addView(column().apply {
            addView(v5.label("ÚLTIMA LECTURA"))
            gap(this, 7)
            addView(v5.text(time, 12, muted))
        }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(16) })
        graph.addView(header)
        gap(graph, 18)
        val metric = v5.horizontal().apply { gravity = Gravity.BOTTOM }
        metric.addView(v5.text(latest?.second?.let(::vitalNumber) ?: "—", 38, bold = true))
        metric.addView(v5.text(unit, 14, muted).apply { setPadding(dp(19), 0, 0, dp(6)) })
        graph.addView(metric)
        gap(graph, 47)
        val plot = FrameLayout(this)
        val grid = column()
        repeat(3) { index ->
            grid.addView(View(this).apply { setBackgroundColor(outline) }, LinearLayout.LayoutParams(-1, dp(1)))
            if (index < 2) gap(grid, 40)
        }
        plot.addView(grid, FrameLayout.LayoutParams(-1, -2))
        if (chartSamples.isEmpty()) {
            plot.addView(v5.badge(icon, tone, ink, 54, 32, 99), FrameLayout.LayoutParams(dp(54), dp(54), Gravity.CENTER))
        } else {
            val spark = vitalSparkline(chartSamples.map { it.second.toFloat() }, tz.cardColor).apply {
                lineColor = ink
                contentDescription = "$title. " + chartSamples.size + " lecturas."
            }
            plot.addView(spark, FrameLayout.LayoutParams(-1, -1))
        }
        graph.addView(plot, LinearLayout.LayoutParams(-1, dp(84)))
        if (chartSamples.isEmpty()) {
            gap(graph, 4)
            graph.addView(v5.text("Aún no hay lecturas", 14, muted).apply { gravity = Gravity.CENTER })
        }
        gap(graph, 30)
        val ticks = v5.horizontal()
        ticks.addView(v5.text(if (chartSamples.isEmpty()) "00:00" else "Primera lectura", 10, muted))
        v5.push(ticks)
        if (chartSamples.isEmpty()) {
            ticks.addView(v5.text("12:00", 10, muted))
            v5.push(ticks)
        }
        ticks.addView(v5.text(if (chartSamples.isEmpty()) "23:59" else "Más reciente", 10, muted))
        graph.addView(ticks)
        v5.section(body, "Sobre los datos")
        val source = v5.panel(body, padding = 0, margin = 20)
        v5.row(source, "Fuente", "Pulsera Tzinda", R.drawable.v5_watch, minHeight = 67, divider = true)
        v5.row(source, "Última actualización", time, R.drawable.v5_clock, minHeight = 67)
        v5.note(body, "Las lecturas aparecerán cuando\nla pulsera envíe datos válidos.", margin = 21)
        v5.action(body, "Revisar conexión", R.drawable.v5_bluetooth) { selectTab(TzindaTabs.BRACELET_PAIR) }
    }

    private fun cameraActivity(body: LinearLayout) {
        heading(body, "EVENTOS DE CÁMARA", "Historial de detecciones", "Cada detección se guarda primero en este teléfono y se sincroniza con la persona protegida cuando hay Internet.")
        val linked = store.prefs.getString("paired_camera_device_id", "").orEmpty().isNotBlank()
        val status = card(body)
        status.addView(statusPill(TzindaStatusCopy.cameraLink(linked, store.prefs.getString("paired_camera_name", "").orEmpty())))
        status.addView(text(if (linked) "Las nuevas detecciones se enviarán a la cuenta protegida cuando haya conexión." else "Vincula esta cámara para compartir las detecciones autorizadas.", 14, color = muted).apply { setPadding(0, dp(8), 0, 0) })
        if (linked) status.addView(button("Sincronizar ahora", false) {
            SyncScheduler.enqueue(applicationContext, immediate = true)
            toast("Sincronización solicitada.")
        })
        val cameraEvents = store.eventHistory(limit = cameraHistoryLimit).filter { row ->
            row.optString("message").contains("cámara", ignoreCase = true)
        }
        if (cameraEvents.isEmpty()) {
            tz.emptyState(
                body,
                "Sin detecciones todavía",
                "Abre la cámara de apoyo para iniciar el detector local. Por privacidad, la cámara no se activa en segundo plano.",
                "Abrir cámara",
                action = { openCameraScreen() },
            )
        } else {
            tz.section(body, "Detecciones guardadas", TzindaText.count(cameraEvents.size, "evento"))
            cameraEvents.forEach { event ->
                val row = card(body)
                row.addView(text(SimpleDateFormat("d MMM yyyy · HH:mm", Locale("es", "MX")).format(Date(event.optLong("time"))), 13, true, accent))
                row.addView(text(event.optString("message"), 17, true))
                row.addView(statusPill(TzindaCopy("Procesado en este teléfono", "La imagen no se guarda ni se comparte.", TzindaTone.INFO)))
            }
        }
        if (store.eventHistoryCount() > cameraHistoryLimit) {
            body.addView(button("Cargar eventos anteriores", false) {
                cameraHistoryLimit = minOf(store.eventHistoryCount(), cameraHistoryLimit + 100)
                render()
            })
        }
    }

    private fun contacts(body: LinearLayout) {
        if (isCaregiver) { caregiverPersonScreen(body); return }
        if (editingContacts) { contactManagement(body); return }
        v5.header(body, "Tu red de confianza", "Elige a quién avisar si necesitas ayuda.", gapBelow = 30)
        val contacts = store.contacts()
        val recipients = store.recipients()
        if (recipients.isEmpty()) {
            val empty = v5.panel(body, radius = 27, padding = 20, margin = 24).apply { gravity = Gravity.CENTER_HORIZONTAL }
            gap(empty, 6)
            empty.addView(v5.art(R.drawable.v5_art_contacts), LinearLayout.LayoutParams(dp(202), dp(124)))
            gap(empty, 19)
            empty.addView(v5.title("Cuidarse también\nes estar conectado.", 25).apply { gravity = Gravity.CENTER })
            gap(empty, 16)
            empty.addView(v5.text("Aún no has añadido contactos.", 13, muted).apply { gravity = Gravity.CENTER })
        } else {
            val list = v5.panel(body, padding = 0, margin = 24)
            recipients.forEachIndexed { i, item ->
                val normalized = PhoneNumbers.key(item.phone)
                val row = contacts.indexOfFirst { PhoneNumbers.key(it.phone) == normalized }
                v5.row(list, item.name.ifBlank { "Contacto " + (i + 1) }, item.phone, R.drawable.v5_people,
                    divider = i < recipients.lastIndex) {
                    if (row >= 0) startContact(row) else openProfile()
                }
            }
        }
        v5.section(body, "Cómo funciona tu red")
        val protocol = v5.panel(body, padding = 0, margin = 27)
        v5.row(protocol, "Avisos por SMS", "Se solicitan a tus contactos registrados", R.drawable.v5_report, minHeight = 69, divider = true)
        v5.row(protocol, "Intentos de llamada", "Según permisos, cobertura y operador", R.drawable.v5_phone, tz.mint, tz.positive, minHeight = 69)
        v5.note(body, "Confirma con cada contacto\nque puede ayudarte.", R.drawable.v5_people)
        gap(body, 13)
        if (contacts.size < 5) v5.action(body, "Añadir contacto", R.drawable.ic_plus) { startContact(-1) }
        if (recipients.isEmpty()) gap(body, 86)
        v5.action(body, "Administrar contactos", fill = tz.cardColor, fg = accent) {
            editingContacts = true
            lastRenderKey = null
            render()
        }
    }

    private fun startContact(index: Int) {
        contactIndex = index
        contactNameDraft = store.contacts().getOrNull(index)?.name.orEmpty()
        contactPhoneDraft = store.contacts().getOrNull(index)?.phone.orEmpty()
        selectTab(TzindaTabs.ADD_CONTACT)
    }

    private fun addContactScreen(body: LinearLayout) {
        val editing = contactIndex >= 0
        v5.header(body, if (editing) "Editar contacto" else "Añadir contacto",
            "Una persona que forma parte de tu red.", gapBelow = 31)
        val hint = v5.panel(body, tz.brandSoft, radius = 27, padding = 20, margin = 25).apply {
            minimumHeight = dp(118)
            gravity = Gravity.CENTER_VERTICAL
        }
        val row = v5.horizontal()
        row.addView(v5.badge(R.drawable.v5_people, tz.cardColor, accent, 69, 44, 99))
        row.addView(column().apply {
            addView(v5.title("Tu persona de confianza", 17))
            gap(this, 7)
            addView(v5.text("Podrá recibir tus avisos\ncuando necesites ayuda.", 12, muted))
        }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(17) })
        hint.addView(row)
        v5.section(body, "Información de contacto")
        gap(body, 9)
        val name = v5.field(body, "Nombre", contactNameDraft, "Nombre del contacto", trailingIcon = R.drawable.v5_people, tag = "contact_name")
        gap(body, 7)
        val phone = v5.field(body, "Teléfono con código de país", contactPhoneDraft, "Ej. +52 55 0000 0000",
            InputType.TYPE_CLASS_PHONE, trailingIcon = R.drawable.v5_phone, tag = "contact_phone")
        contactNameInput = name
        contactPhoneInput = phone
        gap(body, 13)
        v5.note(body, "Revisa que el número corresponda\na la persona que quieres añadir.")
        gap(body, 20)
        v5.action(body, if (editing) "Guardar cambios" else "Guardar contacto", R.drawable.v5_check) {
            val normalized = PhoneNumbers.normalize(phone.text.toString())
            if (normalized == null) {
                phone.error = "Escribe un teléfono válido."
                phone.requestFocus()
                return@action
            }
            val contacts = store.contacts().toMutableList()
            if (contacts.indices.any { it != contactIndex && PhoneNumbers.key(contacts[it].phone) == PhoneNumbers.key(normalized) }) {
                phone.error = "Este teléfono ya está en tu red."
                return@action
            }
            if ((editing && contactIndex !in contacts.indices) || (!editing && contacts.size >= 5)) {
                toast("La lista de contactos cambió. Vuelve a abrirla.", true)
                return@action
            }
            val contact = TrustedContact(name.text.toString(), normalized)
            if (editing) contacts[contactIndex] = contact else contacts.add(contact)
            runCatching { store.saveContacts(contacts) }.onSuccess {
                contactDraft = it
                editingContacts = false
                updateServiceIfActive()
                navigateBack()
            }.onFailure { toast(it.message ?: "No se pudo guardar el contacto.", true) }
        }
        v5.action(body, "Cancelar", fill = tz.cardColor, fg = accent) { navigateBack() }
        if (editing) v5.action(body, "Llamar", R.drawable.v5_phone, tz.cardColor, accent) {
            val savedNumber = store.contacts().getOrNull(contactIndex)?.phone ?: return@action
            runCatching { startActivity(Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", savedNumber, null))) }
                .onFailure { toast("No se pudo abrir el marcador.") }
        }
    }

    private fun contactManagement(body: LinearLayout) {
        val recipients = store.recipients()
        val emergencyKey = PhoneNumbers.normalize(store.profile().text("emergencyContactPhone"))?.let { PhoneNumbers.key(it) }
        heading(body, "TU RED DE APOYO", "Contactos de confianza", "Estas personas reciben los SMS y las llamadas de alerta.")
        val protocol = card(body)
        protocol.addView(statusPill(TzindaCopy("Protocolo de 20 segundos", "Puedes cancelar con “Estoy bien” antes de que se avise a tu red.", TzindaTone.INFO)))
        protocol.addView(text("Si se confirma una posible caída, tienes 20 segundos para cancelar. Después se solicitan los SMS y comienzan las llamadas a tu red de apoyo.", 14, color = muted).apply { setPadding(0, dp(8), 0, 0) })
        protocol.addView(text("Los avisos dependen de la SIM, permisos, cobertura y disponibilidad del teléfono. AxTarget Care no puede confirmar que otra persona los haya leído.", 13, color = muted))

        if (recipients.isEmpty()) {
            tz.emptyState(
                body,
                "Aún no tienes contactos",
                "Agrega al menos una persona para activar el monitoreo y enviar alertas.",
                "Agregar contactos",
                action = { editingContacts = true; render() },
            )
        } else {
            tz.section(body, "Orden de aviso", TzindaText.count(recipients.size, "destinatario"))
            recipients.forEachIndexed { index, contact ->
                val item = card(body)
                val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                val initials = contact.name.trim().split(Regex("\\s+")).filter { it.isNotBlank() }.take(2)
                    .mapNotNull { it.firstOrNull()?.uppercaseChar() }.joinToString("").ifBlank { "${index + 1}" }
                row.addView(tz.avatar(initials, 48, TzindaDesign.Tone.BRAND), LinearLayout.LayoutParams(dp(48), dp(48)))
                val detail = column().apply { setPadding(dp(12), 0, 0, 0) }
                detail.addView(text(contact.name.ifBlank { "Contacto ${index + 1}" }, 17, true))
                detail.addView(text(contact.phone, 15, color = muted))
                row.addView(detail, LinearLayout.LayoutParams(0, -2, 1f))
                item.addView(row)
                val isEmergency = PhoneNumbers.key(contact.phone) == emergencyKey
                item.addView(statusPill(TzindaCopy(
                    if (isEmergency) "Contacto de emergencia del perfil" else "Prioridad ${index + 1}",
                    if (index == 0) "Se avisa primero" else "Se avisa después de la prioridad anterior",
                    if (isEmergency) TzindaTone.POSITIVE else TzindaTone.NEUTRAL,
                )))
                item.addView(tz.callButton("Llamar", contact.phone) {
                    runCatching { startActivity(Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", contact.phone, null))) }
                        .onFailure { toast("No se pudo abrir el marcador del teléfono.", true) }
                })
            }
        }

        val quick = card(body)
        quick.addView(text("Ajustes rápidos de aviso", 20, true))
        quick.addView(text("La ubicación se actualiza solo mientras la protección está activa y la autorizas. Se adjunta a los avisos locales; no se envía a otro dispositivo sin sincronización configurada.", 14, color = muted))
        tz.switchRow(
            quick,
            "Compartir ubicación en vivo y alertas",
            "Se usa durante el monitoreo activo.",
            store.prefs.getBoolean("include_location", false),
            action = { value -> setLocationConsent(value) },
        )
        quick.addView(button("Actualizar ubicación", false) { requestLocation() })

        val test = card(body)
        test.addView(text("Probar el protocolo de aviso", 20, true))
        test.addView(text("La prueba inicia una cuenta regresiva real. Si no la cancelas, puede solicitar SMS y realizar llamadas a tus contactos.", 14, color = muted))
        test.addView(button("Iniciar prueba de cuenta regresiva", false) {
            AlertDialog.Builder(this).setTitle("Iniciar prueba")
                .setMessage("La prueba usa tu SIM real: si no pulsas Estoy bien antes de que termine, AxTarget Care solicitará SMS y llamadas a tus contactos.")
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Continuar") { _, _ -> prepareAlert(GuardianMonitorService.ACTION_TEST) }.show()
        })

        val editor = card(body)
        editor.addView(text(if (editingContacts) "Editar lista de contactos" else "Administrar contactos", 20, true))
        editor.addView(text("Hasta 5 contactos. El contacto principal se llama primero en caso de alerta.", 14, color = muted))
        if (!editingContacts) {
            editor.addView(button(if (recipients.isEmpty()) "Agregar contactos" else "Editar contactos") { editingContacts = true; render() }.apply { tag = "edit_contacts" })
            editor.addView(button("Importar desde mi agenda", false) {
                editingContacts = true; render(); handler.post { beginImport(-1) }
            })
            return
        }
        editor.addView(button("Importar desde mi agenda", false) { beginImport(-1) })
        val rows = contactDraft ?: store.contacts()
        for (i in 0 until 5) {
            val entry = card(editor)
            entry.addView(text("CONTACTO ${i + 1}${if (i == 0) " · LLAMADA PRIORITARIA" else ""}", 12, true, accent))
            val name = field(entry, "Nombre (opcional)", rows.getOrNull(i)?.name.orEmpty(), tag = "contact_name_$i")
            val phone = field(entry, "Teléfono", rows.getOrNull(i)?.phone.orEmpty(), InputType.TYPE_CLASS_PHONE, "contact_phone_$i")
            contactEdits.add(name to phone)
            entry.addView(button("Elegir de la agenda", false) { beginImport(i) })
        }
        editor.addView(button("Guardar contactos") { saveContactForm() }.apply { tag = "save_contacts" })
        editor.addView(button("Cancelar edición", false) {
            contactDraft = null; editingContacts = false; hideKeyboard(); render()
        })
    }

    private fun settings(body: LinearLayout) {
        v5.header(body, "Ajustes", gapBelow = 10)

        v5.section(body, "Tu experiencia")
        val experience = v5.panel(body, padding = 15, margin = 16).apply {
            setPadding(paddingLeft, paddingTop, paddingRight, tz.space(13))
        }
        v5.toggleRow(experience, "Modo simplificado", "Texto y controles más grandes", R.drawable.v5_access,
            simpleMode, height = 50, offColor = if (dark) outline else 0xFFC3CAD0.toInt()) { value ->
            store.prefs.edit().putBoolean("simple_accessibility", value).apply()
            render()
        }
        gap(experience, 5)
        tz.horizontalRule(experience)
        gap(experience, 11)
        experience.addView(v5.text("Apariencia", 13, muted))
        gap(experience, 16)
        val modes = v5.horizontal()
        listOf("Claro" to R.drawable.v5_sun, "Oscuro" to R.drawable.v5_moon).forEachIndexed { index, (title, icon) ->
            val selectedDark = index == 1
            val selected = selectedDark == dark
            val control = tz.button(title, primary = selected, action = {
                if (!selected) {
                    store.prefs.edit().putBoolean("dark_mode", selectedDark).apply()
                    recreate()
                }
            }, iconRes = icon).apply {
                minHeight = dp(40)
                minimumHeight = dp(40)
                textSize = tz.textScale(13f)
                setPadding(dp(12), dp(4), dp(12), dp(4))
                background = rounded(if (selected) tz.primaryFill else tz.softSurface, 13)
                setTextColor(if (selected) Color.WHITE else ink)
                compoundDrawables[0]?.setTint(if (selected) Color.WHITE else muted)
                isSelected = selected
            }
            modes.addView(control, LinearLayout.LayoutParams(0, -2, 1f).apply { if (index == 0) marginEnd = dp(11) })
        }
        experience.addView(modes)
        gap(experience, 12)
        v5.row(experience, "Volver a ver el tutorial", "", R.drawable.v5_info, inset = 0) {
            tutorialReturnTab = tab
            tutorialStep = 0
            render()
        }

        v5.section(body, "Cuidado y protección")
        val protection = v5.panel(body, padding = 0, margin = 18)
        v5.row(protection, if (isCameraRole) "Vincular cámara" else if (isCaregiver) "Persona vinculada" else "Contactos de confianza",
            if (isCameraRole) "Detecciones autorizadas" else "Tu red de confianza", R.drawable.v5_people,
            tz.mint, tz.positive, minHeight = 59, divider = !isCameraRole) { selectTab(TzindaTabs.CONTACTS) }
        if (!isCameraRole) v5.row(protection, "Práctica de alerta", "Propuesta visual", R.drawable.v5_play, minHeight = 59) {
            selectTab(TzindaTabs.ALERT_PRACTICE)
        }

        if (!isCaregiver && !isCameraRole) {
            v5.section(body, "Tus datos")
            val data = v5.panel(body, padding = 0)
            v5.row(data, "Ficha de emergencia", "Propuesta visual", R.drawable.v5_lock, minHeight = 59, divider = true) {
                selectTab(TzindaTabs.EMERGENCY_CARD)
            }
            v5.row(data, "Exportar reporte", "Propuesta visual", R.drawable.v5_report,
                fill = tz.softSurface, fg = tz.warning, minHeight = 59) { selectTab(TzindaTabs.REPORT) }
        }
        gap(body, 24)
        val accountLinks = v5.panel(body, padding = 0)
        v5.row(accountLinks, "Mi perfil", "Tu información", R.drawable.v5_people, divider = true, action = ::openProfile)
        v5.row(accountLinks, "Privacidad", "Aviso de privacidad", R.drawable.v5_lock, divider = true) {
            showPrivacyNotice(manageAccount = true)
        }
        if (sessions.read() == null) {
            v5.row(accountLinks, "Iniciar sesión o crear cuenta", "", R.drawable.v5_people) { openAccess(AuthMode.LOGIN) }
        } else {
            v5.row(accountLinks, "Cerrar sesión", "", R.drawable.v5_lock) { confirmSignOut() }
        }
        body.addView(text("AxTarget Care ${BuildConfig.VERSION_NAME} · Android", 13, color = muted)
            .apply { setPadding(dp(4), dp(18), dp(4), dp(4)) })
    }

    private fun confirmSignOut() {
        AlertDialog.Builder(this).setTitle("Cerrar sesión")
            .setMessage("Volverás a la pantalla de acceso para iniciar sesión o crear una cuenta.")
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Cerrar sesión") { _, _ ->
                val signingOutBackend = backend
                val session = signingOutBackend.clearLocalSession()
                lifecycleScope.launch { runCatching { GoogleSignIn.clear(this@MainActivity) } }
                discardSharedAccountData()
                store.prefs.edit().putBoolean("local_only_mode", false).putBoolean("v9_intro_seen", true).apply()
                authEmailDraft = ""
                authMode = AuthMode.LOGIN
                resetNavigation(if (store.role().isBlank()) ROLE_TAB else TzindaTabs.HOME)
                setAuthStatus("Sesión cerrada.")
                if (session != null) io.execute { runCatching { signingOutBackend.revokeSession(session) } }
            }.show()
    }

    private fun confirmAccountDeletion() {
        AlertDialog.Builder(this).setTitle("Eliminar cuenta")
            .setMessage("Se eliminarán la cuenta y sus datos remotos. Los contactos y registros de este teléfono se conservarán. Esta acción es irreversible.")
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Eliminar cuenta") { _, _ ->
                io.execute {
                    val result = runCatching { backend.deleteAccount() }
                    runOnUiThread {
                        if (isDestroyed || isFinishing) return@runOnUiThread
                        result.onSuccess {
                            discardSharedAccountData()
                            store.prefs.edit().putBoolean("local_only_mode", true).apply()
                            toast("Cuenta eliminada. La app sigue en modo local.")
                            render()
                        }.onFailure { toast(it.message ?: "No se pudo eliminar la cuenta.", true) }
                    }
                }
            }.show()
    }

    private fun discardSharedAccountData() {
        caregiverLocationRealtime?.stop(); caregiverLocationRealtime = null; caregiverLocationRealtimeDevice = ""
        caregiverHealthRealtime?.stop(); caregiverHealthRealtime = null; caregiverHealthRealtimeKey = ""
        store.clearRemoteDerivedCaches()
    }


    private fun emergencyCard(body: LinearLayout) {
        v5.header(body, "Ficha de emergencia", "Información que tú decides incluir.", gapBelow = 31)
        val profile = store.profile()
        val name = profile.text("fullName").trim()
        val emergencyPhone = profile.text("emergencyContactPhone").trim()
        val medications = medicationRepository.database.medications(medicationRepository.currentOwnerScope())
            .filter { it.isActive && !it.isDeleted }
        val allergies = listOf(profile.text("allergies"), profile.text("medicalConditions")).filter(String::isNotBlank)
        val firstAid = v5.panel(body, tz.primaryFill, radius = 28, padding = 19, margin = 28)
        val row = v5.horizontal()
        row.addView(column().apply {
            addView(v5.label("TU INFORMACIÓN ES TUYA", v5.onBlueMuted))
            gap(this, 14)
            addView(v5.title("Lo esencial,\na la mano.", 28, Color.WHITE))
        }, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(v5.badge(R.drawable.v5_shield, 0x33FFFFFF, Color.WHITE, 53, 33, 99),
            LinearLayout.LayoutParams(dp(53), dp(53)).apply { gravity = Gravity.TOP })
        firstAid.addView(row)
        gap(firstAid, 15)
        firstAid.addView(v5.text(if (name.isBlank()) "Ficha voluntaria · Sin configurar" else "Ficha voluntaria", 12, v5.onBlueMuted))
        gap(firstAid, 4)
        v5.section(body, "Datos de emergencia")
        val fields = v5.panel(body, padding = 0, margin = 22)
        v5.row(fields, "Identificación", name.ifBlank { "Por configurar" }, R.drawable.v5_people,
            minHeight = 68, divider = true, action = ::openProfile)
        v5.row(fields, "Alergias y condiciones", allergies.joinToString(" · ").ifBlank { "Por configurar" }, R.drawable.v5_warning,
            tz.warningSurface, tz.warning, minHeight = 68, divider = true, action = ::openProfile)
        v5.row(fields, "Medicamentos informados", if (medications.isEmpty()) "Por configurar" else TzindaText.count(medications.size, "medicamento"),
            R.drawable.v5_pill, tz.mint, tz.positive, minHeight = 68, divider = true, action = ::openMedicationScreen)
        v5.row(fields, "Contacto de emergencia", profile.text("emergencyContactName").ifBlank {
            emergencyPhone.ifBlank { "Seleccionar un contacto" }
        }, R.drawable.v5_phone, minHeight = 68, action = ::openProfile)
        v5.note(body, "Elige qué datos incluir\ny con quién compartirlos.", R.drawable.v5_lock, margin = 25)
        v5.action(body, "Volver a Ajustes", fill = tz.cardColor, fg = accent) { navigateBack() }
    }

    private fun prepareReport(body: LinearLayout) {
        v5.header(body, "Preparar reporte", "Elige el periodo y qué quieres incluir.", gapBelow = 31)
        val intro = v5.panel(body, tz.brandSoft, radius = 28, padding = 17, margin = 22)
        val summary = v5.horizontal()
        summary.addView(v5.art(R.drawable.v5_art_report),
            LinearLayout.LayoutParams(dp(72), dp(90)).apply { marginStart = dp(5); marginEnd = dp(20) })
        summary.addView(column().apply {
            addView(v5.title("Un resumen a tu medida", 18, accent))
            gap(this, 12)
            addView(v5.text("Revisa el contenido antes\nde decidir con quién compartirlo.", 12, muted))
        }, LinearLayout.LayoutParams(0, -2, 1f))
        intro.addView(summary)
        body.addView(v5.text("Periodo", 13, muted))
        gap(body, 10)
        var reportDays = 0
        val period = v5.action(body, "Seleccionar rango", R.drawable.v5_clock, tz.cardColor, accent,
            margin = 29, trailingIcon = true) {}.apply { setTextColor(muted) }
        period.setOnClickListener {
            val choices = intArrayOf(7, 14, 30, 90)
            AlertDialog.Builder(this).setTitle("Periodo del reporte")
                .setItems(choices.map { "Últimos $it días" }.toTypedArray()) { _, i ->
                    reportDays = choices[i]
                    period.text = "Últimos $reportDays días"
                    period.contentDescription = period.text
                }.show()
        }
        v5.section(body, "Contenido del reporte")
        fun option(title: String, detail: String, icon: Int, active: Boolean): CompoundButton {
            val tile = v5.panel(body, padding = 12, margin = 12, radius = 22)
            return v5.toggleRow(tile, title, detail, icon, active, height = 52)
        }
        val readings = option("Lecturas disponibles", "Frecuencia cardíaca y SpO₂", R.drawable.v5_pulse, true)
        val alerts = option("Historial de alertas", "Eventos y estados técnicos", R.drawable.v5_bell, true)
        val medicines = option("Registro de medicamentos", "Solo los registros que elijas", R.drawable.v5_pill, false)
        gap(body, 10)
        v5.note(body, "Puedes revisar el contenido\nantes de compartir.", R.drawable.v5_lock, margin = 25)
        v5.action(body, "Previsualizar reporte", R.drawable.v5_report) {
            if (reportDays == 0) {
                period.performClick()
                return@action
            }
            if (!readings.isChecked && !alerts.isChecked && !medicines.isChecked) {
                toast("Selecciona al menos un contenido.")
                return@action
            }
            val preview = reportPreview(reportDays, readings.isChecked, alerts.isChecked, medicines.isChecked)
            AlertDialog.Builder(this).setTitle("Vista previa del reporte").setMessage(preview)
                .setNegativeButton("Volver", null)
                .setPositiveButton("Compartir") { _, _ ->
                    val share = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, preview)
                    startActivity(Intent.createChooser(share, "Compartir reporte de AxTarget Care"))
                }.show()
        }
    }

    private fun reportPreview(days: Int, readings: Boolean, alerts: Boolean, medicines: Boolean): String {
        val end = System.currentTimeMillis()
        val start = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -days) }.timeInMillis
        val format = SimpleDateFormat("d MMM yyyy · HH:mm", Locale("es", "MX"))
        return buildString {
            appendLine("AxTarget Care · Reporte")
            appendLine(format.format(Date(start)) + " — " + format.format(Date(end)))
            if (readings) {
                appendLine("\nLecturas disponibles")
                val rows = store.telemetry.recentArduinoVitalHistory(300).filter { it.capturedAtMillis in start..end }
                if (rows.isEmpty()) appendLine("Sin lecturas en el periodo.")
                rows.forEach { reading ->
                    val values = listOfNotNull(reading.heartRateBpm?.let { vitalNumber(it) + " lpm" },
                        reading.spo2Percent?.let { vitalNumber(it) + " % SpO₂" })
                    appendLine(format.format(Date(reading.capturedAtMillis)) + " · " + values.joinToString(" · "))
                }
                if (rows.size == 300) appendLine("Se muestran las 300 lecturas más recientes.")
            }
            if (alerts) {
                appendLine("\nHistorial de alertas")
                val rows = store.eventHistory(300).filter { it.optLong("time") in start..end }
                if (rows.isEmpty()) appendLine("Sin eventos en el periodo.")
                rows.forEach { appendLine(format.format(Date(it.optLong("time"))) + " · " + it.optString("message")) }
                if (rows.size == 300) appendLine("Se muestran los 300 eventos más recientes.")
            }
            if (medicines) {
                appendLine("\nRegistro de medicamentos")
                val owner = medicationRepository.currentOwnerScope()
                val medications = medicationRepository.database.medications(owner).associateBy { it.id }
                val rows = medicationRepository.database.intakes(owner, start, end)
                if (rows.isEmpty()) appendLine("Sin tomas en el periodo.")
                rows.forEach {
                    appendLine(format.format(Date(it.scheduledAtMillis)) + " · " +
                        (medications[it.medicationId]?.name ?: "Medicamento") + " · " + TzindaStatusCopy.medicationStatus(it.status))
                }
                if (rows.size == 300) appendLine("Se muestran hasta 300 tomas del periodo.")
            }
        }.trimEnd()
    }

    /**
     * The camera opens as a full-screen viewfinder: the live image fills the window and the
     * controls float above it. The previous 16:9 card wasted most of a phone screen.
     */

    private fun cameraFullscreen(stage: FrameLayout) {
        stage.setBackgroundColor(v5.cameraSurface)
        val preview = PreviewView(this).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
            contentDescription = "Vista en vivo de la cámara"
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        cameraPreviewView?.previewStreamState?.removeObservers(this)
        cameraPreviewView = preview
        stage.addView(preview, FrameLayout.LayoutParams(-1, -1))
        cameraRoomBackdrop = v5.art(R.drawable.v5_art_room).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
        }.also { stage.addView(it, FrameLayout.LayoutParams(-1, -1)) }
        val overlay = PoseOverlayView(this)
        cameraOverlayView = overlay
        stage.addView(overlay, FrameLayout.LayoutParams(-1, -1))
        val guide = object : View(this) {
            private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xAFFFFFFF.toInt()
                strokeWidth = dp(2).toFloat()
            }
            override fun onDraw(canvas: Canvas) {
                val edge = dp(29).toFloat()
                val length = dp(38).toFloat()
                val top = height * .27f
                val bottom = height * .62f
                fun corner(x: Float, y: Float, xDir: Int, yDir: Int) {
                    canvas.drawLine(x, y, x + xDir * length, y, paint)
                    canvas.drawLine(x, y, x, y + yDir * length, paint)
                }
                corner(edge, top, 1, 1)
                corner(width - edge, top, -1, 1)
                corner(edge, bottom, 1, -1)
                corner(width - edge, bottom, -1, -1)
            }
        }
        stage.addView(guide, FrameLayout.LayoutParams(-1, -1))
        val shade = View(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0xA0173038.toInt(), Color.TRANSPARENT, Color.TRANSPARENT, 0xD0183139.toInt()))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        stage.addView(shade, FrameLayout.LayoutParams(-1, -1))
        val top = v5.horizontal()
        top.addView(v5.iconButton(R.drawable.ic_close, "Cerrar cámara y volver", Color.WHITE, Color.BLACK, 18) { navigateBack() },
            LinearLayout.LayoutParams(dp(44), dp(44)))
        val labels = column().apply { gravity = Gravity.CENTER }
        labels.addView(v5.text("Cámara de estancia", 16, Color.WHITE, bold = true).apply { gravity = Gravity.CENTER })
        gap(labels, 5)
        cameraPlaceholder = v5.text("Vista de ejemplo", 11, 0xFFE0E9E9.toInt()).apply { gravity = Gravity.CENTER }
            .also(labels::addView)
        top.addView(labels, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(8) })
        cameraStateBadge = v5.text("•  En pausa", 11, Color.BLACK, bold = true).apply {
            background = rounded(Color.WHITE, 99)
            setPadding(dp(10), dp(6), dp(10), dp(6))
        }.also { top.addView(it) }
        stage.addView(top, FrameLayout.LayoutParams(-1, -2, Gravity.TOP).apply {
            leftMargin = dp(20); rightMargin = dp(20); topMargin = dp(55)
        })

        val bottom = column()
        val status = v5.panel(bottom, v5.cameraSurface, radius = 25, padding = 16, margin = 18)
        val statusRow = v5.horizontal()
        statusRow.addView(View(this).apply { background = rounded(v5.teal, 99) }, LinearLayout.LayoutParams(dp(8), dp(8)))
        statusRow.addView(column().apply {
            cameraStatusTitle = v5.text("Detección en pausa", 16, Color.WHITE, bold = true).also(::addView)
            gap(this, 7)
            cameraStatusDetail = v5.text("Vista de ejemplo. Inicia el procesamiento local.", 12, 0xFFDCE6E6.toInt()).also(::addView)
        }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(15) })
        status.addView(statusRow)
        val panel = v5.panel(bottom, v5.cameraSurface, radius = 26, padding = 8, margin = 12)
        val controls = v5.horizontal()
        fun control(label: String, res: Int, size: Int, fill: Int, color: Int, action: () -> Unit): LinearLayout {
            val button = column().apply { gravity = Gravity.CENTER; contentDescription = label }
            val frame = FrameLayout(this).apply { background = rounded(fill, 99) }
            val glyph = v5.icon(res, if (size > 50) 34 else 27, color)
            frame.addView(glyph, FrameLayout.LayoutParams(glyph.layoutParams.width, glyph.layoutParams.height, Gravity.CENTER))
            button.addView(frame, LinearLayout.LayoutParams(dp(size), dp(size)))
            gap(button, 6)
            val copy = v5.text(label, 11, Color.WHITE, bold = size > 50)
            button.addView(copy)
            if (size > 50) {
                cameraActionButton = copy
                cameraControlIcon = glyph
                button.tag = "camera_ai_toggle"
            }
            controls.addView(button, LinearLayout.LayoutParams(0, -2, 1f))
            v5.activate(button, action)
            return button
        }
        control("Historial", R.drawable.v5_clock, 50, 0xFF304B54.toInt(), Color.WHITE) { selectTab(TzindaTabs.ACTIVITY) }
        val toggle = control("Iniciar", R.drawable.v5_play, 66, tz.primaryFill, Color.WHITE) { toggleCameraDetection() }
        control("Detener", R.drawable.v5_stop, 50, Color.WHITE, tz.imperialRed) {
            stopCameraDetection()
            navigateBack()
        }
        panel.addView(controls)
        bottom.addView(v5.text("Los fotogramas se procesan en este teléfono.", 11, Color.WHITE).apply {
            gravity = Gravity.CENTER
        })
        stage.addView(bottom, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply {
            marginStart = dp(20); marginEnd = dp(20); bottomMargin = dp(29)
        })
        preview.previewStreamState.observe(this) { state ->
            if (cameraPreviewView === preview) {
                val streaming = cameraRequested && state == PreviewView.StreamState.STREAMING
                if (streaming && cameraStatusTitle?.text == "Preparando cámara") {
                    cameraStatusTitle?.text = "Detección activa"
                    cameraStatusDetail?.text = "Procesando la imagen en este teléfono."
                }
                updateCameraControls(active = streaming, preparing = cameraRequested && !streaming)
            }
        }
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)) {
            cameraStatusTitle?.text = "Cámara no disponible"
            cameraStatusDetail?.text = "Este dispositivo no tiene una cámara compatible."
            toggle.isEnabled = false
        }
    }

    private fun updateCameraControls(active: Boolean, preparing: Boolean = false) {
        cameraRoomBackdrop?.visibility = if (active) View.GONE else View.VISIBLE
        cameraPreviewView?.importantForAccessibility =
            if (active) View.IMPORTANT_FOR_ACCESSIBILITY_AUTO else View.IMPORTANT_FOR_ACCESSIBILITY_NO
        cameraPlaceholder?.text = if (active) "Vista en vivo" else "Vista de ejemplo"
        cameraStateBadge?.text = when {
            active -> "•  Activa"
            preparing -> "Preparando"
            else -> "•  En pausa"
        }
        val pause = active || preparing
        cameraActionButton?.text = if (pause) "Pausar" else "Iniciar"
        (cameraActionButton?.parent as? View)?.contentDescription = if (pause) "Pausar detección" else "Iniciar detección"
        cameraControlIcon?.setImageResource(if (pause) R.drawable.v5_pause else R.drawable.v5_play)
    }

    private fun toggleCameraDetection() {
        if (cameraRequested) {
            stopCameraDetection()
            store.event("Detección con cámara IA detenida por el usuario")
            return
        }
        cameraFallHandled = false
        cameraRequested = true
        if (!has(Manifest.permission.CAMERA)) {
            cameraActionButton?.text = "Esperando permiso de cámara…"
            cameraStatusTitle?.text = "Permiso de cámara"
            cameraStatusDetail?.text = "Android te preguntará si permites usar la cámara."
            try { requestPermissions(arrayOf(Manifest.permission.CAMERA), REQUEST_CAMERA) }
            catch (_: RuntimeException) {
                cameraRequested = false
                cameraActionButton?.text = "Iniciar cámara"
                cameraStatusTitle?.text = "No se pudo solicitar el permiso"
                cameraStatusDetail?.text = "Abre Ajustes de Android > Aplicaciones > AxTarget Care > Permisos > Cámara."
            }
            return
        }
        launchCameraDetection()
    }
    private fun launchCameraDetection() {
        if (tab != CAMERA_TAB || !cameraRequested || !has(Manifest.permission.CAMERA)) return
        if (!cameraSessionLogged) {
            store.event("Cámara de apoyo iniciada")
            cameraSessionLogged = true
        }
        cameraStatusTitle?.text = "Preparando cámara"
        cameraStatusDetail?.text = "Iniciando el procesamiento local."
        updateCameraControls(active = false, preparing = true)
        if (cameraController == null) {
            val preview = cameraPreviewView ?: return
            val overlay = cameraOverlayView ?: return
            cameraController = CameraFallController(
                activity = this,
                previewView = preview,
                overlay = overlay,
                onStatus = { title, detail, running ->
                    val failure = title == "Cámara no disponible" || title == "No se pudo iniciar"
                    if (failure) cameraRequested = false
                    val streaming = running && cameraRequested &&
                        preview.previewStreamState.value == PreviewView.StreamState.STREAMING
                    val preparing = running && cameraRequested && !streaming
                    val detected = title.contains("caída", ignoreCase = true)
                    cameraStatusTitle?.text = when {
                        detected -> "Posible caída"
                        failure -> title
                        streaming -> "Detección activa"
                        preparing -> "Preparando cámara"
                        else -> "Cámara detenida"
                    }
                    updateCameraControls(active = streaming, preparing = preparing)
                    cameraStatusDetail?.text = when {
                        detected -> "Se registró un evento para revisión. La cámara se detuvo."
                        failure -> detail
                        streaming -> "Procesando la imagen en este teléfono."
                        preparing -> "Iniciando el procesamiento local."
                        else -> "La cámara se activa solo cuando la abres."
                    }
                },
                onFall = {
                    if (!cameraFallHandled) {
                        cameraFallHandled = true
                        cameraRequested = false
                        cameraController?.stop()
                        updateCameraControls(active = false)
                        cameraStatusTitle?.text = "Posible caída detectada"
                        cameraStatusDetail?.text = "La detección puede equivocarse. Revisa a la persona y mantén la pulsera puesta."
                        store.event("Detección con cámara IA señaló una posible caída")
                        if (isCameraRole) {
                            CameraAlertNotifier.show(applicationContext)
                            SyncScheduler.enqueue(applicationContext, immediate = true)
                            toast("Aviso de caída registrado y enviado para sincronización.")
                        } else prepareAlert(GuardianMonitorService.ACTION_TEST)
                    }
                },
            )
        }
        cameraController?.start()
    }
    private fun stopCameraDetection() {
        cameraRequested = false
        cameraSessionLogged = false
        cameraController?.stop()
        updateCameraControls(active = false)
        if (cameraStatusTitle?.text?.toString() != "Posible caída detectada") {
            cameraStatusTitle?.text = "Cámara detenida"
            cameraStatusTitle?.contentDescription = "Estado de la cámara: detenida"
            cameraStatusDetail?.text = "La cámara se apagó. La pulsera y los avisos locales siguen funcionando."
        }
    }
    private fun disposeCameraController() {
        if (cameraRequested) store.event("Cámara de apoyo detenida al cambiar de pantalla", queueFallEvent = false)
        cameraRequested = false
        cameraSessionLogged = false
        cameraController?.shutdown()
        cameraController = null
        cameraPreviewView?.previewStreamState?.removeObservers(this)
        cameraPreviewView = null
        cameraRoomBackdrop = null
        cameraOverlayView = null
        cameraPlaceholder = null
        cameraStatusTitle = null
        cameraStatusDetail = null
        cameraActionButton = null
        cameraStateBadge = null
        cameraControlIcon = null
    }
    private fun saveContactForm() {
        try {
            val rows = contactEdits.map { TrustedContact(it.first.text.toString(), it.second.text.toString()) }
            val saved = store.saveContacts(rows)
            contactDraft = saved
            editingContacts = false
            hideKeyboard()
            toast("${TzindaText.count(saved.size, "contacto guardado", "contactos guardados")} correctamente")
            updateServiceIfActive()
            render()
        } catch (e: Exception) { toast(e.message ?: "No se pudieron guardar los contactos.", true) }
    }
    private fun profile(body: LinearLayout) {
        heading(body, "INFORMACIÓN PERSONAL", "Tu información", "Estos datos se guardan en este teléfono. Domicilio y fecha de nacimiento no se sincronizan.")
        val p = profileDraft ?: store.profile()
        body.addView(statusPill(TzindaCopy(
            if (p.length() == 0) "Perfil sin completar" else "Perfil guardado",
            "Puedes editar tus datos y tu contacto de emergencia cuando lo necesites.",
            if (p.length() == 0) TzindaTone.NEUTRAL else TzindaTone.POSITIVE,
        )))

        var group = card(body)
        group.addView(text("Datos personales", 20, true))
        group.addView(text("El nombre se usa para saludarte y para el vínculo con tu cuidador/a.", 14, color = muted))
        listOf("fullName" to "Nombre completo", "phoneNumber" to "Teléfono personal (opcional)", "address" to "Domicilio (opcional)").forEach { (key, label) ->
            profileEdits[key] = field(
                group,
                label,
                p.text(key),
                if (key == "phoneNumber") InputType.TYPE_CLASS_PHONE else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES,
                key,
                helper = if (key == "address") "Se guarda solo en este teléfono." else null,
            )
        }

        group = card(body)
        group.addView(text("Contacto de emergencia", 20, true))
        group.addView(text("Este número se agrega al protocolo de aviso y tiene prioridad sobre los demás contactos.", 14, color = muted))
        listOf("emergencyContactName" to "Nombre del contacto", "emergencyContactPhone" to "Teléfono de emergencia").forEach { (key, label) ->
            profileEdits[key] = field(
                group,
                label,
                p.text(key),
                if (key.contains("Phone")) InputType.TYPE_CLASS_PHONE else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES,
                key,
                helper = if (key.contains("Phone")) "Incluye lada o código de país si corresponde." else null,
            )
        }

        group = card(body)
        group.addView(text("Salud y monitoreo", 20, true))
        group.addView(text("Opcional. Esta información es para tu red de apoyo y no constituye un diagnóstico.", 14, color = muted))
        listOf("fallHistory" to "Antecedentes de caídas (opcional)", "allergies" to "Alergias (opcional)",
            "medicalConditions" to "Condiciones médicas (opcional)", "monitoringDevice" to "Dispositivo de monitoreo (opcional)").forEach { (key, label) ->
            profileEdits[key] = field(group, label, p.text(key), InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES, key)
        }

        group = card(body)
        group.addView(text("Fecha de nacimiento y privacidad", 20, true))
        birthDate = p.text("dateOfBirth").take(10)
        birthLabel = text(birthDescription(), 15, true).also { group.addView(it) }
        group.addView(button("Elegir fecha de nacimiento", false) {
            val calendar = Calendar.getInstance()
            runCatching { SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).parse(birthDate) }.getOrNull()?.let { calendar.time = it }
            DatePickerDialog(this, { _, year, month, day ->
                birthDate = String.format(Locale.ROOT, "%04d-%02d-%02d", year, month + 1, day)
                birthLabel?.text = birthDescription()
            }, calendar.get(Calendar.YEAR), calendar.get(Calendar.MONTH), calendar.get(Calendar.DAY_OF_MONTH)).apply {
                datePicker.maxDate = System.currentTimeMillis(); show()
            }
        })
        consentData = tz.switchRow(group, "Autorizo guardar estos datos en el teléfono", "Necesario para el perfil y los avisos.", p.optBoolean("dataConsent"), action = {})
        consentLocation = tz.switchRow(group, "Autorizo compartir mi ubicación en las alertas", "Solo mientras el monitoreo está activo.", p.optBoolean("locationConsent"), action = {})
        group.addView(button("Guardar perfil") {
            try {
                val profile = collectProfile()
                for (key in listOf("phoneNumber", "emergencyContactPhone")) {
                    val raw = profile.optString(key)
                    if (raw.isNotBlank()) profile.put(key, PhoneNumbers.normalize(raw) ?: throw IllegalArgumentException("Escribe un teléfono válido de 7 a 15 dígitos."))
                }
                if (profile.optString("emergencyContactName").isNotBlank() && profile.optString("emergencyContactPhone").isBlank())
                    throw IllegalArgumentException("Completa el teléfono del contacto de emergencia.")
                store.saveProfile(profile); profileDraft = null; hideKeyboard(); toast("Perfil guardado correctamente")
                updateServiceIfActive(); render()
                if (profile.optBoolean("locationConsent") && !hasLocation()) requestLocation()
            } catch (e: Exception) { toast(e.message ?: "No se pudo guardar el perfil.", true) }
        }.apply { tag = "save_profile" })
        if (store.profile().length() > 0) group.addView(button("Eliminar perfil", false) {
            AlertDialog.Builder(this).setTitle("Eliminar perfil")
                .setMessage("Se eliminarán los datos del perfil y su contacto de emergencia. Los contactos de confianza se conservarán.")
                .setNegativeButton("Cancelar", null).setPositiveButton("Eliminar") { _, _ ->
                    LocationSharing(store).setConsent(false)
                    store.prefs.edit().remove("profile").putBoolean("include_location", false).commit()
                    SyncScheduler.enqueue(store.appContext, immediate = true)
                    profileDraft = null; profileReturnTab = 0; updateServiceIfActive(); selectTab(0)
                }.show()
        })
        group = card(body)
        tz.actionRow(group, "Ajustes de la app", "Rol, sesión, sincronización y permisos", R.drawable.ic_settings, showDivider = false) { openSettings() }
            .apply { tag = "open_app_settings" }
        body.addView(text("Los cambios se conservan si sales de esta pantalla mientras la app sigue abierta. Pulsa Guardar perfil para confirmarlos.", 13, color = muted).apply {
            setPadding(dp(4), dp(12), dp(4), dp(4))
        })
    }
    private fun collectProfile() = JSONObject().apply {
        profileEdits.forEach { (key, field) -> put(key, field.text.toString().trim()) }
        put("dateOfBirth", birthDate); put("dataConsent", consentData?.isChecked == true)
        put("locationConsent", consentLocation?.isChecked == true)
    }
    private fun birthDescription(): String {
        if (birthDate.isBlank()) return "Fecha de nacimiento: sin configurar"
        val parsed = runCatching { SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).apply { isLenient = false }.parse(birthDate) }.getOrNull()
            ?: return "Fecha de nacimiento: $birthDate"
        val birth = Calendar.getInstance().apply { time = parsed }
        val now = Calendar.getInstance()
        var age = now.get(Calendar.YEAR) - birth.get(Calendar.YEAR)
        if (now.get(Calendar.MONTH) < birth.get(Calendar.MONTH) || (now.get(Calendar.MONTH) == birth.get(Calendar.MONTH) && now.get(Calendar.DAY_OF_MONTH) < birth.get(Calendar.DAY_OF_MONTH))) age--
        return "Fecha de nacimiento: $birthDate · ${age.coerceAtLeast(0)} años"
    }
    private fun offerContacts() {
        if (isFinishing || isDestroyed) return
        store.prefs.edit().putBoolean("contacts_offered", true).apply()
        if (store.contacts().size >= 5) return
        AlertDialog.Builder(this).setTitle("Configura tus contactos de confianza")
            .setMessage("Permite leer tu agenda para elegir hasta cinco contactos de confianza. Solo se guardarán los números que selecciones. Puedes escribirlos manualmente desde Contactos.")
            .setNegativeButton("Más tarde", null).setPositiveButton("Elegir contactos") { _, _ -> editingContacts = true; selectTab(2); handler.post { beginImport(-1) } }.show()
    }
    private fun beginImport(slot: Int) {
        if (readingContacts) return
        importSlot = slot
        if (!has(Manifest.permission.READ_CONTACTS)) requestPermissions(arrayOf(Manifest.permission.READ_CONTACTS), REQUEST_CONTACTS)
        else readContacts(slot)
    }
    private fun readContacts(slot: Int) {
        val existingRows = contactEdits.map { TrustedContact(it.first.text.toString(), it.second.text.toString()) }
        val free = existingRows.count { it.name.isBlank() && it.phone.isBlank() }
        val limit = if (slot >= 0) 1 else free
        if (limit <= 0) { toast("Ya tienes cinco filas ocupadas. Usa el botón de una fila para reemplazarla.", true); return }
        val excluded = existingRows.mapIndexedNotNull { i, c -> if (i == slot) null else PhoneNumbers.normalize(c.phone)?.let { PhoneNumbers.key(it) } }.toSet()
        readingContacts = true
        toast("Leyendo contactos…")
        io.execute {
            val result = runCatching { ContactsRepository.read(contentResolver, excluded) }
            runOnUiThread {
                readingContacts = false
                if (isFinishing || isDestroyed || tab != 2 || contactEdits.size != 5) return@runOnUiThread
                result.onSuccess { if (it.isEmpty()) toast("No hay contactos nuevos con teléfono válido. Puedes escribirlos manualmente.", true) else contactPicker(it, limit, slot) }
                    .onFailure { toast("No se pudo leer la agenda. Revisa el permiso o escribe los contactos manualmente.", true) }
            }
        }
    }
    private fun contactPicker(entries: List<TrustedContact>, limit: Int, slot: Int) {
        val group = column().apply { setPadding(dp(16), 0, dp(16), 0) }
        val search = field(
            group, "Buscar contacto", "",
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS,
            hintText = "Nombre o teléfono",
        ).apply { setSingleLine(true) }
        val count = text("0 de $limit seleccionados", 14).apply { setPadding(0, dp(8), 0, dp(4)) }
        val list = ListView(this).apply { choiceMode = ListView.CHOICE_MODE_MULTIPLE }
        val selected = linkedMapOf<String, TrustedContact>()
        var visible = entries
        val adapter = ArrayAdapter<String>(this, android.R.layout.simple_list_item_multiple_choice, mutableListOf())
        list.adapter = adapter
        fun filter(query: String) {
            visible = entries.filter { "${it.name} ${it.phone}".contains(query, ignoreCase = true) }
            adapter.clear(); adapter.addAll(visible.map { "${it.name.ifBlank { "Sin nombre" }}\n${it.phone}" })
            list.clearChoices(); visible.forEachIndexed { i, c -> list.setItemChecked(i, selected.containsKey(PhoneNumbers.key(c.phone))) }
        }
        filter("")
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { filter(s.toString()) }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        list.setOnItemClickListener { _, _, position, _ ->
            val c = visible[position]; val key = PhoneNumbers.key(c.phone)
            if (selected.containsKey(key)) selected.remove(key)
            else if (selected.size < limit) selected[key] = c
            else { list.setItemChecked(position, false); toast("Puedes elegir hasta ${TzindaText.count(limit, "contacto")}.") }
            count.text = "${selected.size} de $limit seleccionados · el orden define la prioridad"
        }
        group.addView(count); group.addView(list, LinearLayout.LayoutParams(-1, dp(320)))
        val dialog = AlertDialog.Builder(this).setTitle("Elegir contactos de confianza").setView(group)
            .setNegativeButton("Cancelar", null).setPositiveButton("Guardar selección", null).create()
        dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            if (selected.isEmpty()) { toast("Selecciona al menos un contacto."); return@setOnClickListener }
            val destinations = if (slot >= 0) listOf(slot) else contactEdits.indices.filter { contactEdits[it].first.text.isBlank() && contactEdits[it].second.text.isBlank() }
            selected.values.zip(destinations).forEach { (c, i) -> contactEdits[i].first.setText(c.name); contactEdits[i].second.setText(c.phone) }
            dialog.dismiss(); saveContactForm()
        } }
        dialog.show()
    }
    private fun prepareAlert(action: String) {
        if (smsRequestInFlight || resumeAlertSetup) return
        if (store.recipients().isEmpty()) {
            toast("Guarda al menos un contacto de confianza o de emergencia.", true)
            startContact(-1)
            return
        }
        pendingAction = action
        if (smsPermissionState() != SmsPermissionPolicy.State.GRANTED) { requestSmsPermission(REQUEST_ALERT_SMS); return }
        requestRemainingAlertPermissions(action)
    }
    private fun requestRemainingAlertPermissions(action: String) {
        val permissions = mutableListOf(Manifest.permission.READ_PHONE_STATE)
        permissions.add(Manifest.permission.CALL_PHONE)
        if (Build.VERSION.SDK_INT >= 33 && action != GuardianMonitorService.ACTION_ALERT_NOW) permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        if (action == GuardianMonitorService.ACTION_START && store.braceletConnectionMode() == AppStore.BRACELET_MODE_BLE) {
            if (Build.VERSION.SDK_INT >= 31) {
                permissions.add(Manifest.permission.BLUETOOTH_SCAN)
                permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
            } else if (!has(Manifest.permission.ACCESS_FINE_LOCATION)) {
                // Android 6–11 requires location permission to scan BLE devices.
                permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
        }
        if (store.prefs.getBoolean("include_location", false) && !hasLocation()) {
            permissions.add(Manifest.permission.ACCESS_COARSE_LOCATION); permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        val missing = permissions.filterNot { has(it) }.distinct().toTypedArray()
        if (missing.isNotEmpty()) {
            try { requestPermissions(missing, REQUEST_ALERT) }
            catch (_: RuntimeException) {
                pendingAction = null
                toast("Android no pudo abrir los permisos de las alertas. Puedes volver a intentarlo.", true)
            }
        }
        else completeAlertSetup(action)
    }
    private fun completeAlertSetup(action: String) {
        pendingAction = null
        if (smsPermissionState() != SmsPermissionPolicy.State.GRANTED) { showSmsHelp(); return }
        if (!has(Manifest.permission.CALL_PHONE)) { permissionHelp("Permite realizar llamadas para activar las alertas automáticas con SMS y llamada."); return }
        if (Build.VERSION.SDK_INT >= 33 && action != GuardianMonitorService.ACTION_ALERT_NOW && !has(Manifest.permission.POST_NOTIFICATIONS)) {
            permissionHelp("Permite las notificaciones para ver la cuenta regresiva y cancelar la alerta."); return
        }
        if (store.prefs.getBoolean("include_location", false) && !hasLocation()) toast("El envío funcionará sin ubicación hasta que concedas ese permiso.", true)
        service(action)
        handler.postDelayed({ refreshLive() }, 400)
    }
    private fun service(action: String) {
        try {
            val intent = Intent(this, GuardianMonitorService::class.java).setAction(action).putExtra("user_visible", true)
            if (action == GuardianMonitorService.ACTION_STOP || action == GuardianMonitorService.ACTION_CANCEL ||
                action == GuardianMonitorService.ACTION_BRACELET_DISCONNECT) startService(intent)
            else if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)
        } catch (e: Exception) { toast("Android no permitió iniciar la acción: ${e.javaClass.simpleName}", true) }
    }
    private fun updateServiceIfActive() { if (GuardianMonitorService.instance != null) service(GuardianMonitorService.ACTION_UPDATE) }
    private fun configureSim() {
        if (checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) { requestPermissions(arrayOf(Manifest.permission.READ_PHONE_STATE), REQUEST_SIM); return }
        try {
            val sims = (getSystemService(TELEPHONY_SUBSCRIPTION_SERVICE) as SubscriptionManager).activeSubscriptionInfoList.orEmpty().sortedBy { it.simSlotIndex }
            if (sims.isEmpty()) { toast("No hay SIM activas. Los SMS requieren una SIM con servicio.", true); return }
            val labels = arrayOf("Automática: predeterminada o primera SIM") + sims.map { "SIM ${it.simSlotIndex + 1} · ${it.displayName}" }
            val selected = sims.indexOfFirst { it.subscriptionId == store.prefs.getInt("sms_subscription", -1) } + 1
            AlertDialog.Builder(this).setTitle("SIM para envío automático").setSingleChoiceItems(labels, selected) { dialog, which ->
                store.prefs.edit().putInt("sms_subscription", if (which == 0) -1 else sims[which - 1].subscriptionId).commit()
                dialog.dismiss(); toast("SIM configurada para SMS automáticos")
            }.setNegativeButton("Cancelar", null).show()
        } catch (_: Exception) { toast("No se pudieron consultar las SIM. Configura una SIM predeterminada para SMS en Android.", true) }
    }
    private fun setLocationConsent(value: Boolean) {
        LocationSharing(store).setConsent(value)
        SyncScheduler.enqueue(store.appContext, immediate = true)
        val profile = store.profile()
        if (profile.length() > 0) {
            profile.put("locationConsent", value)
            store.prefs.edit().putString("profile", profile.toString()).apply()
        }
        if (value) requestLocation() else {
            stopLocation()
            updateServiceIfActive()
            io.execute {
                runCatching {
                    if (backend.isConfigured && backend.currentSession() != null &&
                        !LocationSharing(store).clearPending { backend.deleteCaregiverLocation(it) }) SyncScheduler.enqueue(applicationContext)
                }
            }
        }
    }
    private fun requestLocation() {
        if (!store.prefs.getBoolean("include_location", false)) {
            AlertDialog.Builder(this).setTitle("Compartir ubicación")
                .setMessage("¿Quieres incluir tu ubicación disponible en los SMS de ayuda y compartirla con tu cuidador mediante Supabase durante el monitoreo?")
                .setNegativeButton("Cancelar", null).setPositiveButton("Permitir") { _, _ ->
                    LocationSharing(store).setConsent(true); requestLocation()
                }.show(); return
        }
        if (!hasLocation()) { requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), REQUEST_LOCATION); return }
        fetchLocation(); updateServiceIfActive()
    }
    @Suppress("DEPRECATION")
    private fun fetchLocation() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
        try {
            val manager = getSystemService(LOCATION_SERVICE) as LocationManager
            val providers = manager.getProviders(true).filter { it == LocationManager.NETWORK_PROVIDER || it == LocationManager.GPS_PROVIDER }
            if (providers.isEmpty()) { toast("Activa la ubicación del teléfono.", true); return }
            manager.getProviders(true).mapNotNull { runCatching { manager.getLastKnownLocation(it) }.getOrNull() }.maxByOrNull { it.time }?.let { saveLocation(it) }
            stopLocation()
            locationListener = object : LocationListener {
                override fun onLocationChanged(location: Location) { saveLocation(location); stopLocation(); toast("Ubicación actualizada"); if (tab == 0) render() }
                override fun onProviderEnabled(provider: String) = Unit
                override fun onProviderDisabled(provider: String) = Unit
                @Deprecated("Platform callback") override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
            }
            manager.requestSingleUpdate(providers.first(), locationListener!!, Looper.getMainLooper())
            toast("Buscando ubicación…")
            handler.postDelayed(locationTimeout, 15_000)
        } catch (_: Exception) { toast("Ubicación no disponible. Los SMS siguen disponibles.", true) }
    }
    private fun saveLocation(location: Location) {
        store.prefs.edit().putString("latitude", location.latitude.toString()).putString("longitude", location.longitude.toString()).putLong("location_time", location.time).apply()
        if (store.prefs.getBoolean("safe_zones_enabled", false)) {
            val generated = careFeatures.onLocation(location.latitude, location.longitude, location.accuracy, location.time)
            if (generated.isNotEmpty()) { SyncScheduler.enqueue(applicationContext); toast("Se registró un cambio de zona.") }
        }
    }
    private fun stopLocation() { handler.removeCallbacks(locationTimeout); locationListener?.let { runCatching { (getSystemService(LOCATION_SERVICE) as LocationManager).removeUpdates(it) } }; locationListener = null }
    private fun startMedicationScan(
        document: MedicationDocument,
        mode: MedicationScanMode,
        onResult: (MedicationScanResult?, String?) -> Unit,
    ): MedicationScan? {
        if (medicationScan != null) {
            toast("Espera a que termine la lectura de la imagen.")
            return null
        }
        return MedicationScan(document, mode, onResult).also { medicationScan = it }
    }

    private fun launchMedicationCamera(scan: MedicationScan) {
        val directory = File(cacheDir, "medication_scan").apply { mkdirs() }
        directory.listFiles()?.forEach { stale ->
            if (System.currentTimeMillis() - stale.lastModified() > 10 * 60_000L) stale.delete()
        }
        val uri = runCatching {
            val file = File.createTempFile("scan-", ".jpg", directory)
            scan.photo = file
            FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        }.getOrElse {
            finishMedicationScan(scan, null, "No se pudo preparar la cámara. Elige una imagen de la galería.")
            return
        }
        runCatching { medicationCameraLauncher.launch(uri) }.onFailure {
            finishMedicationScan(scan, null, "No se pudo abrir la cámara. Elige una imagen de la galería.")
        }
    }

    private fun finishMedicationScan(scan: MedicationScan, result: MedicationScanResult?, error: String?) {
        scan.photo?.delete()
        if (medicationScan !== scan) return
        medicationScan = null
        if (!isFinishing && !isDestroyed) scan.onResult(result, error)
    }

    private fun readMedicationLabel(scan: MedicationScan, uri: Uri) {
        fun readOnDevice(previousError: String? = null) {
            toast("Leyendo la imagen…")
            MedicationLabelReader.read(this, uri, scan.document) { result, error ->
                finishMedicationScan(scan, result, error ?: previousError.takeIf { result == null })
            }
        }
        if (scan.mode == MedicationScanMode.DEVICE) {
            readOnDevice()
            return
        }
        toast("Leyendo la imagen en la nube…")
        NvidiaMedicationLabelReader.read(this, uri, scan.document) { result, error ->
            if (result != null && result.entries.isNotEmpty()) {
                finishMedicationScan(scan, result, null)
            } else {
                toast(error ?: "La nube no identificó medicamentos. Se leerá la imagen en el teléfono.", true)
                readOnDevice(error)
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_STARTUP_PERMISSIONS) {
            startupPermissionInFlight = false
            recordSmsPermissionResult(permissions, grantResults)
            if (!isFinishing && !isDestroyed) render()
            return
        }
        if (requestCode == REQUEST_SMS_ONLY || requestCode == REQUEST_ALERT_SMS) {
            smsRequestInFlight = false
            recordSmsPermissionResult(permissions, grantResults)
            refreshPermissionUi()
            if (!has(Manifest.permission.SEND_SMS)) {
                pendingAction = null
                resumeAlertSetup = false
                toast(if (grantResults.isEmpty()) "Solicitud cancelada. Puedes volver a pulsar Permitir SMS." else "SMS no autorizado. Puedes seguir usando la app y habilitarlo más adelante.", true)
                return
            }
        }
        if (isFinishing || isDestroyed) return
        when (requestCode) {
            REQUEST_MAP_LOCATION -> {
                val callback = mapLocationPermission
                mapLocationPermission = null
                if (callback != null) {
                    if (hasLocation()) requestMapFix(callback) else callback(null)
                }
            }
            REQUEST_MEDICATION_SCAN -> {
                medicationScan?.let { scan ->
                    if (has(Manifest.permission.CAMERA)) launchMedicationCamera(scan)
                    else finishMedicationScan(scan, null, "Sin permiso de cámara. Puedes elegir una imagen de la galería o escribir los datos a mano.")
                }
            }
            REQUEST_BRACELET_BLE -> {
                val action = pendingBraceletAction
                pendingBraceletAction = null
                if (action != null && requiredBraceletBlePermissions().all(::has)) requestBraceletService(action)
                else if (action != null) toast("Permite dispositivos cercanos para conectar la pulsera por Bluetooth.", true)
            }
            REQUEST_CAMERA -> {
                if (has(Manifest.permission.CAMERA)) {
                    cameraRequested = true
                    launchCameraDetection()
                } else {
                    cameraRequested = false
                    cameraPlaceholder?.visibility = View.VISIBLE
                    cameraActionButton?.text = "Iniciar detección con IA"
                    cameraStatusTitle?.text = "Permiso no concedido"
                    cameraStatusDetail?.text = "Puedes seguir usando los sensores del teléfono. Autoriza Cámara en Ajustes si quieres probar el modelo."
                    if (grantResults.isNotEmpty()) toast("Sin permiso de cámara. La detección del teléfono sigue disponible.", true)
                }
            }
            REQUEST_CAMERA_NOTIFICATIONS -> {
                if (Build.VERSION.SDK_INT < 33 || has(Manifest.permission.POST_NOTIFICATIONS)) toast("Avisos de cámara habilitados.")
                else toast("Sin permiso de notificaciones. Puedes habilitarlo después en Ajustes.", true)
                if (isCameraRole && tab == 0) render()
            }
            REQUEST_CONTACTS -> if (has(Manifest.permission.READ_CONTACTS)) readContacts(importSlot) else permissionHelp("Sin permiso de agenda. Puedes escribir los números manualmente.")
            REQUEST_SMS_ONLY -> {
                refreshPermissionUi()
                if (has(Manifest.permission.SEND_SMS)) {
                    toast("Permiso de SMS habilitado")
                }
            }
            REQUEST_ALERT_SMS -> {
                refreshPermissionUi()
                val action = pendingAction
                if (has(Manifest.permission.SEND_SMS)) {
                    if (action != null) {
                        resumeAlertSetup = true
                        // Wait until PermissionController returns focus before opening another request.
                        if (hasWindowFocus()) onWindowFocusChanged(true)
                    } else toast("Permiso de SMS habilitado")
                }
            }
            REQUEST_ALERT -> pendingAction?.let { completeAlertSetup(it) }
            REQUEST_LOCATION -> if (hasLocation()) { fetchLocation(); updateServiceIfActive() } else toast("Sin ubicación. Los SMS se enviarán sin coordenadas.", true)
            REQUEST_SIM -> if (has(Manifest.permission.READ_PHONE_STATE)) configureSim() else permissionHelp("Configura una SIM predeterminada para SMS en Android.")
            REQUEST_CALLS -> if (!has(Manifest.permission.CALL_PHONE)) toast("Las llamadas no están autorizadas. El envío por SMS sigue disponible.", true)
        }
    }
    private fun recordSmsPermissionResult(permissions: Array<String>, grantResults: IntArray) {
        // An interrupted Android request can return empty arrays without a decision.
        if (grantResults.isEmpty() || Manifest.permission.SEND_SMS !in permissions) return
        smsRequestedInSession = !has(Manifest.permission.SEND_SMS)
        runCatching {
            File(noBackupFilesDir, "sms-permission-requested").writeText(if (smsRequestedInSession) "requested" else "granted")
        }
    }

    private fun smsPermissionState(): SmsPermissionPolicy.State {
        val feature = if (Build.VERSION.SDK_INT >= 33) PackageManager.FEATURE_TELEPHONY_MESSAGING else PackageManager.FEATURE_TELEPHONY
        return SmsPermissionPolicy.evaluate(
            packageManager.hasSystemFeature(feature), has(Manifest.permission.SEND_SMS),
            runCatching { packageManager.isPermissionRevokedByPolicy(Manifest.permission.SEND_SMS, packageName) }.getOrDefault(false),
            smsRequestedInSession || runCatching { File(noBackupFilesDir, "sms-permission-requested").readText() == "requested" }.getOrDefault(false),
            shouldShowRequestPermissionRationale(Manifest.permission.SEND_SMS))
    }
    private fun permissionCard(parent: LinearLayout) {
        val panel = card(parent)
        panel.addView(text("Permiso de SMS", 18, true))
        smsPermissionStatus = text("", 14, color = muted).apply { tag = "sms_permission_status" }.also { panel.addView(it) }
        smsPermissionPill = tz.statusPill("SMS habilitado", TzindaDesign.Tone.SUCCESS).apply {
            visibility = View.GONE
        }.also { panel.addView(it) }
        smsPermissionButton = button("Permitir SMS") { requestSmsPermission(REQUEST_SMS_ONLY) }
            .apply { tag = "enable_sms" }.also { panel.addView(it) }
    }
    private fun refreshPermissionUi() {
        if (!::store.isInitialized) return
        if (has(Manifest.permission.SEND_SMS)) {
            smsRequestedInSession = false
            val marker = File(noBackupFilesDir, "sms-permission-requested")
            runCatching { if (!marker.isFile || marker.readText() != "granted") marker.writeText("granted") }
        }
        val state = smsPermissionState()
        smsPermissionStatus?.text = when (state) {
            SmsPermissionPolicy.State.GRANTED -> "SMS habilitado en este dispositivo. No necesitas volver a concederlo mientras Android lo conserve."
            SmsPermissionPolicy.State.UNSUPPORTED -> "Este dispositivo no dispone de telefonía compatible con SMS."
            SmsPermissionPolicy.State.POLICY_BLOCKED -> "SMS bloqueado por una política de administración del dispositivo."
            SmsPermissionPolicy.State.SETTINGS_REQUIRED -> "SMS pendiente: Android no vuelve a mostrar la solicitud. Revisa cómo habilitarlo."
            SmsPermissionPolicy.State.REQUESTABLE -> "Permite enviar alertas por tu SIM. La app mostrará la solicitud de Android; basta con elegir Permitir."
        }
        smsPermissionPill?.visibility = if (state == SmsPermissionPolicy.State.GRANTED) View.VISIBLE else View.GONE
        smsPermissionButton?.apply {
            isEnabled = !smsRequestInFlight && state != SmsPermissionPolicy.State.GRANTED && state != SmsPermissionPolicy.State.UNSUPPORTED
            alpha = if (isEnabled) 1f else .65f
            visibility = if (state == SmsPermissionPolicy.State.GRANTED) View.GONE else View.VISIBLE
            text = if (smsRequestInFlight) "Esperando respuesta de Android…" else when (state) {
                SmsPermissionPolicy.State.GRANTED -> "SMS habilitado"
                SmsPermissionPolicy.State.UNSUPPORTED -> "SMS no disponible"
                SmsPermissionPolicy.State.POLICY_BLOCKED, SmsPermissionPolicy.State.SETTINGS_REQUIRED -> "Cómo habilitar SMS"
                SmsPermissionPolicy.State.REQUESTABLE -> "Permitir SMS"
            }
        }
    }
    private fun requestSmsPermission(requestCode: Int) {
        if (smsRequestInFlight || isFinishing || isDestroyed) return
        when (smsPermissionState()) {
            SmsPermissionPolicy.State.GRANTED -> {
                refreshPermissionUi()
                if (requestCode == REQUEST_ALERT_SMS) pendingAction?.let { requestRemainingAlertPermissions(it) }
            }
            SmsPermissionPolicy.State.REQUESTABLE -> {
                smsRequestInFlight = true
                refreshPermissionUi()
                try {
                    requestPermissions(arrayOf(Manifest.permission.SEND_SMS), requestCode)
                } catch (_: RuntimeException) {
                    smsRequestInFlight = false
                    pendingAction = null
                    refreshPermissionUi()
                    toast("Android no pudo abrir la solicitud de SMS. Intenta de nuevo o revisa los permisos en Ajustes.", true)
                }
            }
            else -> { if (requestCode == REQUEST_ALERT_SMS) pendingAction = null; showSmsHelp() }
        }
    }
    private fun showSmsHelp() {
        if (isFinishing || isDestroyed || smsHelpDialog?.isShowing == true) return
        val message = when (smsPermissionState()) {
            SmsPermissionPolicy.State.UNSUPPORTED -> "El dispositivo no admite SMS por SIM. Puedes revisar la interfaz, pero las alertas por SMS necesitan un teléfono compatible con servicio móvil."
            SmsPermissionPolicy.State.POLICY_BLOCKED -> "La administración del dispositivo bloquea SMS. La app no puede cambiar esa política; debes consultarlo con quien administra el teléfono."
            SmsPermissionPolicy.State.SETTINGS_REQUIRED -> "Android no permite mostrar otra solicitud. En los ajustes de AxTarget Care abre Permisos > SMS y selecciona Permitir. Si SMS no aparece o está bloqueado, el instalador del dispositivo puede estar restringiéndolo. Para tus dispositivos de prueba puedes usar la instalación por USB incluida con esta versión."
            else -> "Para activar el envío automático necesitas autorizar SMS. Pulsa Permitir SMS en Inicio y elige Permitir en la solicitud de Android."
        }
        smsHelpDialog = AlertDialog.Builder(this).setTitle("Habilitar SMS").setMessage(message)
            .setNegativeButton("Cerrar", null).setPositiveButton("Abrir ajustes") { _, _ -> openAppSettings() }
            .setNeutralButton("Pruebas por USB") { _, _ ->
                AlertDialog.Builder(this).setTitle("Instalación en dispositivos de prueba")
                    .setMessage("En tu computadora, extrae el paquete Pruebas USB de AxTarget Care. Conecta el teléfono, habilita Depuración USB y autoriza la conexión. Ejecuta Instalar-TzindaApp-Pruebas.ps1: instala el APK y autoriza únicamente SMS mediante las herramientas de Android. No se envían mensajes durante la instalación. Esto no elimina políticas de administración ni agrega telefonía a un dispositivo que no la tiene.")
                    .setPositiveButton("Entendido", null).show()
            }.show()
    }
    private fun permissionHelp(message: String) {
        if (isFinishing || isDestroyed) return
        AlertDialog.Builder(this).setTitle("Permisos").setMessage(message).setNegativeButton("Cerrar", null)
            .setPositiveButton("Abrir ajustes") { _, _ -> openAppSettings() }.show()
    }
    private fun openAppSettings() {
        try { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) }
        catch (_: RuntimeException) { toast("Abre Ajustes de Android > Aplicaciones > AxTarget Care > Permisos.", true) }
    }
    private fun has(permission: String) = checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    private fun hasLocation() = has(Manifest.permission.ACCESS_FINE_LOCATION) || has(Manifest.permission.ACCESS_COARSE_LOCATION)
    private fun hasValidatedInternet(): Boolean = runCatching {
        val manager = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = manager.activeNetwork ?: return@runCatching false
        val capabilities = manager.getNetworkCapabilities(network) ?: return@runCatching false
        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }.getOrDefault(false)
    private fun arduinoConnectionAvailable(): Boolean {
        val status = store.prefs.getString("arduino_status", "").orEmpty()
        return status.contains("conexión activa", ignoreCase = true) ||
            status.contains("conectada por Bluetooth", ignoreCase = true)
    }
    private fun hideKeyboard() { currentFocus?.let { (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(it.windowToken, 0); it.clearFocus() } }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun rounded(color: Int, radius: Int = 18) = tz.rounded(color, radius)
    private fun text(value: String, size: Int = 16, bold: Boolean = false, color: Int = ink) =
        tz.text(value, size, bold, color)
    private fun card(parent: LinearLayout) = tz.plainCard(parent)
    private fun gap(parent: LinearLayout, height: Int) { tz.addGap(parent, height) }
    private fun heading(body: LinearLayout, eyebrow: String, title: String, subtitle: String) {
        tz.heading(body, eyebrow, title, subtitle)
    }
    private fun button(label: String, primary: Boolean = true, action: () -> Unit) =
        tz.button(label, primary, action)
    private fun field(parent: LinearLayout, label: String, value: String, input: Int = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS, tag: String? = null, helper: String? = null, hintText: String? = null): EditText =
        tz.field(parent, label, value, input, tag, helper, hintText)
    private fun switch(label: String, value: Boolean, action: (Boolean) -> Unit) =
        tz.switchControl(label, value, action)
    private fun renderLoadingCard(parent: LinearLayout, message: String = "Cargando información…") {
        tz.loadingState(parent, message)
    }
    private fun renderEmptyStateCard(parent: LinearLayout, title: String, subtitle: String, actionLabel: String? = null, action: (() -> Unit)? = null) {
        tz.emptyState(parent, title, subtitle, actionLabel, action)
    }
    private fun renderErrorCard(parent: LinearLayout, title: String, errorMsg: String, retryAction: (() -> Unit)? = null) {
        tz.errorState(parent, title, errorMsg, retryAction)
    }
    private fun designTone(tone: TzindaTone): TzindaDesign.Tone = when (tone) {
        TzindaTone.POSITIVE -> TzindaDesign.Tone.SUCCESS
        TzindaTone.INFO -> TzindaDesign.Tone.INFO
        TzindaTone.WARNING -> TzindaDesign.Tone.WARNING
        TzindaTone.CRITICAL -> TzindaDesign.Tone.DANGER
        TzindaTone.NEUTRAL -> TzindaDesign.Tone.NEUTRAL
    }
    private fun statusPill(copy: TzindaCopy): TextView =
        tz.statusPill(copy.title, designTone(copy.tone))
    private fun statusBanner(
        parent: LinearLayout,
        copy: TzindaCopy,
        actionLabel: String? = null,
        action: (() -> Unit)? = null,
    ): LinearLayout = tz.banner(parent, copy.title, copy.detail, designTone(copy.tone), actionLabel, action)
    private fun sharedEvents(): List<JSONObject> =
        runCatching { JSONArray(store.prefs.getString("caregiver_shared_events", "[]")) }
            .getOrDefault(JSONArray())
            .let { rows -> (0 until rows.length()).mapNotNull { rows.optJSONObject(it) } }
    private fun sharedDevices(): List<JSONObject> =
        runCatching { JSONArray(store.prefs.getString("caregiver_shared_devices", "[]")) }
            .getOrDefault(JSONArray())
            .let { rows -> (0 until rows.length()).mapNotNull { rows.optJSONObject(it) } }
    private fun isoMillis(raw: String): Long = TzindaStatusCopy.timestampMillis(raw)
    private fun deviceLastSignal(device: JSONObject): Long {
        val raw = device.optString("last_sync_at").takeIf { it.isNotBlank() && it != "null" }
            ?: device.optString("last_connected_at").takeIf { it.isNotBlank() && it != "null" } ?: return 0L
        return isoMillis(raw)
    }
    /** A shared registry entry is not the same as a live connection; the copy never claims one. */
    private fun sharedDeviceCopy(device: JSONObject): TzindaCopy {
        val last = deviceLastSignal(device)
        if (last <= 0L) return TzindaStatusCopy.device(DeviceConnectionState.REGISTERED, null)
        val age = (System.currentTimeMillis() - last).coerceAtLeast(0L)
        return if (age > 24 * 60 * 60 * 1000L) TzindaStatusCopy.device(DeviceConnectionState.NO_RECENT_DATA, last) else TzindaCopy(
            "Registrado · datos recientes",
            "Última señal ${TzindaStatusCopy.freshness(age)}. No confirma una conexión en vivo.",
            TzindaTone.INFO,
        )
    }
    private fun pendingSharedAlerts(events: List<JSONObject>): List<JSONObject> {
        val followups = AlertFollowupPolicy.latestByEvent(sharedAlertFollowups())
        return events.filter { AlertFollowupPolicy.isPendingSharedAlert(it, followups) }
    }
    /**
     * Pref events created before the telemetry table existed do not carry a classification or
     * confirmation status. Only actual fall candidates need attention; a cancelled incident is
     * resolved even though its original fall event remains in the local history.
     */
    private fun isPendingLocalAlert(event: JSONObject, all: List<JSONObject>): Boolean {
        if (AlertFollowupPolicy.isPendingAlert(event)) return true
        val message = event.optString("message")
        val isFall = message.startsWith("Posible caída") ||
            message.startsWith("Detección con cámara IA señaló")
        if (!isFall) return false
        val incident = event.optString("incident")
        if (incident.isBlank()) return true
        return all.none { other ->
            other.optString("incident") == incident &&
                other.optString("message").startsWith("Alerta cancelada")
        }
    }
    private fun formatSharedMoment(raw: String): String {
        val millis = isoMillis(raw)
        return if (millis > 0L) SimpleDateFormat("d MMM · HH:mm", Locale("es", "MX")).format(Date(millis)) else raw.ifBlank { "Fecha no disponible" }
    }

    /** Day bucket label used to group the alert feed like the design (HOY / AYER / date). */
    private fun dayLabel(millis: Long): String {
        if (millis <= 0L) return "Sin fecha"
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = millis }
        val today = java.util.Calendar.getInstance()
        val yesterday = java.util.Calendar.getInstance().apply { add(java.util.Calendar.DAY_OF_YEAR, -1) }
        fun sameDay(a: java.util.Calendar) =
            a.get(java.util.Calendar.YEAR) == cal.get(java.util.Calendar.YEAR) &&
                a.get(java.util.Calendar.DAY_OF_YEAR) == cal.get(java.util.Calendar.DAY_OF_YEAR)
        return when {
            sameDay(today) -> "Hoy"
            sameDay(yesterday) -> "Ayer"
            else -> SimpleDateFormat("EEEE d 'de' MMMM", Locale("es", "MX")).format(Date(millis))
                .replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale("es", "MX")) else it.toString() }
        }
    }
    /** Initials for the circular avatar used by the persona card. */
    private fun initials(name: String): String =
        name.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
            .take(2).mapNotNull { it.firstOrNull()?.uppercaseChar() }.joinToString("").ifBlank { "P" }

    /** Millis of a "HH:mm" schedule slot today, or null when it already passed. */
    private fun parseTimeToday(time: String, now: Long): Long? {
        val parts = time.trim().split(":")
        if (parts.size < 2) return null
        val hour = parts[0].toIntOrNull() ?: return null
        val minute = parts[1].toIntOrNull() ?: return null
        val cal = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, hour)
            set(java.util.Calendar.MINUTE, minute)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }
        return cal.timeInMillis.takeIf { it >= now }
    }

    private fun toast(message: String, long: Boolean = false) { Toast.makeText(this, message, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show() }

    private inner class CountdownRing(context: Context) : View(context) {
        var progress: Float = 1f
            set(value) { field = value.coerceIn(0f, 1f); invalidate() }

        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val bounds = RectF()

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val x = width / 2f
            val y = height / 2f
            val radius = minOf(dp(80).toFloat(), y - dp(8), x - dp(8))
            if (radius <= 0f) return
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = dp(1).toFloat()
            paint.color = 0x24FFFFFF
            canvas.drawCircle(x, y, radius + dp(38), paint)
            canvas.drawCircle(x, y, radius + dp(63), paint)
            bounds.set(x - radius, y - radius, x + radius, y + radius)
            paint.strokeWidth = dp(7).toFloat()
            paint.color = 0x42FFFFFF
            canvas.drawOval(bounds, paint)
            paint.strokeCap = Paint.Cap.ROUND
            paint.color = Color.WHITE
            val angle = 360f * progress
            canvas.drawArc(bounds, -90f, angle, false, paint)
            val radians = Math.toRadians((angle - 90).toDouble())
            val dotX = x + radius * kotlin.math.cos(radians).toFloat()
            val dotY = y + radius * kotlin.math.sin(radians).toFloat()
            paint.style = Paint.Style.FILL
            canvas.drawCircle(dotX, dotY, dp(7).toFloat(), paint)
            paint.color = tz.imperialRed
            canvas.drawCircle(dotX, dotY, dp(4).toFloat(), paint)
        }
    }
    companion object {
        // A code exchange must survive Activity recreation; its result is stored independently.
        private val authCallbackIo = Executors.newSingleThreadExecutor()
        private val googleExchangeRunning = AtomicBoolean()
        private const val GOOGLE_AUTH_TIMEOUT_MS = 45_000L
        private const val RETIRED_ADVANCED_SETTINGS = 25
        private const val CAMERA_TAB = TzindaTabs.CAMERA
        private const val CAREGIVER_EVENT_PAGE_SIZE = 100
        private const val CAREGIVER_EVENT_CACHE_LIMIT = 1000
        private const val ROLE_TAB = TzindaTabs.ROLE
        private const val DEVICE_TAB = TzindaTabs.DEVICE
        private const val MEDICATIONS_TAB = TzindaTabs.MEDICATIONS
        private const val CARE_TAB = TzindaTabs.CARE
        private const val SUPERVISION_TAB = TzindaTabs.SUPERVISION
        /**
         * Guard for the dark-mode switch: a tap can be echoed around
         * [Activity.recreate] and the echo may reach a rebuilt instance, so this
         * must outlive individual activity instances.
         */
        private const val REQUEST_CAMERA = 108
        private const val REQUEST_CONTACTS = 101
        private const val REQUEST_ALERT = 102
        private const val REQUEST_LOCATION = 103
        private const val REQUEST_SIM = 104
        private const val REQUEST_CALLS = 105
        private const val REQUEST_SMS_ONLY = 106
        private const val REQUEST_ALERT_SMS = 107
        private const val REQUEST_BRACELET_BLE = 111
        private const val REQUEST_MEDICATION_SCAN = 113
        private const val REQUEST_MAP_LOCATION = 114
        private const val REQUEST_STARTUP_PERMISSIONS = 115
        private const val REQUEST_CAMERA_NOTIFICATIONS = 112
        private const val STARTUP_PERMISSIONS_FINISHED = "finished"
    }
}

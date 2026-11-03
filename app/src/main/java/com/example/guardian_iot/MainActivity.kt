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
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.example.deteccioncaidas.CameraFallController
import com.example.deteccioncaidas.PoseOverlayView
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {
    private lateinit var store: AppStore
    private lateinit var sessions: EncryptedSessionStore
    private lateinit var backend: SupabaseBackend
    private lateinit var medicationRepository: MedicationRepository
    private lateinit var careFeatures: CareFeatureStore
    private lateinit var frame: FrameLayout
    private lateinit var root: View
    private var tab = 0
    private var authMode: AuthMode? = null
    private var recoveryRevision = -1L
    private var authStatus: TextView? = null
    private var authEmail: EditText? = null
    private var authPassword: EditText? = null
    private var authName: EditText? = null
    private var authConfirmation: EditText? = null
    private var authPrivacy: Switch? = null
    private var caregiverRefreshInFlight = false
    private var caregiverRefreshAt = 0L
    private var caregiverRefreshScope: CaregiverScope? = null
    private var caregiverRefreshRequest = 0L
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
    private var activitySection = 0
    private var careJournalFilter = ""
    private var patientPulseValue: TextView? = null
    private var patientSpo2Value: TextView? = null
    private var patientPulseUnit: TextView? = null
    private var patientSpo2Unit: TextView? = null
    private var patientPulseSpark: SparklineView? = null
    private var patientSpo2Spark: SparklineView? = null
    private var patientVitalsStatus: TextView? = null
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
    private var appBarSubtitle: TextView? = null
    private var appBarStatus: TextView? = null
    private var appBarProfile: TextView? = null
    private var homeStatus: TextView? = null
    private var homeHeadline: TextView? = null
    private var monitorButton: Button? = null
    private var monitorToggle: Switch? = null
    private var updatingMonitorToggle = false
    private var emergencyProgressCard: LinearLayout? = null
    private var emergencyProgressStatus: TextView? = null
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
    private var countdownDescription: TextView? = null
    private var countdownRing: CountdownRing? = null
    private var cameraController: CameraFallController? = null
    private var cameraPreviewView: PreviewView? = null
    private var cameraOverlayView: PoseOverlayView? = null
    private var cameraPlaceholder: TextView? = null
    private var cameraStatusTitle: TextView? = null
    private var cameraStatusDetail: TextView? = null
    private var cameraActionButton: Button? = null
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
    private var medicationScanCallback: ((MedicationLabelGuess?, String?) -> Unit)? = null
    private var medicationScanFile: File? = null
    private var medicationScanCloud = false
    private val medicationCameraLauncher = registerForActivityResult(
        // La app de cámara escribe en el archivo temporal: sin estos permisos el envío falla en
        // varios fabricantes aunque el FileProvider ya esté configurado.
        object : ActivityResultContracts.TakePicture() {
            override fun createIntent(context: Context, input: Uri): Intent =
                super.createIntent(context, input)
                    .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        },
    ) { saved ->
        val callback = medicationScanCallback
        medicationScanCallback = null
        val file = medicationScanFile
        when {
            !saved || file == null -> {
                file?.delete()
                medicationScanFile = null
                callback?.invoke(null, "No se tomó la foto. Puedes intentarlo de nuevo o escribir los datos a mano.")
            }
            else -> readMedicationLabel(file, callback)
        }
    }
    private val medicationGalleryLauncher = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        val callback = medicationScanCallback
        medicationScanCallback = null
        if (uri == null) callback?.invoke(null, null) else readMedicationLabel(uri, callback)
    }
    private val medicationLabelScanner = object : MedicationLabelScanner {
        override val cloudAvailable: Boolean get() = NvidiaMedicationLabelReader.isConfigured

        override fun takePhoto(mode: MedicationScanMode, onResult: (MedicationLabelGuess?, String?) -> Unit) {
            medicationScanCloud = mode == MedicationScanMode.CLOUD
            if (!has(Manifest.permission.CAMERA)) {
                medicationScanCallback = onResult
                requestPermissions(arrayOf(Manifest.permission.CAMERA), REQUEST_MEDICATION_SCAN)
                return
            }
            launchMedicationCamera(onResult)
        }

        override fun pickFromGallery(mode: MedicationScanMode, onResult: (MedicationLabelGuess?, String?) -> Unit) {
            medicationScanCloud = mode == MedicationScanMode.CLOUD
            medicationScanCallback = onResult
            runCatching {
                medicationGalleryLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }.onFailure {
                medicationScanCallback = null
                onResult(null, "No se pudo abrir la galería en este teléfono.")
            }
        }
    }
    private enum class AuthMode { LOGIN, REGISTER, RECOVER, RESET }
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
        medicationRepository = MedicationRepository(this, backend, store)
        careFeatures = CareFeatureStore(this)
        authMode = restoredState?.getString("auth_mode")?.let { runCatching { AuthMode.valueOf(it) }.getOrNull() }
            ?: if (backend.isConfigured && sessions.read() == null && !store.prefs.getBoolean("local_only_mode", false)) AuthMode.LOGIN else null
        if (backend.hasPasswordRecovery) authMode = AuthMode.RESET
        else if (authMode == AuthMode.RESET) authMode = AuthMode.RECOVER
        if (dark) setTheme(R.style.AppTheme_Dark)
        super.onCreate(restoredState)
        tab = restoredState?.getInt("tab") ?: if (store.role().isBlank()) ROLE_TAB else 0
        pendingAction = restoredState?.getString("action")
        profileReturnTab = restoredState?.getInt("profile_return_tab", 0) ?: 0
        cameraReturnTab = restoredState?.getInt("camera_return_tab", 0) ?: 0
        deviceReturnTab = restoredState?.getInt("device_return_tab", 0) ?: 0
        medicationReturnTab = restoredState?.getInt("medication_return_tab", 0) ?: 0
        careReturnTab = restoredState?.getInt("care_return_tab", 0) ?: 0
        supervisionReturnTab = restoredState?.getInt("supervision_return_tab", 0) ?: 0
        activityFilter = restoredState?.getInt("activity_filter", 0) ?: 0
        activitySection = restoredState?.getInt("activity_section", 0) ?: 0
        editingContacts = restoredState?.getBoolean("editing_contacts", false) ?: false
        smsRequestInFlight = restoredState?.getBoolean("sms_request_pending") ?: false
        resumeAlertSetup = restoredState?.getBoolean("resume_alert_setup") ?: false
        importSlot = restoredState?.getInt("import_slot", -2) ?: -2
        restoredState?.getString("contact_draft")?.let { json ->
            runCatching { val a = JSONArray(json); contactDraft = (0 until a.length()).map { TrustedContact(a.getJSONObject(it).optString("name"), a.getJSONObject(it).optString("phone")) } }
        }
        restoredState?.getString("profile_draft")?.let { runCatching { profileDraft = JSONObject(it) } }
        setContentView(R.layout.activity_main)
        root = findViewById(R.id.root)
        frame = findViewById(R.id.content)
        root.setBackgroundColor(paper)
        appBarSubtitle = findViewById(R.id.app_bar_subtitle)
        appBarStatus = findViewById(R.id.app_bar_status)
        appBarProfile = findViewById(R.id.app_bar_profile)
        findViewById<View>(R.id.app_bar).setBackgroundColor(paper)
        val back = findViewById<ImageView>(R.id.app_bar_back)
        back.setColorFilter(accent)
        back.setOnClickListener {
            if (isSecondaryScreen(tab)) {
                selectTab(returnTabFor(tab))
            } else if (tab == CAMERA_TAB && !isCameraRole) {
                selectTab(primaryTabOr(cameraReturnTab))
            } else onBackPressedDispatcher.onBackPressed()
        }
        appBarProfile?.apply {
            background = rounded(tz.primaryFill, 24)
            setTextColor(onAccent)
            setOnClickListener { openProfile() }
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (isSecondaryScreen(tab)) {
                    selectTab(returnTabFor(tab))
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
                view.setPadding(padding.left, padding.top, padding.right, padding.bottom)
            } else view.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.stableInsetBottom)
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
        out.putInt("tab", tab); out.putInt("profile_return_tab", profileReturnTab); out.putInt("camera_return_tab", cameraReturnTab); out.putInt("device_return_tab", deviceReturnTab); out.putInt("medication_return_tab", medicationReturnTab); out.putInt("care_return_tab", careReturnTab); out.putInt("supervision_return_tab", supervisionReturnTab)
        out.putInt("activity_filter", activityFilter); out.putInt("activity_section", activitySection); out.putBoolean("editing_contacts", editingContacts)
        out.putString("action", pendingAction); out.putInt("import_slot", importSlot)
        out.putBoolean("sms_request_pending", smsRequestInFlight)
        out.putBoolean("resume_alert_setup", resumeAlertSetup)
        authMode?.let { out.putString("auth_mode", it.name) }
        contactDraft?.let { rows -> out.putString("contact_draft", JSONArray().apply { rows.forEach { put(JSONObject().put("name", it.name).put("phone", it.phone)) } }.toString()) }
        profileDraft?.let { out.putString("profile_draft", it.toString()) }
        super.onSaveInstanceState(out)
    }
    override fun onResume() {
        super.onResume()
        if (!AccountStorage.isCurrent(store.appContext)) { recreate(); return }
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
    }
    override fun onPause() {
        handler.removeCallbacks(live)
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
        smsHelpDialog?.dismiss()
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
        if (callback.scheme == "com.guardian.tzindaapp.v2" && callback.host == "auth-callback") {
            intent.data = null // Do not replay the one-use callback after Activity recreation.
            passwordRecoveryIo.execute {
                runCatching { backend.acceptPasswordRecovery(callback) }
                    .onSuccess { runOnUiThread { if (!isDestroyed && !isFinishing) refreshPasswordRecovery() } }
                    .onFailure { failure -> runOnUiThread { if (!isDestroyed && !isFinishing) { authMode = AuthMode.RECOVER; render(); toast(failure.message ?: "No se pudo abrir el enlace.", true) } } }
            }
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
                    store.saveRole(if (camera) AppStore.ROLE_CAMERA else AppStore.ROLE_CAREGIVER)
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
                    tab = 0; render()
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
                    tab = 0; render()
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
        val content = column().apply {
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(18), dp(8), dp(18), dp(10))
            addView(text("●  LISTO PARA VINCULAR", 12, true, accent).apply {
                gravity = Gravity.CENTER; background = rounded(mint, 20); setPadding(dp(12), dp(8), dp(12), dp(8))
            })
            addView(text(title, 21, true).apply { gravity = Gravity.CENTER; setPadding(0, dp(12), 0, dp(4)) })
            addView(text("La otra persona puede escanear este QR desde TzindaApp, aunque esté en otra red Wi-Fi o use datos móviles.", 14, color = muted).apply { gravity = Gravity.CENTER })
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
            addView(text("Esperando a que el otro dispositivo se conecte…", 13, color = accent).apply {
                gravity = Gravity.CENTER; setPadding(0, dp(6), 0, 0)
            })
            addView(text("Código de un solo uso · vence a las $expiresAt\n\nSi no puede escanearlo, puede escribir los 8 caracteres.", 14, color = muted).apply {
                gravity = Gravity.CENTER; setPadding(dp(4), dp(10), dp(4), 0)
            })
        }
        val dialog = AlertDialog.Builder(this).setView(content).setPositiveButton("Listo", null).create()
        val pollHandler = Handler(Looper.getMainLooper())
        var dismissed = false
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
                                        dismissed = true
                                        runCatching { dialog.dismiss() }
                                        toast("¡Cámara vinculada exitosamente!")
                                        render()
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
                                        dismissed = true
                                        runCatching { dialog.dismiss() }
                                        toast("¡Cuidador/a vinculado exitosamente!")
                                        render()
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
        override fun run() { refreshPasswordRecovery(); refreshLive(); handler.postDelayed(this, 1_000) }
    }
    private fun refreshLive() {
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
        homeStatus?.text = store.prefs.getString("status", "Configura tus contactos y activa el monitoreo.")
        val braceletStatus = store.prefs.getString("arduino_status", "Pulsera sin conectar.").orEmpty()
        braceletStatusViews.forEach { it.text = braceletStatus }
        braceletStatusPillUpdaters.forEach { it(braceletStatus) }
        if (!isCaregiver && tab in 0..1) refreshPatientVitals()
        homeHeadline?.text = if (GuardianMonitorService.monitoring) "Sistema en línea y seguro" else "La protección está en pausa"
        monitorButton?.text = if (GuardianMonitorService.monitoring) "Detener monitoreo" else "Activar monitoreo"
        monitorToggle?.let { toggle ->
            updatingMonitorToggle = true
            toggle.isChecked = GuardianMonitorService.monitoring
            toggle.contentDescription = if (GuardianMonitorService.monitoring) "Pausar monitoreo" else "Activar monitoreo"
            updatingMonitorToggle = false
        }
        appBarStatus?.apply {
            val linked = when {
                isCaregiver -> store.prefs.getString("caregiver_link_id", "").orEmpty().isNotBlank()
                isCameraRole -> store.prefs.getString("paired_camera_device_id", "").orEmpty().isNotBlank()
                else -> GuardianMonitorService.monitoring
            }
            val label = when {
                isCaregiver -> if (linked) "Vínculo activo" else "Sin vínculo"
                isCameraRole -> if (linked) "Cámara vinculada" else "Sin vínculo"
                GuardianMonitorService.monitoring -> "Protección activa"
                else -> "En pausa"
            }
            val tone = if (linked) TzindaDesign.Tone.SUCCESS else TzindaDesign.Tone.NEUTRAL
            text = label
            setTextColor(tz.toneForeground(tone))
            background = rounded(tz.toneBackground(tone), 22)
            contentDescription = "Estado actual: $label"
            minHeight = dp(40)
        }
        appBarProfile?.text = store.profile().text("fullName").trim().split(Regex("\\s+")).filter { it.isNotBlank() }.take(2).mapNotNull { it.firstOrNull()?.uppercaseChar() }.joinToString("").ifBlank { "T" }
        appBarProfile?.contentDescription = "Abrir mi perfil"
        emergencyProgressCard?.visibility = if (store.prefs.getString("dispatch_id", "").orEmpty().isNotBlank()) View.VISIBLE else View.GONE
        emergencyProgressStatus?.text = store.prefs.getString("status", "Solicitando SMS y llamadas a tus contactos.")
        val id = store.prefs.getString("pending_id", "").orEmpty()
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
            countdownDescription?.text = "Si no cancelas, TzindaApp solicitará SMS a tus contactos e intentará llamarles cuando termine la cuenta regresiva."
            countdownRing?.progress = (left / 20f).coerceIn(0f, 1f)
            countdownRing?.contentDescription = "Quedan $left segundos"
        } else {
            countdownDialog?.dismiss(); countdownDialog = null; shownIncident = null
            countdownText = null; countdownDescription = null; countdownRing = null
            if (Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(false); setTurnScreenOn(false) }
            else window.clearFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
    }

    private fun showCountdownDialog() {
        if (isFinishing || isDestroyed) return
        val content = column().apply { setPadding(dp(18), dp(20), dp(18), dp(24)) }
        val scroll = ScrollView(this).apply { isFillViewport = true; addView(content) }
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.addView(tz.iconBadge(R.drawable.ic_shield, TzindaDesign.Tone.DANGER, 42), LinearLayout.LayoutParams(dp(42), dp(42)))
        val headerCopy = column().apply { setPadding(dp(12), 0, 0, 0) }
        headerCopy.addView(text("Alarma de caída activa", 21, true, ink))
        headerCopy.addView(text("TzindaApp detectó un movimiento compatible con una caída.", 14, color = muted))
        header.addView(headerCopy, LinearLayout.LayoutParams(0, -2, 1f))
        content.addView(header)
        tz.addGap(content, 14)

        val warning = card(content)
        warning.addView(text("POSIBLE CAÍDA DETECTADA", 12, true, tz.critical))
        warning.addView(text("¿Estás bien?", 28, true, tz.critical))
        warning.addView(text("Si puedes, pulsa “Estoy bien” para cancelar el aviso. Si no respondes, se pedirá ayuda a tus contactos.", 15, color = muted))

        val timer = card(content)
        val timerFrame = FrameLayout(this).apply { minimumHeight = dp(244) }
        val ring = CountdownRing(this).apply { progress = 1f }
        countdownRing = ring
        timerFrame.addView(ring, FrameLayout.LayoutParams(dp(224), dp(224), Gravity.CENTER))
        val timerLabels = column().apply { gravity = Gravity.CENTER }
        val seconds = text("20", 82, true, ink).apply {
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 0)
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            fontFeatureSettings = "tnum"
            contentDescription = "Quedan 20 segundos"
        }
        countdownText = seconds
        timerLabels.addView(seconds)
        timerLabels.addView(text("SEGUNDOS", 12, true, muted).apply {
            gravity = Gravity.CENTER
            letterSpacing = .08f
        })
        timerFrame.addView(timerLabels, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        timer.addView(timerFrame)
        val description = text("Si no cancelas, se solicitarán SMS y se intentarán llamadas a tus contactos.", 16, color = muted).apply {
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(10), dp(8), dp(8))
        }
        countdownDescription = description
        timer.addView(description)
        timer.addView(text("El SMS y las llamadas dependen de tu SIM, permisos, cobertura y del teléfono. La app no puede confirmar que alguien haya respondido.", 13, color = muted).apply {
            gravity = Gravity.CENTER
        })

        val cancel = Button(this).apply {
            text = "Estoy bien · cancelar alerta"
            isAllCaps = false
            typeface = tz.boldTypeface()
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, tz.textScale(18f))
            minHeight = tz.controlScale(64)
            setTextColor(if (dark) tz.ink else Color.WHITE)
            gravity = Gravity.CENTER
            // Neutral high-contrast surface; Imperial Red stays reserved for sending help.
            background = RippleDrawable(
                ColorStateList.valueOf(if (dark) 0x22000000 else 0x33FFFFFF),
                rounded(if (dark) tz.softSurface else tz.ink, 14),
                null,
            )
            contentDescription = "Estoy bien. Cancelar la alerta y no enviar mensajes."
            setOnClickListener { service(GuardianMonitorService.ACTION_CANCEL) }
            layoutParams = LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) }
        }
        content.addView(cancel)
        val needHelp = tz.dangerButton("Pedir ayuda ahora", R.drawable.ic_emergency) {
            AlertDialog.Builder(this).setTitle("Enviar alerta ahora")
                .setMessage("Se solicitarán SMS y se intentará llamar a tus contactos inmediatamente, sin esperar a que termine la cuenta regresiva.")
                .setNegativeButton("Seguir esperando", null)
                .setPositiveButton("Enviar alerta") { _, _ -> prepareAlert(GuardianMonitorService.ACTION_ALERT_NOW) }
                .show()
        }.apply { minHeight = dp(60); textSize = 17f }
        content.addView(needHelp)
        content.addView(text("También puedes usar el teléfono para llamar a emergencias. TzindaApp no sustituye a los servicios de emergencia.", 13, color = muted).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(10), 0, 0)
        })

        val dialog = Dialog(this)
        dialog.setContentView(scroll)
        dialog.setCancelable(false)
        dialog.setCanceledOnTouchOutside(false)
        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(paper))
            setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
            addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            setDimAmount(0f)
        }
        countdownDialog = dialog
    }
    private fun selectTab(index: Int) {
        if (tab == CAMERA_TAB && index != CAMERA_TAB) disposeCameraController()
        saveDrafts(); tab = index; hideKeyboard(); render()
    }
    private fun chooseRole(role: String) {
        if (role == store.role()) { tab = 0; render(); return }
        val applyRole = {
            if (GuardianMonitorService.monitoring || store.prefs.getBoolean("monitor_enabled", false))
                service(GuardianMonitorService.ACTION_STOP)
            // Leaving the camera role must release the lens instead of keeping a hidden session.
            disposeCameraController()
            store.saveRole(role)
            tab = 0
            render()
        }
        if (store.role().isNotBlank() && (GuardianMonitorService.monitoring || store.prefs.getBoolean("monitor_enabled", false))) {
            AlertDialog.Builder(this).setTitle("Cambiar modo de este teléfono")
                .setMessage("Al cambiar de rol se detendrá el monitoreo de caídas en este teléfono.")
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Cambiar") { _, _ -> applyRole() }.show()
        } else applyRole()
    }
    private fun openCameraScreen() {
        if (tab != CAMERA_TAB) cameraReturnTab = if (isPrimaryTab(tab)) tab else 0
        val continueToCamera = { selectTab(CAMERA_TAB) }
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
        if (tab != MEDICATIONS_TAB) medicationReturnTab = if (isPrimaryTab(tab)) tab else 0
        selectTab(MEDICATIONS_TAB)
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
            index == CAMERA_TAB && !isCameraRole

    private fun returnTabFor(index: Int): Int = primaryTabOr(when (index) {
        TzindaTabs.PROFILE -> profileReturnTab
        CAMERA_TAB -> cameraReturnTab
        DEVICE_TAB -> deviceReturnTab
        MEDICATIONS_TAB -> medicationReturnTab
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
        // Keep the reading position when the same screen is rebuilt (sync, permissions, toggles).
        // A real navigation changes the key, so those screens still start at the top.
        val scrollBefore = (frame.getChildAt(0) as? ScrollView)?.scrollY ?: 0
        val renderKey = "${store.role()}|$tab"
        frame.removeAllViews(); homeStatus = null; homeHeadline = null; monitorButton = null; monitorToggle = null
        patientPulseValue = null; patientSpo2Value = null; patientPulseUnit = null; patientSpo2Unit = null
        patientPulseSpark = null; patientSpo2Spark = null
        patientVitalsStatus = null; patientVitalsRefreshAt = 0L
        caregiverSharedLocationView = null
        caregiverLocationCoordinatesView = null; caregiverLocationFreshnessView = null; caregiverMapButton = null
        caregiverLocationUpdater = null
        caregiverVitalsUpdater = null
        braceletStatusViews.clear()
        braceletStatusPillUpdaters.clear()
        emergencyProgressCard = null; emergencyProgressStatus = null
        smsPermissionStatus = null; smsPermissionButton = null; smsPermissionPill = null
        contactEdits.clear(); profileEdits.clear()
        authMode?.let { lastRenderKey = null; renderAuth(it); return }
        val role = TzindaNavigationPolicy.role(store.role())
        val appBar = findViewById<View>(R.id.app_bar)
        val navigation = findViewById<LinearLayout>(R.id.navigation)
        val onboarding = tab == ROLE_TAB
        val fullscreenCamera = tab == CAMERA_TAB
        val secondary = isSecondaryScreen(tab)
        appBar.visibility = if (onboarding || fullscreenCamera) View.GONE else View.VISIBLE
        navigation.visibility = if (onboarding || fullscreenCamera) View.GONE else View.VISIBLE
        // The viewfinder uses the whole display. Every other screen restores the system bars.
        root.setBackgroundColor(if (fullscreenCamera) Color.BLACK else paper)
        window.statusBarColor = if (fullscreenCamera) Color.BLACK else paper
        window.navigationBarColor = if (fullscreenCamera) Color.BLACK else paper
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = !dark && !fullscreenCamera
            isAppearanceLightNavigationBars = !dark && !fullscreenCamera
            if (fullscreenCamera) {
                hide(WindowInsetsCompat.Type.systemBars())
                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            } else {
                show(WindowInsetsCompat.Type.systemBars())
            }
        }
        appBar.setBackgroundColor(paper)
        // Cápsula flotante: superficie neutra con hairline para separarla del fondo.
        navigation.background = tz.rounded(
            tz.colorWithAlpha(tz.cardColor, if (dark) .96f else 1f), 28,
        ).apply { setStroke(dp(1), outline) }
        navigation.elevation = dp(8).toFloat()
        navigation.clipToOutline = false
        findViewById<TextView>(R.id.app_bar_title).apply {
            setTextColor(ink)
            text = TzindaNavigationPolicy.barTitle(role, tab)
        }
        appBarSubtitle?.visibility = View.GONE
        // The content already communicates the current state. Keeping the header to brand, back
        // navigation and profile removes duplicated labels from every screen.
        appBarStatus?.visibility = View.GONE
        findViewById<ImageView>(R.id.app_bar_logo).apply {
            setColorFilter(accent)
            visibility = View.GONE
        }
        findViewById<ImageView>(R.id.app_bar_back).apply {
            setColorFilter(accent)
            visibility = if (secondary) View.VISIBLE else View.GONE
            contentDescription = "Volver a ${TzindaNavigationPolicy.screenName(role, returnTabFor(tab))}"
        }
        appBarProfile?.apply { setTextColor(onAccent); background = rounded(tz.primaryFill, 24) }
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
                setTextColor(if (selected) tz.primaryDeep else tz.metaGray)
                textSize = if (simpleMode) 13f else 11f
                setTypeface(tz.boldTypeface(), if (selected) Typeface.BOLD else Typeface.NORMAL)
                background = RippleDrawable(
                    ColorStateList.valueOf(tz.colorWithAlpha(tz.accent, .20f)),
                    rounded(if (selected) tz.navSelected else Color.TRANSPARENT, 999),
                    null,
                )
                stateListAnimator = null; elevation = 0f
                setPadding(dp(2), dp(6), dp(2), dp(6))
                minHeight = dp(if (simpleMode) 62 else 56)
                val icon = getDrawable(item.iconRes)?.mutate()
                icon?.setTint(if (selected) tz.primaryDeep else tz.metaGray)
                icon?.setBounds(0, 0, dp(22), dp(22))
                setCompoundDrawables(null, icon, null, null)
                compoundDrawablePadding = dp(3)
                contentDescription = "${item.label}${if (selected) ", seleccionado" else ""}"
                setOnClickListener {
                    if (role == TzindaRole.CAREGIVER && item.targetTab == TzindaTabs.ACTIVITY) activitySection = 1
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
        val body = column().apply { setPadding(dp(16), dp(12), dp(16), dp(28)) }
        val scroll = ScrollView(this).apply { isFillViewport = true; addView(body); tag = "screen_scroll" }
        frame.addView(scroll)
        if (renderKey == lastRenderKey) scroll.post { scroll.scrollTo(0, scrollBefore) }
        lastRenderKey = renderKey
        when (tab) {
            ROLE_TAB -> roleSelection(body)
            0 -> home(body)
            1 -> activity(body)
            2 -> if (isCameraRole) cameraPairingScreen(body) else contacts(body)
            3 -> settings(body)
            DEVICE_TAB -> deviceLinkScreen(body)
            MEDICATIONS_TAB -> {
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
            SUPERVISION_TAB -> if (isCaregiver) supervisionCenter(body) else deviceLinkScreen(body)
            else -> profile(body)
        }
        refreshLive(); refreshPermissionUi()
    }
    private fun renderAuth(mode: AuthMode) {
        findViewById<View>(R.id.app_bar).visibility = View.GONE
        findViewById<LinearLayout>(R.id.navigation).visibility = View.GONE
        val body = column().apply { setPadding(dp(24), dp(36), dp(24), dp(28)) }
        frame.addView(ScrollView(this).apply { isFillViewport = true; addView(body) })
        body.addView(ImageView(this).apply { setImageResource(R.drawable.ic_shield); setColorFilter(accent) }, LinearLayout.LayoutParams(dp(52), dp(52)))
        gap(body, 18)
        val title = when(mode) { AuthMode.LOGIN -> "Inicia sesión"; AuthMode.REGISTER -> "Crea tu cuenta"; AuthMode.RECOVER -> "Recupera tu acceso"; AuthMode.RESET -> "Nueva contraseña" }
        heading(body, "TZINDAAPP · CUENTA SEGURA", title, when(mode) {
            AuthMode.LOGIN -> "Sincroniza tu perfil, contactos y alertas con tu cuenta."
            AuthMode.REGISTER -> "Crea una cuenta con tu correo y contraseña para sincronizar tus datos."
            AuthMode.RECOVER -> "Solicita el enlace aquí y ábrelo en este mismo teléfono. Solo el enlace más reciente será válido."
            AuthMode.RESET -> "Elige una contraseña nueva. Después inicia sesión con ella; la cuenta activa de la app no cambia al abrir este enlace."
        })
        val form = card(body)
        if (mode == AuthMode.REGISTER) authName = field(form, "Nombre", "", tag = "auth_name") else authName = null
        if (mode != AuthMode.RESET) {
            authEmail = field(form, "Correo electrónico", "", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS, "auth_email")
        } else authEmail = null
        if (mode == AuthMode.LOGIN || mode == AuthMode.REGISTER || mode == AuthMode.RESET) {
            authPassword = field(form, if (mode == AuthMode.RESET) "Nueva contraseña (mínimo 8 caracteres)" else "Contraseña", "", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD, "auth_password")
            tz.switchRow(
                form,
                "Mostrar contraseña",
                "Úsalo solo si nadie más está mirando la pantalla.",
                value = false,
                action = { visible ->
                    authPassword?.let { input ->
                        val cursor = input.selectionStart.coerceAtLeast(0)
                        input.inputType = if (visible) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                        else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                        input.setSelection(cursor.coerceAtMost(input.text.length))
                    }
                },
            )
        } else authPassword = null
        if (mode == AuthMode.REGISTER || mode == AuthMode.RESET) authConfirmation = field(form, "Confirma la contraseña", "", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD, "auth_confirmation") else authConfirmation = null
        if (mode == AuthMode.REGISTER) {
            authPrivacy = tz.switchRow(
                form,
                "Acepto el aviso de privacidad",
                "Confirmo que leí cómo se sincronizan mis datos.",
                value = false,
                action = {},
            )
            form.addView(button("Leer aviso de privacidad y uso", false) {
                AlertDialog.Builder(this).setTitle("Privacidad y uso de TzindaApp")
                    .setMessage("Al iniciar sesión, Supabase puede almacenar tu nombre y rol, contactos de confianza con teléfono, eventos de caída y estados técnicos de SMS/llamada, lecturas válidas de pulso/SpO₂ del UNO Q y la ubicación en vivo cuando la autorizas y el monitoreo está activo.\n\nSi registras medicamentos, el nombre, dosis indicada, presentación, horarios, instrucciones e historial de tomas se guardan en este teléfono y se sincronizan con Supabase. Cada medicamento permanece privado hasta que actives “Compartir con mi cuidador/a”; solo una cuenta con vínculo activo podrá consultarlo. El cuidador vinculado puede registrar tomas compartidas; no puede editar el medicamento ni sus horarios. Puedes retirar el permiso de compartir editando el registro. TzindaApp no recomienda ni modifica dosis. Si escaneas la caja o la receta, la foto se lee en este teléfono y no se guarda ni se envía.\n\nEl domicilio, fecha de nacimiento, video y muestras crudas de movimiento no se sincronizan. Las funciones locales siguen disponibles sin Internet. TzindaApp es experimental y no sustituye servicios de emergencia ni atención médica. Puedes cerrar sesión o solicitar la eliminación de la cuenta y los datos remotos desde Ajustes.")
                    .setPositiveButton("Entendido", null).show()
            })
        }
        authStatus = text("", 14, color = muted).also { form.addView(it) }
        val actionLabel = when(mode) { AuthMode.LOGIN -> "Iniciar sesión"; AuthMode.REGISTER -> "Crear cuenta"; AuthMode.RECOVER -> "Enviar enlace"; AuthMode.RESET -> "Guardar contraseña" }
        form.addView(button(actionLabel) { submitAuth(mode) })
        if (mode == AuthMode.LOGIN) body.addView(button("Crear una cuenta", false) { authMode = AuthMode.REGISTER; render() })
        if (mode == AuthMode.LOGIN) body.addView(button("Olvidé mi contraseña", false) { authMode = AuthMode.RECOVER; render() })
        if (mode == AuthMode.REGISTER || mode == AuthMode.RECOVER) body.addView(button("Volver a iniciar sesión", false) { authMode = AuthMode.LOGIN; render() })
        if (mode == AuthMode.RESET) body.addView(button("Cancelar recuperación", false) { backend.cancelPasswordRecovery(); authMode = null; render() })
        if (mode == AuthMode.LOGIN) body.addView(button("Usar la app en este teléfono", false) {
            store.prefs.edit().putBoolean("local_only_mode", true).apply(); authMode = null; render()
        })
        body.addView(text("La detección, cuenta regresiva y avisos locales siguen funcionando en este teléfono. La sincronización solo empieza al iniciar sesión.", 14, color = muted).apply { setPadding(0, dp(16), 0, 0) })
    }
    private fun submitAuth(mode: AuthMode) {
        val email = authEmail?.text?.toString()?.trim().orEmpty()
        val password = authPassword?.text?.toString().orEmpty()
        val name = authName?.text?.toString()?.trim().orEmpty()
        val confirmation = authConfirmation?.text?.toString().orEmpty()
        if (mode != AuthMode.RESET && !AuthInputPolicy.validEmail(email)) { authStatus?.text = "Escribe un correo válido."; return }
        if (mode == AuthMode.REGISTER && (name.isBlank() || name.length > 120 || !authPrivacy!!.isChecked)) { authStatus?.text = "Completa tu nombre y acepta el aviso de sincronización."; return }
        if (mode == AuthMode.LOGIN && password.isBlank()) { authStatus?.text = "Escribe tu contraseña."; return }
        if (mode == AuthMode.REGISTER || mode == AuthMode.RESET) {
            if (!AuthInputPolicy.validPassword(password)) { authStatus?.text = "La contraseña debe tener entre 8 y 128 caracteres."; return }
            if (password != confirmation) { authStatus?.text = "Las contraseñas no coinciden."; return }
        }
        if (!backend.isConfigured) { authStatus?.text = "Supabase aún no está configurado. Puedes seguir en modo local."; return }
        authStatus?.text = "Conectando de forma segura…"
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
                result.onSuccess { message ->
                    authStatus?.text = message
                    if (mode == AuthMode.RESET) { authMode = AuthMode.LOGIN; render(); authStatus?.text = message }
                    if (mode in setOf(AuthMode.LOGIN, AuthMode.REGISTER) && sessions.read() != null) {
                        sessions.read()?.let { session ->
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
                                tab = if (store.role().isBlank()) ROLE_TAB else 0
                                toast("Cuenta cambiada. Los datos anteriores siguen guardados por separado.")
                            }
                            store.prefs.edit().putBoolean("local_only_mode", false).apply()
                            if (mode == AuthMode.REGISTER && authPrivacy?.isChecked == true) {
                                store.prefs.edit().putString("privacy_notice_version", "2026-10-21-v1")
                                    .putLong("privacy_consent_at", System.currentTimeMillis()).apply()
                            }
                            store.prepareContactsForUser(session.userId)
                            authMode = null
                            val pendingType = store.prefs.getString("pending_pairing_type", "").orEmpty()
                            val pendingCode = store.prefs.getString("pending_pairing_code", "").orEmpty()
                            store.prefs.edit().remove("pending_pairing_type").remove("pending_pairing_code").apply()
                            if (pendingType in setOf("caregiver", "camera") && pendingCode.isNotBlank()) {
                                store.saveRole(if (pendingType == "camera") AppStore.ROLE_CAMERA else AppStore.ROLE_CAREGIVER)
                                tab = 0
                                SyncScheduler.enqueue(applicationContext)
                                render()
                                processPairingCodeOrLink("tzinda://pair?type=$pendingType&code=$pendingCode")
                            } else {
                                SyncScheduler.enqueue(applicationContext); render()
                            }
                        }
                    }
                }.onFailure { authStatus?.text = it.message ?: "No se pudo completar la operación." }
            }
        }
    }
    private fun roleSelection(body: LinearLayout) {
        body.setPadding(dp(20), dp(30), dp(20), dp(28))
        body.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_shield); setColorFilter(accent); contentDescription = null
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(52), dp(52)))
        tz.addGap(body, 18)
        heading(body, "TZINDAAPP · CUIDADO CONECTADO", "¿Cómo usarás TzindaApp?", "Elige el modo de este teléfono. Podrás cambiarlo después en Ajustes.")
        body.addView(text("Tu cuenta identifica quién eres. Este modo organiza las funciones de este teléfono; no cambia el modo de otros teléfonos ni concede acceso a datos ajenos. Los vínculos y permisos de compartir se autorizan por separado.", 14, color = muted))
        val options = listOf(
            Triple(AppStore.ROLE_WEARER, "Llevo el teléfono", "Persona protegida. Detecta posibles caídas, comparte lo que autorices y avisa a tu red."),
            Triple(AppStore.ROLE_CAREGIVER, "Acompaño a otra persona", "Cuidador o familiar. Consulta solo los datos que la persona protegida comparta."),
            Triple(AppStore.ROLE_CAMERA, "Este teléfono es una cámara", "Dispositivo de cámara. Detecta posturas de riesgo solo cuando abres la cámara y comparte eventos autorizados."),
        )
        options.forEach { (role, title, description) ->
            val selected = store.role() == role
            val item = card(body)
            val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            val badge = when (role) {
                AppStore.ROLE_WEARER -> Triple(R.drawable.ic_shield, tz.brandSoft, tz.accent)
                AppStore.ROLE_CAREGIVER -> Triple(R.drawable.ic_supervision, tz.pinkSoft, tz.pinkDeep)
                else -> Triple(R.drawable.ic_camera, tz.mint, tz.positive)
            }
            top.addView(tz.coloredBadge(badge.first, badge.second, badge.third, 44),
                LinearLayout.LayoutParams(dp(44), dp(44)))
            val copy = column().apply { setPadding(dp(12), 0, 0, 0) }
            copy.addView(text(title, 20, true))
            copy.addView(text(description, 14, color = muted))
            top.addView(copy, LinearLayout.LayoutParams(0, -2, 1f))
            item.addView(top)
            if (selected) item.addView(statusPill(TzindaCopy("Modo actual", "La navegación está adaptada a este teléfono.", TzindaTone.POSITIVE)))
            item.addView(button(
                if (selected) "Continuar como ${TzindaNavigationPolicy.roleLabel(TzindaNavigationPolicy.role(role)).lowercase(Locale.getDefault())}"
                else "Elegir este modo",
                !selected,
            ) { chooseRole(role) })
        }
        body.addView(text("Puedes usar TzindaApp sin cuenta; en ese caso los datos permanecen en este teléfono. La sincronización con Supabase solo empieza al iniciar sesión.", 13, color = muted).apply {
            setPadding(dp(4), dp(14), dp(4), dp(4))
        })
    }
    private fun cameraHome(body: LinearLayout) {
        val linkId = store.prefs.getString("paired_camera_device_id", "").orEmpty()
        val linked = linkId.isNotBlank()
        val cameraName = store.prefs.getString("paired_camera_name", "").orEmpty().ifBlank { "Cámara Tzinda" }
        val ownerName = store.prefs.getString("paired_camera_owner_name", "").orEmpty()
        heading(
            body,
            "MODO CÁMARA",
            if (linked) cameraName else "Cámara sin vincular",
            "Este teléfono funciona como un dispositivo de cámara independiente para la persona protegida.",
        )
        val status = card(body)
        status.addView(statusPill(TzindaStatusCopy.cameraLink(linked, cameraName)))
        if (linked && ownerName.isNotBlank()) {
            status.addView(text("Protegiendo a: $ownerName", 15, true, ink).apply { setPadding(0, dp(6), 0, 0) })
        }
        status.addView(text(if (linked) "Los eventos se guardan aquí y se envían cuando hay Internet. La cámara solo se activa al abrirla." else "Escanea el QR o escribe el código de 8 caracteres generado en el teléfono de la persona protegida.", 14, color = muted).apply { setPadding(0, dp(8), 0, 0) })
        status.addView(button(if (linked) "Abrir cámara de apoyo" else "Vincular con QR o código") {
            if (linked) openCameraScreen() else selectTab(2)
        })
        if (linked) status.addView(button("Desvincular cámara", false) { unlinkCameraAccess() })

        val detector = card(body)
        detector.addView(text("Detección bajo demanda", 20, true))
        detector.addView(text("La cámara no se activa en segundo plano. La imagen se procesa en este teléfono y no se guarda ni se comparte; solo se envía el evento de posible caída.", 14, color = muted))
        detector.addView(button("Abrir cámara ahora", false) { openCameraScreen() })
        detector.addView(text("La detección visual puede equivocarse. No sustituye la pulsera ni los servicios de emergencia.", 13, color = muted))

        if (linked) {
            val alerts = card(body)
            alerts.addView(text("Avisos y sincronización", 20, true))
            val notificationsAllowed = Build.VERSION.SDK_INT < 33 || has(Manifest.permission.POST_NOTIFICATIONS)
            alerts.addView(statusPill(TzindaCopy(
                if (notificationsAllowed) "Avisos locales permitidos" else "Avisos locales bloqueados",
                if (notificationsAllowed) "Este teléfono puede mostrar una notificación si detecta una posible caída." else "Permite las notificaciones para recibir el aviso en este teléfono.",
                if (notificationsAllowed) TzindaTone.POSITIVE else TzindaTone.WARNING,
            )))
            if (!notificationsAllowed) alerts.addView(button("Permitir avisos de cámara", false) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_CAMERA_NOTIFICATIONS)
            })
            alerts.addView(button("Sincronizar eventos ahora", false) {
                SyncScheduler.enqueue(applicationContext, immediate = true)
                toast("Sincronización de cámara solicitada.")
            })
            alerts.addView(button("Ver eventos registrados", false) { activitySection = 1; selectTab(1) })
        }
        body.addView(text("Vincular esta cámara no activa la cámara por sí solo. La persona protegida decide cuándo usarla.", 13, color = muted).apply {
            setPadding(dp(4), dp(12), dp(4), dp(4))
        })
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
        connect.addView(text("No necesitan estar en la misma red. Funciona con Wi-Fi o datos móviles si ambos teléfonos llegan a TzindaApp.", 14, color = muted))
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
        body.addView(button("Cambiar modo de este teléfono", false) { tab = ROLE_TAB; render() })
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
        content.addView(text("Ponle un nombre fácil de reconocer. Después comparte el QR; la cámara puede estar en cualquier red con conexión a TzindaApp.", 14, color = muted))
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
        val profile = store.profile()
        val name = profile.text("fullName").trim().substringBefore(" ")
        val recipients = store.recipients()
        val today = SimpleDateFormat("EEEE, d 'de' MMMM", Locale("es", "MX")).format(Date())
            .replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale("es", "MX")) else it.toString() }
        heading(
            body,
            today,
            if (name.isBlank()) "Tu tranquilidad, primero" else "Hola, $name",
            "",
        )

        if (smsPermissionState() != SmsPermissionPolicy.State.GRANTED) permissionCard(body)

        val monitoring = GuardianMonitorService.monitoring
        val monitorCopy = TzindaStatusCopy.monitoring(monitoring)
        tz.section(body, "Estado de protección").addView(
            text(if (monitoring) "Activo" else "En pausa", 13, true, if (monitoring) tz.positive else muted),
        )
        val hero = card(body)
        val heroHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        heroHeader.addView(
            tz.iconBadge(R.drawable.ic_bluetooth, if (monitoring) TzindaDesign.Tone.SUCCESS else TzindaDesign.Tone.NEUTRAL, size = 34),
            LinearLayout.LayoutParams(dp(34), dp(34)),
        )
        heroHeader.addView(
            text(if (arduinoConnectionAvailable()) "Dispositivo en línea" else "Sensores del teléfono", 15, true, ink)
                .apply { setPadding(dp(10), 0, 0, 0) },
            LinearLayout.LayoutParams(0, -2, 1f),
        )
        if (simpleMode) {
            // Stack the pill so it never has to wrap at 1.25x text scale.
            hero.addView(heroHeader)
            hero.addView(statusPill(monitorCopy))
        } else {
            heroHeader.addView(statusPill(monitorCopy))
            hero.addView(heroHeader)
        }
        homeHeadline = text(if (monitoring) "Sistema en línea y seguro" else "La protección está en pausa", 22, true, ink)
            .apply { setPadding(0, dp(10), 0, dp(2)) }
            .also { hero.addView(it) }
        homeStatus = text(monitorCopy.detail, 15, color = muted).also { hero.addView(it) }
        val heroFooter = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, 0)
        }
        heroFooter.addView(
            text(if (monitoring) "Detección de caídas en segundo plano" else "Detección detenida", 13, color = muted),
            LinearLayout.LayoutParams(0, -2, 1f),
        )
        heroFooter.addView(text(
            TzindaStatusCopy.sync(store.syncState().pending, store.syncState().failed, hasValidatedInternet(), sessions.read() != null).title,
            13, true, muted,
        ))
        hero.addView(heroFooter)
        val toggleRow = tz.switchRow(
            hero,
            if (monitoring) "Monitoreo en este teléfono" else "Activar monitoreo",
            if (recipients.isEmpty()) "Agrega al menos un contacto antes de activarlo." else "La detección usa los sensores del teléfono.",
            monitoring,
            action = { enabled ->
                if (!updatingMonitorToggle) {
                    if (enabled) prepareAlert(GuardianMonitorService.ACTION_START)
                    else service(GuardianMonitorService.ACTION_STOP)
                }
            },
        )
        monitorToggle = toggleRow
        monitorToggle?.contentDescription = if (monitoring) "Pausar monitoreo" else "Activar monitoreo"

        if (todayPossibleFallCount() > 0) {
            statusBanner(
                body,
                TzindaCopy("Hay una alerta de hoy para revisar", "Abre Actividad para ver el evento y el seguimiento registrado.", TzindaTone.WARNING),
                "Revisar actividad",
            ) { activitySection = 1; selectTab(1) }
        }

        tz.section(body, "¿Necesitas ayuda ahora?").addView(text("Urgencias", 13, true, tz.critical))
        val emergency = card(body)
        val sosHeader = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.TOP }
        sosHeader.addView(tz.iconBadge(R.drawable.ic_emergency, TzindaDesign.Tone.DANGER, size = 34), LinearLayout.LayoutParams(dp(34), dp(34)))
        sosHeader.addView(text("Presiona para activar el protocolo o contactar a tu red de apoyo inmediata.", 15, color = muted).apply {
            setPadding(dp(10), 0, 0, 0)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        emergency.addView(sosHeader)
        emergency.addView(tz.dangerButton("Pedir Ayuda SOS • 911") {
            AlertDialog.Builder(this).setTitle("Enviar SMS de ayuda")
                .setMessage("Se solicitarán SMS a ${TzindaText.count(store.recipients().size, "contacto")} y se intentará llamarles. La recepción depende de la SIM, la red y la disponibilidad del teléfono.")
                .setNegativeButton("Cancelar", null).setPositiveButton("Enviar") { _, _ -> prepareAlert(GuardianMonitorService.ACTION_ALERT_NOW) }.show()
        })
        emergency.addView(text("Mantén presionado para confirmación instantánea; también puedes llamar directo a emergencias.", 12, color = muted).apply {
            setPadding(0, dp(6), 0, 0)
        })

        tz.section(body, "Constantes y actividad", null, "Resumen") { activitySection = 0; selectTab(1) }
        addVitalSummary(body, caregiver = false)

        tz.section(body, "Próximo tratamiento", null, "Recordatorio") { openMedicationScreen() }
        val ownerId = medicationRepository.currentOwnerScope()
        val ownMeds = runCatching { medicationRepository.database.medications(ownerId) }.getOrDefault(emptyList())
            .filter { !it.isDeleted && it.isActive }
        val nowMillis = System.currentTimeMillis()
        val nextDose = ownMeds
            .flatMap { med -> med.scheduleTimes.mapNotNull { t -> parseTimeToday(t, nowMillis)?.let { Pair(med, it) } } }
            .minByOrNull { it.second }
        val doseCard = card(body)
        if (nextDose == null) {
            doseCard.addView(text("Sin tratamientos programados para hoy.", 15, color = muted))
        } else {
            val doseRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            doseRow.addView(tz.iconBadge(R.drawable.ic_medication, TzindaDesign.Tone.BRAND, size = 40), LinearLayout.LayoutParams(dp(40), dp(40)))
            doseRow.addView(column().apply {
                addView(text(nextDose.first.name, 17, true, ink))
                addView(text(nextDose.first.prescribedDose.ifBlank { nextDose.first.presentation }, 14, color = muted))
            }, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(10) })
            doseRow.addView(tz.textAction("Registrar toma", action = { openMedicationScreen() }))
            doseCard.addView(doseRow)
        }

        val contactsCard = card(body)
        val contactsHeader = tz.section(contactsCard, "Contactos de confianza", TzindaText.count(recipients.size, "destinatario"), "Administrar")
        contactsHeader.setOnClickListener { selectTab(2) }
        if (recipients.isEmpty()) {
            contactsCard.addView(text("Aún no tienes contactos", 17, true, ink).apply { setPadding(0, dp(6), 0, 0) })
            contactsCard.addView(text("Agrega al menos una persona para activar el monitoreo y enviar alertas.", 14, color = muted))
            contactsCard.addView(button("Agregar contactos", false) { selectTab(2) })
        } else {
            recipients.take(2).forEachIndexed { index, contact ->
                tz.actionRow(
                    contactsCard,
                    contact.name.ifBlank { "Contacto ${index + 1}" },
                    contact.phone,
                    leadingText = contact.name.trim().split(Regex("\\s+")).filter { it.isNotBlank() }.take(2)
                        .mapNotNull { it.firstOrNull()?.uppercaseChar() }.joinToString("").ifBlank { "${index + 1}" },
                    trailingText = if (index == 0) "Prioridad 1" else null,
                    showDivider = index < minOf(recipients.size, 2) - 1,
                ) {
                    runCatching { startActivity(Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", contact.phone, null))) }
                        .onFailure { toast("No se pudo abrir el marcador del teléfono.", true) }
                }
            }
            contactsCard.addView(button("Ver y editar contactos", false) { selectTab(2) })
        }

        val shortcuts = card(body)
        shortcuts.addView(text("Cuidado diario", 20, true))
        shortcuts.addView(text("Medicamentos, rutinas y los dispositivos que te acompañan.", 14, color = muted))
        tz.actionRow(shortcuts, "Medicamentos y recordatorios", "Horarios indicados y tomas de hoy", R.drawable.ic_medication) { openMedicationScreen() }
        tz.actionRow(shortcuts, "Bitácora, zonas y rutinas", "Registros que decides compartir", R.drawable.ic_book) { openCareFeatures() }
        tz.actionRow(shortcuts, "Dispositivos y cuidador/a", "Pulsera, cámaras y vínculos", R.drawable.ic_phone_headset, showDivider = false) { openDeviceLinkScreen() }

        val sync = store.syncState()
        val syncCopy = TzindaStatusCopy.sync(
            sync.pending,
            sync.failed,
            hasValidatedInternet(),
            sessions.read() != null,
        )
        val syncCard = card(body)
        syncCard.addView(text("Internet y sincronización", 20, true))
        syncCard.addView(statusPill(syncCopy))
        syncCard.addView(text(syncCopy.detail, 14, color = muted).apply { setPadding(0, dp(8), 0, dp(2)) })
        syncCard.addView(text(store.prefs.getString("sync_status", "Aún sin sincronización remota confirmada").orEmpty(), 13, color = muted))
        store.syncModules().forEach { (module, state) ->
            syncCard.addView(text("$module: ${state.pending} pendientes · ${state.failed} con error", 13, color = muted))
        }
        syncCard.addView(button("Sincronizar ahora", false) {
            store.retryFailedSyncItems()
            SyncScheduler.enqueue(applicationContext, immediate = true)
            render()
        })

        val location = card(body)
        location.addView(text("Ubicación del teléfono", 20, true))
        val locationShared = store.prefs.getBoolean("include_location", false) && GuardianMonitorService.monitoring
        location.addView(statusPill(TzindaCopy(
            if (locationShared) "Ubicación autorizada" else "Ubicación no compartida",
            if (locationShared) "Se actualiza mientras la protección está activa y se adjunta a los avisos." else "Solo se usará si lo autorizas y el monitoreo está activo.",
            if (locationShared) TzindaTone.POSITIVE else TzindaTone.NEUTRAL,
        )))
        location.addView(text(store.locationText(), 14, color = muted).apply { setPadding(0, dp(8), 0, 0) })
        location.addView(button("Actualizar ubicación", false) { requestLocation() })

        body.addView(text("Tzinda Guardian Android v3.0 • Conexión cifrada", 13, color = muted).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(18), 0, dp(2))
        })
        body.addView(text("CENTRO MÉDICO TELEASISTIDO 24/7", 11, true, muted).apply {
            gravity = Gravity.CENTER
            letterSpacing = .08f
        })
        body.addView(text("La detección es experimental y puede omitir algunas caídas. No sustituye servicios de emergencia ni atención médica.", 13, color = muted).apply {
            setPadding(dp(4), dp(10), dp(4), dp(4))
        })
    }
    /**
     * Compact, honest vitals block used by Inicio. It keeps the existing live-update views
     * ([patientPulseValue], [patientSpo2Value] and [patientVitalsStatus]) intact.
     */
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
    private fun caregiverHome(body: LinearLayout) {
        refreshCaregiverSharedData()
        val linked = store.prefs.getString("caregiver_link_id", "").orEmpty().isNotBlank()
        val ownerName = store.prefs.getString("caregiver_shared_name", "").orEmpty().trim()
        val person = ownerName.ifBlank { "Persona protegida" }
        val events = sharedEvents()
        val devices = sharedDevices()
        val alertFollowups = latestAlertFollowups()
        val pending = events.filter { isPendingSharedAlert(it, alertFollowups) }
        val fetchedAt = store.prefs.getLong("caregiver_shared_fetched_at", 0L)
        val today = SimpleDateFormat("EEEE, d 'de' MMMM", Locale("es", "MX")).format(Date())
            .replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale("es", "MX")) else it.toString() }
        heading(
            body,
            today,
            if (linked) "Hola, cuidador/a" else "Acompaña con tranquilidad",
            if (linked) "El estado de $person, sus alertas y sus equipos en un solo lugar."
            else "Vincula a la persona protegida para recibir solo los datos que decida compartir.",
        )

        if (!linked) {
            val intro = card(body)
            intro.addView(statusPill(TzindaCopy("Sin vínculo activo", "Todavía no hay una persona conectada.", TzindaTone.NEUTRAL)))
            intro.addView(text("Conecta la cuenta de la persona a la que acompañas", 21, true))
            intro.addView(text("Necesitas un código QR o de 8 caracteres generado en su teléfono. El vínculo es necesario para recibir alertas, ubicación autorizada y medicamentos compartidos.", 14, color = muted))
            intro.addView(button("Vincular persona protegida") { selectTab(2) })
            val how = card(body)
            how.addView(text("Qué podrás ver", 20, true))
            tz.actionRow(how, "Alertas y actividad", "Solo eventos que la persona comparte", R.drawable.ic_alert)
            tz.actionRow(how, "Signos vitales", "Lecturas válidas de la pulsera, sin valores de ejemplo", R.drawable.ic_heart)
            tz.actionRow(how, "Medicamentos", "Solo los que la persona marque como compartidos; puedes registrar tomas, sin editar medicamentos", R.drawable.ic_medication, showDivider = false)
            val privacy = card(body)
            privacy.addView(text("Privacidad por diseño", 18, true))
            privacy.addView(text("No puedes editar medicamentos ni confirmar tomas. La cámara de la persona solo se activa cuando ella la abre. Si no hay datos recientes, la app lo dirá en lugar de inventar una conexión.", 14, color = muted))
            return
        }

        if (pending.isEmpty()) {
            statusBanner(
                body,
                TzindaCopy(
                    "Sin alertas pendientes",
                    "Nada requiere seguimiento ahora. Los últimos eventos aparecen más abajo.",
                    TzindaTone.POSITIVE,
                ),
            )
        } else pending.firstOrNull()?.let { alert ->
            val copy = TzindaCopy(
                if (pending.size == 1) "1 alerta sin confirmar" else "${pending.size} alertas sin confirmar",
                "${TzindaStatusCopy.classification(alert.optString("classification"))} · ${TzindaStatusCopy.source(alert.optString("source"))} · ${formatSharedMoment(alert.optString("occurred_at"))}",
                TzindaTone.CRITICAL,
            )
            statusBanner(body, copy, "Revisar alerta") { activitySection = 1; selectTab(1) }
        }

        tz.section(body, "Persona vinculada", null, "Actualizar") {
            caregiverSharedStatusView?.text = "Actualizando datos compartidos…"
            refreshCaregiverSharedData(force = true, renderWhenDone = true)
        }
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
        }, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(12) })
        hero.addView(personRow)
        hero.addView(Space(this), LinearLayout.LayoutParams(1, dp(16)))
        val metrics = tz.twoColumns(hero)
        tz.metricTile(metrics, "Alertas", pending.size.toString(), "", if (pending.isEmpty()) "Sin pendientes" else "Revisa el detalle", if (pending.isEmpty()) TzindaDesign.Tone.SUCCESS else TzindaDesign.Tone.WARNING)
        val devicesTile = tz.metricTile(metrics, "Equipos", devices.size.toString(), "", if (devices.isEmpty()) "Sin equipos" else "Estado abajo", TzindaDesign.Tone.INFO)
        (devicesTile.layoutParams as LinearLayout.LayoutParams).leftMargin = dp(8)
        hero.addView(tz.textAction("Ver persona y vínculo", action = { selectTab(2) }).apply { setPadding(0, dp(8), 0, 0) })

        val deviceCard = card(body)
        deviceCard.addView(text("Dispositivos de $person", 20, true))
        if (devices.isEmpty()) {
            deviceCard.addView(text("La cuenta vinculada aún no tiene equipos registrados.", 14, color = muted))
        } else {
            devices.take(3).forEachIndexed { index, device ->
                val copy = sharedDeviceCopy(device)
                tz.actionRow(
                    deviceCard,
                    device.optString("name").ifBlank { "Dispositivo" },
                    "${device.optString("device_type").replace('_', ' ').replaceFirstChar { it.uppercase() }} · ${copy.detail}",
                    leadingIcon = if (device.optString("device_type") == "phone") R.drawable.ic_phone else R.drawable.ic_phone_headset,
                    statusText = copy.title.substringBefore(" ·"),
                    statusTone = designTone(copy.tone),
                    showDivider = index < minOf(devices.size, 3) - 1,
                ) { openSupervisionCenter() }
            }
        }
        deviceCard.addView(button("Ver estado de todos los equipos", false) { openSupervisionCenter() })

        addSharedLocationCard(body)

        val vitals = card(body)
        vitals.addView(text("Signos vitales compartidos", 20, true))
        addVitalSummary(vitals, caregiver = true)
        vitals.addView(button("Ver historial y tendencia", false) { activitySection = 0; selectTab(1) })

        val linkedOwner = store.prefs.getString("caregiver_owner_id", "").orEmpty()
        val meds = runCatching { medicationRepository.database.medications(linkedOwner) }.getOrDefault(emptyList())
        val sharedMeds = meds.count { it.shareWithCaregiver && !it.isDeleted }
        val medsCard = card(body)
        medsCard.addView(text("Medicamentos compartidos", 20, true))
        medsCard.addView(statusPill(TzindaCopy("Solo lectura", "La persona protegida conserva el control de horarios y tomas.", TzindaTone.INFO)))
        medsCard.addView(text(
            when {
                sharedMeds > 0 -> "${TzindaText.count(sharedMeds, "medicamento compartido", "medicamentos compartidos")} y su historial de tomas."
                sessions.read() == null -> "Sesión vencida: inicia sesión para descargar los medicamentos compartidos."
                else -> "No hay medicamentos compartidos con este vínculo. La persona debe marcarlos como compartidos en su cuenta y pulsar Actualizar."
            },
            14, color = muted,
        ).apply { setPadding(0, dp(8), 0, 0) })
        medsCard.addView(button("Consultar medicamentos", false) { openMedicationScreen() })

        val care = card(body)
        care.addView(text("Más herramientas de cuidado", 20, true))
        tz.actionRow(care, "Bitácora, zonas y rutinas", "Solo registros compartidos y autorizados", R.drawable.ic_book) { openCareFeatures() }
        tz.actionRow(care, "Centro de supervisión", "Ubicación, vitales y estado de equipos", R.drawable.ic_supervision, showDivider = false) { openSupervisionCenter() }

        val recent = card(body)
        recent.addView(text("Actividad reciente", 20, true))
        if (events.isEmpty()) {
            recent.addView(text("Sin eventos compartidos.", 15, true, muted))
            recent.addView(text("Cuando la persona comparta una detección o alerta, aparecerá aquí. No se muestran eventos inventados.", 14, color = muted))
        } else {
            events.take(4).forEachIndexed { index, event ->
                val prepared = isPendingSharedAlert(event, alertFollowups)
                tz.actionRow(
                    recent,
                    TzindaStatusCopy.classification(event.optString("classification")),
                    // El estado "sin confirmar" ya vive en el chip de la derecha; repetirlo alarga la fila.
                    "${formatSharedMoment(event.optString("occurred_at"))} · ${TzindaStatusCopy.source(event.optString("source"))}" +
                        if (prepared) "" else " · ${TzindaStatusCopy.confirmation(event.optString("confirmation_status"))}",
                    leadingIcon = if (prepared) R.drawable.ic_alert else R.drawable.ic_activity,
                    trailingText = if (prepared) "Sin confirmar" else null,
                    showDivider = index < minOf(events.size, 4) - 1,
                ) { activitySection = 1; selectTab(1) }
            }
            recent.addView(button("Ver todas las alertas", false) { activitySection = 1; selectTab(1) })
        }
        addCaregiverSyncControls(body)
        body.addView(text("TzindaApp muestra únicamente los datos autorizados por la persona protegida. No sustituye servicios de emergencia ni atención médica.", 13, color = muted).apply {
            setPadding(dp(4), dp(12), dp(4), dp(4))
        })
    }
    private fun supervisionCenter(body: LinearLayout) {
        refreshCaregiverSharedData()
        val linked = store.prefs.getString("caregiver_link_id", "").orEmpty().isNotBlank()
        heading(body, "", "Supervisión", "Centro de acompañamiento familiar")
        if (!linked) {
            statusBanner(
                body,
                TzindaCopy("Sin vínculo activo", "Vincula una persona para consultar sus datos autorizados.", TzindaTone.NEUTRAL),
                "Vincular persona",
            ) { selectTab(2) }
            return
        }
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
            tz.section(body, "Alertas que requieren seguimiento", null, "Historial") { activitySection = 1; selectTab(1) }
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
            alerts.addView(button("Abrir historial completo", false) { activitySection = 1; selectTab(1) })
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
        val linkedCaregiver = isCaregiver && store.prefs.getString("caregiver_link_id", "").orEmpty().isNotBlank()
        heading(body, "CUIDADOS Y ACTIVIDAD", "Bitácora y rutinas", "Registros guardados primero en este teléfono y sincronizados al volver a tener Internet.")
        val careState = careFeatures.syncState()
        val pending = careState.pending
        val sync = card(body)
        sync.addView(text("Sincronización", 20, true))
        val syncCopy = TzindaStatusCopy.sync(pending, careState.failed, hasValidatedInternet(), sessions.read() != null)
        sync.addView(statusPill(syncCopy))
        sync.addView(text(if (isCaregiver) "Vista de solo lectura de los registros compartidos. No puedes crear ni modificar rutinas." else syncCopy.detail, 14, color = muted).apply { setPadding(0, dp(8), 0, 0) })
        val unassigned = careFeatures.unassignedCount()
        if (unassigned > 0) sync.addView(text("Hay $unassigned registros o borrados antiguos sin propietario confirmado. Se conservan en este teléfono; no se asignan a la persona vinculada. Al sincronizar se compararán sus identificadores con la cuenta autorizada.", 14, color = muted))
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

        val journalCard = card(body)
        journalCard.addView(text("Bitácora de cuidados", 20, true))
        journalCard.addView(button("Filtrar: ${careJournalFilter.ifBlank { "todos los tipos" }}", false) {
            val available = arrayOf("Todos", "Observación", "Actividad", "Incidencia", "Cambio relevante", "Seguimiento de alerta", "Entrada a zona", "Salida de zona", "Rutina realizada")
            AlertDialog.Builder(this).setTitle("Filtrar registros").setItems(available) { _, choice ->
                careJournalFilter = when (choice) { 0 -> ""; 6 -> "zona_entrada"; 7 -> "zona_salida"; else -> available[choice].lowercase(Locale.ROOT).replace(' ', '_') }
                render()
            }.show()
        })
        if (!isCaregiver) journalCard.addView(button("Registrar observación", false) { createJournalEntry() })
        val journalRowsAll = if (isCaregiver) sharedCareRecords().filter { it.optString("record_kind") == "journal" }.mapNotNull { it.optJSONObject("record") }
            else careFeatures.rows("journal")
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

        val zonesCard = card(body)
        zonesCard.addView(text("Zonas seguras", 20, true))
        val canManageZones = !isCaregiver || linkedCaregiver
        if (canManageZones) {
            zonesCard.addView(text(
                if (isCaregiver) "Puedes crear, editar o pausar las zonas que la persona comparte contigo."
                else "Una zona es un círculo alrededor de un punto, por ejemplo tu casa o la de un familiar.",
                14, color = muted,
            ))
            zonesCard.addView(button("Agregar zona", false) { editSafeZone(null) })
        }
        val linkedOwnerForZones = store.prefs.getString("caregiver_owner_id", "").orEmpty()
        val zones = if (isCaregiver) {
            val local = careFeatures.zones().filter { linkedOwnerForZones.isNotBlank() && it.optString("ownerUserId") == linkedOwnerForZones }
            val remote = sharedCareRecords().filter { it.optString("record_kind") == "zone" }.mapNotNull { it.optJSONObject("record") }
            (local + remote).distinctBy { it.optString("id") }
        } else careFeatures.zones()
        if (zones.isEmpty()) zonesCard.addView(text("Sin zonas configuradas.", 14, color = muted))
        zones.forEach { zone ->
            val last = zone.optLong("lastLocationAt")
            val lastText = if (last > 0) "Último punto: ${SimpleDateFormat("d MMM · HH:mm", Locale("es", "MX")).format(Date(last))}" else "Aún sin ubicación válida"
            zonesCard.addView(text(zone.optString("name"), 16, true).apply { setPadding(0, dp(10), 0, 0) })
            zonesCard.addView(text("${zone.optInt("radius", 150)} m · $lastText", 13, color = muted))
            zonesCard.addView(statusPill(TzindaCopy(
                if (zone.optBoolean("enabled", true)) "Activa" else "Pausada",
                if (zone.optBoolean("enabled", true)) "Se evalúa cuando hay una ubicación válida." else "No se evalúa hasta activarla.",
                if (zone.optBoolean("enabled", true)) TzindaTone.POSITIVE else TzindaTone.NEUTRAL,
            )))
            if (canManageZones) zonesCard.addView(button("Administrar ${zone.optString("name")}", false) {
                AlertDialog.Builder(this).setTitle(zone.optString("name")).setItems(arrayOf("Editar", if (zone.optBoolean("enabled", true)) "Pausar" else "Activar", "Eliminar")) { _, choice ->
                    when (choice) {
                        0 -> editSafeZone(zone)
                        1 -> { if (persistCare("zone", JSONObject(zone.toString()).put("enabled", !zone.optBoolean("enabled", true)))) { SyncScheduler.enqueue(applicationContext, immediate = true); render() } }
                        2 -> AlertDialog.Builder(this).setMessage("¿Eliminar esta zona?").setNegativeButton("Cancelar", null).setPositiveButton("Eliminar") { _, _ -> runCatching { careFeatures.delete("zone", zone.optString("id"), zone.optString("ownerUserId")) }.onSuccess { SyncScheduler.enqueue(applicationContext, immediate = true); render() }.onFailure { toast(it.message ?: "No se pudo eliminar la zona.", true) } }.show()
                    }
                }.show()
            })
        }
        if (isCaregiver && linkedCaregiver) zonesCard.addView(text("Los cambios se guardan en este teléfono y se sincronizan con la cuenta de la persona.", 13, color = muted))
        if (!linkedCaregiver && isCaregiver) zonesCard.addView(text("Vincula a la persona para consultar y administrar sus zonas compartidas.", 13, color = muted))

        val routines = card(body)
        routines.addView(text("Rutinas", 20, true))
        if (!isCaregiver) routines.addView(button("Crear rutina", false) { createRoutine() })
        val routineRows = if (isCaregiver) sharedCareRecords().filter { it.optString("record_kind") == "routine" }.mapNotNull { it.optJSONObject("record") }
            else careFeatures.rows("routine")
        if (routineRows.isEmpty()) {
            routines.addView(text("Sin rutinas registradas. La app solo mostrará actividades marcadas por la persona.", 14, color = muted))
        } else routineRows.forEach { routine ->
            val repeatDays = routine.optJSONArray("days")
            val days = if (repeatDays == null || repeatDays.length() == 0) emptySet() else (0 until repeatDays.length()).mapNotNull { repeatDays.optInt(it).takeIf { day -> day in 1..7 } }.toSet()
            val todayMinute = java.time.LocalTime.now().let { it.hour * 60 + it.minute }
            val routineMinute = runCatching { java.time.LocalTime.parse(routine.optString("time")).let { it.hour * 60 + it.minute } }.getOrNull()
            val due = routineMinute?.let { RoutinePolicy.dueToday(days, it, todayMinute) } == true
            routines.addView(text(routine.optString("name"), 16, true).apply { setPadding(0, dp(10), 0, 0) })
            routines.addView(text("${routine.optString("time", "hora no definida")} · Días: ${days.sorted().joinToString(", ").ifBlank { "sin horario semanal" }}", 13, color = muted))
            if (due) routines.addView(statusPill(TzindaCopy("Programada hoy", "La persona puede marcarla como realizada.", TzindaTone.INFO)))
            if (!isCaregiver) routines.addView(button("Marcar como realizada", false) {
                if (!persistCare("journal", JSONObject().put("ownerUserId", routine.optString("ownerUserId")).put("type", "rutina_realizada").put("description", routine.optString("name"))
                    .put("occurredAt", System.currentTimeMillis()).put("source", "user_action").put("sharedWithCaregiver", routine.optBoolean("sharedWithCaregiver")))) return@button
                SyncScheduler.enqueue(applicationContext); toast("Actividad registrada."); render()
            })
        }
        val notices = card(body)
        notices.addView(text("Avisos de inactividad", 19, true))
        notices.addView(text("No se generan avisos automáticos: la app todavía no tiene una fuente fiable que distinga inactividad física de falta de datos o de conexión.", 14, color = muted))
        if (isCaregiver) notices.addView(text("Solo aparecen los registros que la persona haya compartido y que el vínculo permita consultar.", 13, color = muted))
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

    private fun editSafeZone(existing: JSONObject?) {
        val formOwner = careFeatures.currentOwner() ?: return
        val panel = column().apply { setPadding(dp(2), dp(4), dp(2), dp(4)) }
        panel.addView(text("La zona se calcula en este teléfono. Compartirla permite que el cuidador vinculado consulte si la persona está dentro o fuera; no activa un rastreo continuo.", 14, color = muted))
        val name = field(
            panel, "Nombre de la zona", existing?.optString("name").orEmpty(),
            tag = "zone_name", hintText = "Ej. Casa",
        )
        val lastLat = store.prefs.getString("latitude", "").orEmpty().toDoubleOrNull()
        val lastLon = store.prefs.getString("longitude", "").orEmpty().toDoubleOrNull()
        val latitudeText = existing?.takeIf { it.has("latitude") }?.optDouble("latitude")?.toString().orEmpty()
        val longitudeText = existing?.takeIf { it.has("longitude") }?.optDouble("longitude")?.toString().orEmpty()
        val lat = field(
            panel, "Latitud del centro", latitudeText.ifBlank { lastLat?.toString().orEmpty() },
            InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED,
            hintText = "Ej. 19.4326",
        )
        val lon = field(
            panel, "Longitud del centro", longitudeText.ifBlank { lastLon?.toString().orEmpty() },
            InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED,
            hintText = "Ej. -99.1332",
        )
        val locationStatus = text(
            if (latitudeText.isNotBlank() || lastLat != null) "Puedes ajustar las coordenadas o volver a tomar la ubicación."
            else "Este teléfono aún no tiene una ubicación guardada.",
            13, color = muted,
        )
        panel.addView(button("Usar la ubicación de este teléfono", false) {
            val currentLat = store.prefs.getString("latitude", "").orEmpty().toDoubleOrNull()
            val currentLon = store.prefs.getString("longitude", "").orEmpty().toDoubleOrNull()
            if (currentLat != null && currentLon != null) {
                lat.setText(currentLat.toString())
                lon.setText(currentLon.toString())
                locationStatus.text = "Ubicación aplicada. Ajusta el radio según el tamaño del lugar."
            } else {
                requestLocation()
                locationStatus.text = "Buscando la ubicación… vuelve a pulsar en unos segundos."
            }
        })
        panel.addView(locationStatus)
        val radius = field(
            panel, "Radio de la zona (metros)", existing?.takeIf { it.has("radius") }?.optInt("radius")?.toString() ?: "150",
            InputType.TYPE_CLASS_NUMBER, hintText = "Entre 25 y 20000",
            helper = "150 m cubre una casa; 300 m, una cuadra. La zona se evalúa con ubicaciones válidas.",
        )
        val enabled = tz.switchRow(
            panel,
            "Seguimiento activo",
            "Si lo desactivas, la zona queda guardada pero no genera avisos.",
            value = existing?.optBoolean("enabled", true) ?: true,
            action = {},
        )
        val share = tz.switchRow(
            panel,
            if (isCaregiver) "Mantener visible para la persona" else "Compartir con cuidador/a",
            if (isCaregiver) "La persona verá esta zona en su propia lista y podrá editarla."
            else "El cuidador vinculado podrá consultar y administrar esta zona.",
            value = existing?.optBoolean("sharedWithCaregiver", isCaregiver) ?: isCaregiver,
            action = {},
        )
        val dialog = AlertDialog.Builder(this).setTitle(if (existing == null) "Nueva zona" else "Editar zona")
            .setView(ScrollView(this).apply { addView(panel) })
            .setNegativeButton("Cancelar", null).setPositiveButton("Guardar", null).create()
        dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val a = lat.text.toString().toDoubleOrNull()
            val o = lon.text.toString().toDoubleOrNull()
            val r = radius.text.toString().toDoubleOrNull()
            var valid = true
            if (name.text.isBlank()) { name.error = "Escribe un nombre para la zona."; valid = false }
            if (a == null || a !in -90.0..90.0) { lat.error = "Latitud entre -90 y 90."; valid = false }
            if (o == null || o !in -180.0..180.0) { lon.error = "Longitud entre -180 y 180."; valid = false }
            if (r == null || r !in 25.0..20_000.0) { radius.error = "Radio entre 25 y 20000 metros."; valid = false }
            if (!valid) { tz.announce(name, "Revisa los campos marcados."); return@setOnClickListener }
            val item = JSONObject().put("id", existing?.optString("id").orEmpty()).put("name", name.text.toString().trim().take(100))
                .put("latitude", a).put("longitude", o).put("radius", r!!.toInt()).put("enabled", enabled.isChecked)
                .put("sharedWithCaregiver", share.isChecked)
                .put("ownerUserId", existing?.optString("ownerUserId")?.takeIf { it.isNotBlank() }
                    ?: formOwner)
            if (!persistCare("zone", item)) return@setOnClickListener
            SyncScheduler.enqueue(applicationContext, immediate = true)
            dialog.dismiss()
            toast("Zona guardada en este teléfono.")
            render()
        } }
        dialog.show()
    }

    private fun createRoutine() {
        val formOwner = careFeatures.currentOwner() ?: return
        val panel = column().apply { setPadding(dp(2), dp(4), dp(2), dp(4)) }
        panel.addView(text("Una rutina es un recordatorio descriptivo. TzindaApp no marca actividades como realizadas automáticamente.", 14, color = muted))
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
    private fun refreshCaregiverSharedData(force: Boolean = false, renderWhenDone: Boolean = false) {
        val requestStore = store
        val access = CaregiverAccess(requestStore)
        val scope = access.snapshot() ?: run {
            if (sessions.read() == null) caregiverSharedStatusView?.text = "Sesión no disponible: inicia sesión para actualizar los datos compartidos."
            return
        }
        val requestBackend = SupabaseBackend(sessions, expectedUserId = scope.actor)
        val now = System.currentTimeMillis()
        if (caregiverRefreshScope == scope && (caregiverRefreshInFlight || !force && now - caregiverRefreshAt < 10_000L)) return
        caregiverRefreshScope = scope
        val requestId = ++caregiverRefreshRequest
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
                if (requestId != caregiverRefreshRequest) return@runOnUiThread
                caregiverRefreshInFlight = false
                if (store !== requestStore || !access.isCurrent(scope)) return@runOnUiThread
                result.onSuccess { data ->
                    if (!access.write(scope) {
                        putString("caregiver_shared_name", data.optString("name"))
                        putString("caregiver_shared_devices", data.optJSONArray("devices")?.toString() ?: "[]")
                        putString("caregiver_shared_events", data.optJSONArray("events")?.toString() ?: "[]")
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
        val mapButton = button("Abrir en el mapa", false) {} .also { it.isEnabled = false; locationCard.addView(it) }
        locationCard.addView(button("Actualizar datos compartidos", false) { refreshCaregiverSharedData(force = true, renderWhenDone = true) })
        locationCard.addView(text("Las coordenadas son aproximadas y dependen de la señal del teléfono de la persona.", 12, color = muted))

        fun applyLocation() {
            val row = runCatching { JSONObject(store.prefs.getString("caregiver_shared_location", "{}").orEmpty()) }.getOrDefault(JSONObject())
            val capturedAt = isoMillis(row.optString("captured_at"))
            val latitude = row.optDouble("latitude", Double.NaN)
            val longitude = row.optDouble("longitude", Double.NaN)
            val hasCoordinates = latitude.isFinite() && longitude.isFinite() && capturedAt > 0L
            val ageMs = if (hasCoordinates) (System.currentTimeMillis() - capturedAt).coerceAtLeast(0L) else Long.MAX_VALUE
            val live = hasCoordinates && ageMs <= 90_000L
            statusView.text = if (!hasCoordinates) "Sin datos" else if (live) "●  EN VIVO" else "Última ubicación"
            statusView.setTextColor(if (live) accent else muted)
            coordinatesView.text = if (hasCoordinates) "%.5f, %.5f".format(Locale.US, latitude, longitude) else "—"
            freshnessView.text = when {
                !hasCoordinates -> when {
                    store.prefs.getString("caregiver_link_id", "").orEmpty().isBlank() ->
                        "Vincula a la persona para recibir su ubicación."
                    sessions.read() == null ->
                        "Sesión vencida: inicia sesión para actualizar la ubicación."
                    store.prefs.getLong("caregiver_shared_fetched_at", 0L) == 0L ->
                        "Aún no se descargaron datos. Usa «Actualizar datos compartidos»."
                    else ->
                        "La persona aún no comparte su ubicación. Necesita el monitoreo activo y el permiso de ubicación."
                }
                ageMs < 60_000L -> "Actualizada hace menos de un minuto"
                ageMs < 3_600_000L -> "Actualizada hace ${ageMs / 60_000L} min"
                else -> "Actualizada hace ${ageMs / 3_600_000L} h"
            }
            mapButton.isEnabled = hasCoordinates
            mapButton.text = if (hasCoordinates) "Abrir en el mapa  →" else "Sin ubicación para abrir"
            mapButton.setOnClickListener { if (hasCoordinates) openMap(latitude, longitude) }
        }
        caregiverSharedLocationView = statusView
        caregiverLocationCoordinatesView = coordinatesView
        caregiverLocationFreshnessView = freshnessView
        caregiverMapButton = mapButton
        caregiverLocationUpdater = { applyLocation() }
        applyLocation()
    }

    /** Tries a native map app first and falls back to a web map, so the button works on any phone. */
    private fun openMap(latitude: Double, longitude: Double) {
        val native = Uri.parse("geo:$latitude,$longitude?q=$latitude,$longitude(TzindaApp)")
        val web = Uri.parse("https://www.google.com/maps/search/?api=1&query=$latitude,$longitude")
        val opened = runCatching { startActivity(Intent(Intent.ACTION_VIEW, native)) }.isSuccess ||
            runCatching { startActivity(Intent(Intent.ACTION_VIEW, web)) }.isSuccess
        if (!opened) toast("No hay una aplicación de mapas o un navegador disponible.", true)
    }
    private fun caregiverActivity(body: LinearLayout) {
        refreshCaregiverSharedData()
        val linked = store.prefs.getString("caregiver_link_id", "").orEmpty().isNotBlank()
        if (!linked) {
            statusBanner(
                body,
                TzindaCopy("Sin vínculo activo", "Vincula a la persona protegida para consultar sus alertas compartidas.", TzindaTone.NEUTRAL),
                "Vincular persona",
            ) { selectTab(2) }
            return
        }
        val person = store.prefs.getString("caregiver_shared_name", "").orEmpty().ifBlank { "Persona protegida" }
        val events = sharedEvents()
        val pending = pendingSharedAlerts(events)
        val linkCopy = TzindaStatusCopy.link(true, person, store.prefs.getLong("caregiver_shared_fetched_at", 0L))
        statusBanner(body, linkCopy)
        if (pending.isNotEmpty()) {
            statusBanner(
                body,
                TzindaCopy("${TzindaText.count(pending.size, "alerta")} sin confirmar", "Revisa el evento y registra el seguimiento que corresponde.", TzindaTone.CRITICAL),
            )
        }
        body.addView(button("Actualizar datos compartidos", false) {
            refreshCaregiverSharedData(force = true, renderWhenDone = true)
        })
        tz.section(body, "Historial de alertas y actividad", TzindaText.count(events.size, "evento compartido", "eventos compartidos"))
        if (events.isEmpty()) {
            tz.emptyState(body, "Sin eventos compartidos", "La persona aún no ha compartido detecciones o alertas. No se muestran eventos de ejemplo.")
            return
        }
        val latestFollowups = sharedAlertFollowups()
            .groupBy { it.optString("fall_event_id") }
            .mapValues { (_, rows) -> rows.maxByOrNull { it.optString("created_at") } }
        var lastDay = ""
        events.forEach { event ->
            val day = dayLabel(isoMillis(event.optString("occurred_at")))
            if (day != lastDay) {
                lastDay = day
                body.addView(tz.eyebrow(day).apply { setPadding(0, dp(12), 0, dp(2)) })
            }
            val followup = latestFollowups[event.optString("id")]
            val prepared = isPendingSharedAlert(event, latestFollowups)
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
    }
    private fun caregiverPersonScreen(body: LinearLayout) {
        refreshCaregiverSharedData()
        val remoteLinked = store.prefs.getString("caregiver_link_id", "").orEmpty().isNotBlank()
        val person = store.prefs.getString("caregiver_shared_name", "").orEmpty().ifBlank { "Persona protegida" }
        heading(body, "VINCULACIÓN", "Persona vinculada", "Administra el vínculo y consulta solo los datos autorizados.")
        val status = card(body)
        status.addView(statusPill(TzindaStatusCopy.link(
            remoteLinked,
            person,
            store.prefs.getLong("caregiver_shared_fetched_at", 0L),
        )))
        status.addView(text(if (remoteLinked) "La persona conserva el control de sus medicamentos, cámara y ubicación. Puedes consultar datos compartidos y registrar sus tomas; no puedes editar medicamentos ni horarios." else "Aún no hay una persona conectada a este teléfono.", 14, color = muted).apply { setPadding(0, dp(8), 0, 0) })

        val connect = card(body)
        connect.addView(text("Vincular una persona", 20, true))
        connect.addView(text("Escanea el QR o escribe el código de 8 caracteres generado en su teléfono.", 14, color = muted))
        connect.addView(button("Escanear QR") { scanPairingQr("caregiver") })
        val code = field(connect, "Código de vinculación", "", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS, "caregiver_invite_code")
        code.filters = arrayOf(android.text.InputFilter.LengthFilter(9))
        formatPairingCode(code)
        val actionStatus = text("Código de un solo uso.", 14, color = muted).also { connect.addView(it) }
        connect.addView(button("Continuar") {
            val invite = code.text.toString().filter(Char::isLetterOrDigit)
            if (invite.length != 8) actionStatus.text = "Escribe los 8 caracteres del código."
            else showPairingConfirmation("caregiver", invite)
        })
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
        body.addView(button("Cambiar modo de este teléfono", false) { tab = ROLE_TAB; render() })
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

    private fun deviceLinkScreen(body: LinearLayout) {
        refreshRemoteDevicesIfStale()
        cameraRegistryRenderedRevision = store.prefs.getLong("camera_registry_revision", 0L)
        heading(body, "CONECTAR DISPOSITIVOS", "Dispositivos y enlaces", "Gestiona la pulsera, las cámaras y la vinculación con tu red de apoyo.")
        if (isCaregiver) {
            refreshCaregiverSharedData()
            val remote = store.prefs.getString("caregiver_link_id", "").orEmpty().isNotBlank()
            val person = store.prefs.getString("caregiver_shared_name", "").orEmpty().ifBlank { "Persona protegida" }
            val link = card(body)
            link.addView(statusPill(TzindaStatusCopy.link(remote, person, store.prefs.getLong("caregiver_shared_fetched_at", 0L))))
            link.addView(text(if (remote) "Consulta los equipos registrados por la persona protegida. El estado de cada equipo se muestra por separado del vínculo." else "Escanea el código de la persona protegida para acompañarla.", 14, color = muted).apply { setPadding(0, dp(8), 0, 0) })
            link.addView(button(if (remote) "Administrar vínculo" else "Vincular persona", false) { selectTab(2) })
            if (remote) {
                val devices = sharedDevices()
                tz.section(body, "Equipos de $person", TzindaText.count(devices.size, "registrado", "registrados"))
                if (devices.isEmpty()) {
                    tz.emptyState(body, "Sin equipos registrados", "La cuenta vinculada aún no tiene una pulsera o teléfono registrado.")
                } else devices.forEach { device ->
                    val copy = sharedDeviceCopy(device)
                    val last = deviceLastSignal(device)
                    val item = card(body)
                    item.addView(text(device.optString("name").ifBlank { "Dispositivo" }, 18, true))
                    item.addView(text(device.optString("device_type").replace('_', ' ').replaceFirstChar { it.uppercase() } + if (last > 0) " · Última señal ${TzindaStatusCopy.freshness(System.currentTimeMillis() - last)}" else " · Sin señal registrada", 13, color = muted))
                    item.addView(statusPill(copy))
                }
                val cameras = devices.filter { it.optString("device_type") == "camera" }
                if (cameras.isNotEmpty()) {
                    tz.section(body, "Cámaras de $person", TzindaText.count(cameras.size, "cámara", "cámaras"))
                    cameras.forEachIndexed { index, camera ->
                        val item = card(body)
                        item.addView(text(camera.optString("name").ifBlank { "Cámara" }, 18, true))
                        item.addView(statusPill(sharedDeviceCopy(camera)))
                        item.addView(text("Los eventos de esta cámara aparecen en el historial compartido.", 13, color = muted))
                        if (index == 0) item.addView(button("Ver eventos de cámara", false) {
                            activitySection = 1; selectTab(1)
                        })
                    }
                }
                body.addView(button("Actualizar datos de la persona", false) { refreshCaregiverSharedData(force = true, renderWhenDone = true) })
                body.addView(button("Abrir centro de supervisión", false) { openSupervisionCenter() })
            }
            val limits = card(body)
            limits.addView(text("Qué puedes administrar", 18, true))
            limits.addView(text("Puedes cambiar el estado de las alertas y crear, editar o pausar las zonas seguras que la persona comparte. La pulsera, la vinculación de cámaras y los medicamentos siguen bajo el control de la persona protegida.", 13, color = muted))
            return
        }
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
        body.addView(text("La pulsera y las cámaras pueden estar sin conexión. TzindaApp mostrará “sin datos recientes” en lugar de asumir que están activas.", 13, color = muted).apply {
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

        val arduinoUrl = field(parent, "Dirección Wi-Fi del UNO Q", store.arduinoBaseUrl(),
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI, "arduino_base_url")
        parent.addView(button("Guardar dirección Wi-Fi", false) {
            runCatching { store.saveArduinoBaseUrl(arduinoUrl.text.toString()) }
                .onSuccess { toast("Dirección guardada para la próxima conexión.") }
                .onFailure { toast(it.message ?: "No se pudo guardar la dirección.", true) }
        })
        val testStatus = text("", 14, color = muted)
        parent.addView(testStatus)
        parent.addView(button("Probar conexión Wi-Fi", false) {
            testStatus.text = "Conectando con la pulsera…"
            io.execute {
                val result = runCatching {
                    val connection = (URL("${store.arduinoBaseUrl().trimEnd('/')}/api/v1/sensor").openConnection() as HttpURLConnection).apply {
                        connectTimeout = 3_000; readTimeout = 3_000; requestMethod = "GET"
                    }
                    val code = connection.responseCode
                    val response = (if (code in 200..299) connection.inputStream else connection.errorStream)
                        ?.bufferedReader()?.use { it.readText() }.orEmpty()
                    connection.disconnect()
                    if (code !in 200..299) throw IllegalStateException("La pulsera respondió HTTP $code.")
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
        return store.events().let { events ->
            (0 until events.length()).mapNotNull { events.optJSONObject(it) }
                .filter { it.optLong("time") >= start && it.optString("message").contains("caída", ignoreCase = true) }
                .distinctBy { it.optString("incident").ifBlank { it.optLong("time").toString() } }
                .size
        }
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
        val last = store.events().optJSONObject(0)
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
        if (isCaregiver) refreshCaregiverSharedData()
        heading(
            body,
            if (isCaregiver) "SEGUIMIENTO" else "SUPERVISIÓN",
            if (isCaregiver) "Actividad de la persona" else "Historial y registros",
            if (isCaregiver) "Consulta los datos compartidos por la persona que acompañas."
            else "Revisa tus signos vitales o consulta por separado el historial de avisos.",
        )
        tz.segmented(body, listOf("Signos vitales", "Notificaciones"), activitySection) {
            activitySection = it; render()
        }
        if (activitySection == 0) {
            vitalsUnavailableCard(body, caregiver = isCaregiver)
            if (isCaregiver) {
                addSharedLocationCard(body)
                addSharedPhoneMotionCard(body)
                addCaregiverSyncControls(body)
            } else {
                body.addView(text("Las lecturas se guardan en este teléfono y se comparten con tu cuidador cuando hay Internet. Solo se muestran lecturas válidas recibidas.", 14, color = muted).apply {
                    setPadding(dp(4), dp(10), dp(4), dp(8))
                })
                refreshPatientVitals()
            }
            return
        }
        if (isCaregiver) { caregiverActivity(body); return }

        val progress = card(body)
        progress.addView(text("EMERGENCIA EN PROCESO", 12, true, tz.critical))
        progress.addView(text("Avisos en curso", 21, true, ink))
        emergencyProgressStatus = text("Solicitando SMS y llamadas a tus contactos.", 15, color = muted).also { progress.addView(it) }
        progress.addView(text("El resultado depende de la SIM, la señal y la disponibilidad del teléfono. La app no puede confirmar que otra persona haya respondido.", 13, color = muted))
        emergencyProgressCard = progress
        progress.visibility = if (store.prefs.getString("dispatch_id", "").orEmpty().isNotBlank()) View.VISIBLE else View.GONE

        val records = store.events().let { events -> (0 until events.length()).mapNotNull { events.optJSONObject(it) } }
        val summary = card(body)
        val row = tz.twoColumns(summary)
        tz.metricTile(row, "Eventos guardados", records.size.toString(), "", "En este teléfono", TzindaDesign.Tone.INFO)
        val state = tz.metricTile(
            row,
            "Monitoreo",
            if (GuardianMonitorService.monitoring) "Activo" else "En pausa",
            "",
            if (GuardianMonitorService.monitoring) "Detectando caídas" else "Sin detección",
            if (GuardianMonitorService.monitoring) TzindaDesign.Tone.SUCCESS else TzindaDesign.Tone.NEUTRAL,
        )
        (state.layoutParams as LinearLayout.LayoutParams).leftMargin = dp(8)

        val filtersRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(2), 0, dp(2))
        }
        listOf("Todos", "Alertas", "Cámara", "Pulsera").forEachIndexed { index, label ->
            tz.filterChip(filtersRow, label, activityFilter == index) { activityFilter = index; render() }
        }
        body.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(filtersRow)
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })

        val filtered = records.filter { event ->
            val message = event.optString("message").lowercase(Locale.ROOT)
            when (activityFilter) {
                1 -> listOf("caída", "alerta", "sms", "llamada", "cancelad").any { message.contains(it) }
                2 -> listOf("cámara ia", "detector con cámara", "modelo local", "cámara").any { message.contains(it) }
                3 -> listOf("arduino", "pulsera", "sensor", "servicio", "permiso").any { message.contains(it) }
                else -> true
            }
        }
        tz.section(body, "Registro detallado", TzindaText.count(filtered.size, "evento"))
        if (filtered.isEmpty()) {
            tz.emptyState(
                body,
                if (records.isEmpty()) "Todo empieza aquí" else "No hay eventos en este filtro",
                if (records.isEmpty()) "Cuando actives el monitoreo o envíes una alerta, aquí aparecerá el registro del proceso." else "Prueba otra categoría para ver más actividad.",
                if (records.isNotEmpty()) "Ver todos" else null,
                action = { activityFilter = 0; render() },
            )
        }
        var lastDay = ""
        filtered.forEach { event ->
            val day = dayLabel(event.optLong("time"))
            if (day != lastDay) {
                lastDay = day
                body.addView(tz.eyebrow(day).apply { setPadding(0, dp(12), 0, dp(2)) })
            }
            val message = event.optString("message", "Evento de TzindaApp")
            val date = Date(event.optLong("time"))
            val alert = isPendingLocalAlert(event, records)
            val cancelled = message.startsWith("Alerta cancelada")
            val item = card(body)
            item.addView(text(SimpleDateFormat("EEEE, d MMM · HH:mm", Locale("es", "MX")).format(date), 13, true, accent))
            item.addView(text(message, 17, true))
            item.addView(statusPill(TzindaCopy(
                when {
                    alert -> "Requiere atención"
                    cancelled -> "Alerta cancelada"
                    else -> "Registro informativo"
                },
                when {
                    alert -> "Revisa si el aviso fue atendido o cancelado."
                    cancelled -> "La persona confirmó que estaba bien; no se enviaron avisos nuevos."
                    else -> "Guardado en este teléfono."
                },
                when {
                    alert -> TzindaTone.WARNING
                    cancelled -> TzindaTone.POSITIVE
                    else -> TzindaTone.NEUTRAL
                },
            )))
        }
        body.addView(text("Los eventos locales describen lo que ocurrió en este teléfono. La recepción de un SMS o una llamada no se confirma desde la app.", 13, color = muted).apply {
            setPadding(dp(4), dp(12), dp(4), dp(4))
        })
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
        val cameraEvents = store.events().let { rows ->
            (0 until rows.length()).mapNotNull { rows.optJSONObject(it) }.filter { row ->
                row.optString("message").contains("cámara", ignoreCase = true)
            }
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
    }
    private fun contacts(body: LinearLayout) {
        if (isCaregiver) { caregiverPersonScreen(body); return }
        val recipients = store.recipients()
        val emergencyKey = PhoneNumbers.normalize(store.profile().text("emergencyContactPhone"))?.let { PhoneNumbers.key(it) }
        heading(body, "TU RED DE APOYO", "Contactos de confianza", "Estas personas reciben los SMS y las llamadas de alerta.")
        val protocol = card(body)
        protocol.addView(statusPill(TzindaCopy("Protocolo de 20 segundos", "Puedes cancelar con “Estoy bien” antes de que se avise a tu red.", TzindaTone.INFO)))
        protocol.addView(text("Si se confirma una posible caída, tienes 20 segundos para cancelar. Después se solicitan los SMS y comienzan las llamadas a tu red de apoyo.", 14, color = muted).apply { setPadding(0, dp(8), 0, 0) })
        protocol.addView(text("Los avisos dependen de la SIM, permisos, cobertura y disponibilidad del teléfono. TzindaApp no puede confirmar que otra persona los haya leído.", 13, color = muted))

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
                .setMessage("La prueba usa tu SIM real: si no pulsas Estoy bien antes de que termine, TzindaApp solicitará SMS y llamadas a tus contactos.")
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Continuar") { _, _ -> prepareAlert(GuardianMonitorService.ACTION_TEST) }.show()
        })

        val editor = card(body)
        editor.addView(text(if (editingContacts) "Editar lista de contactos" else "Administrar contactos", 20, true))
        editor.addView(text("Hasta 5 contactos. El contacto principal se llama primero en caso de alerta.", 14, color = muted))
        if (!editingContacts) {
            editor.addView(button(if (recipients.isEmpty()) "Agregar contactos" else "Editar contactos") { editingContacts = true; render() })
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
        heading(
            body,
            when { isCaregiver -> "CUIDADOR O FAMILIAR"; isCameraRole -> "DISPOSITIVO DE CÁMARA"; else -> "PERSONA PROTEGIDA" },
            "Ajustes",
            "Accesibilidad, privacidad, dispositivos y cuenta en un solo lugar.",
        )
        val accountProfile = store.profile()
        val accountName = accountProfile.text("fullName").trim()
        val accountRole = TzindaNavigationPolicy.roleLabel(TzindaNavigationPolicy.role(store.role()))
        val profileSummary = card(body)
        tz.actionRow(
            profileSummary,
            accountName.ifBlank { "Mi perfil" },
            accountRole,
            leadingText = initials(accountName.ifBlank { "Tz" }),
            showDivider = false,
        ) { openProfile() }
        val accessibility = card(body)
        tz.cardHeader(accessibility, R.drawable.ic_profile, "Accesibilidad", "Texto, controles y densidad", TzindaDesign.Tone.INFO)
        accessibility.addView(text("Aumenta texto y controles, y reduce la densidad de las pantallas. Se guarda en este teléfono.", 14, color = muted))
        tz.switchRow(
            accessibility,
            "Modo de asistencia simplificada",
            "Texto y botones más grandes; menos elementos por pantalla.",
            simpleMode,
            action = { value ->
                store.prefs.edit().putBoolean("simple_accessibility", value).apply()
                render()
            },
        )
        accessibility.addView(statusPill(TzindaCopy(
            if (simpleMode) "Modo simplificado activo" else "Tamaño estándar",
            if (simpleMode) "Se mantiene al cerrar y volver a abrir la app." else "Puedes activarlo cuando lo necesites.",
            if (simpleMode) TzindaTone.POSITIVE else TzindaTone.NEUTRAL,
        )))

        val roleCard = card(body)
        tz.cardHeader(roleCard, R.drawable.ic_shield, "Rol de esta app", "Navegación adaptada al rol", TzindaDesign.Tone.BRAND)
        roleCard.addView(statusPill(TzindaCopy(
            when { isCaregiver -> "Cuidador o familiar"; isCameraRole -> "Cámara independiente"; else -> "Persona protegida" },
            "La navegación y las funciones visibles dependen de este rol.",
            TzindaTone.POSITIVE,
        )))
        roleCard.addView(button("Cambiar modo de este teléfono", false) {
            AlertDialog.Builder(this).setTitle("Cambiar rol")
                .setMessage("¿Deseas cambiar el rol de este dispositivo? La navegación y las funciones de la pantalla principal se ajustarán al nuevo rol.")
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Cambiar rol") { _, _ -> tab = ROLE_TAB; render() }.show()
        })

        if (isCaregiver) {
            val linked = store.prefs.getString("caregiver_link_id", "").orEmpty().isNotBlank()
            val linkCard = card(body)
            tz.cardHeader(linkCard, R.drawable.ic_supervision, "Persona vinculada", "Solo lectura y datos autorizados", TzindaDesign.Tone.SUCCESS)
            linkCard.addView(statusPill(TzindaStatusCopy.link(
                linked,
                store.prefs.getString("caregiver_shared_name", "").orEmpty(),
                store.prefs.getLong("caregiver_shared_fetched_at", 0L),
            )))
            linkCard.addView(text("Puedes consultar lo que la persona comparta y registrar sus tomas. Los medicamentos y horarios solo los edita su propietario.", 14, color = muted).apply { setPadding(0, dp(8), 0, 0) })
            linkCard.addView(button(if (linked) "Administrar vínculo" else "Vincular persona", false) { selectTab(2) })
            linkCard.addView(button("Abrir centro de supervisión", false) { openSupervisionCenter() })
        } else if (isCameraRole) {
            val linked = store.prefs.getString("paired_camera_device_id", "").orEmpty().isNotBlank()
            val cameraCard = card(body)
            tz.cardHeader(cameraCard, R.drawable.ic_camera, "Cámara de este teléfono", "Activación bajo demanda", TzindaDesign.Tone.BRAND)
            cameraCard.addView(statusPill(TzindaStatusCopy.cameraLink(linked, store.prefs.getString("paired_camera_name", "").orEmpty())))
            cameraCard.addView(button(if (linked) "Abrir cámara" else "Vincular cámara", false) { if (linked) openCameraScreen() else selectTab(2) })
            cameraCard.addView(button("Ver eventos de cámara", false) { activitySection = 1; selectTab(1) })
        }

        val deviceAccess = card(body)
        tz.cardHeader(deviceAccess, R.drawable.ic_bluetooth, "Dispositivos y vinculaciones", "Pulsera, cámara y vínculos", TzindaDesign.Tone.BRAND)
        deviceAccess.addView(text(if (isCaregiver) "Consulta los equipos de la persona vinculada. No puedes emparejar su pulsera." else "Administra conexiones, permisos y vínculos en un solo lugar.", 14, color = muted))
        deviceAccess.addView(button("Abrir conexiones", false) { openDeviceLinkScreen() })

        val account = card(body)
        val session = sessions.read()
        val syncState = store.syncState()
        val syncCopy = TzindaStatusCopy.sync(syncState.pending, syncState.failed, hasValidatedInternet(), session != null)
        tz.cardHeader(account, R.drawable.ic_lock, "Cuenta y sincronización", null, TzindaDesign.Tone.INFO)
        account.addView(statusPill(syncCopy))
        account.addView(text(syncCopy.detail, 14, color = muted).apply { setPadding(0, dp(8), 0, 0) })
        if (session != null) {
            account.addView(text("Sesión: ${session.email}", 14, color = muted))
            account.addView(text("Modo de este teléfono: ${TzindaNavigationPolicy.roleLabel(TzindaNavigationPolicy.role(store.role()))}", 14, color = muted))
            account.addView(text("El acceso a otra persona depende de un vínculo activo y de lo que comparta, no del modo elegido.", 13, color = muted))
            val synced = store.prefs.getLong("last_sync_success_at", 0L).takeIf { it > 0L }
                ?.let { SimpleDateFormat("d MMM, HH:mm", Locale("es", "MX")).format(Date(it)) } ?: "Aún no sincronizado"
            account.addView(text("Última sincronización correcta: $synced", 13, color = muted))
            account.addView(button("Sincronizar ahora", false) {
                store.retryFailedSyncItems()
                MedicationDatabase.getInstance(applicationContext).retryFailed()
                store.prefs.edit().putInt("sync_global_failures", 0).apply()
                SyncScheduler.enqueue(applicationContext, immediate = true); render()
            })
            if (!isCaregiver && !isCameraRole) account.addView(button("Crear QR o código para cuidador/a") { showCreateCaregiverInvite(account) })
            account.addView(button("Cerrar sesión", false) {
                AlertDialog.Builder(this).setTitle("Cerrar sesión")
                    .setMessage("¿Deseas cerrar la sesión activa? La app continuará funcionando en modo local en este teléfono.")
                    .setNegativeButton("Cancelar", null)
                    .setPositiveButton("Cerrar sesión") { _, _ ->
                        io.execute {
                            runCatching { backend.signOut() }
                            runOnUiThread {
                                caregiverLocationRealtime?.stop(); caregiverLocationRealtime = null; caregiverLocationRealtimeDevice = ""
                                caregiverHealthRealtime?.stop(); caregiverHealthRealtime = null; caregiverHealthRealtimeKey = ""
                                store.clearRemoteDerivedCaches()
                                store.prefs.edit().putBoolean("local_only_mode", true).apply(); render()
                            }
                        }
                    }.show()
            })
            account.addView(button("Eliminar mi cuenta y datos remotos", false) {
                AlertDialog.Builder(this).setTitle("Eliminar cuenta de Supabase")
                    .setMessage("Se eliminará la cuenta y los datos remotos asociados. Los contactos y registros locales de este teléfono se conservarán para que las alertas locales sigan disponibles. Esta acción no se puede deshacer.")
                    .setNegativeButton("Cancelar", null).setPositiveButton("Eliminar cuenta") { _, _ ->
                        io.execute {
                            val result = runCatching { backend.deleteAccount() }
                            runOnUiThread {
                                result.onSuccess {
                                    caregiverLocationRealtime?.stop(); caregiverLocationRealtime = null; caregiverLocationRealtimeDevice = ""
                                    caregiverHealthRealtime?.stop(); caregiverHealthRealtime = null; caregiverHealthRealtimeKey = ""
                                    store.clearRemoteDerivedCaches()
                                    store.prefs.edit().putBoolean("local_only_mode", true).apply()
                                    toast("Cuenta eliminada. La app sigue en modo local."); render()
                                }.onFailure { toast(it.message ?: "No se pudo eliminar la cuenta.", true) }
                            }
                        }
                    }.show()
            })
        } else {
            account.addView(text(if (store.prefs.getBoolean("local_only_mode", false)) "Modo local. Los datos permanecen en este teléfono hasta que inicies sesión." else "Sin sesión. Inicia sesión para sincronizar con Supabase.", 14, color = muted))
            account.addView(button("Iniciar sesión o crear cuenta") { authMode = AuthMode.LOGIN; render() })
        }

        val preferences = card(body)
        tz.cardHeader(preferences, R.drawable.ic_settings, "Apariencia y privacidad", null, TzindaDesign.Tone.NEUTRAL)
        tz.switchRow(preferences, "Modo oscuro", "Paleta oscura con contraste legible.", dark, action = { value ->
            // A tap can be delivered twice around Activity.recreate() (the window is rebuilt
            // while the gesture is still being processed). Ignore echoes and defer the
            // relaunch so the preference always ends up matching what the user chose.
            val now = android.os.SystemClock.elapsedRealtime()
            if (now < themeToggleGuardUntil) return@switchRow
            if (value == store.prefs.getBoolean("dark_mode", false)) return@switchRow
            themeToggleGuardUntil = now + 800L
            store.prefs.edit().putBoolean("dark_mode", value).commit(); saveDrafts()
            handler.postDelayed({ if (!isFinishing && !isDestroyed) recreate() }, 250L)
        })
        preferences.addView(text("El modo oscuro y el modo simplificado pueden usarse al mismo tiempo.", 13, color = muted))
        preferences.addView(statusPill(TzindaCopy("Tipografía del sistema escalable", "Controles de al menos 48 dp y contraste alto.", TzindaTone.INFO)))

        if (!isCaregiver && !isCameraRole) {
            val location = card(body)
            tz.cardHeader(location, R.drawable.ic_location, "Ubicación en tiempo real", null, TzindaDesign.Tone.BRAND)
            location.addView(text("Con tu autorización, el teléfono actualiza la posición mientras el monitoreo está activo. La ubicación se adjunta a los avisos y solo se comparte con el vínculo autorizado.", 14, color = muted))
            tz.switchRow(
                location,
                "Compartir ubicación durante el monitoreo",
                "Puedes desactivarlo cuando quieras.",
                store.prefs.getBoolean("include_location", false),
                action = { value -> setLocationConsent(value) },
            )
            location.addView(text(store.locationText(), 14, color = muted))
            location.addView(button("Actualizar ubicación ahora", false) { requestLocation() })

            val localData = card(body)
            tz.cardHeader(localData, R.drawable.ic_heart, "Pulsera y datos del teléfono", null, TzindaDesign.Tone.SUCCESS)
            localData.addView(text("Lecturas locales: ${store.telemetry.summary().arduino}. Los valores solo aparecen cuando la pulsera envía una lectura válida.", 14, color = muted))
            addBraceletConnectionControls(localData, includeMethodOptions = true)

            val alerts = card(body)
            tz.cardHeader(alerts, R.drawable.ic_alert, "Detección y alertas", null, TzindaDesign.Tone.WARNING)
            alerts.addView(text("Después de detectar un movimiento compatible con una caída, la app inicia una cuenta regresiva de 20 segundos. Puedes cancelarla con “Estoy bien”. La detección es experimental y puede omitir caídas suaves o producir falsas alarmas.", 14, color = muted))
            alerts.addView(button("Probar cuenta regresiva", false) {
                AlertDialog.Builder(this).setTitle("Prueba de 20 segundos")
                    .setMessage("Si no cancelas la prueba, se solicitarán SMS reales y se intentarán llamadas a tus contactos.")
                    .setNegativeButton("Cancelar", null).setPositiveButton("Iniciar prueba") { _, _ -> prepareAlert(GuardianMonitorService.ACTION_TEST) }.show()
            })

            val sms = card(body)
            tz.cardHeader(sms, R.drawable.ic_phone, "SMS y llamadas", null, TzindaDesign.Tone.DANGER)
            sms.addView(text("Los SMS se envían por la SIM configurada. Las llamadas se realizan en orden de prioridad. TzindaApp no puede confirmar que el mensaje se haya leído.", 14, color = muted))
            sms.addView(button("Configurar SIM para SMS", false) { configureSim() })
            sms.addView(button("Permisos de Android", false) { openAppSettings() })
            if (Build.VERSION.SDK_INT >= 34) sms.addView(button("Permitir alertas a pantalla completa", false) {
                runCatching { startActivity(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:$packageName"))) }
                    .onFailure { openAppSettings() }
            })
            permissionCard(sms)
        }

        val dataPrivacy = card(body)
        tz.cardHeader(dataPrivacy, R.drawable.ic_lock, "Qué se guarda y qué se comparte", null, TzindaDesign.Tone.NEUTRAL)
        dataPrivacy.addView(text("Contactos, eventos, lecturas válidas y ubicación autorizada se sincronizan si inicias sesión. El domicilio, la fecha de nacimiento y el video no se sincronizan. Los medicamentos son privados hasta que los compartes. Los datos de salud son informativos y no constituyen diagnóstico.", 14, color = muted))
        dataPrivacy.addView(button("Leer aviso de privacidad y uso", false) {
            AlertDialog.Builder(this).setTitle("Privacidad y uso de TzindaApp")
                .setMessage("Supabase puede almacenar tu nombre y rol, contactos de confianza con teléfono, eventos de caída y estados técnicos de SMS/llamada, lecturas válidas de pulso/SpO₂ del UNO Q y la ubicación en vivo cuando la autorizas y el monitoreo está activo.\n\nLos medicamentos se guardan en este teléfono y se sincronizan si inicias sesión. Cada medicamento permanece privado hasta que actives “Compartir con mi cuidador/a”; solo una cuenta con vínculo activo podrá consultarlo y registrar sus tomas, sin editar medicamentos ni horarios. TzindaApp no recomienda ni modifica dosis. Si escaneas la caja o la receta, la foto se lee en este teléfono y no se guarda ni se envía.\n\nEl domicilio, fecha de nacimiento, video y muestras crudas de movimiento no se sincronizan. Las funciones locales siguen disponibles sin Internet. TzindaApp es experimental y no sustituye servicios de emergencia ni atención médica.\n\nPuedes cerrar sesión o solicitar la eliminación de la cuenta y los datos remotos desde Ajustes.")
                .setPositiveButton("Entendido", null).show()
        })

        val profile = card(body)
        tz.cardHeader(profile, R.drawable.ic_profile, "Mis datos", null, TzindaDesign.Tone.BRAND)
        profile.addView(text("Consulta o actualiza tu información personal y tu contacto de emergencia.", 14, color = muted))
        profile.addView(button("Abrir mi perfil", false) { openProfile() })
        body.addView(text("TzindaApp 2.1.7 · Android nativo", 13, color = muted).apply { setPadding(dp(4), dp(18), dp(4), dp(4)) })
    }
    /**
     * The camera opens as a full-screen viewfinder: the live image fills the window and the
     * controls float above it. The previous 16:9 card wasted most of a phone screen.
     */
    private fun cameraFullscreen(stage: FrameLayout) {
        stage.setBackgroundColor(Color.BLACK)
        val preview = PreviewView(this).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
            contentDescription = "Vista en vivo de la cámara"
        }
        cameraPreviewView = preview
        stage.addView(preview, FrameLayout.LayoutParams(-1, -1))
        val overlay = PoseOverlayView(this)
        cameraOverlayView = overlay
        stage.addView(overlay, FrameLayout.LayoutParams(-1, -1))
        cameraPlaceholder = text("La cámara está apagada\nToca “Iniciar cámara” para usarla", 15, true, Color.WHITE).apply {
            gravity = Gravity.CENTER
            setPadding(dp(20), dp(14), dp(20), dp(14))
            background = rounded(tz.colorWithAlpha(Color.BLACK, .58f), 18)
        }
        stage.addView(cameraPlaceholder, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))

        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val close = TextView(this).apply {
            text = "‹  Cerrar"
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, tz.textScale(15f))
            typeface = tz.boldTypeface()
            setTextColor(tz.royalBlue)
            gravity = Gravity.CENTER
            background = rounded(Color.WHITE, 24)
            minHeight = dp(48)
            setPadding(dp(16), dp(10), dp(16), dp(10))
            isClickable = true
            isFocusable = true
            setOnClickListener { selectTab(cameraReturnTab.coerceIn(0, 3)) }
            contentDescription = "Cerrar cámara y volver"
        }
        topRow.addView(close, LinearLayout.LayoutParams(-2, -2))
        topRow.addView(Space(this), LinearLayout.LayoutParams(0, 1, 1f))
        cameraStatusTitle = text("Cámara apagada", 13, true, Color.WHITE).apply {
            gravity = Gravity.CENTER
            background = rounded(tz.colorWithAlpha(Color.BLACK, .62f), 24)
            minHeight = dp(40)
            setPadding(dp(14), dp(8), dp(14), dp(8))
            contentDescription = "Estado de la cámara: apagada"
        }
        topRow.addView(cameraStatusTitle, LinearLayout.LayoutParams(-2, -2))
        stage.addView(topRow, FrameLayout.LayoutParams(-1, -2, Gravity.TOP).apply {
            leftMargin = dp(14); rightMargin = dp(14); topMargin = dp(14)
        })

        // Controls float over the lower part of the image so the viewfinder stays full-bleed.
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(16))
            background = rounded(tz.colorWithAlpha(tz.royalBlue, .90f), 22)
        }
        cameraStatusDetail = text(
            "Activa la cámara solo cuando quieras. La imagen se procesa en este teléfono.",
            14, color = Color.argb(226, 255, 255, 255),
        ).also { panel.addView(it) }
        cameraActionButton = button("Iniciar cámara") { toggleCameraDetection() }.apply {
            tag = "camera_ai_toggle"
            minHeight = dp(56)
        }.also { panel.addView(it, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) }) }
        panel.addView(text(
            "No se guarda ni se comparte el video. La detección puede equivocarse y no sustituye la pulsera ni los servicios de emergencia.",
            12, color = Color.argb(196, 255, 255, 255),
        ).apply { setPadding(0, dp(10), 0, 0) })
        if (isCameraRole) {
            val linked = store.prefs.getString("paired_camera_device_id", "").orEmpty().isNotBlank()
            panel.addView(button(if (linked) "Ver eventos de cámara" else "Vincular esta cámara", false) {
                if (linked) { activitySection = 1; selectTab(1) } else selectTab(2)
            }.apply {
                // Secondary action on the dark viewfinder needs explicit light text.
                setTextColor(Color.WHITE)
                background = tz.rounded(Color.TRANSPARENT, 16).apply { setStroke(dp(1), Color.argb(120, 255, 255, 255)) }
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        }
        stage.addView(panel, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply {
            leftMargin = dp(14); rightMargin = dp(14); bottomMargin = dp(18)
        })

        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)) {
            cameraStatusTitle?.text = "Cámara no disponible"
            cameraActionButton?.isEnabled = false
            cameraStatusDetail?.text = "Este dispositivo no tiene una cámara compatible. Puedes seguir usando la pulsera y los avisos locales."
        }
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
                cameraStatusDetail?.text = "Abre Ajustes de Android > Aplicaciones > TzindaApp > Permisos > Cámara."
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
        cameraPlaceholder?.visibility = View.GONE
        cameraActionButton?.text = "Detener cámara"
        cameraStatusDetail?.text = "Procesando la imagen en este teléfono. No se guarda ni se comparte."
        if (cameraController == null) {
            val preview = cameraPreviewView ?: return
            val overlay = cameraOverlayView ?: return
            cameraController = CameraFallController(
                activity = this,
                previewView = preview,
                overlay = overlay,
                onStatus = { title, _, running ->
                    cameraStatusTitle?.text = when {
                        title.contains("caída", ignoreCase = true) -> "Posible caída"
                        title.contains("prepar", ignoreCase = true) -> "Preparando cámara"
                        running -> "Cámara activa"
                        else -> "Cámara detenida"
                    }
                    cameraPlaceholder?.visibility = if (running) View.GONE else View.VISIBLE
                    cameraActionButton?.text = if (cameraRequested) "Detener cámara" else "Iniciar cámara"
                    cameraStatusTitle?.contentDescription = "Estado de la cámara: ${cameraStatusTitle?.text?.toString().orEmpty()}"
                    cameraStatusDetail?.text = when {
                        title.contains("caída", ignoreCase = true) -> "Se registró un evento para revisión. La cámara se detuvo."
                        running -> "Procesando la imagen en este teléfono. No se guarda ni se comparte."
                        else -> "La cámara se activa solo cuando la abres. La imagen se procesa en este teléfono y no se guarda ni se comparte."
                    }
                },
                onFall = {
                    if (!cameraFallHandled) {
                        cameraFallHandled = true
                        cameraRequested = false
                        cameraController?.stop()
                        cameraPlaceholder?.visibility = View.VISIBLE
                        cameraStatusTitle?.text = "Posible caída detectada"
                        cameraStatusDetail?.text = "La detección puede equivocarse. Revisa a la persona y mantén la pulsera puesta."
                        cameraActionButton?.text = "Iniciar cámara"
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
        cameraPlaceholder?.visibility = View.VISIBLE
        cameraActionButton?.text = "Iniciar cámara"
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
        cameraPreviewView = null
        cameraOverlayView = null
        cameraPlaceholder = null
        cameraStatusTitle = null
        cameraStatusDetail = null
        cameraActionButton = null
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
        heading(body, "INFORMACIÓN PERSONAL", "Mi perfil", "Estos datos se guardan en este teléfono. Domicilio y fecha de nacimiento no se sincronizan.")
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
        if (store.recipients().isEmpty()) { toast("Guarda al menos un contacto de confianza o de emergencia.", true); editingContacts = true; selectTab(2); return }
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
    private fun launchMedicationCamera(onResult: (MedicationLabelGuess?, String?) -> Unit) {
        val directory = File(cacheDir, "medication_scan").apply { mkdirs() }
        directory.listFiles()?.forEach { stale ->
            if (System.currentTimeMillis() - stale.lastModified() > 10 * 60_000L) stale.delete()
        }
        val file = File(directory, "scan-${System.currentTimeMillis()}.jpg")
        val uri = runCatching { FileProvider.getUriForFile(this, "$packageName.fileprovider", file) }.getOrNull()
        if (uri == null) {
            onResult(null, "No se pudo preparar la cámara. Elige una imagen de la galería.")
            return
        }
        medicationScanFile = file
        medicationScanCallback = onResult
        runCatching { medicationCameraLauncher.launch(uri) }.onFailure {
            medicationScanCallback = null
            medicationScanFile = null
            file.delete()
            onResult(null, "No hay una app de cámara disponible. Elige una imagen de la galería.")
        }
    }

    private fun readMedicationLabel(file: File, callback: ((MedicationLabelGuess?, String?) -> Unit)?) =
        readMedicationLabel(Uri.fromFile(file), callback)

    private fun readMedicationLabel(uri: Uri, callback: ((MedicationLabelGuess?, String?) -> Unit)?) {
        if (callback == null) return
        val useCloud = medicationScanCloud && NvidiaMedicationLabelReader.isConfigured
        medicationScanCloud = false
        val finish: (MedicationLabelGuess?, String?) -> Unit = { guess, error ->
            medicationScanFile?.delete()
            medicationScanFile = null
            callback(guess, error)
        }
        val readOnDevice = {
            toast("Leyendo la imagen…")
            MedicationLabelReader.read(this, uri) { guess, error -> finish(guess, error) }
        }
        if (!useCloud) {
            readOnDevice()
            return
        }
        toast("Leyendo con NVIDIA…")
        NvidiaMedicationLabelReader.read(this, uri) { guess, error ->
            if (guess != null) {
                finish(guess, null)
            } else {
                toast(error ?: "NVIDIA no respondió. Se leerá en el teléfono.", true)
                MedicationLabelReader.read(this, uri) { localGuess, localError ->
                    finish(localGuess, if (localGuess == null) localError ?: error else null)
                }
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_SMS_ONLY || requestCode == REQUEST_ALERT_SMS) {
            smsRequestInFlight = false
            // Android can return empty arrays when a request is interrupted. This is not a denial.
            if (grantResults.isNotEmpty() && permissions.contains(Manifest.permission.SEND_SMS)) {
                smsRequestedInSession = !has(Manifest.permission.SEND_SMS)
                runCatching { File(noBackupFilesDir, "sms-permission-requested").writeText(if (smsRequestedInSession) "requested" else "granted") }
            }
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
            REQUEST_MEDICATION_SCAN -> {
                val callback = medicationScanCallback
                medicationScanCallback = null
                if (callback != null) {
                    if (has(Manifest.permission.CAMERA)) launchMedicationCamera(callback)
                    else callback(null, "Sin permiso de cámara. Puedes elegir una imagen de la galería o escribir los datos a mano.")
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
            SmsPermissionPolicy.State.SETTINGS_REQUIRED -> "Android no permite mostrar otra solicitud. En los ajustes de TzindaApp abre Permisos > SMS y selecciona Permitir. Si SMS no aparece o está bloqueado, el instalador del dispositivo puede estar restringiéndolo. Para tus dispositivos de prueba puedes usar la instalación por USB incluida con esta versión."
            else -> "Para activar el envío automático necesitas autorizar SMS. Pulsa Permitir SMS en Inicio y elige Permitir en la solicitud de Android."
        }
        smsHelpDialog = AlertDialog.Builder(this).setTitle("Habilitar SMS").setMessage(message)
            .setNegativeButton("Cerrar", null).setPositiveButton("Abrir ajustes") { _, _ -> openAppSettings() }
            .setNeutralButton("Pruebas por USB") { _, _ ->
                AlertDialog.Builder(this).setTitle("Instalación en dispositivos de prueba")
                    .setMessage("En tu computadora, extrae el paquete Pruebas USB de TzindaApp. Conecta el teléfono, habilita Depuración USB y autoriza la conexión. Ejecuta Instalar-TzindaApp-Pruebas.ps1: instala el APK y autoriza únicamente SMS mediante las herramientas de Android. No se envían mensajes durante la instalación. Esto no elimina políticas de administración ni agrega telefonía a un dispositivo que no la tiene.")
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
        catch (_: RuntimeException) { toast("Abre Ajustes de Android > Aplicaciones > TzindaApp > Permisos.", true) }
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
    private fun isPendingAlert(row: JSONObject): Boolean =
        row.optString("confirmation_status") == "pending" ||
            row.optString("classification") in setOf("possible_fall", "fall") &&
            row.optString("confirmation_status").isBlank()

    /** Latest caregiver follow-up per fall event; a newer row overrides the event's own status. */
    private fun latestAlertFollowups(): Map<String, JSONObject> {
        val latest = mutableMapOf<String, JSONObject>()
        sharedAlertFollowups().forEach { row ->
            val key = row.optString("fall_event_id")
            val current = latest[key]
            if (current == null || row.optString("created_at") > current.optString("created_at")) latest[key] = row
        }
        return latest
    }

    private fun isResolvedFollowup(status: String): Boolean =
        status.trim().lowercase() in setOf("confirmed", "attended", "closed", "false_alarm", "dismissed", "cancelled", "canceled")

    private fun isPendingSharedAlert(event: JSONObject, followups: Map<String, JSONObject?>): Boolean {
        val followup = followups[event.optString("id")]
        return if (followup != null) !isResolvedFollowup(followup.optString("status")) else isPendingAlert(event)
    }

    private fun pendingSharedAlerts(events: List<JSONObject>): List<JSONObject> {
        val followups = latestAlertFollowups()
        return events.filter { isPendingSharedAlert(it, followups) }
    }
    /**
     * Pref events created before the telemetry table existed do not carry a classification or
     * confirmation status. Only actual fall candidates need attention; a cancelled incident is
     * resolved even though its original fall event remains in the local history.
     */
    private fun isPendingLocalAlert(event: JSONObject, all: List<JSONObject>): Boolean {
        if (isPendingAlert(event)) return true
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
        if (millis <= 0L) return "SIN FECHA"
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = millis }
        val today = java.util.Calendar.getInstance()
        val yesterday = java.util.Calendar.getInstance().apply { add(java.util.Calendar.DAY_OF_YEAR, -1) }
        fun sameDay(a: java.util.Calendar) =
            a.get(java.util.Calendar.YEAR) == cal.get(java.util.Calendar.YEAR) &&
                a.get(java.util.Calendar.DAY_OF_YEAR) == cal.get(java.util.Calendar.DAY_OF_YEAR)
        return when {
            sameDay(today) -> "HOY"
            sameDay(yesterday) -> "AYER"
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
        private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeWidth = dp(13).toFloat(); color = softSurface }
        private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeWidth = dp(13).toFloat(); color = critical }
        private val bounds = RectF()
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val side = minOf(width, height).toFloat()
            val inset = dp(10).toFloat() + track.strokeWidth / 2f
            bounds.set((width - side) / 2f + inset, (height - side) / 2f + inset, (width + side) / 2f - inset, (height + side) / 2f - inset)
            canvas.drawArc(bounds, -90f, 360f, false, track)
            canvas.drawArc(bounds, -90f, 360f * progress, false, arc)
        }
    }
    companion object {
        // A code exchange must survive Activity recreation; its result is stored independently.
        private val passwordRecoveryIo = Executors.newSingleThreadExecutor()
        private const val CAMERA_TAB = TzindaTabs.CAMERA
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
        private var themeToggleGuardUntil = 0L
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
        private const val REQUEST_CAMERA_NOTIFICATIONS = 112
    }
}

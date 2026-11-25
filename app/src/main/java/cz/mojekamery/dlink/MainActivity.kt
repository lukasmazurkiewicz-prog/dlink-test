package cz.mojekamery.dlink

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Build
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.app.NotificationCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.work.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

data class CameraUiState(
    val id: String,
    val name: String,
    val model: String,
    val isOnline: Boolean = true,
    val streamUrl: String? = null,
    val streamId: String? = null,
    val muted: Boolean = true,
    val privacyMode: Boolean = false,
    val motionEnabled: Boolean = true,
    val motionSensitivity: Int = 70,
    val nightVision: String = "AUTO",
    val spotlightOn: Boolean = false,
    val sirenActive: Boolean = false
) {
    val supportsPtz: Boolean get() = model.contains("6500") || model.contains("6501")
    val supportsOutdoorGear: Boolean get() = model.contains("8620")
}

data class CameraLoadResult(
    val cameras: List<CameraUiState>,
    val diagnosticLog: String,
    val usedFallback: Boolean
)

enum class GridMode(val label: String, val columns: Int, val maxCount: Int) {
    SINGLE("1x1", 1, 1),
    DUAL("2x1", 1, 2),
    QUAD("2x2", 2, 4)
}

fun defaultUserCameras(): List<CameraUiState> = listOf(
    CameraUiState(id = "dcs-6500lh-1", name = "Dětský pokoj", model = "DCS-6500LH"),
    CameraUiState(id = "dcs-6501lh-1", name = "Obývák", model = "DCS-6501LH", motionSensitivity = 65),
    CameraUiState(id = "dcs-8620lh-1", name = "Zahrada", model = "DCS-8620LH", motionSensitivity = 85),
    CameraUiState(id = "dcs-6501lh-2", name = "Chodba", model = "DCS-6501LH", nightVision = "ON")
)

object MydlinkClient {
    const val MYDLINK_WEB_PORTAL_URL = "https://portal.mydlink.com/"
    const val DEFAULT_EU_API_SITE = "https://mp-eu-openapi.auto.mydlink.com"
    const val DESKTOP_USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    fun normalizeSiteUrl(rawUrl: String?): String {
        if (rawUrl.isNullOrBlank()) return DEFAULT_EU_API_SITE
        return try {
            val clean = rawUrl.trim()
            if (clean.contains("portal.mydlink.com")) {
                DEFAULT_EU_API_SITE
            } else if (clean.startsWith("http")) {
                val uri = java.net.URI(clean)
                "${uri.scheme}://${uri.host}"
            } else {
                DEFAULT_EU_API_SITE
            }
        } catch (_: Exception) {
            DEFAULT_EU_API_SITE
        }
    }

    fun md5(input: String): String {
        val bytes = MessageDigest.getInstance("MD5").digest(input.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }

    fun parseDevicesFromJson(rawJson: String): List<CameraUiState> {
        val result = mutableListOf<CameraUiState>()
        try {
            val root = JSONObject(rawJson)
            val dataArray: JSONArray? = when {
                root.has("data") && root.get("data") is JSONArray -> root.getJSONArray("data")
                root.has("data") && root.get("data") is JSONObject -> {
                    val d = root.getJSONObject("data")
                    d.optJSONArray("device_list") ?: d.optJSONArray("devices") ?: d.optJSONArray("list")
                }
                root.has("device_list") -> root.getJSONArray("device_list")
                root.has("devices") -> root.getJSONArray("devices")
                else -> null
            }
            if (dataArray != null) {
                for (i in 0 until dataArray.length()) {
                    val item = dataArray.optJSONObject(i) ?: continue
                    val id = item.optString("mydlink_id").ifBlank {
                        item.optString("device_id").ifBlank { item.optString("id") }
                    }
                    val name = item.optString("device_name").ifBlank {
                        item.optString("name").ifBlank { "Kamera ${i + 1}" }
                    }
                    val model = item.optString("model").ifBlank {
                        item.optString("model_name").ifBlank { "DCS-6500LH" }
                    }
                    val online = item.optBoolean("online", true)
                    if (id.isNotBlank() || name.isNotBlank()) {
                        result.add(
                            CameraUiState(
                                id = id.ifBlank { "cam_${i + 1}_${name.hashCode()}" },
                                name = name,
                                model = model,
                                isOnline = online
                            )
                        )
                    }
                }
            }
        } catch (_: Exception) {
        }
        return result
    }

    suspend fun loadCamerasWithToken(
        token: String,
        preferredSite: String?
    ): CameraLoadResult = withContext(Dispatchers.IO) {
        val logs = StringBuilder()
        val baseSite = normalizeSiteUrl(preferredSite)
        val candidateUrls = listOf(
            "$baseSite/me/device/list",
            "$baseSite/v1/me/device/list",
            "https://mp-eu-openapi.auto.mydlink.com/me/device/list",
            "https://api.auto.mydlink.com/me/device/list"
        ).distinct()

        logs.appendLine("Token délka: ${token.length}, server: $baseSite")

        for (url in candidateUrls) {
            try {
                val request = Request.Builder()
                    .url(url)
                    .get()
                    .addHeader("Authorization", "Bearer $token")
                    .addHeader("Origin", "https://portal.mydlink.com")
                    .addHeader("Referer", "https://portal.mydlink.com/")
                    .addHeader("Accept", "application/json")
                    .build()

                httpClient.newCall(request).execute().use { response ->
                    val bodyStr = response.body?.string().orEmpty()
                    if (response.isSuccessful && bodyStr.isNotBlank()) {
                        val parsed = parseDevicesFromJson(bodyStr)
                        if (parsed.isNotEmpty()) {
                            logs.appendLine("OK $url -> nalezeno ${parsed.size} kamer")
                            return@withContext CameraLoadResult(
                                cameras = parsed,
                                diagnosticLog = logs.toString(),
                                usedFallback = false
                            )
                        } else {
                            logs.appendLine("200 OK bez kamer z $url: ${bodyStr.take(180)}")
                        }
                    } else {
                        logs.appendLine("Chyba $url -> HTTP ${response.code}: ${bodyStr.take(150)}")
                    }
                }
            } catch (e: Exception) {
                logs.appendLine("Chyba $url -> ${e.localizedMessage}")
            }
        }

        CameraLoadResult(
            cameras = defaultUserCameras(),
            diagnosticLog = logs.toString(),
            usedFallback = true
        )
    }

    suspend fun sendCommand(
        token: String,
        apiSite: String?,
        cameraId: String,
        command: String,
        params: Map<String, String> = emptyMap()
    ) = withContext(Dispatchers.IO) {
        val base = normalizeSiteUrl(apiSite)
        val payload = JSONObject().apply {
            put("mydlink_id", cameraId)
            put("command", command)
            put("params", JSONObject(params))
        }.toString()

        val req = Request.Builder()
            .url("$base/device/control")
            .post(payload.toRequestBody(jsonMediaType))
            .addHeader("Authorization", "Bearer $token")
            .addHeader("Origin", "https://portal.mydlink.com")
            .addHeader("Referer", "https://portal.mydlink.com/")
            .build()

        runCatching { httpClient.newCall(req).execute().close() }
    }

    suspend fun updateSetting(
        token: String,
        apiSite: String?,
        cameraId: String,
        key: String,
        value: String
    ) = withContext(Dispatchers.IO) {
        val base = normalizeSiteUrl(apiSite)
        val payload = JSONObject().apply {
            put("mydlink_id", cameraId)
            put("key", key)
            put("value", value)
        }.toString()

        val req = Request.Builder()
            .url("$base/device/setting")
            .post(payload.toRequestBody(jsonMediaType))
            .addHeader("Authorization", "Bearer $token")
            .addHeader("Origin", "https://portal.mydlink.com")
            .addHeader("Referer", "https://portal.mydlink.com/")
            .build()

        runCatching { httpClient.newCall(req).execute().close() }
    }

    suspend fun keepStreamAlive(
        token: String,
        apiSite: String?,
        cameraId: String,
        streamId: String
    ) = withContext(Dispatchers.IO) {
        val base = normalizeSiteUrl(apiSite)
        val payload = JSONObject().apply {
            put("mydlink_id", cameraId)
            put("stream_id", streamId)
        }.toString()

        val req = Request.Builder()
            .url("$base/device/stream/keepalive")
            .post(payload.toRequestBody(jsonMediaType))
            .addHeader("Authorization", "Bearer $token")
            .build()

        runCatching { httpClient.newCall(req).execute().close() }
    }
}

class EventCheckWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val prefs = applicationContext.getSharedPreferences("mydlink_prefs", Context.MODE_PRIVATE)
        val token = prefs.getString("access_token", null) ?: return Result.success()
        if (token == "demo_local_token") return Result.success()

        val apiSite = MydlinkClient.normalizeSiteUrl(prefs.getString("api_site", null))
        val lastTs = prefs.getLong("last_event_ts", System.currentTimeMillis())

        return try {
            val req = Request.Builder()
                .url("$apiSite/me/event/list?limit=15")
                .get()
                .addHeader("Authorization", "Bearer $token")
                .build()

            MydlinkClient.httpClient.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (resp.isSuccessful && body.isNotBlank()) {
                    val arr = JSONObject(body).optJSONArray("data")
                    var maxTs = lastTs
                    if (arr != null) {
                        for (i in 0 until arr.length()) {
                            val ev = arr.optJSONObject(i) ?: continue
                            val ts = ev.optLong("timestamp", 0L)
                            if (ts > lastTs) {
                                if (ts > maxTs) maxTs = ts
                                val evId = ev.optString("event_id", "ev_$i").hashCode()
                                val devName = ev.optString("device_name", "Kamera mydlink")
                                val evType = ev.optString("event_type", "motion")
                                sendSystemNotification(applicationContext, evId, devName, evType)
                            }
                        }
                    }
                    if (maxTs > lastTs) {
                        prefs.edit().putLong("last_event_ts", maxTs).apply()
                    }
                }
            }
            Result.success()
        } catch (_: Exception) {
            Result.retry()
        }
    }
}

fun sendSystemNotification(context: Context, id: Int, cameraName: String, type: String) {
    val channelId = "mydlink_alerts"
    val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        val channel = NotificationChannel(
            channelId,
            "Upozornění z IP kamer",
            NotificationManager.IMPORTANCE_HIGH
        ).apply { enableVibration(true) }
        manager.createNotificationChannel(channel)
    }

    val title = when (type) {
        "alarm" -> "ALARM: $cameraName"
        "person" -> "Detekována osoba: $cameraName"
        "sound" -> "Zvuk na kameře: $cameraName"
        else -> "Detekován pohyb: $cameraName"
    }

    val intent = Intent(context, MainActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
    }
    val pendingIntent = PendingIntent.getActivity(
        context, id, intent, PendingIntent.FLAG_IMMUTABLE
    )

    val notification = NotificationCompat.Builder(context, channelId)
        .setSmallIcon(android.R.drawable.ic_dialog_alert)
        .setContentTitle(title)
        .setContentText("Klepnutím otevřete živý náhled kamer.")
        .setPriority(NotificationCompat.PRIORITY_HIGH)
        .setAutoCancel(true)
        .setContentIntent(pendingIntent)
        .build()

    manager.notify(id, notification)
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color(0xFF10B981),
                    background = Color(0xFF121316),
                    surface = Color(0xFF1E2025)
                )
            ) {
                Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFF121316)) {
                    MojeKameryApp()
                }
            }
        }
    }
}

@Composable
fun KeepScreenAwake(enabled: Boolean) {
    val view = LocalView.current
    DisposableEffect(enabled) {
        view.keepScreenOn = enabled
        onDispose { view.keepScreenOn = false }
    }
}

@Composable
fun MojeKameryApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { context.getSharedPreferences("mydlink_prefs", Context.MODE_PRIVATE) }

    var accessToken by remember { mutableStateOf(prefs.getString("access_token", null)) }
    var apiSite by remember { mutableStateOf(prefs.getString("api_site", MydlinkClient.DEFAULT_EU_API_SITE)) }
    var cameras by remember { mutableStateOf(defaultUserCameras()) }
    var gridMode by remember { mutableStateOf(GridMode.QUAD) }
    var selectedSingleId by remember { mutableStateOf("dcs-6500lh-1") }
    var keepAwake by remember { mutableStateOf(true) }
    var keepAliveCountdown by remember { mutableIntStateOf(45) }

    var settingsCameraId by remember { mutableStateOf<String?>(null) }
    var fullscreenCameraId by remember { mutableStateOf<String?>(null) }
    var showNotificationsSheet by remember { mutableStateOf(false) }
    var diagnosticLog by remember { mutableStateOf("Zatím neproběhlo žádné volání API.") }
    var showDiagnosticDialog by remember { mutableStateOf(false) }
    var isRefreshingStreams by remember { mutableStateOf(false) }

    val snackbarHostState = remember { SnackbarHostState() }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        val work = PeriodicWorkRequestBuilder<EventCheckWorker>(15, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            "mydlink_bg_events",
            ExistingPeriodicWorkPolicy.KEEP,
            work
        )
    }

    KeepScreenAwake(enabled = accessToken != null && keepAwake)

    LaunchedEffect(accessToken) {
        if (accessToken == null) return@LaunchedEffect
        while (isActive) {
            delay(1000L)
            if (keepAliveCountdown <= 1) {
                keepAliveCountdown = 45
                val token = accessToken ?: continue
                if (token != "demo_local_token") {
                    cameras.forEach { cam ->
                        val sId = cam.streamId
                        if (sId != null) {
                            MydlinkClient.keepStreamAlive(token, apiSite, cam.id, sId)
                        }
                    }
                }
            } else {
                keepAliveCountdown -= 1
            }
        }
    }

    fun sendApiCommand(camId: String, command: String, params: Map<String, String> = emptyMap()) {
        val token = accessToken ?: return
        if (token == "demo_local_token") return
        scope.launch {
            MydlinkClient.sendCommand(token, apiSite, camId, command, params)
        }
    }

    fun sendApiSetting(camId: String, key: String, value: String) {
        val token = accessToken ?: return
        if (token == "demo_local_token") return
        scope.launch {
            MydlinkClient.updateSetting(token, apiSite, camId, key, value)
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = Color(0xFF121316)
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            if (accessToken == null) {
                LoginScreen(
                    onLoginSuccess = { token, siteUrl, loadedCameras, diag ->
                        val cleanSite = MydlinkClient.normalizeSiteUrl(siteUrl)
                        prefs.edit()
                            .putString("access_token", token)
                            .putString("api_site", cleanSite)
                            .apply()
                        accessToken = token
                        apiSite = cleanSite
                        diagnosticLog = diag
                        if (loadedCameras.isNotEmpty()) {
                            cameras = loadedCameras
                            selectedSingleId = loadedCameras.first().id
                        }
                    }
                )
            } else {
                DashboardScreen(
                    cameras = cameras,
                    gridMode = gridMode,
                    selectedSingleId = selectedSingleId,
                    keepAwake = keepAwake,
                    keepAliveSeconds = keepAliveCountdown,
                    isRefreshing = isRefreshingStreams,
                    onGridModeChange = { gridMode = it },
                    onSelectSingleCamera = { selectedSingleId = it },
                    onRefreshStreams = {
                        val token = accessToken ?: return@DashboardScreen
                        scope.launch {
                            isRefreshingStreams = true
                            val res = MydlinkClient.loadCamerasWithToken(token, apiSite)
                            if (!res.usedFallback && res.cameras.isNotEmpty()) {
                                cameras = res.cameras
                            }
                            diagnosticLog = res.diagnosticLog
                            isRefreshingStreams = false
                            snackbarHostState.showSnackbar("Streamy obnoveny")
                        }
                    },
                    onOpenDiagnostics = { showDiagnosticDialog = true },
                    onToggleKeepAwake = {
                        keepAwake = !keepAwake
                        scope.launch {
                            snackbarHostState.showSnackbar(
                                if (keepAwake) "Displej i streamy zůstávají trvale zapnuté"
                                else "Standardní režim uspání displeje"
                            )
                        }
                    },
                    onToggleMute = { camId ->
                        cameras = cameras.map {
                            if (it.id == camId) it.copy(muted = !it.muted) else it.copy(muted = true)
                        }
                    },
                    onPtzMove = { camId, dir ->
                        sendApiCommand(camId, "ptz_move", mapOf("direction" to dir, "step" to "1"))
                        scope.launch { snackbarHostState.showSnackbar("PTZ příkaz: $dir") }
                    },
                    onToggleSpotlight = { camId ->
                        cameras = cameras.map { cam ->
                            if (cam.id == camId) {
                                val next = !cam.spotlightOn
                                sendApiCommand(camId, if (next) "spotlight_on" else "spotlight_off")
                                cam.copy(spotlightOn = next)
                            } else cam
                        }
                    },
                    onToggleSiren = { camId ->
                        cameras = cameras.map { cam ->
                            if (cam.id == camId) {
                                val next = !cam.sirenActive
                                sendApiCommand(camId, if (next) "siren_on" else "siren_off")
                                cam.copy(sirenActive = next)
                            } else cam
                        }
                    },
                    onOpenFullscreen = { fullscreenCameraId = it },
                    onOpenSettings = { settingsCameraId = it },
                    onOpenNotifications = { showNotificationsSheet = true },
                    onLogout = {
                        CookieManager.getInstance().removeAllCookies(null)
                        CookieManager.getInstance().flush()
                        prefs.edit().remove("access_token").remove("api_site").apply()
                        accessToken = null
                    }
                )
            }

            val fsCam = cameras.find { it.id == fullscreenCameraId }
            if (fsCam != null) {
                FullscreenCameraOverlay(
                    camera = fsCam,
                    onClose = { fullscreenCameraId = null },
                    onToggleMute = {
                        cameras = cameras.map {
                            if (it.id == fsCam.id) it.copy(muted = !it.muted) else it.copy(muted = true)
                        }
                    },
                    onPtzMove = { dir -> sendApiCommand(fsCam.id, "ptz_move", mapOf("direction" to dir)) },
                    onToggleSpotlight = {
                        cameras = cameras.map {
                            if (it.id == fsCam.id) {
                                val next = !it.spotlightOn
                                sendApiCommand(fsCam.id, if (next) "spotlight_on" else "spotlight_off")
                                it.copy(spotlightOn = next)
                            } else it
                        }
                    },
                    onToggleSiren = {
                        cameras = cameras.map {
                            if (it.id == fsCam.id) {
                                val next = !it.sirenActive
                                sendApiCommand(fsCam.id, if (next) "siren_on" else "siren_off")
                                it.copy(sirenActive = next)
                            } else it
                        }
                    },
                    onOpenSettings = { settingsCameraId = fsCam.id }
                )
            }

            val settingsCam = cameras.find { it.id == settingsCameraId }
            if (settingsCam != null) {
                CameraSettingsDialog(
                    camera = settingsCam,
                    onDismiss = { settingsCameraId = null },
                    onUpdateCamera = { updated, key, value ->
                        cameras = cameras.map { if (it.id == updated.id) updated else it }
                        sendApiSetting(updated.id, key, value)
                    },
                    onTriggerCommand = { cmd ->
                        sendApiCommand(settingsCam.id, cmd)
                        scope.launch { snackbarHostState.showSnackbar("Příkaz odeslán: $cmd") }
                    }
                )
            }

            if (showNotificationsSheet) {
                NotificationsDialog(
                    onDismiss = { showNotificationsSheet = false },
                    onTestPush = {
                        sendSystemNotification(
                            context,
                            System.currentTimeMillis().toInt(),
                            "Zahrada (DCS-8620LH)",
                            "alarm"
                        )
                    }
                )
            }

            if (showDiagnosticDialog) {
                AlertDialog(
                    onDismissRequest = { showDiagnosticDialog = false },
                    title = { Text("Diagnostika streamů a API") },
                    text = {
                        Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                            Text(
                                text = diagnosticLog,
                                fontSize = 12.sp,
                                color = Color(0xFFD1D5DB)
                            )
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = { showDiagnosticDialog = false }) {
                            Text("Zavřít")
                        }
                    }
                )
            }
        }
    }
}

@Composable
fun LoginScreen(
    onLoginSuccess: (String, String?, List<CameraUiState>, String) -> Unit
) {
    val scope = rememberCoroutineScope()
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var rememberToken by remember { mutableStateOf(true) }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var showWebOAuth by remember { mutableStateOf(false) }

    fun completeLoginWithToken(token: String, siteUrl: String?, prefetchedCameras: List<CameraUiState> = emptyList()) {
        isLoading = true
        errorMessage = null
        scope.launch {
            val result = MydlinkClient.loadCamerasWithToken(token, siteUrl)
            val finalCameras = when {
                prefetchedCameras.isNotEmpty() -> prefetchedCameras
                !result.usedFallback && result.cameras.isNotEmpty() -> result.cameras
                else -> defaultUserCameras()
            }
            isLoading = false
            onLoginSuccess(
                token,
                siteUrl,
                finalCameras,
                result.diagnosticLog + "\nNačtené kamery: ${finalCameras.joinToString { "${it.name} (${it.model})" }}"
            )
        }
    }

    if (showWebOAuth) {
        MydlinkWebOAuthDialog(
            onTokenReceived = { token, siteUrl, prefetchedCameras ->
                showWebOAuth = false
                completeLoginWithToken(token, siteUrl, prefetchedCameras)
            },
            onDismiss = { showWebOAuth = false }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFF1A1D24), Color(0xFF121316))
                )
            )
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(68.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(Color(0xFF10B981).copy(alpha = 0.15f))
                .border(1.dp, Color(0xFF10B981).copy(alpha = 0.4f), RoundedCornerShape(20.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Default.Shield,
                contentDescription = null,
                tint = Color(0xFF34D399),
                modifier = Modifier.size(34.dp)
            )
        }

        Spacer(Modifier.height(16.dp))
        Text("Moje Kamery", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.White)
        Text(
            "Přihlášení k účtu mydlink Cloud API",
            fontSize = 13.sp,
            color = Color(0xFF9CA3AF)
        )

        Spacer(Modifier.height(24.dp))

        Button(
            onClick = { showWebOAuth = true },
            enabled = !isLoading,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(14.dp)
        ) {
            Icon(Icons.Default.Lock, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Přihlásit se přes mydlink účet (OAuth2)", fontWeight = FontWeight.SemiBold)
        }

        Spacer(Modifier.height(18.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            HorizontalDivider(modifier = Modifier.weight(1f), color = Color(0xFF2E323B))
            Text("  nebo přímý přístup  ", fontSize = 11.sp, color = Color(0xFF6B7280))
            HorizontalDivider(modifier = Modifier.weight(1f), color = Color(0xFF2E323B))
        }

        Spacer(Modifier.height(14.dp))

        OutlinedTextField(
            value = email,
            onValueChange = { email = it },
            label = { Text("mydlink E-mail") },
            singleLine = true,
            leadingIcon = { Icon(Icons.Default.Email, contentDescription = null) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp)
        )

        Spacer(Modifier.height(12.dp))

        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("Heslo k účtu") },
            singleLine = true,
            leadingIcon = { Icon(Icons.Default.Key, contentDescription = null) },
            trailingIcon = {
                IconButton(onClick = { passwordVisible = !passwordVisible }) {
                    Icon(
                        if (passwordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                        contentDescription = null
                    )
                }
            },
            visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp)
        )

        Spacer(Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(checked = rememberToken, onCheckedChange = { rememberToken = it })
            Text("Uložit OAuth token bezpečně", fontSize = 13.sp, color = Color(0xFFD1D5DB))
        }

        if (errorMessage != null) {
            Text(
                errorMessage!!,
                color = Color(0xFFF87171),
                fontSize = 12.sp,
                modifier = Modifier.padding(vertical = 6.dp)
            )
        }

        Spacer(Modifier.height(12.dp))

        OutlinedButton(
            onClick = { showWebOAuth = true },
            enabled = !isLoading,
            modifier = Modifier.fillMaxWidth().height(48.dp),
            shape = RoundedCornerShape(14.dp)
        ) {
            if (isLoading) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            } else {
                Text("Ověřit e-mail a heslo přes mydlink", fontWeight = FontWeight.SemiBold)
            }
        }

        Spacer(Modifier.height(12.dp))

        TextButton(
            onClick = {
                onLoginSuccess("demo_local_token", MydlinkClient.DEFAULT_EU_API_SITE, defaultUserCameras(), "Offline Demo režim")
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Spustit v offline Demo režimu (4 kamery)", color = Color(0xFF9CA3AF))
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun MydlinkWebOAuthDialog(
    onTokenReceived: (String, String?, List<CameraUiState>) -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .fillMaxHeight(0.88f)
                .clip(RoundedCornerShape(20.dp)),
            color = Color(0xFF1E2025)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "Přihlášení k mydlink účtu",
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp,
                            color = Color.White
                        )
                        Text(
                            "Po přihlášení se kamery automaticky přenesou do aplikace",
                            fontSize = 11.sp,
                            color = Color(0xFF34D399)
                        )
                    }
                    TextButton(onClick = onDismiss) {
                        Text("Zrušit", color = Color(0xFF34D399))
                    }
                }

                AndroidView(
                    factory = { ctx ->
                        WebView(ctx).apply {
                            CookieManager.getInstance().setAcceptCookie(true)
                            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.databaseEnabled = true
                            settings.useWideViewPort = true
                            settings.loadWithOverviewMode = true
                            settings.userAgentString = MydlinkClient.DESKTOP_USER_AGENT
                            var completed = false

                            addJavascriptInterface(object {
                                @JavascriptInterface
                                fun onSessionCaptured(
                                    token: String,
                                    siteUrl: String?,
                                    rawDeviceJson: String?
                                ) {
                                    if (!completed && token.isNotBlank()) {
                                        completed = true
                                        CookieManager.getInstance().flush()
                                        val parsedCameras = if (!rawDeviceJson.isNullOrBlank()) {
                                            MydlinkClient.parseDevicesFromJson(rawDeviceJson)
                                        } else {
                                            emptyList()
                                        }
                                        post {
                                            onTokenReceived(token, siteUrl, parsedCameras)
                                        }
                                    }
                                }
                            }, "AndroidOAuthBridge")

                            webViewClient = object : WebViewClient() {
                                override fun shouldOverrideUrlLoading(
                                    view: WebView?,
                                    request: WebResourceRequest?
                                ): Boolean {
                                    val url = request?.url?.toString() ?: return false
                                    if (url.contains("access_token=")) {
                                        val token = url.substringAfter("access_token=").substringBefore("&")
                                        if (token.isNotBlank() && !completed) {
                                            completed = true
                                            CookieManager.getInstance().flush()
                                            onTokenReceived(token, MydlinkClient.DEFAULT_EU_API_SITE, emptyList())
                                            return true
                                        }
                                    }
                                    return false
                                }

                                override fun onPageFinished(view: WebView?, url: String?) {
                                    super.onPageFinished(view, url)
                                    val interceptorJs = """
                                        (function() {
                                            if (window.__mydlinkHooked) return;
                                            window.__mydlinkHooked = true;
                                            window.__lastUserToken = null;
                                            window.__lastApiSite = null;
                                            window.__lastDeviceJson = null;

                                            function handleAuthJson(txt, srcUrl) {
                                                try {
                                                    var data = JSON.parse(txt);
                                                    if (data && data.access_token && data.refresh_token) {
                                                        window.__lastUserToken = data.access_token;
                                                        window.__lastApiSite = data.api_site || srcUrl;
                                                        AndroidOAuthBridge.onSessionCaptured(
                                                            data.access_token,
                                                            window.__lastApiSite,
                                                            window.__lastDeviceJson
                                                        );
                                                    }
                                                } catch(e) {}
                                            }

                                            function handleDeviceListJson(txt, srcUrl, authHeader) {
                                                try {
                                                    var data = JSON.parse(txt);
                                                    if (data && (Array.isArray(data.data) || (data.data && Array.isArray(data.data.device_list)))) {
                                                        window.__lastDeviceJson = txt;
                                                        var tok = window.__lastUserToken;
                                                        if (!tok && authHeader && authHeader.toLowerCase().indexOf('bearer ') === 0) {
                                                            tok = authHeader.substring(7).trim();
                                                        }
                                                        AndroidOAuthBridge.onSessionCaptured(
                                                            tok || 'web_session_active',
                                                            srcUrl,
                                                            txt
                                                        );
                                                    }
                                                } catch(e) {}
                                            }

                                            function extractCamerasFromDom() {
                                                var found = [];
                                                var seen = {};
                                                var allText = document.body ? (document.body.innerText || '') : '';
                                                if (allText.indexOf('Watch Live') === -1 && allText.indexOf('Click to watch') === -1) {
                                                    return null;
                                                }
                                                var blocks = document.querySelectorAll('div, section, article, li');
                                                for (var i = 0; i < blocks.length; i++) {
                                                    var el = blocks[i];
                                                    var txt = (el.innerText || '').trim();
                                                    if (txt.indexOf('Watch Live') !== -1 && txt.length < 220) {
                                                        var lines = txt.split('\n').map(function(l) { return l.trim(); }).filter(function(l) {
                                                            return l.length > 0 &&
                                                                l.indexOf('Watch Live') === -1 &&
                                                                l.indexOf('Click to watch') === -1 &&
                                                                !(/^\d{4}\/\d{2}\/\d{2}/.test(l));
                                                        });
                                                        var camName = lines.length > 0 ? lines[0] : '';
                                                        var modelMatch = txt.match(/DCS-[A-Z0-9]+/i);
                                                        var modelName = modelMatch ? modelMatch[0].toUpperCase() : 'DCS-6500LH';
                                                        if (camName && camName.length < 40 && !seen[camName]) {
                                                            seen[camName] = true;
                                                            found.push({
                                                                mydlink_id: 'dom_cam_' + found.length + '_' + camName,
                                                                device_name: camName,
                                                                model: modelName,
                                                                online: true
                                                            });
                                                        }
                                                    }
                                                }
                                                if (found.length > 0) {
                                                    return JSON.stringify({ data: found });
                                                }
                                                return null;
                                            }

                                            var origFetch = window.fetch;
                                            window.fetch = function(input, init) {
                                                var u = (typeof input === 'string') ? input : (input && input.url);
                                                var auth = null;
                                                try {
                                                    var h = init && init.headers;
                                                    if (h) {
                                                        if (typeof h.get === 'function') auth = h.get('Authorization') || h.get('authorization');
                                                        else auth = h['Authorization'] || h['authorization'];
                                                    }
                                                } catch(e) {}
                                                return origFetch.apply(this, arguments).then(function(resp) {
                                                    try {
                                                        if (u && u.indexOf('mydlink.com') !== -1) {
                                                            resp.clone().text().then(function(txt) {
                                                                handleAuthJson(txt, u);
                                                                if (auth && auth.toLowerCase().indexOf('bearer ') === 0 && u.indexOf('/me/') !== -1) {
                                                                    window.__lastUserToken = auth.substring(7).trim();
                                                                    window.__lastApiSite = u;
                                                                }
                                                                if (u.indexOf('/device/list') !== -1) {
                                                                    handleDeviceListJson(txt, u, auth);
                                                                }
                                                            });
                                                        }
                                                    } catch(e) {}
                                                    return resp;
                                                });
                                            };

                                            var origOpen = XMLHttpRequest.prototype.open;
                                            var origSetHeader = XMLHttpRequest.prototype.setRequestHeader;
                                            var origSend = XMLHttpRequest.prototype.send;

                                            XMLHttpRequest.prototype.open = function(method, reqUrl) {
                                                this.__reqUrl = reqUrl ? reqUrl.toString() : '';
                                                this.__reqHeaders = {};
                                                return origOpen.apply(this, arguments);
                                            };

                                            XMLHttpRequest.prototype.setRequestHeader = function(header, value) {
                                                if (this.__reqHeaders && header) {
                                                    this.__reqHeaders[header.toLowerCase()] = value;
                                                }
                                                return origSetHeader.apply(this, arguments);
                                            };

                                            XMLHttpRequest.prototype.send = function() {
                                                var self = this;
                                                this.addEventListener('load', function() {
                                                    try {
                                                        var u = self.__reqUrl || '';
                                                        var auth = self.__reqHeaders ? self.__reqHeaders['authorization'] : null;
                                                        var txt = self.responseText || '';
                                                        if (u.indexOf('mydlink.com') !== -1) {
                                                            handleAuthJson(txt, u);
                                                            if (auth && auth.toLowerCase().indexOf('bearer ') === 0 && u.indexOf('/me/') !== -1) {
                                                                window.__lastUserToken = auth.substring(7).trim();
                                                                window.__lastApiSite = u;
                                                            }
                                                            if (u.indexOf('/device/list') !== -1) {
                                                                handleDeviceListJson(txt, u, auth);
                                                            }
                                                        }
                                                    } catch(e) {}
                                                });
                                                return origSend.apply(this, arguments);
                                            };

                                            setInterval(function() {
                                                var domJson = extractCamerasFromDom();
                                                if (domJson) {
                                                    AndroidOAuthBridge.onSessionCaptured(
                                                        window.__lastUserToken || 'web_session_active',
                                                        window.__lastApiSite || 'https://mp-eu-openapi.auto.mydlink.com',
                                                        window.__lastDeviceJson || domJson
                                                    );
                                                }
                                            }, 1000);
                                        })();
                                    """.trimIndent()
                                    view?.evaluateJavascript(interceptorJs, null)
                                }
                            }
                            loadUrl(MydlinkClient.MYDLINK_WEB_PORTAL_URL)
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}

@Composable
fun DashboardScreen(
    cameras: List<CameraUiState>,
    gridMode: GridMode,
    selectedSingleId: String,
    keepAwake: Boolean,
    keepAliveSeconds: Int,
    isRefreshing: Boolean,
    onGridModeChange: (GridMode) -> Unit,
    onSelectSingleCamera: (String) -> Unit,
    onRefreshStreams: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onToggleKeepAwake: () -> Unit,
    onToggleMute: (String) -> Unit,
    onPtzMove: (String, String) -> Unit,
    onToggleSpotlight: (String) -> Unit,
    onToggleSiren: (String) -> Unit,
    onOpenFullscreen: (String) -> Unit,
    onOpenSettings: (String) -> Unit,
    onOpenNotifications: () -> Unit,
    onLogout: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize().background(Color(0xFF121316))) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF1E2025))
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Moje Kamery", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Color.White)
                Text(
                    "Ping za ${keepAliveSeconds}s",
                    fontSize = 11.sp,
                    color = Color(0xFF34D399)
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                IconButton(onClick = onRefreshStreams, enabled = !isRefreshing) {
                    Icon(
                        Icons.Default.Refresh,
                        contentDescription = "Obnovit streamy",
                        tint = if (isRefreshing) Color.Gray else Color(0xFF34D399)
                    )
                }

                IconButton(onClick = onOpenDiagnostics) {
                    Icon(
                        Icons.Default.BugReport,
                        contentDescription = "Diagnostika",
                        tint = Color(0xFF9CA3AF)
                    )
                }

                IconButton(onClick = onOpenNotifications) {
                    Icon(Icons.Default.Notifications, contentDescription = "Události", tint = Color.White)
                }

                FilterChip(
                    selected = keepAwake,
                    onClick = onToggleKeepAwake,
                    label = { Text("Zapnuto", fontSize = 10.sp) },
                    leadingIcon = {
                        Icon(Icons.Default.Visibility, contentDescription = null, modifier = Modifier.size(14.dp))
                    }
                )

                IconButton(onClick = onLogout) {
                    Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = "Odhlásit", tint = Color(0xFF9CA3AF))
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.Center
        ) {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF1C1E24))
                    .padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                GridMode.entries.forEach { mode ->
                    val selected = gridMode == mode
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (selected) Color(0xFFE5E7EB) else Color.Transparent)
                            .clickable { onGridModeChange(mode) }
                            .padding(horizontal = 18.dp, vertical = 6.dp)
                    ) {
                        Text(
                            mode.label,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (selected) Color.Black else Color(0xFF9CA3AF)
                        )
                    }
                }
            }
        }

        if (gridMode == GridMode.SINGLE) {
            LazyRow(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(cameras) { cam ->
                    FilterChip(
                        selected = cam.id == selectedSingleId,
                        onClick = { onSelectSingleCamera(cam.id) },
                        label = { Text(cam.name, fontSize = 12.sp) }
                    )
                }
            }
        }

        val visibleCameras = when (gridMode) {
            GridMode.SINGLE -> cameras.filter { it.id == selectedSingleId }
            GridMode.DUAL -> cameras.take(2)
            GridMode.QUAD -> cameras.take(4)
        }

        LazyVerticalGrid(
            columns = GridCells.Fixed(gridMode.columns),
            modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(bottom = 12.dp)
        ) {
            items(visibleCameras, key = { it.id }) { camera ->
                val tileIndex = visibleCameras.indexOf(camera).coerceAtLeast(0)
                CameraTileCard(
                    camera = camera,
                    tileIndex = tileIndex,
                    onRefreshStream = onRefreshStreams,
                    onToggleMute = { onToggleMute(camera.id) },
                    onPtzMove = { dir -> onPtzMove(camera.id, dir) },
                    onToggleSpotlight = { onToggleSpotlight(camera.id) },
                    onToggleSiren = { onToggleSiren(camera.id) },
                    onOpenFullscreen = { onOpenFullscreen(camera.id) },
                    onOpenSettings = { onOpenSettings(camera.id) }
                )
            }
        }
    }
}

@Composable
fun CameraTileCard(
    camera: CameraUiState,
    tileIndex: Int = 0,
    onRefreshStream: () -> Unit,
    onToggleMute: () -> Unit,
    onPtzMove: (String) -> Unit,
    onToggleSpotlight: () -> Unit,
    onToggleSiren: () -> Unit,
    onOpenFullscreen: () -> Unit,
    onOpenSettings: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF1E2026))
            .border(1.dp, Color(0xFF2B2E36), RoundedCornerShape(16.dp))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF252830))
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "${camera.name} (${camera.model})",
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 10f)
                .background(Color.Black)
                .clickable { onOpenFullscreen() }
        ) {
            CameraVideoSurface(camera = camera, tileIndex = tileIndex, onRefreshStream = onRefreshStream)

            Row(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(6.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color(0xFFDC2626))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(modifier = Modifier.size(6.dp).clip(CircleShape).background(Color.White))
                Spacer(Modifier.width(4.dp))
                Text("ŽIVĚ", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = Color.White)
            }

            IconButton(
                onClick = onOpenFullscreen,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(4.dp)
                    .size(30.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.Black.copy(alpha = 0.65f))
            ) {
                Icon(Icons.Default.Fullscreen, contentDescription = "Celá obrazovka", tint = Color.White, modifier = Modifier.size(18.dp))
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            IconButton(
                onClick = onToggleMute,
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(if (!camera.muted) Color(0xFF059669) else Color(0xFF2E323B))
            ) {
                Icon(
                    if (camera.muted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                    contentDescription = "Zvuk",
                    tint = Color.White,
                    modifier = Modifier.size(16.dp)
                )
            }

            if (camera.supportsPtz) {
                PtzDpadControl(onMove = onPtzMove)
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    IconButton(
                        onClick = onToggleSpotlight,
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(if (camera.spotlightOn) Color(0xFFFBBF24) else Color(0xFF2E323B))
                    ) {
                        Icon(
                            Icons.Default.Lightbulb,
                            contentDescription = "Reflektor",
                            tint = if (camera.spotlightOn) Color.Black else Color.White,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    IconButton(
                        onClick = onToggleSiren,
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(if (camera.sirenActive) Color(0xFFDC2626) else Color(0xFF2E323B))
                    ) {
                        Icon(
                            Icons.Default.Warning,
                            contentDescription = "Siréna",
                            tint = Color.White,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            IconButton(
                onClick = onOpenSettings,
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF2E323B))
            ) {
                Icon(Icons.Default.Settings, contentDescription = "Nastavení", tint = Color.White, modifier = Modifier.size(16.dp))
            }
        }
    }
}

@Composable
fun PtzDpadControl(onMove: (String) -> Unit, large: Boolean = false) {
    val outerSize = if (large) 96.dp else 64.dp
    val centerSize = if (large) 30.dp else 20.dp

    Box(
        modifier = Modifier
            .size(outerSize)
            .clip(CircleShape)
            .background(Color(0xFF272A32))
            .border(1.dp, Color(0xFF3F4451), CircleShape),
        contentAlignment = Alignment.Center
    ) {
        IconButton(onClick = { onMove("up") }, modifier = Modifier.align(Alignment.TopCenter).size(24.dp)) {
            Icon(Icons.Default.KeyboardArrowUp, null, tint = Color.White)
        }
        IconButton(onClick = { onMove("down") }, modifier = Modifier.align(Alignment.BottomCenter).size(24.dp)) {
            Icon(Icons.Default.KeyboardArrowDown, null, tint = Color.White)
        }
        IconButton(onClick = { onMove("left") }, modifier = Modifier.align(Alignment.CenterStart).size(24.dp)) {
            Icon(Icons.Default.KeyboardArrowLeft, null, tint = Color.White)
        }
        IconButton(onClick = { onMove("right") }, modifier = Modifier.align(Alignment.CenterEnd).size(24.dp)) {
            Icon(Icons.Default.KeyboardArrowRight, null, tint = Color.White)
        }
        Box(
            modifier = Modifier
                .size(centerSize)
                .clip(CircleShape)
                .background(Color(0xFF4B5563))
                .clickable { onMove("center") }
        )
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun CameraVideoSurface(
    camera: CameraUiState,
    tileIndex: Int = 0,
    onRefreshStream: () -> Unit = {}
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("mydlink_prefs", Context.MODE_PRIVATE) }
    val token = remember { prefs.getString("access_token", null) }
    var webVideoPlaying by remember(camera.id) { mutableStateOf(false) }

    LaunchedEffect(camera.id, webVideoPlaying) {
        if (!webVideoPlaying && camera.streamUrl == null && token != null && token != "demo_local_token") {
            delay(9000L)
            webVideoPlaying = true
        }
    }

    if (camera.privacyMode) {
        Box(modifier = Modifier.fillMaxSize().background(Color(0xFF090A0D)), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Default.VisibilityOff, null, tint = Color(0xFFFBBF24))
                Text("Soukromý režim aktivní", fontSize = 11.sp, color = Color.LightGray)
            }
        }
        return
    }

    if (camera.streamUrl != null) {
        val exoPlayer = remember(camera.streamUrl) {
            ExoPlayer.Builder(context).build().apply {
                addListener(object : Player.Listener {
                    override fun onPlayerError(error: PlaybackException) {
                        prepare()
                        playWhenReady = true
                    }
                })
                setMediaItem(MediaItem.fromUri(camera.streamUrl))
                prepare()
                playWhenReady = true
            }
        }

        LaunchedEffect(camera.muted) {
            exoPlayer.volume = if (camera.muted) 0f else 1f
        }

        DisposableEffect(exoPlayer) {
            onDispose { exoPlayer.release() }
        }

        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    player = exoPlayer
                    useController = false
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                }
            },
            modifier = Modifier.fillMaxSize()
        )
    } else if (token != null && token != "demo_local_token") {
        Box(modifier = Modifier.fillMaxSize().background(Color(0xFF0D1117))) {
            AndroidView(
                factory = { ctx ->
                    WebView(ctx).apply {
                        CookieManager.getInstance().setAcceptCookie(true)
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.databaseEnabled = true
                        settings.useWideViewPort = true
                        settings.loadWithOverviewMode = true
                        settings.mediaPlaybackRequiresUserGesture = false
                        settings.userAgentString = MydlinkClient.DESKTOP_USER_AGENT
                        webChromeClient = object : WebChromeClient() {
                            override fun onPermissionRequest(request: PermissionRequest?) {
                                request?.grant(request.resources)
                            }
                        }

                        addJavascriptInterface(object {
                            @JavascriptInterface
                            fun onVideoFramesReady() {
                                post { webVideoPlaying = true }
                            }
                        }, "AndroidStreamBridge")

                        webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView?, url: String?) {
                                super.onPageFinished(view, url)
                                val safeName = JSONObject.quote(camera.name)
                                val playerHook = """
                                    (function() {
                                        var targetName = $safeName;
                                        var fallbackIdx = $tileIndex;
                                        var clickedWatchLive = false;
                                        var ticks = 0;

                                        function findBestCard() {
                                            var best = null;
                                            var bestLen = 999999;
                                            var allCards = [];
                                            var blocks = document.querySelectorAll('div, section, article, li');
                                            for (var i = 0; i < blocks.length; i++) {
                                                var el = blocks[i];
                                                var txt = (el.innerText || '');
                                                var hasLiveTrigger = (txt.indexOf('Watch Live') !== -1 || txt.indexOf('Click to watch') !== -1 || el.querySelector('video, canvas'));
                                                if (hasLiveTrigger && txt.length < 350) {
                                                    allCards.push(el);
                                                    if (txt.toLowerCase().indexOf(targetName.toLowerCase()) !== -1 && txt.length < bestLen) {
                                                        best = el;
                                                        bestLen = txt.length;
                                                    }
                                                }
                                            }
                                            if (!best && ticks > 3 && allCards.length > fallbackIdx) {
                                                best = allCards[fallbackIdx];
                                            }
                                            return best;
                                        }

                                        function pinElementFullscreen(el) {
                                            el.style.cssText = 'position:fixed !important;top:0 !important;left:0 !important;width:100vw !important;height:100vh !important;object-fit:cover !important;z-index:2147483647 !important;background:#000 !important;';
                                            AndroidStreamBridge.onVideoFramesReady();
                                        }

                                        function isolateVideoOrCanvas() {
                                            var videos = document.querySelectorAll('video');
                                            for (var i = 0; i < videos.length; i++) {
                                                var v = videos[i];
                                                v.muted = ${camera.muted};
                                                v.autoplay = true;
                                                v.playsInline = true;
                                                if (v.paused) { v.play().catch(function(){}); }
                                                if (v.readyState >= 2 && v.videoWidth > 0) {
                                                    pinElementFullscreen(v);
                                                    return true;
                                                }
                                            }
                                            if (clickedWatchLive) {
                                                var canvases = document.querySelectorAll('canvas');
                                                for (var c = 0; c < canvases.length; c++) {
                                                    var cv = canvases[c];
                                                    if (cv.width >= 160 && cv.height >= 90) {
                                                        pinElementFullscreen(cv);
                                                        return true;
                                                    }
                                                }
                                            }
                                            return false;
                                        }

                                        setInterval(function() {
                                            ticks++;
                                            if (isolateVideoOrCanvas()) return;
                                            var card = findBestCard();
                                            if (card && !clickedWatchLive) {
                                                try { card.scrollIntoView({block: 'center'}); } catch(e) {}
                                                var btns = card.querySelectorAll('button, span, div, a, img');
                                                for (var k = 0; k < btns.length; k++) {
                                                    var bTxt = (btns[k].innerText || '').trim();
                                                    if (bTxt === 'Watch Live' || bTxt.indexOf('Watch Live') !== -1 || bTxt.indexOf('Click to watch') !== -1) {
                                                        btns[k].click();
                                                        btns[k].dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true }));
                                                        clickedWatchLive = true;
                                                        break;
                                                    }
                                                }
                                                if (!clickedWatchLive) {
                                                    card.click();
                                                    card.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true }));
                                                    clickedWatchLive = true;
                                                }
                                            }
                                        }, 850);
                                    })();
                                """.trimIndent()
                                view?.evaluateJavascript(playerHook, null)
                            }
                        }
                        postDelayed({
                            loadUrl(MydlinkClient.MYDLINK_WEB_PORTAL_URL)
                        }, (tileIndex * 450L))
                    }
                },
                update = { webView ->
                    webView.evaluateJavascript(
                        "document.querySelectorAll('video').forEach(function(v){ v.muted = ${camera.muted}; if (v.paused) { v.play().catch(function(){}); } });",
                        null
                    )
                },
                modifier = Modifier.fillMaxSize()
            )

            if (!webVideoPlaying) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Brush.verticalGradient(listOf(Color(0xFF1F2937), Color(0xFF111827)))),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(8.dp)
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(22.dp),
                            strokeWidth = 2.dp,
                            color = Color(0xFF34D399)
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Připojuji WebRTC (${camera.model})...",
                            fontSize = 10.sp,
                            color = Color(0xFFD1D5DB),
                            textAlign = TextAlign.Center
                        )
                        Spacer(Modifier.height(6.dp))
                        OutlinedButton(
                            onClick = onRefreshStream,
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                            modifier = Modifier.height(26.dp)
                        ) {
                            Icon(Icons.Default.Refresh, null, modifier = Modifier.size(12.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Obnovit", fontSize = 10.sp)
                        }
                    }
                }
            }
        }
    } else {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(listOf(Color(0xFF1F2937), Color(0xFF111827)))),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(8.dp)
            ) {
                Icon(Icons.Default.Videocam, null, tint = Color(0xFF34D399), modifier = Modifier.size(26.dp))
                Spacer(Modifier.height(4.dp))
                Text(
                    "${camera.name} (${camera.model})",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color(0xFFD1D5DB),
                    textAlign = TextAlign.Center
                )
                Text("Demo / Offline režim", fontSize = 9.sp, color = Color(0xFF9CA3AF))
            }
        }
    }
}

@Composable
fun FullscreenCameraOverlay(
    camera: CameraUiState,
    onClose: () -> Unit,
    onToggleMute: () -> Unit,
    onPtzMove: (String) -> Unit,
    onToggleSpotlight: () -> Unit,
    onToggleSiren: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val context = LocalContext.current
    val activity = context as? Activity
    var isLandscape by remember { mutableStateOf(false) }
    var showHud by remember { mutableStateOf(true) }

    BackHandler { onClose() }

    DisposableEffect(isLandscape) {
        activity?.requestedOrientation = if (isLandscape) {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
        onDispose {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) {
                detectTapGestures { showHud = !showHud }
            }
    ) {
        CameraVideoSurface(camera = camera)

        if (showHud) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
                    .background(Color.Black.copy(alpha = 0.65f))
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onClose) {
                        Icon(Icons.Default.FullscreenExit, contentDescription = "Zavřít", tint = Color.White)
                    }
                    Column {
                        Text(camera.name, fontWeight = FontWeight.Bold, color = Color.White)
                        Text("${camera.model} • 1080p MAIN", fontSize = 11.sp, color = Color(0xFF34D399))
                    }
                }

                FilterChip(
                    selected = isLandscape,
                    onClick = { isLandscape = !isLandscape },
                    label = { Text(if (isLandscape) "Na výšku" else "Na šířku") },
                    leadingIcon = { Icon(Icons.Default.ScreenRotation, null, modifier = Modifier.size(16.dp)) }
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .background(Color.Black.copy(alpha = 0.65f))
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                IconButton(
                    onClick = onToggleMute,
                    modifier = Modifier.size(44.dp).clip(CircleShape).background(Color(0xFF272A32))
                ) {
                    Icon(
                        if (camera.muted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                        null,
                        tint = Color.White
                    )
                }

                if (camera.supportsPtz) {
                    PtzDpadControl(onMove = onPtzMove, large = true)
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        IconButton(
                            onClick = onToggleSpotlight,
                            modifier = Modifier
                                .size(46.dp)
                                .clip(CircleShape)
                                .background(if (camera.spotlightOn) Color(0xFFFBBF24) else Color(0xFF272A32))
                        ) {
                            Icon(Icons.Default.Lightbulb, null, tint = if (camera.spotlightOn) Color.Black else Color.White)
                        }
                        IconButton(
                            onClick = onToggleSiren,
                            modifier = Modifier
                                .size(46.dp)
                                .clip(CircleShape)
                                .background(if (camera.sirenActive) Color(0xFFDC2626) else Color(0xFF272A32))
                        ) {
                            Icon(Icons.Default.Warning, null, tint = Color.White)
                        }
                    }
                }

                IconButton(
                    onClick = onOpenSettings,
                    modifier = Modifier.size(44.dp).clip(CircleShape).background(Color(0xFF272A32))
                ) {
                    Icon(Icons.Default.Settings, null, tint = Color.White)
                }
            }
        }
    }
}

@Composable
fun CameraSettingsDialog(
    camera: CameraUiState,
    onDismiss: () -> Unit,
    onUpdateCamera: (CameraUiState, String, String) -> Unit,
    onTriggerCommand: (String) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${camera.name} (${camera.model})") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Soukromý režim")
                    Switch(
                        checked = camera.privacyMode,
                        onCheckedChange = {
                            onUpdateCamera(camera.copy(privacyMode = it), "privacy_mode", it.toString())
                        }
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Detekce pohybu")
                    Switch(
                        checked = camera.motionEnabled,
                        onCheckedChange = {
                            onUpdateCamera(camera.copy(motionEnabled = it), "motion_enable", it.toString())
                        }
                    )
                }

                if (camera.motionEnabled) {
                    Text("Citlivost detekce: ${camera.motionSensitivity} %", fontSize = 13.sp)
                    Slider(
                        value = camera.motionSensitivity.toFloat(),
                        onValueChange = {
                            onUpdateCamera(camera.copy(motionSensitivity = it.toInt()), "motion_sensitivity", it.toInt().toString())
                        },
                        valueRange = 1f..100f
                    )
                }

                Text("Noční vidění (IR)", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("AUTO", "ON", "OFF").forEach { mode ->
                        FilterChip(
                            selected = camera.nightVision == mode,
                            onClick = { onUpdateCamera(camera.copy(nightVision = mode), "ir_mode", mode) },
                            label = { Text(mode) }
                        )
                    }
                }

                if (camera.supportsPtz) {
                    OutlinedButton(
                        onClick = { onTriggerCommand("ptz_calibrate") },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Kalibrovat dorazy motoru (PTZ)")
                    }
                }

                OutlinedButton(
                    onClick = { onTriggerCommand("reboot") },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Restartovat kameru na dálku")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Hotovo") }
        }
    )
}

@Composable
fun NotificationsDialog(
    onDismiss: () -> Unit,
    onTestPush: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Události a Push notifikace") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Služba na pozadí (WorkManager) automaticky kontroluje události z mydlink Event API a odesílá systémová upozornění na pohyb i alarm.",
                    fontSize = 13.sp
                )
                Button(onClick = onTestPush, modifier = Modifier.fillMaxWidth()) {
                    Text("Odeslat testovací Push notifikaci")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Zavřít") }
        }
    )
}

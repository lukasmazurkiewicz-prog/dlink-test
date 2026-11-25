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
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
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
import androidx.core.app.NotificationCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.work.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import okhttp3.logging.HttpLoggingInterceptor
import org.json.JSONArray
import org.json.JSONObject
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.*
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

// ============================================================================
// 1. DATOVÉ MODELY A MYDLINK API (Retrofit)
// ============================================================================

data class AuthResponse(
    val access_token: String,
    val refresh_token: String?,
    val expires_in: Long?,
    val api_site: String?
)

data class StreamRequest(
    val mydlink_id: String,
    val profile: String = "sub"
)

data class StreamKeepAliveRequest(
    val mydlink_id: String,
    val stream_id: String
)

data class DeviceCommandRequest(
    val mydlink_id: String,
    val command: String,
    val params: Map<String, String> = emptyMap()
)

data class DeviceSettingsRequest(
    val mydlink_id: String,
    val key: String,
    val value: String
)

data class EventListResponse(
    val data: List<CameraEventDto>?
)

data class CameraEventDto(
    val event_id: String,
    val mydlink_id: String,
    val device_name: String,
    val event_type: String,
    val timestamp: Long
)

data class BaseResponse(
    val status: String?
)

interface MydlinkApi {
    @FormUrlEncoded
    @POST
    suspend fun loginAtUrl(
        @Url loginUrl: String,
        @Field("client_id") clientId: String = "mydlinkuap",
        @Field("grant_type") grantType: String = "password",
        @Field("username") email: String,
        @Field("password") password: String
    ): AuthResponse

    @GET
    suspend fun getRawJson(
        @Url fullUrl: String,
        @Header("Authorization") bearerToken: String
    ): ResponseBody

    @POST
    suspend fun postRawJson(
        @Url fullUrl: String,
        @Header("Authorization") bearerToken: String,
        @Body request: StreamRequest
    ): ResponseBody

    @POST
    suspend fun keepStreamAlive(
        @Url fullUrl: String,
        @Header("Authorization") bearerToken: String,
        @Body request: StreamKeepAliveRequest
    ): BaseResponse

    @POST
    suspend fun sendCommand(
        @Url fullUrl: String,
        @Header("Authorization") bearerToken: String,
        @Body request: DeviceCommandRequest
    ): BaseResponse

    @POST
    suspend fun updateSetting(
        @Url fullUrl: String,
        @Header("Authorization") bearerToken: String,
        @Body request: DeviceSettingsRequest
    ): BaseResponse

    @GET
    suspend fun getEvents(
        @Url fullUrl: String,
        @Header("Authorization") bearerToken: String,
        @Query("limit") limit: Int = 20
    ): EventListResponse
}

data class LoadCamerasResult(
    val cameras: List<CameraUiState>,
    val diagnosticLog: String,
    val usedWebFallback: Boolean
)

object MydlinkClient {
    const val DEFAULT_EU_SITE = "https://mp-eu-openapi.auto.mydlink.com"
    const val MYDLINK_WEB_PORTAL_URL = "https://my.dlink.com/"

    val api: MydlinkApi by lazy {
        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BASIC
        }
        val client = OkHttpClient.Builder()
            .addInterceptor(logging)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()

        Retrofit.Builder()
            .baseUrl("https://api.auto.mydlink.com/")
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(MydlinkApi::class.java)
    }

    fun normalizeSiteUrl(rawSite: String?): String {
        val cleaned = rawSite?.trim()?.removeSuffix("/")
        if (cleaned.isNullOrEmpty() || cleaned.contains("my.dlink.com")) return DEFAULT_EU_SITE
        return if (cleaned.startsWith("http")) cleaned else "https://$cleaned"
    }

    fun md5(input: String): String {
        val bytes = MessageDigest.getInstance("MD5").digest(input.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }

    suspend fun loadCamerasWithToken(token: String, siteUrl: String): LoadCamerasResult {
        val log = StringBuilder()
        val candidateSites = listOf(
            siteUrl,
            "https://mp-eu-openapi.auto.mydlink.com",
            "https://mp-openapi.auto.mydlink.com"
        ).distinct()

        val candidatePaths = listOf(
            "/me/device/list",
            "/v1/me/device/list",
            "/v2/me/device/list"
        )

        var deviceArray: JSONArray? = null
        var workingBase = siteUrl

        for (base in candidateSites) {
            for (path in candidatePaths) {
                val url = "$base$path"
                try {
                    val raw = api.getRawJson(url, "Bearer $token").string()
                    val root = JSONObject(raw)
                    val arr = root.optJSONArray("data")
                        ?: root.optJSONObject("data")?.optJSONArray("devices")
                        ?: root.optJSONArray("devices")
                    if (arr != null && arr.length() > 0) {
                        deviceArray = arr
                        workingBase = base
                        log.append("OK: $url (${arr.length()} kamer)\n")
                        break
                    } else {
                        log.append("Prázdné data z $url: ${raw.take(120)}\n")
                    }
                } catch (e: Exception) {
                    log.append("Chyba $url -> ${e.message}\n")
                }
            }
            if (deviceArray != null) break
        }

        if (deviceArray == null) {
            return LoadCamerasResult(
                cameras = defaultUserCameras(useWebStream = true),
                diagnosticLog = log.toString(),
                usedWebFallback = true
            )
        }

        val resultList = mutableListOf<CameraUiState>()
        for (i in 0 until deviceArray.length()) {
            val item = deviceArray.getJSONObject(i)
            val id = item.optString("mydlink_id").ifEmpty { item.optString("device_id") }
            val name = item.optString("device_name").ifEmpty { item.optString("name", "Kamera ${i + 1}") }
            val model = item.optString("model", "DCS-LH")
            val online = item.optBoolean("online", true)

            var streamUrl: String? = null
            var streamId: String? = null
            var streamNote = ""

            val streamPaths = listOf(
                "$workingBase/device/stream/start",
                "$workingBase/v1/device/stream/start",
                "$workingBase/device/live/start"
            )

            for (sUrl in streamPaths) {
                try {
                    val sRaw = api.postRawJson(sUrl, "Bearer $token", StreamRequest(id, "sub")).string()
                    val sObj = JSONObject(sRaw)
                    val dataObj = sObj.optJSONObject("data") ?: sObj
                    val foundUrl = dataObj.optString("url")
                        .ifEmpty { dataObj.optString("flv_url") }
                        .ifEmpty { dataObj.optString("hls_url") }
                        .ifEmpty { dataObj.optString("rtsp_url") }
                    streamId = dataObj.optString("stream_id").ifEmpty { null }
                    if (foundUrl.isNotEmpty()) {
                        streamUrl = foundUrl
                        break
                    } else {
                        streamNote = sRaw.take(160)
                    }
                } catch (e: Exception) {
                    streamNote = e.message ?: "Chyba streamu"
                }
            }

            if (streamUrl == null) {
                log.append("Stream $name ($model): přepnuto na WebRTC portál ($streamNote)\n")
            }

            resultList.add(
                CameraUiState(
                    id = id,
                    name = name,
                    model = model,
                    isOnline = online,
                    streamUrl = streamUrl,
                    streamId = streamId,
                    useWebPortalPlayer = (streamUrl == null),
                    streamStatusNote = streamNote
                )
            )
        }

        return LoadCamerasResult(
            cameras = resultList,
            diagnosticLog = log.toString(),
            usedWebFallback = resultList.any { it.useWebPortalPlayer }
        )
    }
}

// ============================================================================
// 2. APLIKAČNÍ STAV KAMER A UDÁLOSTÍ
// ============================================================================

data class CameraUiState(
    val id: String,
    val name: String,
    val model: String,
    val isOnline: Boolean = true,
    val streamUrl: String? = null,
    val streamId: String? = null,
    val useWebPortalPlayer: Boolean = false,
    val streamStatusNote: String = "",
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

enum class GridMode(val label: String, val columns: Int, val maxCount: Int) {
    SINGLE("1x1", 1, 1),
    DUAL("2x1", 1, 2),
    QUAD("2x2", 2, 4)
}

fun defaultUserCameras(useWebStream: Boolean = false): List<CameraUiState> = listOf(
    CameraUiState(id = "dcs-6500lh-1", name = "Obývák", model = "DCS-6500LH", useWebPortalPlayer = useWebStream),
    CameraUiState(id = "dcs-8620lh-1", name = "Zahrada", model = "DCS-8620LH", motionSensitivity = 85, useWebPortalPlayer = useWebStream),
    CameraUiState(id = "dcs-6501lh-1", name = "Chodba", model = "DCS-6501LH", motionSensitivity = 60, useWebPortalPlayer = useWebStream),
    CameraUiState(id = "dcs-6501lh-2", name = "Garáž", model = "DCS-6501LH", nightVision = "ON", useWebPortalPlayer = useWebStream)
)

// ============================================================================
// 3. WORKMANAGER PRO PUSH NOTIFIKACE NA POZADÍ
// ============================================================================

class EventCheckWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val prefs = applicationContext.getSharedPreferences("mydlink_prefs", Context.MODE_PRIVATE)
        val token = prefs.getString("access_token", null) ?: return Result.success()
        val apiSite = prefs.getString("api_site", MydlinkClient.DEFAULT_EU_SITE) ?: MydlinkClient.DEFAULT_EU_SITE
        val lastTs = prefs.getLong("last_event_ts", System.currentTimeMillis())

        return try {
            val response = MydlinkClient.api.getEvents("$apiSite/me/event/list", "Bearer $token")
            val events = response.data.orEmpty().filter { it.timestamp > lastTs }
            if (events.isNotEmpty()) {
                prefs.edit().putLong("last_event_ts", events.maxOf { it.timestamp }).apply()
                events.forEach { sendSystemNotification(applicationContext, it.event_id.hashCode(), it.device_name, it.event_type) }
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

// ============================================================================
// 4. HLAVNÍ AKTIVITA A JETPACK COMPOSE UI
// ============================================================================

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
    var apiSite by remember { mutableStateOf(prefs.getString("api_site", MydlinkClient.DEFAULT_EU_SITE) ?: MydlinkClient.DEFAULT_EU_SITE) }
    var cameras by remember { mutableStateOf(defaultUserCameras(useWebStream = true)) }
    var diagnosticLog by remember { mutableStateOf("") }
    var isRefreshingStreams by remember { mutableStateOf(false) }

    var gridMode by remember { mutableStateOf(GridMode.QUAD) }
    var selectedSingleId by remember { mutableStateOf("dcs-6500lh-1") }
    var keepAwake by remember { mutableStateOf(true) }
    var keepAliveCountdown by remember { mutableIntStateOf(45) }

    var settingsCameraId by remember { mutableStateOf<String?>(null) }
    var fullscreenCameraId by remember { mutableStateOf<String?>(null) }
    var showNotificationsSheet by remember { mutableStateOf(false) }
    var showDiagnosticDialog by remember { mutableStateOf(false) }

    val snackbarHostState = remember { SnackbarHostState() }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    // Při startu s uloženým tokenem znovu načti kamery a streamy
    LaunchedEffect(accessToken) {
        val token = accessToken
        if (token != null && token != "demo_local_token") {
            isRefreshingStreams = true
            val res = MydlinkClient.loadCamerasWithToken(token, apiSite)
            cameras = res.cameras
            diagnosticLog = res.diagnosticLog
            if (res.cameras.isNotEmpty()) {
                selectedSingleId = res.cameras.first().id
            }
            isRefreshingStreams = false
        }
    }

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

    LaunchedEffect(accessToken, apiSite) {
        if (accessToken == null) return@LaunchedEffect
        while (isActive) {
            delay(1000L)
            if (keepAliveCountdown <= 1) {
                keepAliveCountdown = 45
                val token = accessToken ?: continue
                cameras.forEach { cam ->
                    val sId = cam.streamId
                    if (sId != null) {
                        runCatching {
                            MydlinkClient.api.keepStreamAlive(
                                "$apiSite/device/stream/keepalive",
                                "Bearer $token",
                                StreamKeepAliveRequest(cam.id, sId)
                            )
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
        scope.launch {
            runCatching {
                MydlinkClient.api.sendCommand(
                    "$apiSite/device/control",
                    "Bearer $token",
                    DeviceCommandRequest(camId, command, params)
                )
            }
        }
    }

    fun sendApiSetting(camId: String, key: String, value: String) {
        val token = accessToken ?: return
        scope.launch {
            runCatching {
                MydlinkClient.api.updateSetting(
                    "$apiSite/device/setting",
                    "Bearer $token",
                    DeviceSettingsRequest(camId, key, value)
                )
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = Color(0xFF121316)
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            if (accessToken == null) {
                LoginScreen(
                    onLoginSuccess = { token, siteUrl, loadResult ->
                        prefs.edit()
                            .putString("access_token", token)
                            .putString("api_site", siteUrl)
                            .apply()
                        accessToken = token
                        apiSite = siteUrl
                        cameras = loadResult.cameras
                        diagnosticLog = loadResult.diagnosticLog
                        if (loadResult.cameras.isNotEmpty()) {
                            selectedSingleId = loadResult.cameras.first().id
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
                    isRefreshingStreams = isRefreshingStreams,
                    onGridModeChange = { gridMode = it },
                    onSelectSingleCamera = { selectedSingleId = it },
                    onToggleKeepAwake = {
                        keepAwake = !keepAwake
                        scope.launch {
                            snackbarHostState.showSnackbar(
                                if (keepAwake) "Displej i streamy zůstávají trvale zapnuté"
                                else "Standardní režim uspání displeje"
                            )
                        }
                    },
                    onRefreshStreams = {
                        val token = accessToken ?: return@DashboardScreen
                        scope.launch {
                            isRefreshingStreams = true
                            val res = MydlinkClient.loadCamerasWithToken(token, apiSite)
                            cameras = res.cameras
                            diagnosticLog = res.diagnosticLog
                            isRefreshingStreams = false
                            snackbarHostState.showSnackbar("Streamy obnoveny")
                        }
                    },
                    onShowDiagnostics = { showDiagnosticDialog = true },
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
                        prefs.edit().remove("access_token").apply()
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
                                diagnosticLog.ifEmpty { "Zatím bez záznamu. Zkuste obnovit streamy." },
                                fontSize = 11.sp,
                                color = Color(0xFFD1D5DB)
                            )
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = { showDiagnosticDialog = false }) { Text("Zavřít") }
                    }
                )
            }
        }
    }
}

// ============================================================================
// 5. PŘIHLAŠOVACÍ OBRAZOVKA + OFICIÁLNÍ MYDLINK WEB PORTÁL OAUTH2
// ============================================================================

@Composable
fun LoginScreen(
    onLoginSuccess: (String, String, LoadCamerasResult) -> Unit
) {
    val scope = rememberCoroutineScope()
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var rememberToken by remember { mutableStateOf(true) }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var showWebOAuth by remember { mutableStateOf(false) }

    if (showWebOAuth) {
        MydlinkWebOAuthDialog(
            onDismiss = { showWebOAuth = false },
            onTokenCaptured = { capturedToken, capturedSite ->
                showWebOAuth = false
                isLoading = true
                scope.launch {
                    val siteUrl = MydlinkClient.normalizeSiteUrl(capturedSite)
                    val loaded = MydlinkClient.loadCamerasWithToken(capturedToken, siteUrl)
                    isLoading = false
                    onLoginSuccess(capturedToken, siteUrl, loaded)
                }
            }
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

        Spacer(Modifier.height(22.dp))

        Button(
            onClick = { showWebOAuth = true },
            enabled = !isLoading,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(14.dp)
        ) {
            if (isLoading) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = Color.White)
            } else {
                Icon(Icons.Default.Language, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("Přihlásit se přes mydlink účet (OAuth2)", fontWeight = FontWeight.Bold)
            }
        }

        Spacer(Modifier.height(18.dp))
        HorizontalDivider(color = Color(0xFF2B2E36))
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

        Spacer(Modifier.height(10.dp))

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
                modifier = Modifier.padding(vertical = 4.dp)
            )
        }

        FilledTonalButton(
            onClick = {
                isLoading = true
                errorMessage = null
                scope.launch {
                    try {
                        val endpoints = listOf(
                            "https://api.auto.mydlink.com/oauth/token",
                            "https://mp-eu-openapi.auto.mydlink.com/oauth/token",
                            "https://api.auto.mydlink.com/oauth/authorize2"
                        )
                        var authResult: AuthResponse? = null
                        var lastErr: Exception? = null

                        for (ep in endpoints) {
                            try {
                                authResult = MydlinkClient.api.loginAtUrl(
                                    loginUrl = ep,
                                    email = email.trim(),
                                    password = MydlinkClient.md5(password)
                                )
                                break
                            } catch (ex: Exception) {
                                lastErr = ex
                            }
                        }

                        if (authResult == null) {
                            if (lastErr is HttpException && lastErr.code() == 404) {
                                isLoading = false
                                showWebOAuth = true
                                return@launch
                            }
                            throw lastErr ?: IllegalStateException("Nepodařilo se získat token")
                        }

                        val siteUrl = MydlinkClient.normalizeSiteUrl(authResult.api_site)
                        val mapped = MydlinkClient.loadCamerasWithToken(authResult.access_token, siteUrl)
                        onLoginSuccess(authResult.access_token, siteUrl, mapped)
                    } catch (e: Exception) {
                        errorMessage = "API vrátilo chybu (${e.localizedMessage}). Použijte tlačítko OAuth2 nahoře."
                    } finally {
                        isLoading = false
                    }
                }
            },
            enabled = !isLoading && email.isNotBlank() && password.isNotBlank(),
            modifier = Modifier.fillMaxWidth().height(46.dp),
            shape = RoundedCornerShape(14.dp)
        ) {
            Text("Přihlásit se heslem")
        }

        Spacer(Modifier.height(10.dp))

        OutlinedButton(
            onClick = {
                onLoginSuccess(
                    "demo_local_token",
                    MydlinkClient.DEFAULT_EU_SITE,
                    LoadCamerasResult(defaultUserCameras(useWebStream = false), "Demo režim", false)
                )
            },
            modifier = Modifier.fillMaxWidth().height(46.dp),
            shape = RoundedCornerShape(14.dp)
        ) {
            Text("Spustit v offline Demo režimu (4 kamery)")
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun MydlinkWebOAuthDialog(
    onDismiss: () -> Unit,
    onTokenCaptured: (String, String?) -> Unit
) {
    val alreadyCaptured = remember { AtomicBoolean(false) }

    fun tryExtractTokenFromUrl(urlStr: String): Boolean {
        if (alreadyCaptured.get()) return true
        if (urlStr.contains("access_token=")) {
            val normalized = urlStr.replace("#", "?")
            val uri = Uri.parse(normalized)
            val token = uri.getQueryParameter("access_token")
            val site = uri.getQueryParameter("api_site")
            if (!token.isNullOrBlank() && alreadyCaptured.compareAndSet(false, true)) {
                onTokenCaptured(token, site)
                return true
            }
        }
        return false
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Přihlášení k mydlink účtu", fontSize = 16.sp) },
        text = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(450.dp)
                    .clip(RoundedCornerShape(12.dp))
            ) {
                AndroidView(
                    factory = { ctx ->
                        WebView(ctx).apply {
                            CookieManager.getInstance().setAcceptCookie(true)
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.userAgentString =
                                "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

                            addJavascriptInterface(object {
                                @JavascriptInterface
                                fun onAuthData(token: String?, apiSite: String?) {
                                    if (!token.isNullOrBlank() && alreadyCaptured.compareAndSet(false, true)) {
                                        post { onTokenCaptured(token, apiSite) }
                                    }
                                }
                            }, "AndroidOAuthBridge")

                            webViewClient = object : WebViewClient() {
                                override fun shouldOverrideUrlLoading(
                                    view: WebView?,
                                    request: WebResourceRequest?
                                ): Boolean {
                                    val urlStr = request?.url?.toString() ?: return false
                                    return tryExtractTokenFromUrl(urlStr)
                                }

                                override fun shouldInterceptRequest(
                                    view: WebView?,
                                    request: WebResourceRequest?
                                ): WebResourceResponse? {
                                    val reqUrl = request?.url?.toString() ?: return null
                                    val authHeader = request.requestHeaders?.get("Authorization")
                                        ?: request.requestHeaders?.get("authorization")
                                    if (!authHeader.isNullOrBlank() && authHeader.startsWith("Bearer ", ignoreCase = true)) {
                                        val token = authHeader.substring(7).trim()
                                        val host = request.url?.host
                                        if (token.length > 10 && alreadyCaptured.compareAndSet(false, true)) {
                                            view?.post { onTokenCaptured(token, host) }
                                        }
                                    }
                                    tryExtractTokenFromUrl(reqUrl)
                                    return super.shouldInterceptRequest(view, request)
                                }

                                override fun onPageFinished(view: WebView?, url: String?) {
                                    super.onPageFinished(view, url)
                                    if (url != null && tryExtractTokenFromUrl(url)) return

                                    val hookScript = """
                                        (function() {
                                            function scanStorage() {
                                                try {
                                                    for (var i = 0; i < localStorage.length; i++) {
                                                        var k = localStorage.key(i);
                                                        var v = localStorage.getItem(k);
                                                        if (v && v.indexOf('access_token') !== -1) {
                                                            var obj = JSON.parse(v);
                                                            if (obj && obj.access_token) {
                                                                AndroidOAuthBridge.onAuthData(obj.access_token, obj.api_site || null);
                                                            }
                                                        }
                                                        if (k && k.toLowerCase().indexOf('token') !== -1 && v && v.length > 20 && v.indexOf('{') === -1) {
                                                            AndroidOAuthBridge.onAuthData(v, localStorage.getItem('api_site') || null);
                                                        }
                                                    }
                                                } catch(e) {}
                                            }
                                            scanStorage();
                                            setInterval(scanStorage, 1000);
                                        })();
                                    """.trimIndent()
                                    view?.evaluateJavascript(hookScript, null)
                                }
                            }
                            loadUrl(MydlinkClient.MYDLINK_WEB_PORTAL_URL)
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Zrušit") }
        }
    )
}

// ============================================================================
// 6. HLAVNÍ DASHBOARD S MŘÍŽKOU 1x1, 2x1 A 2x2
// ============================================================================

@Composable
fun DashboardScreen(
    cameras: List<CameraUiState>,
    gridMode: GridMode,
    selectedSingleId: String,
    keepAwake: Boolean,
    keepAliveSeconds: Int,
    isRefreshingStreams: Boolean,
    onGridModeChange: (GridMode) -> Unit,
    onSelectSingleCamera: (String) -> Unit,
    onToggleKeepAwake: () -> Unit,
    onRefreshStreams: () -> Unit,
    onShowDiagnostics: () -> Unit,
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
                Text("Moje Kamery", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White)
                Text(
                    if (isRefreshingStreams) "Načítám streamy..." else "Ping za ${keepAliveSeconds}s",
                    fontSize = 11.sp,
                    color = Color(0xFF34D399)
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                IconButton(onClick = onRefreshStreams) {
                    Icon(Icons.Default.Refresh, contentDescription = "Obnovit obraz", tint = Color(0xFF34D399))
                }

                IconButton(onClick = onShowDiagnostics) {
                    Icon(Icons.Default.BugReport, contentDescription = "Diagnostika", tint = Color(0xFF9CA3AF))
                }

                IconButton(onClick = onOpenNotifications) {
                    Icon(Icons.Default.Notifications, contentDescription = "Události", tint = Color.White)
                }

                FilterChip(
                    selected = keepAwake,
                    onClick = onToggleKeepAwake,
                    label = { Text("Zapnuto", fontSize = 11.sp) },
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
                CameraTileCard(
                    camera = camera,
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
            CameraVideoSurface(camera = camera, onRefreshStream = onRefreshStream)

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
    onRefreshStream: () -> Unit = {}
) {
    val context = LocalContext.current
    var showWebPlayer by remember(camera.id, camera.useWebPortalPlayer) {
        mutableStateOf(camera.useWebPortalPlayer)
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
    } else if (showWebPlayer) {
        // Vestavěný WebRTC přehrávač využívající přihlášenou relaci my.dlink.com
        AndroidView(
            factory = { ctx ->
                WebView(ctx).apply {
                    CookieManager.getInstance().setAcceptCookie(true)
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.mediaPlaybackRequiresUserGesture = false
                    webViewClient = WebViewClient()
                    loadUrl(MydlinkClient.MYDLINK_WEB_PORTAL_URL)
                }
            },
            modifier = Modifier.fillMaxSize()
        )
    } else {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(listOf(Color(0xFF1F2937), Color(0xFF111827))))
                .padding(8.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Default.Videocam, null, tint = Color(0xFF34D399), modifier = Modifier.size(26.dp))
                Spacer(Modifier.height(4.dp))
                Text("Stream připraven (${camera.model})", fontSize = 11.sp, color = Color(0xFFD1D5DB))
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilledTonalButton(
                        onClick = { showWebPlayer = true },
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                        modifier = Modifier.height(28.dp)
                    ) {
                        Icon(Icons.Default.PlayArrow, null, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Spustit obraz", fontSize = 10.sp)
                    }
                    OutlinedButton(
                        onClick = onRefreshStream,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        modifier = Modifier.height(28.dp)
                    ) {
                        Text("API", fontSize = 10.sp)
                    }
                }
                if (camera.streamStatusNote.isNotEmpty()) {
                    Text(
                        camera.streamStatusNote,
                        fontSize = 8.sp,
                        color = Color(0xFF9CA3AF),
                        maxLines = 2,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        }
    }
}

// ============================================================================
// 7. CELÁ OBRAZOVKA (FULL-SCREEN) PRO VYBRANOU KAMERU
// ============================================================================

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

// ============================================================================
// 8. NASTAVENÍ KAMERY A PŘEHLED NOTIFIKACÍ
// ============================================================================

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

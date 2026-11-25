/*
 * Copyright (C) 2026 Salman Bashir (8002Keys)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dev.patrickgold.florisboard.app.update

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SystemUpdateAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import dev.patrickgold.florisboard.BuildConfig
import dev.patrickgold.florisboard.app.FlorisAppActivity
import dev.patrickgold.florisboard.ime.ai.AiMemoryVault
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class AppUpdateInfo(
    val installedVersionName: String,
    val installedVersionCode: Int,
    val installedCommit: String,
    val remoteVersionName: String,
    val remoteVersionCode: Int,
    val remoteCommit: String,
    val remoteShortCommit: String,
    val commitMessage: String,
    val publishedAt: String,
    val apkSha256: String,
    val apkSizeBytes: Long,
    val downloadUrl: String,
    val releasePageUrl: String,
    val isUpdateAvailable: Boolean,
) {
    val installedShortCommit: String
        get() = installedCommit.trim().take(7).ifBlank {
            installedVersionName.substringAfter("+", "local").take(7)
        }

    val formattedSizeMb: String
        get() = if (apkSizeBytes > 0L) {
            String.format(java.util.Locale.US, "%.1f MB", apkSizeBytes.toDouble() / (1024.0 * 1024.0))
        } else {
            "~64 MB"
        }
}

object AppUpdateManager {
    const val BUILD_INFO_URL =
        "https://github.com/salmanbashir80/8002keys/releases/download/v1.9.70-debug.1/build-info.json"
    const val GITHUB_RELEASE_API_URL =
        "https://api.github.com/repos/salmanbashir80/8002keys/releases/tags/v1.9.70-debug.1"
    const val DEFAULT_APK_DOWNLOAD_URL =
        "https://github.com/salmanbashir80/8002keys/releases/download/v1.9.70-debug.1/8002Keys-v1.9.70-debug.apk"
    const val DEFAULT_RELEASE_PAGE_URL =
        "https://github.com/salmanbashir80/8002keys/releases/tag/v1.9.70-debug.1"

    private const val AUTO_CHECK_INTERVAL_MS = 2 * 60 * 1000L // 2 minutes

    private val managerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _latestUpdateFlow = MutableStateFlow<AppUpdateInfo?>(null)
    val latestUpdateFlow: StateFlow<AppUpdateInfo?> = _latestUpdateFlow.asStateFlow()

    private val _isCheckingFlow = MutableStateFlow(false)
    val isCheckingFlow: StateFlow<Boolean> = _isCheckingFlow.asStateFlow()

    private val _showDialogFlow = MutableStateFlow(false)
    val showDialogFlow: StateFlow<Boolean> = _showDialogFlow.asStateFlow()

    private val _downloadProgressFlow = MutableStateFlow<Int?>(null)
    val downloadProgressFlow: StateFlow<Int?> = _downloadProgressFlow.asStateFlow()

    private val _statusMessageFlow = MutableStateFlow<String?>(null)
    val statusMessageFlow: StateFlow<String?> = _statusMessageFlow.asStateFlow()

    @Volatile
    private var lastCheckEpochMs: Long = 0L

    @Volatile
    private var snoozedCommitHash: String? = null

    fun installedShortCommit(): String {
        val raw = BuildConfig.BUILD_COMMIT_HASH.trim()
        if (raw.isNotEmpty() && raw != "null") {
            return raw.take(7)
        }
        return BuildConfig.VERSION_NAME.substringAfter("+", "local").take(7)
    }

    fun openUpdateDialog() {
        _showDialogFlow.value = true
    }

    fun dismissUpdateDialog(snoozeCurrentCommit: Boolean = true) {
        if (snoozeCurrentCommit) {
            _latestUpdateFlow.value?.let { info ->
                snoozedCommitHash = info.remoteShortCommit.ifBlank { info.remoteCommit }
            }
        }
        _showDialogFlow.value = false
    }

    fun triggerBackgroundCheck(force: Boolean = false, autoPopupIfAvailable: Boolean = true) {
        val now = System.currentTimeMillis()
        if (!force && (now - lastCheckEpochMs) < AUTO_CHECK_INTERVAL_MS && _latestUpdateFlow.value != null) {
            val cached = _latestUpdateFlow.value
            if (autoPopupIfAvailable && cached?.isUpdateAvailable == true) {
                val commitKey = cached.remoteShortCommit.ifBlank { cached.remoteCommit }
                if (commitKey != snoozedCommitHash) {
                    _showDialogFlow.value = true
                }
            }
            return
        }
        if (_isCheckingFlow.value) return
        managerScope.launch {
            checkForUpdates(force = force, autoPopupIfAvailable = autoPopupIfAvailable)
        }
    }

    suspend fun checkForUpdates(
        force: Boolean = false,
        autoPopupIfAvailable: Boolean = true,
    ): AppUpdateInfo? = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        if (!force && (now - lastCheckEpochMs) < AUTO_CHECK_INTERVAL_MS && _latestUpdateFlow.value != null) {
            return@withContext _latestUpdateFlow.value
        }
        _isCheckingFlow.value = true
        try {
            val installedCommit = BuildConfig.BUILD_COMMIT_HASH.trim()
            val installedVersionCode = BuildConfig.VERSION_CODE
            val installedVersionName = BuildConfig.VERSION_NAME

            val fromBuildInfo = runCatching {
                val raw = fetchUrlText("$BUILD_INFO_URL?t=$now")
                parseBuildInfoJson(
                    jsonText = raw,
                    installedCommit = installedCommit,
                    installedVersionCode = installedVersionCode,
                    installedVersionName = installedVersionName,
                )
            }.getOrNull()

            val info = fromBuildInfo ?: runCatching {
                val raw = fetchUrlText("$GITHUB_RELEASE_API_URL?t=$now")
                parseGitHubReleaseJson(
                    jsonText = raw,
                    installedCommit = installedCommit,
                    installedVersionCode = installedVersionCode,
                    installedVersionName = installedVersionName,
                )
            }.getOrNull()

            if (info != null) {
                lastCheckEpochMs = System.currentTimeMillis()
                _latestUpdateFlow.value = info
                if (info.isUpdateAvailable) {
                    _statusMessageFlow.value =
                        "New update available: ${info.remoteVersionName} (${info.remoteShortCommit})"
                    val commitKey = info.remoteShortCommit.ifBlank { info.remoteCommit }
                    if (autoPopupIfAvailable && (force || commitKey != snoozedCommitHash)) {
                        _showDialogFlow.value = true
                    }
                } else {
                    _statusMessageFlow.value =
                        "Up to date (${info.installedVersionName})"
                    if (force) {
                        _showDialogFlow.value = false
                    }
                }
            } else if (force) {
                _statusMessageFlow.value = "Could not reach GitHub update server. Check connection."
            }
            info
        } finally {
            _isCheckingFlow.value = false
        }
    }

    fun isCommitNewerOrDifferent(installedCommit: String, remoteCommit: String): Boolean {
        val cleanInstalled = installedCommit.trim().lowercase()
        val cleanRemote = remoteCommit.trim().lowercase()
        if (cleanRemote.isBlank() || cleanRemote == "null") return false
        if (cleanInstalled.isBlank() || cleanInstalled == "null") return true
        if (cleanInstalled == cleanRemote) return false
        if (cleanInstalled.startsWith(cleanRemote) || cleanRemote.startsWith(cleanInstalled)) {
            return false
        }
        return true
    }

    fun parseBuildInfoJson(
        jsonText: String,
        installedCommit: String,
        installedVersionCode: Int,
        installedVersionName: String,
    ): AppUpdateInfo? {
        if (jsonText.isBlank()) return null
        val obj = runCatching { JSONObject(jsonText) }.getOrNull() ?: return null
        val remoteVersionName = obj.optString("versionName", "").trim()
        val remoteVersionCode = obj.optInt("versionCode", installedVersionCode)
        val remoteCommit = obj.optString("commit", "").trim()
        val remoteShortCommit = obj.optString("shortCommit", "").trim().ifBlank {
            remoteCommit.take(7).ifBlank {
                remoteVersionName.substringAfter("+", "").take(7)
            }
        }
        val commitMessage = obj.optString("commitMessage", "").trim().ifBlank {
            "Latest 8002Keys cloud build from commit $remoteShortCommit"
        }
        val publishedAt = obj.optString("publishedAt", "").trim()
        val apkSha256 = obj.optString("apkSha256", "").trim().lowercase()
        val apkSizeBytes = obj.optLong("apkSizeBytes", 0L)
        val downloadUrl = obj.optString("downloadUrl", "").trim().ifBlank { DEFAULT_APK_DOWNLOAD_URL }
        val releasePageUrl = obj.optString("releasePageUrl", "").trim().ifBlank { DEFAULT_RELEASE_PAGE_URL }

        val effectiveInstalledCommit = installedCommit.trim().ifBlank {
            installedVersionName.substringAfter("+", "")
        }
        val effectiveRemoteCommit = remoteCommit.ifBlank { remoteShortCommit }

        val isDifferentCommit = isCommitNewerOrDifferent(effectiveInstalledCommit, effectiveRemoteCommit)
        val isHigherVersionCode = remoteVersionCode > installedVersionCode
        val isUpdateAvailable = isHigherVersionCode || isDifferentCommit

        return AppUpdateInfo(
            installedVersionName = installedVersionName,
            installedVersionCode = installedVersionCode,
            installedCommit = installedCommit,
            remoteVersionName = remoteVersionName.ifBlank { "1.9.70-debug+$remoteShortCommit" },
            remoteVersionCode = remoteVersionCode,
            remoteCommit = remoteCommit,
            remoteShortCommit = remoteShortCommit,
            commitMessage = commitMessage,
            publishedAt = publishedAt,
            apkSha256 = apkSha256,
            apkSizeBytes = apkSizeBytes,
            downloadUrl = downloadUrl,
            releasePageUrl = releasePageUrl,
            isUpdateAvailable = isUpdateAvailable,
        )
    }

    fun parseGitHubReleaseJson(
        jsonText: String,
        installedCommit: String,
        installedVersionCode: Int,
        installedVersionName: String,
    ): AppUpdateInfo? {
        if (jsonText.isBlank()) return null
        val obj = runCatching {
            val trimmed = jsonText.trim()
            if (trimmed.startsWith("[")) {
                JSONArray(trimmed).optJSONObject(0)
            } else {
                JSONObject(trimmed)
            }
        }.getOrNull() ?: return null

        val title = obj.optString("name", "").trim()
        val body = obj.optString("body", "").trim()
        val publishedAt = obj.optString("published_at", "").trim()
        val targetCommitish = obj.optString("target_commitish", "").trim()

        val titleShortSha = Regex("\\+([0-9a-fA-F]{7,40})").find(title)?.groupValues?.getOrNull(1).orEmpty()
        val bodyShortSha = Regex("built from `([0-9a-fA-F]{7,40})`").find(body)?.groupValues?.getOrNull(1).orEmpty()
        val remoteShortCommit = titleShortSha.ifBlank {
            bodyShortSha.ifBlank { targetCommitish.take(7) }
        }
        val remoteCommit = if (targetCommitish.length >= 7 && !targetCommitish.equals("main", ignoreCase = true)) {
            targetCommitish
        } else {
            remoteShortCommit
        }

        var downloadUrl = DEFAULT_APK_DOWNLOAD_URL
        var apkSizeBytes = 0L
        var apkSha256 = ""
        val assets = obj.optJSONArray("assets")
        if (assets != null) {
            for (i in 0 until assets.length()) {
                val asset = assets.optJSONObject(i) ?: continue
                val name = asset.optString("name", "")
                if (name.endsWith(".apk", ignoreCase = true)) {
                    downloadUrl = asset.optString("browser_download_url", DEFAULT_APK_DOWNLOAD_URL)
                    apkSizeBytes = asset.optLong("size", 0L)
                    val digest = asset.optString("digest", "")
                    if (digest.startsWith("sha256:", ignoreCase = true)) {
                        apkSha256 = digest.substringAfter(":").trim().lowercase()
                    }
                    break
                }
            }
        }

        val effectiveInstalledCommit = installedCommit.trim().ifBlank {
            installedVersionName.substringAfter("+", "")
        }
        val isUpdateAvailable = isCommitNewerOrDifferent(effectiveInstalledCommit, remoteCommit)

        return AppUpdateInfo(
            installedVersionName = installedVersionName,
            installedVersionCode = installedVersionCode,
            installedCommit = installedCommit,
            remoteVersionName = if (remoteShortCommit.isNotBlank()) {
                "1.9.70-debug+$remoteShortCommit"
            } else {
                title.ifBlank { installedVersionName }
            },
            remoteVersionCode = installedVersionCode,
            remoteCommit = remoteCommit,
            remoteShortCommit = remoteShortCommit,
            commitMessage = body.ifBlank { title.ifBlank { "New 8002Keys GitHub release available" } },
            publishedAt = publishedAt,
            apkSha256 = apkSha256,
            apkSizeBytes = apkSizeBytes,
            downloadUrl = downloadUrl,
            releasePageUrl = obj.optString("html_url", DEFAULT_RELEASE_PAGE_URL),
            isUpdateAvailable = isUpdateAvailable,
        )
    }

    private fun fetchUrlText(urlStr: String): String {
        var currentUrl = urlStr
        var redirects = 0
        while (redirects < 6) {
            val conn = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 12_000
                readTimeout = 15_000
                instanceFollowRedirects = true
                setRequestProperty("Accept", "application/json, text/plain, */*")
                setRequestProperty("Cache-Control", "no-cache")
                setRequestProperty("User-Agent", "8002Keys-AutoUpdater/${BuildConfig.VERSION_NAME}")
            }
            val code = conn.responseCode
            if (code in 300..399) {
                val location = conn.getHeaderField("Location") ?: break
                conn.disconnect()
                currentUrl = location
                redirects++
                continue
            }
            if (code in 200..299) {
                return conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            }
            conn.disconnect()
            throw IllegalStateException("HTTP $code from $currentUrl")
        }
        throw IllegalStateException("Too many redirects for $urlStr")
    }

    suspend fun downloadAndInstallUpdate(
        context: Context,
        info: AppUpdateInfo,
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            _downloadProgressFlow.value = 0
            _statusMessageFlow.value = "Saving AI accounts & memory vault before update..."
            runCatching { AiMemoryVault.saveToPersistentStorageNow(context) }

            val updatesDir = File(context.cacheDir, "app-updates").apply { mkdirs() }
            val targetApk = File(updatesDir, "8002Keys-update.apk")
            if (targetApk.exists()) {
                targetApk.delete()
            }

            var currentUrl = info.downloadUrl.ifBlank { DEFAULT_APK_DOWNLOAD_URL }
            var redirects = 0
            var connection: HttpURLConnection? = null
            while (redirects < 6) {
                val conn = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 15_000
                    readTimeout = 60_000
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", "8002Keys-AutoUpdater/${BuildConfig.VERSION_NAME}")
                }
                val code = conn.responseCode
                if (code in 300..399) {
                    val location = conn.getHeaderField("Location")
                        ?: throw IllegalStateException("Redirect missing Location header")
                    conn.disconnect()
                    currentUrl = location
                    redirects++
                    continue
                }
                if (code !in 200..299) {
                    conn.disconnect()
                    throw IllegalStateException("Download failed with HTTP $code")
                }
                connection = conn
                break
            }

            val activeConn = connection ?: throw IllegalStateException("Could not open APK download stream")
            val totalBytes = activeConn.contentLengthLong.takeIf { it > 0L } ?: info.apkSizeBytes.takeIf { it > 0L } ?: -1L
            val digest = MessageDigest.getInstance("SHA-256")

            BufferedInputStream(activeConn.inputStream).use { input ->
                FileOutputStream(targetApk).use { output ->
                    val buffer = ByteArray(32 * 1024)
                    var downloaded = 0L
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                        digest.update(buffer, 0, read)
                        downloaded += read
                        if (totalBytes > 0L) {
                            val pct = ((downloaded * 100L) / totalBytes).toInt().coerceIn(1, 99)
                            _downloadProgressFlow.value = pct
                            val mbDone = String.format(java.util.Locale.US, "%.1f", downloaded / (1024.0 * 1024.0))
                            val mbTotal = String.format(java.util.Locale.US, "%.1f", totalBytes / (1024.0 * 1024.0))
                            _statusMessageFlow.value = "Downloading update: $pct% ($mbDone MB / $mbTotal MB)"
                        } else {
                            val mbDone = String.format(java.util.Locale.US, "%.1f", downloaded / (1024.0 * 1024.0))
                            _statusMessageFlow.value = "Downloading update: $mbDone MB..."
                        }
                    }
                    output.flush()
                }
            }
            activeConn.disconnect()

            val computedSha256 = digest.digest().joinToString("") { "%02x".format(it) }
            if (info.apkSha256.length == 64 && !computedSha256.equals(info.apkSha256, ignoreCase = true)) {
                targetApk.delete()
                throw IllegalStateException("APK SHA-256 mismatch (expected ${info.apkSha256.take(10)}..., got ${computedSha256.take(10)}...)")
            }

            // Also copy the verified APK into Public Downloads/8002Keys so user can install from Files/Downloads anytime
            runCatching { copyApkToPublicDownloads(context, targetApk, info.remoteShortCommit) }

            _downloadProgressFlow.value = 100
            _statusMessageFlow.value = "Download complete (SHA-256 verified). Launching installer..."

            withContext(Dispatchers.Main) {
                launchApkInstallerWithFallback(context, targetApk, info.downloadUrl)
            }
            targetApk
        }.onFailure { err ->
            _statusMessageFlow.value = "In-app download error: ${err.message}. Opening direct browser download..."
            withContext(Dispatchers.Main) {
                openBrowserDownload(context, info.downloadUrl)
            }
        }.also {
            _downloadProgressFlow.value = null
        }
    }

    private fun copyApkToPublicDownloads(context: Context, sourceApk: File, shortCommit: String) {
        val fileName = if (shortCommit.isNotBlank()) {
            "8002Keys-v1.9.70-$shortCommit.apk"
        } else {
            "8002Keys-latest.apk"
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, "application/vnd.android.package-archive")
                put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/8002Keys")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return
            resolver.openOutputStream(uri)?.use { out ->
                sourceApk.inputStream().use { input -> input.copyTo(out) }
            }
            val done = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
            resolver.update(uri, done, null, null)
        }
    }

    fun launchApkInstallerWithFallback(context: Context, apkFile: File, fallbackUrl: String) {
        val launched = runCatching {
            val apkUri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.provider.file",
                apkFile,
            )
            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(installIntent)
            true
        }.getOrDefault(false)

        if (!launched) {
            openBrowserDownload(context, fallbackUrl)
        }
    }

    fun openBrowserDownload(context: Context, url: String) {
        val targetUrl = url.ifBlank { DEFAULT_APK_DOWNLOAD_URL }
        runCatching {
            val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(targetUrl)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(browserIntent)
            Toast.makeText(
                context,
                "Downloading 8002Keys update APK in Browser / Downloads...",
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    fun openAppAndShowUpdateDialog(context: Context) {
        _showDialogFlow.value = true
        runCatching {
            val intent = Intent(context, FlorisAppActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            context.startActivity(intent)
        }
    }
}

@Composable
fun AppUpdateHost(autoCheckOnMount: Boolean = true) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val latestUpdate by AppUpdateManager.latestUpdateFlow.collectAsState()
    val showDialog by AppUpdateManager.showDialogFlow.collectAsState()
    val downloadProgress by AppUpdateManager.downloadProgressFlow.collectAsState()
    val statusMessage by AppUpdateManager.statusMessageFlow.collectAsState()

    LaunchedEffect(autoCheckOnMount) {
        if (autoCheckOnMount) {
            AppUpdateManager.triggerBackgroundCheck(force = false, autoPopupIfAvailable = true)
        }
    }

    val info = latestUpdate
    if (showDialog && info != null) {
        AlertDialog(
            onDismissRequest = {
                if (downloadProgress == null) {
                    AppUpdateManager.dismissUpdateDialog(snoozeCurrentCommit = true)
                }
            },
            title = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.SystemUpdateAlt,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    val titleText = if (info.isUpdateAvailable) {
                        "🚀 8002Keys Auto-Update Available!"
                    } else {
                        "✓ 8002Keys is Up to Date"
                    }
                    Text(
                        text = titleText,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val introText = if (info.isUpdateAvailable) {
                        "A new cloud build of 8002Keys is ready on GitHub. Your Google/ChatGPT logins and personal AI memory remain safe during update."
                    } else {
                        "You are currently running the latest 8002Keys build."
                    }
                    Text(
                        text = introText,
                        style = MaterialTheme.typography.bodySmall,
                    )

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                                RoundedCornerShape(10.dp),
                            )
                            .padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        val currentLine = "Installed: ${info.installedVersionName} (${info.installedShortCommit})"
                        Text(
                            text = currentLine,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                        )
                        val latestLine = "Latest:    ${info.remoteVersionName} (${info.remoteShortCommit})"
                        Text(
                            text = latestLine,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        val sizeLine = "APK Size:  ${info.formattedSizeMb}" +
                            if (info.apkSha256.isNotBlank()) " • SHA-256: ${info.apkSha256.take(12)}…" else ""
                        Text(
                            text = sizeLine,
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                        )
                    }

                    if (info.commitMessage.isNotBlank()) {
                        val notesHeader = "What's New in This Build:"
                        Text(
                            text = notesHeader,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        val notesBody = info.commitMessage.take(360)
                        Text(
                            text = notesBody,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }

                    if (downloadProgress != null) {
                        Spacer(modifier = Modifier.height(4.dp))
                        LinearProgressIndicator(
                            progress = { (downloadProgress ?: 0) / 100f },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }

                    statusMessage?.let { msg ->
                        Text(
                            text = msg,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
            },
            confirmButton = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Button(
                        onClick = {
                            scope.launch {
                                AppUpdateManager.downloadAndInstallUpdate(context, info)
                            }
                        },
                        enabled = downloadProgress == null,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(
                            imageVector = Icons.Default.CloudDownload,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        val btnLabel = if (downloadProgress != null) {
                            "Downloading Update (${downloadProgress}%)..."
                        } else if (info.isUpdateAvailable) {
                            "Update Now (Download & Install)"
                        } else {
                            "Re-Download & Reinstall Latest APK"
                        }
                        Text(btnLabel)
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedButton(
                            onClick = {
                                AppUpdateManager.openBrowserDownload(context, info.downloadUrl)
                            },
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(
                                imageVector = Icons.Default.OpenInBrowser,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            val browserLabel = "Browser APK"
                            Text(browserLabel, fontSize = 12.sp)
                        }
                        TextButton(
                            onClick = {
                                AppUpdateManager.dismissUpdateDialog(snoozeCurrentCommit = true)
                            },
                            enabled = downloadProgress == null,
                            modifier = Modifier.weight(1f),
                        ) {
                            val closeLabel = "Later"
                            Text(closeLabel)
                        }
                    }
                }
            },
        )
    }
}

@Composable
fun AppUpdateBannerCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val latestUpdate by AppUpdateManager.latestUpdateFlow.collectAsState()
    val isChecking by AppUpdateManager.isCheckingFlow.collectAsState()
    val downloadProgress by AppUpdateManager.downloadProgressFlow.collectAsState()
    val statusMessage by AppUpdateManager.statusMessageFlow.collectAsState()

    val hasUpdate = latestUpdate?.isUpdateAvailable == true
    val cardColors = if (hasUpdate) {
        CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
        )
    } else {
        CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
        )
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .border(
                width = if (hasUpdate) 1.5.dp else 1.dp,
                color = if (hasUpdate) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.outlineVariant
                },
                shape = RoundedCornerShape(14.dp),
            ),
        shape = RoundedCornerShape(14.dp),
        colors = cardColors,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(
                        imageVector = if (hasUpdate) Icons.Default.SystemUpdateAlt else Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = if (hasUpdate) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.secondary
                        },
                    )
                    Column {
                        val headerLabel = if (hasUpdate) {
                            "🚀 Auto-Update Available: ${latestUpdate?.remoteVersionName}"
                        } else {
                            "🔄 8002Keys Auto-Update Center"
                        }
                        Text(
                            text = headerLabel,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                        )
                        val subLabel = "Installed build: ${BuildConfig.VERSION_NAME} (${AppUpdateManager.installedShortCommit()})"
                        Text(
                            text = subLabel,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
                if (isChecking || downloadProgress != null) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                    )
                }
            }

            if (hasUpdate) {
                val msg = latestUpdate?.commitMessage.orEmpty()
                if (msg.isNotBlank()) {
                    val changeLine = "Changes: ${msg.take(180)}"
                    Text(
                        text = changeLine,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            statusMessage?.let { status ->
                Text(
                    text = status,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (hasUpdate) {
                    Button(
                        onClick = {
                            val info = latestUpdate ?: return@Button
                            AppUpdateManager.openUpdateDialog()
                            scope.launch {
                                AppUpdateManager.downloadAndInstallUpdate(context, info)
                            }
                        },
                        enabled = downloadProgress == null,
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(
                            imageVector = Icons.Default.CloudDownload,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        val updateBtnText = if (downloadProgress != null) {
                            "Downloading ${downloadProgress}%..."
                        } else {
                            "Update Now"
                        }
                        Text(updateBtnText)
                    }
                }

                OutlinedButton(
                    onClick = {
                        scope.launch {
                            val result = AppUpdateManager.checkForUpdates(
                                force = true,
                                autoPopupIfAvailable = true,
                            )
                            if (result != null && !result.isUpdateAvailable) {
                                Toast.makeText(
                                    context,
                                    "✓ 8002Keys is up to date (${result.installedVersionName})",
                                    Toast.LENGTH_SHORT,
                                ).show()
                            } else if (result == null) {
                                Toast.makeText(
                                    context,
                                    "Could not check GitHub updates right now.",
                                    Toast.LENGTH_SHORT,
                                ).show()
                            }
                        }
                    },
                    enabled = !isChecking && downloadProgress == null,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    val checkBtnText = if (isChecking) "Checking..." else "Check Updates"
                    Text(checkBtnText)
                }

                if (hasUpdate) {
                    OutlinedButton(
                        onClick = {
                            AppUpdateManager.openBrowserDownload(
                                context,
                                latestUpdate?.downloadUrl.orEmpty(),
                            )
                        },
                    ) {
                        Icon(
                            imageVector = Icons.Default.OpenInBrowser,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            }
        }
    }
}

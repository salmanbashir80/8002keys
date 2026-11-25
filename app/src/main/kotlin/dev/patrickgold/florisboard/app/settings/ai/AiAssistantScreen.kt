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

package dev.patrickgold.florisboard.app.settings.ai

import android.accounts.AccountManager
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.MotionEvent
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import dev.patrickgold.florisboard.app.update.AppUpdateBannerCard
import dev.patrickgold.florisboard.app.update.AppUpdateHost
import dev.patrickgold.florisboard.ime.ai.AiMemoryVault
import dev.patrickgold.florisboard.ime.ai.AiProviderType
import dev.patrickgold.florisboard.ime.ai.AiWritingTask
import dev.patrickgold.florisboard.ime.ai.CloudAiClient
import dev.patrickgold.florisboard.lib.compose.FlorisScreen
import kotlinx.coroutines.launch
import org.florisboard.lib.android.showLongToastSync
import org.florisboard.lib.android.showShortToastSync

private fun openExternalUrl(context: Context, url: String) {
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
        )
    }.onFailure {
        context.showShortToastSync("Unable to open browser")
    }
}

@Composable
fun AiAssistantScreen() = FlorisScreen {
    title = "Google & ChatGPT AI Login"
    previewFieldVisible = true

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val vault by AiMemoryVault.state.collectAsState()
    val pendingLogin by AiMemoryVault.pendingLoginProvider.collectAsState()

    var geminiEmail by remember(vault.geminiAccountEmail) {
        mutableStateOf(vault.geminiAccountEmail)
    }
    var geminiStatus by remember { mutableStateOf<String?>(null) }

    var chatGptEmail by remember(vault.chatGptAccountEmail) {
        mutableStateOf(vault.chatGptAccountEmail)
    }
    var chatGptStatus by remember { mutableStateOf<String?>(null) }

    var inlineWebViewProvider by remember { mutableStateOf<AiProviderType?>(null) }

    var testInput by remember {
        mutableStateOf("I am coming to Lahore tomorrow to meet you")
    }
    var testOutput by remember { mutableStateOf<String?>(null) }
    var isTestingTask by remember { mutableStateOf(false) }

    fun connectGoogleAccountNow(emailInput: String) {
        val cleanEmail = emailInput.trim().ifBlank { "Google Account" }
        val token = "${CloudAiClient.GOOGLE_WEB_SESSION_PREFIX}$cleanEmail"
        geminiEmail = cleanEmail
        AiMemoryVault.connectGemini(
            context = context,
            accountEmail = cleanEmail,
            apiKey = token,
            model = vault.geminiModel,
        )
        geminiStatus = "✓ Google Account Connected ($cleanEmail)"
        inlineWebViewProvider = null
        context.showLongToastSync("Google Account ($cleanEmail) connected!")
    }

    fun connectChatGptAccountNow(emailInput: String) {
        val cleanEmail = emailInput.trim().ifBlank {
            geminiEmail.trim().ifBlank { "ChatGPT Plus Subscription" }
        }
        val token = "${CloudAiClient.CHATGPT_WEB_SESSION_PREFIX}$cleanEmail"
        chatGptEmail = cleanEmail
        AiMemoryVault.connectChatGpt(
            context = context,
            accountEmail = cleanEmail,
            apiKey = token,
            model = vault.chatGptModel,
            baseUrl = vault.chatGptBaseUrl,
        )
        chatGptStatus = "✓ ChatGPT Subscription Attached ($cleanEmail)"
        inlineWebViewProvider = null
        context.showLongToastSync("ChatGPT Subscription ($cleanEmail) attached!")
    }

    val googleAccountPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val pickedEmail = result.data?.getStringExtra(AccountManager.KEY_ACCOUNT_NAME)?.trim().orEmpty()
            if (pickedEmail.isNotEmpty()) {
                connectGoogleAccountNow(pickedEmail)
            }
        }
    }

    fun launchSystemGoogleAccountPicker() {
        runCatching {
            val intent = AccountManager.newChooseAccountIntent(
                null,
                null,
                arrayOf("com.google"),
                null,
                null,
                null,
                null,
            )
            googleAccountPickerLauncher.launch(intent)
        }.onFailure {
            inlineWebViewProvider = AiProviderType.GEMINI
        }
    }

    LaunchedEffect(Unit) {
        AiMemoryVault.initialize(context)
    }

    LaunchedEffect(pendingLogin) {
        val target = pendingLogin
        if (target != null) {
            AiMemoryVault.requestDirectLoginModal(null)
            if (target == AiProviderType.GEMINI) {
                launchSystemGoogleAccountPicker()
            } else {
                inlineWebViewProvider = target
            }
        }
    }

    val emeraldGreen = Color(0xFF059669)
    val royalBlue = Color(0xFF2563EB)
    val deepIndigo = Color(0xFF4F46E5)

    content {
        AppUpdateHost(autoCheckOnMount = true)
        AppUpdateBannerCard()

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Optional Inline Login WebView (rendered directly in Activity window so keyboard ALWAYS opens!)
            val activeWebProvider = inlineWebViewProvider
            if (activeWebProvider != null) {
                InlineAccountSignInCard(
                    provider = activeWebProvider,
                    onAccountConnected = { prov, email ->
                        if (prov == AiProviderType.GEMINI) {
                            connectGoogleAccountNow(email)
                        } else {
                            connectChatGptAccountNow(email)
                        }
                    },
                    onClose = { inlineWebViewProvider = null },
                )
            }

            // CARD 1: GOOGLE ACCOUNT LOGIN (SIMPLE, LARGE FONT, 1-TAP)
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(
                        width = 1.5.dp,
                        color = if (vault.isGeminiReady) emeraldGreen else royalBlue,
                        shape = RoundedCornerShape(16.dp),
                    ),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                ),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Default.AccountCircle,
                                contentDescription = null,
                                tint = if (vault.isGeminiReady) emeraldGreen else royalBlue,
                                modifier = Modifier.size(28.dp),
                            )
                            val card1Title = "1. Google Account"
                            Text(
                                text = card1Title,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.ExtraBold,
                            )
                        }
                        val statusBadge = if (vault.isGeminiReady) {
                            "✓ Connected"
                        } else {
                            "Not Connected"
                        }
                        Text(
                            text = statusBadge,
                            color = if (vault.isGeminiReady) emeraldGreen else MaterialTheme.colorScheme.error,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }

                    if (vault.isGeminiReady && vault.geminiAccountEmail.isNotBlank()) {
                        val activeEmailLine = "Logged in as: ${vault.geminiAccountEmail}"
                        Text(
                            text = activeEmailLine,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = emeraldGreen,
                        )
                    }

                    // 1-Tap Phone Google Account Chooser Button
                    Button(
                        onClick = { launchSystemGoogleAccountPicker() },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = royalBlue),
                    ) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = null,
                            modifier = Modifier.size(22.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        val btn1Text = "1-Tap Choose Phone's Google Account"
                        Text(
                            text = btn1Text,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }

                    // Direct Email Input Box (Right on main screen so keyboard opens immediately!)
                    OutlinedTextField(
                        value = geminiEmail,
                        onValueChange = { geminiEmail = it },
                        label = {
                            val labelText = "Or Type Your Gmail Address Here"
                            Text(labelText, fontSize = 14.sp)
                        },
                        placeholder = {
                            val placeholderText = "example@gmail.com"
                            Text(placeholderText, fontSize = 15.sp)
                        },
                        textStyle = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Medium),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Button(
                            onClick = { connectGoogleAccountNow(geminiEmail) },
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = emeraldGreen),
                        ) {
                            val saveGoogleText = "✓ Connect Google"
                            Text(
                                text = saveGoogleText,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                            )
                        }

                        OutlinedButton(
                            onClick = { inlineWebViewProvider = AiProviderType.GEMINI },
                            modifier = Modifier.height(48.dp),
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            val webLoginText = "Web Login"
                            Text(
                                text = webLoginText,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }

                    if (!geminiStatus.isNullOrBlank()) {
                        Text(
                            text = geminiStatus!!,
                            color = emeraldGreen,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }

            // CARD 2: CHATGPT SUBSCRIPTION LOGIN (SIMPLE, LARGE FONT, 1-TAP)
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(
                        width = 1.5.dp,
                        color = if (vault.isChatGptReady) emeraldGreen else deepIndigo,
                        shape = RoundedCornerShape(16.dp),
                    ),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                ),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Default.AutoAwesome,
                                contentDescription = null,
                                tint = if (vault.isChatGptReady) emeraldGreen else deepIndigo,
                                modifier = Modifier.size(28.dp),
                            )
                            val card2Title = "2. ChatGPT Subscription"
                            Text(
                                text = card2Title,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.ExtraBold,
                            )
                        }
                        val gptBadge = if (vault.isChatGptReady) {
                            "✓ Attached"
                        } else {
                            "Not Attached"
                        }
                        Text(
                            text = gptBadge,
                            color = if (vault.isChatGptReady) emeraldGreen else MaterialTheme.colorScheme.error,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }

                    if (vault.isChatGptReady && vault.chatGptAccountEmail.isNotBlank()) {
                        val activeGptLine = "Subscription Account: ${vault.chatGptAccountEmail}"
                        Text(
                            text = activeGptLine,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = emeraldGreen,
                        )
                    }

                    // 1-Tap Attach ChatGPT Subscription using Google Account
                    Button(
                        onClick = {
                            val emailToUse = chatGptEmail.trim().ifBlank {
                                geminiEmail.trim().ifBlank {
                                    vault.geminiAccountEmail.trim()
                                }
                            }
                            if (emailToUse.isNotBlank()) {
                                connectChatGptAccountNow(emailToUse)
                            } else {
                                launchSystemGoogleAccountPicker()
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = deepIndigo),
                    ) {
                        Icon(
                            imageVector = Icons.Default.AutoAwesome,
                            contentDescription = null,
                            modifier = Modifier.size(22.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        val oneTapGptText = "1-Tap Attach ChatGPT with Google Account"
                        Text(
                            text = oneTapGptText,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }

                    // Direct Email Input Box (Keyboard opens immediately!)
                    OutlinedTextField(
                        value = chatGptEmail,
                        onValueChange = { chatGptEmail = it },
                        label = {
                            val gptFieldLabel = "Or Type Your ChatGPT Email Here"
                            Text(gptFieldLabel, fontSize = 14.sp)
                        },
                        placeholder = {
                            val gptPlaceholder = "example@live.com or gmail.com"
                            Text(gptPlaceholder, fontSize = 15.sp)
                        },
                        textStyle = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Medium),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Button(
                            onClick = { connectChatGptAccountNow(chatGptEmail) },
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = emeraldGreen),
                        ) {
                            val attachGptText = "✓ Attach ChatGPT"
                            Text(
                                text = attachGptText,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                            )
                        }

                        OutlinedButton(
                            onClick = { inlineWebViewProvider = AiProviderType.CHATGPT },
                            modifier = Modifier.height(48.dp),
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            val webGptText = "ChatGPT Web"
                            Text(
                                text = webGptText,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }

                    if (!chatGptStatus.isNullOrBlank()) {
                        Text(
                            text = chatGptStatus!!,
                            color = emeraldGreen,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }

            // CARD 3: LIVE TEST PLAYGROUND (LARGE READABLE FONT)
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                ),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Language,
                            contentDescription = null,
                            tint = royalBlue,
                            modifier = Modifier.size(26.dp),
                        )
                        val testCardTitle = "3. Live Test: Grammar & Urdu Translation"
                        Text(
                            text = testCardTitle,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.ExtraBold,
                        )
                    }

                    OutlinedTextField(
                        value = testInput,
                        onValueChange = { testInput = it },
                        label = {
                            val testBoxLabel = "Type English or Roman Urdu to Test"
                            Text(testBoxLabel, fontSize = 14.sp)
                        },
                        textStyle = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Medium),
                        modifier = Modifier.fillMaxWidth(),
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Button(
                            onClick = {
                                isTestingTask = true
                                scope.launch {
                                    val res = CloudAiClient.executeTask(
                                        context = context,
                                        task = AiWritingTask.FIX_GRAMMAR_SPELLING,
                                        inputText = testInput,
                                    )
                                    isTestingTask = false
                                    testOutput = res.outputText
                                }
                            },
                            enabled = !isTestingTask,
                            modifier = Modifier
                                .weight(1f)
                                .height(46.dp),
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = royalBlue),
                        ) {
                            val fixTestLabel = "✨ Fix Grammar"
                            Text(fixTestLabel, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        }

                        Button(
                            onClick = {
                                isTestingTask = true
                                scope.launch {
                                    val res = CloudAiClient.executeTask(
                                        context = context,
                                        task = AiWritingTask.TO_URDU_SCRIPT,
                                        inputText = testInput,
                                    )
                                    isTestingTask = false
                                    testOutput = res.outputText
                                }
                            },
                            enabled = !isTestingTask,
                            modifier = Modifier
                                .weight(1f)
                                .height(46.dp),
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = emeraldGreen),
                        ) {
                            val urduTestLabel = "🇵🇰 Translate Urdu"
                            Text(urduTestLabel, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Button(
                            onClick = {
                                isTestingTask = true
                                scope.launch {
                                    val res = CloudAiClient.executeTask(
                                        context = context,
                                        task = AiWritingTask.ROMAN_URDU_TO_ENGLISH,
                                        inputText = testInput,
                                    )
                                    isTestingTask = false
                                    testOutput = res.outputText
                                }
                            },
                            enabled = !isTestingTask,
                            modifier = Modifier
                                .weight(1f)
                                .height(46.dp),
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = deepIndigo),
                        ) {
                            val engTestLabel = "🇬🇧 To English"
                            Text(engTestLabel, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        }

                        Button(
                            onClick = {
                                isTestingTask = true
                                scope.launch {
                                    val res = CloudAiClient.executeTask(
                                        context = context,
                                        task = AiWritingTask.ENGLISH_TO_ROMAN_URDU,
                                        inputText = testInput,
                                    )
                                    isTestingTask = false
                                    testOutput = res.outputText
                                }
                            },
                            enabled = !isTestingTask,
                            modifier = Modifier
                                .weight(1f)
                                .height(46.dp),
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0D9488)),
                        ) {
                            val romanTestLabel = "🇵🇰 To Roman Urdu"
                            Text(romanTestLabel, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    if (isTestingTask) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            val runningLabel = "Translating / Checking Grammar..."
                            Text(runningLabel, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }

                    if (!testOutput.isNullOrBlank()) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    emeraldGreen.copy(alpha = 0.14f),
                                    RoundedCornerShape(12.dp),
                                )
                                .border(1.dp, emeraldGreen, RoundedCornerShape(12.dp))
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            val resTitle = "Result:"
                            Text(
                                text = resTitle,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = emeraldGreen,
                            )
                            Text(
                                text = testOutput!!,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Inline WebView Sign-In Card rendered directly inside the main Activity layout
 * (NOT inside a Compose Dialog) so the soft keyboard opens normally on all input fields.
 */
@SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
@Composable
private fun InlineAccountSignInCard(
    provider: AiProviderType,
    onAccountConnected: (provider: AiProviderType, email: String) -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val isGemini = provider == AiProviderType.GEMINI || provider == AiProviderType.AUTO_HYBRID
    val loginUrl = if (isGemini) {
        "https://accounts.google.com/ServiceLogin?continue=https://gemini.google.com/app"
    } else {
        "https://chatgpt.com/"
    }

    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var detectedEmail by remember { mutableStateOf("") }
    var hasActiveSessionCookie by remember { mutableStateOf(false) }

    fun inspectPageAndCookies(pageText: String, currentUrl: String?) {
        val emailMatch = Regex("""[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}""").find(pageText)
        if (emailMatch != null && !emailMatch.value.endsWith("@example.com")) {
            detectedEmail = emailMatch.value
        }
        runCatching {
            val cookies = listOfNotNull(
                currentUrl?.let { CookieManager.getInstance().getCookie(it) },
                CookieManager.getInstance().getCookie("https://google.com"),
                CookieManager.getInstance().getCookie("https://gemini.google.com"),
                CookieManager.getInstance().getCookie("https://chatgpt.com"),
            ).joinToString("; ")
            if (isGemini) {
                if (cookies.contains("SID=") || cookies.contains("SAPISID=") || cookies.contains("__Secure-1PSID=")) {
                    hasActiveSessionCookie = true
                }
            } else {
                if (cookies.contains("session-token") || cookies.contains("oai-did")) {
                    hasActiveSessionCookie = true
                }
            }
        }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(16.dp)),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val titleText = if (isGemini) {
                    "Google Account Sign-In"
                } else {
                    "ChatGPT Subscription Sign-In"
                }
                Text(
                    text = titleText,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.ExtraBold,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(onClick = { openExternalUrl(context, loginUrl) }) {
                        Icon(Icons.Default.OpenInBrowser, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Chrome", fontSize = 13.sp)
                    }
                    OutlinedButton(onClick = onClose) {
                        Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(16.dp))
                    }
                }
            }

            Button(
                onClick = {
                    val wv = webViewRef
                    if (wv != null) {
                        wv.evaluateJavascript(
                            "(function(){ return (window.__chatgpt_session_json || '') + ' ' + (document.body ? document.body.innerText : ''); })();",
                        ) { res ->
                            inspectPageAndCookies(res.orEmpty(), wv.url)
                            val finalEmail = detectedEmail.ifBlank {
                                if (isGemini) "Google Account" else "ChatGPT Plus Subscription"
                            }
                            onAccountConnected(provider, finalEmail)
                        }
                    } else {
                        val finalEmail = detectedEmail.ifBlank {
                            if (isGemini) "Google Account" else "ChatGPT Plus Subscription"
                        }
                        onAccountConnected(provider, finalEmail)
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF059669)),
            ) {
                val doneLabel = when {
                    detectedEmail.isNotBlank() -> "✓ Finish & Connect ($detectedEmail)"
                    hasActiveSessionCookie -> "✓ Session Active — Tap to Connect!"
                    else -> "✓ Tap Here After Signing In Below"
                }
                Text(doneLabel, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            }

            AndroidView(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(440.dp),
                factory = { ctx ->
                    WebView(ctx).apply {
                        isFocusable = true
                        isFocusableInTouchMode = true
                        requestFocus(View.FOCUS_DOWN)
                        setOnTouchListener { v, event ->
                            when (event.action) {
                                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP -> {
                                    if (!v.hasFocus()) {
                                        v.requestFocus()
                                    }
                                }
                            }
                            false
                        }
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        val cleanUa = settings.userAgentString
                            .replace("; wv)", ")")
                            .replace("; wv", "")
                            .replace(Regex("""Version/\d+\.\d+\s+"""), "")
                        settings.userAgentString = cleanUa
                        runCatching {
                            val cm = CookieManager.getInstance()
                            cm.setAcceptCookie(true)
                            cm.setAcceptThirdPartyCookies(this, true)
                        }
                        webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView?, url: String?) {
                                super.onPageFinished(view, url)
                                view?.evaluateJavascript(
                                    """
                                    (function(){
                                      try {
                                        if (location.hostname.indexOf('chatgpt.com') !== -1) {
                                          fetch('/api/auth/session', {credentials:'include'})
                                            .then(function(r){ return r.text(); })
                                            .then(function(t){ window.__chatgpt_session_json = t; });
                                        }
                                      } catch(e) {}
                                      return (window.__chatgpt_session_json || '') + ' ' + (document.body ? document.body.innerText : '');
                                    })();
                                    """.trimIndent(),
                                ) { res ->
                                    inspectPageAndCookies(res.orEmpty(), url)
                                }
                            }
                        }
                        loadUrl(loginUrl)
                        webViewRef = this
                    }
                },
            )
        }
    }
}

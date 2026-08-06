package com.xs.storemanager.ui

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.xs.storemanager.data.ApiClient
import com.xs.storemanager.data.ApiException
import com.xs.storemanager.data.SecurePrefs
import com.xs.storemanager.speech.RecognitionServices
import kotlinx.coroutines.launch

class SettingsViewModel : ViewModel() {
    var saving by mutableStateOf(false)
    var toast by mutableStateOf<String?>(null)

    fun login(ctx: Context, baseUrl: String, username: String, password: String, onOk: () -> Unit) {
        if (baseUrl.isBlank() || username.isBlank() || password.isBlank()) {
            toast = "请填写后端地址、用户名和密码"; return
        }
        saving = true
        viewModelScope.launch {
            try {
                SecurePrefs.saveBaseUrl(ctx, baseUrl)
                ApiClient.login(ctx, username.trim(), password)
                toast = "登录成功"
                onOk()
            } catch (e: ApiException) {
                toast = e.message
            } finally {
                saving = false
            }
        }
    }

    fun saveDeepSeekKey(ctx: Context, key: String, onOk: () -> Unit) {
        if (key.isBlank()) { toast = "请填写 DeepSeek API Key"; return }
        SecurePrefs.saveDeepSeekKey(ctx, key.trim())
        toast = "API Key 已保存（本地加密）"
        onOk()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsDialog(onDismiss: () -> Unit, onSaved: () -> Unit) {
    val ctx = LocalContext.current
    val vm: SettingsViewModel = viewModel()
    var baseUrl by remember { mutableStateOf(SecurePrefs.getBaseUrl(ctx)) }
    var username by remember { mutableStateOf(SecurePrefs.getUsername(ctx) ?: "") }
    var password by remember { mutableStateOf("") }
    var deepseekKey by remember { mutableStateOf(SecurePrefs.getDeepSeekKey(ctx) ?: "") }
    var showKey by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = { if (!vm.saving) onDismiss() },
        title = { Text("设置") },
        text = {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .widthIn(max = 380.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("后端连接", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it },
                    label = { Text("后端地址") },
                    placeholder = { Text("http://192.168.10.10:19117") },
                    singleLine = true,
                    enabled = !vm.saving,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text("用户名") },
                    singleLine = true,
                    enabled = !vm.saving,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("密码") },
                    singleLine = true,
                    visualTransformation = if (showKey) PasswordVisualTransformation.None else PasswordVisualTransformation(),
                    enabled = !vm.saving,
                    modifier = Modifier.fillMaxWidth()
                )
                Button(
                    onClick = { vm.login(ctx, baseUrl, username, password, onSaved) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !vm.saving
                ) {
                    Text(if (vm.saving) "登录中..." else "登录 / 保存")
                }

                HorizontalDivider()

                Text("AI 分析（DeepSeek）", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                OutlinedTextField(
                    value = deepseekKey,
                    onValueChange = { deepseekKey = it },
                    label = { Text("DeepSeek API Key") },
                    singleLine = true,
                    visualTransformation = if (showKey) PasswordVisualTransformation.None else PasswordVisualTransformation(),
                    enabled = !vm.saving,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = showKey, onCheckedChange = { showKey = it })
                    Text("显示密钥")
                }
                Text(
                    "密钥仅 AES 加密保存在本机，只发送给 DeepSeek，不会上传到你的销售服务器。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )
                Button(
                    onClick = { vm.saveDeepSeekKey(ctx, deepseekKey, onSaved) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !vm.saving
                ) {
                    Text("保存 API Key")
                }

                HorizontalDivider()

                Text("语音识别引擎", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                Text(
                    "选择按住说话时使用的输入法引擎。默认=系统当前输入法；选了指定引擎后，语音按钮会直接调用它（Android 13+ 生效）。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )
                val engines = remember {
                    RecognitionServices.list(ctx)
                }
                val currentSel = remember {
                    RecognitionServices.getSelection(ctx)
                }
                if (engines.isEmpty()) {
                    Text("未检测到可用的语音识别服务，请确认已安装带语音的输入法。",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                } else {
                    engines.forEach { opt ->
                        val selected = opt.packageName == currentSel?.first && opt.className == currentSel?.second
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = selected,
                                onClick = {
                                    RecognitionServices.saveSelection(ctx, opt.packageName, opt.className)
                                }
                            )
                            Column {
                                Text(opt.label, style = MaterialTheme.typography.bodyMedium)
                                if (opt.isDefault) {
                                    Text("（系统默认）", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                }
                            }
                        }
                    }
                    // 清除指定 → 回到系统默认
                    TextButton(onClick = {
                        RecognitionServices.clearSelection(ctx)
                    }) { Text("恢复系统默认引擎") }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onDismiss() }, enabled = !vm.saving) { Text("关闭") }
        }
    )

    val msg = vm.toast
    if (msg != null) {
        LaunchedEffect(msg) {
            android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_LONG).show()
            vm.toast = null
        }
    }
}

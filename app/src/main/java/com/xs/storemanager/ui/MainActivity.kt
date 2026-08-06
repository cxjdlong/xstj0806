package com.xs.storemanager.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.xs.storemanager.data.*
import com.xs.storemanager.speech.VoiceRecognizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainViewModel : ViewModel() {
    var dashboard by mutableStateOf<DashboardData?>(null)
    var loading by mutableStateOf(false)
    var isLoggedIn by mutableStateOf(false)
    var toast by mutableStateOf<String?>(null)
    var drafts by mutableStateOf<List<DraftItem>>(emptyList())
    var online by mutableStateOf(true)

    fun refreshDrafts(ctx: android.content.Context) {
        drafts = DraftsRepository.load(ctx)
    }

    fun loadDashboard(ctx: android.content.Context) {
        if (!SecurePrefs.hasToken(ctx)) { isLoggedIn = false; return }
        loading = true
        viewModelScope.launch {
            try {
                dashboard = ApiClient.dashboard(ctx)
                isLoggedIn = true
            } catch (e: ApiException) {
                if (e.message?.contains("登录") == true || e.message?.contains("token") == true) {
                    SecurePrefs.clearCredential(ctx)
                    isLoggedIn = false
                } else {
                    toast = e.message
                }
            } finally {
                loading = false
            }
        }
    }

    /**
     * 录入入口。
     * - 有网：调 DeepSeek 结构化 → POST /api/sales 录入，成功后清空输入框
     * - 无网：把文字存入本地草稿（每条独立），提示已暂存，联网后自动补录
     */
    fun submit(ctx: android.content.Context, text: String, onDone: () -> Unit) {
        online = NetworkMonitor.isOnline(ctx)
        viewModelScope.launch {
            if (!online) {
                DraftsRepository.add(ctx, text.trim())
                refreshDrafts(ctx)
                toast = "当前离线，已存入本地草稿，联网后自动录入"
                onDone()
                return@launch
            }
            loading = true
            try {
                val entry = DeepSeekClient.analyze(ctx, text)
                val msg = ApiClient.createSale(ctx, entry)
                toast = msg
                onDone()
                loadDashboard(ctx)
            } catch (e: ApiException) {
                toast = e.message
            } finally {
                loading = false
            }
        }
    }

    /** 草稿自动补录：把全部 pending 草稿逐个格式化并录入数据库 */
    fun flushDrafts(ctx: android.content.Context) {
        val pending = DraftsRepository.pending(ctx)
        if (pending.isEmpty()) return
        loading = true
        viewModelScope.launch {
            for (d in pending) {
                try {
                    val entry = DeepSeekClient.analyze(ctx, d.text)
                    ApiClient.createSale(ctx, entry)
                    // 录入成功后才标记 done，避免中途崩溃丢草稿
                    DraftsRepository.updateStatus(ctx, d.id, DraftItem.DRAFT_DONE)
                } catch (e: ApiException) {
                    DraftsRepository.updateStatus(ctx, d.id, DraftItem.DRAFT_FAILED)
                    toast = "部分草稿补录失败：${e.message}"
                    break
                }
            }
            refreshDrafts(ctx)
            loadDashboard(ctx)
            loading = false
            if (DraftsRepository.pending(ctx).isEmpty()) {
                toast = "离线草稿已全部录入完成"
            }
        }
    }

    /** 删除一条草稿 */
    fun deleteDraft(ctx: android.content.Context, id: Long) {
        DraftsRepository.remove(ctx, id)
        refreshDrafts(ctx)
    }

    /** 手动重试某条 failed 草稿 */
    fun retryDraft(ctx: android.content.Context, d: DraftItem) {
        viewModelScope.launch {
            try {
                DraftsRepository.updateStatus(ctx, d.id, DraftItem.DRAFT_DONE)
                val entry = DeepSeekClient.analyze(ctx, d.text)
                ApiClient.createSale(ctx, entry)
                toast = "草稿已录入"
            } catch (e: ApiException) {
                DraftsRepository.updateStatus(ctx, d.id, DraftItem.DRAFT_FAILED)
                toast = "补录失败：${e.message}"
            }
            refreshDrafts(ctx)
            loadDashboard(ctx)
        }
    }
}

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CrashLogger.install(applicationContext)
        setContent {
            MaterialTheme(colorScheme = lightColorScheme()) {
                StoreManagerApp(vm)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StoreManagerApp(vm: MainViewModel) {
    val ctx = LocalContext.current
    var showSettings by remember { mutableStateOf(false) }
    var showDrafts by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf("") }
    var listening by remember { mutableStateOf(false) }

    // 录音权限
    var hasMicPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        )
    }
    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasMicPermission = granted }

    val voice = remember { VoiceRecognizer(ctx.applicationContext) }
    DisposableEffect(Unit) {
        onDispose { voice.destroy() }
    }

    // 启动时加载概览 + 草稿
    LaunchedEffect(Unit) {
        vm.online = NetworkMonitor.isOnline(ctx)
        vm.loadDashboard(ctx)
        vm.refreshDrafts(ctx)
        // 有网且有待补录草稿 → 自动补录
        if (NetworkMonitor.isOnline(ctx)) {
            vm.flushDrafts(ctx)
        }
    }

    // 网络恢复 → 自动补录草稿
    val netCb = remember {
        NetworkMonitor.observe(ctx) {
            vm.online = true
            vm.flushDrafts(ctx)
        }
    }
    DisposableEffect(Unit) {
        onDispose { NetworkMonitor.unregister(ctx, netCb) }
    }

    // 底部按钮按住说话
    val micPress = Modifier.pointerInput(Unit) {
        detectTapGestures(
            onPress = {
                if (!hasMicPermission) {
                    permLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    return@detectTapGestures
                }
                if (!voice.isAvailable()) {
                    vm.toast = "当前设备无可用语音识别服务"
                    return@detectTapGestures
                }
                listening = true
                voice.start(object : VoiceRecognizer.Callback {
                    override fun onResult(t: String) {
                        listening = false
                        text = (text.trimEnd() + " " + t.trim()).trim()
                    }
                    override fun onError(msg: String) {
                        listening = false
                        vm.toast = msg
                    }
                    override fun onStartListening() {}
                    override fun onEndListening() { listening = false }
                })
                tryAwaitRelease()
                voice.stop()
                listening = false
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("门店管理系统", fontWeight = FontWeight.Bold) },
                actions = {
                    // 网络状态
                    if (!vm.online) {
                        Text("离线", color = Color(0xFFF57C00), modifier = Modifier.padding(horizontal = 8.dp))
                    }
                    // 草稿入口（带数量角标）
                    if (vm.drafts.any { it.status == DraftItem.DRAFT_PENDING }) {
                        BadgedBox(
                            badge = { Badge { Text("${vm.drafts.count { it.status == DraftItem.DRAFT_PENDING }}") } }
                        ) {
                            IconButton(onClick = { showDrafts = true }) {
                                Icon(Icons.Default.Inventory2, contentDescription = "离线草稿")
                            }
                        }
                    } else {
                        IconButton(onClick = { showDrafts = true }) {
                            Icon(Icons.Default.Inventory2, contentDescription = "离线草稿")
                        }
                    }
                    IconButton(onClick = { showSettings = true }) {
                        Icon(Icons.Default.Settings, contentDescription = "设置")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
        ) {
            // ===== 销售概览数据区 =====
            DashboardCard(vm)

            Spacer(Modifier.height(16.dp))

            // ===== 文本框 =====
            Text("录入内容（可手动修改）", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                placeholder = { Text("按住下方按钮说话，或手动输入，例如：卖了2台华为Mate60进价3800卖4200") },
                enabled = !vm.loading
            )

            Spacer(Modifier.height(16.dp))

            // ===== 底部两按钮 =====
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                // 左：按住说话
                Button(
                    onClick = {},
                    modifier = Modifier
                        .weight(1f)
                        .height(56.dp)
                        .then(micPress),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (listening) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primaryContainer,
                        contentColor = if (listening) Color.White else MaterialTheme.colorScheme.onPrimaryContainer
                    )
                ) {
                    Icon(Icons.Default.Mic, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (listening) "松开结束" else "按住说话")
                }
                // 右：录入
                Button(
                    onClick = {
                        if (text.isBlank()) { vm.toast = "请输入内容"; return@Button }
                        if (!NetworkMonitor.isOnline(ctx)) {
                            // 断网 → 存草稿
                            vm.submit(ctx, text) { text = "" }
                            return@Button
                        }
                        vm.submit(ctx, text) { text = "" }
                    },
                    modifier = Modifier
                        .weight(1f)
                        .height(56.dp),
                    enabled = !vm.loading,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50))
                ) {
                    Text("录入")
                }
            }
        }
    }

    // 设置对话框
    if (showSettings) {
        SettingsDialog(
            onDismiss = { showSettings = false },
            onSaved = { vm.loadDashboard(ctx) }
        )
    }

    // 草稿列表对话框
    if (showDrafts) {
        DraftsDialog(
            vm = vm,
            ctx = ctx,
            onDismiss = { showDrafts = false }
        )
    }

    // toast
    val toastMsg = vm.toast
    if (toastMsg != null) {
        LaunchedEffect(toastMsg) {
            Toast.makeText(ctx, toastMsg, Toast.LENGTH_LONG).show()
            vm.toast = null
        }
    }
}

@Composable
fun DashboardCard(vm: MainViewModel) {
    val data = vm.dashboard
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("销售概览", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            if (vm.loading && data == null) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp))
            } else if (data == null) {
                Text("请先在设置中登录并填写后端地址", color = MaterialTheme.colorScheme.outline)
            } else {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    StatItem("今日收入", "¥%.0f".format(data.todayRevenue))
                    StatItem("今日毛利", "¥%.0f".format(data.todayProfit))
                    StatItem("本周毛利", "¥%.0f".format(data.weekProfit))
                }
                Spacer(Modifier.height(12.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    StatItem("本月毛利", "¥%.0f".format(data.monthProfit))
                    StatItem("本月收入", "¥%.0f".format(data.monthRevenue))
                    StatItem("当年毛利", "¥%.0f".format(data.yearProfit))
                }
            }
        }
    }
}

@Composable
fun StatItem(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.outline)
        Spacer(Modifier.height(4.dp))
        Text(value, fontSize = 16.sp, fontWeight = FontWeight.Bold)
    }
}

/** 离线草稿列表：pending 待补录、done 已录入、failed 失败(可重试/删除) */
@Composable
fun DraftsDialog(vm: MainViewModel, ctx: android.content.Context, onDismiss: () -> Unit) {
    val drafts = vm.drafts
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("离线草稿") },
        text = {
            if (drafts.isEmpty()) {
                Text("暂无草稿", color = MaterialTheme.colorScheme.outline)
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "断网时录入的内容会先存这里，联网后自动补录。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                    LazyColumn(
                        modifier = Modifier.weight(1f, fill = false).heightIn(max = 360.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(drafts.size) { i ->
                            val d = drafts[i]
                            val statusText = when (d.status) {
                                DraftItem.DRAFT_PENDING -> "待补录"
                                DraftItem.DRAFT_DONE -> "已录入"
                                DraftItem.DRAFT_FAILED -> "失败"
                                else -> d.status
                            }
                            val statusColor = when (d.status) {
                                DraftItem.DRAFT_DONE -> Color(0xFF4CAF50)
                                DraftItem.DRAFT_FAILED -> Color(0xFFF44336)
                                else -> MaterialTheme.colorScheme.outline
                            }
                            Card(modifier = Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(12.dp)) {
                                    Text(d.text, style = MaterialTheme.typography.bodyMedium)
                                    Spacer(Modifier.height(6.dp))
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(statusText, color = statusColor, style = MaterialTheme.typography.labelSmall)
                                        Row {
                                            if (d.status == DraftItem.DRAFT_FAILED) {
                                                TextButton(onClick = { vm.retryDraft(ctx, d) }) { Text("重试") }
                                            }
                                            TextButton(onClick = { vm.deleteDraft(ctx, d.id) }) { Text("删除") }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (NetworkMonitor.isOnline(ctx)) vm.flushDrafts(ctx)
                else vm.toast = "当前离线，无法补录"
            }) { Text("立即补录") }
            TextButton(onClick = onDismiss) { Text("关闭") }
        }
    )
}

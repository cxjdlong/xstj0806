1|package com.xs.storemanager.ui
2|
3|import android.content.Context
4|import androidx.compose.foundation.layout.*
5|import androidx.compose.foundation.rememberScrollState
6|import androidx.compose.foundation.verticalScroll
7|import androidx.compose.material3.*
8|import androidx.compose.runtime.*
9|import androidx.compose.ui.Alignment
10|import androidx.compose.ui.Modifier
11|import androidx.compose.ui.platform.LocalContext
12|import androidx.compose.ui.text.input.PasswordVisualTransformation
13|import androidx.compose.ui.unit.dp
14|import androidx.lifecycle.ViewModel
15|import androidx.lifecycle.viewModelScope
16|import androidx.lifecycle.viewmodel.compose.viewModel
17|import com.xs.storemanager.data.ApiClient
18|import com.xs.storemanager.data.ApiException
19|import com.xs.storemanager.data.SecurePrefs
20|import com.xs.storemanager.speech.RecognitionServices
21|import kotlinx.coroutines.launch
22|
23|class SettingsViewModel : ViewModel() {
24|    var saving by mutableStateOf(false)
25|    var toast by mutableStateOf<String?>(null)
26|
27|    fun login(ctx: Context, baseUrl: String, username: String, password: String, onOk: () -> Unit) {
28|        if (baseUrl.isBlank() || username.isBlank() || password.isBlank()) {
29|            toast = "请填写后端地址、用户名和密码"; return
30|        }
31|        saving = true
32|        viewModelScope.launch {
33|            try {
34|                SecurePrefs.saveBaseUrl(ctx, baseUrl)
35|                ApiClient.login(ctx, username.trim(), password)
36|                toast = "登录成功"
37|                onOk()
38|            } catch (e: ApiException) {
39|                toast = e.message
40|            } finally {
41|                saving = false
42|            }
43|        }
44|    }
45|
46|    fun saveDeepSeekKey(ctx: Context, key: String, onOk: () -> Unit) {
47|        if (key.isBlank()) { toast = "请填写 DeepSeek API Key"; return }
48|        SecurePrefs.saveDeepSeekKey(ctx, key.trim())
49|        toast = "API Key 已保存（本地加密）"
50|        onOk()
51|    }
52|}
53|
54|@OptIn(ExperimentalMaterial3Api::class)
55|@Composable
56|fun SettingsDialog(onDismiss: () -> Unit, onSaved: () -> Unit) {
57|    val ctx = LocalContext.current
58|    val vm: SettingsViewModel = viewModel()
59|    var baseUrl by remember { mutableStateOf(SecurePrefs.getBaseUrl(ctx)) }
60|    var username by remember { mutableStateOf(SecurePrefs.getUsername(ctx) ?: "") }
61|    var password by remember { mutableStateOf("") }
62|    var deepseekKey by remember { mutableStateOf(SecurePrefs.getDeepSeekKey(ctx) ?: "") }
63|    var showKey by remember { mutableStateOf(false) }
64|
65|    AlertDialog(
66|        onDismissRequest = { if (!vm.saving) onDismiss() },
67|        title = { Text("设置") },
68|        text = {
69|            Column(
70|                modifier = Modifier
71|                    .verticalScroll(rememberScrollState())
72|                    .widthIn(max = 380.dp),
73|                verticalArrangement = Arrangement.spacedBy(12.dp)
74|            ) {
75|                Text("后端连接", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
76|                OutlinedTextField(
77|                    value = baseUrl,
78|                    onValueChange = { baseUrl = it },
79|                    label = { Text("后端地址") },
80|                    placeholder = { Text("http://192.168.10.10:19117") },
81|                    singleLine = true,
82|                    enabled = !vm.saving,
83|                    modifier = Modifier.fillMaxWidth()
84|                )
85|                OutlinedTextField(
86|                    value = username,
87|                    onValueChange = { username = it },
88|                    label = { Text("用户名") },
89|                    singleLine = true,
90|                    enabled = !vm.saving,
91|                    modifier = Modifier.fillMaxWidth()
92|                )
93|                OutlinedTextField(
94|                    value = password,
95|                    onValueChange = { password = it },
96|                    label = { Text("密码") },
97|                    singleLine = true,
98|                    visualTransformation = if (showKey) PasswordVisualTransformation.None else PasswordVisualTransformation(),
99|                    enabled = !vm.saving,
100|                    modifier = Modifier.fillMaxWidth()
101|                )
102|                Button(
103|                    onClick = { vm.login(ctx, baseUrl, username, password, onSaved) },
104|                    modifier = Modifier.fillMaxWidth(),
105|                    enabled = !vm.saving
106|                ) {
107|                    Text(if (vm.saving) "登录中..." else "登录 / 保存")
108|                }
109|
110|                HorizontalDivider()
111|
112|                Text("AI 分析（DeepSeek）", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
113|                OutlinedTextField(
114|                    value = deepseekKey,
115|                    onValueChange = { deepseekKey = it },
116|                    label = { Text("DeepSeek API Key") },
117|                    singleLine = true,
118|                    visualTransformation = if (showKey) PasswordVisualTransformation.None else PasswordVisualTransformation(),
119|                    enabled = !vm.saving,
120|                    modifier = Modifier.fillMaxWidth()
121|                )
122|                Row(verticalAlignment = Alignment.CenterVertically) {
123|                    Checkbox(checked = showKey, onCheckedChange = { showKey = it })
124|                    Text("显示密钥")
125|                }
126|                Text(
127|                    "密钥仅 AES 加密保存在本机，只发送给 DeepSeek，不会上传到你的销售服务器。",
128|                    style = MaterialTheme.typography.bodySmall,
129|                    color = MaterialTheme.colorScheme.outline
130|                )
131|                Button(
132|                    onClick = { vm.saveDeepSeekKey(ctx, deepseekKey, onSaved) },
133|                    modifier = Modifier.fillMaxWidth(),
134|                    enabled = !vm.saving
135|                ) {
136|                    Text("保存 API Key")
137|                }
138|
139|                HorizontalDivider()
140|
141|                Text("语音识别引擎", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
142|                Text(
143|                    "选择按住说话时使用的输入法引擎。默认=系统当前输入法；选了指定引擎后，语音按钮会直接调用它（Android 13+ 生效）。",
144|                    style = MaterialTheme.typography.bodySmall,
145|                    color = MaterialTheme.colorScheme.outline
146|                )
147|                val engines = remember {
148|                    RecognitionServices.list(ctx)
149|                }
150|                val currentSel = remember {
151|                    RecognitionServices.getSelection(ctx)
152|                }
153|                if (engines.isEmpty()) {
154|                    Text("未检测到可用的语音识别服务，请确认已安装带语音的输入法。",
155|                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
156|                } else {
157|                    engines.forEach { opt ->
158|                        val selected = opt.packageName == currentSel?.first && opt.className == currentSel?.second
159|                        Row(
160|                            modifier = Modifier.fillMaxWidth(),
161|                            verticalAlignment = Alignment.CenterVertically
162|                        ) {
163|                            RadioButton(
164|                                selected = selected,
165|                                onClick = {
166|                                    RecognitionServices.saveSelection(ctx, opt.packageName, opt.className)
167|                                }
168|                            )
169|                            Column {
170|                                Text(opt.label, style = MaterialTheme.typography.bodyMedium)
171|                                if (opt.isDefault) {
172|                                    Text("（系统默认）", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
173|                                }
174|                            }
175|                        }
176|                    }
177|                    // 清除指定 → 回到系统默认
178|                    TextButton(onClick = {
179|                        RecognitionServices.clearSelection(ctx)
180|                    }) { Text("恢复系统默认引擎") }
181|                }
182|            }
183|        },
184|        confirmButton = {
185|            TextButton(onClick = { onDismiss() }, enabled = !vm.saving) { Text("关闭") }
186|        }
187|    )
188|
189|    val msg = vm.toast
190|    if (msg != null) {
191|        LaunchedEffect(msg) {
192|            android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_LONG).show()
193|            vm.toast = null
194|        }
195|    }
196|}
197|
package com.novaprod.dropthink.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.novaprod.dropthink.data.ChatMessage
import com.novaprod.dropthink.data.ModelEntry
import com.novaprod.dropthink.data.ModelStatus
import com.novaprod.dropthink.mcp.McpStatus
import com.novaprod.dropthink.tools.LocalTools
import com.novaprod.dropthink.tools.ToolRegistry
import com.novaprod.dropthink.tools.key
import com.novaprod.dropthink.util.formatBytes

@Composable
fun AppRoot(vm: MainViewModel) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val titles = listOf("Chat", "Models", "Tools", "Settings")
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                TabRow(selectedTabIndex = tab) {
                    titles.forEachIndexed { i, t -> Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t) }) }
                }
                when (tab) {
                    0 -> ChatScreen(vm)
                    1 -> ModelsScreen(vm)
                    2 -> ToolsScreen(vm)
                    else -> SettingsScreen(vm)
                }
            }
        }
    }
}

@Composable
fun ChatScreen(vm: MainViewModel) {
    val messages by vm.messages.collectAsState()
    val generating by vm.generating.collectAsState()
    val loaded by vm.loaded.collectAsState()
    val status by vm.status.collectAsState()
    var input by rememberSaveable { mutableStateOf("") }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    val cfg by vm.settings.collectAsState()
    val listState = rememberLazyListState()
    LaunchedEffect(messages.size) { listState.scrollToItem(0) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                loaded?.let { "Model: ${vm.modelName(it.entryId)}" } ?: "No model loaded — open the Models tab",
                Modifier.weight(1f), style = MaterialTheme.typography.labelMedium,
            )
            TextButton(onClick = { vm.clearChat() }, enabled = !generating && messages.isNotEmpty()) { Text("New chat") }
        }
        status?.let { Text(it, Modifier.padding(horizontal = 12.dp), style = MaterialTheme.typography.labelSmall) }
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(), state = listState, reverseLayout = true,
            contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(messages.asReversed(), key = { it.id }) { MessageBubble(it) }
        }
        if (showSettings) ChatSettingsDialog(vm) { showSettings = false }
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.Bottom) {
            TextButton(onClick = { showSettings = true }, contentPadding = PaddingValues(horizontal = 6.dp)) {
                Text(if (cfg.toolsEnabled && cfg.selectedTools.isNotEmpty()) "⚙ ${cfg.selectedTools.size}" else "⚙")
            }
            OutlinedTextField(
                value = input, onValueChange = { input = it }, modifier = Modifier.weight(1f),
                placeholder = { Text("Message") }, maxLines = 5,
            )
            Spacer(Modifier.width(8.dp))
            if (generating) Button(onClick = { vm.stop() }) { Text("Stop") }
            else Button(onClick = { vm.send(input); input = "" }, enabled = loaded != null && input.isNotBlank()) { Text("Send") }
        }
    }
}

@Composable
fun ToolCheck(title: String, subtitle: String, checked: Boolean, onToggle: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onToggle() }.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = { onToggle() })
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            if (subtitle.isNotBlank()) Text(subtitle.replace("\n", " ").take(90), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun ChatSettingsDialog(vm: MainViewModel, onDismiss: () -> Unit) {
    val s by vm.settings.collectAsState()
    val states by vm.mcp.states.collectAsState()
    val configs by vm.mcp.configs.collectAsState()
    var page by rememberSaveable { mutableIntStateOf(0) }
    LaunchedEffect(page) {
        if (page == 1) configs.filter { it.enabled }.forEach { if (states[it.id]?.status != McpStatus.CONNECTED) vm.connectServer(it.id) }
    }
    fun toggle(keys: List<String>, on: Boolean) = vm.updateSettings { c -> c.copy(selectedTools = if (on) c.selectedTools + keys else c.selectedTools - keys.toSet()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (page == 0) "Chat settings" else "Select tools") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (page == 0) {
                    SwitchRow("Thinking", s.thinking) { v -> vm.updateSettings { it.copy(thinking = v) } }
                    SwitchRow("Tools", s.toolsEnabled) { v -> vm.updateSettings { it.copy(toolsEnabled = v) } }
                    if (s.toolsEnabled) {
                        TextButton(onClick = { page = 1 }) {
                            Text(if (s.selectedTools.isEmpty()) "Select tools ›  (none: automatic routing)" else "Select tools ›  (${s.selectedTools.size} selected)")
                        }
                        Text(
                            "With tools selected, the app sends the model instructions on how to use them before your message, and requires a tool call before any answer.",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                } else {
                    Text("Built-in", style = MaterialTheme.typography.labelLarge)
                    LocalTools.specs.forEach { t -> ToolCheck(t.name, t.description, t.key in s.selectedTools) { toggle(listOf(t.key), t.key !in s.selectedTools) } }
                    configs.forEach { c ->
                        val st = states[c.id]
                        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(c.name, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                            val tools = st?.tools.orEmpty()
                            if (!c.enabled) Text("disabled", style = MaterialTheme.typography.labelSmall)
                            else if (st?.status == McpStatus.CONNECTED) {
                                val keys = tools.map { "${c.id}/${it.name}" }
                                val all = keys.isNotEmpty() && keys.all { it in s.selectedTools }
                                TextButton(onClick = { toggle(keys, !all) }) { Text(if (all) "None" else "All") }
                            } else TextButton(onClick = { vm.connectServer(c.id) }) { Text(if (st?.status == McpStatus.CONNECTING) "Connecting…" else "Connect") }
                        }
                        if (c.enabled && st?.status == McpStatus.ERROR) Text("Error: ${st.error}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                        if (c.enabled) st?.tools.orEmpty().forEach { t ->
                            val k = "${c.id}/${t.name}"
                            ToolCheck(t.name, t.description, k in s.selectedTools) { toggle(listOf(k), k !in s.selectedTools) }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { if (page == 1) page = 0 else onDismiss() }) { Text(if (page == 1) "Back" else "Done") } },
        dismissButton = if (page == 1) ({ TextButton(onClick = onDismiss) { Text("Close") } }) else null,
    )
}

@Composable
fun ReasoningBlock(text: String) {
    var open by remember { mutableStateOf(false) }
    Text(
        if (open) "▾ Reasoning" else "▸ Reasoning (${text.length} chars)",
        Modifier.clickable { open = !open }, style = MaterialTheme.typography.labelMedium,
    )
    if (open) Text(text, fontStyle = FontStyle.Italic, style = MaterialTheme.typography.bodySmall)
}

@Composable
fun MessageBubble(m: ChatMessage) {
    val isUser = m.role == "user"
    val container = when {
        isUser -> MaterialTheme.colorScheme.primaryContainer
        m.role == "tool" || m.kind == "toolcall" -> MaterialTheme.colorScheme.tertiaryContainer
        m.isError -> MaterialTheme.colorScheme.errorContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    Card(colors = CardDefaults.cardColors(containerColor = container), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(10.dp)) {
            Text(
                when {
                    isUser -> "You"
                    m.kind == "toolcall" -> "Tool call"
                    m.kind == "toolresult" -> if (m.isError) "Tool result (failed)" else "Tool result"
                    else -> "Assistant"
                },
                style = MaterialTheme.typography.labelSmall,
            )
            if (m.reasoning.isNotBlank()) ReasoningBlock(m.reasoning)
            if (m.kind == "toolresult") {
                var open by remember { mutableStateOf(false) }
                SelectionContainer { Text(if (open) m.content else m.content.take(300) + if (m.content.length > 300) "…" else "", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
                if (m.content.length > 300) Text(if (open) "Show less" else "Show full result", Modifier.clickable { open = !open }, style = MaterialTheme.typography.labelMedium)
            } else {
                val body = m.content.ifEmpty { if (!isUser && m.footer.isEmpty()) "…" else "" }
                if (body.isNotEmpty()) SelectionContainer {
                    Text(body, fontFamily = if (m.kind == "toolcall") FontFamily.Monospace else FontFamily.Default)
                }
            }
            if (m.notice.isNotBlank()) Text(m.notice, style = MaterialTheme.typography.labelSmall)
            if (m.sources.isNotEmpty()) {
                Text("Sources (from tool results):", style = MaterialTheme.typography.labelMedium)
                SelectionContainer { Text(m.sources.joinToString("\n"), style = MaterialTheme.typography.bodySmall) }
            }
            if (m.footer.isNotBlank()) {
                Text(m.footer, style = MaterialTheme.typography.labelSmall, color = if (m.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
fun ModelsScreen(vm: MainViewModel) {
    val models by vm.models.collectAsState()
    val loaded by vm.loaded.collectAsState()
    val busy by vm.busy.collectAsState()
    val status by vm.status.collectAsState()
    val generating by vm.generating.collectAsState()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) vm.importModel(uri) }
    val ram = remember(models, loaded) { vm.memoryLine() }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Button(onClick = { picker.launch(arrayOf("*/*")) }, enabled = busy == null) { Text("Import GGUF from device…") }
            Text("The file is linked in place and not copied. Removing it from this list never deletes your file.", style = MaterialTheme.typography.bodySmall)
            Text(ram, style = MaterialTheme.typography.labelMedium)
            busy?.let { Text(it) }
            status?.let { Text(it) }
        }
        items(models, key = { it.id }) { e -> ModelCard(e, loaded?.entryId == e.id, busy == null && !generating, vm) }
    }
}

@Composable
fun ModelCard(e: ModelEntry, isLoaded: Boolean, canAct: Boolean, vm: MainViewModel) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(e.displayName, style = MaterialTheme.typography.titleSmall)
            Text("${e.fileName} · ${formatBytes(e.sizeBytes)}", style = MaterialTheme.typography.bodySmall)
            Text(
                "arch: ${e.arch.ifBlank { "?" }} · quant: ${e.quantFromName.ifBlank { "?" }} (file type: ${e.ftype.ifBlank { "?" }}) · trained ctx: ${e.nCtxTrain}",
                style = MaterialTheme.typography.bodySmall,
            )
            if (e.status != ModelStatus.INCOMPATIBLE && !e.templateSupported) Text("Chat template: ChatML fallback (model's own template not natively supported)", style = MaterialTheme.typography.bodySmall)
            Text(
                "status: ${e.status.name.lowercase().replace('_', ' ')}${if (e.detail.isNotBlank()) " — ${e.detail}" else ""}",
                style = MaterialTheme.typography.bodySmall,
                color = if (e.status == ModelStatus.INCOMPATIBLE) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (isLoaded) OutlinedButton(onClick = { vm.unloadModel() }, enabled = canAct) { Text("Unload") }
                else Button(onClick = { vm.loadModel(e.id) }, enabled = canAct && e.status != ModelStatus.INCOMPATIBLE) { Text("Load") }
                OutlinedButton(onClick = { vm.removeModel(e.id) }, enabled = canAct) { Text("Remove from list") }
            }
        }
    }
}

@Composable
fun ToolsScreen(vm: MainViewModel) {
    val configs by vm.mcp.configs.collectAsState()
    val states by vm.mcp.states.collectAsState()
    val log by vm.toolLog.entries.collectAsState()
    var testTool by rememberSaveable { mutableStateOf("") }
    var testArgs by rememberSaveable { mutableStateOf("{}") }
    var testOut by remember { mutableStateOf("") }
    val all = remember(states) { ToolRegistry.build(states) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Remote tool servers (MCP)", style = MaterialTheme.typography.titleMedium)
        Text(
            "These three servers are online services. When a request needs them, the search terms, URLs or place names are sent to them. " +
                "They need an internet connection; local chat and local tools never use the network. Servers connect only when you tap Connect or when a request needs them.",
            style = MaterialTheme.typography.bodySmall,
        )
        configs.forEach { c ->
            val st = states[c.id]
            var token by remember(c.id) { mutableStateOf("") }
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(c.name, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                        Switch(checked = c.enabled, onCheckedChange = { vm.setServerEnabled(c.id, it) })
                    }
                    Text(c.url, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
                    Text(c.purpose, style = MaterialTheme.typography.bodySmall)
                    val line = when (st?.status ?: McpStatus.IDLE) {
                        McpStatus.IDLE -> "Not connected"
                        McpStatus.CONNECTING -> "Connecting…"
                        McpStatus.CONNECTED -> "Connected · protocol ${st?.protocol} · ${st?.tools?.size} tools · ${st?.latencyMs} ms"
                        McpStatus.ERROR -> "Error: ${st?.error}"
                    }
                    Text(line, style = MaterialTheme.typography.labelMedium, color = if (st?.status == McpStatus.ERROR) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                    if (st != null && st.tools.isNotEmpty()) Text("Tools: " + st.tools.joinToString { it.name }, style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { vm.connectServer(c.id) }, enabled = c.enabled && st?.status != McpStatus.CONNECTING) { Text(if (st?.status == McpStatus.CONNECTED) "Refresh tools" else "Connect / test") }
                    }
                    OutlinedTextField(
                        value = token, onValueChange = { token = it }, label = { Text(if (vm.mcp.hasToken(c.id)) "Optional API key (saved)" else "Optional API key (not needed)") },
                        singleLine = true, modifier = Modifier.fillMaxWidth(),
                    )
                    TextButton(onClick = { vm.setToken(c.id, token); token = "" }) { Text(if (token.isBlank()) "Remove saved key" else "Save key (encrypted)") }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { vm.restoreServers() }) { Text("Restore default servers") }
            OutlinedButton(onClick = { vm.clearCredentials() }) { Text("Delete saved keys") }
        }
        HorizontalDivider()
        Text("Tool test", style = MaterialTheme.typography.titleMedium)
        Text("Run any tool directly, without the model.", style = MaterialTheme.typography.bodySmall)
        all.forEach { t ->
            if (testTool == t.name) Button(onClick = { testTool = t.name }) { Text(t.name) } else OutlinedButton(onClick = { testTool = t.name; testArgs = "{}" }) { Text(t.name) }
        }
        all.firstOrNull { it.name == testTool }?.let { Text(it.schema.toString().take(600), style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace) }
        OutlinedTextField(value = testArgs, onValueChange = { testArgs = it }, label = { Text("Arguments (JSON)") }, modifier = Modifier.fillMaxWidth())
        Button(onClick = { testOut = "Running…"; vm.testTool(testTool, testArgs) { testOut = it } }, enabled = testTool.isNotBlank()) { Text("Run tool") }
        if (testOut.isNotBlank()) SelectionContainer { Text(testOut, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
        HorizontalDivider()
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Routing / tool log", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = { vm.toolLog.clear() }) { Text("Clear") }
        }
        if (log.isEmpty()) Text("Nothing yet.", style = MaterialTheme.typography.bodySmall)
        log.take(40).forEach { e ->
            val ts = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date(e.ts))
            Text(
                "$ts [${e.route}] ${e.tool} ${if (e.tool == "-") "" else if (e.ok) "✓" else "✗"} ${e.ms} ms\n${e.note}${if (e.args.isNotEmpty()) "\nargs: ${e.args}" else ""}${if (e.preview.isNotEmpty()) "\n→ ${e.preview}" else ""}",
                style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace,
            )
        }
    }
}

@Composable
fun IntField(label: String, value: Int, onValue: (Int) -> Unit) {
    var text by remember { mutableStateOf(value.toString()) }
    OutlinedTextField(
        value = text, onValueChange = { text = it; it.trim().toIntOrNull()?.let(onValue) }, label = { Text(label) },
        singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
fun FloatField(label: String, value: Float, onValue: (Float) -> Unit) {
    var text by remember { mutableStateOf(value.toString()) }
    OutlinedTextField(
        value = text, onValueChange = { text = it; it.trim().toFloatOrNull()?.let(onValue) }, label = { Text(label) },
        singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
fun SettingsScreen(vm: MainViewModel) {
    val s by vm.settings.collectAsState()
    val loaded by vm.loaded.collectAsState()
    val needsReload = loaded?.config?.let { c -> c.nCtx != s.nCtx || c.nBatch != s.nBatch || c.nUbatch != s.nUbatch || c.threads != s.threads || c.useMmap != s.useMmap } ?: false
    val sam = s.presets[s.activePreset.coerceIn(0, s.presets.lastIndex)].sampling
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Prompt", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(value = s.systemPrompt, onValueChange = { v -> vm.updateSettings { it.copy(systemPrompt = v) } }, label = { Text("System prompt") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
        SwitchRow("Thinking mode (templates with <think>)", s.thinking) { v -> vm.updateSettings { it.copy(thinking = v) } }
        HorizontalDivider()
        Text("Sampling preset", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            s.presets.forEachIndexed { i, p ->
                if (i == s.activePreset) Button(onClick = {}) { Text(p.name) } else OutlinedButton(onClick = { vm.updateSettings { it.copy(activePreset = i) } }) { Text(p.name) }
            }
        }
        Text("The tool-calling preset is used automatically whenever tools are exposed for a request.", style = MaterialTheme.typography.bodySmall)
        key(s.activePreset) {
            FloatField("Temperature (0 = greedy)", sam.temperature) { v -> vm.updateSampling { it.copy(temperature = v) } }
            FloatField("Top-p", sam.topP) { v -> vm.updateSampling { it.copy(topP = v) } }
            IntField("Top-k (0 = off)", sam.topK) { v -> vm.updateSampling { it.copy(topK = v) } }
            FloatField("Min-p", sam.minP) { v -> vm.updateSampling { it.copy(minP = v) } }
            FloatField("Repetition penalty (1 = off)", sam.repeatPenalty) { v -> vm.updateSampling { it.copy(repeatPenalty = v) } }
            FloatField("Presence penalty", sam.presencePenalty) { v -> vm.updateSampling { it.copy(presencePenalty = v) } }
            FloatField("Frequency penalty", sam.frequencyPenalty) { v -> vm.updateSampling { it.copy(frequencyPenalty = v) } }
            IntField("Penalty window (tokens)", sam.penaltyLastN) { v -> vm.updateSampling { it.copy(penaltyLastN = v) } }
            IntField("Seed (-1 = random)", sam.seed) { v -> vm.updateSampling { it.copy(seed = v) } }
        }
        OutlinedButton(onClick = { vm.resetPresets() }) { Text("Reset presets to defaults") }
        HorizontalDivider()
        Text("Output length", style = MaterialTheme.typography.titleMedium)
        IntField("Max tokens per pass", s.maxTokensPerPass) { v -> vm.updateSettings { it.copy(maxTokensPerPass = v.coerceAtLeast(16)) } }
        IntField("Total output budget (tokens)", s.totalOutputBudget) { v -> vm.updateSettings { it.copy(totalOutputBudget = v.coerceAtLeast(16)) } }
        SwitchRow("Auto-continue when a pass hits its limit", s.autoContinue) { v -> vm.updateSettings { it.copy(autoContinue = v) } }
        IntField("Max continuations", s.maxContinuations) { v -> vm.updateSettings { it.copy(maxContinuations = v.coerceAtLeast(0)) } }
        HorizontalDivider()
        Text("Tools", style = MaterialTheme.typography.titleMedium)
        SwitchRow("Enable tool routing", s.toolsEnabled) { v -> vm.updateSettings { it.copy(toolsEnabled = v) } }
        IntField("Max tools exposed per request", s.maxToolsExposed) { v -> vm.updateSettings { it.copy(maxToolsExposed = v.coerceAtLeast(1)) } }
        IntField("Max tool calls per request", s.maxToolCalls) { v -> vm.updateSettings { it.copy(maxToolCalls = v.coerceAtLeast(1)) } }
        IntField("Correction retries", s.toolRetries) { v -> vm.updateSettings { it.copy(toolRetries = v.coerceAtLeast(0)) } }
        IntField("Max tool result characters", s.maxToolResultChars) { v -> vm.updateSettings { it.copy(maxToolResultChars = v.coerceAtLeast(500)) } }
        HorizontalDivider()
        Text("Runtime (applies on next model load)", style = MaterialTheme.typography.titleMedium)
        IntField("Context length", s.nCtx) { v -> vm.updateSettings { it.copy(nCtx = v) } }
        IntField("Batch size", s.nBatch) { v -> vm.updateSettings { it.copy(nBatch = v) } }
        IntField("Micro-batch size", s.nUbatch) { v -> vm.updateSettings { it.copy(nUbatch = v) } }
        IntField("CPU threads (0 = auto)", s.threads) { v -> vm.updateSettings { it.copy(threads = v.coerceAtLeast(0)) } }
        SwitchRow("Memory-map model file", s.useMmap) { v -> vm.updateSettings { it.copy(useMmap = v) } }
        if (needsReload) Text("These changes need the model to be reloaded (Models → Unload, then Load).", color = MaterialTheme.colorScheme.error)
        HorizontalDivider()
        Text("Not available in this build", style = MaterialTheme.typography.titleMedium)
        Text(
            "• GPU offload: this build is CPU-only; no GPU backend is active.\n• KV-cache quantization: not implemented (the runtime's default cache is used).\n" +
                "• Stop sequences, manual chat-template override, embeddings/RAG, Hugging Face download: not implemented yet.",
            style = MaterialTheme.typography.bodySmall,
        )
        HorizontalDivider()
        Text("Runtime info", style = MaterialTheme.typography.titleMedium)
        loaded?.let { l ->
            Text(
                "Loaded in ${l.loadMs} ms\n" + l.info.entries.joinToString("\n") { "${it.key}: ${it.value}" } + "\n" + l.ctxInfo.entries.joinToString("\n") { "${it.key}: ${it.value}" },
                fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelSmall,
            )
        }
        Text(vm.backendInfo, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelSmall)
    }
}

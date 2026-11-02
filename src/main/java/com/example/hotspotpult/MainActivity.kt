package com.example.hotspotpult

import android.app.Application
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Три цвета, которые пользователь выбирает сам. Остальное считается из них. */
data class AppTheme(val bg: Color, val accent: Color, val text: Color) {
    val surface: Color get() = lerp(bg, text, 0.08f)
    val muted: Color get() = lerp(bg, text, 0.55f)
    val onAccent: Color get() = if (accent.luminance() > 0.5f) Color(0xFF0E1216) else Color.White
    val pause: Color get() = if (bg.luminance() > 0.5f) Color(0xFFB9730F) else Color(0xFFF2B35C)
}

val DefaultTheme = AppTheme(
    bg = Color(0xFF0E1216),
    accent = Color(0xFF5BE0A8),
    text = Color(0xFFE8EEF2),
)

val BgPresets = listOf(
    Color(0xFF0E1216), Color(0xFF000000), Color(0xFF0B1630),
    Color(0xFF1E1B2E), Color(0xFFE9EDF1), Color(0xFFFFFFFF),
)
val AccentPresets = listOf(
    Color(0xFF5BE0A8), Color(0xFF5AA9FF), Color(0xFFA78BFA),
    Color(0xFFFF7AA8), Color(0xFFFF9F5A), Color(0xFFFFD25A),
)
val TextPresets = listOf(
    Color(0xFFE8EEF2), Color(0xFFFFFFFF), Color(0xFFB8C2CC),
    Color(0xFF101418), Color(0xFF2B3640), Color(0xFF5BE0A8),
)

data class Dev(val mac: String, val name: String, val paused: Boolean)
data class UiState(
    val devices: List<Dev> = emptyList(),
    val serviceOn: Boolean = false,
    val busy: Boolean = false,
    val message: String = "",
)

class VM(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("hotspot", 0)
    private val _state = MutableStateFlow(UiState())
    val state = _state.asStateFlow()
    private var autoTried = false

    private fun loadTheme() = AppTheme(
        bg = Color(prefs.getInt("t_bg", DefaultTheme.bg.toArgb())),
        accent = Color(prefs.getInt("t_accent", DefaultTheme.accent.toArgb())),
        text = Color(prefs.getInt("t_text", DefaultTheme.text.toArgb())),
    )

    private val _theme = MutableStateFlow(loadTheme())
    val theme = _theme.asStateFlow()

    fun setTheme(t: AppTheme) {
        prefs.edit()
            .putInt("t_bg", t.bg.toArgb())
            .putInt("t_accent", t.accent.toArgb())
            .putInt("t_text", t.text.toArgb())
            .apply()
        _theme.value = t
    }

    fun resetTheme() = setTheme(DefaultTheme)

    private fun getMap(key: String): Map<String, String> =
        prefs.getStringSet(key, emptySet())!!.mapNotNull {
            val i = it.indexOf('=')
            if (i < 0) null else it.substring(0, i) to it.substring(i + 1)
        }.toMap()

    private fun putMap(key: String, m: Map<String, String>) {
        prefs.edit().putStringSet(key, m.map { "${it.key}=${it.value}" }.toSet()).apply()
    }

    private fun paused(): Set<String> = prefs.getStringSet("paused", emptySet())!!.toSet()

    private fun msg(s: String) { _state.value = _state.value.copy(message = s) }
    private fun busy(b: Boolean, s: String? = null) {
        _state.value = _state.value.copy(busy = b, message = s ?: _state.value.message)
    }

    fun rebuild() {
        val paused = paused()
        val known = getMap("known")
        val names = getMap("names")
        val macs = known.keys + paused
        val devs = macs.map { mac ->
            Dev(
                mac = mac,
                name = names[mac] ?: known[mac]?.takeIf { it.isNotBlank() } ?: "Устройство ${mac.takeLast(5)}",
                paused = mac in paused,
            )
        }.sortedWith(compareBy({ it.paused }, { it.name }))
        _state.value = _state.value.copy(devices = devs, serviceOn = HotspotAutomationService.instance != null)
    }

    /** При первом запуске один раз сам читает список устройств. */
    fun onStart() {
        rebuild()
        if (!autoTried && HotspotAutomationService.instance != null && getMap("known").isEmpty() && !_state.value.busy) {
            autoTried = true
            sync()
        }
    }

    fun sync() {
        val svc = HotspotAutomationService.instance
        if (svc == null) { msg("Сначала включите доступ в специальных возможностях."); return }
        if (_state.value.busy) return
        viewModelScope.launch {
            busy(true, "Читаю список устройств в настройках…")
            val res = svc.syncDevices()
            if (res == null) {
                msg("Не открылся список подключений. Включите раздачу и повторите.")
            } else {
                val old = getMap("known")
                val p = paused()
                val fresh = LinkedHashMap<String, String>()
                res.forEach { fresh[it.mac] = it.name }
                p.forEach { if (it !in fresh) fresh[it] = old[it] ?: "" }
                putMap("known", fresh)
                msg("Найдено устройств: ${res.size}")
            }
            busy(false)
            rebuild()
        }
    }

    fun toggle(mac: String) {
        val svc = HotspotAutomationService.instance
        if (svc == null) { msg("Сначала включите доступ в специальных возможностях."); return }
        if (_state.value.busy) return
        val isPaused = mac in paused()
        val name = _state.value.devices.firstOrNull { it.mac == mac }?.name ?: mac
        viewModelScope.launch {
            busy(true, if (isPaused) "Возвращаю $name…" else "Ставлю $name на паузу…")
            val ok = if (isPaused) svc.unblock(listOf(mac)) else svc.block(listOf(mac))
            if (ok) {
                val set = paused().toMutableSet()
                if (isPaused) set.remove(mac) else set.add(mac)
                prefs.edit().putStringSet("paused", set).apply()
                msg(if (isPaused) "$name снова в сети" else "$name на паузе")
            } else {
                msg("Не получилось нажать нужные кнопки. Попробуйте ещё раз при включённой раздаче.")
            }
            busy(false)
            rebuild()
        }
    }

    fun resumeAll() {
        val svc = HotspotAutomationService.instance
        if (svc == null) { msg("Сначала включите доступ в специальных возможностях."); return }
        if (_state.value.busy) return
        val list = paused().toList()
        if (list.isEmpty()) return
        viewModelScope.launch {
            busy(true, "Возвращаю всех в сеть…")
            val ok = svc.unblock(list)
            if (ok) {
                prefs.edit().putStringSet("paused", emptySet()).apply()
                msg("Все устройства снова в сети")
            } else {
                msg("Часть устройств вернуть не удалось. Проверьте чёрный список в настройках.")
            }
            busy(false)
            rebuild()
        }
    }

    fun rename(mac: String, newName: String) {
        val map = getMap("names").toMutableMap()
        if (newName.isBlank()) map.remove(mac) else map[mac] = newName.trim()
        putMap("names", map)
        rebuild()
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val vm: VM = viewModel()
            val theme by vm.theme.collectAsStateWithLifecycle()
            val scheme = if (theme.bg.luminance() > 0.5f) {
                lightColorScheme(background = theme.bg, surface = theme.surface, primary = theme.accent, onSurface = theme.text)
            } else {
                darkColorScheme(background = theme.bg, surface = theme.surface, primary = theme.accent, onSurface = theme.text)
            }
            val view = LocalView.current
            SideEffect {
                val w = (view.context as? ComponentActivity)?.window ?: return@SideEffect
                val light = theme.bg.luminance() > 0.5f
                val c = WindowCompat.getInsetsController(w, view)
                c.isAppearanceLightStatusBars = light
                c.isAppearanceLightNavigationBars = light
            }
            MaterialTheme(colorScheme = scheme) {
                var showSettings by remember { mutableStateOf(false) }
                BackHandler(enabled = showSettings) { showSettings = false }
                if (showSettings) {
                    SettingsScreen(
                        t = theme,
                        onChange = { vm.setTheme(it) },
                        onReset = { vm.resetTheme() },
                        onBack = { showSettings = false },
                    )
                } else {
                    MainScreen(vm, theme, onSettings = { showSettings = true })
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MainScreen(vm: VM, t: AppTheme, onSettings: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val owner = LocalLifecycleOwner.current
    val ctx = LocalContext.current
    LaunchedEffect(owner) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            vm.onStart()
            while (true) { delay(1000); vm.rebuild() }
        }
    }
    var renaming by remember { mutableStateOf<Dev?>(null) }
    val pausedCount = state.devices.count { it.paused }

    Column(
        Modifier.fillMaxSize().background(t.bg).systemBarsPadding().padding(horizontal = 16.dp),
    ) {
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("ТОЧКА ДОСТУПА", color = t.muted, fontSize = 12.sp, letterSpacing = 1.4.sp)
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { vm.sync() }, enabled = state.serviceOn && !state.busy) {
                    Text("Обновить", color = if (state.serviceOn && !state.busy) t.accent else t.muted, fontSize = 13.sp)
                }
                TextButton(onClick = onSettings) {
                    Text("Оформление", color = t.accent, fontSize = 13.sp)
                }
            }
        }
        Text("Кто сейчас в сети", color = t.text, fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Text(
            "${state.devices.size - pausedCount} в сети · $pausedCount на паузе",
            color = t.muted, fontSize = 14.sp,
        )
        Spacer(Modifier.height(10.dp))
        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth(), color = t.accent, trackColor = t.surface)
        Spacer(Modifier.height(10.dp))

        if (!state.serviceOn) {
            Column(
                Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("Нужен доступ «Специальные возможности»", color = t.text, fontWeight = FontWeight.Bold,
                    fontSize = 17.sp, textAlign = TextAlign.Center)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Приложение само нажимает те же кнопки, что и вы в настройках точки доступа. " +
                        "Откройте настройки, найдите «Хотспот Пульт» и включите его. " +
                        "Если переключатель серый, откройте карточку приложения, нажмите ⋮ и выберите «Разрешить ограниченные настройки».",
                    color = t.muted, fontSize = 14.sp, textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = { ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
                    colors = ButtonDefaults.buttonColors(containerColor = t.accent, contentColor = t.onAccent),
                ) { Text("Открыть настройки", fontWeight = FontWeight.Bold) }
            }
        } else if (state.devices.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(
                    "Список пуст. Включите раздачу, подключите устройство и нажмите «Обновить».",
                    color = t.muted, textAlign = TextAlign.Center, fontSize = 15.sp,
                )
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalArrangement = Arrangement.spacedBy(22.dp),
            ) {
                items(state.devices, key = { it.mac }) { d ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        val color = if (d.paused) t.pause else t.accent
                        Box(
                            Modifier
                                .fillMaxWidth().aspectRatio(1f)
                                .clip(CircleShape)
                                .background(if (d.paused) Color.Transparent else t.surface)
                                .border(2.dp, color, CircleShape)
                                .combinedClickable(
                                    onClick = { vm.toggle(d.mac) },
                                    onLongClick = { renaming = d },
                                )
                                .padding(16.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(d.name, color = if (d.paused) t.muted else t.text, fontWeight = FontWeight.Bold,
                                    fontSize = 15.sp, textAlign = TextAlign.Center, maxLines = 2)
                                Text(if (d.paused) "ПАУЗА" else "В СЕТИ", color = color, fontSize = 11.sp, letterSpacing = 1.2.sp)
                            }
                        }
                        Text(d.mac, color = t.muted, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                    }
                }
            }
        }

        Text(
            state.message.ifEmpty { "Тап: пауза или возврат. Долгое нажатие: переименовать." },
            color = t.muted, fontSize = 12.sp, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        )
        Button(
            onClick = { vm.resumeAll() },
            enabled = pausedCount > 0 && state.serviceOn && !state.busy,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = t.accent, contentColor = t.onAccent,
                disabledContainerColor = t.surface, disabledContentColor = t.muted,
            ),
        ) { Text("Вернуть всех в сеть", fontWeight = FontWeight.Bold, fontSize = 16.sp) }
        Spacer(Modifier.height(12.dp))
    }

    renaming?.let { d ->
        var text by remember(d.mac) { mutableStateOf(d.name) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            containerColor = t.surface,
            titleContentColor = t.text,
            textContentColor = t.text,
            title = { Text("Название устройства") },
            text = { OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true, colors = fieldColors(t)) },
            confirmButton = { TextButton(onClick = { vm.rename(d.mac, text); renaming = null }) { Text("Сохранить", color = t.accent) } },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("Отмена", color = t.muted) } },
        )
    }
}

@Composable
fun fieldColors(t: AppTheme) = OutlinedTextFieldDefaults.colors(
    focusedTextColor = t.text,
    unfocusedTextColor = t.text,
    focusedBorderColor = t.accent,
    unfocusedBorderColor = t.muted,
    cursorColor = t.accent,
    focusedLabelColor = t.accent,
    unfocusedLabelColor = t.muted,
    focusedPrefixColor = t.text,
    unfocusedPrefixColor = t.muted,
)

@Composable
fun SettingsScreen(t: AppTheme, onChange: (AppTheme) -> Unit, onReset: () -> Unit, onBack: () -> Unit) {
    Column(
        Modifier.fillMaxSize().background(t.bg).systemBarsPadding().padding(horizontal = 16.dp),
    ) {
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("← Назад", color = t.accent, fontSize = 15.sp) }
        }
        Text("Оформление", color = t.text, fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        Text("Цвета меняются сразу. Выберите готовый или введите свой код.", color = t.muted, fontSize = 14.sp)
        Spacer(Modifier.height(16.dp))

        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(22.dp),
        ) {
            ColorPicker("Цвет фона", t.bg, BgPresets, t) { onChange(t.copy(bg = it)) }
            ColorPicker("Цвет кнопок и кружков", t.accent, AccentPresets, t) { onChange(t.copy(accent = it)) }
            ColorPicker("Цвет текста", t.text, TextPresets, t) { onChange(t.copy(text = it)) }
            Spacer(Modifier.height(4.dp))
        }

        OutlinedButton(
            onClick = onReset,
            modifier = Modifier.fillMaxWidth().height(48.dp),
        ) { Text("Вернуть стандартные цвета", color = t.accent) }
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
fun ColorPicker(title: String, current: Color, presets: List<Color>, t: AppTheme, onPick: (Color) -> Unit) {
    var hex by remember(current) { mutableStateOf(String.format("%06X", current.toArgb() and 0xFFFFFF)) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, color = t.text, fontWeight = FontWeight.Bold, fontSize = 15.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            presets.forEach { c ->
                val selected = c.toArgb() == current.toArgb()
                Box(
                    Modifier
                        .size(42.dp)
                        .clip(CircleShape)
                        .background(c)
                        .border(if (selected) 3.dp else 1.dp, if (selected) t.accent else t.muted, CircleShape)
                        .clickable { onPick(c) },
                )
            }
        }
        OutlinedTextField(
            value = hex,
            onValueChange = { v ->
                val cleaned = v.uppercase().filter { it in "0123456789ABCDEF" }.take(6)
                hex = cleaned
                if (cleaned.length == 6) onPick(Color(0xFF000000.toInt() or cleaned.toInt(16)))
            },
            prefix = { Text("#") },
            label = { Text("Свой цвет (6 символов)") },
            singleLine = true,
            colors = fieldColors(t),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

package com.example.hotspotpult

import android.app.Application
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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

private val Bg = Color(0xFF0E1216)
private val Surface0 = Color(0xFF171D23)
private val Fg = Color(0xFFE8EEF2)
private val Muted = Color(0xFF8593A0)
private val Mint = Color(0xFF5BE0A8)
private val Amber = Color(0xFFF2B35C)

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
            MaterialTheme(colorScheme = darkColorScheme(background = Bg, surface = Surface0, primary = Mint)) {
                Screen()
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Screen(vm: VM = viewModel()) {
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
        Modifier.fillMaxSize().background(Bg).systemBarsPadding().padding(horizontal = 16.dp),
    ) {
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("ТОЧКА ДОСТУПА", color = Muted, fontSize = 12.sp, letterSpacing = 1.4.sp)
            TextButton(onClick = { vm.sync() }, enabled = state.serviceOn && !state.busy) {
                Text("Обновить список", color = if (state.serviceOn && !state.busy) Mint else Muted, fontSize = 13.sp)
            }
        }
        Text("Кто сейчас в сети", color = Fg, fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Text(
            "${state.devices.size - pausedCount} в сети · $pausedCount на паузе",
            color = Muted, fontSize = 14.sp,
        )
        Spacer(Modifier.height(10.dp))
        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Mint, trackColor = Surface0)
        Spacer(Modifier.height(10.dp))

        if (!state.serviceOn) {
            Column(
                Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("Нужен доступ «Специальные возможности»", color = Fg, fontWeight = FontWeight.Bold,
                    fontSize = 17.sp, textAlign = TextAlign.Center)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Приложение само нажимает те же кнопки, что и вы в настройках точки доступа. " +
                        "Откройте настройки, найдите «Хотспот Пульт» и включите его. " +
                        "Если переключатель серый, откройте карточку приложения, нажмите ⋮ и выберите «Разрешить ограниченные настройки».",
                    color = Muted, fontSize = 14.sp, textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = { ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
                    colors = ButtonDefaults.buttonColors(containerColor = Mint, contentColor = Bg),
                ) { Text("Открыть настройки", fontWeight = FontWeight.Bold) }
            }
        } else if (state.devices.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(
                    "Список пуст. Включите раздачу, подключите устройство и нажмите «Обновить список».",
                    color = Muted, textAlign = TextAlign.Center, fontSize = 15.sp,
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
                        val color = if (d.paused) Amber else Mint
                        Box(
                            Modifier
                                .fillMaxWidth().aspectRatio(1f)
                                .clip(CircleShape)
                                .background(if (d.paused) Color.Transparent else Surface0)
                                .border(2.dp, color, CircleShape)
                                .combinedClickable(
                                    onClick = { vm.toggle(d.mac) },
                                    onLongClick = { renaming = d },
                                )
                                .padding(16.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(d.name, color = if (d.paused) Muted else Fg, fontWeight = FontWeight.Bold,
                                    fontSize = 15.sp, textAlign = TextAlign.Center, maxLines = 2)
                                Text(if (d.paused) "ПАУЗА" else "В СЕТИ", color = color, fontSize = 11.sp, letterSpacing = 1.2.sp)
                            }
                        }
                        Text(d.mac, color = Muted, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                    }
                }
            }
        }

        Text(
            state.message.ifEmpty { "Тап: пауза или возврат. Долгое нажатие: переименовать." },
            color = Muted, fontSize = 12.sp, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        )
        Button(
            onClick = { vm.resumeAll() },
            enabled = pausedCount > 0 && state.serviceOn && !state.busy,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Mint, contentColor = Bg,
                disabledContainerColor = Surface0, disabledContentColor = Muted,
            ),
        ) { Text("Вернуть всех в сеть", fontWeight = FontWeight.Bold, fontSize = 16.sp) }
        Spacer(Modifier.height(12.dp))
    }

    renaming?.let { d ->
        var text by remember(d.mac) { mutableStateOf(d.name) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("Название устройства") },
            text = { OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true) },
            confirmButton = { TextButton(onClick = { vm.rename(d.mac, text); renaming = null }) { Text("Сохранить") } },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("Отмена") } },
        )
    }
}

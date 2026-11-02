package com.example.hotspotpult

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.SystemClock
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class Seen(val mac: String, val name: String)

/** Тексты экранов настроек TECNO (русский язык системы). Если прошивка другая, правятся здесь. */
object T {
    const val HOTSPOT = "Личная точка доступа"
    const val CONNECTIONS = "Управление подключениями"
    const val CONNECTED = "Подключенные устройства"
    const val BLACKLIST = "Черный список"
    const val ADD = "Добавить"
    const val REMOVE = "Удалить"
}

/**
 * Повторяет те же нажатия, что вы делаете руками:
 * Точка доступа > Управление подключениями > устройство > Добавить (пауза)
 * Точка доступа > Управление подключениями > Черный список > корзина > Удалить (возврат)
 */
class HotspotAutomationService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: HotspotAutomationService? = null
        val macRe = Regex("^[0-9a-f]{2}(:[0-9a-f]{2}){5}$")
        val timeRe = Regex("^\\d{1,2}:\\d{2}$")
    }

    private val lock = Mutex()

    override fun onServiceConnected() { instance = this }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}
    override fun onUnbind(intent: Intent?): Boolean { instance = null; return super.onUnbind(intent) }
    override fun onDestroy() { instance = null; super.onDestroy() }

    // ---------- публичные действия ----------

    /** Читает список подключённых устройств с экрана настроек. null, если экран не открылся. */
    suspend fun syncDevices(): List<Seen>? = lock.withLock {
        if (!openConnections()) return@withLock null
        val all = LinkedHashMap<String, Seen>()
        var i = 0
        while (i < 8) {
            collectDevices().forEach { all[it.mac] = it }
            if (!scroll(true)) break
            delay(450)
            i++
        }
        returnToApp()
        all.values.toList()
    }

    /** Пауза: добавляет устройства в системный чёрный список. */
    suspend fun block(macs: List<String>): Boolean = lock.withLock {
        if (!openConnections()) return@withLock false
        var ok = true
        for (mac in macs) {
            val row = locate { findText(mac) }
            if (row == null) { ok = false; continue }
            clickNode(row)
            val btn = waitFor(4000) { findText(T.ADD) }
            if (btn == null) { ok = false; continue }
            clickNode(btn)
            waitFor(3000) { if (findText(mac) == null) true else null }
            delay(300)
        }
        returnToApp()
        ok
    }

    /** Возврат: удаляет устройства из чёрного списка. */
    suspend fun unblock(macs: List<String>): Boolean = lock.withLock {
        if (macs.isEmpty()) return@withLock true
        if (!openConnections()) return@withLock false
        val bl = waitFor(3000) { findText(T.BLACKLIST) }
        if (bl == null) { returnToApp(); return@withLock false }
        clickNode(bl)
        delay(700)
        var ok = true
        for (mac in macs) {
            val row = waitFor(4000) { findText(mac) }
            if (row == null) { ok = false; continue }
            clickTrash(row)
            val btn = waitFor(4000) { findText(T.REMOVE) }
            if (btn == null) { ok = false; continue }
            clickNode(btn)
            waitFor(3000) { if (findText(mac) == null) true else null }
            delay(300)
        }
        returnToApp()
        ok
    }

    // ---------- навигация ----------

    private suspend fun openConnections(): Boolean {
        val i = Intent("android.settings.TETHER_SETTINGS")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        try {
            startActivity(i)
        } catch (e: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (e2: Exception) {
                return false
            }
        }
        delay(900)
        var row = waitFor(3000) { findText(T.CONNECTIONS) }
        if (row == null) {
            findText(T.HOTSPOT)?.let { clickNode(it) }
            row = waitFor(5000) { findText(T.CONNECTIONS) }
        }
        if (row == null) return false
        clickNode(row)
        return waitFor(6000) { findText(T.CONNECTED) } != null
    }

    private fun returnToApp() {
        try {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            )
        } catch (e: Exception) {
            performGlobalAction(GLOBAL_ACTION_HOME)
        }
    }

    // ---------- поиск и нажатия ----------

    private suspend fun <T> waitFor(timeoutMs: Long, block: () -> T?): T? {
        val end = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < end) {
            val r = block()
            if (r != null) return r
            delay(200)
        }
        return block()
    }

    private fun findText(text: String): AccessibilityNodeInfo? =
        rootInActiveWindow?.findAccessibilityNodeInfosByText(text)
            ?.firstOrNull { it.text?.toString()?.trim().equals(text, ignoreCase = true) }

    /** Ищет узел на экране; если его нет, прокручивает список вверх, затем вниз. */
    private suspend fun locate(finder: () -> AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        val first = waitFor(2000, finder)
        if (first != null) return first
        var g = 0
        while (g < 8 && scroll(false)) { delay(250); g++ }
        var s = 0
        while (s < 8) {
            val n = finder()
            if (n != null) return n
            if (!scroll(true)) break
            delay(450)
            s++
        }
        return finder()
    }

    private fun scroll(forward: Boolean): Boolean {
        val root = rootInActiveWindow ?: return false
        val sc = firstScrollable(root) ?: return false
        val act = if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        return sc.performAction(act)
    }

    private fun firstScrollable(n: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (n.isScrollable) return n
        for (i in 0 until n.childCount) {
            val c = n.getChild(i) ?: continue
            val r = firstScrollable(c)
            if (r != null) return r
        }
        return null
    }

    private fun clickNode(n: AccessibilityNodeInfo): Boolean {
        var cur: AccessibilityNodeInfo? = n
        while (cur != null) {
            if (cur.isClickable && cur.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            cur = cur.parent
        }
        val r = Rect()
        n.getBoundsInScreen(r)
        return tap(r.centerX().toFloat(), r.centerY().toFloat())
    }

    /** Корзина справа в строке чёрного списка. */
    private fun clickTrash(macNode: AccessibilityNodeInfo): Boolean {
        val dm = resources.displayMetrics
        var p = macNode.parent
        val r = Rect()
        while (p != null) {
            p.getBoundsInScreen(r)
            if (r.width() >= dm.widthPixels * 0.6) break
            p = p.parent
        }
        if (p != null) {
            val icon = findDescendant(p) {
                it.isClickable && (it.className?.toString()?.contains("Image") == true)
            }
            if (icon != null && icon.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
        }
        macNode.getBoundsInScreen(r)
        return tap(dm.widthPixels * 0.89f, r.centerY() - 10f * dm.density)
    }

    private fun findDescendant(n: AccessibilityNodeInfo, pred: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        for (i in 0 until n.childCount) {
            val c = n.getChild(i) ?: continue
            if (pred(c)) return c
            val r = findDescendant(c, pred)
            if (r != null) return r
        }
        return null
    }

    private fun tap(x: Float, y: Float): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 60))
            .build()
        return dispatchGesture(g, null, null)
    }

    // ---------- чтение списка ----------

    private fun collectDevices(): List<Seen> {
        val root = rootInActiveWindow ?: return emptyList()
        val out = mutableListOf<Seen>()
        walk(root) { n ->
            val t = n.text?.toString()?.trim()?.lowercase()
            if (t != null && macRe.matches(t)) {
                var name = ""
                val parent = n.parent
                if (parent != null) {
                    for (i in 0 until parent.childCount) {
                        val ct = parent.getChild(i)?.text?.toString()?.trim() ?: continue
                        if (!macRe.matches(ct.lowercase()) && !timeRe.matches(ct)) { name = ct; break }
                    }
                }
                out.add(Seen(t, name))
            }
        }
        return out
    }

    private fun walk(n: AccessibilityNodeInfo, f: (AccessibilityNodeInfo) -> Unit) {
        f(n)
        for (i in 0 until n.childCount) {
            val c = n.getChild(i) ?: continue
            walk(c, f)
        }
    }
}

package com.thorctl.app

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.PixelFormat
import android.graphics.drawable.ColorDrawable
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.util.Base64
import android.util.Log
import android.view.Display
import android.view.ViewGroup
import android.view.WindowManager
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.ComposeView
import com.thorctl.ui.RecentsActions
import com.thorctl.ui.RecentsStage
import com.thorctl.ui.ThorTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Hosts the Home button's screens (see [RecentsStage]): recent apps on Home hold, and the zoom
 * to the home screen on a Home press inside an app. The daemon sends the pictures (the screen as
 * it was when Home went down, the home screen, previews of other apps) and, while recents is open,
 * the controller's buttons. Everything is dropped when the overlay goes, so it costs no memory
 * while closed.
 */
@SuppressLint("StaticFieldLeak")
object RecentsController {
    private const val TAG = "ThorRecents"
    private const val HOME_KEY = -1000
    private const val PRESS_APP_KEY = -1001

    @Immutable
    data class Card(
        /** Task id, or a negative key for the home card / the app in a Home press. */
        val key: Int,
        val pkg: String,
        val label: String,
        val icon: ImageBitmap? = null,
        val image: ImageBitmap? = null,
        val onBottom: Boolean = false,
        /** Not running any more: opening it starts the app again (done here, see [launch]). */
        val running: Boolean = true,
        val isHome: Boolean = false,
    )

    enum class Intro { FROM_APP, FROM_HOME, HOME_PRESS }

    @Immutable
    data class Session(val id: Int, val intro: Intro, val start: Int, val originKey: Int)

    private val cards = MutableStateFlow<List<Card>>(emptyList())
    private val keys = MutableSharedFlow<String>(extraBufferCapacity = 32)

    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var dialog: Dialog? = null
    private var view: ComposeView? = null
    private var owner: OverlayOwner? = null
    private var session = 0
    private var displayId = -1

    // Home press: the switch underneath starts once our first frame is on screen.
    private var homeSeq = 0
    private var frameShown = false
    private var homeGoPending = false

    fun onEvent(o: JSONObject) {
        when (o.optString("ev")) {
            "recents" -> openRecents(o)
            "home_anim" -> openHomePress(o)
            "recents_thumb" -> updateThumb(o.optInt("id"), o.optString("thumb"))
            "recents_key" -> keys.tryEmit(o.optString("key"))
            "recents_close" -> removeNow()
        }
    }

    // ---- Opening ------------------------------------------------------------------------------

    private fun display(id: Int): Display? = OverlayController.appContext.getSystemService(DisplayManager::class.java).getDisplay(id)

    private fun openRecents(o: JSONObject) {
        removeNow()
        val s = ++session
        val display = display(o.optInt("display")) ?: run {
            DaemonClient.recentsClose()
            return
        }
        val app = OverlayController.appContext
        val bottomId = DaemonClient.state.value?.bottomDisplayId ?: -1
        val fromHome = o.optString("origin") == "home"
        val originTask = o.optInt("originTask", -1)
        val heroB64 = o.optString("hero")
        val homeB64 = o.optString("home")
        val tasks = o.optJSONArray("tasks")
        scope.launch {
            val built = withContext(Dispatchers.Default) {
                val hero = decode(heroB64)
                val apps = (0 until (tasks?.length() ?: 0)).map { i ->
                    val t = tasks!!.getJSONObject(i)
                    val id = t.optInt("id")
                    val pkg = t.optString("pkg")
                    Card(
                        key = id,
                        pkg = pkg,
                        label = label(app, pkg),
                        icon = icon(app, pkg),
                        image = if (id == originTask && hero != null) hero else decode(t.optString("thumb")),
                        onBottom = bottomId >= 0 && t.optInt("display", -1) == bottomId,
                        running = t.optBoolean("running", true),
                    )
                }
                apps.reversed() + Card(HOME_KEY, "", "Home", image = decode(homeB64), isHome = true)
            }
            if (s != session) return@launch
            cards.value = built
            val intro = if (fromHome) Intro.FROM_HOME else Intro.FROM_APP
            val start = if (fromHome) built.lastIndex else built.indexOfFirst { it.key == originTask }.takeIf { it >= 0 } ?: (built.lastIndex - 1).coerceAtLeast(0)
            if (!show(display, Session(s, intro, start, originTask))) DaemonClient.recentsClose()
        }
    }

    private fun openHomePress(o: JSONObject) {
        removeNow()
        val s = ++session
        homeSeq = o.optInt("seq")
        val display = display(o.optInt("display")) ?: return
        val appB64 = o.optString("app")
        val homeB64 = o.optString("home")
        scope.launch {
            val built = withContext(Dispatchers.Default) {
                listOf(
                    Card(PRESS_APP_KEY, "", "", image = decode(appB64)),
                    Card(HOME_KEY, "", "Home", image = decode(homeB64), isHome = true),
                )
            }
            if (s != session) return@launch
            if (built.any { it.image == null }) return@launch // the daemon sends Home itself shortly
            cards.value = built
            show(display, Session(s, Intro.HOME_PRESS, 0, PRESS_APP_KEY))
        }
    }

    private fun updateThumb(id: Int, b64: String) {
        val s = session
        scope.launch {
            val bmp = withContext(Dispatchers.Default) { decode(b64) } ?: return@launch
            if (s != session) return@launch
            cards.value = cards.value.map { if (it.key == id) it.copy(image = bmp) else it }
        }
    }

    /** Screens are opaque: RGB_565 halves the memory. Full resolution, they fill the screen. */
    private fun decode(b64: String): ImageBitmap? {
        if (b64.isEmpty()) return null
        return try {
            val bytes = Base64.decode(b64, Base64.DEFAULT)
            val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)?.asImageBitmap()
        } catch (e: Exception) {
            Log.w(TAG, "decode failed", e)
            null
        }
    }

    private fun label(ctx: Context, pkg: String): String = try {
        val pm = ctx.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    } catch (_: Exception) {
        pkg
    }

    private fun icon(ctx: Context, pkg: String): ImageBitmap? = try {
        val d = ctx.packageManager.getApplicationIcon(pkg)
        val px = (40 * ctx.resources.displayMetrics.density).toInt()
        val b = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        d.setBounds(0, 0, px, px)
        d.draw(Canvas(b))
        b.asImageBitmap()
    } catch (_: Exception) {
        null
    }

    // ---- Window -------------------------------------------------------------------------------

    private fun show(display: Display, s: Session): Boolean {
        val (ctx, type) = OverlayController.overlayContext(display) ?: run {
            Log.w(TAG, "no way to show an overlay")
            return false
        }
        displayId = display.displayId
        val d = Dialog(ctx, com.thorctl.R.style.Theme_ThorControl_Menu)
        val w = d.window ?: return false
        w.setType(type)
        w.setBackgroundDrawable(ColorDrawable(android.graphics.Color.TRANSPARENT))
        w.setFormat(PixelFormat.TRANSLUCENT)
        w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        w.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        // Never focusable: the app underneath keeps focus (and keeps running); buttons come from the daemon.
        w.addFlags(
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
        )
        w.attributes = w.attributes.apply {
            title = "ThorControlRecents"
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            fitInsetsTypes = 0
            windowAnimations = 0
        }
        frameShown = false
        homeGoPending = false

        val o = OverlayOwner()
        val v = ComposeView(ctx)
        o.attach(v)
        v.setContent {
            ThorTheme {
                RecentsStage(
                    session = s,
                    cards = cards,
                    keys = keys,
                    actions = Actions(s.id),
                )
            }
        }
        // The first frame is on screen: only now may the app underneath change.
        v.viewTreeObserver.registerFrameCommitCallback {
            main.postDelayed({
                frameShown = true
                if (homeGoPending) sendHomeGo()
            }, 24)
        }
        d.setContentView(v, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        d.setCancelable(false)
        return try {
            d.show()
            o.resume()
            dialog = d
            view = v
            owner = o
            true
        } catch (e: Exception) {
            Log.w(TAG, "show failed", e)
            o.destroy()
            false
        }
    }

    private fun sendHomeGo() {
        homeGoPending = false
        DaemonClient.homeGo(homeSeq)
    }

    /** After the zoom: let the switch underneath settle, fade out, and drop everything. */
    private fun finish(sessionId: Int, settleMs: Long) {
        if (sessionId != session) return
        if (settleMs <= 0) {
            removeNow()
            return
        }
        main.postDelayed({
            if (sessionId != session) return@postDelayed
            val v = view ?: return@postDelayed
            v.animate().alpha(0f).setDuration(140).withEndAction { if (sessionId == session) removeNow() }.start()
        }, settleMs)
    }

    private fun removeNow() {
        val d = dialog ?: return
        dialog = null
        view = null
        try {
            d.dismiss()
        } catch (e: Exception) {
            Log.w(TAG, "dismiss", e)
        }
        owner?.destroy()
        owner = null
        cards.value = emptyList()
        session++
        DaemonClient.overlayDone()
    }

    /** Starts an app whose task is no longer running, like a launcher would, on the same screen. */
    private fun launch(c: Card) {
        val app = OverlayController.appContext
        val intent = app.packageManager.getLaunchIntentForPackage(c.pkg) ?: run {
            Log.w(TAG, "no launch intent for ${c.pkg}")
            return
        }
        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        val opts = android.app.ActivityOptions.makeBasic()
        if (displayId >= 0) opts.launchDisplayId = displayId
        try {
            app.startActivity(intent, opts.toBundle())
        } catch (e: Exception) {
            Log.w(TAG, "launch ${c.pkg} failed", e)
        }
    }

    private class Actions(private val sessionId: Int) : RecentsActions {
        private fun live() = sessionId == session

        override fun openApp(card: Card) {
            if (!live()) return
            if (card.running) {
                DaemonClient.recentsOpen(card.key)
            } else {
                launch(card)
                DaemonClient.recentsClose()
            }
        }

        override fun openHome(alreadyThere: Boolean) {
            if (!live()) return
            if (alreadyThere) DaemonClient.recentsClose() else DaemonClient.recentsHome()
        }

        override fun back() {
            if (live()) DaemonClient.recentsClose()
        }

        override fun remove(card: Card) {
            if (live()) DaemonClient.recentsRemove(card.key)
        }

        override fun clearAll(ids: List<Int>) {
            if (live()) DaemonClient.recentsClear(ids)
        }

        override fun homeGo() {
            if (!live()) return
            if (frameShown) sendHomeGo() else homeGoPending = true
        }

        override fun finish(settleMs: Long) = finish(sessionId, settleMs)

        override fun tick() = OverlayController.haptic(VibrationEffect.EFFECT_TICK)
    }
}

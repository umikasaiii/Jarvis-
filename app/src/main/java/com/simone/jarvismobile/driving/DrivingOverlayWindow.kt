package com.simone.jarvismobile.driving

import android.content.Context
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner

/**
 * One small `TYPE_APPLICATION_OVERLAY` window hosting a Compose panel.
 *
 * Modalità Guida is built as **several** small, `WRAP_CONTENT`-sized windows
 * (top cluster, nav-compact, media dock) rather than one full-screen window
 * with a "transparent" middle — a single window still intercepts every touch
 * inside its bounds no matter how see-through it looks, which would break the
 * spec's actual requirement that Google Maps stays *usable*, not just visible,
 * under the gap. Sizing each window to just its own panel means the gap
 * between them is real screen with no window over it at all, so taps, drags
 * and pinch-zoom on Maps there go straight through.
 */
internal class DrivingOverlayWindow(
    private val context: Context,
    private val gravity: Int,
    private val keepScreenOn: Boolean = false,
    /**
     * Lets this one window draw its background under the status bar instead of
     * being kept clear of it — the top cluster wants its dark panel to visually
     * reach the true screen edge (system bar icons still render above it either
     * way), while the compact-nav and media-dock windows stay clear as before.
     * The content inside must still apply its own inset padding so text/icons
     * do not collide with the real status bar icons.
     */
    private val extendUnderStatusBar: Boolean = false,
) {
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val owner = DrivingOverlayLifecycleOwner()
    private var view: ComposeView? = null

    fun show(content: @Composable () -> Unit) {
        if (view != null) return
        owner.create()
        owner.start()
        owner.resume()
        val composeView = ComposeView(context).apply {
            setViewTreeLifecycleOwner(owner)
            setViewTreeViewModelStoreOwner(owner)
            setViewTreeSavedStateRegistryOwner(owner)
            setContent(content)
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            baseFlags(),
            PixelFormat.TRANSLUCENT,
        ).apply { this.gravity = this@DrivingOverlayWindow.gravity }
        runCatching { windowManager.addView(composeView, params) }
            .onSuccess { view = composeView }
    }

    private fun baseFlags(): Int {
        // Without FLAG_LAYOUT_IN_SCREEN the system keeps a window clear of the
        // status/gesture bars on its own, correctly for whatever the device's
        // own bar heights are — far more reliable than guessing a fixed padding.
        // That is right for the nav-compact and media-dock windows (that was the
        // earlier fix for the top bar overlapping Maps' own top UI); the top
        // cluster now opts back into it on request, so its background can reach
        // the true top edge — the content still insets itself, see DrivingTopPanel.
        var flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        if (extendUnderStatusBar) flags = flags or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        if (keepScreenOn) flags = flags or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        return flags
    }

    fun hide() {
        val current = view ?: return
        runCatching { windowManager.removeViewImmediate(current) }
        owner.destroy()
        view = null
    }

    companion object {
        const val GRAVITY_TOP = Gravity.TOP or Gravity.START
        const val GRAVITY_BOTTOM = Gravity.BOTTOM or Gravity.START
    }
}

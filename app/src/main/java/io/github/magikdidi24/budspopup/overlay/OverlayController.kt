package io.github.magikdidi24.budspopup.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import io.github.magikdidi24.budspopup.protocol.BudsColor
import io.github.magikdidi24.budspopup.protocol.BudsStatus
import kotlinx.coroutines.flow.StateFlow

/** Puts the [PopupCard] in a system overlay window at the bottom of the screen. */
class OverlayController(private val context: Context) {
    private val windowManager = context.getSystemService(WindowManager::class.java)
    private val visible = MutableTransitionState(false)
    private var view: ComposeView? = null
    private var owner: OverlayOwner? = null
    private var onHidden: (() -> Unit)? = null

    fun show(title: String, status: StateFlow<BudsStatus?>, color: BudsColor, onDismiss: () -> Unit) {
        val owner = OverlayOwner().also { owner = it }
        visible.targetState = true
        val view = ComposeView(context).apply {
            // Compose needs both owners on the view tree, which a service window does not provide.
            setViewTreeLifecycleOwner(owner)
            setViewTreeSavedStateRegistryOwner(owner)
            setContent {
                val s by status.collectAsState()
                PopupCard(title, s, color, visible, onDismiss)
                LaunchedEffect(Unit) {
                    snapshotFlow { !visible.targetState && !visible.currentState && visible.isIdle }.collect { done ->
                        if (done) post { remove() } // not from inside the composition being torn down
                    }
                }
            }
            setOnTouchListener { _, event ->
                if (event.action == MotionEvent.ACTION_OUTSIDE) onDismiss()
                false
            }
        }
        windowManager.addView(view, layoutParams())
        this.view = view
    }

    /** Slides the card out, then removes the window and calls [then]. */
    fun hide(then: () -> Unit) {
        onHidden = then
        if (view == null) remove() else visible.targetState = false
    }

    fun remove() {
        view?.let { windowManager.removeView(it) }
        view = null
        owner?.destroy()
        owner = null
        onHidden?.invoke()
        onHidden = null
    }

    private fun layoutParams() = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
            WindowManager.LayoutParams.FLAG_DIM_BEHIND,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.BOTTOM
        dimAmount = 0.25f
        fitInsetsTypes = 0 // reach the screen edge so the card slides in from the very bottom
        title = "BudsPopup"
    }
}

private class OverlayOwner : SavedStateRegistryOwner {
    private val registry = LifecycleRegistry(this)
    private val savedState = SavedStateRegistryController.create(this)
    override val lifecycle: Lifecycle get() = registry
    override val savedStateRegistry get() = savedState.savedStateRegistry

    init {
        savedState.performRestore(null)
        registry.currentState = Lifecycle.State.RESUMED
    }

    fun destroy() {
        registry.currentState = Lifecycle.State.DESTROYED
    }
}

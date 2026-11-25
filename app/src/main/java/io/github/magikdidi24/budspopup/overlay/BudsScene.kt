package io.github.magikdidi24.budspopup.overlay

import android.content.res.AssetManager
import android.view.Choreographer
import android.view.TextureView
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.google.android.filament.ColorGrading
import com.google.android.filament.ToneMapper
import com.google.android.filament.View
import com.google.android.filament.android.UiHelper
import com.google.android.filament.utils.KTX1Loader
import com.google.android.filament.utils.ModelViewer
import com.google.android.filament.utils.Utils
import io.github.magikdidi24.budspopup.protocol.BudsColor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.min

// Keep in sync with tools/build_model.py (frames at 30 fps, Blender coords turned Y-up: (x, z, -y)).
private const val OPEN_END = 66f / 30
private const val IDLE_START = 70f / 30
private const val IDLE_LENGTH = 180f / 30
private val EYE = doubleArrayOf(0.0, 7.5, 21.0)
private val TARGET = doubleArrayOf(0.0, 1.3, 0.0)
private const val FOCAL_LENGTH = 50f
const val SCENE_ASPECT = 640f / 400f

/** Open sequence once, then the idle loop forever. */
fun animationTime(seconds: Float) =
    if (seconds < OPEN_END) seconds else IDLE_START + (seconds - OPEN_END) % IDLE_LENGTH

/** Base colours of the black buds; the asset itself is the white model. */
private val BLACK = mapOf(
    "Shell" to floatArrayOf(0.02f, 0.02f, 0.022f, 1f),
    "Trim" to floatArrayOf(0.035f, 0.035f, 0.035f, 1f),
    "Blade" to floatArrayOf(0.09f, 0.09f, 0.1f, 1f),
    "Lid" to floatArrayOf(0.03f, 0.03f, 0.03f, 0.5f),
)

@Composable
fun BudsScene(color: BudsColor, modifier: Modifier = Modifier) {
    AndroidView(factory = { context -> TextureView(context).also { BudsRenderer(it, color) } }, modifier = modifier)
}

/** Filament renderer on a transparent TextureView, so the card colour shows behind the model. */
private class BudsRenderer(textureView: TextureView, color: BudsColor) : Choreographer.FrameCallback {
    private val choreographer = Choreographer.getInstance()
    private val viewer = ModelViewer(
        textureView,
        uiHelper = UiHelper(UiHelper.ContextErrorPolicy.DONT_CHECK).apply { isOpaque = false },
        manipulator = null,
    )
    private var lastNanos = 0L
    private var seconds = 0f

    init {
        val assets = textureView.context.assets
        viewer.autoPlayAnimations = false
        viewer.view.blendMode = View.BlendMode.TRANSLUCENT
        // The default tone mapper turns the white plastic cream; PBR Neutral keeps whites white.
        viewer.view.colorGrading = ColorGrading.Builder().toneMapper(ToneMapper.PBRNeutral()).build(viewer.engine)
        viewer.renderer.clearOptions = viewer.renderer.clearOptions.apply { clear = true }
        viewer.loadModelGlb(assets.buffer("buds.glb"))
        val ibl = KTX1Loader.createIndirectLight(viewer.engine, assets.buffer("studio_ibl.ktx"))
        viewer.scene.indirectLight = ibl.indirectLight?.apply { intensity = 30_000f }
        viewer.indirectLightCubemap = ibl.cubemap // destroyed with the viewer
        val lights = viewer.engine.lightManager
        lights.setDirection(lights.getInstance(viewer.light), 0.3f, -1f, -0.6f)
        viewer.cameraFocalLength = FOCAL_LENGTH
        viewer.camera.lookAt(EYE[0], EYE[1], EYE[2], TARGET[0], TARGET[1], TARGET[2], 0.0, 1.0, 0.0)
        if (color == BudsColor.Black) {
            viewer.asset?.instance?.materialInstances?.forEach { mi ->
                BLACK[mi.name]?.let { (r, g, b, a) -> mi.setParameter("baseColorFactor", r, g, b, a) }
            }
        }
        // ModelViewer destroys its engine when the view detaches; stop drawing at the same time.
        textureView.addOnAttachStateChangeListener(object : android.view.View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: android.view.View) = Unit
            override fun onViewDetachedFromWindow(v: android.view.View) = choreographer.removeFrameCallback(this@BudsRenderer)
        })
        choreographer.postFrameCallback(this)
    }

    override fun doFrame(frameTimeNanos: Long) {
        choreographer.postFrameCallback(this)
        val animator = viewer.animator
        if (animator != null && viewer.progress >= 1f) { // start the clock once the model is loaded
            // At most one 30 fps step per frame: the first frames compile shaders and can take
            // seconds, and the open sequence must not be skipped meanwhile.
            if (lastNanos != 0L) seconds += min((frameTimeNanos - lastNanos) / 1e9f, 1f / 30)
            lastNanos = frameTimeNanos
            val t = animationTime(seconds)
            // Blender exports one animation per object (case, lid, each bud), all on the same timeline.
            for (i in 0 until animator.animationCount) animator.applyAnimation(i, t)
            animator.updateBoneMatrices()
        }
        viewer.render(frameTimeNanos)
    }

    companion object {
        init {
            Utils.init() // loads the filament, gltfio and utils native libraries
        }
    }
}

private fun AssetManager.buffer(name: String): ByteBuffer {
    val bytes = open(name).use { it.readBytes() }
    return ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).put(bytes).apply { flip() }
}

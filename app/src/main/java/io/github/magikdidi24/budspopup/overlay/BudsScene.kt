package io.github.magikdidi24.budspopup.overlay

import android.content.res.AssetManager
import android.util.Log
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
import io.github.magikdidi24.budspopup.TAG
import io.github.magikdidi24.budspopup.protocol.BudsColor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

// Keep in sync with tools/build_model.py (frames at 30 fps, Blender coords turned Y-up: (x, z, -y)).
private const val OPEN_END = 30f / 30 // the baked part: case pop-in and lid opening
private val EYE = doubleArrayOf(0.0, 7.5, 21.0)
private val TARGET = doubleArrayOf(0.0, 1.3, 0.0)
private const val FOCAL_LENGTH = 50f
const val SCENE_ASPECT = 640f / 400f
private const val LID_OPEN = 0.9f  // s: frame 26, buds wait for the lid before moving
// iPhone layout: with a bud out, the case slides right and the buds stand as a pair on the left.
private const val CASE_X = 3.4f
private const val PAIR_X = -3.5f
private const val PAIR_GAP = 0.95f // each bud's distance from the pair's centre
private const val BUD_Y = -0.2f
private const val LIFT = 3.4f      // how high a bud rises out of its slot before moving aside
private const val TURN = 20f       // degrees each bud of the pair turns outwards
private const val TURN_PERIOD = 7f // s per turn of the turntable, as on the iPhone
private const val TURN_RAMP = 1f   // s to reach that speed once the lid is open
private const val POP_IN = 14f / 30 // s, the headphones' pop-in, as long as the case's

/** Base colours of the black buds; the asset itself is the white model. */
private val BLACK = mapOf(
    "Shell" to floatArrayOf(0.02f, 0.02f, 0.022f, 1f),
    "Trim" to floatArrayOf(0.035f, 0.035f, 0.035f, 1f),
    "Blade" to floatArrayOf(0.09f, 0.09f, 0.1f, 1f),
    "Lid" to floatArrayOf(0.03f, 0.03f, 0.03f, 0.5f),
)

@Composable
fun BudsScene(color: BudsColor, leftOut: Boolean, rightOut: Boolean, modifier: Modifier = Modifier) {
    AndroidView(
        factory = { context -> TextureView(context).apply { tag = BudsRenderer(this, color) } },
        update = { (it.tag as BudsRenderer).setOut(leftOut, rightOut) },
        modifier = modifier,
    )
}

@Composable
fun HeadphonesScene(modifier: Modifier = Modifier) {
    AndroidView(
        factory = { context -> TextureView(context).apply { tag = BudsRenderer(this, BudsColor.Black, headphones = true) } },
        modifier = modifier,
    )
}

private class Bud(val entity: Int, val side: Float, val rest: FloatArray, val motion: BudMotion)

/** Filament renderer on a transparent TextureView, so the card colour shows behind the model. */
private class BudsRenderer(
    textureView: TextureView,
    color: BudsColor,
    private val headphones: Boolean = false,
) : Choreographer.FrameCallback {
    private val choreographer = Choreographer.getInstance()
    private val viewer = ModelViewer(
        textureView,
        uiHelper = UiHelper(UiHelper.ContextErrorPolicy.DONT_CHECK).apply { isOpaque = false },
        manipulator = null,
    )
    private var lastNanos = 0L
    private var seconds = 0f
    private var leftOut = false
    private var rightOut = false
    private var buds: List<Bud>? = null
    private val matrix = FloatArray(16)

    fun setOut(left: Boolean, right: Boolean) {
        leftOut = left
        rightOut = right
        buds?.let { (l, r) -> l.motion.out = left; r.motion.out = right }
    }

    init {
        val assets = textureView.context.assets
        viewer.autoPlayAnimations = false
        viewer.view.blendMode = View.BlendMode.TRANSLUCENT
        // The default tone mapper turns the white plastic cream; PBR Neutral keeps whites white.
        viewer.view.colorGrading = ColorGrading.Builder().toneMapper(ToneMapper.PBRNeutral()).build(viewer.engine)
        viewer.renderer.clearOptions = viewer.renderer.clearOptions.apply { clear = true }
        viewer.loadModelGlb(assets.buffer(if (headphones) "headphones.glb" else "buds.glb"))
        val ibl = KTX1Loader.createIndirectLight(viewer.engine, assets.buffer("studio_ibl.ktx"))
        viewer.scene.indirectLight = ibl.indirectLight?.apply { intensity = 30_000f }
        viewer.indirectLightCubemap = ibl.cubemap // destroyed with the viewer
        val lights = viewer.engine.lightManager
        lights.setDirection(lights.getInstance(viewer.light), 0.3f, -1f, -0.6f)
        viewer.cameraFocalLength = FOCAL_LENGTH
        viewer.camera.lookAt(EYE[0], EYE[1], EYE[2], TARGET[0], TARGET[1], TARGET[2], 0.0, 1.0, 0.0)
        if (!headphones && color == BudsColor.Black) { // the headphones asset is black already
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
            if (lastNanos == 0L) Log.i(TAG, "3D model ready")
            val dt = if (lastNanos == 0L) 0f else min((frameTimeNanos - lastNanos) / 1e9f, 1f / 30)
            lastNanos = frameTimeNanos
            seconds += dt
            // Blender exports one animation per object (case, lid), all on the same timeline.
            for (i in 0 until animator.animationCount) animator.applyAnimation(i, min(seconds, OPEN_END))
            animator.updateBoneMatrices()
            if (headphones) {
                placeHeadphones()
            } else {
                val buds = buds ?: findBuds().also { buds = it }
                if (seconds > LID_OPEN) buds.forEach { it.motion.step(dt) }
                placeScene(buds)
            }
        }
        viewer.render(frameTimeNanos)
    }

    /** Buds sitting out at the start (already in an ear) are shown beside the case straight away. */
    private fun findBuds(): List<Bud> {
        val asset = viewer.asset!!
        val tm = viewer.engine.transformManager
        return listOf(Triple("BudL", -1f, leftOut), Triple("BudR", 1f, rightOut)).map { (name, side, out) ->
            val entity = asset.getFirstEntityByName(name)
            tm.getTransform(tm.getInstance(entity), matrix)
            Bud(entity, side, floatArrayOf(matrix[12], matrix[13], matrix[14]), BudMotion(out))
        }
    }

    /**
     * The case and the pair of buds turn together like on a turntable. The case slides right as soon
     * as a bud leaves it, and each bud goes between its slot and its place in the pair on the left.
     */
    private fun placeScene(buds: List<Bud>) {
        val tm = viewer.engine.transformManager
        val case = tm.getInstance(viewer.asset!!.getFirstEntityByName("CaseRoot"))
        tm.getTransform(case, matrix)
        val scale = hypot(matrix[0], matrix[2]) // the baked pop-in
        val yaw = turn(seconds, LID_OPEN)
        val (c, s) = cos(yaw) to sin(yaw)
        val caseX = CASE_X * buds.maxOf { it.motion.away }
        pose(caseX, 0f, 0f, yaw, 0f, scale)
        tm.setTransform(case, matrix)
        for (bud in buds) {
            val m = bud.motion
            val (rx, ry, rz) = bud.rest
            val away = m.away
            pose(
                x = lerp(caseX + scale * (c * rx + s * rz), PAIR_X + c * bud.side * PAIR_GAP, away),
                y = lerp(scale * ry + LIFT * m.rise, BUD_Y, away),
                z = lerp(scale * (c * rz - s * rx), -s * bud.side * PAIR_GAP, away),
                yaw = yaw - Math.toRadians((bud.side * TURN).toDouble()).toFloat() * away,
                pitch = PI.toFloat() / 2 * away, // lying in the slot, standing once out
                scale = lerp(scale, 1f, away),
            )
            tm.setTransform(tm.getInstance(bud.entity), matrix)
        }
    }

    /** The headphones pop in, then turn like the buds scene, from the start. */
    private fun placeHeadphones() {
        val tm = viewer.engine.transformManager
        val back = (min(seconds / POP_IN, 1f) - 1).let { 1 + 2.4f * it * it * it + 1.4f * it * it } // ease out, slight overshoot
        pose(0f, 0f, 0f, turn(seconds, POP_IN), 0f, lerp(0.82f, 1f, back))
        tm.setTransform(tm.getInstance(viewer.asset!!.getFirstEntityByName("Headphones")), matrix)
    }

    /** Turntable angle: still until [start], then up to one turn every [TURN_PERIOD]. */
    private fun turn(t: Float, start: Float): Float {
        val speed = 2 * PI.toFloat() / TURN_PERIOD
        val d = t - start
        return when {
            d <= 0f -> 0f
            d < TURN_RAMP -> speed * d * d / (2 * TURN_RAMP)
            else -> speed * (d - TURN_RAMP / 2)
        }
    }

    /** matrix = translate · rotateY(yaw) · rotateX(pitch) · scale, column-major as Filament wants. */
    private fun pose(x: Float, y: Float, z: Float, yaw: Float, pitch: Float, scale: Float) {
        val (ca, sa, cb, sb) = floatArrayOf(cos(yaw), sin(yaw), cos(pitch), sin(pitch))
        floatArrayOf(
            ca * scale, 0f, -sa * scale, 0f,
            sa * sb * scale, cb * scale, ca * sb * scale, 0f,
            sa * cb * scale, -sb * scale, ca * cb * scale, 0f,
            x, y, z, 1f,
        ).copyInto(matrix)
    }

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

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

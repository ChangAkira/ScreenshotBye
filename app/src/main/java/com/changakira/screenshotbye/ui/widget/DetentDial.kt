package com.changakira.screenshotbye.ui.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityNodeInfo
import androidx.annotation.ColorInt
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.customview.widget.ExploreByTouchHelper
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat.CollectionItemInfoCompat
import com.changakira.screenshotbye.R
import com.google.android.material.R as MaterialR
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 固定档位吸附旋钮。Views 版，照搬参考仓库 Compose 的 DetentDial：240° 弧、120° 缺口朝下、
 * 读数放在圆心、档位数字挂在弧外侧，切档时「咔」一下短震动。
 *
 * 为什么不是 Slider：Slider 是连续值，十几个格子挤在一条轨道上，每格约 17dp，指尖一次压住两格，
 * 用户得瞄着划。这里只有 presets 里那几个值可选，怎么滑都吸附到最近的一格；
 * 每个档位还是独立的无障碍节点，读屏能逐档念、逐档选。
 */
class DetentDial @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private companion object {
        /** 弧的起点：150° 在屏幕坐标系里是左下方 */
        const val ANGLE_START = 150f

        /** 弧的张角，留 120° 缺口朝下，读数正好放在圆心 */
        const val ANGLE_SWEEP = 240f

        /** 轨道半径占旋钮方框的比例，和 onDraw 里保持一致 */
        const val TRACK_RADIUS_RATIO = 0.363f

        /**
         * 可拨动的环带半宽（占方框比例）。只有落点距圆心在 [轨道半径-带宽, 轨道半径+带宽+档位数字外扩]
         * 这一段环带里，才算「点在圆形轨道附近」，由旋钮消费；环带以内（圆心读数区）和以外（方框四角空白）
         * 都交还给父容器滚动。这样卡片里除了轨道那圈，其余空白都能上下滑动。
         */
        const val TRACK_BAND = 0.17f

        /**
         * 弧两端往外多留一点余量。弧只张 240°，下方 120° 是空的缺口——那一段连轨道都没有，
         * 按下时必须让给页面滚动，不能被旋钮吃掉。
         */
        const val ARC_SLACK = 12f

        /** 没在 XML 里指定 ∞ 档位时的哨兵值；任何档位都不会等于它 */
        const val NO_INFINITY = Int.MIN_VALUE
    }

    private var presets = intArrayOf(1, 2, 3)
    private var index = 0
    private var label: String? = null
    private var unit = ""

    /** 等于这个值的档位画成 ∞ 而不是数字（「默认保留」存 0 表示无限天）；没指定时是 NO_INFINITY */
    private var infinityValue = NO_INFINITY
    private var onValueChange: ((Int) -> Unit)? = null
    private var lastTickAt = 0L

    private val density = resources.displayMetrics.density
    private val maxSizePx = (190f * density).roundToInt()
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var dragging = false

    // 弧的两端是圆头，和参考实现里的 StrokeCap.Round 一致；方头会让 240° 的弧看着像被切断
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val progress = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
    private val thumb = Paint(Paint.ANTI_ALIAS_FLAG)
    private val detentText = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val centerText = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val unitText = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val labelText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = 14f * density
    }

    @ColorInt private var primary = 0
    @ColorInt private var onSurface = 0
    @ColorInt private var faintText = 0

    private val a11y = object : ExploreByTouchHelper(this) {
        override fun getVirtualViewAt(x: Float, y: Float): Int {
            val box = box()
            presets.indices.forEach { if (hitRect(it, box).contains(x.toInt(), y.toInt())) return it }
            return INVALID_ID
        }

        override fun getVisibleVirtualViews(ids: MutableList<Int>) {
            presets.indices.forEach { ids.add(it) }
        }

        override fun onPopulateNodeForVirtualView(id: Int, node: AccessibilityNodeInfoCompat) {
            val box = box()
            node.setBoundsInParent(hitRect(id, box))
            node.contentDescription = context.getString(R.string.dial_value_desc, textFor(presets[id]), unit)
            node.isCheckable = true
            node.isChecked = id == index
            node.setCollectionItemInfo(CollectionItemInfoCompat.obtain(id, 1, 0, presets.size, false, false))
            node.addAction(AccessibilityNodeInfoCompat.ACTION_CLICK)
        }

        override fun onPerformActionForVirtualView(id: Int, action: Int, args: android.os.Bundle?): Boolean {
            if (action != AccessibilityNodeInfoCompat.ACTION_CLICK) return false
            select(id)
            invalidateVirtualView(id)
            return true
        }
    }

    init {
        resolveColors()
        if (attrs != null) {
            val a = context.obtainStyledAttributes(attrs, R.styleable.DetentDial)
            label = a.getString(R.styleable.DetentDial_dialLabel)
            unit = a.getString(R.styleable.DetentDial_dialUnit) ?: ""
            infinityValue = a.getInt(R.styleable.DetentDial_dialInfinityValue, NO_INFINITY)
            parsePresets(a.getString(R.styleable.DetentDial_dialPresets))?.let { presets = it }
            a.recycle()
        }
        if (presets.size < 2) presets = intArrayOf(1, 2, 3)
        isClickable = true
        ViewCompat.setAccessibilityDelegate(this, a11y)
        contentDescription = describe()
    }

    // ---- 对外接口 ----

    fun setPresets(values: List<Int>) {
        require(values.size >= 2) { context.getString(R.string.dial_error_two_presets) }
        presets = values.toIntArray()
        index = nearestIndex(value)
        contentDescription = describe()
        invalidate()
    }

    fun setLabel(text: String?) {
        label = text
        contentDescription = describe()
        invalidate()
    }

    fun setUnit(text: String) {
        unit = text
        contentDescription = describe()
        invalidate()
    }

    fun setOnValueChangeListener(listener: ((Int) -> Unit)?) {
        onValueChange = listener
    }

    /** 外部改了值（例如从别的页面回来、换了主题重绘）时跟着归位。 */
    var value: Int
        get() = presets[index]
        set(v) {
            index = nearestIndex(v)
            contentDescription = describe()
            invalidate()
        }

    // ---- 颜色 ----

    private fun resolveColors() {
        primary = themeColor(MaterialR.attr.colorPrimary)
        onSurface = themeColor(MaterialR.attr.colorOnSurface)
        // 参考仓库写的是 primary.copy(alpha = 0.55f)，画布上直接给画笔设 alpha 就行
        faintText = onSurface
    }

    @ColorInt
    private fun themeColor(attr: Int): Int {
        val out = android.util.TypedValue()
        context.theme.resolveAttribute(attr, out, true)
        return if (out.resourceId != 0) context.getColor(out.resourceId) else out.data
    }

    // ---- 测量与绘制 ----

    /**
     * 旋钮本体所在的方框。档位数字挂在弧外侧，最上那一档会顶到方框外，
     * 所以本体只占可用方框的 92%，上方另留一行标签文字。
     */
    private fun box(): IntArray {
        val labelSpace = (labelText.textSize + 6f * density).roundToInt()
        val availH = (height - labelSpace).coerceAtLeast(0)
        val square = min(min(maxSizePx, width), availH)
        val dial = (square * 0.92f).roundToInt()
        return intArrayOf((width - dial) / 2, labelSpace + (availH - dial) / 2, dial)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val desiredH = min(maxSizePx, w) + (24f * density).roundToInt()
        val h = when (MeasureSpec.getMode(heightMeasureSpec)) {
            MeasureSpec.EXACTLY -> MeasureSpec.getSize(heightMeasureSpec)
            MeasureSpec.AT_MOST -> min(desiredH, MeasureSpec.getSize(heightMeasureSpec))
            else -> desiredH
        }
        setMeasuredDimension(w, h)
    }

    override fun onDraw(canvas: Canvas) {
        val box = box()
        val dial = box[2]
        if (dial <= 0) return
        val cx = box[0] + dial / 2f
        val cy = box[1] + dial / 2f
        val r = dial * TRACK_RADIUS_RATIO
        val stroke = dial * 0.047f

        label?.let {
            labelText.color = onSurface
            canvas.drawText(it, cx, box[1] - 6f * density, labelText)
        }

        track.color = primary
        track.alpha = 38 // 0.15
        track.strokeWidth = stroke
        canvas.drawArc(cx - r, cy - r, cx + r, cy + r, ANGLE_START, ANGLE_SWEEP, false, track)

        val current = angleFor(index)
        val sweep = current - ANGLE_START
        if (sweep > 0f) {
            progress.color = primary
            progress.strokeWidth = stroke
            canvas.drawArc(cx - r, cy - r, cx + r, cy + r, ANGLE_START, sweep, false, progress)
        }

        dot.color = primary
        dot.alpha = 89 // 0.35
        presets.indices.forEach {
            // 当前档不画小刻度，由指针大圆点代替
            if (it != index) {
                val p = polar(cx, cy, r, angleFor(it))
                canvas.drawCircle(p[0], p[1], dial * 0.013f, dot)
            }
        }
        thumb.color = primary
        val t = polar(cx, cy, r, current)
        canvas.drawCircle(t[0], t[1], dial * 0.058f, thumb)

        val detentSize = (dial * 0.068f).coerceIn(10.5f * density, 13.5f * density)
        detentText.textSize = detentSize
        presets.indices.forEach {
            val anchor = polar(cx, cy, r + dial * 0.09f, angleFor(it))
            detentText.color = if (it == index) primary else faintText
            detentText.alpha = if (it == index) 255 else 140 // 0.55
            detentText.isFakeBoldText = it == index
            canvas.drawText(textFor(presets[it]), anchor[0], anchor[1] + detentSize / 3f, detentText)
        }

        val big = dial * 0.147f
        centerText.color = primary
        centerText.textSize = big
        canvas.drawText(textFor(presets[index]), cx, cy + big / 3f, centerText)

        val small = dial * 0.068f
        unitText.color = faintText
        unitText.alpha = 153 // 0.6
        unitText.textSize = small
        canvas.drawText(unit, cx, cy + big * 0.75f + small / 2f, unitText)
    }

    private fun angleFor(i: Int): Float = ANGLE_START + ANGLE_SWEEP * i / (presets.size - 1)

    private fun polar(cx: Float, cy: Float, r: Float, angleDeg: Float): FloatArray {
        val rad = Math.toRadians(angleDeg.toDouble())
        return floatArrayOf(cx + r * cos(rad).toFloat(), cy + r * sin(rad).toFloat())
    }

    private fun hitRect(i: Int, box: IntArray): Rect {
        val dial = box[2]
        val p = polar(box[0] + dial / 2f, box[1] + dial / 2f, dial * 0.363f + dial * 0.09f, angleFor(i))
        val half = (dial * 0.095f).roundToInt().coerceAtLeast(1)
        return Rect((p[0] - half).toInt(), (p[1] - half).toInt(), (p[0] + half).toInt(), (p[1] + half).toInt())
    }

    private fun parsePresets(raw: String?): IntArray? {
        val values = raw?.split(',', '，')?.mapNotNull { it.trim().toIntOrNull() } ?: return null
        return if (values.size >= 2) values.toIntArray() else null
    }

    private fun nearestIndex(v: Int): Int {
        var best = 0
        var bestGap = Int.MAX_VALUE
        presets.indices.forEach {
            val gap = abs(presets[it] - v)
            if (gap < bestGap) {
                bestGap = gap
                best = it
            }
        }
        return best
    }

    /**
     * 手指位置 → 档位下标。圆心里外都按角度算，落在下方缺口里则归到离它较近的那一端。
     */
    private fun indexAt(x: Float, y: Float): Int {
        val box = box()
        if (box[2] <= 0) return -1
        val cx = box[0] + box[2] / 2f
        val cy = box[1] + box[2] / 2f
        val dx = x - cx
        val dy = y - cy
        if (dx == 0f && dy == 0f) return -1
        var deg = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
        if (deg < 0f) deg += 360f
        val rel = (deg - ANGLE_START + 360f) % 360f
        val count = presets.size
        if (rel > ANGLE_SWEEP) {
            return if (rel - ANGLE_SWEEP < (360f - ANGLE_SWEEP) / 2f) count - 1 else 0
        }
        return (rel / ANGLE_SWEEP * (count - 1)).roundToInt().coerceIn(0, count - 1)
    }

    private fun select(target: Int) {
        if (target !in presets.indices || target == index) return
        index = target
        // 一次快速划过好几档时只响一下，不然变成持续的嗡嗡声
        val now = SystemClock.uptimeMillis()
        if (now - lastTickAt > 25) {
            lastTickAt = now
            detentTick()
        }
        contentDescription = describe()
        invalidate()
        onValueChange?.invoke(presets[index])
    }

    private fun describe(): String {
        val l = label
        return if (l != null) context.getString(R.string.dial_desc_with_label, l, textFor(presets[index]), unit)
        else context.getString(R.string.dial_desc, textFor(presets[index]), unit)
    }

    /**
     * 档位值怎么显示。绝大多数档位就是数字；[infinityValue] 那一档画成 ∞——
     * 「默认保留」存的是 0，意思是"无限天后自动保留"，写成 0 反而像"0 天就出队"。
     */
    private fun textFor(v: Int): String =
        if (v != NO_INFINITY && v == infinityValue) context.getString(R.string.dial_infinity) else v.toString()

    /** 给测试和无障碍用：某个档位值在界面上的读数（∞ 档返回「∞」）。 */
    fun displayText(v: Int): String = textFor(v)

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                dragging = false
                // 只有按在圆形轨道那圈环带上才由旋钮接管；圆心读数区和方框四角的空白都交还父容器，
                // 让整页能正常上下滑动（设置页套在 NestedScrollView 里）。
                if (!nearTrack(event.x, event.y)) return false
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                // 只滤掉按下后没超过 touchSlop 的抖动，其余一律按角度吸附到最近档位
                if (!dragging && abs(event.x - downX) < touchSlop && abs(event.y - downY) < touchSlop) return true
                dragging = true
                select(indexAt(event.x, event.y))
                return true
            }

            MotionEvent.ACTION_UP -> {
                select(indexAt(event.x, event.y))
                performClick()
                dragging = false
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                dragging = false
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    /**
     * 落点是否落在旋钮那段真的画了轨道的弧上：既要在环带半径内，也要在 240° 的张角内。
     * 下方 120° 是缺口，圆心读数区和方框四角同样是空白——这些都交还给父容器滚动。
     */
    private fun nearTrack(x: Float, y: Float): Boolean {
        val box = box()
        val dial = box[2]
        if (dial <= 0) return false
        val cx = box[0] + dial / 2f
        val cy = box[1] + dial / 2f
        val dx = x - cx
        val dy = y - cy
        // 正圆心里按下去没有方向可言，同样算空白
        if (dx == 0f && dy == 0f) return false
        val dist = sqrt(dx * dx + dy * dy)
        val r = dial * TRACK_RADIUS_RATIO
        val inner = (r - dial * TRACK_BAND).coerceAtLeast(0f)
        // 外沿多留一点，把挂在弧外侧的档位数字也罩进来
        val outer = r + dial * (TRACK_BAND + 0.09f)
        if (dist !in inner..outer) return false
        var deg = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
        if (deg < 0f) deg += 360f
        val rel = (deg - ANGLE_START + 360f) % 360f
        // 落在缺口里（两端各留 ARC_SLACK 余量除外）就不该由旋钮接手
        return rel <= ANGLE_SWEEP + ARC_SLACK || rel >= 360f - ARC_SLACK
    }

    /**
     * 读屏手指沿屏幕划过时，ExploreByTouchHelper 靠这里收到 hover 事件才会逐个档位念；
     * 它自己不会去抢 onHoverEvent，所以必须宿主转发。
     */
    override fun onHoverEvent(event: MotionEvent): Boolean = a11y.dispatchHoverEvent(event) || super.onHoverEvent(event)

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className = SeekBarClassName
        info.text = describe()
        info.isScrollable = true
    }

    override fun getAccessibilityClassName(): CharSequence = SeekBarClassName

    /**
     * 切档的那一下「咔」。
     *
     * 刻意不读系统的「触摸反馈」开关：用户关掉它通常是不想让所有操作都跟着震，
     * 不等于不许应用给自己的一处交互加反馈。参考仓库同样这么取舍。
     */
    private fun detentTick() {
        val vibrator = resolveVibrator() ?: return
        runCatching {
            if (!vibrator.hasVibrator()) return
            val amplitude = if (vibrator.hasAmplitudeControl()) 180 else VibrationEffect.DEFAULT_AMPLITUDE
            vibrator.vibrate(VibrationEffect.createOneShot(28, amplitude))
        }
    }

    private fun resolveVibrator(): Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Vibrator::class.java)
        }
}

/** 报成 SeekBar，读屏才会用「可调节控件」的语气念它，而不是一个死的图片。 */
private const val SeekBarClassName = "android.widget.SeekBar"

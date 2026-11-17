package com.changakira.screenshotbye.ui

import android.content.Intent
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import com.changakira.screenshotbye.R
import com.changakira.screenshotbye.core.Decision
import com.changakira.screenshotbye.core.DecisionOutcome
import com.changakira.screenshotbye.core.PendingShot
import com.changakira.screenshotbye.core.ShotState
import com.changakira.screenshotbye.core.ScreenshotRepository
import com.changakira.screenshotbye.databinding.ActivityReviewBinding
import com.changakira.screenshotbye.notify.Channels
import com.google.android.material.color.MaterialColors
import android.text.format.DateFormat
import java.util.Date
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * 逐个确认待处理截图的卡片页，通知点开就是这里。
 *
 * 两段式：先看一张答一张（保留 / 稍后 / 删除），整轮答完进批量屏，一次 deleteBatch 收口——
 * 这样连截十张也只问一次、只弹一次系统确认，而不是十张截图十条通知十个系统对话框。
 *
 * 本轮的清单从头到尾不缩短，每张记着自己的答复（照孪生项目 PhotosManager 的
 * `PhotoSelection.action` 那套来）：选「保留」只是把这张记成已保留并当场出队，卡片仍留在本轮，
 * 左右滑回去还能改判删除，批量屏里也照样列着它（没勾）。以前答完就把这张从列表里抹掉，
 * 用户一反悔就再也找不回来了。
 */
class ReviewActivity : ThemedActivity() {

    private lateinit var b: ActivityReviewBinding
    private lateinit var repo: ScreenshotRepository
    private lateinit var confirmAdapter: ReviewConfirmAdapter
    private val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "review-ui").apply { isDaemon = true }
    }

    /** 本轮快照：进页面时取一次，整轮不增不减，翻页和收口都按它算。 */
    private var items: List<PendingShot> = emptyList()
    private var index = 0

    /** 标了「删除」的，真正动手留到批量屏收口，中途随时能改判。 */
    private val marked = LinkedHashSet<Long>()

    /** 已答「保留 / 稍后」的，当场落库；记在这儿是为了翻回去时看得见自己选过什么。 */
    private val answered = LinkedHashMap<Long, Decision>()

    /** 一次只允许一个后台动作，防止连点把两张同时处理掉。 */
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Thumbs.init(this)
        Channels.ensure(this)
        b = ActivityReviewBinding.inflate(layoutInflater)
        setContentView(b.root)
        setSupportActionBar(b.toolbar)
        supportActionBar?.title = getString(R.string.review_title)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        b.toolbar.setNavigationOnClickListener { finish() }

        repo = ScreenshotRepository(this)
        confirmAdapter = ReviewConfirmAdapter { refreshConfirmLabel() }
        b.confirmList.layoutManager = LinearLayoutManager(this)
        b.confirmList.adapter = confirmAdapter

        wireSwipe()
        b.btnKeep.setOnClickListener { answer(Decision.KEEP) }
        b.btnSnooze.setOnClickListener { answer(Decision.SNOOZE) }
        b.btnDelete.setOnClickListener { markDelete() }
        b.btnConfirmBack.setOnClickListener { backToCards() }
        b.btnConfirmDelete.setOnClickListener { commitDeletes() }

        load()
    }

    private fun wireSwipe() {
        val detector = android.view.GestureDetector(
            this,
            object : android.view.GestureDetector.SimpleOnGestureListener() {
                override fun onDown(e: MotionEvent) = true

                override fun onFling(
                    e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float
                ): Boolean {
                    if (b.confirmList.visibility == View.VISIBLE) return false
                    if (abs(velocityX) < MIN_FLING || abs(velocityX) < abs(velocityY)) return false
                    goTo(if (e2.x < (e1?.x ?: 0f)) 1 else -1)
                    return true
                }
            }
        )
        b.shotImage.setOnTouchListener { _, event ->
            detector.onTouchEvent(event)
            true
        }
    }

    private fun load() {
        val focus = intent?.getLongExtra(EXTRA_FOCUS_ID, -1L) ?: -1L
        io.execute {
            val queue = repo.pending()
                .filter { it.state != ShotState.DELETING }
                .sortedBy { it.askAt }
            runOnUiThread {
                items = queue
                index = if (focus > 0) {
                    queue.indexOfFirst { it.mediaId == focus }.coerceAtLeast(0)
                } else 0
                if (items.isEmpty()) showEmpty() else renderCard()
            }
        }
    }

    private fun renderCard() {
        if (items.isEmpty()) {
            showEmpty()
            return
        }
        if (index !in items.indices) index = items.lastIndex
        b.reviewEmpty.visibility = View.GONE
        b.reviewCard.visibility = View.VISIBLE
        b.reviewActions.visibility = View.VISIBLE
        b.confirmList.visibility = View.GONE
        b.confirmActions.visibility = View.GONE
        supportActionBar?.title = getString(R.string.review_title)

        val shot = items[index]
        b.shotName.text = shot.name
        val base = getString(
            R.string.shot_meta,
            shot.folder.ifBlank { "-" },
            sizeOf(this, shot.size),
            DateFormat.getTimeFormat(this).format(Date(shot.takenAt))
        )
        b.shotMeta.text = if (shot.state == ShotState.WAITING) {
            val left = TimeUnit.MILLISECONDS.toMinutes(
                (shot.askAt - System.currentTimeMillis()).coerceAtLeast(0)
            ).toInt() + 1
            base + " · " + getString(R.string.review_countdown, left)
        } else base
        // 翻回已经答过的那张时，得看得见自己选的是什么，否则改判全凭记忆
        val decision = decisionOf(shot.mediaId)
        b.shotDecision.setText(noteOf(decision))
        b.shotDecision.setTextColor(
            MaterialColors.getColor(
                b.shotDecision,
                if (decision == Decision.DELETE) com.google.android.material.R.attr.colorError
                else com.google.android.material.R.attr.colorOnSurfaceVariant
            )
        )
        b.shotProgress.text = getString(R.string.review_progress, index + 1, items.size, marked.size)
        // 大图不进缩略图缓存，否则几张就占满内存
        Thumbs.media(b.shotImage, shot.contentUri, maxPx = 1024, cache = false)
    }

    /** 这张在本轮的答复：标了删除就是删除，否则看已答记录，都没有就是还没答。 */
    private fun decisionOf(mediaId: Long): Decision? =
        if (mediaId in marked) Decision.DELETE else answered[mediaId]

    private fun noteOf(decision: Decision?): Int = when (decision) {
        Decision.DELETE -> R.string.review_state_delete
        Decision.KEEP -> R.string.review_state_keep
        Decision.SNOOZE -> R.string.review_state_snooze
        null -> R.string.review_state_open
    }

    /** 本轮还欠答的格子。收口只认这个，不认位置（见 [showNext]）。 */
    private fun owed(): List<Int> = items.indices.filter { decisionOf(items[it].mediaId) == null }

    /** internal：滑动翻页的目标位置要能单独验（收口判错过一次，见 [showNext]）。 */
    internal fun goTo(delta: Int) {
        // 后台正落库这一张，此刻翻页会让「点的」和「屏幕上那张」对不上
        if (busy || items.isEmpty()) return
        val target = (index + delta).coerceIn(0, items.lastIndex)
        if (target != index) {
            index = target
            renderCard()
            return
        }
        // 翻到尽头还往右翻：整轮都答完了就去批量屏。从批量屏「返回」改判完，也走这条回去
        if (delta > 0 && owed().isEmpty()) showConfirmPhase()
    }

    /**
     * 保留 / 稍后：当场落库（保留就是出队、稍后就是重排计时），但卡片留在本轮，
     * 记成「已答」而不是抹掉——用户滑回去改判删除时，这张还得在。
     */
    private fun answer(decision: Decision) {
        if (busy || items.isEmpty()) return
        val shot = items[index]
        busy = true
        io.execute {
            // 改判：这张可能刚按保留出了队。稍后再问要重新排计时，先把快照放回清单
            if (decision == Decision.SNOOZE && repo.find(shot.mediaId) == null) repo.requeue(shot)
            val outcome = repo.decide(shot.mediaId, decision)
            val recordGone = repo.find(shot.mediaId) == null
            runOnUiThread {
                busy = false
                // 只有真出错才要说，并且这张留在原地等重试；
                // 正常答完不弹回执——卡片翻到下一张，本身就是「记下了」的反馈
                if (outcome is DecisionOutcome.Error && !recordGone) {
                    toast(outcome.reason)
                    return@runOnUiThread
                }
                // 记录已经被别人收走了（到期自动保留、通知上的快捷操作、系统那边已经删掉）也算答过：
                // 这一张不再欠问，否则它会把整轮永远卡住
                answered[shot.mediaId] = decision
                marked.remove(shot.mediaId)
                showNext()
            }
        }
    }

    /** 删除：只标记，真正删除留到批量屏一次做，中途还能翻回来改判保留。 */
    private fun markDelete() {
        if (items.isEmpty()) return
        marked.add(items[index].mediaId)
        showNext()
    }

    /**
     * 翻到下一张「还没答」的，一张都不欠了才收口。
     *
     * 收口不能看位置，得看「本轮还剩几张欠答」——位置在一个能被滑动乱翻的列表上不作数：
     * - 判「走到列表最后一格」：两张、第一张标删除、左滑翻回去改判保留，老代码把这张移出列表后
     *   第二张正好顶到当前格，位置以为问完了，页面直接收尾；
     * - 只从当前格往后找：左右滑动翻过去两张没答、停在最后一张上答掉它，后面已经没有格子了，
     *   被翻过去的那两张照样被漏掉。
     * 所以整轮一起数：先从当前格往后找第一张欠答的，往后找不着就绕回列表头接着找。
     * 答过的（保留 / 稍后 / 标了删除）都不欠答，一张都不欠了才进批量屏——
     * 哪怕一张都没标删除也进去，那一屏正是用户反悔改判的地方。
     */
    private fun showNext() {
        if (items.isEmpty()) {
            showEmpty()
            return
        }
        val open = owed()
        val next = open.firstOrNull { it >= index } ?: open.firstOrNull()
        if (next == null) {
            showConfirmPhase()
            return
        }
        index = next
        renderCard()
    }

    private fun showConfirmPhase() {
        b.reviewCard.visibility = View.GONE
        b.reviewActions.visibility = View.GONE
        b.reviewEmpty.visibility = View.GONE
        b.confirmList.visibility = View.VISIBLE
        b.confirmActions.visibility = View.VISIBLE
        supportActionBar?.title = getString(R.string.review_confirm_title)
        // 本轮每一张都列出来，勾着的才删；已保留的那些也在，用户正是在这儿反悔
        confirmAdapter.submit(items, marked) { id -> getString(noteOf(decisionOf(id))) }
        refreshConfirmLabel()
    }

    private fun refreshConfirmLabel() {
        val checked = confirmAdapter.checkedIds()
        // 批量屏上的勾选就是本轮的删除标记：取消勾选的那张退回原样（答过保留的仍是已保留，
        // 没答过的退回「还没答」），按「返回」回卡片还能接着答
        marked.clear()
        marked.addAll(checked)
        b.btnConfirmDelete.text = getString(R.string.review_confirm_delete_n, checked.size)
        b.btnConfirmDelete.isEnabled = checked.isNotEmpty()
    }

    private fun backToCards() {
        index = 0
        renderCard()
    }

    private fun commitDeletes() {
        val ids = confirmAdapter.checkedIds()
        // 按钮上写着「删除 N 张」，一张没勾就是「删除 0 张」，不必再弹一句吐司
        if (ids.isEmpty() || busy) return
        busy = true
        io.execute {
            // 带上本轮快照：勾上的可能是刚按保留出队的那张，清单里已经没有它了
            val plan = repo.deleteBatch(ids, known = items)
            runOnUiThread {
                busy = false
                if (plan.awaitingConfirm.isNotEmpty()) {
                    // 交给一次系统弹窗批量确认，避免每张弹一次
                    startActivity(
                        Intent(this, DeleteConfirmActivity::class.java)
                            .putExtra(
                                DeleteConfirmActivity.EXTRA_MEDIA_IDS,
                                plan.awaitingConfirm.toLongArray()
                            )
                    )
                    finish()
                    return@runOnUiThread
                }
                // 直删成功不报数：页面关掉、清单清空就是结果。只有失败要说，否则用户不知道那几张还在
                when {
                    plan.deleted > 0 && plan.failed.isNotEmpty() ->
                        toast(getString(R.string.review_done_partial, plan.deleted, plan.failed.size))

                    plan.deleted > 0 -> finish()

                    plan.failed.isNotEmpty() -> toast(plan.failed.first())

                    else -> Unit
                }
            }
        }
    }

    private fun showEmpty() {
        b.reviewCard.visibility = View.GONE
        b.reviewActions.visibility = View.GONE
        b.confirmList.visibility = View.GONE
        b.confirmActions.visibility = View.GONE
        b.reviewEmpty.visibility = View.VISIBLE
    }

    override fun onDestroy() {
        io.shutdownNow()
        super.onDestroy()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    companion object {
        /** 从通知点进来时优先展示的那一张。 */
        const val EXTRA_FOCUS_ID = "focus_media_id"
        private const val MIN_FLING = 600f
    }
}

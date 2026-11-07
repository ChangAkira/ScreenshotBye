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
import android.text.format.DateFormat
import java.util.Date
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * 逐个确认待处理截图的卡片页，通知点开就是这里。
 *
 * 两段式：先看一张标一张（留着 / 稍后再问 / 不需要），全部看完后进反选屏，
 * 一次 deleteBatch 收口——这样连截十张也只问一次、只弹一次系统确认，
 * 而不是十张截图十条通知十个系统对话框。
 */
class ReviewActivity : ThemedActivity() {

    private lateinit var b: ActivityReviewBinding
    private lateinit var repo: ScreenshotRepository
    private lateinit var confirmAdapter: ReviewConfirmAdapter
    private val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "review-ui").apply { isDaemon = true }
    }

    private var items: List<PendingShot> = emptyList()
    private var index = 0
    private val marked = LinkedHashSet<Long>()

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
        b.shotProgress.text = getString(R.string.review_progress, index + 1, items.size, marked.size)
        // 大图不进缩略图缓存，否则几张就占满内存
        Thumbs.media(b.shotImage, shot.contentUri, maxPx = 1024, cache = false)
    }

    /** internal：滑动翻页的目标位置要能单独验（收口判错过一次，见 [showNext]）。 */
    internal fun goTo(delta: Int) {
        val target = (index + delta).coerceIn(0, (items.size - 1).coerceAtLeast(0))
        if (target != index) {
            index = target
            renderCard()
        }
    }

    /** 留着 / 稍后：立刻落库，然后把这张移出本轮。 */
    private fun answer(decision: Decision) {
        if (busy || items.isEmpty()) return
        val shot = items[index]
        busy = true
        io.execute {
            val outcome = repo.decide(shot.mediaId, decision)
            runOnUiThread {
                busy = false
                // 只有真出错才要说，并且这张留在原地等重试；
                // 正常答完不弹回执——卡片翻到下一张，本身就是「记下了」的反馈
                if (outcome is DecisionOutcome.Error) {
                    toast(outcome.reason)
                    return@runOnUiThread
                }
                items = items.filterNot { it.mediaId == shot.mediaId }
                marked.remove(shot.mediaId)
                // 这张已经移出列表，后面那张正好顶到当前格，所以这一趟从当前格接着往下找
                showNext(fromCurrent = true)
            }
        }
    }

    /** 不需要，删除：只标记，真正删除留到最后一次做，中途还能翻回来改判保留。 */
    private fun markDelete() {
        if (items.isEmpty()) return
        marked.add(items[index].mediaId)
        showNext(fromCurrent = false)
    }

    /**
     * 往后翻到下一张「还没答」的，一张都不欠了才收口。
     *
     * 收口条件以前是「已经走到列表最后一格」，可在一个中途会缩短的列表上不成立：
     * 两张、第一张标删除、左滑翻回去把它改判保留——这张一移出列表，第二张就顶到了当前格，
     * 位置判断以为问完了，页面直接收尾，第二张压根没问过。现在改数「后面还有没有没答的」，
     * 标了删除的那些不算欠问（它们在反选屏收口），也不许再往前翻回来重问一遍。
     */
    private fun showNext(fromCurrent: Boolean) {
        val from = if (fromCurrent) index else index + 1
        val next = items.indices.firstOrNull { it >= from && items[it].mediaId !in marked }
        if (next == null) {
            if (marked.isEmpty()) showEmpty() else showConfirmPhase()
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
        confirmAdapter.submit(items, marked)
        refreshConfirmLabel()
    }

    private fun refreshConfirmLabel() {
        b.btnConfirmDelete.text = getString(R.string.review_confirm_delete_n, confirmAdapter.checkedCount())
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
            val plan = repo.deleteBatch(ids)
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

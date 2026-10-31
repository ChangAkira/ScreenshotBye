package com.changakira.screenshotbye.ui

import android.content.Intent
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
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
class ReviewActivity : AppCompatActivity() {

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

    private fun goTo(delta: Int) {
        val target = (index + delta).coerceIn(0, (items.size - 1).coerceAtLeast(0))
        if (target != index) {
            index = target
            renderCard()
        }
    }

    /** 留着 / 稍后再问：立刻落库，然后把这张移出本轮。 */
    private fun answer(decision: Decision) {
        if (busy || items.isEmpty()) return
        val shot = items[index]
        busy = true
        io.execute {
            val outcome = repo.decide(shot.mediaId, decision)
            runOnUiThread {
                busy = false
                when (outcome) {
                    is DecisionOutcome.Error -> {
                        toast(outcome.reason)
                        return@runOnUiThread
                    }

                    DecisionOutcome.NeedsSystemConfirm ->
                        toast(getString(R.string.toast_needs_confirm))

                    else -> Unit
                }
                items = items.filterNot { it.mediaId == shot.mediaId }
                marked.remove(shot.mediaId)
                advance()
            }
        }
    }

    /** 不需要，删除：只标记，真正删除留到最后一次做。 */
    private fun markDelete() {
        if (items.isEmpty()) return
        marked.add(items[index].mediaId)
        advance()
    }

    private fun advance() {
        if (index + 1 >= items.size) {
            if (marked.isEmpty()) showEmpty() else showConfirmPhase()
        } else {
            index++
            renderCard()
        }
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
        if (busy) return
        val ids = confirmAdapter.checkedIds()
        if (ids.isEmpty()) {
            toast(getString(R.string.review_none_selected))
            return
        }
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
                val msg = when {
                    plan.deleted > 0 && plan.failed.isNotEmpty() ->
                        getString(R.string.review_done_partial, plan.deleted, plan.failed.size)

                    plan.deleted > 0 -> getString(R.string.review_done_deleted, plan.deleted)

                    plan.failed.isNotEmpty() -> plan.failed.first()

                    else -> getString(R.string.review_nothing)
                }
                toast(msg)
                if (plan.deleted > 0) finish()
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

package dev.jev.wechatmood.hook

import android.app.Activity
import android.app.Dialog
import android.content.Intent
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import dev.jev.wechatmood.core.MoodLog
import dev.jev.wechatmood.core.RelationFactBridge
import dev.jev.wechatmood.reply.FactTimes
import dev.jev.wechatmood.reply.RelationFact
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 关系总览：全部会话的待办放进同一个窗口。
 *
 * 档案与待办原本只能按聊天打开，想知道「这几天要处理谁的事」得一个个点进去。
 * 这里只读本机档案库、不请求模型，按到期时间分组；点一条直接进对应聊天。
 */
internal class RelationOverviewUi(private val activity: Activity) {
    private val kit = PanelKit(activity)
    private val theme = kit.theme
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val listContainer = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
    private var dialog: Dialog? = null

    private data class Row(val talker: String?, val name: String, val fact: RelationFact)

    fun show() {
        val dialog = Dialog(activity)
        this.dialog = dialog
        val root = kit.root()
        val column = kit.content(root)
        column.addView(kit.heading("关系总览"))
        column.addView(kit.caption("所有会话的待办放在一起，按到期时间排；点一条进对应的聊天").apply {
            setPadding(0, theme.dp(4), 0, theme.dp(8))
        })
        val scroll = PanelMaxScroll(activity, (activity.resources.displayMetrics.heightPixels * 0.62f).toInt()).apply {
            addView(listContainer)
        }
        column.addView(scroll, LinearLayout.LayoutParams(-1, -2).apply { topMargin = theme.dp(6) })
        column.addView(theme.action("关闭", primary = true) { dialog.dismiss() },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = theme.dp(10) })

        dialog.setContentView(root)
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setGravity(Gravity.BOTTOM)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(ReplyMetrics.DIM)
        }
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        dialog.show()

        listContainer.addView(kit.body("正在汇总…"))
        scope.launch { load() }
        dialog.setOnDismissListener { scope.cancel() }
    }

    private suspend fun load() {
        val now = System.currentTimeMillis()
        // 只读本地档案库；读不到就当空，不把异常抛到界面。
        val groups = withContext(Dispatchers.IO) {
            runCatching { RelationFactBridge.dueContacts(activity) }.getOrDefault(emptyList())
        }
        val rows = groups.flatMap { group ->
            group.facts.map { Row(group.talker, group.name.ifBlank { "未命名会话" }, it) }
        }.filter { it.fact.occurredAt > 0 }.sortedBy { it.fact.occurredAt }
        MoodLog.i("RELATION_OVERVIEW_LOAD contacts=" + groups.size + " rows=" + rows.size)
        if (activity.isFinishing || activity.isDestroyed) return
        render(rows, now)
    }

    private fun render(rows: List<Row>, now: Long) {
        listContainer.removeAllViews()
        if (rows.isEmpty()) {
            listContainer.addView(kit.body("还没有带时间的约定"))
            listContainer.addView(kit.caption("在聊天里整理一次档案，或者手动加一条带时间的待办，这里就会汇总").apply {
                setPadding(0, theme.dp(4), 0, 0)
            })
            return
        }
        val overdue = rows.filter { FactTimes.overdue(it.fact.occurredAt, now) }
        val today = rows.filter { !FactTimes.overdue(it.fact.occurredAt, now) && FactTimes.sameDay(it.fact.occurredAt, now) }
        val tomorrow = rows.filter { bucketOf(it.fact.occurredAt, now) == 2 }
        val later = rows.filter { bucketOf(it.fact.occurredAt, now) == 3 }
        val head = buildString {
            append("共 ").append(rows.size).append(" 条")
            if (overdue.isNotEmpty()) append(" · 逾期 ").append(overdue.size)
            if (today.isNotEmpty()) append(" · 今天 ").append(today.size)
        }
        listContainer.addView(kit.body(head))
        section("已过期 · " + overdue.size + " 条", overdue, now, true)
        section("今天 · " + today.size + " 条", today, now, false)
        section("明天 · " + tomorrow.size + " 条", tomorrow, now, false)
        section("之后 · " + later.size + " 条", later, now, false)
    }

    private fun section(title: String, rows: List<Row>, now: Long, overdueFirst: Boolean) {
        if (rows.isEmpty()) return
        listContainer.addView(kit.sectionLabel(title))
        val ordered = if (overdueFirst) rows.sortedBy { it.fact.occurredAt } else rows
        ordered.forEach { row ->
            val whenText = if (overdueFirst) "已过 · " + FactTimes.label(row.fact.occurredAt, now)
                else FactTimes.label(row.fact.occurredAt, now)
            listContainer.addView(kit.listRow(row.fact.text, row.name + " · " + whenText) {
                openChat(row.talker)
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = theme.dp(2) })
        }
    }

    /** 0 已过期、1 今天、2 明天、3 更晚。只到日的条目当天结束前不算过期。 */
    private fun bucketOf(at: Long, now: Long): Int = when {
        FactTimes.overdue(at, now) -> 0
        FactTimes.sameDay(at, now) -> 1
        FactTimes.startOfDay(at) == FactTimes.startOfDay(now) + 86_400_000L -> 2
        else -> 3
    }

    /**
     * 打开对应会话。这版微信的 LauncherUI 不认带 Chat_User 的 extra，
     * 点了只会回到首页；ChattingUI 才是真正的聊天页入口。
     */
    private fun openChat(talker: String?) {
        val target = talker?.takeIf { it.matches(TALKER) }
        if (target == null) {
            MoodLog.w("RELATION_OVERVIEW_OPEN no_talker")
            kit.info("打不开这个聊天", "档案里还没记下它对应哪个会话；重新进一次那个聊天就会补上。")
            return
        }
        dialog?.dismiss()
        val intent = Intent().apply {
            setClassName("com.tencent.mm", "com.tencent.mm.ui.chatting.ChattingUI")
            putExtra("Chat_User", target)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val opened = runCatching { activity.startActivity(intent); true }.getOrDefault(false)
        MoodLog.i("RELATION_OVERVIEW_OPEN talker=" + target.take(6) + " ok=" + opened)
    }

    private companion object {
        val TALKER = Regex("[A-Za-z0-9_@.\\-]{1,256}")
    }
}

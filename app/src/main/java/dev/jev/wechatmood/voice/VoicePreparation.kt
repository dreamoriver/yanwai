package dev.jev.wechatmood.voice

import dev.jev.wechatmood.core.AnalysisInput
import dev.jev.wechatmood.reply.ReplyContext
import dev.jev.wechatmood.core.MessagePolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

object VoicePreparation {
    /** 一次最多真正调用原生转写这么多条；其余直接占位，群里几十条语音不会把界面拖住。 */
    private const val MAX_CONTEXT_TRANSCRIPTS = 3
    private const val SKIPPED_VOICE = VoiceText.SKIPPED

    suspend fun analysis(input: AnalysisInput, transcribe: suspend (VoiceSource) -> String): AnalysisInput {
        currentCoroutineContext().ensureActive()
        val target = input.voice?.let {
            requireNotNull(MessagePolicy.textOrNull(transcribe(it))) { "语音转写为空或超过 1000 字符，无法分析" }
        } ?: input.text
        var budget = MessagePolicy.MAX_CONTEXT_CHARACTERS - (input.quoted?.text?.length ?: 0)
        var voiceBudget = MAX_CONTEXT_TRANSCRIPTS
        val context = input.context.asReversed().mapNotNull { message ->
            currentCoroutineContext().ensureActive()
            if (budget <= 0) return@mapNotNull null
            if (message.voice != null && voiceBudget <= 0)
                return@mapNotNull message.copy(text = SKIPPED_VOICE, voiceState = VoiceState.SKIPPED)
            if (message.voice != null) voiceBudget--
            val prepared = if (message.voice == null) message else try {
                val text = requireNotNull(MessagePolicy.textOrNull(transcribe(message.voice)))
                message.copy(text = text, voiceState = VoiceState.READY)
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                message.copy(text = VoiceText.FAILED, voiceState = VoiceState.FAILED)
            }
            val size = prepared.text.length + (prepared.quoted?.text?.length ?: 0)
            if (size > budget) { budget = 0; null }
            else { budget -= size; prepared }
        }.asReversed()
        currentCoroutineContext().ensureActive()
        // 跳过的语音只是没转写，不是「尝试失败」，不能计入不可用数量。
        val failed = context.count { it.voiceState == VoiceState.FAILED }
        return input.copy(text = target, context = context,
            voiceState = if (input.voice == null) VoiceState.NONE else VoiceState.READY,
            coverage = input.coverage.copy(unavailableVoice = failed,
                truncated = input.coverage.truncated || context.size != input.context.size))
    }

    /**
     * @param existing 只读查询微信已保存的转写（VoiceTransText），不触发原生转写。
     *   命中即用，且不占 [MAX_CONTEXT_TRANSCRIPTS] 额度：微信已经转写过的语音再调一次
     *   原生接口既慢又会把额度吃光，让后面本来有文本的语音变成「未转写」。
     * @param onPlan 本轮真正要调用原生转写的条数，给界面做进度分母。语音总条数和它无关：
     *   已有转写的不占额度，超过上限的也不会转。
     */
    suspend fun reply(context: ReplyContext, existing: suspend (VoiceSource) -> String? = { null },
        onPlan: (Int) -> Unit = {},
        transcribe: suspend (VoiceSource) -> String): ReplyContext {
        var budget = ReplyContext.MAX_CHARACTERS
        var trimmed = context.trimmed
        var voiceBudget = MAX_CONTEXT_TRANSCRIPTS
        val transcripts = HashMap<Long, String>()
        for (message in context.messages) {
            currentCoroutineContext().ensureActive()
            val voice = message.voice ?: continue
            val ready = try { existing(voice) } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
            if (!ready.isNullOrBlank()) transcripts[message.id] = ready.trim()
        }
        // 用户明确长按的那条语音先转写：否则它后面几条较新的语音会把轮换预算用光，
        // 真正要回复的那条只剩「未转写」占位。已经有转写的不再占额度。
        val focus = context.messages.lastOrNull { it.id == context.focusMessageId && it.voice != null }
            ?: context.messages.lastOrNull { it.voice != null }
        // 计划转写条数必须在真正转写之前算：目标条先转，界面第一条语音就该有分母。
        // 规则与循环一致（已有转写跳过、目标条单独处理、上限 3 条），别拿语音总条数当分母。
        val planned = run {
            var budget = MAX_CONTEXT_TRANSCRIPTS
            var count = 0
            if (focus?.voice != null && transcripts[focus.id] == null) { budget--; count++ }
            for (message in context.messages.asReversed()) {
                if (budget <= 0) break
                if (message.voice == null || transcripts[message.id] != null) continue
                if (message.id == focus?.id) continue
                budget--; count++
            }
            count
        }
        onPlan(planned)
        if (focus?.voice != null && transcripts[focus.id] == null && voiceBudget > 0) {
            voiceBudget--
            try {
                val text = transcribe(focus.voice).trim()
                check(text.isNotBlank())
                transcripts[focus.id] = text
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { /* 主循环会把目标标成转写失败 */ }
        }
        val messages = context.messages.asReversed().mapNotNull { message ->
            currentCoroutineContext().ensureActive()
            if (budget <= 0) { trimmed = true; return@mapNotNull null }
            val prepared = when {
                message.voice == null -> message
                transcripts[message.id] != null ->
                    message.copy(text = transcripts.getValue(message.id), voiceState = VoiceState.READY)
                message.id == focus?.id -> message.copy(text = VoiceText.FAILED, voiceState = VoiceState.FAILED)
                voiceBudget <= 0 -> {
                    val rest = message.copy(text = SKIPPED_VOICE, voiceState = VoiceState.SKIPPED)
                    budget -= rest.text.length
                    return@mapNotNull rest
                }
                else -> {
                    voiceBudget--
                    try {
                        val text = transcribe(message.voice).trim()
                        check(text.isNotBlank())
                        message.copy(text = text, voiceState = VoiceState.READY)
                    } catch (e: CancellationException) { throw e }
                    catch (_: Exception) { message.copy(text = VoiceText.FAILED, voiceState = VoiceState.FAILED) }
                }
            }
            val end = minOf(budget, prepared.text.length).let {
                if (it < prepared.text.length && it > 0 && prepared.text[it - 1].isHighSurrogate() && prepared.text[it].isLowSurrogate()) it - 1 else it
            }
            if (end < prepared.text.length) trimmed = true
            budget -= end
            prepared.copy(text = prepared.text.take(end))
        }.asReversed()
        currentCoroutineContext().ensureActive()
        return context.copy(messages = messages, trimmed = trimmed)
    }
}

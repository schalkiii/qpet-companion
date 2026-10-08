package com.copilot.qqpet.engine.task

import com.copilot.qqpet.engine.model.StudyDispatchParam
import com.copilot.qqpet.engine.model.StudyDispatchResult
import com.copilot.qqpet.engine.utils.PetPureCalculations
import com.copilot.qqpet.protocol.QQPetDirectBridge
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * 负责学园学习调度。课程和阶段严格按用户配置，查询失败或没有匹配项时本轮停止。
 */
object PetStudyTask {

    private const val NETWORK_TIMEOUT_MS = 8000L

    suspend fun startSchoolAwait(
        bridge: QQPetDirectBridge,
        petId: String,
        courseName: String = "基础学园课程",
        page: Long = 6100L,
        subEventType: Long = 6101L,
        timeoutMs: Long = NETWORK_TIMEOUT_MS
    ): Triple<Int, String?, String?> =
        try {
            withTimeoutOrNull(timeoutMs) {
                suspendCancellableCoroutine { cont ->
                    bridge.startSchool(petId, courseName, page, subEventType) { code, storyId, _, errorMsg ->
                        if (cont.isActive) cont.resume(Triple(code, storyId, errorMsg))
                    }
                }
            } ?: Triple(-99, null, "网络响应超时")
        } catch (t: Throwable) {
            Triple(-99, null, t.message)
        }

    suspend fun executeAdaptiveStudy(
        bridge: QQPetDirectBridge,
        petId: String,
        param: StudyDispatchParam,
        onLog: (String) -> Unit
    ): StudyDispatchResult {
        val targetStage = resolveTargetStage(bridge, petId, param.customSchoolStage)
        if (targetStage <= 0) {
            onLog("⚠️ [学园调度] 没有可用的学园阶段，本次不降到初级学园")
            return StudyDispatchResult(code = -4, errorMsg = "学园阶段未知")
        }
        onLog("📚 [学园阶段] 锁定目标学园阶段: stage=$targetStage")

        val (evtCode, dynamicEvents) = PetWorkTask.querySelectEventsAwait(bridge, 6100L, petId, schoolStage = targetStage, careerType = 0)
        if (evtCode != 0 || dynamicEvents.isEmpty()) {
            onLog("⚠️ [学园调度] 课程查询失败 code=$evtCode，停在阶段 $targetStage，不改去固定课程")
            return StudyDispatchResult(code = evtCode, errorMsg = "课程查询失败")
        }
        onLog("📚 [课程拉取] 服务端返回 ${dynamicEvents.size} 门课程")
        val targetCourse = filterAndSelectCourse(dynamicEvents, param, onLog)
        if (targetCourse == null) {
            onLog("⚠️ [学园调度] 阶段 $targetStage 没有与所设科目或时长匹配的课程，本次不报名")
            return StudyDispatchResult(code = -3, errorMsg = "没有与所设科目或时长匹配的课程")
        }
        if (param.enableFatigueToAdventure && targetCourse.isFatigued) {
            return StudyDispatchResult(code = -2, isFatigued = true, fatigueTip = targetCourse.eventTips)
        }
        onLog("📚 [学园报名] 锁定课程: ${targetCourse.eventName} (时长:${targetCourse.costTime})，发起启程...")
        val (code, storyId, errMsg) = startSchoolAwait(bridge, petId, targetCourse.eventName, 6100L, targetCourse.subEventType)
        if (code == 0 && !storyId.isNullOrEmpty()) {
            return StudyDispatchResult(code = 0, storyId = storyId, courseName = targetCourse.eventName, subEventType = targetCourse.subEventType)
        }
        if (PetPureCalculations.isPetAlreadyOutError(code, errMsg)) {
            onLog("ℹ️ [学园报名] 小宠已在出行中 (${errMsg ?: "code=$code"})")
        } else {
            onLog("⚠️ [学园调度] ${targetCourse.eventName} 报名未成功 (code=$code, err=${errMsg ?: "无"})，不改报其它课程")
        }
        return StudyDispatchResult(
            code = code,
            errorMsg = errMsg ?: "报名未生效",
            courseName = targetCourse.eventName,
            subEventType = targetCourse.subEventType
        )
    }

    private suspend fun resolveTargetStage(bridge: QQPetDirectBridge, petId: String, customStage: Int): Int {
        if (customStage > 0) return customStage
        val details = PetWorkTask.querySecondMapInfoDetailsAwait(bridge, 6100L, petId)
        return if (details.code == 0 && details.currentStage > 0) details.currentStage else 0
    }

    private fun filterAndSelectCourse(
        events: List<QQPetDirectBridge.SelectEvent>,
        param: StudyDispatchParam,
        onLog: (String) -> Unit = {}
    ): QQPetDirectBridge.SelectEvent? {
        val available = events.filter { it.canDo }.ifEmpty { events }
        if (available.isEmpty()) return null
        val durationFiltered = when (param.customCourseDuration) {
            1 -> available.filter { it.costTime.contains("分") && !it.costTime.contains("90") && !it.costTime.contains("135") }
            2 -> available.filter { it.costTime.contains("小时") || it.costTime.contains("90") || it.costTime.contains("135") }
            else -> available
        }
        if (durationFiltered.isEmpty()) return null
        if (param.customCourseSubject == 0) {
            return durationFiltered[param.studyAttributeCursor % durationFiltered.size]
        }
        // 优先按 reward 文案里的三围属性匹配；匹配不到再用 subEvent 段位兜底
        // (61xx=智力/文科, 62xx=力量/体育, 63xx=魅力/艺术，与历史候选池一致)
        val rewardKey = when (param.customCourseSubject) {
            1 -> "智力"
            2 -> "力量"
            3 -> "魅力"
            else -> ""
        }
        val byReward = if (rewardKey.isNotEmpty()) durationFiltered.find { it.reward.contains(rewardKey) } else null
        if (byReward != null) return byReward

        // reward 文案没命中：打印候选现场，便于后续按真实 subEvent/reward 校准
        val subRange = when (param.customCourseSubject) {
            1 -> 6100L..6199L
            2 -> 6200L..6299L
            3 -> 6300L..6399L
            else -> return null
        }
        val bySub = durationFiltered.find { it.subEventType in subRange }
        if (bySub == null) {
            onLog("🔍 [课程科目] reward 未含「$rewardKey」且 subEvent 不在 $subRange，候选现场：")
            durationFiltered.take(8).forEach {
                onLog("    · subEvent=${it.subEventType} name=${it.eventName} reward=${it.reward} costTime=${it.costTime}")
            }
        }
        return bySub
    }
}

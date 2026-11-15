package com.byyourside.talkandcut.command

import android.content.Context
import com.byyourside.talkandcut.booth.Command
import com.byyourside.talkandcut.filter.EmotionInterpreter
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/** Team4 W8A16 NPU model for its trained function vocabulary; existing controls remain available. */
class Team4Interpreter(context: Context, private val scope: CoroutineScope) : CommandInterpreter, InterpreterFeedback {
    private val prefs = context.getSharedPreferences("team4", Context.MODE_PRIVATE)
    private val client = Team4Client(context)
    private val prefix = context.assets.open("team4/prompt-prefix.txt").bufferedReader().use { it.readText() }
    private val mutableEnabled = MutableStateFlow(prefs.getBoolean("enabled", true))
    val enabled = mutableEnabled.asStateFlow()
    private val mutableStatus = MutableStateFlow("Team4 NPU 모델 확인 중")
    val status = mutableStatus.asStateFlow()
    private val mutableBusy = MutableStateFlow(false)
    val busy = mutableBusy.asStateFlow()
    override var explanation: String? = null; private set
    override var rawOutput = ""; private set
    override var interpreterName = "Team4 W8A16 · NPU"; private set
    private var relation: Int? = null
    private var concept: Int? = null
    private var previousQuestion = ""
    private var stage = "음성 촬영"
    private var filterIndex = 0
    private var preparation: Job? = null
    init { if (mutableEnabled.value) prepare() else mutableStatus.value = "규칙 모드" }
    private fun prepare() {
        preparation = scope.launch {
            mutableStatus.value = "Team4 NPU 모델 준비 중"
            try { client.prepare(); mutableStatus.value = "Team4 W8A16 · NPU 사용 중" }
            catch (e: CancellationException) { throw e }
            catch (e: Throwable) { mutableStatus.value = "모델 연결 실패: ${e.message}" }
        }
    }
    suspend fun awaitPreparation() { preparation?.join() }
    fun setEnabled(value: Boolean) {
        prefs.edit().putBoolean("enabled", value).apply()
        mutableEnabled.value = value
        if (value) prepare() else mutableStatus.value = "규칙 모드"
    }
    override suspend fun interpret(text: String, ctx: BoothContext): List<Command> {
        explanation = null; rawOutput = ""
        val rules = RuleInterpreter.parse(text, ctx.hasFilter)
        val outsideTraining = rules.any { it is Command.Cancel || it is Command.Retake || it is Command.Start ||
            it is Command.Home || it is Command.Layout || it is Command.Caption || it is Command.Save ||
            it is Command.ResetFilter || it is Command.Result } ||
            (rules.any { it is Command.Filter } && !text.contains("추천") && !text.contains("컨셉"))
        if (!mutableEnabled.value || outsideTraining) {
            interpreterName = if (mutableEnabled.value) "규칙 · 모델 범위 밖 명령" else "규칙"
            return rules
        }
        interpreterName = "Team4 W8A16 · NPU"
        mutableBusy.value = true
        try {
            val prompt = prompt(text, ctx)
            rawOutput = client.query(prompt)
            android.util.Log.i("Team4Npu", "Model output: $rawOutput")
            val call = Team4Call.parse(rawOutput) ?: error("모델 출력 형식을 확인할 수 없어요")
            mutableStatus.value = "Team4 W8A16 · NPU 사용 중"
            val result = map(call, text, ctx)
            // This field is the outstanding question, not the last acknowledgement.
            // A previous "do not capture" acknowledgement biases subsequent requests.
            previousQuestion = explanation?.takeIf { '?' in it }.orEmpty()
            return result
        } catch (e: CancellationException) { throw e
        } catch (e: Throwable) {
            mutableStatus.value = "모델 오류: ${e.message}"
            explanation = "명령 모델을 실행하지 못했어요. 메뉴에서 모델 상태를 확인해 주세요."
            android.util.Log.e("Team4Npu", "Interpretation failed", e)
            return emptyList()
        } finally { mutableBusy.value = false }
    }
    private fun prompt(text: String, ctx: BoothContext): String {
        val relations = mapOf(1 to "개인 촬영", 2 to "커플", 3 to "친구", 4 to "가족", 5 to "동료", 6 to "기타", 7 to "비공개")
        val concepts = mapOf(1 to "로맨틱", 2 to "코믹", 3 to "감동", 4 to "시크", 5 to "활기참", 6 to "귀여움", 7 to "깔끔함", 8 to "따뜻함", 9 to "상관없음")
        val moodConcept = when (ctx.moodId) { "romantic" -> 1; "chic" -> 4; "happy" -> 5; "bright" -> 6; "warm" -> 8; else -> null }
        val state = JSONObject().put("people", ctx.headcount).put("relation", relations[relation] ?: JSONObject.NULL)
            .put("concept", concepts[concept ?: moodConcept] ?: JSONObject.NULL)
            .put("camera_status", ctx.cameraStatus).put("voice_capture_enabled", true)
            .put("recommendation_history", JSONObject()
                .put("pose", JSONArray(listOfNotNull(ctx.poseId)))
                .put("filter", JSONArray(listOfNotNull(ctx.moodId)))
                .put("frame", JSONArray(listOf(ctx.themeId))))
        val input = JSONObject().put("stage", stage)
            .put("previous_question", previousQuestion.ifEmpty { "촬영 준비가 끝났어요. 찍어줘라고 말해주세요" })
            .put("state", state).put("utterance", text)
        android.util.Log.d("Team4Npu", "Model input: $input")
        return prefix + input.toString() + "<|eot_id|><|start_header_id|>assistant<|end_header_id|>\n\n"
    }
    private fun map(call: Team4Call, text: String, ctx: BoothContext): List<Command> {
        val value = call.value
        return when (call.function) {
            0 -> when (value) {
                in 1..4 -> listOf(Command.Headcount(value)).also { stage = "관계 확인" }
                -1 -> emptyList<Command>().also { explanation = "촬영 인원은 1명부터 4명까지 지원해요." }
                else -> emptyList<Command>().also { stage = "인원 확인"; explanation = "몇 명이 촬영하나요?" }
            }
            1 -> emptyList<Command>().also {
                if (value == 0) { stage = "관계 확인"; explanation = "어떤 관계인가요? 친구, 커플, 가족처럼 말해 주세요." }
                else { relation = value; stage = "컨셉 확인"; explanation = "관계를 설정했어요. 어떤 분위기로 촬영할까요?" }
            }
            2 -> emptyList<Command>().also {
                if (value == 0) { stage = "컨셉 확인"; explanation = "어떤 분위기를 원하나요? 따뜻함, 시크함, 귀여움처럼 말해 주세요." }
                else { concept = value; stage = "음성 촬영"; explanation = "촬영 컨셉을 설정했어요. 포즈, 필터 또는 프레임을 추천해 달라고 말해 주세요." }
            }
            3 -> listOf(if (value == 2) Command.PoseNext else Command.PosePlan)
            4 -> {
                val palette = when (concept) {
                    1 -> listOf("로맨틱", "몽환적인")
                    2, 5 -> listOf("신나는", "선명한")
                    3, 8 -> listOf("따뜻한", "차분한")
                    4 -> listOf("시크한", "흑백")
                    6 -> listOf("화사한", "설레는")
                    else -> listOf("화사한", "따뜻한", "청량한")
                }
                if (value == 2) filterIndex++ else filterIndex = 0
                val mood = EmotionInterpreter.interpretMood(palette[filterIndex % palette.size], false)
                listOfNotNull(mood?.let(Command::Filter))
            }
            5 -> listOf(if (value == 2) Command.FrameNext else Command.FrameRecommend)
            6 -> when {
                value == 0 -> emptyList<Command>().also { explanation = "새 촬영을 시작하지 않을게요." }
                ctx.cameraStatus != "ready" -> emptyList<Command>().also { explanation = "카메라가 촬영 준비 상태일 때 말해 주세요." }
                value == 2 -> emptyList<Command>().also { explanation = "지금 촬영할까요?" }
                else -> listOf(Command.Capture(RuleInterpreter.parse(text).filterIsInstance<Command.Capture>().firstOrNull()?.delay ?: 3))
            }
            else -> emptyList()
        }
    }
    fun close() = client.close()
}

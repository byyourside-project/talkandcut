# Talk & Cut Android 시연 앱 계획

기준: `byyourside-project/talkandcut` 브랜치 `feat/voice-first-booth` (커밋 `969bbf6`)
목표: **시연용 Android 앱.** 화면은 일반 카메라 앱처럼 촬영 화면 하나, 음성이 안 될 때는 오른쪽 위 메뉴 버튼으로 같은 동작을 터치로 진행.
작성: 2026-11-14

저장소에 `docs/mobile-port-plan.md`로 두고, Claude Code에서 "docs/mobile-port-plan.md Step N 진행해줘"로 시작한다.

---

## 1. 화면

화면은 촬영 화면 하나뿐이다. 홈 화면, 결과 화면은 따로 만들지 않는다.

```
┌──────────────────────────────┐
│ CUT 2/4  ●●○○            [≡] │ ← 오른쪽 위: 메뉴(터치 조작)
│                              │
│                              │
│        카메라 미리보기          │
│     (보정 셰이더 적용)          │
│      + 포즈 윤곽선 겹침         │
│                              │
│             3                │ ← 카운트다운
│                              │
│ 얼굴 옆 브이                   │ ← 포즈 이름·팁
│ "몽환적인 느낌으로 채도 −15…"   │ ← 들은 말 / 응답
│ [네컷]      🎙 듣는 중          │
└──────────────────────────────┘
```

- **전체 화면 카메라** 위에 컷 진행, 포즈 윤곽선, 카운트다운, 음성 상태, 응답 한 줄만 올린다.
- **왼쪽 아래 썸네일**: 찍은 컷이 있으면 네컷 합성 미리보기. 누르면 전체 화면으로 크게 보기 + 저장 버튼. 네 컷을 다 찍으면 자동으로 열린다.
- **오른쪽 위 메뉴(≡)**: 바텀시트. 음성 명령과 같은 동작을 버튼으로 실행한다. 음성 인식이 실패해도 시연이 멈추지 않게 하는 용도.

### 메뉴 구성
| 그룹 | 버튼 | 대응 명령 |
| --- | --- | --- |
| 촬영 | 3초 뒤 촬영 · 바로 촬영 · 취소 · N번째 컷 다시 찍기 · 처음부터 | CAPTURE, CANCEL, RETAKE, START |
| 포즈 | 인원 1~4 · 다른 포즈 | HEADCOUNT, POSE_NEXT |
| 보정 | 감성 칩 15개 · 조금 더 / 덜 · 원본 | FILTER, RESET_FILTER |
| 프레임 | 2×2 / 1×4 · 프레임 추천 · 다른 프레임 · 문구 입력 | LAYOUT, FRAME_RECOMMEND, FRAME_NEXT, CAPTION |
| 저장 | 사진 저장 | SAVE |
| 음성 | 마이크 다시 연결 · 음성 안내 켜기/끄기 · **문장 직접 입력** | — |
| 디버그 | 마지막 명령 JSON 보기 | — |

**모든 버튼은 음성과 같은 경로를 탄다**: 버튼 → `Command` 객체 → `CommandExecutor`. 음성 경로만 앞에 STT + 해석이 붙는다. 그래서 버튼으로 되는 건 음성으로도 되고, 시연 중 음성이 틀려도 같은 결과를 버튼으로 보여줄 수 있다.

### 웹과 달라지는 점
| 웹 | 앱 |
| --- | --- |
| 홈에서 레이아웃 고르고 시작 | 바로 촬영 화면. 기본 2×2, 메뉴나 "세로로 바꿔"로 변경 (첫 컷 찍기 전만) |
| 결과 화면에서 프레임·필터 편집 | 촬영 중이든 다 찍은 뒤든 음성·메뉴로 변경, 썸네일에 바로 반영 |
| 포즈 인식, 관절 판정, 인원 자동 감지, 조건부 셔터 | **없음.** 윤곽선만 표시, 인원은 음성·메뉴로 |
| 슬라이더 8개 | 없음. 감성 칩과 "조금 더/덜"만 (시연용 단순화) |

---

## 2. 범위

**만든다**
- 카메라 미리보기 + 보정 셰이더 (`glfilter.js` 이식)
- 네 컷 촬영, 카운트다운, 셔터 효과, 재촬영
- 포즈 윤곽선: 카탈로그 51종, 4컷 플랜 추천, 얼굴 높이 맞춤 배치 (`pose-catalog.js`, `recommendPlan`, `guidePlacement`)
- 감성 보정 + 멀티턴 (`emotion.js`)
- 프레임 테마·추천·합성·갤러리 저장 (`frames.js`)
- 음성 명령: 임시로 안드로이드 `SpeechRecognizer` + 규칙 파서(`intent.js` 이식) + `TextToSpeech`
- 오른쪽 위 메뉴

**안 만든다**
- STT·명령 판단·TTS **모델** (양자화 후 전달받음 → 교체 지점만 만들어 둠)
- 포즈 판정 관련 전부, 홈/결과 화면, 설정 화면, 공유 기능

---

## 3. 기술 스택

| 항목 | 선택 |
| --- | --- |
| 언어·UI | Kotlin + Jetpack Compose |
| 카메라 | CameraX |
| 보정 | OpenGL ES 3.0 (`GLSurfaceView` + `SurfaceTexture`) |
| 합성 | `Canvas` → PNG, `MediaStore` 저장 |
| 음성(임시) | `SpeechRecognizer`(ko-KR), `TextToSpeech` |
| 최소 SDK | 26 |
| 방향 | 세로 고정 |
| 구조 | 단일 Activity, `BoothViewModel` 하나에 상태 전부 |

---

## 4. 폴더 구조

```
talkandcut/
├─ (웹 파일 그대로 — 수정하지 않음)
└─ android/
   ├─ CLAUDE.md
   └─ app/src/main/
      ├─ assets/poses/{1..4}/*.webp      ← assets/poses PNG 변환본
      ├─ assets/pose-catalog.json        ← pose-catalog.js에서 변환
      └─ java/.../talkandcut/
         ├─ MainActivity.kt
         ├─ booth/   BoothViewModel, BoothState, CommandExecutor
         ├─ ui/      CameraScreen, BoothMenuSheet, ResultViewer, PoseGuideOverlay
         ├─ camera/  CameraController, FilterRenderer(GL)
         ├─ filter/  FilterParams, Moods, EmotionInterpreter
         ├─ pose/    PoseCatalog, PosePlanner, GuidePlacement
         ├─ frame/   Layouts, Themes, FrameRecommender, Compositor
         ├─ command/ Command(sealed), CommandInterpreter, RuleInterpreter
         └─ voice/   SpeechInput, SpeechOutput, AndroidSpeechInput, AndroidSpeechOutput, VoiceSession
```

---

## 5. 진행 단계

시연용이라 5단계로 줄였다. 각 Step = Claude Code 세션 하나 = 커밋/PR 하나.

### Step 1. 카메라 화면 + 촬영 (터치만)
- `android/` 프로젝트 생성, 카메라 권한
- 전체 화면 CameraX 미리보기(전면, 거울 모드)
- 뷰파인더 비율을 레이아웃 칸 비율(`cellAspect`)에 맞춤, 바깥은 어둡게
- 상단 CUT 표시, 카운트다운, 셔터 플래시+소리
- 오른쪽 위 메뉴: 이 단계에선 촬영·취소·재촬영·처음부터·레이아웃만
- `BoothViewModel`: `main.js`의 `state`, `nextIndex`, `requestCapture`, `startCountdown`, `shoot`, `retake`, `resetSession` 이식
- 찍은 컷은 보정 전 원본 Bitmap으로 보관

**완료 기준**: 실기기에서 메뉴로 네 컷 촬영·재촬영이 된다.

### Step 2. 보정 셰이더 + 감성 보정
- `glfilter.js` 셰이더를 `FilterRenderer`로 이식(uniform 이름·계산식 그대로), 미리보기에 실시간 적용
- `emotion.js` 이식: `MOODS`, `interpretMood`, `applyMood`, `explain`, `nearestMood`
- 메뉴 보정 그룹: 감성 칩, 조금 더/덜, 원본
- 응답 한 줄에 `explain` 결과 표시("몽환적인 느낌으로 채도 −15, …")

**완료 기준**: 감성 칩을 누르면 미리보기 색감이 바뀌고 설명이 뜬다. 웹 `tests/intent.test.mjs`의 보정 관련 케이스를 JUnit으로 옮겨 통과.

### Step 3. 포즈 윤곽선
- `pose-catalog.js` → `assets/pose-catalog.json` 변환 스크립트, PNG → WebP 변환
- `recommendPlan`(역할·분위기 점수), `guidePlacement` 이식
- 현재 컷 윤곽선을 뷰파인더 위에 반투명으로, 하단에 포즈 이름·팁
- 메뉴 포즈 그룹: 인원 1~4, 다른 포즈
- 분위기가 바뀌면 아직 안 찍은 컷만 다시 추천

**완료 기준**: 인원 1~4명 × 레이아웃 2종에서 윤곽선이 칸 안에 잘리지 않는다.

### Step 4. 프레임 합성 + 썸네일 + 저장
- `frames.js` 이식: `LAYOUTS`, `cellRects`, `THEMES`, 패턴, `recommendFrames`, `averageColor`, `composite`
- 합성할 때 원본에 보정 다시 적용(비파괴) — 오프스크린 GL 렌더
- 왼쪽 아래 썸네일 → 누르면 크게 보기 + 저장, 네 컷 완료 시 자동으로 열림
- 메뉴 프레임·저장 그룹, 문구 입력
- `MediaStore`로 갤러리 저장

**완료 기준**: 네 컷 완료 → 결과 보기 → 프레임·보정 변경 즉시 반영 → 갤러리에 600×1800 / 1200×1800 PNG 저장.

### Step 5. 음성
- 인터페이스 3개(6장) + 임시 구현
  - `AndroidSpeechInput`: `SpeechRecognizer` ko-KR, 한 문장 끝나면 자동 재시작, 오류·타임아웃 상태(`voice.js` 상태 이름 그대로)
  - `RuleInterpreter`: `intent.js` 이식. `POSE_MATCH`는 일반 3초 촬영 + "포즈 판정은 지원하지 않아서 3초 뒤에 찍을게요"
  - `AndroidSpeechOutput`: `TextToSpeech` 한국어, 속도 1.1
- `VoiceSession`: 안내 중엔 인식 멈춤, 끝나면 재개
- 메뉴 음성 그룹: 마이크 재연결, 안내 켜기/끄기, **문장 직접 입력**(음성과 같은 해석 경로), 마지막 명령 JSON
- 웹 `tests/intent.test.mjs` 전체를 JUnit으로 옮겨 통과

**완료 기준**: "찍어줘 / 바로 찍어 / 두 번째 컷 다시 찍어 / 몽환적인 느낌으로 / 조금 더 따뜻하게 / 다른 포즈 / 우리 두 명이야 / 프레임 추천해줘 / 저장해줘"가 실기기에서 동작.

### (나중) Step 6. 모델 연결
모델을 받으면 `CommandInterpreter`·`SpeechInput`·`SpeechOutput` 구현 클래스만 추가한다. 메뉴 디버그 그룹에 "규칙 ↔ 모델" 전환 토글을 붙인다.

---

## 6. 모델 교체 지점

```kotlin
interface SpeechInput {
    val state: StateFlow<VoiceState>   // OFF, CONNECTING, LISTENING, SPEAKING, DENIED, UNSUPPORTED, ERROR
    val transcripts: Flow<String>      // 최종 문장만
    fun start(); fun stop(); fun pause(); fun resume()
}

interface CommandInterpreter {
    suspend fun interpret(text: String, ctx: BoothContext): List<Command>
}

interface SpeechOutput {
    suspend fun say(text: String)      // 끝날 때까지 suspend
    fun cancel()
}
```

- `BoothContext`: 현재 보정값, 분위기, 인원, 레이아웃, 찍은 컷 수, 직전 추천(포즈·필터·프레임)
- `Command`: `intent.js` 상단 스키마를 sealed class로. 모델 출력 JSON → `Command` 변환 함수를 따로 둔다.

**모델 받을 때 확인할 것**: 런타임(QNN/LiteRT/ONNX), 입력 형식(STT는 샘플레이트·채널), 명령 판단 출력 JSON 스키마, TTS 출력 샘플레이트, 대상 칩셋, 추론 시간.

---

## 7. `android/CLAUDE.md` 초안

```markdown
# Talk & Cut Android (시연용)

웹 MVP(저장소 루트 index.html, src/*.js)를 안드로이드 카메라 앱 형태로 옮긴다.
동작 기준은 웹 코드다. 애매하면 웹 코드를 읽고 똑같이 만든다. 웹 파일은 수정하지 않는다.

## 화면
- 촬영 화면 하나. 홈·결과 화면을 따로 만들지 않는다.
- 오른쪽 위 메뉴 버튼(바텀시트)으로 모든 음성 명령을 터치로 실행할 수 있어야 한다.
- 버튼과 음성은 같은 Command → CommandExecutor 경로를 탄다.

## 범위
- 포즈는 윤곽선 이미지만. 포즈 인식·관절 판정·인원 자동 감지·조건부 셔터는 만들지 않는다.
- STT/명령 판단/TTS 모델은 없다. voice/, command/ 인터페이스 뒤에 임시 구현만 둔다.
- 시연용이다. 설정 화면, 공유, 다국어, 태블릿 대응은 하지 않는다.

## 규칙
- 안내·오류 문구는 웹 문구를 그대로 쓴다.
- 파서·보정·합성 로직은 JUnit으로 검증한다: ./gradlew test
- 한 커밋은 docs/mobile-port-plan.md의 Step 하나.
```

---

## 8. Claude Code 진행 방법

1. `feat/android-app` 브랜치에서 Claude Code 실행, 먼저 `android/CLAUDE.md`와 이 문서를 넣는다.
2. Step마다 **Plan 모드로 시작**해 계획을 확인한 뒤 실행.
3. Step이 끝나면 `./gradlew test` → `./gradlew installDebug`로 실기기 확인 → 커밋.

### Step별 시작 프롬프트
- **Step 1**: "docs/mobile-port-plan.md Step 1 진행. android/ 프로젝트 만들고, src/main.js의 촬영 흐름(state, requestCapture, startCountdown, shoot, retake)을 BoothViewModel로 옮겨서 CameraX 전체 화면 촬영 + 오른쪽 위 메뉴로 네 컷 찍게 해줘."
- **Step 2**: "Step 2. src/glfilter.js 셰이더를 FilterRenderer로, src/emotion.js를 EmotionInterpreter로 옮겨줘. 셰이더 uniform 이름과 계산식은 그대로."
- **Step 3**: "Step 3. src/pose-catalog.js를 assets/pose-catalog.json으로 바꾸는 스크립트 만들고, PNG를 WebP로 변환해줘. src/pose.js의 recommendPlan, guidePlacement만 옮겨. 판정 코드는 옮기지 마."
- **Step 4**: "Step 4. src/frames.js를 옮겨서 왼쪽 아래 썸네일과 결과 보기, 갤러리 저장까지."
- **Step 5**: "Step 5. 6장 인터페이스 만들고 src/intent.js를 RuleInterpreter로, src/voice.js 상태 관리를 VoiceSession으로 옮겨줘. tests/intent.test.mjs 케이스를 JUnit으로 옮겨서 전부 통과시켜."

---

## 9. 시연 체크리스트

- [ ] 시연 기기에 한국어 음성 인식(Google 앱) 동작 확인, 가능하면 오프라인 한국어 팩 설치
- [ ] 마이크·카메라 권한 미리 허용
- [ ] 조용한 곳 / 시끄러운 곳 각각 리허설, 안 되는 문장은 메뉴로 대체하는 동선 연습
- [ ] 스피커 안내가 명령으로 다시 인식되지 않는지 확인
- [ ] 갤러리 저장 결과 확인
- [ ] 미리 찍어 둔 네컷 결과물(시연 실패 대비)

## 10. 위험

| 항목 | 대응 |
| --- | --- |
| `SpeechRecognizer`가 문장마다 끊기고 기기에 따라 시작음이 남 | 자동 재시작 루프, 소리는 기기 설정에서 확인. 안 되면 메뉴 |
| 인식 서비스가 기기에 없거나 네트워크 필요 | 메뉴 "문장 직접 입력"으로 같은 해석 경로 시연 |
| 윤곽선 PNG 21MB | WebP 변환 후 APK 크기 확인 |
| GL 미리보기 성능 | 미리보기 해상도 720p로 제한 |

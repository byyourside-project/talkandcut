# Team4 command model

Source: https://github.com/byyourside-project/talkandcut/releases/tag/team4-archive-20261116

- Original ZIP: team4-20261115T145656Z-1-001.zip (1,294,686,382 bytes)
- ZIP SHA-256: 2e852561568d5e1ac7379ff5a9fe5e37eb8e25ed6efbb1598ac1b2b04d529de2
- Context: llama32_hf_v68.bin (1,527,857,152 bytes)
- Context SHA-256: 0614146a2d06be3f3e193bbab71749146f475933ef2ef457802937b3ea50dd7a
- Llama-3.2-1B-Instruct-TalkCut-v3.0, W8A16, QCS6490 / HTP v68 / SoC 93
- Provided runtime: Genie 1.13.0, QAIRT 2.40.0.251030, with the original tokenizer and prompt.

The model is a text-to-function intent classifier, not an audio-to-text model. Whisper
continues to transcribe the selected microphone; Team4 handles its trained command vocabulary.
The original artifact's libraries, configuration and prompt are used, with file paths made
absolute for Android. No model reconversion or re-quantization is performed.

Prepare native libraries/assets with `tools/Prepare-Team4.ps1`. Install the debug APK, then
use `tools/Prepare-Team4.ps1 -InstallToBoard` to copy the large model into app-private files.
The context is deliberately outside the APK; an ordinary APK update preserves it. A full
uninstall removes it and requires re-installing the model.

The runtime uses the provided Genie C API through JNA in a private bound service process.
Function output is validated as one known `maum_0` through `maum_6` call before mapping to app
commands. Capture action 0 suppresses new captures; action 2 asks for confirmation. The
latest camera state is checked again before executing a capture. Existing controls outside
the trained function vocabulary (cancel, save, layout, retake, direct adjustments) retain
their existing implementation. The settings menu can switch between Team4 and rule mode.

Board validation (2026-11-16): the private service initialized QnnHtp v68, and successive
typed requests `찍지마` and `지금촬영해줘` produced action 0 and action 1 respectively.
The latter took a USB-camera photo and advanced CUT 1/4 to CUT 2/4. These tests isolate
command inference from microphone transcription. Warm inference took about 6–8 seconds.
The 93 JVM tests passed. Speech and NPU initialization are sequenced to reduce peak RAM,
and the bound service inherits foreground importance while the booth is active.

`previous_question` contains an outstanding question only. Passing the acknowledgement
"새 촬영을 시작하지 않을게요" here caused this artifact to suppress later explicit captures;
the integration clears acknowledgements and uses the capture-ready prompt instead.

## Prefix cache

The service primes the unchanged trained system prompt with a neutral, non-executable
request during preparation. Subsequent queries use Genie's `SENTENCE_REWIND` (5): the
complete current prompt is supplied, so Genie retains only its common KV prefix and
replaces the previous request and generated response. No previous camera state is
appended as conversation history. The cache is private to the existing worker/dialog
and disappears when that process closes. A rejected rewind disables reuse for that
process and retries the full prompt after a reset.

The release's own `genie-t2t-run --rewind` benchmark on this board measured 5.738 s
for a full prompt and 2.029 s for a changed utterance with the same prefix. First-token
latency fell from 4.439 s to 0.451 s. Both responses were correct (capture, then no
capture). Startup now includes the one-time prefix priming; it does not execute actions.
In-app warmed queries measured approximately 1.82–2.21 s. The camera readiness guard
checks both the request-time state and the current state so a request received during
countdown cannot become a new capture after inference finishes.

# Android device selection

For a fresh checkout, run `android/tools/Download-SpeechModels.ps1` and
`android/tools/Prepare-Team4.ps1` before building. Model weights and the downloaded
inference runtime are excluded from Git. After installing the debug APK, run
`android/tools/Prepare-Team4.ps1 -InstallToBoard -Device <adb-device>` to install the
Team4 model in app-private storage. See `android/third-party/speech/README.md` and
`android/third-party/team4/README.md` for provenance, checksums and latency measurements.
The final integrated build passed 96 unit tests and was installed on the board.

The first section of the right-hand menu contains camera, microphone and speaker dropdowns.
`기본` uses the system default. USB entries are numbered within each device category and
include product names. Device callbacks update the inventory; missing selections fall back
to default. Preferences are stored locally.

Camera: CameraX for system cameras, direct UVC for USB webcams. Default prefers a USB
webcam when the system exposes no cameras. Preview and capture share the selected source.
USB permission is requested by Android; backgrounding closes the UVC stream.

Microphone: USB microphones use direct UAC capture; other routes use AudioRecord.setPreferredDevice.
USB PCM is downmixed without dropping samples and passed at its true rate to bundled
Whisper-small int8 with language set to Korean. Only the VAD branch is resampled to 16 kHz,
using an anti-aliasing FIR filter. No system SpeechRecognizer service or network is
required. The screen shows input level, the actual microphone and the last recognized text.

Speaker: MediaPlayer.setPreferredDevice for synthesized speech and AudioTrack.setPreferredDevice
for countdown/shutter sounds. Available audio routes come from AudioManager; USB detection
alone does not mean a device is available as an Android audio route.

Validation: assembleDebug and all 90 unit tests passed. On the connected Android 13
board, SPC-A1200MB Webcam was listed, selected, preserved across restarts, previewed and used
to capture a shot. The board's AudioRecord USB DSP path returned zero samples with ASoC
prepare error -131. Direct UAC capture produced real 48 kHz mono input from SPC-A1200MB.
An actual diagnostic recording differed substantially between the unmodified 48 kHz source
and the initial unfiltered 16 kHz decimation. ASR now receives the source PCM directly and
uses the runtime's resampler. An additional issue was microphone selection: the webcam's
microphone was selected while the user was speaking into a separate USB microphone.
Selected `USB 2 · USB Audio Device` (GeneralPlus), granted its USB permission and verified
48 kHz capture. With that input and Whisper-small, the live recognized-text panel showed
“3초 뒤 촬영” and the booth advanced to the next shot. Typical observed inference was
1–3 seconds, with some longer segments around 4.5 seconds.

Short-command segmentation combines an adaptive energy gate with Silero VAD, retains
pre-roll, and finishes after silence or a bounded four-second window. Microphone teardown
and reopen are serialized to avoid claiming the same USB audio interface twice.
UAC initialization, capture and teardown all run on one dedicated OS thread because the
prebuilt library retains a thread-local JNIEnv. Using Dispatchers.IO across suspensions
caused a native abort in DeleteGlobalRef during release; repeated stop/restart cycles on
the dedicated thread were verified on the board without process restart.
The recognized-text area is always visible beneath the offline/microphone status.
Command normalization accepts SenseVoice syllable spacing such as “바로 찍 어 줘”.

Large screens use landscape immersive mode and a full-screen camera preview, with the
photo's portrait crop marked by a thin outline. Settings open as a right-hand panel.
Compact screens retain the original portrait preview and bottom sheet. On this board,
the HDMI monitor is Android display 2 (1920×1080); launch with:

```
adb shell am start --display 2 --windowingMode 1 -n com.byyourside.talkandcut/.MainActivity
```

Verified the HDMI screenshot at 1920×1080 with full-screen preview, recognized-text panel
and captured-shot thumbnails. A locally synthesized Korean WAV was recognized as
“바로 찍 어 줘”; the command normalizer maps that actual model output to immediate capture.

The UVC dependency is vendored with source provenance and notices under android/uvc.

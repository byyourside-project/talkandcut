# Embedded Korean speech recognition

- Runtime: sherpa-onnx 2.0.1, https://github.com/k2-fsa/sherpa-onnx/releases/tag/v2.0.1
- ASR: Whisper-small multilingual by OpenAI (MIT), int8 ONNX export by sherpa-onnx:
  https://huggingface.co/csukuangfj/sherpa-onnx-whisper-small
  Pinned revision: 8f3c18b358db4d1f2fc1eae49d75cd20989e4309
  Original model: https://github.com/openai/whisper
- VAD: Silero VAD by Silero Team, distributed at
  https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx

License texts are retained here and packaged in APK assets. SenseVoice license files
are retained for the initial comparison; SenseVoice weights are no longer packaged.
The model runs locally on CPU; audio is not uploaded. The app has no INTERNET permission.

SHA-256:

```
4CBE7B22FA9026B843B60A68640C747DE05BAFB1A11B57EDC0E66C232D9F33A9  small-encoder.int8.onnx
ACAD50B5C782696E91B55914CC5AB4F756F1532F76E22AA6FC615F39FB69A8EE  small-decoder.int8.onnx
B34B360DBB493E781E479794586D661700670D65564001F23024971D1F2FA126  small-tokens.txt
9E2449E1087496D8D4CABA907F23E0BD3F78D91FA552479BB9C23AC09CBB1FD6  silero_vad.onnx
A8D09D1D32F3EB8520482C6D3D43FAE674681C5B050385967C70A3C2C9BF0EE7  sherpa-onnx-2.0.1.aar
```

The ASR weights occupy about 375 MB before packaging. Recreate downloaded dependencies
with `android/tools/Download-SpeechModels.ps1`, then run `gradlew assembleDebug`.

Latency tuning (2026-11-16): retain Whisper-small int8 and Korean transcription, but
use 300 tail-padding feature frames instead of 1000. The sherpa configuration recommends
300 for multilingual models; these are computation frames, not a microphone wait.
On the board, two alternating runs on the same command WAV measured 1872/1895 ms
with 1000 frames and 975/1018 ms with 300. The latter recognized "바로 찍어줘" correctly.
A second microphone recording retained the same transcription and fell from
2402/2384 ms to 1459/1454 ms. This is a small regression set, not a broad accuracy score.

The segmenter measures ambient energy during its first 200 ms, preserving pre-roll
and allowing neural speech immediately. If speech occurs during calibration it is
not learned as background. This avoids treating this USB microphone's steady RMS
~0.008 noise as continuous speech against the old fixed 0.0035 threshold. Neural VAD
hangover is 100 ms and the final silence endpoint is 250 ms (previously 350 + 500 ms).
The 4-second maximum remains a bound for sustained input, not a mandatory wait.
Tests cover loud ambient, short quiet commands, immediate speech and endpoint timing.
Full VAD/Whisper/Team4 replay recognized the command, inferred capture and took a photo;
one measured run took 1559 ms for ASR and 1830 ms for intent inference. Live acoustics
and slower CPUs can differ. No timing UI was added.

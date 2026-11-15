$ErrorActionPreference = 'Stop'
$androidRoot = Split-Path -Parent $PSScriptRoot
$assetsPath = Join-Path $androidRoot 'app\src\main\assets\speech'
$libsPath = Join-Path $androidRoot 'app\libs'
$cachePath = Join-Path $androidRoot '.speech-download'
New-Item -ItemType Directory -Force -Path $assetsPath,$libsPath,$cachePath | Out-Null
function Assert-Hash([string]$Path, [string]$Expected) {
    if ((Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash -ne $Expected) { throw "Checksum mismatch: $Path" }
}
$aarPath = Join-Path $libsPath 'sherpa-onnx-2.0.1.aar'
Invoke-WebRequest 'https://github.com/k2-fsa/sherpa-onnx/releases/download/v2.0.1/sherpa-onnx-2.0.1.aar' -OutFile $aarPath
Assert-Hash $aarPath 'A8D09D1D32F3EB8520482C6D3D43FAE674681C5B050385967C70A3C2C9BF0EE7'
$whisperPath = Join-Path $assetsPath 'whisper-small'
New-Item -ItemType Directory -Force -Path $whisperPath | Out-Null
$revision = '8f3c18b358db4d1f2fc1eae49d75cd20989e4309'
$files = @{
    'small-encoder.int8.onnx' = '4CBE7B22FA9026B843B60A68640C747DE05BAFB1A11B57EDC0E66C232D9F33A9'
    'small-decoder.int8.onnx' = 'ACAD50B5C782696E91B55914CC5AB4F756F1532F76E22AA6FC615F39FB69A8EE'
    'small-tokens.txt' = 'B34B360DBB493E781E479794586D661700670D65564001F23024971D1F2FA126'
}
foreach ($name in $files.Keys) {
    $destination = Join-Path $whisperPath $name
    Invoke-WebRequest "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-small/resolve/$revision/$name" -OutFile $destination
    Assert-Hash $destination $files[$name]
}
$vadPath = Join-Path $assetsPath 'silero_vad.onnx'
Invoke-WebRequest 'https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx' -OutFile $vadPath
Assert-Hash $vadPath '9E2449E1087496D8D4CABA907F23E0BD3F78D91FA552479BB9C23AC09CBB1FD6'
Write-Output 'Offline speech dependencies verified.'

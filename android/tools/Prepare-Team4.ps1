param([string]$ArchivePath = '', [switch]$InstallToBoard, [string]$Device = '192.168.0.19:5555')
$ErrorActionPreference = 'Stop'
$androidRoot = Split-Path -Parent $PSScriptRoot
$cache = Join-Path $androidRoot '.team4-download'
New-Item -ItemType Directory -Force -Path $cache | Out-Null
if (!$ArchivePath) {
    $ArchivePath = Join-Path $cache 'team4.zip'
    if (!(Test-Path -LiteralPath $ArchivePath)) {
        Invoke-WebRequest 'https://github.com/byyourside-project/talkandcut/releases/download/team4-archive-20261116/team4-20261115T145656Z-1-001.zip' -OutFile $ArchivePath
    }
}
if ((Get-FileHash -LiteralPath $ArchivePath -Algorithm SHA256).Hash -ne '2E852561568D5E1AC7379FF5A9FE5E37EB8E25ED6EFBB1598AC1B2B04D529DE2') { throw 'Team4 ZIP checksum mismatch' }
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [IO.Compression.ZipFile]::OpenRead((Resolve-Path -LiteralPath $ArchivePath))
$assets = Join-Path $androidRoot 'app\src\main\assets\team4'
$native = Join-Path $androidRoot 'app\src\main\jniLibs\arm64-v8a'
New-Item -ItemType Directory -Force -Path $assets,$native | Out-Null
try {
    foreach ($name in @('libGenie.so','libQnnHtp.so','libQnnSystem.so','libQnnHtpV68Stub.so','libQnnHtpNetRunExtensions.so','libc++_shared.so')) {
        [IO.Compression.ZipFileExtensions]::ExtractToFile($zip.GetEntry("team4/bundle/$name"),(Join-Path $native $name),$true)
    }
    foreach ($name in @('tokenizer.json','libQnnHtpV68Skel.so','genie_config_npu.json','htp_backend_ext_config.json')) {
        [IO.Compression.ZipFileExtensions]::ExtractToFile($zip.GetEntry("team4/bundle/$name"),(Join-Path $assets $name),$true)
    }
    [IO.Compression.ZipFileExtensions]::ExtractToFile($zip.GetEntry('team4/bundle/llama32_hf_v68.bin'),(Join-Path $cache 'llama32_hf_v68.bin'),$true)
    $reader = [IO.StreamReader]::new($zip.GetEntry('team4/bundle/npu_manifest.json').Open())
    try { $manifest = $reader.ReadToEnd() | ConvertFrom-Json } finally { $reader.Dispose() }
    [IO.File]::WriteAllText((Join-Path $assets 'prompt-prefix.txt'),$manifest.model_profile.prompt_prefix,[Text.UTF8Encoding]::new($false))
} finally { $zip.Dispose() }
$model = Join-Path $cache 'llama32_hf_v68.bin'
if ((Get-FileHash -LiteralPath $model -Algorithm SHA256).Hash -ne '0614146A2D06BE3F3E193BBAB71749146F475933EF2EF457802937B3EA50DD7A') { throw 'Team4 context checksum mismatch' }
if ($InstallToBoard) {
    $adb = Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'
    & $adb -s $Device push $model /data/local/tmp/talkandcut-team4-model.bin
    if ($LASTEXITCODE -ne 0) { throw 'ADB model transfer failed' }
    & $adb -s $Device shell run-as com.byyourside.talkandcut mkdir -p files/team4
    & $adb -s $Device shell run-as com.byyourside.talkandcut cp /data/local/tmp/talkandcut-team4-model.bin files/team4/llama32_hf_v68.bin
    if ($LASTEXITCODE -ne 0) { throw 'App model installation failed; install the debug APK first' }
}
Write-Output 'Team4 model and runtime prepared.'

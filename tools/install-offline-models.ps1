param(
    [string]$Tv = "192.168.2.89:5555",
    [string]$Package = "app.orchard.tv.aerialvoice.mictest06",
    [string]$ModelRoot = (Join-Path $PSScriptRoot "models")
)
$ErrorActionPreference = "Stop"

function Invoke-AdbChecked([string[]]$AdbArguments, [string]$Description) {
    Write-Host $Description -ForegroundColor Cyan
    $old = $ErrorActionPreference
    try {
        $ErrorActionPreference = "Continue"
        $output = @(& adb @AdbArguments 2>&1)
        $code = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $old
    }
    foreach ($line in $output) { Write-Host $line }
    if ($code -ne 0) { throw "$Description : ADB error $code" }
}

if (-not (Get-Command adb -ErrorAction SilentlyContinue)) {
    throw "ADB не найден. Добавь Android Platform Tools в PATH."
}
$gigaPath = Join-Path $ModelRoot "gigaam-v3"
$voskZip = Join-Path $ModelRoot "vosk-model-small-ru-0.22.zip"
$manifest = Join-Path $ModelRoot "SHA256SUMS.txt"
if (-not (Test-Path -LiteralPath $manifest)) {
    throw "Нет контрольных сумм моделей: $manifest. Используй полный офлайн ZIP."
}

Write-Host "Проверяю целостность двух моделей на компьютере..." -ForegroundColor Cyan
$hashes = @{}
foreach ($line in [System.IO.File]::ReadAllLines($manifest)) {
    $textLine = $line.Trim()
    if (-not $textLine -or $textLine.StartsWith("#")) { continue }
    if ($textLine -notmatch '^([a-fA-F0-9]{64})\s+(.+)$') {
        throw "Повреждён список SHA256: $textLine"
    }
    $hashes[$Matches[2].Replace("/", "\")] = $Matches[1].ToLowerInvariant()
}
$required = @(
    "vosk-model-small-ru-0.22.zip",
    "gigaam-v3\encoder.int8.onnx",
    "gigaam-v3\decoder.onnx",
    "gigaam-v3\joiner.onnx",
    "gigaam-v3\tokens.txt"
)
foreach ($relative in $required) {
    $file = Join-Path $ModelRoot $relative
    if (-not (Test-Path -LiteralPath $file)) { throw "В офлайн-архиве отсутствует: $relative" }
    if (-not $hashes.ContainsKey($relative)) { throw "Нет контрольной суммы: $relative" }
    $actual = (Get-FileHash -LiteralPath $file -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($actual -ne $hashes[$relative]) { throw "SHA-256 не совпадает: $relative. Файл повреждён." }
    Write-Host "OK: $relative" -ForegroundColor Green
}

$remote = "/sdcard/Android/data/$Package/files/models"
Invoke-AdbChecked -AdbArguments @("connect", $Tv) -Description "Подключаюсь к телевизору"
Invoke-AdbChecked -AdbArguments @("shell", "mkdir", "-p", "$remote/gigaam-v3") -Description "Подготавливаю папку офлайн-моделей"
$gigaNames = @("encoder.int8.onnx", "decoder.onnx", "joiner.onnx", "tokens.txt")
foreach ($name in $gigaNames) {
    $source = Join-Path $gigaPath $name
    Invoke-AdbChecked -AdbArguments @("push", $source, "$remote/gigaam-v3/$name") -Description "Передаю GigaAM v3: $name"
}

$tmp = Join-Path $env:TEMP ("aerial-voice-vosk-" + [Guid]::NewGuid().ToString("N"))
try {
    Write-Host "Распаковываю Vosk локально на компьютере..." -ForegroundColor Cyan
    Expand-Archive -LiteralPath $voskZip -DestinationPath $tmp -Force
    $modelFolder = Join-Path $tmp "vosk-model-small-ru-0.22"
    foreach ($part in @("am\final.mdl","conf\mfcc.conf","graph\Gr.fst")) {
        if (-not (Test-Path -LiteralPath (Join-Path $modelFolder $part))) {
            throw "Неполная модель Vosk в ZIP. Отсутствует $part"
        }
    }
    Invoke-AdbChecked -AdbArguments @("shell", "mkdir", "-p", "$remote/vosk-model-small-ru-0.22") -Description "Подготавливаю Vosk"
    Invoke-AdbChecked -AdbArguments @("push", "$modelFolder\.", "$remote/vosk-model-small-ru-0.22/") -Description "Передаю офлайн-модель Vosk"
}
finally {
    if (Test-Path -LiteralPath $tmp) {
        Remove-Item -LiteralPath $tmp -Recurse -Force -ErrorAction SilentlyContinue
    }
}
foreach ($file in @(
  "$remote/gigaam-v3/encoder.int8.onnx",
  "$remote/gigaam-v3/decoder.onnx",
  "$remote/gigaam-v3/joiner.onnx",
  "$remote/gigaam-v3/tokens.txt",
  "$remote/vosk-model-small-ru-0.22/am/final.mdl",
  "$remote/vosk-model-small-ru-0.22/graph/Gr.fst"
)) {
    Invoke-AdbChecked -AdbArguments @("shell", "test", "-s", $file) -Description "Проверяю файл на телевизоре: $file"
}
Write-Host "Готово: Vosk и GigaAM v3 установлены локально. Телевизору интернет не нужен." -ForegroundColor Green

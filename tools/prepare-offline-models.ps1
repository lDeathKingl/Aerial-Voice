param([string]$Output=(Join-Path $PSScriptRoot "offline-models"))
$ErrorActionPreference="Stop"
$ProgressPreference="SilentlyContinue"
$root=[IO.Path]::GetFullPath($Output)
New-Item -ItemType Directory -Force (Join-Path $root "gigaam-v3") | Out-Null
function Download([string]$Url,[string]$Dest,[string]$Expected) {
  if (Test-Path -LiteralPath $Dest) {
    if ((Get-FileHash $Dest -Algorithm SHA256).Hash -ieq $Expected) {
      Write-Host "Cached $Dest"
      return
    }
  }
  $temp="$Dest.partial"
  Remove-Item $temp -Force -ErrorAction SilentlyContinue
  Invoke-WebRequest -Uri $Url -OutFile $temp -MaximumRedirection 10 -TimeoutSec 1800 -UseBasicParsing
  if ((Get-FileHash $temp -Algorithm SHA256).Hash -ine $Expected) {
    Remove-Item $temp -Force
    throw "Invalid SHA256: $Url"
  }
  Move-Item $temp $Dest -Force
}
$hf="https://huggingface.co/nocmt/GigaAM-v3-E2E-CTC-int8-ONNX/resolve/main"
Download "$hf/model.int8.onnx" (Join-Path $root "gigaam-v3/model.int8.onnx") "784d77aaa7a0899a247e72c71d667792087ee4dec985db1f57961767a5f6014f"
Download "$hf/tokens.txt" (Join-Path $root "gigaam-v3/tokens.txt") "142de7570b3de5b3035ce111a89c228e80e6085273731d944093ddf24fa539cd"
$expected=@("gigaam-v3/model.int8.onnx","gigaam-v3/tokens.txt")
$manifest=[ordered]@{format="AerialVoice-offline-model-cache-v1";generatedUtc=[DateTime]::UtcNow.ToString("o");files=@()}
foreach($relative in $expected) {
  $f=Get-Item -LiteralPath (Join-Path $root ($relative.Replace("/",[IO.Path]::DirectorySeparatorChar)))
  $manifest.files += [ordered]@{path=$relative;bytes=$f.Length;sha256=(Get-FileHash $f.FullName -Algorithm SHA256).Hash.ToLowerInvariant()}
}
$manifest | ConvertTo-Json -Depth 6 | Set-Content -Encoding UTF8 (Join-Path $root "manifest.json")
Write-Host "Offline GigaAM ready: $root"

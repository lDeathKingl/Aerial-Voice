#!/usr/bin/env bash
# CI-only: download verified open models, then package them alongside the APK.
# The TV installer never downloads models or accesses Hugging Face.
set -euo pipefail

ASSETS="${1:-dist/models}"
mkdir -p "$ASSETS/gigaam-v3"
MODEL_REPO="https://huggingface.co/csukuangfj/sherpa-onnx-nemo-transducer-punct-giga-am-v3-russian-2025-12-16/resolve/main"

fetch() {
  local remote="$1" dest="$2"
  echo "Downloading model artifact: $(basename "$dest")"
  curl --fail --location --retry 6 --retry-all-errors --retry-delay 5 \
    --connect-timeout 35 --max-time 1500 \
    -H "User-Agent: AerialVoiceOfflineBuild/0.7.0" \
    --output "$dest" "$remote"
  test -s "$dest"
}

fetch "$MODEL_REPO/encoder.int8.onnx" "$ASSETS/gigaam-v3/encoder.int8.onnx"
fetch "$MODEL_REPO/decoder.onnx" "$ASSETS/gigaam-v3/decoder.onnx"
fetch "$MODEL_REPO/joiner.onnx" "$ASSETS/gigaam-v3/joiner.onnx"
fetch "$MODEL_REPO/tokens.txt" "$ASSETS/gigaam-v3/tokens.txt"
fetch "https://alphacephei.com/vosk/models/vosk-model-small-ru-0.22.zip" "$ASSETS/vosk-model-small-ru-0.22.zip"

echo "Verifying upstream SHA-256 for the large GigaAM v3 components"
(
  cd "$ASSETS"
  cat > /tmp/aerial-gigaam-known-sha256.txt <<'EOF'
369f35a71bf288d3b8e0391fabd8dba5f2314088d440bca474056b7b4b6e66bf  gigaam-v3/encoder.int8.onnx
38fc7475443ea2a26f63211ca350f73ac50fff824ab7a3876ee2bd610c53bbc4  gigaam-v3/decoder.onnx
602ff7017a93311aad34df1437c8d7f49911353c13d6eae7a6ee7b041339465c  gigaam-v3/joiner.onnx
EOF
  sha256sum --check --status /tmp/aerial-gigaam-known-sha256.txt
  test "$(wc -c < gigaam-v3/tokens.txt)" -gt 8000
  test "$(wc -c < vosk-model-small-ru-0.22.zip)" -gt 20000000
  unzip -Z1 vosk-model-small-ru-0.22.zip > /tmp/aerial-vosk-files.txt
  grep -Fq 'vosk-model-small-ru-0.22/am/final.mdl' /tmp/aerial-vosk-files.txt
  grep -Fq 'vosk-model-small-ru-0.22/graph/Gr.fst' /tmp/aerial-vosk-files.txt
  sha256sum vosk-model-small-ru-0.22.zip gigaam-v3/encoder.int8.onnx gigaam-v3/decoder.onnx gigaam-v3/joiner.onnx gigaam-v3/tokens.txt > SHA256SUMS.txt
  sha256sum --check SHA256SUMS.txt
)
cat > "$ASSETS/OFFLINE_MODELS_LICENSES.txt" <<'EOF'
Aerial Voice 0.7.0 offline Russian speech model distribution.
GigaAM v3 E2E RNNT (Sber/Salute Developers, MIT)
  https://github.com/salute-developers/GigaAM
  https://huggingface.co/csukuangfj/sherpa-onnx-nemo-transducer-punct-giga-am-v3-russian-2025-12-16
  Copyright attribution and original license: see upstream GigaAM LICENSE.
sherpa-onnx runtime (Apache-2.0)
  https://github.com/k2-fsa/sherpa-onnx
Vosk Small Russian model
  https://alphacephei.com/vosk/models/vosk-model-small-ru-0.22.zip
  https://alphacephei.com/vosk/models
The full ZIP includes the original model files and no cloud ASR service.
EOF
echo "Both offline models downloaded and verified; no TV download needed."

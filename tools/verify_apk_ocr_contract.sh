#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 2 ]]; then
  echo "usage: verify_apk_ocr_contract.sh APK DEXDUMP" >&2
  exit 2
fi

apk=$1
dexdump=$2
work=$(mktemp -d /tmp/fox-apk-contract-XXXXXX)
trap 'rm -rf "$work"' EXIT

unzip -q "$apk" 'classes*.dex' -d "$work"
for dex in "$work"/classes*.dex; do
  "$dexdump" -d -l plain "$dex" 2>/dev/null >> "$work/dump.txt"
done

stable='(Landroid/graphics/Bitmap;Ljp/chisana/foxkifuscanner/BoardAnalyzer$Region;Ljava/util/List;Ljava/util/List;)Ljp/chisana/foxkifuscanner/GameMetadata;'
recognizer='(Landroid/graphics/Bitmap;Ljp/chisana/foxkifuscanner/BoardAnalyzer$Region;)Ljava/util/List;'
bounded_recognizer='(Landroid/graphics/Bitmap;Ljp/chisana/foxkifuscanner/BoardAnalyzer$Region;J)Ljava/util/List;'
capture_after='(JJ)Ljp/chisana/foxkifuscanner/CaptureService$CapturedFrame;'
latest_frame='()Ljp/chisana/foxkifuscanner/CaptureService$CapturedFrame;'
obsolete='Ljp/chisana/foxkifuscanner/HeaderTextRecognizer$Result;'

has_method() {
  local descriptor=$1
  local method=$2
  local signature=$3
  awk -v descriptor="$descriptor" -v method="$method" -v signature="$signature" '
    BEGIN { expected_name = "name          : \047" method "\047" }
    /Class descriptor  :/ {
      in_class = index($0, descriptor) > 0
      saw_method = 0
    }
    in_class && /name          :/ {
      saw_method = index($0, expected_name) > 0
      next
    }
    in_class && saw_method && /type          :/ && index($0, signature) > 0 {
      found = 1
      exit
    }
    END { exit found ? 0 : 1 }
  ' "$work/dump.txt"
}

has_method 'Ljp/chisana/foxkifuscanner/MetadataReader;' read "$stable" || {
  echo "stable MetadataReader List ABI is missing from APK" >&2
  exit 1
}
has_method 'Ljp/chisana/foxkifuscanner/HeaderTextRecognizer;' recognize "$recognizer" || {
  echo "stable HeaderTextRecognizer List ABI is missing from APK" >&2
  exit 1
}
has_method 'Ljp/chisana/foxkifuscanner/HeaderTextRecognizer;' recognize "$bounded_recognizer" || {
  echo "bounded HeaderTextRecognizer API is missing from APK" >&2
  exit 1
}
has_method 'Ljp/chisana/foxkifuscanner/CaptureService;' captureAfter "$capture_after" || {
  echo "sequence-aware CaptureService API is missing from APK" >&2
  exit 1
}
has_method 'Ljp/chisana/foxkifuscanner/CaptureService;' latestFrame "$latest_frame" || {
  echo "continuous CaptureService cache API is missing from APK" >&2
  exit 1
}
if grep -Fq "$obsolete" "$work/dump.txt"; then
  echo "obsolete HeaderTextRecognizer.Result ABI remains in APK" >&2
  exit 1
fi
grep -Fq "Class descriptor  : 'Ljp/chisana/foxkifuscanner/BoardFrameFingerprint;'" \
  "$work/dump.txt" || {
  echo "integrated speed fingerprint class is missing from APK" >&2
  exit 1
}
grep -Fq "Class descriptor  : 'Ljp/chisana/foxkifuscanner/ReaderAccessibilityService\$LightFrame;'" \
  "$work/dump.txt" || {
  echo "integrated lightweight frame class is missing from APK" >&2
  exit 1
}
grep -Fq "Class descriptor  : 'Ljp/chisana/foxkifuscanner/GoBoardRules;'" \
  "$work/dump.txt" || {
  echo "exact Go transition validator is missing from APK" >&2
  exit 1
}
grep -Fq "Class descriptor  : 'Ljp/chisana/foxkifuscanner/ScanMetrics;'" \
  "$work/dump.txt" || {
  echo "scan metrics class is missing from APK" >&2
  exit 1
}
for class_name in ShsFramePixels SliderIndexRange SliderIndexTextParser SliderSeekPlan SliderStepValidator; do
  grep -Fq "Class descriptor  : 'Ljp/chisana/foxkifuscanner/${class_name};'" \
    "$work/dump.txt" || {
    echo "SHS class ${class_name} is missing from APK" >&2
    exit 1
  }
done

count=$(grep -Fc "Class descriptor  : 'Ljp/chisana/foxkifuscanner/MetadataReader;'" "$work/dump.txt")
[[ "$count" -eq 1 ]] || {
  echo "MetadataReader class count is $count, expected 1" >&2
  exit 1
}

count=$(grep -Fc "Class descriptor  : 'Ljp/chisana/foxkifuscanner/HeaderTextRecognizer;'" \
  "$work/dump.txt")
[[ "$count" -eq 1 ]] || {
  echo "HeaderTextRecognizer class count is $count, expected 1" >&2
  exit 1
}

count=$(grep -Fc "Class descriptor  : 'Ljp/chisana/foxkifuscanner/BoardFrameFingerprint;'" \
  "$work/dump.txt")
[[ "$count" -eq 1 ]] || {
  echo "BoardFrameFingerprint class count is $count, expected 1" >&2
  exit 1
}

echo "Integrated APK OCR/speed contract verified"

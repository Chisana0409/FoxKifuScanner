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

stable='Ljp/chisana/foxkifuscanner/MetadataReader;.read:(Landroid/graphics/Bitmap;Ljp/chisana/foxkifuscanner/BoardAnalyzer$Region;Ljava/util/List;Ljava/util/List;)Ljp/chisana/foxkifuscanner/GameMetadata;'
recognizer='Ljp/chisana/foxkifuscanner/HeaderTextRecognizer;.recognize:(Landroid/graphics/Bitmap;Ljp/chisana/foxkifuscanner/BoardAnalyzer$Region;)Ljava/util/List;'
obsolete='Ljp/chisana/foxkifuscanner/HeaderTextRecognizer$Result;'

grep -Fq "$stable" "$work/dump.txt" || {
  echo "stable MetadataReader List ABI is missing from APK" >&2
  exit 1
}
grep -Fq "$recognizer" "$work/dump.txt" || {
  echo "stable HeaderTextRecognizer List ABI is missing from APK" >&2
  exit 1
}
if grep -Fq "$obsolete" "$work/dump.txt"; then
  echo "obsolete HeaderTextRecognizer.Result ABI remains in APK" >&2
  exit 1
fi
if grep -Eq 'BoardFrameFingerprint|ReaderAccessibilityService\$LightFrame' "$work/dump.txt"; then
  echo "speed-branch classes were mixed into the OCR APK" >&2
  exit 1
fi

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

echo "APK OCR contract verified"

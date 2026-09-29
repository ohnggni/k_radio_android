#!/bin/bash
# KRadio 배포 스크립트
# 사용법: ./scripts/release.sh 1.1.0
# 전제: RELEASE_NOTES.md에 "## <버전> (Unreleased)" 항목이 있어야 함
set -euo pipefail
trap 'echo "   ❌ 중단됨 (release.sh $LINENO번째 줄)"' ERR

VER="${1:?버전을 입력하세요. 예: ./scripts/release.sh 1.1.0}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
GRADLE_FILE="app/build.gradle.kts"
OUT_DIR="$HOME/KRadio-release"
APK="$OUT_DIR/KRadio-$VER.apk"
DRIVE="mygd:/MyGD/KRadio_releases/"

echo "▶ 0. 사전 확인"
if ! git diff --quiet || ! git diff --cached --quiet; then
  echo "   커밋 안 된 변경이 있어요. 먼저 커밋하세요."; exit 1
fi
git pull -q --ff-only
grep -q "^## $VER (Unreleased)$" RELEASE_NOTES.md || {
  echo "   RELEASE_NOTES.md에 '## $VER (Unreleased)' 항목이 없어요."; exit 1; }

echo "▶ 1. 버전 올리기"
OLD_CODE=$(grep -E '^[[:space:]]*versionCode[[:space:]]*=' "$GRADLE_FILE" | grep -oE '[0-9]+' | head -1)
NEW_CODE=$((OLD_CODE + 1))
sed -i '' -E "s/^([[:space:]]*versionCode[[:space:]]*=[[:space:]]*)[0-9]+/\1$NEW_CODE/" "$GRADLE_FILE"
sed -i '' -E "s/^([[:space:]]*versionName[[:space:]]*=[[:space:]]*)\"[^\"]*\"/\1\"$VER\"/" "$GRADLE_FILE"
sed -i '' "s/^## $VER (Unreleased)$/## $VER ($(date +%F))/" RELEASE_NOTES.md
echo "   versionCode $OLD_CODE → $NEW_CODE, versionName $VER"

echo "▶ 2. 정식 빌드 + 검증"
./gradlew -q assembleRelease
mkdir -p "$OUT_DIR"
cp app/build/outputs/apk/release/app-release.apk "$APK"
BT=$(ls -d "$HOME/Library/Android/sdk/build-tools/"* | tail -1)
BADGE=$("$BT/aapt2" dump badging "$APK" 2>/dev/null | head -1 || true)
echo "$BADGE" | grep -q "versionCode='$NEW_CODE' versionName='$VER'" || {
  echo "   APK 버전 불일치: $BADGE"; exit 1; }
"$BT/apksigner" verify "$APK" 2>/dev/null || { echo "   서명 확인 실패"; exit 1; }
echo "   $APK ($(du -h "$APK" | cut -f1)) 버전·서명 확인"

echo "▶ 3. 내 폰에 설치"
# USB·무선 상관없이 연결된 기기 찾기 (여러 대면 내 폰 우선)
DEVICES=$(adb devices | awk 'NR>1 && $2=="device" {print $1}')
DEVICE=$(echo "$DEVICES" | grep -m1 "R5KL700JYQN" || echo "$DEVICES" | head -1)
if [ -n "$DEVICE" ]; then
  echo "   기기: $DEVICE"
  adb -s "$DEVICE" install -r "$APK"
  read -r -p "   폰에서 동작 확인 후 Enter (중단하려면 Ctrl+C) " _
else
  echo "   폰이 연결되지 않아 건너뜀"
fi

echo "▶ 4. 구글 드라이브 업로드"
rclone copy "$APK" "$DRIVE"
rclone ls "$DRIVE" | grep -q "KRadio-$VER.apk" || { echo "   업로드 확인 실패"; exit 1; }
echo "   업로드 확인"

echo "▶ 5. 앱 내 업데이트 알림 켜기 (channels.json)"
python3 - "$NEW_CODE" "$VER" << 'EOF'
import re, sys
code, ver = sys.argv[1], sys.argv[2]
p = "channels.json"
s = open(p, encoding="utf-8").read()
s = re.sub(r'"latestVersionCode":\s*\d+', f'"latestVersionCode": {code}', s)
s = re.sub(r'"latestVersionName":\s*"[^"]*"', f'"latestVersionName": "{ver}"', s)
open(p, "w", encoding="utf-8").write(s)
EOF
python3 -m json.tool channels.json > /dev/null || { echo "   channels.json 문법 오류"; exit 1; }

echo "▶ 6. 커밋·태그·푸시"
git add "$GRADLE_FILE" RELEASE_NOTES.md channels.json
git commit -q -m "Release $VER"
git tag "v$VER"
git push -q
git push -q origin "v$VER"

echo "✅ $VER 배포 완료 → $APK"

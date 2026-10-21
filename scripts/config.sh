#!/bin/bash
# 채널 설정 편집 도우미
#   원본(평문)은 비공개 저장소 kradio-private 의 channels.json. 공개 저장소엔 암호문과 껍데기만 올라감
#
#   ./scripts/config.sh open              옆 폴더(kradio-private)를 최신으로 맞추고 위치 표시
#   ./scripts/config.sh push "변경 내용"   비공개 저장소 커밋·푸시 → 암호화해서 공개 d 브랜치에 올림
#                                         → 옛 버전 앱용 껍데기(config 브랜치, main 루트) 갱신
#   ./scripts/config.sh publish           (release.sh가 씀) 암호문·껍데기만 올리고 main 루트는 커밋 안 함
set -euo pipefail
trap 'echo "   ❌ 중단됨 (config.sh ${LINENO}번째 줄)"' ERR

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PRIV="$(dirname "$ROOT")/kradio-private"

ensure_priv() {
  if [ ! -e "$PRIV/.git" ]; then
    gh repo clone ohnggni/kradio-private "$PRIV" -- -q
  fi
  git -C "$PRIV" pull -q --ff-only
  python3 -c "import cryptography" 2>/dev/null || {
    echo "   파이썬 cryptography 패키지가 필요해요: pip3 install --user --break-system-packages cryptography"; exit 1; }
}

secret() { grep "^$1=" "$PRIV/secrets.properties" | cut -d= -f2- | tr -d '[:space:]'; }

# 한 파일짜리 브랜치를 히스토리 없이 덮어씀 (같은 내용이면 건너뜀)
put_branch() {  # 브랜치 파일이름 로컬파일
  local blob tree commit
  blob=$(git -C "$ROOT" hash-object -w "$3")
  if [ "$(git -C "$ROOT" rev-parse -q --verify "origin/$1:$2" 2>/dev/null || true)" = "$blob" ] \
     && [ "$(git -C "$ROOT" rev-list --count "origin/$1")" = "1" ]; then
    echo "   $1 브랜치: 변경 없음"; return
  fi
  tree=$(printf '100644 blob %s\t%s\n' "$blob" "$2" | git -C "$ROOT" mktree)
  commit=$(git -C "$ROOT" commit-tree "$tree" -m "data")
  git -C "$ROOT" push -q -f origin "$commit:refs/heads/$1"
  echo "   $1 브랜치 갱신"
}

publish() {
  python3 -m json.tool "$PRIV/channels.json" > /dev/null || { echo "   channels.json 문법 오류"; exit 1; }
  git -C "$ROOT" fetch -q origin d config
  # 1) 암호문: 공개된 것을 풀어서 지금 원본과 같으면 건너뜀 (암호문은 매번 달라지므로 평문으로 비교)
  local file; file=$(secret CONFIG_FILE)
  if git -C "$ROOT" show "origin/d:$file" > "$PRIV/.remote.bin" 2>/dev/null \
     && python3 "$PRIV/kcrypt.py" dec "$PRIV/.remote.bin" 2>/dev/null | cmp -s - "$PRIV/channels.json"; then
    echo "   d 브랜치(암호문): 변경 없음"
  else
    python3 "$PRIV/kcrypt.py" enc > /dev/null
    put_branch d "$file" "$PRIV/$file"
  fi
  rm -f "$PRIV/.remote.bin"
  # 2) 옛 버전 앱용 껍데기
  python3 "$ROOT/scripts/stub.py" "$PRIV/channels.json" "$PRIV/.stub.json"
  put_branch config channels.json "$PRIV/.stub.json"
  cp "$PRIV/.stub.json" "$ROOT/channels.json"
  rm -f "$PRIV/.stub.json"
}

case "${1:-}" in
  open)
    ensure_priv
    echo "채널 설정 파일: $PRIV/channels.json"
    echo "편집이 끝나면: ./scripts/config.sh push \"변경 내용\""
    ;;
  push)
    MSG="${2:?변경 내용을 입력하세요. 예: ./scripts/config.sh push \"채널 주소 변경\"}"
    ensure_priv
    python3 -m json.tool "$PRIV/channels.json" > /dev/null || { echo "   channels.json 문법 오류"; exit 1; }
    if [ -n "$(git -C "$PRIV" status --porcelain -- channels.json)" ]; then
      git -C "$PRIV" add channels.json
      git -C "$PRIV" commit -qm "$MSG"
    fi
    git -C "$PRIV" push -q
    echo "   비공개 저장소 푸시"
    publish
    if ! git -C "$ROOT" diff --quiet -- channels.json; then
      git -C "$ROOT" commit -qm "Update legacy stub" -- channels.json
      git -C "$ROOT" push -q
      echo "   main 루트 껍데기 갱신"
    fi
    echo "✅ 완료"
    ;;
  publish)
    ensure_priv
    publish
    ;;
  *)
    echo "사용법: ./scripts/config.sh open | push \"변경 내용\""; exit 1 ;;
esac

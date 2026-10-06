#!/bin/bash
# 채널 설정(config 브랜치) 편집 도우미
#   ./scripts/config.sh open             옆 폴더(KRadio-config)를 최신으로 맞추고 위치 표시
#   ./scripts/config.sh push "변경 내용"  config 브랜치 커밋·푸시 + main 루트 channels.json(옛 버전용 복사본) 갱신·푸시
set -euo pipefail
trap 'echo "   ❌ 중단됨 (config.sh ${LINENO}번째 줄)"' ERR

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
CFG="$(dirname "$ROOT")/KRadio-config"

ensure_cfg() {
  if [ ! -e "$CFG/.git" ]; then
    git -C "$ROOT" worktree prune
    git -C "$ROOT" fetch -q origin config
    git -C "$ROOT" worktree add -q "$CFG" config 2>/dev/null \
      || git -C "$ROOT" worktree add -q -B config "$CFG" origin/config
  fi
  git -C "$CFG" pull -q --ff-only
}

case "${1:-}" in
  open)
    ensure_cfg
    echo "채널 설정 폴더: $CFG"
    echo "편집이 끝나면: ./scripts/config.sh push \"변경 내용\""
    ;;
  push)
    MSG="${2:?변경 내용을 입력하세요. 예: ./scripts/config.sh push \"채널 주소 변경\"}"
    python3 -m json.tool "$CFG/channels.json" > /dev/null || { echo "   channels.json 문법 오류"; exit 1; }
    if [ -n "$(git -C "$CFG" status --porcelain)" ]; then
      git -C "$CFG" add -A
      git -C "$CFG" commit -qm "$MSG"
    fi
    git -C "$CFG" push -q
    echo "   config 브랜치 푸시"
    # 옛 버전 앱은 main 루트를 봄 → 같은 내용으로 복사 (main의 다른 수정은 건드리지 않음)
    cp "$CFG/channels.json" "$ROOT/channels.json"
    if ! git -C "$ROOT" diff --quiet -- channels.json; then
      git -C "$ROOT" commit -qm "Mirror config: $MSG" -- channels.json
      git -C "$ROOT" push -q
      echo "   main 루트 복사본 갱신"
    fi
    echo "✅ 완료"
    ;;
  *)
    echo "사용법: ./scripts/config.sh open | push \"변경 내용\""; exit 1 ;;
esac

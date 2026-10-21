#!/usr/bin/env python3
"""옛 버전 앱(1.10.1 이하)용 껍데기 설정 만들기

사용법: python3 scripts/stub.py <비공개 channels.json> <출력 파일>

옛 앱은 공개 저장소의 평문 channels.json(config 브랜치, main 루트)을 읽음.
이제 거기엔 채널 정보 없이 최신 버전 정보만 둠 → 옛 앱은 채널 목록이 비고 업데이트 안내만 보임.
비공개 설정에 "legacyNameSuffix"가 있으면 latestVersionName 뒤에 붙임
→ "새 버전 1.11.0(업데이트해야 재생 가능)이 나왔어요"
"""
import json
import sys

src, dst = sys.argv[1], sys.argv[2]
cfg = json.load(open(src, encoding="utf-8"))
name = (cfg.get("latestVersionName") or "") + (cfg.get("legacyNameSuffix") or "").strip()
stub = {
    "version": cfg.get("version", 2),
    "latestVersionCode": cfg.get("latestVersionCode"),
    "latestVersionName": name,
}
if cfg.get("updateUrl"):
    stub["updateUrl"] = cfg["updateUrl"]
stub["channels"] = []
with open(dst, "w", encoding="utf-8") as f:
    json.dump(stub, f, ensure_ascii=False, indent=2)
    f.write("\n")

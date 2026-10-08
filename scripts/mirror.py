#!/usr/bin/env python3
"""config 브랜치 channels.json → main 루트 복사본 (옛 버전 앱 1.7.2 이하가 읽는 파일)

사용법: python3 scripts/mirror.py <config의 channels.json> <main 루트 channels.json>

config에 "legacyNameSuffix"가 있으면 복사본의 latestVersionName 뒤에만 붙임.
→ 옛 버전 앱 업데이트 알림: "새 버전 1.9.0(10월 말까지 꼭 업데이트)이 나왔어요"
   새 버전 앱은 config 브랜치를 읽으므로 영향 없음.
"""
import json, re, sys

src, dst = sys.argv[1], sys.argv[2]
s = open(src, encoding="utf-8").read()
suffix = (json.loads(s).get("legacyNameSuffix") or "").strip()
if suffix:
    s = re.sub(r'("latestVersionName":\s*")([^"]*)(")',
               lambda m: m.group(1) + m.group(2) + suffix + m.group(3), s, count=1)
json.loads(s)  # 문법 확인
open(dst, "w", encoding="utf-8").write(s)
print(f"   main 복사본: latestVersionName → {json.loads(s).get('latestVersionName')}")

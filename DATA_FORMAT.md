# KRadio 데이터 형식 안내

KRadio 앱은 두 가지 데이터를 인터넷에서 받아서 동작합니다.

| 데이터 | 형식 | 역할 |
|---|---|---|
| 채널 설정 | `channels.json` (JSON) | 채널 목록, 스트림 주소를 얻는 방법, 로고, 편성표 연결 정보 |
| 편성표 (EPG) | `xmltv.xml` (XMLTV) | 채널별 현재·다음 프로그램 정보 |

앱은 기본적으로 KRadio가 제공하는 데이터를 사용합니다. 기본 제공 서버에 문제가 생기면, 이 문서의 형식대로 직접 파일을 만들어 아무 웹 주소에나 올린 뒤 **앱 설정 → 데이터 출처**에서 그 주소를 지정하면 계속 사용할 수 있습니다.

---

## 1. 채널 설정 파일 (`channels.json`)

### 1-1. 전체 구조

```json
{
  "version": 2,
  "epgUrl": "https://example.com/xmltv.xml",
  "logoBase": "https://example.com/logos/",
  "headerSets": { "...": { "...": "..." } },
  "channels": [ { "...": "..." } ]
}
```

| 필드 | 필수 | 설명 |
|---|---|---|
| `version` | 선택 | 파일 형식 버전. 현재 `2` |
| `epgUrl` | 선택 | 편성표(XMLTV) 주소. 앱 설정에서 편성표 주소를 따로 지정하지 않으면 이 주소를 사용 |
| `logoBase` | 선택 | 로고 이미지의 공통 앞부분 주소. 채널의 `logo`가 `http`로 시작하지 않으면 이 값 뒤에 붙여서 사용 |
| `headerSets` | 선택 | 방송사별 HTTP 요청 헤더 묶음. 채널에서 이름으로 참조 |
| `channels` | **필수** | 채널 목록. **배열 순서가 앱의 기본 채널 순서**(이전/다음 버튼 순서) |

### 1-2. 채널 항목 공통 필드

| 필드 | 필수 | 설명 |
|---|---|---|
| `id` | **필수** | 채널 고유 ID. 영문·숫자·밑줄 권장 (예: `kbs_cool`) |
| `name` | **필수** | 화면에 표시할 채널 이름 |
| `group` | 선택 | 묶음 이름 (예: `KBS`, `MBC`, `기타`) |
| `type` | **필수** | `direct`(고정 주소) 또는 `api`(방송사 API로 주소 얻기) |
| `logo` | 선택 | 로고 이미지. 파일 이름(→ `logoBase` 뒤에 붙음) 또는 전체 주소 |
| `epg` | 선택 | 편성표(XMLTV)의 채널 ID. 없으면 "편성 정보 없음"으로 표시 |
| `headers` | 선택 | `headerSets`에 정의한 헤더 묶음 이름 |

> **주의:** `id`를 바꾸면 사용자가 앱에서 정한 순서·숨김·수정 내용과의 연결이 끊깁니다. 한 번 정한 `id`는 가급적 유지하세요.

### 1-3. `direct` 채널 — 고정 스트림 주소

주소가 바뀌지 않는 채널에 사용합니다.

| 필드 | 필수 | 설명 |
|---|---|---|
| `url` | **필수** | 스트림 주소. HLS(`.m3u8`), MP3, AAC 지원 |

```json
{
  "id": "ytn", "name": "YTN 라디오", "group": "기타", "type": "direct",
  "epg": "2074615.naver", "logo": "ytn.png",
  "url": "https://radiolive.ytn.co.kr/radio/_definst_/20211118_fmlive/playlist.m3u8"
}
```

### 1-4. `api` 채널 — 방송사 API로 주소 얻기

KBS·MBC·SBS처럼 재생할 때마다 토큰이 붙은 새 주소를 발급하는 채널에 사용합니다. 앱은 재생 직전에 `api` 주소를 호출하고, 응답에서 스트림 주소를 추출합니다.

| 필드 | 필수 | 설명 |
|---|---|---|
| `api` | **필수** | 호출할 주소 |
| `headers` | 대부분 필요 | 방송사가 요구하는 헤더(Referer, User-Agent 등) 묶음 이름 |
| `extract` | **필수** | 응답에서 주소를 뽑는 방법. `json` 또는 `regex` 중 하나 |

**`extract.json` — JSON 경로 방식**

`키.키.키` 형태로 따라가며, 배열에서는 `[키=값]`으로 조건에 맞는 첫 항목을 고릅니다.

```json
"extract": { "json": "channel_item[media_type=radio].service_url" }
```

위 예시는 응답의 `channel_item` 배열에서 `media_type`이 `radio`인 항목의 `service_url` 값을 가져옵니다. (KBS는 같은 응답에 영상 스트림도 들어 있어서 조건 지정이 필요합니다.)

**`extract.regex` — 정규식 방식**

응답 텍스트 전체에서 정규식으로 찾습니다. 괄호로 묶은 첫 번째 그룹이 있으면 그 값을, 없으면 일치한 전체를 사용합니다.

```json
"extract": { "regex": "(https?://[^\"']+\\.m3u8[^\"']*)" }
```

> 응답 안의 `\/`(JSON 이스케이프된 슬래시)는 추출 전에 자동으로 `/`로 바뀝니다.

**`headerSets` — 방송사 헤더**

여기 정의한 헤더는 API 호출뿐 아니라 **스트림 재생 요청에도 함께 붙습니다.**

```json
"headerSets": {
  "kbs": {
    "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/106.0.0.0 Safari/537.36",
    "Referer": "https://onair.kbs.co.kr/"
  }
}
```

**API 채널 예시 (KBS 쿨FM)**

```json
{
  "id": "kbs_cool", "name": "KBS 쿨FM", "group": "KBS", "type": "api", "headers": "kbs",
  "epg": "815457.naver", "logo": "kbs_cool.png",
  "api": "https://cfpwwwapi.kbs.co.kr/api/v1/landing/live/channel_code/25",
  "extract": { "json": "channel_item[media_type=radio].service_url" }
}
```

### 1-5. 최소 예제

채널 하나짜리 가장 간단한 파일입니다. 이것만으로도 앱이 동작합니다.

```json
{
  "version": 2,
  "channels": [
    {
      "id": "ytn", "name": "YTN 라디오", "type": "direct",
      "url": "https://radiolive.ytn.co.kr/radio/_definst_/20211118_fmlive/playlist.m3u8"
    }
  ]
}
```

---

## 2. 편성표 파일 (XMLTV) — epg2xml로 만들기

### 2-1. 앱이 읽는 부분

XMLTV 표준 중 앱이 실제로 사용하는 요소는 아래뿐입니다.

```xml
<tv>
  <programme start="20260924070000 +0900" stop="20260924090000 +0900" channel="815463.naver">
    <title lang="ko">굿모닝FM 테이입니다</title>
    <sub-title lang="ko">1부</sub-title>   <!-- 선택 -->
  </programme>
</tv>
```

| 요소 | 설명 |
|---|---|
| `programme@channel` | 채널 ID. **채널 설정의 `epg` 값과 정확히 같아야 연결됨** |
| `programme@start`, `@stop` | `yyyyMMddHHmmss +0900` 형식 (시간대 포함) |
| `title` | 프로그램 이름 (필수) |
| `sub-title` | 부제 (선택, 예: `1부`, `2부`) |

`<channel>` 요소, `<desc>`, `<rating>` 등은 있어도 무시됩니다. 앱은 5분 미만의 짧은 편성(날씨, 캠페인 등)을 건너뛰고 앞뒤의 본 프로그램을 표시합니다.

### 2-2. 편성 정보 출처: 네이버 (NAVER)

KRadio 기본 편성표는 오픈소스 도구 **[epg2xml](https://github.com/epg2xml/epg2xml)** 로 **네이버(NAVER) 편성표**를 가져와 만듭니다.

- epg2xml은 KT·LG·SK·DAUM·NAVER·TVING·SPOTV·WAVVE 등 여러 출처를 지원하지만, **라디오 채널 편성이 가장 잘 갖춰진 네이버만 사용**합니다.
- 채널 ID 형식은 `{네이버 ServiceId}.naver` 입니다. (예: 네이버 ServiceId `815457` → `815457.naver`)
- 채널 설정의 `epg` 값에는 이 형식의 ID를 넣습니다.
- 네이버 ServiceId는 네이버 쪽 사정으로 바뀔 수 있습니다. 특정 채널의 편성이 갑자기 안 나오면 ServiceId부터 확인하세요.
- 편성표는 네이버 페이지에서 수집한 정보이므로 개인·비상업 용도로만 사용하세요.

### 2-3. epg2xml 설치와 실행

Python 3가 설치된 리눅스/맥에서:

```bash
pip3 install git+https://github.com/epg2xml/epg2xml.git lxml

# epg2xml.json 이 있는 폴더에서 실행
python3 -m epg2xml run --xmlfile=xmltv.xml
```

처음 실행하면 설정 파일(`epg2xml.json`)이 없을 경우 기본 설정이 만들어집니다. 아래 KRadio 설정으로 교체해서 사용하세요.

### 2-4. KRadio용 `epg2xml.json`

네이버만 켜고, 라디오 채널 22개를 등록한 설정입니다. 사용하지 않는 출처(KT, LG, SK, DAUM, TVING, SPOTV, WAVVE)는 `"ENABLED": false`로 두면 됩니다.

```json
{
  "GLOBAL": {
    "ENABLED": true,
    "FETCH_LIMIT": 2,
    "ID_FORMAT": "{ServiceId}.{Source.lower()}",
    "ADD_REBROADCAST_TO_TITLE": false,
    "ADD_EPNUM_TO_TITLE": true,
    "ADD_DESCRIPTION": true,
    "ADD_XMLTV_NS": false,
    "GET_MORE_DETAILS": false,
    "ADD_CHANNEL_ICON": true,
    "HTTP_PROXY": null
  },
  "NAVER": {
    "ENABLED": true,
    "MY_CHANNELS": [
      { "Name": "KBS1FM",        "ServiceId": "815454",  "Category": "라디오" },
      { "Name": "KBS2FM",        "ServiceId": "815457",  "Category": "라디오" },
      { "Name": "MBCFM4U",       "ServiceId": "815463",  "Category": "라디오" },
      { "Name": "MBC표준FM",     "ServiceId": "815464",  "Category": "라디오" },
      { "Name": "SBS러브FM",     "ServiceId": "815465",  "Category": "라디오" },
      { "Name": "SBS파워FM",     "ServiceId": "815467",  "Category": "라디오" },
      { "Name": "KBS1R",         "ServiceId": "815455",  "Category": "라디오" },
      { "Name": "KBS2R",         "ServiceId": "815458",  "Category": "라디오" },
      { "Name": "KBS3R",         "ServiceId": "815460",  "Category": "라디오" },
      { "Name": "KBS한민족",     "ServiceId": "815461",  "Category": "라디오" },
      { "Name": "KBSWorldRadio", "ServiceId": "815447",  "Category": "라디오" },
      { "Name": "BBS불교방송",   "ServiceId": "815448",  "Category": "라디오" },
      { "Name": "CBS음악FM",     "ServiceId": "815449",  "Category": "라디오" },
      { "Name": "CBS표준FM",     "ServiceId": "815451",  "Category": "라디오" },
      { "Name": "EBS교육방송",   "ServiceId": "815452",  "Category": "라디오" },
      { "Name": "TBS교통방송",   "ServiceId": "815468",  "Category": "라디오" },
      { "Name": "CPBC 평화방송", "ServiceId": "1974894", "Category": "라디오" },
      { "Name": "경인방송",      "ServiceId": "1974895", "Category": "라디오" },
      { "Name": "YTN NEWS FM",   "ServiceId": "2074615", "Category": "라디오" },
      { "Name": "극동방송",      "ServiceId": "2074616", "Category": "라디오" },
      { "Name": "국악방송",      "ServiceId": "2891853", "Category": "라디오" },
      { "Name": "원음방송",      "ServiceId": "6303645", "Category": "라디오" }
    ]
  }
}
```

설정 항목 중 중요한 것:

| 항목 | 값 | 의미 |
|---|---|---|
| `FETCH_LIMIT` | `2` | 오늘·내일 2일치 편성 수집 |
| `ID_FORMAT` | `{ServiceId}.{Source.lower()}` | XMLTV 채널 ID를 `815457.naver` 형태로 생성. **채널 설정의 `epg` 값과 형식이 같아야 함** |
| `ADD_EPNUM_TO_TITLE` | `true` | 회차 정보를 제목에 포함 |

### 2-5. 채널 매핑표 (KRadio 기본 채널 기준)

네이버 표기 이름이 방송사 공식 이름과 다른 경우가 있으니 주의하세요. (예: 네이버 `KBS1FM` = KBS 클래식FM, `KBS2FM` = KBS 쿨FM, `KBS2R` = KBS 해피FM)

| KRadio 채널 `id` | 채널 이름 | 네이버 표기 | `epg` 값 |
|---|---|---|---|
| `kbs_1radio` | KBS 1라디오 | KBS1R | `815455.naver` |
| `kbs_happy` | KBS 해피FM | KBS2R | `815458.naver` |
| `kbs_3radio` | KBS 3라디오 | KBS3R | `815460.naver` |
| `kbs_classic` | KBS 클래식FM | KBS1FM | `815454.naver` |
| `kbs_cool` | KBS 쿨FM | KBS2FM | `815457.naver` |
| `mbc_fm` | MBC 표준FM | MBC표준FM | `815464.naver` |
| `mbc_fm4u` | MBC FM4U | MBCFM4U | `815463.naver` |
| `sbs_power` | SBS 파워FM | SBS파워FM | `815467.naver` |
| `sbs_love` | SBS 러브FM | SBS러브FM | `815465.naver` |
| `cbs_music_fm` | CBS 음악FM | CBS음악FM | `815449.naver` |
| `cbs_fm` | CBS 표준FM | CBS표준FM | `815451.naver` |
| `ebsfm` | EBS FM | EBS교육방송 | `815452.naver` |
| `ytn` | YTN 라디오 | YTN NEWS FM | `2074615.naver` |
| `tbsfm` | TBS FM | TBS교통방송 | `815468.naver` |
| `ifm` | 경인방송 iFM | 경인방송 | `1974895.naver` |
| `tbnfm` | TBN 경인 | (네이버에 없음) | 없음 |

> TBN 경인은 네이버 편성표에 없습니다. epg2xml의 DAUM 출처에는 `라디오 TBN(경인)` 채널이 있으므로, 필요하면 DAUM을 켜고 해당 채널만 추가하는 방법을 시도해 볼 수 있습니다. (KRadio 기본 편성표에는 미포함)

### 2-6. 주기적으로 만들어 올리기

편성표는 날마다 바뀌므로 **하루 두 번 정도** 새로 만들어 올려야 합니다. KRadio 기본 편성표는 다음처럼 운영합니다.

1. 서버에서 epg2xml을 매일 **04:56, 16:56**에 실행
   ```
   56 4,16 * * * cd /경로/epg && python3 -m epg2xml run --xmlfile=/경로/epg/xmltv.xml
   ```
2. 매시간 한 번씩 결과 파일을 검사해서, **내용이 바뀌었을 때만** 공개 저장소에 올림
   - 프로그램이 10개 미만이면 깨진 파일로 보고 올리지 않음
   - GitHub 사용 시 별도 브랜치에 커밋 1개로 **덮어쓰기**(force push) 하면 기록이 쌓이지 않음

업로드 스크립트 예시(GitHub, 저장소 전용 Deploy key 사용):

```bash
#!/bin/bash
set -euo pipefail
WORK="$HOME/kradio-epg-push"
REPO="git@github-kradio:계정/저장소.git"      # ~/.ssh/config 에 Deploy key 호스트 별칭 등록
SRC="/경로/epg/xmltv.xml"                     # 또는 http://127.0.0.1:포트/epg/xmltv.xml

TMP=$(mktemp); trap 'rm -f "$TMP"' EXIT
cp "$SRC" "$TMP"    # URL이면: curl -fsS "$SRC" -o "$TMP"

COUNT=$(grep -c "<programme" "$TMP" || true)
[ "$COUNT" -lt 10 ] && { echo "편성표 이상 - 건너뜀"; exit 1; }

NEW_SHA=$(sha256sum "$TMP" | cut -d' ' -f1)
[ -f "$WORK/last.sha" ] && [ "$(cat "$WORK/last.sha")" = "$NEW_SHA" ] && exit 0

rm -rf "$WORK/repo" && mkdir -p "$WORK/repo" && cd "$WORK/repo"
git init -q -b epg
git config user.name "epg-bot"
git config user.email "epg-bot@users.noreply.github.com"
cp "$TMP" xmltv.xml
date -u +%FT%TZ > updated.txt
git add . && git commit -q -m "EPG update"
git push -q -f "$REPO" epg
echo "$NEW_SHA" > "$WORK/last.sha"
```

크론 등록(매시간 20분):
```
20 * * * * $HOME/kradio-epg-push/push_epg.sh
```

---

## 3. 파일 올리기와 앱 설정

### 3-1. 어디에 올려도 됩니다

아래 조건만 맞으면 GitHub가 아니어도 됩니다. (개인 웹서버, NAS, 다른 저장소, 정적 파일 호스팅 등)

- 로그인 없이 주소만으로 파일을 받을 수 있을 것 (HTTP GET)
- 파일 인코딩은 **UTF-8**
- 가능하면 **HTTPS** 주소

GitHub를 쓴다면 파일의 "Raw" 주소(`https://raw.githubusercontent.com/계정/저장소/브랜치/파일`)를 사용합니다. GitHub Raw는 캐시 때문에 수정 후 **최대 5분 정도** 늦게 반영될 수 있습니다.

### 3-2. 앱에서 주소 지정하기

1. 상단 톱니바퀴 → **설정 → 데이터 출처**
2. **채널 설정**을 눌러 `channels.json` 주소 입력 → 저장
3. 편성표 주소를 `channels.json`의 `epgUrl`에 적어두었다면 **편성표**는 비워둬도 됩니다. 따로 지정하려면 **편성표 (EPG)**에 `xmltv.xml` 주소 입력
4. **지금 다시 불러오기**로 확인. 실패하면 해당 항목에 빨간색 "연결 실패"가 표시됩니다
5. 언제든 **기본값으로**를 누르면 KRadio 기본 제공 데이터로 돌아갑니다

편성표 주소는 다음 순서로 결정됩니다.

1. 앱 설정에서 직접 지정한 편성표 주소
2. 채널 설정 파일의 `epgUrl`
3. KRadio 기본 제공 편성표

### 3-3. 앱이 데이터를 다루는 방식

- 불러온 데이터는 폰에 저장해 두고, 연결에 실패하면 **마지막으로 받은 데이터**로 계속 동작합니다.
- 편성표는 3시간마다 새로 받습니다.
- 사용자가 앱에서 바꾼 순서·숨김·채널 수정·직접 추가한 채널은 폰에만 저장되며, 데이터 출처를 바꿔도 유지됩니다. (단, 채널 `id`가 같은 경우에만 연결됩니다)

---

## 4. 문제 해결

| 증상 | 확인할 것 |
|---|---|
| 설정에 "연결 실패"가 뜸 | 주소를 브라우저로 열어 파일이 바로 보이는지 확인 |
| 채널 목록이 안 바뀜 | JSON 문법 오류 여부 (쉼표, 따옴표). JSON 검사 도구로 확인 |
| 특정 채널만 재생 안 됨 (`direct`) | 스트림 주소를 VLC 등에서 직접 열어보기 |
| 특정 채널만 재생 안 됨 (`api`) | API 응답 형식 변경 여부 → `extract` 경로·정규식, `headers` 확인 |
| 편성이 "편성 정보 없음" | 채널의 `epg` 값과 XMLTV의 `programme@channel` 값이 정확히 같은지 |
| 편성이 오래된 내용 | 편성표 생성·업로드 스케줄이 돌고 있는지, 파일 안 날짜 확인 |
| 특정 채널 편성만 갑자기 사라짐 | 네이버 ServiceId 변경 여부 확인 후 `epg2xml.json` 수정 |

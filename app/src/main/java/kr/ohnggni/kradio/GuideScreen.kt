package kr.ohnggni.kradio

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

@Composable
fun GuideScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val copy: (String, String) -> Unit = { label, text ->
        context.getSystemService(ClipboardManager::class.java)
            .setPrimaryClip(ClipData.newPlainText(label, text))
        // 안드로이드 13 이상은 시스템이 복사 알림을 직접 보여줌
        if (Build.VERSION.SDK_INT < 33) {
            Toast.makeText(context, "$label 복사됨", Toast.LENGTH_SHORT).show()
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            Row(
                Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) { Icon(IconBackGuide, contentDescription = "뒤로") }
                Text("데이터 형식 안내", style = MaterialTheme.typography.titleLarge)
            }
        }
    ) { inner ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(inner)
                .verticalScroll(rememberScrollState())
                .padding(bottom = 32.dp)
        ) {
            Para(
                "KRadio는 채널 설정(channels.json)과 편성표(XMLTV) 두 파일을 인터넷에서 받아 동작해요. " +
                        "기본 제공 서버에 문제가 생기면 아래 형식대로 직접 만들어 올리고, 설정에서 주소를 지정하면 계속 쓸 수 있어요."
            )

            // ---------- 1. 채널 설정 ----------
            Heading("1. 채널 설정 파일 (channels.json)")
            Para("채널 목록과 재생 방법을 담은 JSON 파일이에요. channels 배열 순서가 기본 채널 순서(이전/다음 순서)가 돼요.")
            Bullet("id: 채널 고유 ID. 바꾸면 앱에서 정한 순서·숨김·수정과 연결이 끊겨요")
            Bullet("name · group · logo: 이름, 묶음, 로고 (파일 이름만 쓰면 logoBase 뒤에 붙여요)")
            Bullet("type \"direct\": url에 고정 스트림 주소 (m3u8, mp3, aac)")
            Bullet("type \"api\": 재생할 때마다 api 주소를 불러서 extract 규칙(json 경로 또는 regex)으로 주소를 뽑아요. headers에는 headerSets의 이름을 적어요")
            Bullet("epg: 편성표의 채널 ID (예: 815457.naver)")
            Bullet("epgUrl (파일 맨 위): 편성표 주소")
            CodeBlock("최소 예제 (channels.json)", SAMPLE_MINIMAL, copy)
            CodeBlock("API 채널 예제", SAMPLE_API, copy)

            // ---------- 2. 편성표 ----------
            Heading("2. 편성표 (XMLTV)")
            Para("오픈소스 도구 epg2xml로 네이버(NAVER) 편성표를 수집해서 만들어요. 라디오 편성은 네이버 출처만 사용해요.")
            Bullet("채널 ID 형식은 {네이버 ServiceId}.naver 예요. 채널 설정의 epg 값과 똑같아야 편성이 연결돼요")
            Bullet("네이버 표기 주의: KBS1FM = 클래식FM, KBS2FM = 쿨FM, KBS2R = 해피FM")
            Bullet("편성은 날마다 바뀌니 하루 두 번(예: 04:56, 16:56) 새로 만들어 올려요")
            Bullet("앱은 programme의 channel · start · stop · title · sub-title만 읽어요")
            Bullet("TBN 경인은 네이버 편성표에 없어요")
            CodeBlock("설치와 실행", SAMPLE_EPG_CMD, copy)
            CodeBlock("KRadio용 epg2xml.json", SAMPLE_EPG2XML, copy)

            // ---------- 3. 앱 설정 ----------
            Heading("3. 앱에서 지정하기")
            Bullet("로그인 없이 주소만으로 받을 수 있는 곳에 올려요 (HTTPS 권장, UTF-8)")
            Bullet("설정 → 데이터 출처 → 채널 설정 / 편성표에 주소를 넣고 저장해요")
            Bullet("편성표는 직접 지정한 주소 → channels.json의 epgUrl → 기본 제공 순서로 정해져요")
            Bullet("\"지금 다시 불러오기\"로 확인하고, 빨간 \"연결 실패\"가 뜨면 주소를 확인해요")
            Bullet("언제든 \"기본값으로\"를 누르면 기본 제공 데이터로 돌아가요")
        }
    }
}

@Composable
private fun Heading(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 6.dp)
    )
}

@Composable
private fun Para(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
    )
}

@Composable
private fun Bullet(text: String) {
    Row(Modifier.padding(start = 20.dp, end = 16.dp, top = 2.dp, bottom = 2.dp)) {
        Text("•  ", style = MaterialTheme.typography.bodyMedium)
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

/** 코드 예제 박스: 길면 앞부분만 보여주고, 복사는 전체 */
@Composable
private fun CodeBlock(label: String, code: String, onCopy: (String, String) -> Unit) {
    val lines = code.lines()
    val preview = if (lines.size > 14) {
        lines.take(12).joinToString("\n") + "\n  …  (전체는 복사해서 확인)"
    } else code

    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
    ) {
        Column {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(label, style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = { onCopy(label, code) }) { Text("복사") }
            }
            Text(
                preview,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
                softWrap = false,
                modifier = Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(start = 12.dp, end = 12.dp, bottom = 12.dp)
            )
        }
    }
}

// ---------------- 예제 ----------------

private val SAMPLE_MINIMAL = """
{
  "version": 2,
  "epgUrl": "https://example.com/xmltv.xml",
  "channels": [
    {
      "id": "ytn",
      "name": "YTN 라디오",
      "type": "direct",
      "epg": "2074615.naver",
      "url": "https://radiolive.ytn.co.kr/radio/_definst_/20211118_fmlive/playlist.m3u8"
    }
  ]
}
""".trimIndent()

private val SAMPLE_API = """
"headerSets": {
  "kbs": {
    "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/106.0.0.0 Safari/537.36",
    "Referer": "https://onair.kbs.co.kr/"
  }
},
"channels": [
  {
    "id": "kbs_cool", "name": "KBS 쿨FM", "group": "KBS",
    "type": "api", "headers": "kbs", "epg": "815457.naver",
    "api": "https://cfpwwwapi.kbs.co.kr/api/v1/landing/live/channel_code/25",
    "extract": { "json": "channel_item[media_type=radio].service_url" }
  }
]
""".trimIndent()

private val SAMPLE_EPG_CMD = """
pip3 install git+https://github.com/epg2xml/epg2xml.git lxml

# epg2xml.json 이 있는 폴더에서
python3 -m epg2xml run --xmlfile=xmltv.xml

# 하루 두 번 자동 실행 (crontab)
56 4,16 * * * cd /경로 && python3 -m epg2xml run --xmlfile=/경로/xmltv.xml
""".trimIndent()

private val SAMPLE_EPG2XML = """
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
""".trimIndent()

private val IconBackGuide =
    svgIcon("back", "M20,11H7.83l5.59,-5.59L12,4l-8,8 8,8 1.41,-1.41L7.83,13H20v-2z")
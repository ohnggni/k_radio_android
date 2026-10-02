package kr.ohnggni.kradio

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * 변경 내역 (assets/release_notes_ko.txt).
 * 형식: "## 버전 (날짜)" / "### 구분" / "- 항목" / "  - 하위 항목" / 그 외는 문단.
 * 날짜의 (Unreleased)는 배포 스크립트가 배포일로 바꿈.
 */
private data class Release(val version: String, val date: String, val lines: List<String>)

private fun parseNotes(text: String): List<Release> {
    val out = mutableListOf<Release>()
    var version: String? = null
    var date = ""
    var lines = mutableListOf<String>()
    fun flush() {
        version?.let { out += Release(it, date, lines.dropLastWhile { l -> l.isBlank() }) }
    }
    for (line in text.lines()) {
        if (line.startsWith("## ")) {
            flush()
            val head = line.removePrefix("## ").trim()
            version = head.substringBefore(" (").trim()
            date = head.substringAfter("(", "").substringBefore(")", "")
            lines = mutableListOf()
        } else if (version != null) {
            if (line.isNotBlank() || lines.isNotEmpty()) lines += line
        }
    }
    flush()
    return out
}

@Composable
fun ReleaseNotesScreen(appVersion: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val releases = remember {
        runCatching {
            context.assets.open("release_notes_ko.txt").bufferedReader().use { it.readText() }
        }.map { parseNotes(it) }.getOrDefault(emptyList())
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
                IconButton(onClick = onBack) { Icon(IconBackNotes, contentDescription = "뒤로") }
                Text("변경 내역", style = MaterialTheme.typography.titleLarge)
            }
        }
    ) { inner ->
        if (releases.isEmpty()) {
            Text(
                "변경 내역을 불러오지 못했어요.",
                modifier = Modifier.padding(inner).padding(16.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            return@Scaffold
        }
        LazyColumn(
            Modifier.fillMaxSize().padding(inner),
            contentPadding = PaddingValues(bottom = 32.dp)
        ) {
            items(releases, key = { it.version }) { r ->
                ReleaseBlock(r, current = r.version == appVersion)
            }
        }
    }
}

@Composable
private fun ReleaseBlock(r: Release, current: Boolean) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(r.version, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(8.dp))
            Text(
                if (r.date == "Unreleased") "배포 전" else r.date,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (current) {
                Spacer(Modifier.width(8.dp))
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Text(
                        "현재 버전",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }
        }
        for (line in r.lines) NoteLine(line)
    }
    HorizontalDivider()
}

@Composable
private fun NoteLine(line: String) {
    val body = MaterialTheme.typography.bodyMedium
    when {
        line.isBlank() -> Spacer(Modifier.height(4.dp))
        line.startsWith("### ") -> Text(
            line.removePrefix("### "),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 8.dp, bottom = 2.dp)
        )
        line.startsWith("  - ") -> Bullet("–", line.removePrefix("  - "), indent = 18.dp)
        line.startsWith("- ") -> Bullet("•", line.removePrefix("- "), indent = 0.dp)
        else -> Text(line, style = body, modifier = Modifier.padding(vertical = 2.dp))
    }
}

@Composable
private fun Bullet(mark: String, text: String, indent: androidx.compose.ui.unit.Dp) {
    Row(Modifier.padding(start = indent, top = 2.dp, bottom = 2.dp)) {
        Text(mark, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(14.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

private val IconBackNotes =
    svgIcon("back", "M20,11H7.83l5.59,-5.59L12,4l-8,8 8,8 1.41,-1.41L7.83,13H20v-2z")

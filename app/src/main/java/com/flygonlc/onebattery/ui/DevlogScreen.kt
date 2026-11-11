package com.flygonlc.onebattery.ui

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.flygonlc.onebattery.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** One release from assets/devlog.json (the DevLogEntry shape from @aribradshaw/devlog). */
internal data class DevlogRelease(
    val version: String,
    val date: String,
    val title: String,
    val summary: String?,
    val notes: List<String>,
    val compatibility: String,
    val action: String?,
    val legacyVersion: String?,
)

/** Releases per pager page. */
private const val PAGE_SIZE = 3

internal fun loadDevlog(context: Context): List<DevlogRelease> = runCatching {
    val text = context.assets.open("devlog.json").bufferedReader().use { it.readText() }
    val arr = JSONArray(text)
    (0 until arr.length()).mapNotNull { i ->
        val o = arr.optJSONObject(i) ?: return@mapNotNull null
        val version = o.optString("version").takeIf { Regex("""\d+\.\d+\.\d+""").matches(it) } ?: return@mapNotNull null
        val notesArr = o.optJSONArray("notes") ?: JSONArray()
        DevlogRelease(
            version = version,
            date = o.optString("date"),
            title = o.optString("title").ifBlank { "Update" },
            summary = o.optString("summary").takeIf { o.has("summary") && !o.isNull("summary") && it.isNotBlank() },
            notes = (0 until notesArr.length()).map { notesArr.optString(it) }.filter { it.isNotBlank() },
            compatibility = o.optString("compatibility").ifBlank { "compatible" },
            action = o.optString("action").takeIf { o.has("action") && !o.isNull("action") && it.isNotBlank() },
            legacyVersion = o.optString("legacyVersion").takeIf { o.has("legacyVersion") && it.isNotBlank() },
        )
    }.sortedWith(compareByDescending<DevlogRelease> { it.versionParts[0] }
        .thenByDescending { it.versionParts[1] }.thenByDescending { it.versionParts[2] })
}.getOrDefault(emptyList())

private val DevlogRelease.versionParts: List<Int> get() = version.split('.').map { it.toInt() }

/** Same compact pagination as @aribradshaw/devlog's getDevLogPaginationItems (1-based; null = ellipsis). */
internal fun devlogPaginationItems(current: Int, total: Int): List<Int?> {
    if (total <= 7) return (1..total).toList()
    val visible = listOf(1, total, current - 1, current, current + 1).filter { it in 1..total }.distinct().sorted()
    val items = mutableListOf<Int?>()
    visible.forEachIndexed { i, p ->
        if (i > 0 && p - visible[i - 1] > 1) items += null
        items += p
    }
    return items
}

private val dateFormat = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US)
private fun prettyDate(iso: String) = runCatching { LocalDate.parse(iso).format(dateFormat) }.getOrDefault(iso)

/**
 * Paginated, newest-first devlog bundled at build time (app/build.gradle.kts copies
 * config/devlog-releases.json into the APK as assets/devlog.json).
 *
 * [handleBack]: leave true when shown on its own; pass false when hosted in [SlideNav], whose
 * predictive-back handler already owns system back for the second screen.
 */
@Composable
fun DevlogScreen(onBack: () -> Unit, handleBack: Boolean = true) {
    BackHandler(enabled = handleBack, onBack = onBack)
    val context = LocalContext.current
    // Asset read + JSON parse off the main thread (it would otherwise run during the slide-in);
    // null while loading.
    val releases by produceState<List<DevlogRelease>?>(null, context) {
        value = withContext(Dispatchers.IO) { loadDevlog(context) }
    }
    val pages = remember(releases) { releases.orEmpty().chunked(PAGE_SIZE) }
    val pager = rememberPagerState { pages.size.coerceAtLeast(1) }
    val scope = rememberCoroutineScope()
    val anims = animationsEnabled()
    fun go(page: Int) {
        val target = page.coerceIn(0, (pages.size - 1).coerceAtLeast(0))
        scope.launch { if (anims) pager.animateScrollToPage(target) else pager.scrollToPage(target) }
    }

    Column(Modifier.fillMaxSize().background(Palette.bg).windowInsetsPadding(WindowInsets.safeDrawing)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp)) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back", tint = Palette.text)
            }
            Spacer(Modifier.width(4.dp))
            Column(Modifier.weight(1f)) {
                Text("Devlog", color = Palette.text, fontSize = Palette.headingSize(26.sp), fontWeight = FontWeight.Bold, fontFamily = Palette.heading)
                Text(
                    "Bradshaw Versioning · YEAR.MONTH.UPDATE",
                    color = Palette.muted, fontSize = 12.sp,
                )
            }
        }

        if (releases == null) {
            Box(Modifier.weight(1f).fillMaxWidth().padding(16.dp), contentAlignment = Alignment.TopCenter) {
                Text("Loading devlog…", color = Palette.muted, fontSize = 13.sp, modifier = Modifier.padding(top = 24.dp))
            }
            return@Column
        }

        if (pages.isEmpty()) {
            Box(Modifier.weight(1f).padding(16.dp)) {
                CardBox {
                    SectionTitle("No devlog in this build")
                    Text("This APK was built without assets/devlog.json.", color = Palette.muted, fontSize = 13.sp)
                }
            }
            return@Column
        }

        // Peak refresh rate while pages swipe or a page's list scrolls.
        FastFrames(active = { pager.isScrollInProgress }, Modifier.weight(1f).fillMaxWidth()) {
            HorizontalPager(
                state = pager,
                modifier = Modifier.fillMaxSize(),
                beyondViewportPageCount = 1,
                key = { pages[it].first().version },
            ) { page ->
                val list = rememberLazyListState()
                FastFrames(active = { list.isScrollInProgress }, Modifier.fillMaxSize()) {
                    LazyColumn(
                        Modifier.fillMaxSize(),
                        state = list,
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                        contentPadding = PaddingValues(16.dp),
                    ) {
                        items(pages[page], key = { it.version }) { ReleaseCard(it) }
                    }
                }
            }
        }

        PageControls(
            current = pager.currentPage,
            total = pages.size,
            onPage = ::go,
        )
    }
}

@Composable
private fun ReleaseCard(r: DevlogRelease) {
    val installed = r.version == BuildConfig.VERSION_NAME
    CardBox {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
            Text(
                r.version,
                color = Palette.onAccent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clip(Palette.chipShape).background(Palette.accent).padding(horizontal = 10.dp, vertical = 3.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                prettyDate(r.date) + (r.legacyVersion?.let { " · was v$it" } ?: ""),
                color = Palette.muted, fontSize = 12.sp, modifier = Modifier.weight(1f),
            )
            if (installed) {
                Text(
                    "Installed",
                    color = Palette.green, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clip(Palette.chipShape).border(1.dp, Palette.green, Palette.chipShape)
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
        }
        SectionTitle(r.title)
        r.summary?.let {
            Text(it, color = Palette.text, fontSize = 14.sp, modifier = Modifier.padding(bottom = 8.dp))
        }
        r.notes.forEach { note ->
            Row(Modifier.padding(vertical = 3.dp)) {
                Text("•", color = Palette.accent, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(16.dp))
                Text(note, color = Palette.text, fontSize = 14.sp, lineHeight = 20.sp)
            }
        }
        if (r.compatibility != "compatible" || r.action != null) {
            Spacer(Modifier.height(8.dp))
            val color = if (r.compatibility == "breaking") Palette.red else Palette.amber
            Text(
                buildString {
                    append(if (r.compatibility == "breaking") "Breaking" else "Action required")
                    r.action?.let { append(": ").append(it) }
                },
                color = color, fontSize = 13.sp, fontWeight = FontWeight.Medium,
            )
        }
    }
}

@Composable
private fun PageControls(current: Int, total: Int, onPage: (Int) -> Unit) {
    val t = Palette.theme
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        IconButton(onClick = { onPage(current - 1) }, enabled = current > 0, modifier = Modifier.alpha(if (current > 0) 1f else 0.35f)) {
            Icon(Icons.Outlined.ChevronLeft, contentDescription = "Newer releases", tint = Palette.text)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            devlogPaginationItems(current + 1, total).forEach { item ->
                if (item == null) {
                    Text("…", color = Palette.muted, fontSize = 14.sp, modifier = Modifier.width(20.dp), textAlign = TextAlign.Center)
                } else {
                    val selected = item == current + 1
                    val shape = if (t.hardShadow > 0.dp) RoundedCornerShape(t.chipRadius) else Palette.chipShape
                    val outline = if (t.border > 0.dp) (if (t.hardShadow > 0.dp) t.ink else t.line) else Color.Transparent
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.size(width = 36.dp, height = 32.dp).widthIn(min = 32.dp)
                            .clip(shape)
                            .background(if (selected) Palette.accent else Palette.card)
                            .border(if (selected || t.border == 0.dp) 0.dp else t.border, outline, shape)
                            .clickable(role = Role.Button) { onPage(item - 1) }
                            .semantics { contentDescription = "Page $item of $total" },
                    ) {
                        Text(
                            "$item",
                            color = if (selected) Palette.onAccent else Palette.text,
                            fontSize = 13.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                        )
                    }
                }
            }
        }
        IconButton(onClick = { onPage(current + 1) }, enabled = current < total - 1, modifier = Modifier.alpha(if (current < total - 1) 1f else 0.35f)) {
            Icon(Icons.Outlined.ChevronRight, contentDescription = "Older releases", tint = Palette.text)
        }
    }
}

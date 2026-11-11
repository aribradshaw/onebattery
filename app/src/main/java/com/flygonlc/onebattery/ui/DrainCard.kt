package com.flygonlc.onebattery.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Android
import androidx.compose.material.icons.outlined.BatteryAlert
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material.icons.outlined.ScreenLockPortrait
import androidx.compose.material.icons.outlined.StopCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.flygonlc.onebattery.BackgroundUse
import com.flygonlc.onebattery.BatteryDb
import com.flygonlc.onebattery.DrainReport
import com.flygonlc.onebattery.DrainRow
import com.flygonlc.onebattery.DrainTracker
import com.flygonlc.onebattery.Stats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

private const val MAX_ROWS = 8
private const val MAX_BACKGROUND = 6
private const val MIN_TREND_MS = 7 * 24 * 3_600_000L
private val AppIconSize = 28.dp
private val AppIconGap = 10.dp

/** Play's prominent disclosure for usage access (docs/PLAY_STORE.md §1.4). */
private const val USAGE_DISCLOSURE =
    "To show which apps drain your battery, OneBattery needs to see which app is on screen while it " +
        "measures, and Android's own totals of each app's background activity and data use. This stays " +
        "on your phone. OneBattery has no internet access and never shares it. You can turn this off at " +
        "any time in Settings → Usage access."

@Composable
internal fun DrainCard(range: Range, onRange: (Range) -> Unit) {
    val context = LocalContext.current
    var hasAccess by remember { mutableStateOf(DrainTracker.hasUsageAccess(context)) }
    // Starts from the last report for this range, so scrolling the card back into view shows it
    // straight away instead of flashing "Collecting data…" (and jumping the list) while it reloads.
    var report by remember { mutableStateOf(DrainReportCache.get(range)) }
    var expanded by remember { mutableStateOf<String?>(null) }
    var askAccess by remember { mutableStateOf(false) }
    LifecycleResumeEffect(Unit) {
        hasAccess = DrainTracker.hasUsageAccess(context)
        onPauseOrDispose { }
    }
    // Refreshes every minute, but only while the activity is resumed (and once on each return).
    val resumed = isResumed()
    LaunchedEffect(range, hasAccess, resumed) {
        if (!resumed) return@LaunchedEffect
        DrainReportCache.get(range)?.let { report = it }
        while (true) {
            report = withContext(Dispatchers.IO) {
                DrainTracker.report(context, range.ms, System.currentTimeMillis())
            }.also { DrainReportCache.put(range, it) }
            delay(60_000)
        }
    }
    CardBox {
        SectionTitle("What's draining your battery")
        RangeChips(range, onRange)
        Spacer(Modifier.height(10.dp))
        if (!hasAccess) {
            Text(
                "Allow Usage access so OneBattery can see which app is open while it measures. " +
                    "Nothing leaves your phone.",
                color = Palette.amber, fontSize = 13.sp,
            )
            TextButton(
                onClick = { askAccess = true },
                shape = Palette.chipShape,
                colors = ButtonDefaults.textButtonColors(contentColor = Palette.accent),
            ) { Text("Allow Usage access…") }
        }
        val r = report
        if (r == null || r.rows.isEmpty()) {
            Text("Collecting data… use your phone unplugged and check back.", color = Palette.muted, fontSize = 13.sp)
        } else {
            val total = r.rows.sumOf { it.mah }
            Text(
                "~${total.roundToInt()} mAh tracked, about ${(total * 100 / r.capacityMah).roundToInt()}% of a full charge",
                color = Palette.muted, fontSize = 12.sp,
            )
            Spacer(Modifier.height(8.dp))
            r.rows.take(MAX_ROWS).forEach { row ->
                DrainItem(row, total, range, expanded == row.pkg) {
                    expanded = row.pkg.takeIf { it != expanded }
                }
            }
        }
        if (r != null && r.background.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            Text("Busy in the background", color = Palette.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text("Android's records of always-running services and background data", color = Palette.muted, fontSize = 12.sp)
            Spacer(Modifier.height(6.dp))
            r.background.take(MAX_BACKGROUND).forEach { BackgroundItem(it) }
        }
        Spacer(Modifier.height(10.dp))
        Text(
            "Open apps are measured from real current. Screen-off drain comes from the drop in battery % and " +
                "can't be split by app. Tracking starts when you install this version and only counts unplugged time.",
            color = Palette.muted, fontSize = 11.sp,
        )
    }
    if (askAccess) {
        UsageAccessDialog(
            onContinue = {
                askAccess = false
                startOrFalse(context, Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            },
            onDismiss = { askAccess = false },
        )
    }
}

/** Last drain report per range, kept for the process so the card re-enters the list fully drawn. */
private object DrainReportCache {
    private val reports = HashMap<Range, DrainReport>()
    @Synchronized fun get(range: Range): DrainReport? = reports[range]
    @Synchronized fun put(range: Range, report: DrainReport) { reports[range] = report }
}

private fun isRealApp(pkg: String) = pkg != DrainTracker.SCREEN_OFF && pkg != DrainTracker.SCREEN_ON_OTHER

@Composable
private fun DrainItem(row: DrainRow, total: Double, range: Range, isExpanded: Boolean, onClick: () -> Unit) {
    val context = LocalContext.current
    val itemShape = RoundedCornerShape(minOf(Palette.theme.chipRadius, 12.dp))
    Column(
        Modifier.fillMaxWidth().clip(itemShape).clickable(onClick = onClick).padding(vertical = 6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AppIcon(row.pkg)
            Spacer(Modifier.width(AppIconGap))
            Text(
                row.label, color = Palette.text, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Text("${row.mah.roundToInt()} mAh", color = Palette.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            if (isRealApp(row.pkg)) {
                Spacer(Modifier.width(8.dp))
                AppActions(row.pkg)
            }
        }
        // Bars line up across rows (all start after the icon and span the rest of the card),
        // so their lengths compare honestly whether or not a row has action buttons.
        Column(Modifier.padding(start = AppIconSize + AppIconGap)) {
            Spacer(Modifier.height(4.dp))
            FxBar(progress = (row.mah / total).toFloat(), color = Palette.green, height = 6.dp)
            Spacer(Modifier.height(4.dp))
            Text(detail(row, range), color = Palette.muted, fontSize = 12.sp)
        }
        if (isExpanded) {
            val now = System.currentTimeMillis()
            val from = now - maxOf(range.ms, MIN_TREND_MS)
            var days by remember(row.pkg, range) { mutableStateOf<List<Pair<Long, Float>>>(emptyList()) }
            LaunchedEffect(row.pkg, range) {
                days = withContext(Dispatchers.IO) { BatteryDb.get(context).drainByDay(row.pkg, from) }
            }
            Spacer(Modifier.height(8.dp))
            Text("mAh per day", color = Palette.muted, fontSize = 12.sp)
            TimeLineChart(
                points = days, xStart = from, xEnd = now, yMin = 0f,
                yMax = ((days.maxOfOrNull { it.second } ?: 100f) * 1.2f).coerceAtLeast(10f),
                color = Palette.amber, yLabel = { "${it.roundToInt()}" }, height = 110.dp, showDots = true,
                maxGapMs = 3 * 24 * 3_600_000L,
            )
        }
    }
}

private fun detail(row: DrainRow, range: Range): String {
    val parts = ArrayList<String>()
    val hours = row.ms / 3_600_000.0
    if (row.ms >= 60_000L) {
        val verb = if (row.pkg == DrainTracker.SCREEN_OFF) "asleep" else "on screen"
        parts += "${Stats.formatDuration(row.ms)} $verb"
    }
    if (hours >= 0.25) parts += "${(row.mah / hours).roundToInt()} mAh/h"
    val prev = row.previousMah
    if (range != Range.All && prev != null && prev >= 5) {
        val change = ((row.mah - prev) * 100 / prev).roundToInt()
        parts += "${if (change > 0) "+" else ""}$change% vs previous ${range.label}"
    }
    return parts.joinToString(" · ")
}

@Composable
private fun BackgroundItem(use: BackgroundUse) {
    val parts = ArrayList<String>()
    if (use.serviceMs >= 60_000L) parts += "${Stats.formatDuration(use.serviceMs)} running"
    if (use.bytes >= 1_000_000L) parts += "${use.bytes / 1_000_000} MB data"
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        AppIcon(use.pkg)
        Spacer(Modifier.width(AppIconGap))
        Column(Modifier.weight(1f)) {
            Text(use.label, color = Palette.text, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(parts.joinToString(" · "), color = Palette.muted, fontSize = 12.sp)
        }
        Spacer(Modifier.width(8.dp))
        AppActions(use.pkg)
    }
}

// ---- Per-app actions -------------------------------------------------------------------------

/**
 * Android doesn't let one app stop or restrict another, so both buttons open the system page that
 * can: App info (Force stop) and the app's battery usage page (Restricted / Optimised).
 */
@Composable
private fun AppActions(pkg: String) {
    val context = LocalContext.current
    Row {
        ActionIcon(
            icon = Icons.Outlined.StopCircle, tint = Palette.red,
            title = "Stop", explanation = "Opens App info, where Force stop ends it right now.",
            description = "Stop app (opens App info)",
        ) { openAppInfo(context, pkg) }
        Spacer(Modifier.width(6.dp))
        ActionIcon(
            icon = Icons.Outlined.BatteryAlert, tint = Palette.amber,
            title = "Restrict", explanation = "Opens its battery page. Pick Restricted to stop background use.",
            description = "Restrict battery use (opens battery settings)",
        ) { openBatteryPage(context, pkg) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ActionIcon(
    icon: ImageVector,
    tint: Color,
    title: String,
    explanation: String,
    description: String,
    onClick: () -> Unit,
) {
    val t = Palette.theme
    val shape = Palette.chipShape
    val outline = if (t.hardShadow > 0.dp) t.ink else t.line
    // Long-press shows the tooltip (TooltipBox handles that gesture); a tap runs the action.
    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(8.dp),
        tooltip = { ThemedTooltip(title, explanation) },
        state = rememberTooltipState(isPersistent = true),
    ) {
        Box(
            Modifier.size(40.dp).clip(shape).background(Palette.cardHi)
                .then(if (t.border > 0.dp) Modifier.border(t.border, outline, shape) else Modifier)
                .clickable(onClickLabel = title, role = Role.Button, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = description, tint = tint, modifier = Modifier.size(20.dp))
        }
    }
}

/** Tooltip card in the theme's own surface: hard ink shadow for the pixel theme, soft shadow elsewhere. */
@Composable
private fun ThemedTooltip(title: String, text: String) {
    val t = Palette.theme
    val radius = minOf(t.radius, 14.dp)
    val shape = RoundedCornerShape(radius)
    val pixel = t.hardShadow > 0.dp
    val outline = if (pixel) t.ink else t.line
    Column(
        Modifier.widthIn(max = 240.dp)
            .then(
                if (pixel) Modifier.padding(end = t.hardShadow, bottom = t.hardShadow).drawBehind {
                    val o = t.hardShadow.toPx()
                    drawRoundRect(t.ink, topLeft = Offset(o, o), size = size, cornerRadius = CornerRadius(radius.toPx()))
                } else Modifier.shadow(6.dp, shape)
            )
            .clip(shape).background(t.surface)
            .then(if (t.border > 0.dp || t.dark) Modifier.border(maxOf(t.border, 1.dp), outline, shape) else Modifier)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text(
            title, color = t.ink, fontFamily = t.heading, fontSize = Palette.headingSize(14.sp),
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(3.dp))
        Text(text, color = t.muted, fontSize = 12.sp, lineHeight = 16.sp)
    }
}

private fun startOrFalse(context: Context, intent: Intent): Boolean = try {
    context.startActivity(intent)
    true
} catch (_: Exception) {
    false
}

private fun openAppInfo(context: Context, pkg: String) {
    startOrFalse(context, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg")))
}

/**
 * The app's own battery usage page (AOSP / One UI "App battery usage", where Unrestricted /
 * Optimised / Restricted live). Not a public API, so fall back to App info if it's missing or refuses.
 */
private fun openBatteryPage(context: Context, pkg: String) {
    val uid = try {
        context.packageManager.getApplicationInfo(pkg, 0).uid
    } catch (_: Exception) {
        -1
    }
    val intent = Intent("android.settings.VIEW_ADVANCED_POWER_USAGE_DETAIL", Uri.parse("package:$pkg"))
        .putExtra("package_name", pkg)
        .putExtra("extra_package_name", pkg)
        .apply { if (uid >= 0) { putExtra("extra_uid", uid); putExtra("uid", uid) } }
    if (!startOrFalse(context, intent)) openAppInfo(context, pkg)
}

// ---- App icons -------------------------------------------------------------------------------

/**
 * Icon lookups go through package visibility: with only launcher apps queryable, some packages
 * throw NameNotFoundException. Those (and failures) are cached as null and get a generic glyph.
 */
private object AppIcons {
    private val cache = HashMap<String, ImageBitmap?>()

    @Synchronized fun peek(pkg: String): ImageBitmap? = cache[pkg]

    fun load(context: Context, pkg: String): ImageBitmap? {
        synchronized(this) { if (cache.containsKey(pkg)) return cache[pkg] }
        val bmp = try {
            context.packageManager.getApplicationIcon(pkg).toBitmap(96, 96).asImageBitmap()
        } catch (_: Exception) {
            null
        }
        synchronized(this) { cache[pkg] = bmp }
        return bmp
    }
}

@Composable
private fun AppIcon(pkg: String) {
    val context = LocalContext.current
    val icon by produceState(AppIcons.peek(pkg), pkg) {
        if (isRealApp(pkg)) value = withContext(Dispatchers.IO) { AppIcons.load(context, pkg) }
    }
    val shape = RoundedCornerShape(minOf(Palette.theme.chipRadius, 8.dp))
    Box(Modifier.size(AppIconSize).clip(shape), contentAlignment = Alignment.Center) {
        val bmp = icon
        if (bmp != null) {
            Image(bmp, contentDescription = null, modifier = Modifier.fillMaxSize())
        } else {
            Box(Modifier.fillMaxSize().background(Palette.cardHi), contentAlignment = Alignment.Center) {
                val glyph = when (pkg) {
                    DrainTracker.SCREEN_OFF -> Icons.Outlined.Bedtime
                    DrainTracker.SCREEN_ON_OTHER -> Icons.Outlined.ScreenLockPortrait
                    else -> Icons.Outlined.Android
                }
                Icon(glyph, contentDescription = null, tint = Palette.muted, modifier = Modifier.size(18.dp))
            }
        }
    }
}

// ---- Usage access disclosure -----------------------------------------------------------------

@Composable
private fun UsageAccessDialog(onContinue: () -> Unit, onDismiss: () -> Unit) {
    val t = Palette.theme
    val shape = RoundedCornerShape(t.radius)
    val pixel = t.hardShadow > 0.dp
    val outline = if (pixel) t.ink else t.line
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier
            .then(
                if (pixel) Modifier.drawBehind {
                    val o = t.hardShadow.toPx()
                    drawRoundRect(t.ink, topLeft = Offset(o, o), size = size, cornerRadius = CornerRadius(t.radius.toPx()))
                } else Modifier
            )
            .then(if (t.border > 0.dp) Modifier.border(t.border, outline, shape) else Modifier),
        shape = shape,
        containerColor = t.surface,
        tonalElevation = 0.dp,
        iconContentColor = Palette.accent,
        titleContentColor = t.ink,
        textContentColor = t.muted,
        icon = { Icon(Icons.Outlined.BatteryAlert, contentDescription = null) },
        title = {
            Text("Allow Usage access?", fontFamily = t.heading, fontSize = Palette.headingSize(22.sp), fontWeight = FontWeight.SemiBold)
        },
        text = { Text(USAGE_DISCLOSURE, fontSize = 14.sp, lineHeight = 20.sp) },
        confirmButton = {
            Button(
                onClick = onContinue,
                shape = Palette.chipShape,
                colors = ButtonDefaults.buttonColors(containerColor = Palette.accent, contentColor = Palette.onAccent),
            ) { Text("Continue") }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                shape = Palette.chipShape,
                colors = ButtonDefaults.textButtonColors(contentColor = Palette.text),
            ) { Text("Not now") }
        },
    )
}

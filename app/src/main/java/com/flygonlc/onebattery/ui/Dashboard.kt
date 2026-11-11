package com.flygonlc.onebattery.ui

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.flygonlc.onebattery.BatteryDb
import com.flygonlc.onebattery.BatteryReader
import com.flygonlc.onebattery.BatterySnapshot
import com.flygonlc.onebattery.BuildConfig
import com.flygonlc.onebattery.CapacityEstimate
import com.flygonlc.onebattery.CoulombCounter
import com.flygonlc.onebattery.Measurement
import com.flygonlc.onebattery.MonitorSettings
import com.flygonlc.onebattery.Stats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToInt

internal enum class Range(val label: String, val ms: Long) {
    Day("24h", 24 * 3_600_000L),
    Week("7d", 7 * 24 * 3_600_000L),
    Month("30d", 30 * 24 * 3_600_000L),
    All("All", 365 * 24 * 3_600_000L),
}

private const val PREFS = "onebattery"
private const val KEY_DESIGN = "design_mah"

@Composable
fun Dashboard() {
    val context = LocalContext.current
    val db = remember { BatteryDb.get(context) }
    val prefs = remember { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

    var live by remember { mutableStateOf(BatteryReader.read(context)) }
    var data by remember { mutableStateOf(DashboardData.Empty) }
    // Saved across rotation (and kept while the history card scrolls out of the list).
    var range by rememberSaveable { mutableStateOf(Range.Day) }
    var chartWindow by rememberSaveable(stateSaver = ChartWindowSaver) { mutableStateOf<ChartWindow?>(null) }
    val selectRange: (Range) -> Unit = { range = it; chartWindow = null }
    var design by remember {
        mutableIntStateOf(prefs.getInt(KEY_DESIGN, 0).takeIf { it > 0 } ?: BatteryReader.designCapacityMah(context))
    }
    var editDesign by rememberSaveable { mutableStateOf(false) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var showDevlog by rememberSaveable { mutableStateOf(false) }
    var tick by remember { mutableIntStateOf(0) }

    // Live readings every 2s; persist a sample at most every 5 minutes while open. The loop runs
    // only while the activity is resumed (restarting, with an immediate read, on return). The
    // battery read (several binder calls) happens off the main thread.
    val resumed = isResumed()
    LaunchedEffect(resumed) {
        if (!resumed) return@LaunchedEffect
        while (true) {
            var inserted = false
            val snap = withContext(Dispatchers.IO) {
                BatteryReader.read(context)?.also { snap ->
                    if (snap.time - db.lastSampleTime() > 5 * 60_000L) {
                        db.insert(snap)
                        inserted = true
                    }
                }
            }
            live = snap
            if (inserted) tick++
            delay(2000)
        }
    }
    // Reload when a measurement finishes (progress resets to null). Collected here rather than read
    // in composition, so per-reading progress updates don't recompose the whole dashboard.
    LaunchedEffect(Unit) {
        CoulombCounter.progress.map { it == null }.distinctUntilChanged().collect { tick++ }
    }
    // Everything derived from the history (chart series, charge bands, per-range usage) is built
    // here, off the main thread, so scrolling cards back into view never redoes it on the UI thread.
    LaunchedEffect(tick) {
        data = withContext(Dispatchers.IO) { DashboardData.load(db) }
    }

    val now = System.currentTimeMillis()

    // Main ⇄ settings and main ⇄ devlog slides (with predictive back). The list state is hoisted
    // out of both transitions so the dashboard keeps its scroll position across either trip.
    val mainList = rememberLazyListState()
    SlideNav(
        showSecond = showSettings,
        onBack = { showSettings = false },
        second = { SettingsScreen(onBack = { showSettings = false }) },
    ) {
        SlideNav(
            showSecond = showDevlog,
            onBack = { showDevlog = false },
            second = { DevlogScreen(onBack = { showDevlog = false }, handleBack = false) },
        ) {
            FastFrames(active = { mainList.isScrollInProgress }, Modifier.fillMaxSize()) {
                LazyColumn(
                    Modifier.fillMaxSize().background(Palette.bg).windowInsetsPadding(WindowInsets.safeDrawing),
                    state = mainList,
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                ) {
                    item(key = "header") { Header(onSettings = { showSettings = true }) }
                    item(key = "hero") { live?.let { HeroCard(it, data.rate) } }
                    item(key = "live") { live?.let { LiveGrid(it, data.capacity?.mah) } }
                    item(key = "health") {
                        val progress by CoulombCounter.progress.collectAsState()
                        HealthCard(data.capacity, design, live?.cycleCount, progress, charging = live?.isCharging == true) { editDesign = true }
                    }
                    item(key = "drain") { DrainCard(range, onRange = selectRange) }
                    item(key = "history") {
                        HistoryCard(
                            data.history, range, chartWindow, now,
                            onRange = selectRange,
                            onWindow = { chartWindow = it },
                        )
                    }
                    item(key = "trend") { CapacityTrendCard(data.measurements, design, now) }
                    item(key = "usage") { UsageCard(data, range) }
                    item(key = "footer") { DevlogFooter(onOpen = { showDevlog = true }) }
                }
            }
        }
    }

    if (editDesign) {
        DesignDialog(design, onDismiss = { editDesign = false }) {
            design = it
            prefs.edit().putInt(KEY_DESIGN, it).apply()
            editDesign = false
        }
    }
}

@Composable
private fun Header(onSettings: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
        Column(Modifier.weight(1f)) {
            Text("OneBattery", color = Palette.text, fontSize = Palette.headingSize(28.sp), fontWeight = FontWeight.Bold, fontFamily = Palette.heading)
            Text("by Flygon LC", color = Palette.accent, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        }
        IconButton(onClick = onSettings) {
            Icon(Icons.Outlined.Settings, contentDescription = "Settings", tint = Palette.muted)
        }
    }
}

/** App name + version at the foot of the dashboard; tapping it opens the devlog. */
@Composable
private fun DevlogFooter(onOpen: () -> Unit) {
    Box(Modifier.fillMaxWidth().padding(vertical = 4.dp), contentAlignment = Alignment.Center) {
        Row(
            Modifier.clip(Palette.chipShape)
                .clickable(onClickLabel = "Open devlog", role = Role.Button, onClick = onOpen)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("OneBattery by Flygon LC · v${BuildConfig.VERSION_NAME}", color = Palette.muted, fontSize = 12.sp)
            Text("  ·  ", color = Palette.muted, fontSize = 12.sp)
            Text("Devlog", color = Palette.accent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Icon(Icons.Outlined.ChevronRight, contentDescription = null, tint = Palette.accent, modifier = Modifier.size(16.dp))
        }
    }
}

@Composable
private fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var live by remember { mutableStateOf(MonitorSettings.liveNotification(context)) }
    var interval by remember { mutableLongStateOf(MonitorSettings.intervalMs(context)) }
    var screenOnly by remember { mutableStateOf(MonitorSettings.screenOnOnly(context)) }
    val list = rememberLazyListState()

    FastFrames(active = { list.isScrollInProgress }, Modifier.fillMaxSize()) {
    LazyColumn(
        Modifier.fillMaxSize().background(Palette.bg).windowInsetsPadding(WindowInsets.safeDrawing),
        state = list,
        verticalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
    ) {
        item {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = 4.dp, start = 0.dp),
            ) {
                IconButton(onClick = onBack, modifier = Modifier.padding(start = 0.dp)) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back", tint = Palette.text)
                }
                Spacer(Modifier.width(4.dp))
                Text("Settings", color = Palette.text, fontSize = Palette.headingSize(26.sp), fontWeight = FontWeight.Bold, fontFamily = Palette.heading)
            }
        }
        item { ThemePicker() }
        item {
            CardBox {
                SectionTitle("Live notification")
                Text(
                    "The ongoing notification in the shade tracks level, charging state and power.",
                    color = Palette.muted, fontSize = 13.sp,
                )
                Spacer(Modifier.height(6.dp))
                ToggleRow(
                    title = "Show live values",
                    subtitle = "Off leaves a static \u201CMonitoring\u201D notification",
                    checked = live,
                ) { on ->
                    live = on
                    MonitorSettings.setLiveNotification(context, on)
                }
                Spacer(Modifier.height(14.dp))
                Text("Update every", color = Palette.text, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Text(
                    if (live) "Drag the hand or tap a time. The rim marker laps once per update."
                    else "Turn on live values to change this.",
                    color = Palette.muted, fontSize = 12.sp,
                )
                Spacer(Modifier.height(10.dp))
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    IntervalDial(
                        stops = MonitorSettings.CADENCES,
                        selectedMs = interval,
                        enabled = live,
                        onSelect = { ms -> interval = ms; MonitorSettings.setIntervalMs(context, ms) },
                    )
                }
                Spacer(Modifier.height(10.dp))
                ToggleRow(
                    title = "Only while the screen is on",
                    subtitle = "No rewrites overnight \u2014 the last text stays",
                    checked = screenOnly,
                ) { on ->
                    screenOnly = on
                    MonitorSettings.setScreenOnOnly(context, on)
                }
            }
        }
        item {
            CardBox {
                SectionTitle("Sampling")
                Text(
                    "Current is still sampled every 10 s in use and every 30 s at rest \u2014 coulomb " +
                        "counting needs that. Only the notification writes are throttled above, and those " +
                        "are what wake the CPU each time.",
                    color = Palette.muted, fontSize = 13.sp,
                )
            }
        }
        item {
            Text(
                "OneBattery by Flygon LC \u00b7 v${BuildConfig.VERSION_NAME}",
                color = Palette.muted, fontSize = 12.sp,
                modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }
    }
}

@Composable
private fun ThemePicker() {
    val context = LocalContext.current
    CardBox {
        SectionTitle("Theme")
        Themes.all.forEach { t ->
            val selected = Palette.theme.id == t.id
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { Themes.select(context, t) }
                    .background(if (selected) Palette.cardHi else Color.Transparent)
                    .padding(8.dp),
            ) {
                ThemeSwatch(t)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(t.name, color = Palette.text, fontSize = 15.sp * t.headingScale, fontWeight = FontWeight.SemiBold, fontFamily = t.heading)
                    Text(t.description, color = Palette.muted, fontSize = 12.sp)
                }
                RadioButton(selected = selected, onClick = { Themes.select(context, t) })
            }
        }
    }
}

/** A tiny rendering of the theme: its background, a card, and the accent. */
@Composable
private fun ThemeSwatch(t: AppTheme) {
    Box(
        Modifier.size(52.dp).clip(RoundedCornerShape(10.dp)).background(t.paper)
            .border(1.dp, t.line, RoundedCornerShape(10.dp)).padding(7.dp)
    ) {
        Column(
            Modifier.fillMaxSize().clip(RoundedCornerShape(minOf(t.radius, 6.dp))).background(t.surface).padding(5.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Box(Modifier.fillMaxWidth(0.8f).height(5.dp).clip(RoundedCornerShape(3.dp)).background(t.ink))
            Box(Modifier.fillMaxWidth(0.55f).height(5.dp).clip(RoundedCornerShape(3.dp)).background(t.accent))
            Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(t.grid))
        }
    }
}

@Composable
private fun ToggleRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Palette.text, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Text(subtitle, color = Palette.muted, fontSize = 12.sp)
        }
        Spacer(Modifier.width(10.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
internal fun CardBox(content: @Composable () -> Unit) {
    val t = Palette.theme
    val shape = RoundedCornerShape(t.radius)
    Box(
        Modifier.fillMaxWidth()
            .then(
                if (t.hardShadow > 0.dp) Modifier.padding(end = t.hardShadow, bottom = t.hardShadow).drawBehind {
                    val o = t.hardShadow.toPx()
                    drawRoundRect(t.ink, topLeft = Offset(o, o), size = size, cornerRadius = CornerRadius(t.radius.toPx()))
                } else Modifier
            )
            .clip(shape).background(t.surface)
            .then(if (t.border > 0.dp) Modifier.border(t.border, if (t.hardShadow > 0.dp) t.ink else t.line, shape) else Modifier)
            .padding(18.dp)
    ) { Column { content() } }
}

@Composable
internal fun SectionTitle(text: String, trailing: @Composable (() -> Unit)? = null) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
        Text(text, color = Palette.text, fontSize = Palette.headingSize(16.sp), fontWeight = FontWeight.SemiBold, fontFamily = Palette.heading, modifier = Modifier.weight(1f))
        trailing?.invoke()
    }
}

@Composable
private fun HeroCard(s: BatterySnapshot, rate: Float?) {
    val color = if (s.isCharging) Palette.green else Palette.forLevel(s.level)
    // Charging motion: the streak runs faster the more watts go in (2.8 s at trickle → 1.1 s at 30 W+).
    val animate = s.isCharging && animationsEnabled()
    val watts = (s.powerW ?: 0f).coerceIn(0f, 30f)
    val sweepPeriod = 2800f - 1700f * (watts / 30f)
    val sweep = rememberLoopPhase(animate, sweepPeriod)
    val pulse = rememberLoopPhase(animate, sweepPeriod * 0.6f)
    val pixel = isPixelTheme
    val dark = Palette.theme.dark
    CardBox {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(140.dp), contentAlignment = Alignment.Center) {
                Canvas(Modifier.fillMaxSize().fastFrames(animate)) {
                    val stroke = 14.dp.toPx()
                    val inset = stroke / 2
                    val arc = Size(size.width - stroke, size.height - stroke)
                    drawArc(Palette.grid, 135f, 270f, false, Offset(inset, inset), arc, style = Stroke(stroke, cap = StrokeCap.Round))
                    drawArc(color, 135f, 270f * s.level / 100f, false, Offset(inset, inset), arc, style = Stroke(stroke, cap = StrokeCap.Round))
                    if (animate) drawChargeFx(s.level, stroke, sweep.value, pulse.value, dark, pixel, color)
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("${s.level}%", color = Palette.text, fontSize = 38.sp, fontWeight = FontWeight.Bold)
                    if (s.isCharging) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "⚡", color = color, fontSize = 12.sp,
                                modifier = Modifier.graphicsLayer {
                                    if (!animate) return@graphicsLayer
                                    // Bolt beats with the edge pulse: quick swell, slow fade.
                                    val p = if (pixel) stepped(pulse.value, 4) else pulse.value
                                    val beat = if (p < 0.25f) p / 0.25f else 1f - (p - 0.25f) / 0.75f
                                    scaleX = 1f + 0.18f * beat
                                    scaleY = scaleX
                                    alpha = 0.6f + 0.4f * beat
                                },
                            )
                            Text(" charging", color = color, fontSize = 12.sp)
                        }
                    } else {
                        Text(BatteryReader.pluggedLabel(s.plugged).lowercase(), color = color, fontSize = 12.sp)
                    }
                }
            }
            Spacer(Modifier.width(18.dp))
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(BatteryReader.statusLabel(s.status), color = Palette.text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                if (s.plugged != 0) Text("via ${BatteryReader.pluggedLabel(s.plugged)}", color = Palette.muted, fontSize = 13.sp)
                val eta = when {
                    s.isCharging && s.chargeTimeRemainingMs != null -> "Full in ${Stats.formatDuration(s.chargeTimeRemainingMs)}"
                    !s.isCharging && s.plugged == 0 && rate != null && rate > 0.1f ->
                        "~${Stats.formatDuration((s.level / rate * 3_600_000).toLong())} left"
                    else -> null
                }
                eta?.let { Text(it, color = Palette.green, fontSize = 15.sp, fontWeight = FontWeight.Medium) }
                s.powerW?.let {
                    Text(
                        "${if (s.isCharging) "+" else "−"}${"%.2f".format(it)} W",
                        color = if (s.isCharging) Palette.green else Palette.amber, fontSize = 15.sp,
                    )
                }
            }
        }
    }
}

@Composable
private fun LiveGrid(s: BatterySnapshot, capacityMah: Int?) {
    val tiles = listOf(
        Triple("Temperature", "%.1f °C".format(s.tempC), if (s.tempC >= 40) Palette.red else if (s.tempC >= 35) Palette.amber else Palette.text),
        Triple("Voltage", "%.2f V".format(s.voltageMv / 1000f), Palette.text),
        Triple("Current", s.currentMa?.let { "${if (it > 0) "+" else ""}$it mA" } ?: "—", Palette.text),
        if (capacityMah != null) Triple("Charge left", "~${capacityMah * s.level / 100} mAh", Palette.text)
        else Triple("Charge left (OS)", s.chargeMah?.let { "$it mAh" } ?: "—", Palette.muted),
        Triple("OS health", BatteryReader.healthLabel(s.health), if (s.health == android.os.BatteryManager.BATTERY_HEALTH_GOOD) Palette.green else Palette.amber),
        Triple("Technology", s.technology ?: "—", Palette.text),
    )
    CardBox {
        SectionTitle("Live")
        tiles.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { (label, value, color) ->
                    Column(
                        Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).background(Palette.cardHi).padding(12.dp)
                    ) {
                        Text(label, color = Palette.muted, fontSize = 12.sp)
                        Text(value, color = color, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

@Composable
private fun HealthCard(
    capacity: CapacityEstimate?,
    design: Int,
    cycles: Int?,
    progress: CoulombCounter.Progress?,
    charging: Boolean = false,
    onEdit: () -> Unit,
) {
    CardBox {
        SectionTitle("Battery health") {
            Row(
                Modifier.clip(RoundedCornerShape(10.dp)).clickable(onClick = onEdit).padding(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Design $design mAh", color = Palette.muted, fontSize = 12.sp)
                Spacer(Modifier.width(4.dp))
                Icon(Icons.Outlined.Edit, null, tint = Palette.muted, modifier = Modifier.size(14.dp))
            }
        }
        if (capacity == null) {
            Text("Measuring…", color = Palette.text, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Text(
                "Your phone reports charge left as battery % × design size, so it always looks brand new. " +
                    "OneBattery instead counts the real current flowing in or out while the % changes. " +
                    "Charge ${CoulombCounter.MIN_LEVEL_SPAN}%+ in one go (e.g. 30% → 80%) with OneBattery running to get your first reading.",
                color = Palette.muted, fontSize = 13.sp,
            )
        } else {
            val pct = (capacity.mah * 100f / design).roundToInt()
            val color = Palette.forHealth(pct)
            Row(verticalAlignment = Alignment.Bottom) {
                CountUpPercent(pct, color)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.padding(bottom = 6.dp)) {
                    Text("~${capacity.mah} mAh full capacity", color = Palette.text, fontSize = 14.sp)
                    Text(
                        if (pct >= 100) "No measurable degradation" else "${100 - pct}% lost (~${design - capacity.mah} mAh)",
                        color = Palette.muted, fontSize = 13.sp,
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            FxBar(progress = pct.coerceIn(0, 100) / 100f, color = color, flowing = charging)
            Spacer(Modifier.height(8.dp))
            Text("Measured ${capacity.basis}, last 60 days · ±5%", color = Palette.muted, fontSize = 12.sp)
        }
        progress?.let { p ->
            Spacer(Modifier.height(12.dp))
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Palette.cardHi).padding(12.dp)) {
                Text(
                    "Measuring this ${if (p.charging) "charge" else "discharge"}",
                    color = Palette.green, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                )
                Text(
                    if (p.anchorLevel == null) "Waiting for the % to tick over to start counting…"
                    else "${p.levelSpan}% of ${CoulombCounter.MIN_LEVEL_SPAN}% needed · ${p.mah.roundToInt()} mAh counted" +
                        (p.capacityMah?.let { " · ~$it mAh so far" } ?: ""),
                    color = Palette.muted, fontSize = 12.sp,
                )
                Spacer(Modifier.height(8.dp))
                FxBar(
                    progress = p.levelSpan.toFloat() / CoulombCounter.MIN_LEVEL_SPAN,
                    color = Palette.green,
                    // Stripes only while charging; on discharge the bar is static (bounded fill ease only).
                    flowing = p.anchorLevel != null && p.charging,
                    height = 6.dp,
                )
            }
        }
        if (cycles != null) {
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MiniStat("Charge cycles", cycles.toString(), Modifier.weight(1f))
                MiniStat("Cycle wear", "%.0f%% of 800".format(cycles / 8f), Modifier.weight(1f))
            }
        }
    }
}

/** The health % counting up: its own composable, so the per-frame count recomposes only this text. */
@Composable
private fun CountUpPercent(pct: Int, color: Color) {
    val shownPct = animatedCount(pct.toFloat()).roundToInt()
    Text("$shownPct%", color = color, fontSize = 40.sp, fontWeight = FontWeight.Bold, modifier = Modifier.fastFrames(shownPct != pct))
}

@Composable
internal fun MiniStat(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier.clip(RoundedCornerShape(14.dp)).background(Palette.cardHi).padding(12.dp)) {
        Text(label, color = Palette.muted, fontSize = 12.sp)
        Text(value, color = Palette.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun UsageCard(data: DashboardData, range: Range) {
    val rate = data.rate
    val usage = data.usage[range] ?: UsageSummary.None
    val avgTemp = usage.avgTemp
    val maxTemp = usage.maxTemp
    val count = data.sampleCount
    CardBox {
        SectionTitle("Usage (${range.label})")
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MiniStat("Avg drain", rate?.let { "%.1f %%/h".format(it) } ?: "—", Modifier.weight(1f))
            MiniStat("Full runtime", rate?.takeIf { it > 0.1f }?.let { Stats.formatDuration((100 / it * 3_600_000).toLong()) } ?: "—", Modifier.weight(1f))
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MiniStat("Charges", usage.charges.toString(), Modifier.weight(1f))
            MiniStat("Avg / max temp", if (avgTemp != null && maxTemp != null) "%.0f° / %.0f°".format(avgTemp, maxTemp) else "—", Modifier.weight(1f))
        }
        Spacer(Modifier.height(10.dp))
        val since = data.history.samples.firstOrNull()?.time
        Text(
            "$count readings logged" + (since?.let { " since " + java.text.SimpleDateFormat("MMM d, HH:mm", java.util.Locale.getDefault()).format(java.util.Date(it)) } ?: "") +
                " · drain averaged over 7 days",
            color = Palette.muted, fontSize = 12.sp,
        )
    }
}

@Composable
private fun DesignDialog(current: Int, onDismiss: () -> Unit, onSave: (Int) -> Unit) {
    var text by rememberSaveable { mutableStateOf(current.toString()) }
    val value = text.toIntOrNull()?.takeIf { it in 500..20000 }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Palette.card,
        title = { Text("Design capacity") },
        text = {
            OutlinedTextField(
                value = text, onValueChange = { text = it.filter(Char::isDigit).take(5) },
                label = { Text("mAh (Galaxy S24 Ultra = 5000)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true,
            )
        },
        confirmButton = { TextButton(onClick = { value?.let(onSave) }, enabled = value != null) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Charge count and temperatures over one range, precomputed with the history load. */
@Immutable
internal class UsageSummary(val charges: Int, val avgTemp: Double?, val maxTemp: Float?) {
    companion object {
        val None = UsageSummary(0, null, null)

        fun of(samples: List<BatterySnapshot>) = UsageSummary(
            charges = Stats.chargeSessions(samples).size,
            avgTemp = samples.map { it.tempC }.average().takeIf { !it.isNaN() },
            maxTemp = samples.maxOfOrNull { it.tempC },
        )
    }
}

/** One load of everything the dashboard shows from the database. Built on Dispatchers.IO. */
@Immutable
internal class DashboardData(
    val history: HistoryData,
    val usage: Map<Range, UsageSummary>,
    val rate: Float?,
    val sampleCount: Long,
    val measurements: List<Measurement>,
    val capacity: CapacityEstimate?,
) {
    companion object {
        val Empty = DashboardData(HistoryData.Empty, emptyMap(), null, 0L, emptyList(), null)

        fun load(db: BatteryDb): DashboardData {
            val now = System.currentTimeMillis()
            val samples = db.since(now - Range.All.ms)
            val measurements = db.measurements()
            return DashboardData(
                history = HistoryData.from(samples),
                usage = Range.entries.associateWith { r -> UsageSummary.of(samples.filter { it.time >= now - r.ms }) },
                rate = Stats.dischargeRate(samples.filter { it.time >= now - Range.Week.ms }),
                sampleCount = db.count(),
                measurements = measurements,
                capacity = Stats.capacity(measurements.filter { it.time >= now - 60 * 24 * 3_600_000L }),
            )
        }
    }
}

package com.kendang.realpads

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

// MODE MIXER.
//
// Tombolnya ada di tab samping (sebaris dengan tombol mode edit & mode kit). Saat nyala, area pad
// (klasik maupun skin) diganti deretan "strip" per pad: PAN (L/R), PITCH (tune), dan fader volume pad.
// Panel preset, volume kendang, dan musik di sisi layar TETAP seperti biasa (tidak diduplikasi di sini).
//
// Pad yang sedang dipilih dibunyikan BERULANG selama dipilih, supaya setiap perubahan PAN/PITCH/volume
// langsung terdengar. Tidak ada M/S - sengaja disederhanakan.
object MixerState {
    // Tidak disimpan permanen: setiap app dibuka, mode mixer selalu mati.
    var active by mutableStateOf(false)
        private set

    fun toggle() { active = !active }
    fun exit() { active = false }
}

// Jeda antar pengulangan bunyi pad terpilih (ms).
private const val PREVIEW_INTERVAL_MS = 1100L
// Jeda singkat setelah nilai berubah sebelum bunyi diulang (supaya geser slider tidak memicu ratusan bunyi).
private const val PREVIEW_DEBOUNCE_MS = 110L
private const val VOL_MAX = 1.5f

private enum class MixerDialogKind { PAN, PITCH }

@Composable
fun MixerPanel(
    preset: Int,
    padCount: Int,
    ready: Boolean,
    onSelectPad: (Int) -> Unit,
    // Berubah saat dialog Pengaturan dibuka/ditutup -> nilai volume/tune dibaca ulang dari engine.
    refreshKey: Any = Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val pads = remember(padCount) { (1..padCount).toList() }

    // Nilai awal dibaca dari engine (sumber kebenaran), lalu disimpan lokal supaya UI responsif.
    val vols = remember(preset, refreshKey) {
        mutableStateMapOf<Int, Float>().also { m -> for (p in 1..PresetStorage.MAX_PAD) m[p] = PadVolumeStorage.get(preset, p) }
    }
    val pans = remember(preset, refreshKey) {
        mutableStateMapOf<Int, Float>().also { m -> for (p in 1..PresetStorage.MAX_PAD) m[p] = PadPanStorage.get(preset, p) }
    }
    val tunes = remember(preset, refreshKey) {
        mutableStateMapOf<Int, Float>().also { m -> for (p in 1..PresetStorage.MAX_PAD) m[p] = PadTuneStorage.get(preset, p) }
    }

    var sel by remember(preset) { mutableStateOf<Int?>(null) }
    var tick by remember { mutableStateOf(0) }
    var dialog by remember(preset) { mutableStateOf<MixerDialogKind?>(null) }

    fun select(pad: Int) {
        if (sel != pad) {
            sel = pad
            onSelectPad(pad)
            tick++
        }
    }

    // --- PREVIEW: pad terpilih dibunyikan berulang. Efek dimulai ulang tiap pilihan/nilai berubah. ---
    LaunchedEffect(sel, tick, preset, ready) {
        val pad = sel ?: return@LaunchedEffect
        if (!ready) return@LaunchedEffect
        delay(PREVIEW_DEBOUNCE_MS)
        if (PadLoopStorage.get(preset, pad)) {
            // Pad mode LOOP: ketukan = start/stop. Hentikan loop lama (kalau ada) lalu mulai lagi
            // supaya pengaturan baru terpakai; loop-nya sendiri sudah berulang, tidak perlu dipukul terus.
            if (AudioEngine.nativeIsPadLooping(preset, pad)) {
                AudioEngine.nativeTrigger(preset, pad)
                delay(220)
            }
            AudioEngine.nativeTrigger(preset, pad)
        } else {
            while (true) {
                AudioEngine.nativeTrigger(preset, pad)
                delay(PREVIEW_INTERVAL_MS)
            }
        }
    }
    // Pindah pad / keluar mixer: kalau pad terakhir sedang loop, hentikan.
    DisposableEffect(sel, preset) {
        val pad = sel
        onDispose {
            if (pad != null && AudioEngine.nativeIsPadLooping(preset, pad)) AudioEngine.nativeTrigger(preset, pad)
        }
    }

    fun setVolume(pad: Int, v: Float) {
        vols[pad] = v
        PadVolumeStorage.set(context, preset, pad, v)
        tick++
    }
    fun setPan(pad: Int, v: Float) {
        pans[pad] = v
        PadPanStorage.set(context, preset, pad, v)
        tick++
    }
    fun setTune(pad: Int, v: Float) {
        tunes[pad] = v
        PadTuneStorage.set(context, preset, pad, v)
        tick++
    }

    Column(modifier = modifier.background(PadLineColor).padding(3.dp)) {
        // Header tipis: judul + pad terpilih + reset pad terpilih.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(30.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(CtrlPanelColor)
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("MIXER", color = PadTextColor, fontWeight = FontWeight.Bold, fontSize = 12.sp, letterSpacing = 2.sp)
            Spacer(Modifier.width(10.dp))
            Text(
                if (sel != null) "PAD $sel" else "Ketuk PAN, PITCH, atau fader untuk mulai",
                color = if (sel != null) ThemeState.accentColor else TextDim,
                fontSize = 11.sp,
                fontWeight = if (sel != null) FontWeight.SemiBold else FontWeight.Normal,
                modifier = Modifier.weight(1f)
            )
            val canReset = sel != null
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(5.dp))
                    .background(PanelColor)
                    .clickable(enabled = canReset) {
                        sel?.let { p ->
                            setPan(p, 0f); setTune(p, 0f); setVolume(p, 1f)
                        }
                    }
                    .padding(horizontal = 10.dp, vertical = 4.dp)
            ) {
                Text("RESET PAD", color = if (canReset) PadTextColor else TextDim.copy(alpha = 0.5f), fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        Spacer(Modifier.height(3.dp))

        BoxWithConstraints(modifier = Modifier.weight(1f).fillMaxWidth()) {
            val n = pads.size
            val stripW = maxOf(56.dp, (maxWidth - 3.dp * (n - 1)) / n)
            Row(
                modifier = Modifier.fillMaxHeight().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                for (pad in pads) {
                    MixerStrip(
                        pad = pad,
                        selected = sel == pad,
                        volume = vols[pad] ?: 1f,
                        pan = pans[pad] ?: 0f,
                        tune = tunes[pad] ?: 0f,
                        onSelect = { select(pad) },
                        onPan = { select(pad); dialog = MixerDialogKind.PAN },
                        onPitch = { select(pad); dialog = MixerDialogKind.PITCH },
                        onVolume = { select(pad); setVolume(pad, it) },
                        modifier = Modifier.width(stripW).fillMaxHeight()
                    )
                }
            }
        }
    }

    val dPad = sel
    if (dPad != null) {
        when (dialog) {
            MixerDialogKind.PAN -> MixerPanDialog(
                pad = dPad,
                pan = ((pans[dPad] ?: 0f) * 100f).roundToInt(),
                onChange = { setPan(dPad, it / 100f) },
                onDismiss = { dialog = null }
            )
            MixerDialogKind.PITCH -> MixerPitchDialog(
                pad = dPad,
                total = tunes[dPad] ?: 0f,
                onChange = { setTune(dPad, it) },
                onDismiss = { dialog = null }
            )
            null -> {}
        }
    }
}

// ---------------------------------------------------------------------------------------------
// STRIP PER PAD
// ---------------------------------------------------------------------------------------------

@Composable
private fun MixerStrip(
    pad: Int,
    selected: Boolean,
    volume: Float,
    pan: Float,
    tune: Float,
    onSelect: () -> Unit,
    onPan: () -> Unit,
    onPitch: () -> Unit,
    onVolume: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val accent = ThemeState.accentColor
    val shape = RoundedCornerShape(8.dp)
    Column(
        modifier = modifier
            .clip(shape)
            .background(PadColor)
            .border(BorderStroke(2.dp, if (selected) accent else Color.Transparent), shape)
            .padding(horizontal = 5.dp, vertical = 5.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // PAN
        val panInt = (pan * 100f).roundToInt()
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .height(40.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(CtrlPanelColor)
                .clickable { onPan() }
                .padding(horizontal = 6.dp, vertical = 5.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                panLabel(panInt),
                color = if (panInt == 0) TextDim else accent,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(5.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Color.White.copy(alpha = 0.07f))
                    .drawBehind {
                        val c = size.width / 2f
                        if (pan < 0f) {
                            drawRect(accent, Offset(c + pan * c, 0f), Size(-pan * c, size.height))
                        } else if (pan > 0f) {
                            drawRect(accent, Offset(c, 0f), Size(pan * c, size.height))
                        }
                        drawRect(TextDim, Offset(c - 0.75.dp.toPx(), 0f), Size(1.5.dp.toPx(), size.height))
                    }
            )
        }
        Spacer(Modifier.height(4.dp))
        // PITCH
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(28.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(CtrlPanelColor)
                .clickable { onPitch() },
            contentAlignment = Alignment.Center
        ) {
            Text(
                if (abs(tune) < 0.005f) "PITCH" else fmtTrim(tune),
                color = if (abs(tune) < 0.005f) TextDim else accent,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1
            )
        }
        Spacer(Modifier.height(4.dp))
        // FADER volume pad
        MixerFader(
            value = volume,
            onChange = onVolume,
            modifier = Modifier.weight(1f).fillMaxWidth()
        )
        Spacer(Modifier.height(4.dp))
        // LABEL (ketuk = pilih & dengarkan)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(28.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(if (selected) accent.copy(alpha = 0.22f) else CtrlPanelColor)
                .clickable { onSelect() },
            contentAlignment = Alignment.Center
        ) {
            Text(
                "PAD $pad",
                color = if (selected) accent else PadTextColor,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun MixerFader(value: Float, onChange: (Float) -> Unit, modifier: Modifier = Modifier) {
    val accent = ThemeState.accentColor
    val curChange by rememberUpdatedState(onChange)
    val thumbH = with(LocalDensity.current) { 22.dp.toPx() }
    Box(
        modifier = modifier.pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                fun setFrom(y: Float) {
                    val top = thumbH / 2f
                    val bottom = size.height - thumbH / 2f
                    if (bottom <= top) return
                    val f = ((bottom - y) / (bottom - top)).coerceIn(0f, 1f)
                    var v = f * VOL_MAX
                    if (abs(v - 1f) < 0.04f) v = 1f // "magnet" di 100%
                    curChange((v * 100f).roundToInt() / 100f)
                }
                setFrom(down.position.y)
                down.consume()
                while (true) {
                    val ev = awaitPointerEvent()
                    val c = ev.changes.firstOrNull { it.id == down.id } ?: break
                    if (!c.pressed) break
                    setFrom(c.position.y)
                    c.consume()
                }
            }
        }
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val top = thumbH / 2f
            val bottom = h - thumbH / 2f
            if (bottom <= top) return@Canvas
            val frac = (value / VOL_MAX).coerceIn(0f, 1f)
            val ty = bottom - (bottom - top) * frac
            val grooveW = 4.dp.toPx()
            drawRoundRect(CtrlPanelColor, Offset(w / 2f - grooveW / 2f, top), Size(grooveW, bottom - top), CornerRadius(grooveW / 2f))
            drawRoundRect(accent, Offset(w / 2f - grooveW / 2f, ty), Size(grooveW, bottom - ty), CornerRadius(grooveW / 2f))
            // Tanda 100%
            val y100 = bottom - (bottom - top) * (1f / VOL_MAX)
            val tickLen = 6.dp.toPx()
            val tickGap = 11.dp.toPx()
            drawLine(TextDim, Offset(w / 2f - tickGap - tickLen, y100), Offset(w / 2f - tickGap, y100), 1.5.dp.toPx())
            drawLine(TextDim, Offset(w / 2f + tickGap, y100), Offset(w / 2f + tickGap + tickLen, y100), 1.5.dp.toPx())
            // Thumb
            val tw = min(w - 4.dp.toPx(), 38.dp.toPx())
            val left = w / 2f - tw / 2f
            drawRoundRect(CtrlPanelColor, Offset(left, ty - thumbH / 2f), Size(tw, thumbH), CornerRadius(5.dp.toPx()))
            drawRoundRect(
                PadTextColor.copy(alpha = 0.55f), Offset(left, ty - thumbH / 2f), Size(tw, thumbH), CornerRadius(5.dp.toPx()),
                style = androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx())
            )
            for (i in -1..1) {
                val ly = ty + i * 4.dp.toPx()
                drawLine(PadTextColor, Offset(w / 2f - tw * 0.26f, ly), Offset(w / 2f + tw * 0.26f, ly), 1.5.dp.toPx())
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// DIALOG (SELALU DI TENGAH LAYAR) - gaya sama dengan ConfirmDialog / dialog lain di app ini
// ---------------------------------------------------------------------------------------------

@Composable
private fun MixerDialogFrame(
    title: String,
    onDismiss: () -> Unit,
    onReset: () -> Unit,
    content: @Composable () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 380.dp)
                .shadow(elevation = 18.dp, shape = RoundedCornerShape(14.dp), clip = false)
                .clip(RoundedCornerShape(14.dp))
                .background(PanelColor)
                .padding(horizontal = 18.dp, vertical = 14.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text(
                title,
                color = PadTextColor,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                letterSpacing = 2.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(10.dp))
            content()
            Spacer(Modifier.height(12.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = onReset,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = CtrlPanelColor, contentColor = PadTextColor)
                ) { Text("RESET", fontWeight = FontWeight.SemiBold, fontSize = 12.sp) }
                Button(
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = ThemeState.accentColor,
                        contentColor = ThemeState.accentTextColor
                    )
                ) { Text("SELESAI", fontWeight = FontWeight.Bold, fontSize = 12.sp) }
            }
        }
    }
}

@Composable
private fun MixerPanDialog(pad: Int, pan: Int, onChange: (Int) -> Unit, onDismiss: () -> Unit) {
    val curPan by rememberUpdatedState(pan)
    MixerDialogFrame(title = "PAN · PAD $pad", onDismiss = onDismiss, onReset = { onChange(0) }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StepButton("−") { onChange((curPan - 1).coerceIn(-100, 100)) }
            Text(
                when {
                    pan == 0 -> "C"
                    pan < 0 -> "L${-pan}"
                    else -> "R$pan"
                },
                color = if (pan == 0) PadTextColor else ThemeState.accentColor,
                fontSize = 34.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f)
            )
            StepButton("+") { onChange((curPan + 1).coerceIn(-100, 100)) }
        }
        Slider(
            value = pan.toFloat(),
            onValueChange = { onChange(it.roundToInt().coerceIn(-100, 100)) },
            valueRange = -100f..100f,
            colors = mixerSliderColors(),
            modifier = Modifier.fillMaxWidth()
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("L100", color = TextDim, fontSize = 11.sp)
            Text(
                "CENTER",
                color = if (pan == 0) ThemeState.accentColor else TextDim,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clickable { onChange(0) }
            )
            Text("R100", color = TextDim, fontSize = 11.sp)
        }
    }
}

@Composable
private fun MixerPitchDialog(pad: Int, total: Float, onChange: (Float) -> Unit, onDismiss: () -> Unit) {
    // Tune disimpan sebagai satu angka semitone desimal (-12..+12). Di dialog dipecah jadi
    // semitone bulat + fine tune (cent, -100..+100) supaya enak diatur.
    var semis by remember { mutableStateOf(total.roundToInt()) }
    var cents by remember { mutableStateOf(((total - total.roundToInt()) * 100f).roundToInt()) }
    var tab by remember { mutableStateOf(0) } // 0 = semitone, 1 = fine tune

    fun push() {
        // Jaga total tetap di rentang engine (-12..+12 semitone).
        val t = semis + cents / 100f
        if (t > 12f) cents = ((12 - semis) * 100).coerceAtLeast(-100)
        if (t < -12f) cents = ((-12 - semis) * 100).coerceAtMost(100)
        onChange((semis + cents / 100f).coerceIn(-12f, 12f))
    }
    val curSemis by rememberUpdatedState(semis)
    val curCents by rememberUpdatedState(cents)
    val totalNow = (semis + cents / 100f).coerceIn(-12f, 12f)

    MixerDialogFrame(
        title = "PITCH · PAD $pad",
        onDismiss = onDismiss,
        onReset = { semis = 0; cents = 0; push() }
    ) {
        // Tab SEMITON / FINE TUNE
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(CtrlPanelColor)
                .padding(3.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            listOf("SEMITON (±12)", "FINE TUNE (±100)").forEachIndexed { i, label ->
                val on = tab == i
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(32.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (on) ThemeState.accentColor else Color.Transparent)
                        .clickable { tab = i },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        label,
                        color = if (on) ThemeState.accentTextColor else PadTextColor,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1
                    )
                }
            }
        }
        Spacer(Modifier.height(10.dp))

        val value = if (tab == 0) semis else cents
        val limit = if (tab == 0) 12 else 100
        fun step(d: Int) {
            if (tab == 0) semis = (curSemis + d).coerceIn(-12, 12) else cents = (curCents + d).coerceIn(-100, 100)
            push()
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            StepButton("−") { step(-1) }
            Text(
                if (value > 0) "+$value" else "$value",
                color = if (value == 0) PadTextColor else ThemeState.accentColor,
                fontSize = 34.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f)
            )
            StepButton("+") { step(1) }
        }
        Slider(
            value = value.toFloat(),
            onValueChange = {
                val v = it.roundToInt().coerceIn(-limit, limit)
                if (tab == 0) semis = v else cents = v
                push()
            },
            valueRange = -limit.toFloat()..limit.toFloat(),
            colors = mixerSliderColors(),
            modifier = Modifier.fillMaxWidth()
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(if (tab == 0) "-12 ST" else "-100 CENT", color = TextDim, fontSize = 11.sp)
            Text(
                "CENTER",
                color = if (value == 0) ThemeState.accentColor else TextDim,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clickable {
                    if (tab == 0) semis = 0 else cents = 0
                    push()
                }
            )
            Text(if (tab == 0) "+12 ST" else "+100 CENT", color = TextDim, fontSize = 11.sp)
        }
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(CtrlPanelColor)
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Total", color = TextDim, fontSize = 11.sp)
            Text(
                "${fmtTrim(totalNow)} st  ($semis st, $cents c)",
                color = PadTextColor,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.End,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

// Tombol − / + : ketuk = 1 langkah, tahan = berulang makin cepat.
@Composable
private fun StepButton(label: String, onStep: () -> Unit) {
    val scope = rememberCoroutineScope()
    val cur by rememberUpdatedState(onStep)
    Box(
        modifier = Modifier
            .size(46.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(CtrlPanelColor)
            .pointerInput(Unit) {
                detectTapGestures(onPress = {
                    cur()
                    val job = scope.launch {
                        delay(380)
                        var gap = 90L
                        while (true) {
                            cur()
                            delay(gap)
                            if (gap > 35L) gap -= 5L
                        }
                    }
                    tryAwaitRelease()
                    job.cancel()
                })
            },
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = PadTextColor, fontSize = 24.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun mixerSliderColors() = SliderDefaults.colors(
    thumbColor = ThemeState.accentColor,
    activeTrackColor = ThemeState.accentColor,
    inactiveTrackColor = CtrlPanelColor
)

private fun panLabel(p: Int): String = when {
    p == 0 -> "PAN"
    p < 0 -> "L ${-p}"
    else -> "R $p"
}

// "+2.35", "-0.5", "+12", "0" (angka desimal rapi, nol di belakang dibuang).
private fun fmtTrim(v: Float): String {
    val s = String.format(Locale.US, "%+.2f", v).trimEnd('0').trimEnd('.')
    return if (s == "+0" || s == "-0" || s == "+" || s == "-") "0" else s
}

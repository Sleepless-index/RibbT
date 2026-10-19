package app.mediadl

import androidx.compose.ui.graphics.luminance

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private fun hsvOf(color: Color): FloatArray {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(color.toArgb(), hsv)
    return hsv
}

private fun colorOf(hue: Float, sat: Float, value: Float): Color =
    Color(android.graphics.Color.HSVToColor(floatArrayOf(hue.coerceIn(0f, 360f), sat.coerceIn(0f, 1f), value.coerceIn(0f, 1f))))

fun hexOf(color: Color): String = "%06X".format(color.toArgb() and 0xFFFFFF)

/** Accepts "#FF2442", "ff2442" and the short "f24" form. Anything else is not a colour yet. */
fun parseHex(text: String): Color? {
    var digits = text.trim().removePrefix("#")
    if (digits.length == 3) digits = digits.map { "$it$it" }.joinToString("")
    if (digits.length != 6 || !digits.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
    return Color(0xFF000000L or digits.toLong(16))
}

/**
 * "Accent color" row that opens a picker: presets, a saturation/brightness box, a hue bar and a hex field.
 * The colour is applied live while dragging and saved when the finger lifts.
 */
@Composable
fun AccentPicker(vm: AppViewModel) {
    val palette = LocalPalette.current
    var open by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().animateContentSize()) {
        val isDefault = hexOf(vm.accent) == hexOf(DefaultAccent)
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp)) {
            T("Accent color", size = 14.4.sp, lineHeight = 18.7.sp)
            T(
                if (isDefault) "Default" else "Custom · #${hexOf(vm.accent)}",
                size = 12.16.sp,
                color = palette.muted,
                modifier = Modifier.padding(top = 2.dp),
                lineHeight = 15.8.sp,
            )
            Row(
                Modifier.padding(top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AccentSwatch(DefaultAccent, isDefault, "Use the default accent") { vm.updateAccent(DefaultAccent, save = true) }
                if (!isDefault) AccentSwatch(vm.accent, true, "Your custom accent, #${hexOf(vm.accent)}") { open = true }
                Box(
                    Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .border(1.5.dp, palette.muted, CircleShape)
                        .clickable(onClickLabel = "Pick a custom accent color", role = Role.Button) { open = !open },
                    contentAlignment = Alignment.Center,
                ) { VIconView(VIcons.Plus, 15.dp, palette.muted) }
            }
        }
        if (open) PickerBody(vm)
    }
}

@Composable
private fun PickerBody(vm: AppViewModel) {
    val palette = LocalPalette.current
    val start = remember { hsvOf(vm.accent) }
    var hue by remember { mutableFloatStateOf(start[0]) }
    var sat by remember { mutableFloatStateOf(start[1]) }
    var value by remember { mutableFloatStateOf(start[2]) }
    var hex by remember { mutableStateOf(hexOf(vm.accent)) }

    fun preview() {
        val color = colorOf(hue, sat, value)
        hex = hexOf(color)
        vm.updateAccent(color, save = false)
    }

    fun commit() = vm.updateAccent(colorOf(hue, sat, value), save = true)

    fun adopt(color: Color) {
        val hsv = hsvOf(color)
        hue = hsv[0]; sat = hsv[1]; value = hsv[2]
        hex = hexOf(color)
        vm.updateAccent(color, save = true)
    }

    Column(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // Saturation (left to right) and brightness (bottom to top) for the chosen hue.
        var boxSize by remember { mutableStateOf(IntSize.Zero) }
        fun pickAt(point: Offset) {
            if (boxSize.width == 0 || boxSize.height == 0) return
            sat = (point.x / boxSize.width).coerceIn(0f, 1f)
            value = 1f - (point.y / boxSize.height).coerceIn(0f, 1f)
            preview()
        }
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(168.dp)
                .clip(RoundedCornerShape(12.dp))
                .onSizeChanged { boxSize = it }
                .pointerInput(Unit) { detectTapGestures { point -> pickAt(point); commit() } }
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { pickAt(it) },
                        onDragEnd = { commit() },
                        onDragCancel = { commit() },
                    ) { change, _ ->
                        change.consume()
                        pickAt(change.position)
                    }
                },
        ) {
            drawRect(Brush.horizontalGradient(listOf(Color.White, colorOf(hue, 1f, 1f))))
            drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))
            val knob = Offset(sat * size.width, (1f - value) * size.height)
            drawCircle(Color.White, radius = 11.dp.toPx(), center = knob, style = androidx.compose.ui.graphics.drawscope.Stroke(3.dp.toPx()))
            drawCircle(colorOf(hue, sat, value), radius = 8.dp.toPx(), center = knob)
        }

        // Hue bar.
        var barSize by remember { mutableStateOf(IntSize.Zero) }
        fun hueAt(x: Float) {
            if (barSize.width == 0) return
            hue = (x / barSize.width).coerceIn(0f, 1f) * 360f
            preview()
        }
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(22.dp)
                .clip(RoundedCornerShape(11.dp))
                .onSizeChanged { barSize = it }
                .pointerInput(Unit) { detectTapGestures { point -> hueAt(point.x); commit() } }
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { hueAt(it.x) },
                        onDragEnd = { commit() },
                        onDragCancel = { commit() },
                    ) { change, _ ->
                        change.consume()
                        hueAt(change.position.x)
                    }
                },
        ) {
            val stops = (0..6).map { colorOf(it * 60f, 1f, 1f) }
            drawRect(Brush.horizontalGradient(stops))
            val x = (hue / 360f) * size.width
            drawCircle(Color.White, radius = 10.dp.toPx(), center = Offset(x.coerceIn(10.dp.toPx(), size.width - 10.dp.toPx()), size.height / 2f), style = androidx.compose.ui.graphics.drawscope.Stroke(3.dp.toPx()))
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(Accent).border(1.dp, palette.line, RoundedCornerShape(10.dp)))
            Spacer(Modifier.width(10.dp))
            Row(
                Modifier
                    .weight(1f)
                    .height(40.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(palette.sep)
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                T("#", size = 14.sp, color = palette.muted, modifier = Modifier.padding(end = 2.dp))
                BasicTextField(
                    value = hex,
                    onValueChange = { raw ->
                        val cleaned = raw.removePrefix("#").filter { it.isLetterOrDigit() }.take(6).uppercase()
                        hex = cleaned
                        parseHex(cleaned)?.let { color ->
                            val hsv = hsvOf(color)
                            hue = hsv[0]; sat = hsv[1]; value = hsv[2]
                            vm.updateAccent(color, save = true)
                        }
                    },
                    singleLine = true,
                    textStyle = TextStyle(color = palette.text, fontSize = 14.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium),
                    cursorBrush = SolidColor(Accent),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { parseHex(hex)?.let { adopt(it) } }),
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.width(10.dp))
            SmallButton(
                text = "Reset",
                enabled = hexOf(vm.accent) != hexOf(DefaultAccent),
                onClick = { adopt(DefaultAccent) },
                tonal = true,
                minWidth = 64.dp,
            )
        }
    }
}

@Composable
private fun AccentSwatch(color: Color, chosen: Boolean, label: String, onClick: () -> Unit) {
    val palette = LocalPalette.current
    Box(
        Modifier
            .size(32.dp)
            .clip(CircleShape)
            .background(color)
            .border(if (chosen) 2.5.dp else 1.dp, if (chosen) palette.text else palette.line, CircleShape)
            .clickable(onClickLabel = label, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (chosen) VIconView(VIcons.Check, 14.dp, if (color.luminance() > 0.55f) Color(0xFF1C1C1E) else Color.White)
    }
}

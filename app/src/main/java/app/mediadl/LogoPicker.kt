package app.mediadl

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// The three faces of the box in the logo's 512 x 512 space, as x, y pairs. The edges that touch the
// centre are pulled in a little so a thin seam shows between the faces, like in the logo files.
private val FaceTop = floatArrayOf(256.00f, 116.00f, 373.51f, 183.98f, 256.00f, 251.96f, 138.49f, 183.98f)
private val FaceLeft = floatArrayOf(135.00f, 190.04f, 252.50f, 258.02f, 252.50f, 393.98f, 135.00f, 326.00f)
private val FaceRight = floatArrayOf(377.00f, 190.04f, 377.00f, 326.00f, 259.50f, 393.98f, 259.50f, 258.02f)

/** The logo drawn at any size. [corner] is the corner radius as a share of the width: 0.5 makes it round. */
@Composable
fun LogoMark(logo: AppLogo, modifier: Modifier = Modifier, corner: Float = 116f / 512f) {
    Canvas(modifier.aspectRatio(1f)) {
        val scale = size.width / 512f
        drawRoundRect(
            brush = Brush.linearGradient(logo.background, start = Offset.Zero, end = Offset(size.width, size.height)),
            cornerRadius = CornerRadius(size.width * corner),
        )
        fun face(points: FloatArray, color: Color) {
            val path = Path().apply {
                moveTo(points[0] * scale, points[1] * scale)
                for (i in 2 until points.size step 2) lineTo(points[i] * scale, points[i + 1] * scale)
                close()
            }
            drawPath(path, color)
        }
        face(FaceTop, logo.top)
        face(FaceLeft, logo.left)
        face(FaceRight, logo.right)
    }
}

/**
 * "App logo" setting: the choices, each drawn as the logo itself, and an Apply button that switches the
 * launcher icon. Tapping a choice only highlights it, so nothing moves on the home screen until Apply.
 */
@Composable
fun LogoPicker(vm: AppViewModel) {
    val palette = LocalPalette.current
    var pending by rememberSaveable { mutableStateOf(vm.appLogo) }
    val shown = AppLogo.fromKey(pending)
    val changed = pending != vm.appLogo

    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp)) {
        T("App logo", size = 14.4.sp, lineHeight = 18.7.sp)
        // Choices.
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            AppLogo.entries.forEach { logo ->
                LogoTile(
                    logo = logo,
                    isSelected = logo == shown,
                    onClick = { pending = logo.key },
                    modifier = Modifier.weight(1f),
                )
            }
        }

        Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.End) {
            SmallButton(text = "Apply", enabled = changed, onClick = { vm.updateAppLogo(pending) })
        }
    }
}

@Composable
private fun LogoTile(logo: AppLogo, isSelected: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val palette = LocalPalette.current
    val shape = RoundedCornerShape(12.dp)
    val source = remember { MutableInteractionSource() }
    Column(
        modifier
            .clip(shape)
            .background(if (isSelected) Accent.copy(alpha = 0.08f) else palette.bg)
            .border(1.5.dp, if (isSelected) Accent else Color.Transparent, shape)
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        LogoMark(logo, Modifier.size(48.dp))
        T(
            logo.label,
            modifier = Modifier.padding(top = 8.dp),
            size = 12.sp,
            weight = FontWeight.SemiBold,
            color = if (isSelected) Accent else palette.text,
            maxLines = 1,
        )
    }
}

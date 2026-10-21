package app.mediadl

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** The "Supported services" subpage of Settings: every service in one card. */
@Composable
fun ServicesList(vm: AppViewModel) {
    val palette = LocalPalette.current
    if (vm.platforms.isEmpty()) {
        T(
            if (vm.online == false) "Start the server to see the supported services." else "Loading…",
            modifier = Modifier.padding(start = 6.dp),
            size = 13.44.sp,
            color = palette.muted,
        )
        return
    }
    CardBox(Modifier.fillMaxWidth()) {
        Column {
            vm.platforms.forEachIndexed { index, platform ->
                if (index > 0) Separator()
                ServiceRow(platform)
            }
            Separator()
            T(
                "Listing a service doesn’t imply any affiliation or endorsement.",
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                size = 12.16.sp,
                color = palette.muted,
                lineHeight = 18.2.sp,
            )
        }
    }
}

@Composable
private fun ServiceRow(platform: Platform) {
    val palette = LocalPalette.current
    val context = LocalContext.current
    val logo = context.resources.getIdentifier("logo_${platform.name}", "drawable", context.packageName)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(Accent.copy(alpha = 0.10f)),
            contentAlignment = Alignment.Center,
        ) {
            if (logo != 0) {
                Image(
                    painter = painterResource(logo),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.size(44.dp),
                )
            } else {
                T(platform.label.take(1).uppercase(), size = 16.8.sp, color = Accent, weight = FontWeight.Bold)
            }
        }
        Column(Modifier.weight(1f).padding(start = 14.dp)) {
            T(platform.label, size = 15.2.sp, weight = FontWeight.SemiBold, lineHeight = 21.3.sp)
            T(platform.display.joinToString(", "), size = 12.16.sp, color = palette.muted, lineHeight = 16.4.sp)
        }
        if (platform.subtitles) {
            Box(
                Modifier
                    .padding(start = 8.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Accent.copy(alpha = 0.12f))
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            ) { T("Subtitles", size = 11.5.sp, color = Accent, weight = FontWeight.SemiBold, maxLines = 1) }
        }
    }
}

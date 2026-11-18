package ai.abhify.android.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Timings of the launch reveal (ms). The app starts underneath and nothing waits for this. */
object SplashTiming {
    const val LOGO_IN = 320L          // logo fades in and settles (scale 0.94 → 1)
    const val RING_DELAY = 120L
    const val RING = 300L             // one brand-gradient ring sweep behind the logo
    const val TEXT_DELAY = 400L
    const val TEXT_IN = 200L          // name and tagline fade in
    const val HOLD = 150L
    /** Time before the app is shown (tap skips it). With the 150 ms cross-fade the whole reveal is at most 900 ms. */
    const val TOTAL = TEXT_DELAY + TEXT_IN + HOLD
    const val FADE_OUT = 150
    /** With "Remove animations" on: a static logo, briefly. */
    const val REDUCED = 300L
}

/**
 * 4.5 launch reveal, once per cold start right after Android's system splash (same background and mark, so the two read
 * as one launch). Logo → one ring sweep → "Abhify.AI" and the tagline, then the app. It never waits for Google Drive, a
 * server or the AI model; tapping skips it; with reduced motion it is a static lockup for 300 ms.
 */
@Composable
fun BrandSplash(onDone: () -> Unit) {
    val reduced = reducedMotion()
    val logo = remember { Animatable(if (reduced) 1f else 0f) }
    val ring = remember { Animatable(if (reduced) 1f else 0f) }
    val text = remember { Animatable(if (reduced) 1f else 0f) }
    LaunchedEffect(Unit) {
        if (reduced) { delay(SplashTiming.REDUCED); onDone(); return@LaunchedEffect }
        launch { logo.animateTo(1f, tween(SplashTiming.LOGO_IN.toInt(), easing = Motion.Emphasized)) }
        launch { delay(SplashTiming.RING_DELAY); ring.animateTo(1f, tween(SplashTiming.RING.toInt(), easing = Motion.Standard)) }
        launch { delay(SplashTiming.TEXT_DELAY); text.animateTo(1f, tween(SplashTiming.TEXT_IN.toInt())) }
        delay(SplashTiming.TOTAL)
        onDone()
    }
    BrandBackground(Modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClickLabel = "Skip", onClick = onDone)
        .semantics { contentDescription = "${BrandText.NAME}. ${BrandText.TAGLINE}" }) {
        Box(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 32.dp), contentAlignment = Alignment.Center) {
            Column(Modifier.widthIn(max = 420.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    // the ring sweeps once and fades as it closes: a single, quiet brand moment (no glow)
                    if (!reduced) Canvas(Modifier.size(148.dp).alpha(1f - 0.6f * ring.value)) {
                        drawArc(Brand.Accent, startAngle = -90f, sweepAngle = 360f * ring.value, useCenter = false,
                            style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round))
                    }
                    Box(Modifier.alpha(logo.value).scale(0.94f + 0.06f * logo.value)) { Logo(120) }
                }
                Text(BrandText.NAME, style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.onBackground,
                    textAlign = TextAlign.Center, modifier = Modifier.alpha(text.value))
                Text(BrandText.TAGLINE, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center, modifier = Modifier.alpha(text.value))
            }
        }
    }
}

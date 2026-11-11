package com.thorctl.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.RocketLaunch
import androidx.compose.material.icons.rounded.SportsEsports
import androidx.compose.material.icons.rounded.VerticalAlignBottom
import androidx.compose.material.icons.rounded.VerticalAlignTop
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

enum class HudIcon { CONTROLLER, MOVE_DOWN, MOVE_UP, GAME, INFO, BRIGHTNESS }

/** [level] (0..1) shows a level bar instead of [text] (brightness). */
data class HudMessage(
    val icon: HudIcon,
    val text: String,
    val displayId: Int,
    val level: Float? = null,
    val stamp: Long = System.nanoTime(),
)

/**
 * Confirmation pill for AYN shortcuts. Pops in on a bouncy spring while its icon container
 * morphs from a circle into a turning 9-sided cookie (M3 Expressive shape language).
 */
@Composable
fun HudPill(state: StateFlow<HudMessage?>, onGone: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val msg by state.collectAsState()
    var last by remember { mutableStateOf<HudMessage?>(null) }
    if (msg != null) last = msg
    val gone by rememberUpdatedState(onGone)
    val pop = remember { Animatable(0f) }
    val morphP = remember { Animatable(0f) }
    val morph = rememberMorph(MaterialShapes.Circle, MaterialShapes.Cookie9Sided)
    var shownIcon by remember { mutableStateOf<HudIcon?>(null) }

    LaunchedEffect(msg?.stamp) {
        val cur = msg
        if (cur != null) {
            // Another step on a level pill that is already up: only the bar moves.
            val update = cur.level != null && shownIcon == cur.icon && pop.value > 0.5f
            shownIcon = cur.icon
            launch { pop.animateTo(1f, spring(dampingRatio = 0.55f, stiffness = 520f)) }
            if (update) return@LaunchedEffect
            morphP.snapTo(0f)
            morphP.animateTo(1f, spring(dampingRatio = 0.5f, stiffness = 260f))
        } else if (last != null) {
            pop.animateTo(0f, tween(170, easing = FastOutLinearInEasing))
            shownIcon = null
            gone()
        }
    }

    val m = last ?: return
    Row(
        Modifier
            .padding(12.dp)
            .graphicsLayer {
                val v = pop.value
                alpha = v.coerceIn(0f, 1f)
                scaleX = 0.6f + 0.4f * v
                scaleY = 0.6f + 0.4f * v
                translationY = (1f - v) * -24.dp.toPx()
                transformOrigin = TransformOrigin(0.5f, 0f)
            }
            .shadow(12.dp, RoundedCornerShape(50))
            .clip(RoundedCornerShape(50))
            .background(cs.surfaceContainerHigh)
            .padding(start = 8.dp, end = 22.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(42.dp)
                .graphicsLayer { rotationZ = (1f - morphP.value) * -120f }
                .clip(MorphShape(morph, morphP.value))
                .background(cs.primary),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                when (m.icon) {
                    HudIcon.CONTROLLER -> Icons.Rounded.SportsEsports
                    HudIcon.MOVE_DOWN -> Icons.Rounded.VerticalAlignBottom
                    HudIcon.MOVE_UP -> Icons.Rounded.VerticalAlignTop
                    HudIcon.GAME -> Icons.Rounded.RocketLaunch
                    HudIcon.INFO -> Icons.Rounded.Info
                    HudIcon.BRIGHTNESS -> Icons.Rounded.LightMode
                },
                null,
                tint = cs.onPrimary,
                modifier = Modifier.size(22.dp).graphicsLayer { rotationZ = (1f - morphP.value) * 120f },
            )
        }
        Spacer(Modifier.width(14.dp))
        val level = m.level
        if (level != null) {
            LevelBar(level)
        } else {
            AnimatedContent(m.text, transitionSpec = { fadeIn(tween(120)) togetherWith fadeOut(tween(90)) }, label = "hud") { t ->
                Text(t, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Medium, fontSize = 16.sp), color = cs.onSurface)
            }
        }
    }
}

/** A level shown as a linear scale (display only, not a slider); glides to each new value. */
@Composable
private fun LevelBar(level: Float) {
    val cs = MaterialTheme.colorScheme
    val shown by animateFloatAsState(level.coerceIn(0f, 1f), spring(dampingRatio = 1f, stiffness = 500f), label = "level")
    Box(
        Modifier
            .padding(end = 4.dp)
            .width(168.dp)
            .height(8.dp)
            .clip(RoundedCornerShape(50))
            .background(cs.onSurface.copy(alpha = 0.12f)),
    ) {
        Box(
            Modifier
                .fillMaxHeight()
                .fillMaxWidth(shown)
                .clip(RoundedCornerShape(50))
                .background(cs.primary),
        )
    }
}

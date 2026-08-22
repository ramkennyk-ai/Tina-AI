package com.tina.character

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.airbnb.lottie.compose.*

/**
 * Renders TINA at the given expression. Looks for a matching Lottie asset
 * at assets/tina/<file>; if it's not there yet (no art produced), falls
 * back to a colored circle + emoji so the reaction engine's behavior is
 * visible and testable before any animation assets exist.
 */
@Composable
fun TinaCharacterView(
    expression: TinaExpression,
    modifier: Modifier = Modifier,
    size: Dp = 100.dp
) {
    val context = LocalContext.current
    val assetPath = "tina/${expression.assetFileName}"
    val assetExists = remember(assetPath) {
        try {
            context.assets.open(assetPath).close()
            true
        } catch (e: Exception) {
            false
        }
    }

    Box(modifier = modifier.size(size), contentAlignment = Alignment.Center) {
        AnimatedContent(targetState = expression, label = "tina_expression") { expr ->
            if (assetExists) {
                LottieTinaView(expr, size)
            } else {
                FallbackTinaView(expr, size)
            }
        }
    }
}

@Composable
private fun LottieTinaView(expression: TinaExpression, size: Dp) {
    val composition by rememberLottieComposition(
        LottieCompositionSpec.Asset("tina/${expression.assetFileName}")
    )
    val progress by animateLottieCompositionAsState(
        composition = composition,
        iterations = if (expression == TinaExpression.IDLE || expression == TinaExpression.LISTENING)
            LottieConstants.IterateForever else 1
    )
    LottieAnimation(
        composition = composition,
        progress = { progress },
        modifier = Modifier.size(size)
    )
}

@Composable
private fun FallbackTinaView(expression: TinaExpression, size: Dp) {
    Box(
        modifier = Modifier
            .size(size)
            .background(Color(0xFF6C5CE7).copy(alpha = 0.85f), CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Text(text = expression.fallbackEmoji, style = MaterialTheme.typography.displayMedium)
    }
}

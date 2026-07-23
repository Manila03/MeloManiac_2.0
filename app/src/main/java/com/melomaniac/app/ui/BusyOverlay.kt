package com.melomaniac.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.melomaniac.app.ui.theme.Accent
import com.melomaniac.app.ui.theme.Background
import com.melomaniac.app.ui.theme.TextPrimary

@Composable
fun BusyOverlay(message: String) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Background.copy(alpha = 0.82f))
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
                onClick = {},
            ),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(
                color = Accent,
                modifier = Modifier.size(48.dp),
            )
            Text(
                text = message,
                color = TextPrimary,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 16.dp, start = 32.dp, end = 32.dp),
            )
        }
    }
}

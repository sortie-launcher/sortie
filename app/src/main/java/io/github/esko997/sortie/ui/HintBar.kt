package io.github.esko997.sortie.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** Controller hints wrap as whole button/action pairs, never as narrow columns of letters. (Throwaway: emulator trigger proof.) */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HintBar(vararg hints: Pair<String, String>, modifier: Modifier = Modifier) {
    FlowRow(
        modifier.fillMaxWidth().background(Color(0xFF0B0D10)).padding(horizontal = 14.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        hints.forEach { (button, action) ->
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(
                    button,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    color = Ink,
                    modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(Color(0xFF9AA3AD)).padding(horizontal = 5.dp, vertical = 1.dp)
                )
                Text(action, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
        }
    }
}

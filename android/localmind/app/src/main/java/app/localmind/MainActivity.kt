package app.localmind

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val OledScheme = darkColorScheme(
    primary = Color(0xFFB7C8FF),
    onPrimary = Color(0xFF102B60),
    secondary = Color(0xFFBFC7DC),
    background = Color(0xFF000000),
    surface = Color(0xFF000000),
    surfaceVariant = Color(0xFF17191F),
    onBackground = Color(0xFFF2F0F7),
    onSurface = Color(0xFFF2F0F7)
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = OledScheme) {
                Surface(Modifier.fillMaxSize(), color = Color.Black) {
                    HomeScreen()
                }
            }
        }
    }
}

@Composable
private fun HomeScreen() {
    Column(
        modifier = Modifier.fillMaxSize().background(Color.Black).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        Spacer(Modifier.height(8.dp))
        Text("LocalMind", fontSize = 32.sp, fontWeight = FontWeight.Bold, color = Color.White)
        Text(
            "Your models. Your device. Your network.",
            color = Color(0xFFB7B7C2),
            style = MaterialTheme.typography.bodyLarge
        )
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF111111))
        ) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("MODEL", color = Color(0xFFB7C8FF), style = MaterialTheme.typography.labelMedium)
                Text("No model loaded", color = Color.White, style = MaterialTheme.typography.titleLarge)
                Text("Stage 1 · App foundation", color = Color(0xFFB7B7C2))
                Button(onClick = { }, modifier = Modifier.fillMaxWidth()) {
                    Text("Model manager — coming next")
                }
            }
        }
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF111111))
        ) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("LAN API", color = Color(0xFFB7C8FF), style = MaterialTheme.typography.labelMedium)
                Text("Stopped", color = Color.White, style = MaterialTheme.typography.titleLarge)
                Text(
                    "OpenAI-compatible API and API-key protection come after local inference works.",
                    color = Color(0xFFB7B7C2),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Start,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Local inference · GGUF · llama.cpp", color = Color(0xFF858590))
        }
    }
}

package app.localmind

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Router
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val OledBlack = Color(0xFF000000)
private val SurfaceDark = Color(0xFF111114)
private val Accent = Color(0xFFB8C7FF)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Accent,
                    onPrimary = Color(0xFF142044),
                    background = OledBlack,
                    surface = SurfaceDark,
                    onBackground = Color(0xFFF2F0F7),
                    onSurface = Color(0xFFF2F0F7),
                    secondary = Color(0xFFC4C6D0)
                )
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = OledBlack
                ) {
                    LocalMindHome()
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LocalMindHome() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(OledBlack)
            .padding(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(
                text = "LocalMind",
                fontSize = 32.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
            Text(
                text = "مدل‌های هوش مصنوعی، روی دستگاه خودت",
                style = MaterialTheme.typography.bodyLarge,
                color = Color(0xFFB9BAC4)
            )
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(28.dp),
            colors = CardDefaults.cardColors(containerColor = SurfaceDark)
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Memory, contentDescription = null, tint = Accent)
                    Text(
                        "  مدل فعال",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Text(
                    "هنوز مدلی اضافه نشده",
                    style = MaterialTheme.typography.titleLarge,
                    color = Color.White
                )
                Text(
                    "یک فایل GGUF از حافظه انتخاب کن یا بعداً مستقیماً از Hugging Face دانلودش کن.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFFB9BAC4)
                )
                Button(
                    onClick = { },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp)
                ) {
                    Icon(Icons.Rounded.Storage, contentDescription = null)
                    Text("  مدیریت مدل‌ها")
                }
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(28.dp),
            colors = CardDefaults.cardColors(containerColor = SurfaceDark)
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Router, contentDescription = null, tint = Accent)
                    Text(
                        "  سرور API محلی",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .background(Color(0xFF4B4D55), RoundedCornerShape(50))
                            .padding(horizontal = 10.dp, vertical = 5.dp)
                    ) {
                        Text("راه‌اندازی نشده", style = MaterialTheme.typography.labelMedium)
                    }
                }
                Text(
                    "پس از اتصال موتور llama.cpp، می‌توانی API سازگار با OpenAI را روی شبکه محلی اجرا کنی.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFFB9BAC4)
                )
                OutlinedButton(
                    onClick = { },
                    enabled = false,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp)
                ) {
                    Text("شروع سرور در مرحله بعد فعال می‌شود")
                }
            }
        }

        Spacer(modifier = Modifier.height(2.dp))
        Text(
            "نسخه آغازین · اجرای مدل و API در مراحل بعد اضافه می‌شوند",
            style = MaterialTheme.typography.labelMedium,
            color = Color(0xFF777984)
        )
    }
}

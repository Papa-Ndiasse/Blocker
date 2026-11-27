package com.blocker.app.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blocker.app.BlockerApp
import com.blocker.app.guard.CooldownLockoutEngine
import com.blocker.app.ui.theme.BgDark
import com.blocker.app.ui.theme.BlockerTheme
import com.blocker.app.ui.theme.CrimsonAlert
import com.blocker.app.ui.theme.SurfaceDark
import com.blocker.app.ui.theme.TextPrimary
import com.blocker.app.ui.theme.TextSecondary
import kotlinx.coroutines.delay
import java.util.Locale

/**
 * Fullscreen cooldown lockout screen shown when 3 content blocks occur within 10 minutes.
 * Escalates 5 -> 10 -> 20 -> 40 -> 60 min within the same hour, then resets to 5 min.
 * Always keeps emergency calling (112) accessible for safety.
 */
class LockoutActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val app = application as BlockerApp
        val initialState = app.preferences.loadLockoutState()
        val now = System.currentTimeMillis()
        if (!CooldownLockoutEngine.isLockoutActive(initialState, now)) {
            finish()
            return
        }

        val level = initialState.escalationLevel
        val durationMin = CooldownLockoutEngine.durationMinutesForLevel(level)

        setContent {
            BlockerTheme {
                // Disable Back button while lockout is active
                BackHandler(enabled = true) { }

                var remainingMs by remember {
                    mutableLongStateOf(CooldownLockoutEngine.remainingMs(initialState, System.currentTimeMillis()))
                }

                LaunchedEffect(Unit) {
                    while (true) {
                        val currentState = app.preferences.loadLockoutState()
                        val left = CooldownLockoutEngine.remainingMs(currentState, System.currentTimeMillis())
                        remainingMs = left
                        if (left <= 0L) {
                            finish()
                            break
                        }
                        delay(1000L)
                    }
                }

                val totalSeconds = (remainingMs / 1000L).coerceAtLeast(0L)
                val minutes = totalSeconds / 60L
                val seconds = totalSeconds % 60L
                val formattedCountdown = String.format(Locale.FRANCE, "%02d:%02d", minutes, seconds)

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(BgDark)
                        .padding(28.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = null,
                        tint = CrimsonAlert,
                        modifier = Modifier.size(68.dp)
                    )
                    Spacer(modifier = Modifier.height(18.dp))
                    Text(
                        text = "Pause forcée active",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "3 blocages enchaînés en moins de 10 min",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(24.dp))

                    Card(
                        colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                        shape = RoundedCornerShape(20.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 24.dp, horizontal = 16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = formattedCountdown,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 52.sp,
                                fontWeight = FontWeight.Bold,
                                color = CrimsonAlert
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Palier ${level + 1}/5 — Verrouillage de $durationMin min",
                                style = MaterialTheme.typography.labelLarge,
                                color = Color.White,
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "Dans la même heure : 5 → 10 → 20 → 40 → 60 min.\nRetour à 5 min après 1 heure sans nouveau verrouillage.",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary,
                                textAlign = TextAlign.Center
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(32.dp))
                    OutlinedButton(
                        onClick = {
                            runCatching {
                                startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:112")))
                            }
                        }
                    ) {
                        Text("Appel d'urgence (112)", color = TextSecondary, fontSize = 12.sp)
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        isVisible = true
    }

    override fun onPause() {
        isVisible = false
        super.onPause()
    }

    companion object {
        @Volatile
        var isVisible: Boolean = false
    }
}

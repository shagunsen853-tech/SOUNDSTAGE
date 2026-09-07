package com.example.soundstage

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            SoundstageApp(this)
        }
    }
}

@Composable
fun SoundstageApp(context: Context) {

    val prefs = remember {
        context.getSharedPreferences("soundstage_prefs", Context.MODE_PRIVATE)
    }

    var currentScreen by remember {
        mutableStateOf("welcome")
    }

    var maria by remember {
        mutableStateOf(prefs.getBoolean("maria", true))
    }

    var leah by remember {
        mutableStateOf(prefs.getBoolean("leah", true))
    }

    var alex by remember {
        mutableStateOf(prefs.getBoolean("alex", false))
    }

    var daniel by remember {
        mutableStateOf(prefs.getBoolean("daniel", false))
    }

    fun savePerson(name: String, value: Boolean) {
        prefs.edit().putBoolean(name, value).apply()
    }

    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Color(0xFF6C5CE7),
            background = Color(0xFF101010),
            surface = Color(0xFF1A1A1A)
        )
    ) {

        when (currentScreen) {

            "welcome" -> {
                WelcomeScreen(
                    onGetStarted = {
                        currentScreen = "pair"
                    }
                )
            }

            "pair" -> {
                PairScreen(
                    onContinue = {
                        currentScreen = "people"
                    },
                    onBack = {
                        currentScreen = "welcome"
                    }
                )
            }

            "people" -> {
                PeopleScreen(
                    maria = maria,
                    leah = leah,
                    alex = alex,
                    daniel = daniel,

                    onMaria = {
                        maria = !maria
                        savePerson("maria", maria)
                    },

                    onLeah = {
                        leah = !leah
                        savePerson("leah", leah)
                    },

                    onAlex = {
                        alex = !alex
                        savePerson("alex", alex)
                    },

                    onDaniel = {
                        daniel = !daniel
                        savePerson("daniel", daniel)
                    },

                    onSoundstage = {
                        currentScreen = "soundstage"
                    }
                )
            }

            "soundstage" -> {
                SoundstageScreen(
                    maria = maria,
                    leah = leah,
                    alex = alex,
                    daniel = daniel,

                    onMaria = {
                        maria = !maria
                        savePerson("maria", maria)
                    },

                    onLeah = {
                        leah = !leah
                        savePerson("leah", leah)
                    },

                    onAlex = {
                        alex = !alex
                        savePerson("alex", alex)
                    },

                    onDaniel = {
                        daniel = !daniel
                        savePerson("daniel", daniel)
                    },

                    onPeopleList = {
                        currentScreen = "people"
                    }
                )
            }
        }
    }
}

@Composable
fun WelcomeScreen(
    onGetStarted: () -> Unit
) {

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF101010))
            .padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {

        Box(
            modifier = Modifier
                .size(110.dp)
                .background(
                    Color(0xFF6C5CE7),
                    CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "S",
                color = Color.White,
                fontSize = 48.sp,
                fontWeight = FontWeight.Bold
            )
        }

        Spacer(modifier = Modifier.height(30.dp))

        Text(
            text = "Welcome to Soundstage",
            color = Color.White,
            fontSize = 30.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(15.dp))

        Text(
            text = "Hear the people who matter.",
            color = Color.LightGray,
            fontSize = 18.sp,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(45.dp))

        Button(
            onClick = onGetStarted,
            modifier = Modifier
                .fillMaxWidth()
                .height(55.dp),
            shape = RoundedCornerShape(14.dp)
        ) {
            Text(
                text = "Get Started",
                fontSize = 18.sp
            )
        }
    }
}

@Composable
fun PairScreen(
    onContinue: () -> Unit,
    onBack: () -> Unit
) {

    var paired by remember {
        mutableStateOf(false)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF101010))
            .padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {

        Text(
            text = "← Back",
            color = Color.LightGray,
            modifier = Modifier
                .align(Alignment.Start)
                .clickable {
                    onBack()
                }
        )

        Spacer(modifier = Modifier.height(80.dp))

        Text(
            text = "Pair Hearing Aids",
            color = Color.White,
            fontSize = 30.sp,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(15.dp))

        Text(
            text = "Connect your hearing aids to continue.",
            color = Color.Gray,
            fontSize = 16.sp,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(50.dp))

        Box(
            modifier = Modifier
                .size(130.dp)
                .background(
                    Color(0xFF1D1D1D),
                    CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "🎧",
                fontSize = 55.sp
            )
        }

        Spacer(modifier = Modifier.height(40.dp))

        Button(
            onClick = {
                paired = true
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(55.dp)
        ) {
            Text(
                text = if (paired)
                    "Hearing Aids Paired ✓"
                else
                    "Pair Hearing Aids"
            )
        }

        Spacer(modifier = Modifier.height(20.dp))

        if (paired) {

            Button(
                onClick = onContinue,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(55.dp)
            ) {
                Text("Continue")
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        Text(
            text = "Demo mode • No real Bluetooth connection",
            color = Color.Gray,
            fontSize = 12.sp
        )
    }
}

@Composable
fun PeopleScreen(
    maria: Boolean,
    leah: Boolean,
    alex: Boolean,
    daniel: Boolean,
    onMaria: () -> Unit,
    onLeah: () -> Unit,
    onAlex: () -> Unit,
    onDaniel: () -> Unit,
    onSoundstage: () -> Unit
) {

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF101010))
            .padding(20.dp)
    ) {

        Text(
            text = "People List",
            color = Color.White,
            fontSize = 30.sp,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Choose who stays in your conversation",
            color = Color.Gray,
            fontSize = 15.sp
        )

        Spacer(modifier = Modifier.height(30.dp))

        PersonRow(
            name = "Maria",
            enabled = maria,
            onToggle = onMaria
        )

        Spacer(modifier = Modifier.height(12.dp))

        PersonRow(
            name = "Leah",
            enabled = leah,
            onToggle = onLeah
        )

        Spacer(modifier = Modifier.height(12.dp))

        PersonRow(
            name = "Alex",
            enabled = alex,
            onToggle = onAlex
        )

        Spacer(modifier = Modifier.height(12.dp))

        PersonRow(
            name = "Daniel",
            enabled = daniel,
            onToggle = onDaniel
        )

        Spacer(modifier = Modifier.weight(1f))

        Button(
            onClick = onSoundstage,
            modifier = Modifier
                .fillMaxWidth()
                .height(55.dp)
        ) {
            Text(
                text = "Go to Soundstage",
                fontSize = 17.sp
            )
        }
    }
}

@Composable
fun PersonRow(
    name: String,
    enabled: Boolean,
    onToggle: () -> Unit
) {

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                Color(0xFF1B1B1B),
                RoundedCornerShape(15.dp)
            )
            .padding(18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {

        Column {

            Text(
                text = name,
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )

            Text(
                text = if (enabled)
                    "In your conversation"
                else
                    "Muted",
                color = if (enabled)
                    Color(0xFF71E5D5)
                else
                    Color.Gray,
                fontSize = 13.sp
            )
        }

        Switch(
            checked = enabled,
            onCheckedChange = {
                onToggle()
            }
        )
    }
}

@Composable
fun SoundstageScreen(
    maria: Boolean,
    leah: Boolean,
    alex: Boolean,
    daniel: Boolean,
    onMaria: () -> Unit,
    onLeah: () -> Unit,
    onAlex: () -> Unit,
    onDaniel: () -> Unit,
    onPeopleList: () -> Unit
) {

    val activeCount =
        listOf(maria, leah, alex, daniel).count { it }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF101010))
            .padding(20.dp)
    ) {

        Text(
            text = "Soundstage",
            color = Color.White,
            fontSize = 30.sp,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(6.dp))

        Text(
            text = "$activeCount people in your conversation",
            color = Color.Gray,
            fontSize = 14.sp
        )

        Spacer(modifier = Modifier.height(30.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentAlignment = Alignment.Center
        ) {

            Box(
                modifier = Modifier
                    .size(300.dp)
                    .background(
                        Color(0xFF075B50),
                        CircleShape
                    )
            ) {

                PersonBubble(
                    name = "Maria",
                    active = maria,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .offset(
                            x = 35.dp,
                            y = 50.dp
                        ),
                    onClick = onMaria
                )

                PersonBubble(
                    name = "Leah",
                    active = leah,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .offset(
                            x = (-35).dp,
                            y = 50.dp
                        ),
                    onClick = onLeah
                )

                PersonBubble(
                    name = "Alex",
                    active = alex,
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .offset(x = 15.dp),
                    onClick = onAlex
                )

                PersonBubble(
                    name = "Daniel",
                    active = daniel,
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .offset(x = (-15).dp),
                    onClick = onDaniel
                )

                Box(
                    modifier = Modifier
                        .size(75.dp)
                        .align(Alignment.BottomCenter)
                        .offset(y = (-30).dp)
                        .background(
                            Color(0xFF6C5CE7),
                            CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {

                    Text(
                        text = "You",
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Color(0xFF1B1B1B),
                    RoundedCornerShape(15.dp)
                )
                .padding(16.dp)
        ) {

            Text(
                text = "🎤 Maria",
                color = Color.White,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(5.dp))

            Text(
                text = "\"...grab the table by the window?\"",
                color = Color.LightGray
            )
        }

        Spacer(modifier = Modifier.height(15.dp))

        Text(
            text = "Tap a person to keep or mute",
            color = Color.Gray,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
            fontSize = 13.sp
        )

        Spacer(modifier = Modifier.height(15.dp))

        Button(
            onClick = onPeopleList,
            modifier = Modifier
                .fillMaxWidth()
                .height(55.dp)
        ) {
            Text(
                text = "Back to People List"
            )
        }
    }
}

@Composable
fun PersonBubble(
    name: String,
    active: Boolean,
    modifier: Modifier,
    onClick: () -> Unit
) {

    Column(
        modifier = modifier
            .clickable {
                onClick()
            },
        horizontalAlignment = Alignment.CenterHorizontally
    ) {

        Box(
            modifier = Modifier
                .size(62.dp)
                .background(
                    if (active)
                        Color(0xFF0B7A69)
                    else
                        Color.DarkGray,
                    CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {

            Text(
                text = name,
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )
        }

        if (!active) {
            Text(
                text = "Muted",
                color = Color.Gray,
                fontSize = 11.sp
            )
        }
    }
}
package com.timetracker.ui

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.timetracker.TrackerApplication
import com.timetracker.core.model.Tag
import com.timetracker.core.model.TimeEntry
import com.timetracker.sync.SyncWorker
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import java.util.UUID

class TrackerViewModel(app: Application) : AndroidViewModel(app) {
    private val repository = (app as TrackerApplication).repository
    val tags = repository.tags.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val entries = repository.entries.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    var signedIn by mutableStateOf(repository.signedIn)
    val email = repository.sessionEmail
    var serverUrl by mutableStateOf(repository.serverUrl)
    var error by mutableStateOf("")

    fun signIn(email: String, password: String, register: Boolean) {
        viewModelScope.launch {
            try {
                repository.updateServerUrl(serverUrl)
                repository.login(email, password, register)
                signedIn = true
                error = ""
                SyncWorker.enqueue(getApplication())
            } catch (exception: Exception) {
                error = exception.message ?: "Sign in failed."
            }
        }
    }

    fun signOut() {
        viewModelScope.launch {
            repository.signOut()
            signedIn = false
        }
    }

    fun start(title: String, description: String?, tagIds: List<String>) {
        viewModelScope.launch {
            repository.startTimer(title, description, tagIds)
            SyncWorker.enqueue(getApplication())
        }
    }

    fun stop(entry: TimeEntry) {
        viewModelScope.launch {
            repository.saveEntry(entry.copy(endedAt = Instant.now().toString()))
            SyncWorker.enqueue(getApplication())
        }
    }

    fun saveEntry(entry: TimeEntry) {
        viewModelScope.launch {
            repository.saveEntry(entry)
            SyncWorker.enqueue(getApplication())
        }
    }

    fun saveTag(name: String, description: String, color: String) {
        viewModelScope.launch {
            repository.saveTag(
                Tag(
                    id = UUID.randomUUID().toString(),
                    name = name,
                    description = description.ifBlank { null },
                    color = color,
                    updatedAt = Instant.now().toString(),
                    deviceId = "phone",
                ),
            )
            SyncWorker.enqueue(getApplication())
        }
    }
}

private val colors = lightColorScheme(
    primary = Color(0xFFC2410C),
    background = Color(0xFFF4F1EA),
    surface = Color.White,
    onBackground = Color(0xFF1C1917),
    onSurface = Color(0xFF1C1917),
)

@Composable
fun TrackerApp(model: TrackerViewModel = viewModel()) {
    MaterialTheme(colorScheme = colors) {
        var screen by remember { mutableIntStateOf(0) }
        if (!model.signedIn) {
            AuthScreen(model)
            return@MaterialTheme
        }
        Scaffold(
            containerColor = colors.background,
            bottomBar = {
                NavigationBar {
                    listOf("Timer", "Log", "Tags").forEachIndexed { index, label ->
                        NavigationBarItem(selected = screen == index, onClick = { screen = index }, label = { Text(label) }, icon = {})
                    }
                }
            },
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).padding(20.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                when (screen) {
                    0 -> TimerScreen(model)
                    1 -> LogScreen(model)
                    else -> TagsScreen(model)
                }
            }
        }
    }
}

@Composable
private fun AuthScreen(model: TrackerViewModel) {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().background(colors.background).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Time Tracker", fontFamily = FontFamily.Serif, fontSize = 36.sp)
        Text("Changes are saved on the phone and sync when a connection comes back.")
        OutlinedTextField(model.serverUrl, { model.serverUrl = it }, label = { Text("Server") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(email, { email = it }, label = { Text("Email") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(password, { password = it }, label = { Text("Password") }, modifier = Modifier.fillMaxWidth())
        if (model.error.isNotBlank()) Text(model.error, color = Color(0xFFB91C1C))
        Button(onClick = { model.signIn(email, password, false) }, modifier = Modifier.fillMaxWidth()) { Text("Sign in") }
        TextButton(onClick = { model.signIn(email, password, true) }) { Text("Create account") }
    }
}

@Composable
private fun TimerScreen(model: TrackerViewModel) {
    val entries by model.entries.collectAsState()
    val tags by model.tags.collectAsState()
    val running = entries.firstOrNull { it.endedAt == null }
    var title by remember(running?.id) { mutableStateOf(running?.title ?: "") }
    var selected by remember(running?.id) { mutableStateOf(running?.tagIds ?: emptyList()) }
    val elapsed = running?.let { Duration.between(Instant.parse(it.startedAt), Instant.now()).format() } ?: "00:00:00"
    Text(elapsed, fontFamily = FontFamily.Serif, fontSize = 56.sp)
    OutlinedTextField(title, { title = it }, label = { Text("Title") }, modifier = Modifier.fillMaxWidth())
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        tags.forEach { tag ->
            FilterChip(selected = selected.contains(tag.id), onClick = {
                selected = if (selected.contains(tag.id)) selected - tag.id else selected + tag.id
            }, label = { Text(tag.name) })
        }
    }
    if (running == null) {
        Button(onClick = { model.start(title, null, selected) }, modifier = Modifier.fillMaxWidth()) { Text("Start") }
    } else {
        Button(onClick = { model.stop(running) }, modifier = Modifier.fillMaxWidth()) { Text("Stop") }
    }
}

@Composable
private fun LogScreen(model: TrackerViewModel) {
    val entries by model.entries.collectAsState()
    Text("Log", fontFamily = FontFamily.Serif, fontSize = 32.sp)
    if (entries.isEmpty()) Text("Nothing tracked yet.")
    entries.forEach { entry ->
        val end = entry.endedAt?.let(Instant::parse) ?: Instant.now()
        Column {
            Text(entry.title, style = MaterialTheme.typography.titleMedium)
            Text(Duration.between(Instant.parse(entry.startedAt), end).format())
            TextButton(onClick = { model.saveEntry(entry.copy(deletedAt = Instant.now().toString())) }) { Text("Delete") }
        }
    }
}

@Composable
private fun TagsScreen(model: TrackerViewModel) {
    val tags by model.tags.collectAsState()
    var name by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    Text("Tags", fontFamily = FontFamily.Serif, fontSize = 32.sp)
    OutlinedTextField(name, { name = it }, label = { Text("Name") }, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(description, { description = it }, label = { Text("Description") }, modifier = Modifier.fillMaxWidth())
    Button(onClick = {
        if (name.isNotBlank()) {
            model.saveTag(name, description, "#c2410c")
            name = ""
            description = ""
        }
    }) { Text("Add tag") }
    tags.forEach { tag ->
        Text(tag.name, style = MaterialTheme.typography.titleMedium)
        val details = tag.description
        if (!details.isNullOrBlank()) Text(details)
    }
}

private fun Duration.format(): String {
    val seconds = coerceAtLeast(Duration.ZERO).seconds
    return "%02d:%02d:%02d".format(seconds / 3600, (seconds % 3600) / 60, seconds % 60)
}

package com.timetracker.wear

import android.app.Application
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant

class WearApplication : Application() {
    lateinit var repository: WearRepository
        private set

    override fun onCreate() {
        super.onCreate()
        repository = WearRepository(this)
    }
}

class WearViewModel(app: Application) : AndroidViewModel(app) {
    private val repository = (app as WearApplication).repository
    val entries = repository.entries.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun start() {
        viewModelScope.launch { repository.start() }
    }

    fun stop(entryId: String) {
        viewModelScope.launch {
            val entry = entries.value.firstOrNull { it.id == entryId } ?: return@launch
            repository.stop(entry)
        }
    }
}

class WearActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { WearApp() }
    }
}

@Composable
private fun WearApp(model: WearViewModel = viewModel()) {
    val entries by model.entries.collectAsState()
    val running = entries.firstOrNull { it.endedAt == null }
    var now by remember { mutableStateOf(Instant.now()) }
    LaunchedEffect(running?.id) {
        while (running != null) {
            delay(1000)
            now = Instant.now()
        }
    }
    val elapsed = running?.let { Duration.between(Instant.parse(it.startedAt), now).seconds.coerceAtLeast(0) }
    MaterialTheme {
        ScalingLazyColumn {
            item { TimeText() }
            item {
                Text(if (elapsed == null) "00:00:00" else "%02d:%02d:%02d".format(elapsed / 3600, (elapsed % 3600) / 60, elapsed % 60))
            }
            item { Text(running?.title ?: entries.firstOrNull()?.title ?: "Ready") }
            item {
                Button(onClick = { if (running == null) model.start() else model.stop(running.id) }, modifier = Modifier.fillMaxWidth()) {
                    Text(if (running == null) "Start" else "Stop")
                }
            }
        }
    }
}

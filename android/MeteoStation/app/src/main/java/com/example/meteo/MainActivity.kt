package com.example.meteo

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.meteo.ui.ChartsScreen
import com.example.meteo.ui.MeteoTheme
import com.example.meteo.ui.Navy
import com.example.meteo.ui.NowScreen
import com.example.meteo.ui.SettingsScreen

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    private val askNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33) askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)

        setContent {
            MeteoTheme {
                val s by vm.state.collectAsStateWithLifecycle()
                var tab by rememberSaveable { mutableIntStateOf(0) }
                val snackbar = remember { SnackbarHostState() }

                LaunchedEffect(s.message) {
                    s.message?.let {
                        snackbar.showSnackbar(it)
                        vm.consumeMessage()
                    }
                }

                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = { Text(listOf("Метеостанция", "Графики", "Настройки")[tab]) },
                            colors = TopAppBarDefaults.topAppBarColors(containerColor = Navy, titleContentColor = Color.White)
                        )
                    },
                    bottomBar = {
                        NavigationBar {
                            NavigationBarItem(tab == 0, { tab = 0 }, { Icon(Icons.Filled.Home, null) }, label = { Text("Сейчас") })
                            NavigationBarItem(tab == 1, { tab = 1 }, { Icon(Icons.Filled.DateRange, null) }, label = { Text("Графики") })
                            NavigationBarItem(tab == 2, { tab = 2 }, { Icon(Icons.Filled.Settings, null) }, label = { Text("Настройки") })
                        }
                    },
                    snackbarHost = { SnackbarHost(snackbar) }
                ) { padding ->
                    androidx.compose.foundation.layout.Box(Modifier.padding(padding)) {
                        when (tab) {
                            0 -> NowScreen(s)
                            1 -> ChartsScreen(s, vm)
                            else -> SettingsScreen(s, vm)
                        }
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        vm.startPolling()
    }

    override fun onStop() {
        super.onStop()
        vm.stopPolling()
    }
}

package com.example.DSH_Mobile.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.DSH_Mobile.vm.ChatViewModel
import com.example.DSH_Mobile.vm.AppViewModel
import com.example.DSH_Mobile.vm.Screen

@Composable
fun DshApp() {
    val appVm: AppViewModel = viewModel { AppViewModel() }
    val chatVm: ChatViewModel = viewModel { ChatViewModel() }
    val state by appVm.state.collectAsState()

    LaunchedEffect(Unit) { appVm.boot() }

    DshMobileTheme {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            when (state.screen) {
                Screen.CONNECT -> ConnectScreen(state, appVm)
                Screen.CHAT -> ChatScreen(state, appVm, chatVm)
            }
        }
    }
}

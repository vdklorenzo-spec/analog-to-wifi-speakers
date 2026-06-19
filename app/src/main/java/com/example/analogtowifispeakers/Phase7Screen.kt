package com.example.analogtowifispeakers

import androidx.compose.runtime.Composable

@Composable
fun Phase7Screen(
    initials: String,
    level01: Float,
    castReady: Boolean,
    liveActive: Boolean,
    onCastClick: () -> Unit,
    onPlayClick: () -> Unit,
    onStopClick: () -> Unit,
    onSidebarVisibleChanged: (Boolean) -> Unit,
    onLiveChanged: (Boolean) -> Unit,
) {
    // Sidebar verwijderd in stap 1.
    // Deze stub blijft alleen bestaan voor compatibiliteit.
}
package io.github.numq.kempo.example

import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.application

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "Kempo — Studio Time-Stretching & Pitch-Shifting",
        state = WindowState(width = 720.dp, height = 760.dp)
    ) {
        App(window = this.window)
    }
}
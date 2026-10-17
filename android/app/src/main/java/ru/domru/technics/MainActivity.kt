package ru.domru.technics

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import ru.domru.technics.ui.AppViewModel
import ru.domru.technics.ui.DomRuTechnicsApp

/** Единственное окно приложения: создаёт экран и сообщает ему о сворачивании. */
class MainActivity : ComponentActivity() {
    private val viewModel: AppViewModel by viewModels()

    /** Создаёт интерфейс при первом открытии окна. */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Рисуем приложение под строкой состояния. Так верхняя часть выглядит цельной.
        enableEdgeToEdge()
        setContent {
            DomRuTechnicsApp(viewModel = viewModel)
        }
    }

    /** Сообщает экрану, что приложение снова видно. */
    override fun onStart() {
        super.onStart()
        // После возврата на экран снова просим свежую временную ссылку камеры.
        viewModel.onAppForegrounded()
    }

    /** Сообщает экрану, что приложение больше не видно. */
    override fun onStop() {
        // Когда приложение скрыли, камера не должна продолжать тратить интернет.
        viewModel.onAppBackgrounded()
        super.onStop()
    }
}

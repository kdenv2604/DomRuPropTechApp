package ru.domru.technics.domain

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/** Вид запроса определяет, безопасно ли выполнять его ещё раз. */
enum class RequestKind {
    // Это обычное чтение. Его можно осторожно повторить, если пропал интернет.
    SAFE_READ,
    // Вход нельзя повторять много раз: сервер может принять это за подбор пароля.
    LOGIN,
    // Обновление сессии пробуем один раз, иначе получится бесконечный круг.
    SESSION_REFRESH,
    // Открытие двери никогда не повторяем сами: первый запрос мог уже сработать.
    DOOR_OPEN,
}

/** Решает, сколько раз можно выполнить разные виды сетевых запросов. */
class RetryPolicy(
    private val sleeper: suspend (Long) -> Unit = { delay(it) },
) {
    /** Возвращает жёсткий предел попыток для каждого вида запроса. */
    fun maxAttempts(kind: RequestKind): Int = when (kind) {
        RequestKind.SAFE_READ -> 3
        RequestKind.LOGIN,
        RequestKind.SESSION_REFRESH,
        RequestKind.DOOR_OPEN,
        -> 1
    }

    /** Выполняет работу и повторяет только разрешённые временные ошибки. */
    suspend fun <T> execute(
        kind: RequestKind,
        isRetryable: (Throwable) -> Boolean = { true },
        block: suspend (attempt: Int) -> T,
    ): T {
        val attempts = maxAttempts(kind)
        var lastError: Throwable? = null
        repeat(attempts) { index ->
            try {
                return block(index + 1)
            } catch (error: Throwable) {
                // Отмена означает, что экран закрыли. Такой запрос запускать заново нельзя.
                if (error is CancellationException) throw error
                lastError = error
                val hasNextAttempt = index + 1 < attempts
                if (!hasNextAttempt || !isRetryable(error)) throw error
                // Небольшая пауза не даёт приложению засыпать сервер запросами.
                sleeper(backoffMillis(index + 1))
            }
        }
        throw checkNotNull(lastError)
    }

    /** Между попытками делает короткую растущую паузу. */
    private fun backoffMillis(completedAttempt: Int): Long = when (completedAttempt) {
        1 -> 250L
        else -> 750L
    }
}

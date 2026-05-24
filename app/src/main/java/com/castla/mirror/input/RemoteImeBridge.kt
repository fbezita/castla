package com.castla.mirror.input

import com.castla.mirror.shizuku.IPrivilegedService
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import java.util.concurrent.Executors

sealed class ImeCommand {
    data class CommitText(val text: String) : ImeCommand()
    data class SetComposingText(val text: String, val replaceChars: Int = 0) : ImeCommand()
    data class DeleteSurroundingText(val beforeLength: Int, val afterLength: Int = 0) : ImeCommand()
    data object FinishComposingText : ImeCommand()
}

class RemoteImeBridge(
    private val privilegedServiceProvider: () -> IPrivilegedService?,
    private val displayIdProvider: () -> Int,
    dispatcher: CoroutineDispatcher = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "remote-ime-bridge").apply { isDaemon = true }
    }.asCoroutineDispatcher()
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    fun dispatch(command: ImeCommand) {
        scope.launch {
            val service = privilegedServiceProvider() ?: return@launch
            val displayId = displayIdProvider()
            when (command) {
                is ImeCommand.CommitText -> service.injectText(command.text, displayId)
                is ImeCommand.SetComposingText -> service.injectComposingText(
                    command.replaceChars,
                    command.text,
                    displayId
                )
                is ImeCommand.DeleteSurroundingText -> repeat(command.beforeLength.coerceAtLeast(0)) {
                    service.execCommand(if (displayId > 0) "input -d $displayId keyevent 67" else "input keyevent 67")
                }
                ImeCommand.FinishComposingText -> service.injectComposingText(0, "", displayId)
            }
        }
    }
}

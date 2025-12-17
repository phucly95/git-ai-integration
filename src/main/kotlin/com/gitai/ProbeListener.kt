package com.gitai

import com.intellij.openapi.command.CommandEvent
import com.intellij.openapi.command.CommandListener
import com.intellij.openapi.diagnostic.Logger

class ProbeListener : CommandListener {
    private val LOG = Logger.getInstance(ProbeListener::class.java)

    override fun commandStarted(event: CommandEvent) {
        logEvent("STARTED", event)
    }

    override fun commandFinished(event: CommandEvent) {
        logEvent("FINISHED", event)
    }

    private fun logEvent(state: String, event: CommandEvent) {
        val commandName = event.commandName ?: "NULL_NAME"
        val commandGroupId = event.commandGroupId?.toString() ?: "NULL_GROUP"
        
        // Log to the IDE's internal log (viewable in Help -> Show Log in Finder/Explorer)
        // AND print to standard output/console so it's visible in the run tool window if running from Gradle.
        val message = "[GIT-AI-PROBE] Command $state: Name='$commandName', GroupId='$commandGroupId'"
        LOG.info(message)
        println(message)

        // For AWS Q specific debugging, we might want to dump stack trace if the name looks suspicious
        // OR if the name is empty (which happens for some plugin actions)
        if (commandName.isEmpty() || 
            commandName == "NULL_NAME" ||
            commandName.contains("Insert", ignoreCase = true) || 
            commandName.contains("AWS", ignoreCase = true) ||
            commandName == "Reload From Disk" ||
            commandName.contains("Paste", ignoreCase = true)) {
            
            val stackTrace = Thread.currentThread().stackTrace.take(20).joinToString("\n\t") { it.toString() }
            val stackMessage = "[GIT-AI-PROBE] StackTrace for relevant command '$commandName':\n\t$stackTrace"
            LOG.info(stackMessage)
            println(stackMessage)
        }
    }
}

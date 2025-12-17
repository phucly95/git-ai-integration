package com.gitai

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.CommandEvent
import com.intellij.openapi.command.CommandListener
import com.intellij.openapi.project.ProjectManager
import java.util.concurrent.atomic.AtomicLong

class HumanActivityListener : CommandListener {
    private val DEBOUNCE_TIME = 1000L
    private val lastHumanAction = AtomicLong(0)

    override fun commandFinished(event: CommandEvent) {
        val name = event.commandName ?: ""
        
        // Filter out commands that are definitely NOT human typing/editing
        // or act as noise.
        if (name.isEmpty() || 
            name == "Reload From Disk" || 
            name.contains("git-ai", ignoreCase = true)) {
            return
        }

        // Check if this is actually AWS Q masquerading as a generic command
        if (AwsQDetector.isAwsQStackTrace()) {
            return
        }

        val now = System.currentTimeMillis()
        // Simple debounce: Only checkpoint if it's been a while? 
        // No, for "Typing", we want to capture it. 
        // But running `git-ai` on every keypress is too heavy.
        // For now, let's just trigger it. git-ai is fast enough?
        // Optimally, we'd use an Alarm/Timer.
        
        triggerCheckpointHuman()
    }

    private fun triggerCheckpointHuman() {
        // Delegate to Manager
        com.intellij.openapi.components.service<CheckpointManager>().requestHumanCheckpoint()
    }
}

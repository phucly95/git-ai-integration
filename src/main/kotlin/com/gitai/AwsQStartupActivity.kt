package com.gitai

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.components.service

class AwsQStartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        println("[GIT-AI-STARTUP] Executing startup activity for project: ${project.name}")
        
        // Initialize services
        service<AwsQDetector>()
        service<CheckpointManager>()
        
        // Auto-Install Shim
        val gitAiService = project.service<GitAiService>()
        if (!gitAiService.isShimInstalled()) {
            println("[GIT-AI-STARTUP] Shim not installed or broken. Installing...")
            try {
                gitAiService.installGlobalShim()
                println("[GIT-AI-STARTUP] Shim installation complete.")
                // Notify user? In IntelliJ, usually via NotificationGroupManager. 
                // For now, silent or console log is acceptable for basic port.
            } catch (e: Exception) {
                println("[GIT-AI-STARTUP] Shim installation failed: ${e.message}")
            }
        }
    }
}

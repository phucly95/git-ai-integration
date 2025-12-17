package com.gitai

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.components.service

class AwsQStartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        println("[GIT-AI-STARTUP] Executing startup activity for project: ${project.name}")
        // Eagerly initialize the application service
        service<AwsQDetector>()
        service<CheckpointManager>()
    }
}

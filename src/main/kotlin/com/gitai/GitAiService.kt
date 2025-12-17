package com.gitai

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.diagnostic.Logger
import java.io.File
import java.util.concurrent.TimeUnit

@Service(Service.Level.PROJECT)
class GitAiService(private val project: Project) {
    private val LOG = Logger.getInstance(GitAiService::class.java)
    private val GIT_AI_PATH = System.getProperty("user.home") + "/.git-ai/bin/git-ai"

    fun checkpointHuman() {
        runCommand("checkpoint")
    }

    fun checkpointAwsQ(filePath: String?) {
        val basePath = project.basePath ?: return
        val timestamp = System.currentTimeMillis()
        
        // Compute relative path if filePath is provided
        val editedFilePathsJson = if (filePath != null && filePath.startsWith(basePath)) {
            val relPath = filePath.substring(basePath.length + 1) // +1 for slash
            "[\"$relPath\"]"
        } else {
            "[]"
        }

        // Construct JSON payload for agent-v1
        val payload = """
            {
              "type": "ai_agent",
              "repo_working_dir": "$basePath",
              "edited_filepaths": $editedFilePathsJson,
              "agent_name": "aws-q",
              "model": "amazon-q",
              "conversation_id": "intellij-$timestamp",
              "transcript": {
                "messages": []
              }
            }
        """.trimIndent()

        println("[GIT-AI-Payload] $payload")
        runCommand("checkpoint", "agent-v1", "--hook-input", payload)
    }

    private fun runCommand(vararg args: String) {
        if (!File(GIT_AI_PATH).exists()) {
            LOG.warn("[GIT-AI] Binary not found at $GIT_AI_PATH")
            return
        }

        try {
            val command = mutableListOf(GIT_AI_PATH)
            command.addAll(args)

            val process = ProcessBuilder(command)
                .directory(File(project.basePath ?: return))
                .start()

            val completed = process.waitFor(5, TimeUnit.SECONDS)
            if (completed) {
                if (process.exitValue() == 0) {
                    LOG.info("[GIT-AI] Successfully executed: ${command.joinToString(" ")}")
                } else {
                    val error = process.errorStream.bufferedReader().readText()
                    LOG.warn("[GIT-AI] Command failed: ${command.joinToString(" ")}. Error: $error")
                }
            } else {
                process.destroy()
                LOG.warn("[GIT-AI] Command timed out: ${command.joinToString(" ")}")
            }
        } catch (e: Exception) {
            LOG.error("[GIT-AI] Exception running command", e)
        }
    }
}

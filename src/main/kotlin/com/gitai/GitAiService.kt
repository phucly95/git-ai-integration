package com.gitai

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.diagnostic.Logger
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.LinkOption
import java.nio.file.attribute.PosixFilePermission
import java.util.concurrent.TimeUnit
import com.intellij.openapi.util.SystemInfo
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.jsonObject
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent


@Service(Service.Level.PROJECT)
class GitAiService(private val project: Project) {
    private val LOG = Logger.getInstance(GitAiService::class.java)

    /**
     * Resolves the path to the git-ai executable.
     * Checks ~/.git-ai/bin/git-ai first, then falls back to "git-ai" in PATH.
     */
    private val gitAiPath: String
        get() {
            val defaultPath = File(System.getProperty("user.home"), ".git-ai/bin/git-ai")
            return if (defaultPath.exists()) {
                defaultPath.absolutePath
            } else {
                "git-ai" // Fallback to PATH
            }
        }
    
    // --- shim logic start ---
    
    fun isShimInstalled(): Boolean {
        val homeDir = System.getProperty("user.home")
        val shimDir = File(homeDir, ".git-ai/bin")
        val gitShim = File(shimDir, "git")
        val gitOgShim = File(shimDir, "git-og")
        val configPath = File(homeDir, ".git-ai/config.json")
        
        if (gitShim.exists() && gitOgShim.exists() && configPath.exists()) {
            // Validate Config
            try {
                val content = configPath.readText()
                 // Simple regex or manual parsing to avoid heavy deps if possible, 
                 // but let's try basic string check for recursion first for robustness.
                 
                 // Check for recursion
                 if (content.contains(".git-ai") || content.contains(".local/bin")) {
                     LOG.warn("[GIT-AI] Detected recursive config. Forcing reinstall.")
                     return false
                 }
                 
                 return true
            } catch (e: Exception) {
                return false
            }
        }
        return false
    }
    
    fun installGlobalShim() {
        val homeDir = System.getProperty("user.home")
        val targetDir = File(homeDir, ".git-ai/bin")
        targetDir.mkdirs()
        
        // 1. Install Bundled CLI
        val isArm64 = System.getProperty("os.arch") == "aarch64"
        val binaryName = when {
            SystemInfo.isMac && isArm64 -> "macos-arm64/git-ai"
            SystemInfo.isMac -> "macos-intel/git-ai"
            SystemInfo.isWindows -> "windows-x64/git-ai.exe"
            else -> throw Exception("Unsupported platform: ${SystemInfo.OS_NAME} / ${System.getProperty("os.arch")}")
        }
        
        val resourcePath = "/bin/$binaryName"
        val gitAiDest = File(targetDir, if (SystemInfo.isWindows) "git-ai.exe" else "git-ai")
        
        try {
            GitAiService::class.java.getResourceAsStream(resourcePath)?.use { input ->
                Files.copy(input, gitAiDest.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            } ?: run {
                LOG.warn("Resource not found: $resourcePath. Skipping bundled binary install.")
            }
            if (!SystemInfo.isWindows) {
                gitAiDest.setExecutable(true)
            }
        } catch (e: Exception) {
            LOG.warn("Failed to extract bundled binary", e)
        }
        
        // 2. Find Real Git
        val realGit = findRealGitPath() ?: throw Exception("Could not find system git")
        
        // 3. Shim 'git' -> 'git-ai'
        val gitShim = File(targetDir, "git")
        if (gitShim.exists()) gitShim.delete()
        
        // Use symlink for 'git' -> 'git-ai' (Standard)
        try {
            Files.createSymbolicLink(gitShim.toPath(), gitAiDest.toPath())
        } catch (e: Exception) {
             // Windows fallback or permission issue?
        }
        
        // 4. Shim 'git-og' -> Real Git (Wrapper Script)
        val gitOgShim = File(targetDir, "git-og")
        if (gitOgShim.exists()) gitOgShim.delete()
        
        if (SystemInfo.isWindows) {
            File(targetDir, "git-og.cmd").writeText("@echo off\r\n\"$realGit\" %*")
            File(targetDir, "git-og").writeText("#!/bin/sh\nexec \"${realGit.replace("\\", "/")}\" \"$@\"\n")
        } else {
            gitOgShim.writeText("#!/bin/sh\nexec \"$realGit\" \"$@\"\n")
            gitOgShim.setExecutable(true)
        }
        
        // 5. Write Config
        val configDir = File(homeDir, ".git-ai")
        configDir.mkdirs()
        File(configDir, "config.json").writeText("""
            {
              "git_path": "$realGit"
            }
        """.trimIndent())
        
        // 6. Configure Shell Path
        configureShellPath()

        // 7. Prompt for Restart
        val notification = NotificationGroupManager.getInstance()
            .getNotificationGroup("Git AI Notification Group")
            .createNotification(
                "Git AI Tracking Installed",
                "Standard environment variables updated. Restart IDE to apply changes.",
                NotificationType.INFORMATION
            )
        
        notification.addAction(object : AnAction("Restart IDE Config") {
            override fun actionPerformed(e: AnActionEvent) {
                ApplicationManager.getApplication().restart()
            }
        })
        
        notification.notify(project)
    }
    
    private fun configureShellPath() {
        if (SystemInfo.isWindows) return
        
        val homeDir = System.getProperty("user.home")
        val possibleRcFiles = listOf(".zshrc", ".bash_profile", ".bashrc", ".profile")
        
        // Naive Shell Detection or just try all common ones that exist
        // Prioritize based on SHELL env if possible, but checking all existing is safer for "All-in-One"
        val rcFilesToUpdate = possibleRcFiles.map { File(homeDir, it) }.filter { it.exists() }
        
        if (rcFilesToUpdate.isEmpty()) {
            // Create default?
            val defaultRc = if (SystemInfo.isMac) File(homeDir, ".zshrc") else File(homeDir, ".bashrc")
            if (!defaultRc.exists()) defaultRc.createNewFile()
             updateRcFile(defaultRc)
        } else {
            rcFilesToUpdate.forEach { updateRcFile(it) }
        }
    }
    
    private fun updateRcFile(file: File) {
        try {
            val content = file.readText()
            val exportLine = "export PATH=\"\$HOME/.git-ai/bin:\$PATH\""
            
            // CHECK IF EXISTS (Idempotency)
            if (!content.contains(exportLine)) {
                file.appendText("\n# Added by git-ai-intelij to ensure correct attribution\n$exportLine\n")
                LOG.info("Updated shell path in ${file.name}")
            }
        } catch (e: Exception) {
            LOG.warn("Failed to update shell config: ${file.name}", e)
        }
    }
    
    private fun findRealGitPath(): String? {
        val cmd = if (SystemInfo.isWindows) "where git" else "which -a git"
        
        try {
            val proc = Runtime.getRuntime().exec(cmd)
            val output = proc.inputStream.bufferedReader().readText()
            val lines = output.lines().filter { it.isNotBlank() }
            
            val home = System.getProperty("user.home")
            val excludePatterns = listOf("$home/.git-ai", "$home/.local/bin")
            
            for (line in lines) {
                val p = line.trim()
                if (excludePatterns.any { p.startsWith(it) } || p.contains("git-ai")) {
                    continue
                }
                
                // Realpath check
                try {
                   val path = Paths.get(p)
                   val real = path.toRealPath()
                   val name = real.fileName.toString().lowercase()
                   if (name == "git-ai" || name == "git-ai.exe") continue
                   
                   return p
                } catch (e: Exception) {
                    continue
                }
            }
        } catch (e: Exception) {
            LOG.error("Failed to find git", e)
        }
        return null
    }

    // --- existing methods ---

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

        runCommand("checkpoint", "agent-v1", "--hook-input", payload)
    }

    private fun runCommand(vararg args: String) {
        val executable = gitAiPath
        
        // If using absolute path and it doesn't exist (double check), warn
        if (executable.startsWith("/") && !File(executable).exists()) {
             LOG.warn("[GIT-AI] Binary not found at $executable")
             return
        }

        try {
            val command = mutableListOf(executable)
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

package com.gitai

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.CommandEvent
import com.intellij.openapi.command.CommandListener
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import java.util.logging.Handler
import java.util.logging.LogRecord

@Service(Service.Level.APP)
class AwsQDetector : Disposable {
    private val LOG = Logger.getInstance(AwsQDetector::class.java)
    @Volatile
    private var lastFsReplaceTime: Long = 0

    init {
        LOG.info("[GIT-AI-DETECTOR] Initializing...")
        setupLogInterceptor()
        setupCommandListener()
    }

    private fun setupLogInterceptor() {
        try {
            // Strategy 1: JUL Handler
            // Many IntelliJ plugins eventually route through JUL or can be intercepted there if they use slf4j-jdk14
            val rootLogger = java.util.logging.Logger.getLogger("")
            val handler = object : Handler() {
                override fun publish(record: LogRecord?) {
                    checkLog(record?.message)
                }
                override fun flush() {}
                override fun close() {}
            }
            rootLogger.addHandler(handler)
            LOG.info("[GIT-AI-DETECTOR] JUL Handler attached to Root Logger.")
            
            val awsLogger = java.util.logging.Logger.getLogger("software.aws.toolkits")
            awsLogger.addHandler(handler)

        } catch (e: Exception) {
            LOG.error("[GIT-AI-DETECTOR] Failed to setup log interceptor", e)
        }
    }

    private fun checkLog(msg: String?) {
        if (msg == null) return
        
        // Detect ANY ToolUseEvent related to file system
        if (msg.contains("ToolUseEvent") && (msg.contains("fsReplace") || msg.contains("fsWrite") || msg.contains("fsDelete"))) {
            LOG.info("[GIT-AI-DETECTOR] Detected AWS Q File System Event. Signalling Manager.")
            val manager = com.intellij.openapi.components.service<CheckpointManager>()
            manager.signalAiActivity()
        }
    }

    private var lastDetectedPath: String? = null

    private fun extractPathFromLog(msg: String): String? {
        val match = Regex("\"path\":\\s*\"([^\"]+)\"").find(msg)
        return match?.groupValues?.get(1)
    }

    private fun setupCommandListener() {
        // No longer needed to listen to commands for correlation, 
        // as we correlate with File Events in CheckpointManager directly.
    }
    
    private fun isAwsQHelperCommand(name: String): Boolean {
        // Generic commands that might be AI
        return name.contains("Insert", ignoreCase = true) || 
               name.contains("Choose Lookup Item", ignoreCase = true) ||
               name.contains("Typing", ignoreCase = true) ||
               name.isEmpty() 
    }

    private fun triggerCheckpoint(isHuman: Boolean, filePath: String? = null) {
        val manager = com.intellij.openapi.components.service<CheckpointManager>()
        if (isHuman) {
            manager.requestHumanCheckpoint()
        } else {
            manager.requestAwsQCheckpoint(filePath)
        }
    }
    
    companion object {
        fun isAwsQStackTrace(): Boolean {
            // Check top 30 frames for AWS signatures
            val stack = Thread.currentThread().stackTrace
            val framesToCheck = stack.take(30)
            return framesToCheck.any { 
                val s = it.toString()
                s.contains("software.aws.toolkits", ignoreCase = true) || 
                s.contains("jetbrains.services.amazonq", ignoreCase = true) 
            }
        }
    }

    override fun dispose() {
    }
}

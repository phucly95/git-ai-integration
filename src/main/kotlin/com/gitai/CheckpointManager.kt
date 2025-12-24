package com.gitai

import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.ProjectManager
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

@Service(Service.Level.APP)
class CheckpointManager {
    private val LOG = Logger.getInstance(CheckpointManager::class.java)
    private val scheduler = Executors.newSingleThreadScheduledExecutor()
    private var pendingHumanTask: ScheduledFuture<*>? = null
    
    // Time when the last AI activity was detected (log)
    private val lastAiSignalTime = AtomicLong(0)
    private val AI_SIGNAL_WINDOW_MS = 2000L // Window to correlate log -> file event

    // Time when the last AI checkpoint finished.
    // We ignore human events for a grace period after this.
    private val lastAiCheckpointTime = AtomicLong(0)
    private val AI_GRACE_PERIOD_MS = 5000L 
    private val HUMAN_DEBOUNCE_MS = 1500L

    init {
        LOG.info("[GIT-AI-MANAGER] CheckpointManager initialized.")
    }

    /**
     * Called by AwsQDetector when it sees a relevant log (fsWrite, fsReplace, etc.)
     */
    fun signalAiActivity() {
        lastAiSignalTime.set(System.currentTimeMillis())
        LOG.info("[GIT-AI-MANAGER] AI Activity Signal received.")
    }

    /**
     * Called by ProbeBulkFileListener when a file is modified/created.
     */
    fun handleFileChange(filePath: String) {
        val now = System.currentTimeMillis()
        val timeSinceAi = now - lastAiSignalTime.get()

        if (timeSinceAi < AI_SIGNAL_WINDOW_MS) {
            LOG.info("[GIT-AI-MANAGER] Correlated File Change to AI (delta=${timeSinceAi}ms). Path=$filePath")
            requestAwsQCheckpoint(filePath)
        } else {
            // It's a human change (or at least not correlated to a recent AI log)
            // We delegate to the standard human checkpoint request (which is debounced)
            requestHumanCheckpoint()
        }
    }

    fun requestHumanCheckpoint() {
        // 1. Check if we are in the grace period of an AI edit (to prevent overwriting AI work)
        if (System.currentTimeMillis() - lastAiCheckpointTime.get() < AI_GRACE_PERIOD_MS) {
            LOG.info("[GIT-AI-MANAGER] Human checkpoint ignored (Grace Period active).")
            return
        }

        // 2. Debounce
        synchronized(this) {
            if (pendingHumanTask != null && !pendingHumanTask!!.isDone) {
                pendingHumanTask!!.cancel(false)
            }
            
            pendingHumanTask = scheduler.schedule({
                executeHumanCheckpoint()
            }, HUMAN_DEBOUNCE_MS, TimeUnit.MILLISECONDS)
        }
    }

    fun requestAwsQCheckpoint(filePath: String? = null) {
        LOG.info("[GIT-AI-MANAGER] AWS Q Checkpoint requested. Cancelling pending human tasks.")
        
        synchronized(this) {
            if (pendingHumanTask != null) {
                pendingHumanTask!!.cancel(false)
                pendingHumanTask = null
            }
        }

        // Execute immediately
        scheduler.submit {
            executeAwsQCheckpoint(filePath)
            lastAiCheckpointTime.set(System.currentTimeMillis())
        }
    }

    private fun executeHumanCheckpoint() {
        // Final check before running
        if (System.currentTimeMillis() - lastAiCheckpointTime.get() < AI_GRACE_PERIOD_MS) {
             return
        }
        
        val projects = ProjectManager.getInstance().openProjects
        for (project in projects) {
            val service = project.getService(GitAiService::class.java)
            service?.checkpointHuman()
            updateWidgets(project)
        }
    }

    private fun executeAwsQCheckpoint(filePath: String?) {
        val projects = ProjectManager.getInstance().openProjects
        for (project in projects) {
            val service = project.getService(GitAiService::class.java)
            service?.checkpointAwsQ(filePath)
            // Trigger Widget Update
            updateWidgets(project)
        }
    }
    
    private fun updateWidgets(project: com.intellij.openapi.project.Project) {
        val statusBar = com.intellij.openapi.wm.WindowManager.getInstance().getStatusBar(project)
        statusBar?.updateWidget("GitAiStatusBarWidget")
        
        // Also simpler: Finding the widget instance if we could, 
        // but 'updateWidget' ID causes it to re-fetch data via getPresentation() -> text/tooltip? 
        // Actually our widget fetches data in 'updateStats' manually or via install. 
        // We need to tell the WIDGET to refresh data.
        // Since we don't have direct ref easily without factory lookup, let's use message bus? 
        // OR simply rely on the fact that 'updateWidget' usually refreshes presentation.
        // BUT our presentation is dynamic based on 'lastStats'. We need to re-fetch stats.
        
        // Hack: Let's find the widget and call 'updateStats'
        val widget = statusBar?.getWidget("GitAiStatusBarWidget")
        if (widget is GitAiStatusBarWidget) {
            widget.updateStats()
        }
    }
}

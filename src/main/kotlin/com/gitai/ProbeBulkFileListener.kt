package com.gitai

import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.openapi.diagnostic.Logger

class ProbeBulkFileListener : BulkFileListener {
    private val LOG = Logger.getInstance(ProbeBulkFileListener::class.java)

    override fun after(events: MutableList<out VFileEvent>) {
        for (event in events) {
            val requestor = event.requestor
            val path = event.path
            val requestorInfo = if (requestor != null) {
                "${requestor.javaClass.name} ($requestor)"
            } else {
                "NULL"
            }
            
            val message = "[GIT-AI-PROBE] File Event: ${event.javaClass.simpleName}, Path='$path', Requestor='$requestorInfo'"
            LOG.info(message)
            println(message)
            
            // Attribution for File Copy / Create / Move (e.g. via Project View)
            // AWS Q uses fsReplace which triggers VFileContentChangeEvent via "refresh", 
            // but we can assume explicit UI actions (Copy/Paste file) are Human.
            if (isHumanFileAction(event)) {
                 triggerCheckpointHuman(path)
            }
        }
    }

    private fun isHumanFileAction(event: VFileEvent): Boolean {
        // We now act on ALL file changes (Content, Create, Copy, Move).
        // CheckpointManager will categorize them as AI or Human based on timing.
        
        // We previously filtered out "refresh" (VFileEvent.refresh), but AWS Q often appears as a refresh 
        // after fsReplace. So we MUST allow it to let correlation work.
        
        return true
    }

    private fun triggerCheckpointHuman(path: String) {
        // Delegate to CheckpointManager to decide if it's Human or AI based on recent signals
        com.intellij.openapi.components.service<CheckpointManager>().handleFileChange(path)
    }
}

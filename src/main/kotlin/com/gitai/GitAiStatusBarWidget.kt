package com.gitai

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.util.Consumer
import java.awt.event.MouseEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.testFramework.LightVirtualFile
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup

import com.intellij.ide.DataManager

class GitAiStatusBarWidget(private val project: Project) : StatusBarWidget, StatusBarWidget.TextPresentation {
    
    // We'll store the latest stats here
    private var lastStats: GitAiService.RecentCommitsData? = null
    private var commitDepth = 1
    
    override fun ID(): String = "GitAiStatusBarWidget"

    override fun install(statusBar: StatusBar) {
        // Initial load
        updateStats()
    }

    override fun dispose() {}

    // --- Presentation ---

    override fun getPresentation(): StatusBarWidget.WidgetPresentation = this

    override fun getAlignment(): Float = 0.0f // Left alignment in the widget area

    override fun getText(): String {
        val stats = lastStats?.aggregated
        if (stats == null) {
            return "Git AI: 🤖 Initializing..."
        }
        
        val total = stats.human_additions + stats.mixed_additions + stats.ai_additions + stats.ai_accepted
        if (total == 0) {
            return "Git AI: 🤖 0%"
        }

        val aiPct = Math.round(((stats.ai_additions + stats.ai_accepted).toDouble() / total) * 100)
        val mixedPct = Math.round((stats.mixed_additions.toDouble() / total) * 100)
        val humanPct = Math.round((stats.human_additions.toDouble() / total) * 100)

        // Using simple text symbols matching VS Code
        // 🤖 = AI, 👥 = Mix, 👤 = Human (approximate)
        return "Git AI: 🤖 $aiPct%  👥 $mixedPct%  👤 $humanPct%"
    }
    
    override fun getTooltipText(): String? {
        val data = lastStats ?: return "Git AI: No stats available"
        val commits = data.commits
        if (commits.isEmpty()) return "Git AI: No commits analyzed"
        
        // Build HTML Table
        val sb = StringBuilder()
        sb.append("<html><body>")
        sb.append("<h3>Authorship Stats (Last $commitDepth Commits)</h3>")
        sb.append("<table border='1' cellspacing='0' cellpadding='3'>")
        sb.append("<tr>")
        sb.append("<th>No</th><th>Commit</th><th>Message</th><th>Author</th><th>AI</th><th>Mix</th><th>Human</th><th>% (A/M/H)</th>")
        sb.append("</tr>")
        
        val maxRows = 15
        val visibleCommits = commits.take(maxRows)
        val hidden = commits.size - maxRows
        
        visibleCommits.forEachIndexed { i, c ->
            val total = c.stats.human_additions + c.stats.mixed_additions + c.stats.ai_additions + c.stats.ai_accepted
            var cAi = 0
            var cMix = 0
            var cHuman = 0
            if (total > 0) {
                 cAi = Math.round(((c.stats.ai_additions + c.stats.ai_accepted).toDouble() / total) * 100).toInt()
                 cMix = Math.round((c.stats.mixed_additions.toDouble() / total) * 100).toInt()
                 cHuman = Math.round((c.stats.human_additions.toDouble() / total) * 100).toInt()
            }
            
            val shortMsg = if (c.subject.length > 25) c.subject.substring(0, 24) + "..." else c.subject
            val shortAuth = if (c.author.length > 15) c.author.substring(0, 14) + "..." else c.author
            // Non-breaking space for author and message
             val safeAuth = shortAuth.replace(" ", "&nbsp;")
             val safeMsg = shortMsg.replace(" ", "&nbsp;")
            
            sb.append("<tr>")
            sb.append("<td>${i + 1}</td>")
            sb.append("<td>${c.shortHash}</td>")
            sb.append("<td>$safeMsg</td>")
            sb.append("<td>$safeAuth</td>")
            sb.append("<td>${c.stats.ai_additions + c.stats.ai_accepted}</td>")
            sb.append("<td>${c.stats.mixed_additions}</td>")
            sb.append("<td>${c.stats.human_additions}</td>")
            sb.append("<td>$cAi/$cMix/$cHuman</td>")
            sb.append("</tr>")
        }
        
        if (hidden > 0) {
             sb.append("<tr><td colspan='8' align='center'>... ($hidden more commits) ...</td></tr>")
        }
        
        // Sum Row
        val agg = data.aggregated
        val aggTotal = agg.human_additions + agg.mixed_additions + agg.ai_additions + agg.ai_accepted
        var aAi = 0
        var aMix = 0
        var aHuman = 0
        if (aggTotal > 0) {
             aAi = Math.round(((agg.ai_additions + agg.ai_accepted).toDouble() / aggTotal) * 100).toInt()
             aMix = Math.round((agg.mixed_additions.toDouble() / aggTotal) * 100).toInt()
             aHuman = Math.round((agg.human_additions.toDouble() / aggTotal) * 100).toInt()
        }

        sb.append("<tr>")
        sb.append("<td><b>Sum</b></td>")
        sb.append("<td></td><td></td><td></td>")
        sb.append("<td><b>${agg.ai_additions + agg.ai_accepted}</b></td>")
        sb.append("<td><b>${agg.mixed_additions}</b></td>")
        sb.append("<td><b>${agg.human_additions}</b></td>")
        sb.append("<td><b>$aAi/$aMix/$aHuman</b></td>")
        sb.append("</tr>")

        sb.append("</table>")
        sb.append("<p>Total Additions: $aggTotal lines</p>")
        sb.append("</body></html>")
        
        return sb.toString()
    }

    override fun getClickConsumer(): Consumer<MouseEvent>? {
        return Consumer { e ->
            // Fix: Retrieve DataContext from Event Component
             val component = e.component
             val dataContext = DataManager.getInstance().getDataContext(component)

             val group = DefaultActionGroup()
             
             group.add(object : AnAction("Open Full Stats Report...") {
                 override fun actionPerformed(e: AnActionEvent) {
                     openFullReport()
                 }
             })
             
             group.add(object : AnAction("Configure Commit Depth") {
                 override fun actionPerformed(e: AnActionEvent) {
                     configureDepth()
                 }
             })
             
             val popup = JBPopupFactory.getInstance().createActionGroupPopup(
                 "Git AI Options",
                 group,
                 dataContext,
                 JBPopupFactory.ActionSelectionAid.SPEEDSEARCH,
                 true
             )
             popup.showInBestPositionFor(dataContext)
        }
    }
    
    // --- Actions ---

    fun updateStats() {
        ApplicationManager.getApplication().executeOnPooledThread {
            val service = project.getService(GitAiService::class.java)
            val stats = service.getRecentStats(commitDepth)
            
            // UI Update on EDT
            ApplicationManager.getApplication().invokeLater {
                this.lastStats = stats
                // Force widget redraw
                val statusBar = com.intellij.openapi.wm.WindowManager.getInstance().getStatusBar(project)
                statusBar?.updateWidget(ID())
            }
        }
    }
    
    private fun configureDepth() {
        val current = commitDepth.toString()
        val input = Messages.showInputDialog(
            project,
            "Enter number of recent commits to analyze (1-100):",
            "Configure Commit Depth",
            Messages.getQuestionIcon(),
            current,
            null
        )
        
        if (input != null) {
            val n = input.toIntOrNull()
            if (n != null && n > 0) {
                commitDepth = n
                updateStats() // Trigger refresh
            }
        }
    }
    
    private fun openFullReport() {
        val data = lastStats ?: return
        
        val sb = StringBuilder()
        sb.append("# Git AI Authorship Report\n\n")
        sb.append("**Scope:** Last $commitDepth Commits\n")
        sb.append("**Generated:** ${java.util.Date()}\n\n")

        val agg = data.aggregated
        val totalLines = agg.human_additions + agg.mixed_additions + agg.ai_additions + agg.ai_accepted
        
         var pAi = 0
         var pMix = 0
         var pHuman = 0
         if (totalLines > 0) {
             pAi = Math.round(((agg.ai_additions + agg.ai_accepted).toDouble() / totalLines) * 100).toInt()
             pMix = Math.round((agg.mixed_additions.toDouble() / totalLines) * 100).toInt()
             pHuman = Math.round((agg.human_additions.toDouble() / totalLines) * 100).toInt()
         }

        sb.append("## Summary\n")
        sb.append("- **Total Lines Added:** $totalLines\n")
        sb.append("- **AI Generated:** ${agg.ai_additions + agg.ai_accepted} ($pAi%)\n")
        sb.append("- **Mixed:** ${agg.mixed_additions} ($pMix%)\n")
        sb.append("- **Human:** ${agg.human_additions} ($pHuman%)\n\n")

        
        sb.append("| No | Commit | Message | Author | AI | Mix | Human | % (A/M/H) |\n")
        sb.append("| -- | ------ | ------- | ------ | -- | --- | ----- | --------- |\n")
        
        data.commits.forEachIndexed { i, c ->
             // ... stats calc ...
            val total = c.stats.human_additions + c.stats.mixed_additions + c.stats.ai_additions + c.stats.ai_accepted
            var cAi = 0; var cMix = 0; var cHuman = 0
            if (total > 0) {
                 cAi = Math.round(((c.stats.ai_additions + c.stats.ai_accepted).toDouble() / total) * 100).toInt()
                 cMix = Math.round((c.stats.mixed_additions.toDouble() / total) * 100).toInt()
                 cHuman = Math.round((c.stats.human_additions.toDouble() / total) * 100).toInt()
            }
            sb.append("| ${i + 1} | ${c.shortHash} | ${c.subject} | ${c.author} | ${c.stats.ai_additions + c.stats.ai_accepted} | ${c.stats.mixed_additions} | ${c.stats.human_additions} | $cAi/$cMix/$cHuman |\n")
        }
        
        // Open Editor
        ApplicationManager.getApplication().runWriteAction {
            val file = LightVirtualFile("GitAI_Report.md", sb.toString())
            FileEditorManager.getInstance(project).openFile(file, true)
        }
    }
    
    // Legacy support via `ExpectedTextProvider` logic deprecated in some versions but good for sizing
    // override fun getExpectedText(): String = "Git AI: 🤖 100%  👥 100%  👤 100%"
}

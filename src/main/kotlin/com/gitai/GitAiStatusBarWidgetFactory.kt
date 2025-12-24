package com.gitai

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory

class GitAiStatusBarWidgetFactory : StatusBarWidgetFactory {
    override fun getId(): String = "GitAiStatusBarWidget"

    override fun getDisplayName(): String = "Git AI Tracking"

    override fun isAvailable(project: Project): Boolean = true

    override fun createWidget(project: Project): StatusBarWidget {
        return GitAiStatusBarWidget(project)
    }

    override fun disposeWidget(widget: StatusBarWidget) {
        if (widget is GitAiStatusBarWidget) {
            // dispose logic if any
        }
    }

    override fun canBeEnabledOn(statusBar: StatusBar): Boolean = true
}

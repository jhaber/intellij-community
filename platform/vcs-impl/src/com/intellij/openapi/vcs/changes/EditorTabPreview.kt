// Copyright 2000-2020 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.openapi.vcs.changes

import com.intellij.diff.impl.DiffRequestProcessor
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.CommonShortcuts.ESCAPE
import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vcs.changes.actions.diff.lst.LocalChangeListDiffTool.ALLOW_EXCLUDE_FROM_COMMIT
import com.intellij.openapi.vcs.changes.ui.ChangesTree
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.util.EditSourceOnDoubleClickHandler.isToggleEvent
import com.intellij.util.Processor
import com.intellij.util.ui.update.MergingUpdateQueue
import com.intellij.util.ui.update.Update
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import javax.swing.JComponent

abstract class EditorTabPreview(private val diffProcessor: DiffRequestProcessor) : ChangesViewPreview {
  private val project get() = diffProcessor.project!!
  private val previewFile = PreviewDiffVirtualFile(EditorTabDiffPreviewProvider(diffProcessor) { getCurrentName() })
  private val updatePreviewQueue =
    MergingUpdateQueue("updatePreviewQueue", 100, true, null, diffProcessor).apply {
      setRestartTimerOnAdd(true)
    }
  private val updatePreviewProcessor: DiffPreviewUpdateProcessor? get() = diffProcessor as? DiffPreviewUpdateProcessor

  var escapeHandler: Runnable? = null

  fun installOn(tree: ChangesTree) {
    installDoubleClickHandler(tree)
    installEnterKeyHandler(tree)
    installSelectionChangedHandler(tree)
  }

  fun installNextDiffActionOn(component: JComponent) {
    DumbAwareAction.create { openPreview(true) }.apply {
      copyShortcutFrom(ActionManager.getInstance().getAction(IdeActions.ACTION_NEXT_DIFF))
      registerCustomShortcutSet(component, diffProcessor)
    }
  }

  private fun installDoubleClickHandler(tree: ChangesTree) {
    val oldDoubleClickHandler = tree.doubleClickHandler
    val newDoubleClickHandler = Processor<MouseEvent> { e ->
      if (isToggleEvent(tree, e)) return@Processor false

      openPreview(true) || oldDoubleClickHandler?.process(e) == true
    }

    tree.doubleClickHandler = newDoubleClickHandler
    Disposer.register(diffProcessor, Disposable { tree.doubleClickHandler = oldDoubleClickHandler })
  }

  private fun installEnterKeyHandler(tree: ChangesTree) {
    val oldEnterKeyHandler = tree.enterKeyHandler
    val newEnterKeyHandler = Processor<KeyEvent> { e ->
      openPreview(false) || oldEnterKeyHandler?.process(e) == true
    }

    tree.enterKeyHandler = newEnterKeyHandler
    Disposer.register(diffProcessor, Disposable { tree.enterKeyHandler = oldEnterKeyHandler })
  }

  private fun installSelectionChangedHandler(tree: ChangesTree) =
    tree.addSelectionListener(
      Runnable {
        updatePreviewQueue.queue(Update.create(this) {
          if (skipPreviewUpdate()) return@create
          updatePreview(false)
        })
      },
      updatePreviewQueue
    )

  protected abstract fun getCurrentName(): String?

  protected abstract fun hasContent(): Boolean

  protected open fun skipPreviewUpdate(): Boolean = ToolWindowManager.getInstance(project).isEditorComponentActive

  override fun updatePreview(fromModelRefresh: Boolean) {
    updatePreviewProcessor?.run { if (isPreviewOpen()) refresh(false) else clear() }
  }

  override fun setPreviewVisible(isPreviewVisible: Boolean) {
    if (isPreviewVisible) openPreview(false) else closePreview()
  }

  override fun setAllowExcludeFromCommit(value: Boolean) {
    diffProcessor.putContextUserData(ALLOW_EXCLUDE_FROM_COMMIT, value)
    diffProcessor.updateRequest(true)
  }

  private fun isPreviewOpen(): Boolean = FileEditorManager.getInstance(project).isFileOpen(previewFile)

  fun closePreview() {
    FileEditorManager.getInstance(project).closeFile(previewFile)
    updatePreviewProcessor?.clear()
  }

  fun openPreview(focusEditor: Boolean): Boolean {
    updatePreviewProcessor?.refresh(false)
    if (!hasContent()) return false

    openPreview(project, previewFile, focusEditor, escapeHandler)
    return true
  }

  companion object {
    fun openPreview(project: Project, file: PreviewDiffVirtualFile, focusEditor: Boolean, escapeHandler: Runnable? = null) {
      val wasAlreadyOpen = FileEditorManager.getInstance(project).isFileOpen(file)
      val editor = FileEditorManager.getInstance(project).openFile(file, focusEditor, true).singleOrNull() ?: return

      if (wasAlreadyOpen) return
      escapeHandler?.let { r -> DumbAwareAction.create { r.run() }.registerCustomShortcutSet(ESCAPE, editor.component, editor) }
    }
  }
}

private class EditorTabDiffPreviewProvider(
  private val diffProcessor: DiffRequestProcessor,
  private val tabNameProvider: () -> String?
) : DiffPreviewProvider {

  override fun createDiffRequestProcessor(): DiffRequestProcessor = diffProcessor

  override fun getOwner(): Any = this

  override fun getEditorTabName(): String = tabNameProvider().orEmpty()
}
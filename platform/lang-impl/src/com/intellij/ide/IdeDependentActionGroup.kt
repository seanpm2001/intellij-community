// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.ide

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.ui.IdeUICustomization

internal class IdeDependentActionGroup : DefaultActionGroup() {
  private val id by lazy { ActionManager.getInstance().getId(this) }

  override fun update(e: AnActionEvent) {
    super.update(e)
    IdeUICustomization.getInstance().getActionText(id)?.let {
      e.presentation.text = it
    }
  }

  override fun getActionUpdateThread() = ActionUpdateThread.BGT

  override fun isDumbAware() = true
}

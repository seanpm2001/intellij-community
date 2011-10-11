/*
 * Copyright 2000-2011 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jetbrains.idea.devkit.inspections;

import com.intellij.codeInspection.LocalInspectionToolSession;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.openapi.util.Pair;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiElementVisitor;
import com.intellij.psi.XmlElementVisitor;
import com.intellij.psi.util.InheritanceUtil;
import com.intellij.psi.xml.XmlTag;
import com.intellij.util.xml.DomElement;
import com.intellij.util.xml.DomUtil;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.idea.devkit.dom.Extension;
import org.jetbrains.idea.devkit.dom.ExtensionPoint;
import org.jetbrains.idea.devkit.dom.IdeaPlugin;

/**
 * @author Dmitry Avdeev
 *         Date: 10/10/11
 */
public class InspectionMappingConsistencyInspection extends DevKitInspectionBase {

  @NotNull
  @Override
  public PsiElementVisitor buildVisitor(@NotNull final ProblemsHolder holder,
                                        boolean isOnTheFly,
                                        @NotNull LocalInspectionToolSession session) {
    return new XmlElementVisitor()  {
      @Override
      public void visitXmlTag(XmlTag tag) {
        DomElement element = DomUtil.getDomElement(tag);
        if (element instanceof Extension) {
          ExtensionPoint target = ((Extension)element).getExtensionPoint();
          if (target != null && InheritanceUtil.isInheritor(target.getBeanClass().getValue(), "com.intellij.codeInspection.InspectionEP")) {
            boolean key = tag.getAttribute("key") != null;
            boolean groupKey = tag.getAttribute("groupKey") != null;
            if (key) {
              if (tag.getAttribute("bundle") == null) {
                checkDefaultBundle(element, holder);
              }
            }
            if (groupKey) {
              if (tag.getAttribute("bundle") == null && tag.getAttribute("groupBundle") == null) {
                checkDefaultBundle(element, holder);
              }
            }
          }
        }
      }
    };
  }

  private static void checkDefaultBundle(DomElement element, ProblemsHolder holder) {
    IdeaPlugin plugin = DomUtil.getParentOfType(element, IdeaPlugin.class, true);
    if (plugin != null && plugin.getResourceBundles().isEmpty()) {
      Pair<TextRange,PsiElement> range = DomUtil.getProblemRange(element.getXmlTag());
      holder.registerProblem(range.second, range.first, "Bundle should be specified");
    }
  }

  @Nls
  @NotNull
  @Override
  public String getDisplayName() {
    return "<inspection> tag consistency";
  }

  @NotNull
  @Override
  public String getShortName() {
    return "InspectionMappingConsistency";
  }
}

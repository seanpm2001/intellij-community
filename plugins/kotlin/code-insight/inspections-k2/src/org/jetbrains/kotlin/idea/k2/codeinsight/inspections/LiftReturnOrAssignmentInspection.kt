// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.kotlin.idea.k2.codeinsight.inspections

import com.intellij.codeInspection.*
import com.intellij.codeInspection.ProblemHighlightType.*
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import org.jetbrains.kotlin.KtNodeTypes
import org.jetbrains.kotlin.analysis.api.KtAllowAnalysisOnEdt
import org.jetbrains.kotlin.analysis.api.KtAnalysisSession
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.analyzeWithReadAction
import org.jetbrains.kotlin.analysis.api.lifetime.allowAnalysisOnEdt
import org.jetbrains.kotlin.idea.base.psi.getLineCount
import org.jetbrains.kotlin.idea.base.resources.KotlinBundle
import org.jetbrains.kotlin.idea.codeinsight.api.classic.inspections.AbstractKotlinInspection
import org.jetbrains.kotlin.idea.k2.codeinsight.inspections.branchedTransformations.BranchedFoldingUtils
import org.jetbrains.kotlin.idea.k2.codeinsight.inspections.branchedTransformations.BranchedFoldingUtils.getFoldableAssignmentNumber
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.psi.psiUtil.startOffset

/**
 * An inspection to lift return or assignment within expressions with branches e.g., if/when/try expressions.
 *
 * Example:
 *
 *   - Lift assignment:
 *     // Before
 *     when(foo) {
 *       1 -> bar = 2
 *       2 -> bar = 3
 *       else -> bar = 4
 *     }
 *     // After
 *     bar = when(foo) {
 *       1 -> 2
 *       2 -> 3
 *       else -> 4
 *     }
 *
 *   - Lift return:
 *     // Before
 *     when(foo) {
 *       1 -> return 2
 *       2 -> return 3
 *       else -> return 4
 *     }
 *     // After
 *     return when(foo) {
 *       1 -> 2
 *       2 -> 3
 *       else -> 4
 *     }
 *
 * TODO: Handle the lift-return case.
 */
class LiftReturnOrAssignmentInspection : AbstractKotlinInspection() {

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean, session: LocalInspectionToolSession) =
        object : KtVisitorVoid() {
            override fun visitExpression(expression: KtExpression) {
                // This inspection targets only return and assignment within expressions with branches.
                // Their values are not used in other expressions.
                if (analyze(expression) { expression.isUsedAsExpression() }) return

                val states = getState(expression) ?: return
                states.forEach { state ->
                    registerProblem(
                        expression,
                        state.keyword,
                        state.isSerious,
                        when (state.liftType) {
                            LiftType.LIFT_ASSIGNMENT_OUT -> LiftAssignmentOutFix(state.keyword.text)
                        },
                        state.highlightElement,
                        state.highlightType,
                    )
                }
            }

            private fun registerProblem(
                expression: KtExpression,
                keyword: PsiElement,
                isSerious: Boolean,
                fix: LocalQuickFix,
                highlightElement: PsiElement = keyword,
                highlightType: ProblemHighlightType = if (isSerious) GENERIC_ERROR_OR_WARNING else INFORMATION,
            ) {
                val subject = KotlinBundle.message("text.Assignment")
                holder.registerProblemWithoutOfflineInformation(
                    expression,
                    KotlinBundle.message("0.1.be.lifted.out.of.2", subject, keyword.text),
                    isOnTheFly,
                    highlightType,
                    highlightElement.textRange?.shiftRight(-expression.startOffset),
                    fix
                )
            }

        }

    private class LiftAssignmentOutFix(private val keyword: String) : LocalQuickFix {
        override fun getName() = KotlinBundle.message("lift.assignment.out.fix.text.0", keyword)

        override fun getFamilyName() = name

        override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
            BranchedFoldingUtils.tryFoldToAssignment(descriptor.psiElement as KtExpression)
        }
    }

    private val LINES_LIMIT: Int
        get() = 15

    private fun KtAnalysisSession.getStateForWhenOrTry(expression: KtExpression, keyword: PsiElement): List<LiftState>? {
        if (expression.getLineCount() > LINES_LIMIT) return null
        if (expression.parent.node.elementType == KtNodeTypes.ELSE) return null

        val assignmentNumber = getFoldableAssignmentNumber(expression)
        if (assignmentNumber > 0) {
            val isSerious = assignmentNumber > 1
            return listOf(LiftState(keyword, isSerious, LiftType.LIFT_ASSIGNMENT_OUT))
        }
        return null
    }

    @OptIn(KtAllowAnalysisOnEdt::class)
    fun getState(expression: KtExpression) = allowAnalysisOnEdt {
        analyzeWithReadAction(expression) {
            when (expression) {
                is KtWhenExpression -> getStateForWhenOrTry(expression, expression.whenKeyword)
                is KtIfExpression -> getStateForWhenOrTry(expression, expression.ifKeyword)
                is KtTryExpression -> expression.tryKeyword?.let {
                    getStateForWhenOrTry(expression, it)
                }
                else -> null
            }
        }
    }

    /**
     * Types of lift.
     *
     * TODO: Add LIFT_RETURN_OUT and handle the lift-return case.
     */
    enum class LiftType {
        LIFT_ASSIGNMENT_OUT
    }

    data class LiftState(
        val keyword: PsiElement,
        val isSerious: Boolean,
        val liftType: LiftType,
        val highlightElement: PsiElement = keyword,
        val highlightType: ProblemHighlightType = if (isSerious) GENERIC_ERROR_OR_WARNING else INFORMATION
    )
}
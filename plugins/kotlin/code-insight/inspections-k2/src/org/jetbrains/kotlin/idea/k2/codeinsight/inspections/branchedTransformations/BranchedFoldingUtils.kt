/*
 * Copyright 2010-2022 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */
package org.jetbrains.kotlin.idea.k2.codeinsight.inspections.branchedTransformations

import org.jetbrains.kotlin.analysis.api.KtAnalysisSession
import org.jetbrains.kotlin.analysis.api.types.KtType
import org.jetbrains.kotlin.analysis.api.types.KtTypeNullability
import org.jetbrains.kotlin.analysis.api.types.KtUsualClassType
import org.jetbrains.kotlin.idea.base.util.module
import org.jetbrains.kotlin.idea.references.mainReference
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.psi.psiUtil.*

object BranchedFoldingUtils {
    private val KtIfExpression.branches: List<KtExpression?> get() = ifBranchesOrThis()

    private fun KtExpression.ifBranchesOrThis(): List<KtExpression?> {
        if (this !is KtIfExpression) return listOf(this)
        return listOf(then) + `else`?.ifBranchesOrThis().orEmpty()
    }

    private fun KtTryExpression.tryBlockAndCatchBodies(): List<KtExpression?> = listOf(tryBlock) + catchClauses.map { it.catchBody }

    private fun getFoldableBranchedAssignment(branch: KtExpression?): KtBinaryExpression? {
        fun checkAssignment(expression: KtBinaryExpression): Boolean {
            if (expression.operationToken !in KtTokens.ALL_ASSIGNMENTS) return false

            val left = expression.left as? KtNameReferenceExpression ?: return false
            if (expression.right == null) return false

            val parent = expression.parent
            if (parent is KtBlockExpression) {
                return !KtPsiUtil.checkVariableDeclarationInBlock(parent, left.text)
            }

            return true
        }
        return (branch?.lastBlockStatementOrThis() as? KtBinaryExpression)?.takeIf(::checkAssignment)
    }

    fun tryFoldToAssignment(expression: KtExpression) {
        var lhs: KtExpression? = null
        var op: String? = null
        val psiFactory = KtPsiFactory(expression)
        fun KtBinaryExpression.replaceWithRHS() {
            if (lhs == null || op == null) {
                lhs = left!!.copy() as KtExpression
                op = operationReference.text
            }

            val rhs = right!!
            if (rhs is KtLambdaExpression && this.parent !is KtBlockExpression) {
                replace(psiFactory.createSingleStatementBlock(rhs))
            } else {
                replace(rhs)
            }
        }

        fun lift(e: KtExpression?) {
            when (e) {
                is KtWhenExpression -> e.entries.forEach { entry ->
                    getFoldableBranchedAssignment(entry.expression)?.replaceWithRHS() ?: lift(entry.expression?.lastBlockStatementOrThis())
                }

                is KtIfExpression -> e.branches.forEach { branch ->
                    getFoldableBranchedAssignment(branch)?.replaceWithRHS() ?: lift(branch?.lastBlockStatementOrThis())
                }

                is KtTryExpression -> e.tryBlockAndCatchBodies().forEach {
                    getFoldableBranchedAssignment(it)?.replaceWithRHS() ?: lift(it?.lastBlockStatementOrThis())
                }
            }
        }
        lift(expression)
        if (lhs != null && op != null) {
            expression.replace(psiFactory.createExpressionByPattern("$0 $1 $2", lhs!!, op!!, expression))
        }
    }

    fun KtAnalysisSession.getFoldableAssignmentNumber(expression: KtExpression?): Int {
        expression ?: return -1
        val assignments = linkedSetOf<KtBinaryExpression>()
        fun collectAssignmentsAndCheck(e: KtExpression?): Boolean = when (e) {
            is KtWhenExpression -> {
                val entries = e.entries
                // When the KtWhenExpression has missing cases with an else branch, we cannot fold it.
                if (!KtPsiUtil.checkWhenExpressionHasSingleElse(e) && e.getMissingCases().isNotEmpty()) false
                else entries.isNotEmpty() && entries.all { entry ->
                    val assignment = getFoldableBranchedAssignment(entry.expression)?.run { assignments.add(this) }
                    assignment != null || collectAssignmentsAndCheck(entry.expression?.lastBlockStatementOrThis())
                }
            }

            is KtIfExpression -> {
                val branches = e.branches
                val elseBranch = branches.lastOrNull()?.getStrictParentOfType<KtIfExpression>()?.`else`
                branches.size > 1 && elseBranch != null && branches.all { branch ->
                    val assignment = getFoldableBranchedAssignment(branch)?.run { assignments.add(this) }
                    assignment != null || collectAssignmentsAndCheck(branch?.lastBlockStatementOrThis())
                }
            }

            is KtTryExpression -> {
                e.tryBlockAndCatchBodies().all {
                    val assignment = getFoldableBranchedAssignment(it)?.run { assignments.add(this) }
                    assignment != null || collectAssignmentsAndCheck(it?.lastBlockStatementOrThis())
                }
            }

            is KtCallExpression -> {
                e.getKtType()?.isNothing ?: false
            }

            is KtBreakExpression, is KtContinueExpression, is KtThrowExpression, is KtReturnExpression -> true

            else -> false
        }
        if (!collectAssignmentsAndCheck(expression)) return -1
        val firstAssignment = assignments.firstOrNull { it.right?.isNull() != true } ?: assignments.firstOrNull() ?: return 0
        val leftType = firstAssignment.left?.let { it.getKtType() } ?: return 0
        val rightType = firstAssignment.right?.let { it.getKtType() } ?: return 0
        if (assignments.any { assignment -> !checkAssignmentsMatch(firstAssignment, assignment, leftType, rightType) }) {
            return -1
        }
        if (expression.anyDescendantOfType<KtBinaryExpression>(predicate = { binaryExpression ->
                if (binaryExpression.operationToken in KtTokens.ALL_ASSIGNMENTS) {
                    if (binaryExpression.getNonStrictParentOfType<KtFinallySection>() != null) {
                        checkAssignmentsMatch(firstAssignment, binaryExpression, leftType, rightType)
                    } else {
                        binaryExpression !in assignments
                    }
                } else {
                    false
                }
            })) {
            return -1
        }
        return assignments.size
    }

    /**
     * Returns whether the binary assignment expressions [first] and [second] match or not.
     * We say they match when they satisfy the following conditions:
     *  1. They have the same left operands
     *  2. They have the same operation tokens
     *  3. It satisfies one of the following:
     *    - The left operand is nullable and the right is null
     *    - The operation has a callable declaration with a single parameter, and we can cast [second] to the parameter
     *    - Types of right operands of [first] and [second] are the same
     */
    private fun KtAnalysisSession.checkAssignmentsMatch(
        first: KtBinaryExpression,
        second: KtBinaryExpression,
        leftType: KtType,
        rightTypeOfFirst: KtType,
    ): Boolean {
        // Check if they satisfy the above condition 1 and 2.
        val leftOfFirst = first.left ?: return false
        val leftOfSecond = second.left ?: return false
        if (leftOfFirst.text != leftOfSecond.text || first.operationToken != second.operationToken || leftOfFirst.mainReference?.resolve() != leftOfSecond.mainReference?.resolve()) return false

        // Check if they satisfy the first of condition 3.
        val isSecondRightNull = second.right?.isNull()
        if (isSecondRightNull == true && leftType.canBeNull) return true

        // Check if they satisfy the second of condition 3.
        val rightTypeOfSecond = second.right?.getKtType() ?: return false
        val operatorDeclaration = first.operationReference.mainReference.resolve()
        if (operatorDeclaration is KtCallableDeclaration && operatorDeclaration.module == second.module) {
            val parameterType = operatorDeclaration.valueParameters.singleOrNull()?.getReturnKtType()
            if (parameterType != null) {
                // It matches if
                //  - the operation parameter type matches the type of the right operator or
                //  - the operation parameter is nullable and the right operator of the second is null or
                //  - the operation parameter type with non-nullability matches the type of the right operator
                if (parameterType == rightTypeOfSecond) return true
                if (parameterType.isMarkedNullable) {
                    when {
                        isSecondRightNull == true -> return true
                        !rightTypeOfSecond.isMarkedNullable && parameterType.withNullability(KtTypeNullability.NON_NULLABLE) == rightTypeOfSecond -> return true
                    }
                }
            }
        }

        // Check if they satisfy the third of condition 3.
        return rightTypeOfFirst == rightTypeOfSecond || hasSameConstructor(
            rightTypeOfFirst, rightTypeOfSecond
        ) || (first.operationToken == KtTokens.EQ && isSubtypeOf(
            rightTypeOfSecond, leftType
        ))
    }

    /**
     * Returns true if [first] and [second] has the same constructor and the same type arguments.
     */
    private fun KtAnalysisSession.hasSameConstructor(first: KtType?, second: KtType?): Boolean {
        if (first is KtUsualClassType) {
            if (second !is KtUsualClassType) return false
            if (first.typeArguments != second.typeArguments) return false
        }
        val constructorsOfFirst = first?.expandedClassSymbol?.psi?.children?.filterIsInstance<KtConstructor<*>>() ?: return false
        val constructorsOfSecond = second?.expandedClassSymbol?.psi?.children?.filterIsInstance<KtConstructor<*>>() ?: return false
        return constructorsOfFirst == constructorsOfSecond
    }

    private fun KtAnalysisSession.isSubtypeOf(subtype: KtType?, parentType: KtType): Boolean {
        return subtype?.expandedClassSymbol?.superTypes?.any { it == parentType } == true
    }
}
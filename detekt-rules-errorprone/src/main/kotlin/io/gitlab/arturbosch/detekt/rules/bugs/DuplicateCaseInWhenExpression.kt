package io.gitlab.arturbosch.detekt.rules.bugs

import io.gitlab.arturbosch.detekt.api.ActiveByDefault
import io.gitlab.arturbosch.detekt.api.CodeSmell
import io.gitlab.arturbosch.detekt.api.Config
import io.gitlab.arturbosch.detekt.api.Entity
import io.gitlab.arturbosch.detekt.api.Rule
import org.jetbrains.kotlin.psi.KtWhenExpression

/**
 * Flags duplicate `case` statements in `when` expressions.
 *
 * If a `when` expression contains the same `case` statement multiple times they should be merged. Otherwise, it might be
 * easy to miss one of the cases when reading the code, leading to unwanted side effects.
 *
 * <noncompliant>
 * when (i) {
 *     1 -> println("one")
 *     1 -> println("one")
 *     else -> println("else")
 * }
 * </noncompliant>
 *
 * <compliant>
 * when (i) {
 *     1 -> println("one")
 *     else -> println("else")
 * }
 * </compliant>
 */
@ActiveByDefault(since = "1.0.0")
@Deprecated("Rule deprecated as compiler performs this check by default")
class DuplicateCaseInWhenExpression(config: Config) : Rule(
    config,
    "Duplicated `case` statements in a `when` expression detected. Both cases should be merged."
) {

    override fun visitWhenExpression(expression: KtWhenExpression) {
        val distinctEntries = expression.entries.distinctBy { entry -> entry.conditions.joinToString { it.text } }

        if (distinctEntries != expression.entries) {
            val duplicateEntries = expression.entries
                .subtract(distinctEntries)
                .map { entry -> entry.conditions.joinToString { it.text } }
            report(
                CodeSmell(
                    issue,
                    Entity.from(expression),
                    "When expression has multiple case statements for ${duplicateEntries.joinToString("; ")}."
                )
            )
        }
    }
}

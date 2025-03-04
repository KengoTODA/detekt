package io.gitlab.arturbosch.detekt.api

/**
 * A code smell indicates any possible design problem inside a program's source code.
 * The type of a code smell is described by an [Issue].
 *
 * If the design problem manifests by different source locations, references to these
 * locations can be stored in additional [Entity]'s.
 */
open class CodeSmell(
    final override val issue: Issue,
    final override val entity: Entity,
    final override val message: String,
    final override val references: List<Entity> = emptyList()
) : Finding {
    init {
        require(message.isNotBlank()) { "The message should not be empty" }
    }

    internal var internalSeverity: Severity? = null
    override val severity: Severity
        get() = internalSeverity ?: super.severity

    override fun compact(): String = "${issue.id} - ${entity.compact()}"

    override fun compactWithSignature(): String = compact() + " - Signature=" + entity.signature

    override fun toString(): String {
        return "CodeSmell(issue=$issue, " +
            "entity=$entity, " +
            "message=$message, " +
            "references=$references, " +
            "severity=$severity)"
    }
}

/**
 * Represents a code smell that can be auto-corrected.
 *
 * @see CodeSmell
 */
open class CorrectableCodeSmell(
    issue: Issue,
    entity: Entity,
    message: String,
    references: List<Entity> = emptyList(),
    val autoCorrectEnabled: Boolean
) : CodeSmell(
    issue,
    entity,
    message,
    references
) {
    override fun toString(): String {
        return "CorrectableCodeSmell(" +
            "autoCorrectEnabled=$autoCorrectEnabled, " +
            "issue=$issue, " +
            "entity=$entity, " +
            "message=$message, " +
            "references=$references, " +
            "severity=$severity)"
    }
}

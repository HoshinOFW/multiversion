package com.github.hoshinofw.multiversion.engine

enum class MergeAction {
    /** Full AST merge was performed. */
    MERGED,
    /** Current file is authoritative (no triggers or no base). */
    COPIED_CURRENT,
    /** Base propagated verbatim (no current overlay). */
    COPIED_BASE,
    /** Output should be deleted (@DeleteClass or both files absent). */
    DELETED,
    /** No type declaration found in current file. */
    SKIPPED,
}

data class MergeResult(
    /** The merged/copied file content, or null when action is DELETED or SKIPPED. */
    val content: String?,
    /** Origin map TSV lines (without trailing newlines). Empty when not applicable. */
    val originEntries: List<String>,
    /** What the engine decided to do. */
    val action: MergeAction,
)

package io.github.tanvesh.explainer

/** The cleaned, size-capped part of a build log that is actually analysed. */
class LogExcerpt implements Serializable {
    String text
    String failedStage
    int totalLines
    int keptLines
    int errorLineCount
}

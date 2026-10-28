package io.github.tanvesh.explainer

import com.cloudbees.groovy.cps.NonCPS

/** The result shown to developers: what broke, why, and what to do next. */
class Explanation implements Serializable {
    String category = 'UNKNOWN'
    String summary = ''
    String rootCause = ''
    List<String> suggestedFixes = []
    String failedStage
    String evidence
    String confidence = 'low'      // low | medium | high
    String source = 'none'         // llm | rules | none
    String model
    String llmError                // set when the LLM was tried and failed
    long durationMs

    @NonCPS
    Map toMap() {
        return [category      : category, summary: summary, rootCause: rootCause,
                suggestedFixes: suggestedFixes, failedStage: failedStage, evidence: evidence,
                confidence    : confidence, source: source, model: model,
                llmError      : llmError, durationMs: durationMs]
    }
}

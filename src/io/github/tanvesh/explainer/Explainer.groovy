package io.github.tanvesh.explainer

import com.cloudbees.groovy.cps.NonCPS

/**
 * Orchestrates one explanation:  raw log -> clean excerpt -> rule hint -> LLM -> Explanation.
 * Falls back to the rule-based answer whenever the LLM is missing, slow, or returns junk,
 * so a failed build always gets some explanation.
 */
class Explainer implements Serializable {

    LogPreprocessor preprocessor = new LogPreprocessor()
    LlmClient llm                 // null or unconfigured = rules only

    @NonCPS
    Explanation explain(String rawLog, Map buildInfo = [:]) {
        long started = System.currentTimeMillis()
        LogExcerpt excerpt = preprocessor.process(rawLog)
        Map hint = KnownPatterns.match(excerpt.text)

        Explanation result = null
        String llmError = null

        if (llm?.isConfigured() && excerpt.text?.trim()) {
            try {
                String reply = llm.chat(PromptBuilder.build(excerpt, hint, buildInfo))
                result = ExplanationParser.parse(reply)
                result.source = 'llm'
                result.model = llm.model
            } catch (Exception e) {
                llmError = "${e.class.simpleName}: ${e.message}".toString()
            }
        }

        if (result == null) {
            result = fromRules(hint)
            result.llmError = llmError
        }

        result.failedStage = excerpt.failedStage
        if (!result.evidence && hint) {
            result.evidence = hint.evidence
        }
        result.durationMs = System.currentTimeMillis() - started
        return result
    }

    @NonCPS
    static Explanation fromRules(Map hint) {
        Explanation e = new Explanation()
        if (hint) {
            e.category = hint.category
            e.summary = hint.title
            e.rootCause = "The log matches a known ${hint.category.toLowerCase().replace('_', ' ')} pattern. " +
                    'This is a rule-based diagnosis; configure an LLM for a log-specific explanation.'
            e.suggestedFixes = new ArrayList<String>(hint.fixes as List)
            e.evidence = hint.evidence
            e.confidence = 'medium'
            e.source = 'rules'
        } else {
            e.summary = 'Build failed, cause not recognised'
            e.rootCause = 'No known failure pattern matched and no LLM explanation was available. ' +
                    'Check the last error lines in the console output.'
            e.suggestedFixes = ['Open the console log and search for the first ERROR or FAILED line.']
            e.source = 'none'
        }
        return e
    }
}

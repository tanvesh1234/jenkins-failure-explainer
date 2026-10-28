package io.github.tanvesh.explainer

import com.cloudbees.groovy.cps.NonCPS
import groovy.json.JsonSlurper

/**
 * Parses the LLM reply into an Explanation. Lenient on purpose: small local models often wrap
 * JSON in ```json fences, add a sentence before it, or use slightly different key names.
 */
class ExplanationParser implements Serializable {

    static final Set<String> CATEGORIES = [
            'COMPILATION_ERROR', 'TEST_FAILURE', 'DEPENDENCY_RESOLUTION', 'OUT_OF_MEMORY', 'DISK_FULL',
            'NETWORK', 'AUTH', 'FILE_PERMISSION', 'MISSING_TOOL', 'DOCKER_DAEMON', 'SCRIPT_APPROVAL',
            'TIMEOUT', 'CONFIGURATION', 'INFRASTRUCTURE', 'UNKNOWN'] as Set

    @NonCPS
    static Explanation parse(String reply) {
        Map data = extractJson(reply)
        if (data == null) {
            throw new IllegalArgumentException('LLM reply did not contain a JSON object')
        }
        Explanation e = new Explanation()
        String category = str(data, ['category', 'type'])?.toUpperCase()?.replaceAll(/[\s-]+/, '_')
        e.category = CATEGORIES.contains(category) ? category : 'UNKNOWN'
        e.summary = str(data, ['summary', 'title']) ?: ''
        e.rootCause = str(data, ['root_cause', 'rootCause', 'cause', 'explanation']) ?: ''
        e.evidence = str(data, ['evidence', 'log_line', 'evidence_line'])
        def fixes = data['suggested_fixes'] ?: data['suggestedFixes'] ?: data['fixes'] ?: data['fix']
        if (fixes instanceof List) {
            e.suggestedFixes = fixes.collect { it.toString().trim() }.findAll { it }
        } else if (fixes) {
            e.suggestedFixes = [fixes.toString().trim()]
        }
        String conf = str(data, ['confidence'])?.toLowerCase()
        e.confidence = (conf in ['low', 'medium', 'high']) ? conf : 'medium'
        if (!e.summary && !e.rootCause) {
            throw new IllegalArgumentException('LLM reply JSON had neither summary nor root_cause')
        }
        return e
    }

    @NonCPS
    static Map extractJson(String reply) {
        if (!reply) {
            return null
        }
        String s = reply.trim()
        // Strip ``` fences
        s = s.replaceAll(/(?s)^```(?:json)?\s*/, '').replaceAll(/(?s)\s*```\s*$/, '')
        // Try whole text, then the outermost {...} block
        List<String> candidates = [s]
        int start = s.indexOf('{')
        int end = s.lastIndexOf('}')
        if (start >= 0 && end > start) {
            candidates << s.substring(start, end + 1)
        }
        for (String c : candidates) {
            try {
                def parsed = new JsonSlurper().parseText(c)
                if (parsed instanceof Map) {
                    // JsonSlurper's lazy maps are not serializable; copy into a plain map.
                    return new LinkedHashMap(parsed)
                }
            } catch (Exception ignored) {
                // try next candidate
            }
        }
        return null
    }

    @NonCPS
    private static String str(Map data, List<String> keys) {
        for (String k : keys) {
            def v = data[k]
            if (v != null && v.toString().trim()) {
                return v.toString().trim()
            }
        }
        return null
    }
}

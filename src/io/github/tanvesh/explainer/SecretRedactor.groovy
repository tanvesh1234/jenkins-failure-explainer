package io.github.tanvesh.explainer

import com.cloudbees.groovy.cps.NonCPS

/**
 * Removes secrets from a build log before any of it leaves the Jenkins controller.
 *
 * Jenkins already masks bound credentials as ****, but logs often leak tokens that were
 * never bound (echoed env vars, curl -v output, stack traces with connection strings).
 * Everything sent to an LLM passes through here first.
 */
class SecretRedactor implements Serializable {

    static final String MASK = '[REDACTED]'

    // Order matters: the private-key block must run before generic token rules.
    private static final List<List> RULES = [
        // PEM private keys (multi-line)
        [~/(?s)-----BEGIN [A-Z ]*PRIVATE KEY-----.*?-----END [A-Z ]*PRIVATE KEY-----/, MASK],
        // AWS access key ids
        [~/\b(AKIA|ASIA)[0-9A-Z]{16}\b/, MASK],
        // GitHub tokens
        [~/\b(ghp|gho|ghu|ghs|ghr)_[A-Za-z0-9]{36,}\b/, MASK],
        [~/\bgithub_pat_[A-Za-z0-9_]{40,}\b/, MASK],
        // Slack tokens and webhooks
        [~/\bxox[abprs]-[A-Za-z0-9-]{10,}\b/, MASK],
        [~/https:\/\/hooks\.slack\.com\/services\/[A-Za-z0-9\/]+/, MASK],
        // OpenAI / Groq / Anthropic / Google style API keys
        [~/\b(sk|gsk)[-_][A-Za-z0-9_-]{20,}\b/, MASK],
        [~/\bAIza[0-9A-Za-z_-]{35}\b/, MASK],
        // JWTs
        [~/\beyJ[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}\b/, MASK],
        // Authorization headers
        [~/(?i)(authorization:\s*(bearer|basic|token)\s+)\S+/, '$1' + MASK],
        // user:password@ in URLs
        [~/(?i)([a-z][a-z0-9+.-]*:\/\/[^\s:\/@]+:)[^\s@\/]+@/, '$1' + MASK + '@'],
        // key=value / key: value pairs whose key looks secret
        [~/(?i)\b([a-z0-9_.-]*(password|passwd|pwd|secret|token|api[_-]?key|access[_-]?key|client[_-]?secret)[a-z0-9_.-]*)(\s*[=:]\s*)("[^"]*"|'[^']*'|\S+)/,
         '$1$3' + MASK],
    ]

    @NonCPS
    static String redact(String text) {
        if (text == null) {
            return ''
        }
        String out = text
        RULES.each { rule ->
            out = out.replaceAll(rule[0], rule[1] as String)
        }
        return out
    }
}

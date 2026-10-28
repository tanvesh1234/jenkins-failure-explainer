package io.github.tanvesh.explainer

import com.cloudbees.groovy.cps.NonCPS

/** Builds the chat messages sent to the LLM. Asks for strict JSON so the answer can be parsed. */
class PromptBuilder implements Serializable {

    static final String SYSTEM_PROMPT = '''You are a senior DevOps engineer reviewing a failed Jenkins build.
You get an excerpt of the console log (secrets already removed) and sometimes a rule-based hint.
Work out the single most likely root cause. Be specific: name the file, test, dependency, command or
setting from the log. Do not invent details that are not in the log. If the log does not show the
cause, say so and set confidence to "low".

Reply with ONLY a JSON object, no markdown, in exactly this shape:
{
  "category": one of "COMPILATION_ERROR","TEST_FAILURE","DEPENDENCY_RESOLUTION","OUT_OF_MEMORY",
              "DISK_FULL","NETWORK","AUTH","FILE_PERMISSION","MISSING_TOOL","DOCKER_DAEMON",
              "SCRIPT_APPROVAL","TIMEOUT","CONFIGURATION","INFRASTRUCTURE","UNKNOWN",
  "summary": "one sentence a developer can read in a Slack notification",
  "root_cause": "two to four sentences explaining why the build failed, citing the log",
  "evidence": "the single most relevant log line, copied exactly",
  "suggested_fixes": ["concrete step 1", "concrete step 2"],
  "confidence": "low" | "medium" | "high"
}'''

    @NonCPS
    static List<Map> build(LogExcerpt excerpt, Map hint, Map buildInfo) {
        StringBuilder user = new StringBuilder()
        user.append("Job: ${buildInfo?.jobName ?: 'unknown'}\n")
        user.append("Build: #${buildInfo?.buildNumber ?: '?'}\n")
        if (excerpt.failedStage) {
            user.append("Failed stage: ${excerpt.failedStage}\n")
        }
        user.append("Log lines: ${excerpt.totalLines} total, ${excerpt.keptLines} shown below\n")
        if (hint) {
            user.append("Rule-based hint: ${hint.category} (${hint.title}). Matched line: ${hint.evidence}\n")
            user.append('Use the hint only if the log supports it.\n')
        }
        user.append('\n--- LOG EXCERPT START ---\n')
        user.append(excerpt.text)
        user.append('--- LOG EXCERPT END ---\n')

        return [
                [role: 'system', content: SYSTEM_PROMPT],
                [role: 'user', content: user.toString()]
        ]
    }
}

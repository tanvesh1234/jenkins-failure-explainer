import groovy.json.JsonOutput
import io.github.tanvesh.explainer.Explainer
import io.github.tanvesh.explainer.Explanation
import io.github.tanvesh.explainer.LlmClient
import io.github.tanvesh.explainer.Renderer

/**
 * Pipeline step: explainFailure()
 *
 * Call it from post { failure { ... } }. It reads this build's console log, asks an LLM why
 * the build failed, and prints / archives / posts the answer. It never fails the build itself
 * unless failOnError: true is passed.
 *
 *   post {
 *     failure {
 *       explainFailure()                                   // settings from env vars
 *       explainFailure(model: 'qwen2.5:3b',                // or per job
 *                      baseUrl: 'http://ollama:11434/v1',
 *                      credentialsId: null,                // no key for local Ollama
 *                      webhookCredentialsId: 'slack-webhook')
 *     }
 *   }
 *
 * Settings (argument, else environment variable, else default):
 *   baseUrl              LLM_BASE_URL          OpenAI-compatible base URL, e.g. https://api.groq.com/openai/v1
 *   model                LLM_MODEL             model id at that provider
 *   credentialsId        LLM_CREDENTIALS_ID    Secret-text credential with the API key (default 'llm-api-key')
 *   maxLogLines          -                     how many log lines to read from the end (default 5000)
 *   webhookUrl           EXPLAINER_WEBHOOK_URL Slack-compatible incoming webhook (plain URL)
 *   webhookCredentialsId -                     same, but stored as a Secret-text credential (preferred)
 *   archive              -                     archive failure-explanation.md/.json (default true)
 *   setDescription       -                     put the one-line summary on the build page (default true)
 *   failOnError          -                     rethrow if the step itself breaks (default false)
 *
 * Returns a Map with the explanation fields (category, summary, rootCause, suggestedFixes, ...).
 */
def call(Map cfg = [:]) {
    Map buildInfo = [jobName    : env.JOB_NAME,
                     buildNumber: env.BUILD_NUMBER,
                     buildUrl   : env.BUILD_URL]
    try {
        String baseUrl = cfg.baseUrl ?: env.LLM_BASE_URL
        String model = cfg.model ?: env.LLM_MODEL
        String credId = cfg.containsKey('credentialsId') ? cfg.credentialsId :
                (env.LLM_CREDENTIALS_ID ?: 'llm-api-key')
        int maxLines = (cfg.maxLogLines ?: 5000) as int

        String log = readOwnLog(maxLines)
        Map out = null

        if (baseUrl && model && credId) {
            try {
                withCredentials([string(credentialsId: credId, variable: 'EXPLAINER_LLM_KEY')]) {
                    out = analyse(log, baseUrl, model, env.EXPLAINER_LLM_KEY, buildInfo)
                }
            } catch (Exception e) {
                echo "explainFailure: could not use credential '${credId}' (${e.message}); trying without an API key."
            }
        }
        if (out == null) {
            out = analyse(log, baseUrl, model, '', buildInfo)
        }

        echo out.console

        if (cfg.setDescription != false) {
            String line = out.oneLine
            currentBuild.description = line.length() > 250 ? line.take(247) + '...' : line
        }

        if (cfg.archive != false) {
            try {
                writeFile file: 'failure-explanation.md', text: out.markdown
                writeFile file: 'failure-explanation.json', text: out.json
                archiveArtifacts artifacts: 'failure-explanation.md,failure-explanation.json',
                        allowEmptyArchive: true, fingerprint: false
            } catch (Exception e) {
                echo "explainFailure: skipped archiving (${e.message}). Call it inside a node/agent to archive."
            }
        }

        sendNotification(cfg, out.payload)
        return out.data
    } catch (Exception e) {
        echo "explainFailure: could not explain this failure: ${e}"
        if (cfg.failOnError) {
            throw e
        }
        return null
    }
}

/** Reads the last N lines of this build's own console log. Trusted global library only. */
@NonCPS
String readOwnLog(int maxLines) {
    return currentBuild.rawBuild.getLog(maxLines).join('\n')
}

/** All non-serialisable work (HTTP, regex matchers) happens in this one @NonCPS call. */
@NonCPS
Map analyse(String log, String baseUrl, String model, String apiKey, Map buildInfo) {
    LlmClient llm = (baseUrl && model) ? new LlmClient(baseUrl: baseUrl, model: model, apiKey: apiKey) : null
    Explanation e = new Explainer(llm: llm).explain(log, buildInfo)
    // Return plain strings and maps only, so nothing unserialisable is held by the CPS program.
    return [console: Renderer.console(e),
            markdown: Renderer.markdown(e, buildInfo),
            json    : Renderer.json(e),
            oneLine : Renderer.oneLine(e),
            payload : Renderer.chatPayload(e, buildInfo),
            data    : new LinkedHashMap(e.toMap())]
}

void sendNotification(Map cfg, String payload) {
    String url = cfg.webhookUrl ?: env.EXPLAINER_WEBHOOK_URL
    if (cfg.webhookCredentialsId) {
        try {
            withCredentials([string(credentialsId: cfg.webhookCredentialsId, variable: 'EXPLAINER_HOOK')]) {
                echo postJson(env.EXPLAINER_HOOK, payload)
            }
        } catch (Exception e) {
            echo "explainFailure: notification skipped, credential '${cfg.webhookCredentialsId}' not usable (${e.message})"
        }
    } else if (url) {
        echo postJson(url, payload)
    }
}

@NonCPS
String postJson(String url, String payload) {
    try {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection()
        c.requestMethod = 'POST'
        c.connectTimeout = 10000
        c.readTimeout = 15000
        c.doOutput = true
        c.setRequestProperty('Content-Type', 'application/json')
        c.outputStream.withWriter('UTF-8') { it << payload }
        int code = c.responseCode
        c.disconnect()
        return "explainFailure: notification sent (HTTP ${code})"
    } catch (Exception e) {
        return "explainFailure: notification failed (${e.class.simpleName})"
    }
}

package io.github.tanvesh.explainer

import com.cloudbees.groovy.cps.NonCPS
import groovy.json.JsonOutput
import groovy.json.JsonSlurper

/**
 * Minimal client for any OpenAI-compatible /chat/completions endpoint.
 *
 * One code path covers the free options: Groq, Google Gemini (OpenAI compatibility),
 * OpenRouter free models, and local Ollama or LM Studio. Only the base URL, model and key change.
 * Uses java.net only, so no extra Jenkins plugins are needed.
 */
class LlmClient implements Serializable {

    String baseUrl            // e.g. https://api.groq.com/openai/v1  or  http://ollama:11434/v1
    String model
    String apiKey             // may be empty for local Ollama
    double temperature = 0.1
    int maxTokens = 700
    int connectTimeoutMs = 10000
    int readTimeoutMs = 90000
    boolean jsonMode = true   // asks for response_format json_object; retried without it if rejected

    @NonCPS
    boolean isConfigured() {
        return baseUrl && model
    }

    /** Sends the messages and returns the assistant's text. Throws IOException on failure. */
    @NonCPS
    String chat(List<Map> messages) {
        try {
            return post(messages, jsonMode)
        } catch (LlmHttpException e) {
            // Some providers or models reject response_format. Retry once without it.
            if (jsonMode && e.status == 400) {
                return post(messages, false)
            }
            throw e
        }
    }

    @NonCPS
    private String post(List<Map> messages, boolean withJsonFormat) {
        Map body = [model: model, messages: messages, temperature: temperature, max_tokens: maxTokens]
        if (withJsonFormat) {
            body.response_format = [type: 'json_object']
        }
        String url = baseUrl.replaceAll(/\/+$/, '') + '/chat/completions'

        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection()
        try {
            conn.requestMethod = 'POST'
            conn.connectTimeout = connectTimeoutMs
            conn.readTimeout = readTimeoutMs
            conn.doOutput = true
            conn.setRequestProperty('Content-Type', 'application/json')
            conn.setRequestProperty('Accept', 'application/json')
            if (apiKey) {
                conn.setRequestProperty('Authorization', "Bearer ${apiKey}")
            }
            conn.outputStream.withWriter('UTF-8') { it << JsonOutput.toJson(body) }

            int status = conn.responseCode
            InputStream stream = status < 400 ? conn.inputStream : conn.errorStream
            String text = stream ? stream.getText('UTF-8') : ''
            if (status >= 400) {
                throw new LlmHttpException(status, SecretRedactor.redact(text.take(500)))
            }
            def json = new JsonSlurper().parseText(text)
            String content = json?.choices?.getAt(0)?.message?.content
            if (!content) {
                throw new IOException('LLM response had no choices[0].message.content')
            }
            return content
        } finally {
            conn.disconnect()
        }
    }
}

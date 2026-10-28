package io.github.tanvesh.explainer

/** Non-2xx reply from the LLM endpoint. Kept separate so callers can check the status code. */
class LlmHttpException extends IOException {
    int status

    LlmHttpException(int status, String body) {
        super("LLM endpoint returned HTTP ${status}: ${body}".toString())
        this.status = status
    }
}

package io.github.tanvesh.explainer

import org.junit.Test

class ExplainerTest {

    /** LLM double that returns a canned reply (or throws) and records the prompt it got. */
    static class FakeLlm extends LlmClient {
        String reply
        Exception failure
        List<Map> lastMessages

        @Override
        String chat(List<Map> messages) {
            lastMessages = messages
            if (failure) {
                throw failure
            }
            return reply
        }
    }

    static String log(String name) { LogPreprocessorTest.sample(name) }

    @Test
    void usesRulesWhenNoLlmIsConfigured() {
        Explanation e = new Explainer().explain(log('java-oom'), [jobName: 'reporting-batch', buildNumber: '7'])
        assert e.source == 'rules'
        assert e.category == 'OUT_OF_MEMORY'
        assert e.failedStage == 'Integration Tests'
        assert e.evidence.contains('OutOfMemoryError')
        assert e.llmError == null
    }

    @Test
    void usesTheLlmAnswerAndSendsHintAndCleanExcerpt() {
        FakeLlm llm = new FakeLlm(baseUrl: 'http://fake', model: 'fake-model', reply: ExplanationParserTest.GOOD)
        Explanation e = new Explainer(llm: llm).explain(log('pytest-failures'), [jobName: 'billing-api', buildNumber: '42'])
        assert e.source == 'llm'
        assert e.model == 'fake-model'
        assert e.category == 'TEST_FAILURE'
        assert e.failedStage == 'Test'

        String prompt = llm.lastMessages[1].content
        assert prompt.contains('Job: billing-api')
        assert prompt.contains('Failed stage: Test')
        assert prompt.contains('Rule-based hint: TEST_FAILURE')
        assert !prompt.contains('Sup3rS3cret')
        assert !prompt.contains('[Pipeline]')
    }

    @Test
    void fallsBackToRulesWhenTheLlmFails() {
        FakeLlm llm = new FakeLlm(baseUrl: 'http://fake', model: 'm', failure: new IOException('connect timed out'))
        Explanation e = new Explainer(llm: llm).explain(log('docker-daemon-down'))
        assert e.source == 'rules'
        assert e.category == 'DOCKER_DAEMON'
        assert e.llmError == 'IOException: connect timed out'
    }

    @Test
    void fallsBackToRulesWhenTheLlmReturnsJunk() {
        FakeLlm llm = new FakeLlm(baseUrl: 'http://fake', model: 'm', reply: 'I think it is broken.')
        Explanation e = new Explainer(llm: llm).explain(log('npm-eresolve'))
        assert e.source == 'rules'
        assert e.category == 'DEPENDENCY_RESOLUTION'
        assert e.llmError.startsWith('IllegalArgumentException')
    }

    @Test
    void stillAnswersForUnrecognisedFailures() {
        Explanation e = new Explainer().explain('step one\nstep two\nsomething odd happened')
        assert e.source == 'none'
        assert e.category == 'UNKNOWN'
        assert e.suggestedFixes
    }
}

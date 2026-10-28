package io.github.tanvesh.explainer

import groovy.json.JsonSlurper
import org.junit.Test

class RendererTest {

    Explanation sample() {
        Explanation e = ExplanationParser.parse(ExplanationParserTest.GOOD)
        e.source = 'llm'
        e.model = 'llama-test'
        e.failedStage = 'Test'
        e.durationMs = 1234
        return e
    }

    Map info = [jobName: 'billing-api', buildNumber: '42', buildUrl: 'http://jenkins/job/billing-api/42/']

    @Test
    void consoleShowsTheEssentials() {
        String out = Renderer.console(sample())
        assert out.contains('BUILD FAILURE EXPLAINED')
        assert out.contains('Category   : TEST_FAILURE')
        assert out.contains('Stage      : Test')
        assert out.contains('  1. Round once on the invoice total')
        assert out.contains('Source: LLM (llama-test)')
    }

    @Test
    void markdownHasSections() {
        String md = Renderer.markdown(sample(), info)
        assert md.startsWith('# Build failure: billing-api #42')
        assert md.contains('## Root cause')
        assert md.contains('## How to fix')
        assert md.contains('http://jenkins/job/billing-api/42/')
    }

    @Test
    void chatPayloadIsValidJsonWithConsoleLink() {
        Map payload = new JsonSlurper().parseText(Renderer.chatPayload(sample(), info)) as Map
        assert payload.text.contains('billing-api #42 failed')
        assert payload.text.contains('<http://jenkins/job/billing-api/42/console|Open console log>')
    }

    @Test
    void oneLineAndJson() {
        assert Renderer.oneLine(sample()) == "[TEST_FAILURE] in 'Test': Two GST tests fail after the rounding change"
        assert new JsonSlurper().parseText(Renderer.json(sample())).category == 'TEST_FAILURE'
    }
}

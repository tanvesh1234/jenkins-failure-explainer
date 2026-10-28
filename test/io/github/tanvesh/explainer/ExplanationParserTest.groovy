package io.github.tanvesh.explainer

import org.junit.Test

class ExplanationParserTest {

    static final String GOOD = '''{"category":"TEST_FAILURE","summary":"Two GST tests fail after the rounding change",
"root_cause":"Rounding moved to per line item.","evidence":"2 failed, 33 passed",
"suggested_fixes":["Round once on the invoice total","Update the expected values"],"confidence":"high"}'''

    @Test
    void parsesStrictJson() {
        Explanation e = ExplanationParser.parse(GOOD)
        assert e.category == 'TEST_FAILURE'
        assert e.summary.startsWith('Two GST tests')
        assert e.suggestedFixes.size() == 2
        assert e.confidence == 'high'
        assert e.evidence == '2 failed, 33 passed'
    }

    @Test
    void parsesFencedJsonAndJsonWithProse() {
        assert ExplanationParser.parse('```json\n' + GOOD + '\n```').category == 'TEST_FAILURE'
        assert ExplanationParser.parse('Sure! Here is the analysis:\n' + GOOD + '\nHope it helps.').category == 'TEST_FAILURE'
    }

    @Test
    void acceptsAlternativeKeysAndNormalisesValues() {
        Explanation e = ExplanationParser.parse(
                '{"type":"out of memory","title":"Heap too small","cause":"Loads 4.8M rows","fix":"Stream rows","confidence":"HIGH"}')
        assert e.category == 'OUT_OF_MEMORY'
        assert e.rootCause == 'Loads 4.8M rows'
        assert e.suggestedFixes == ['Stream rows']
        assert e.confidence == 'high'
    }

    @Test
    void unknownCategoryAndConfidenceFallBackSafely() {
        Explanation e = ExplanationParser.parse('{"category":"COSMIC_RAYS","summary":"x","confidence":"certain"}')
        assert e.category == 'UNKNOWN'
        assert e.confidence == 'medium'
    }

    @Test(expected = IllegalArgumentException)
    void rejectsReplyWithoutJson() {
        ExplanationParser.parse('The build failed because of a test.')
    }

    @Test(expected = IllegalArgumentException)
    void rejectsJsonWithoutContent() {
        ExplanationParser.parse('{"category":"TEST_FAILURE"}')
    }
}

package io.github.tanvesh.explainer

import org.junit.Test

class LogPreprocessorTest {

    static String sample(String name) {
        new File("demo/logs/${name}.log").getText('UTF-8')
    }

    @Test
    void stripsAnsiConsoleNotesTimestampsAndPipelineNoise() {
        String raw = '\u001B[8mha:////4KbQ0sAAAA==\u001B[0m[Pipeline] sh\n' +
                '2026-10-27T09:12:44.120Z [\u001B[1;31mERROR\u001B[m] boom\n'
        LogExcerpt ex = new LogPreprocessor().process(raw)
        assert ex.text.trim() == '[ERROR] boom'
        assert !ex.text.contains('[Pipeline]')
        assert !ex.text.contains('\u001B')
    }

    @Test
    void detectsTheFailedStage() {
        Map expected = ['maven-compile-error': 'Build',
                        'npm-eresolve'       : 'Install',
                        'pytest-failures'    : 'Test',
                        'java-oom'           : 'Integration Tests',
                        'docker-daemon-down' : 'Docker Image']
        expected.each { name, stage ->
            assert new LogPreprocessor().process(sample(name)).failedStage == stage: name
        }
    }

    @Test
    void keepsTheErrorLinesAndRedactsSecrets() {
        LogExcerpt ex = new LogPreprocessor().process(sample('pytest-failures'))
        assert ex.text.contains('2 failed, 33 passed')
        assert ex.text.contains("assert Decimal('353.97') == Decimal('353.96')")
        assert !ex.text.contains('Sup3rS3cret')
        assert !ex.text.contains('gsk_live_')
    }

    @Test
    void shrinksLongLogsAroundTheErrors() {
        List<String> lines = (1..20000).collect { "[INFO] compiling module ${it}".toString() }
        lines.add(10000, '[ERROR] Something broke in module 10000')
        lines << 'BUILD FAILURE'
        LogExcerpt ex = new LogPreprocessor().process(lines.join('\n'))
        assert ex.totalLines == 20002
        assert ex.keptLines < 100
        assert ex.text.contains('Something broke in module 10000')
        assert ex.text.contains('lines omitted')
        assert ex.text.trim().endsWith('BUILD FAILURE')
    }

    @Test
    void capsTheExcerptSizeKeepingTheEnd() {
        List<String> lines = (1..3000).collect { "ERROR line ${it} ".toString() + ('x' * 50) }
        LogExcerpt ex = new LogPreprocessor(maxChars: 2000).process(lines.join('\n'))
        assert ex.text.length() <= 2000 + 40
        assert ex.text.contains('ERROR line 3000')
        assert ex.text.startsWith('... [excerpt truncated] ...')
    }

    @Test
    void ignoresHarmlessErrorWords() {
        LogExcerpt ex = new LogPreprocessor(tailLines: 0).process(
                'Tests run: 5, Failures: 0, Errors: 0\nCompiled with 0 errors\nall good')
        assert ex.errorLineCount == 0
    }

    @Test
    void handlesEmptyLog() {
        LogExcerpt ex = new LogPreprocessor().process('')
        assert ex.text == ''
        assert ex.failedStage == null
    }
}

package io.github.tanvesh.explainer

import org.junit.Test

class KnownPatternsTest {

    @Test
    void classifiesEverySampleLog() {
        Map expected = ['maven-compile-error': 'COMPILATION_ERROR',
                        'npm-eresolve'       : 'DEPENDENCY_RESOLUTION',
                        'pytest-failures'    : 'TEST_FAILURE',
                        'java-oom'           : 'OUT_OF_MEMORY',
                        'docker-daemon-down' : 'DOCKER_DAEMON']
        expected.each { name, category ->
            String text = new LogPreprocessor().process(LogPreprocessorTest.sample(name)).text
            Map hit = KnownPatterns.match(text)
            assert hit?.category == category: name
            assert hit.evidence
            assert hit.fixes
        }
    }

    @Test
    void classifiesOneLinersFromOtherEcosystems() {
        assert KnownPatterns.match('error TS2339: Property x does not exist').category == 'COMPILATION_ERROR'
        assert KnownPatterns.match('ERROR: No matching distribution found for torch==9.9').category == 'DEPENDENCY_RESOLUTION'
        assert KnownPatterns.match('Tests run: 40, Failures: 2, Errors: 0, Skipped: 1').category == 'TEST_FAILURE'
        assert KnownPatterns.match('write /tmp/x: No space left on device').category == 'DISK_FULL'
        assert KnownPatterns.match('curl: (6) Could not resolve host: nexus.acme.io').category == 'NETWORK'
        assert KnownPatterns.match('git@github.com: Permission denied (publickey).').category == 'AUTH'
        assert KnownPatterns.match('./deploy.sh: line 3: kubectl: command not found').category == 'MISSING_TOOL'
        assert KnownPatterns.match('Scripts not permitted to use method java.lang.String execute').category == 'SCRIPT_APPROVAL'
        assert KnownPatterns.match('Timeout has been exceeded').category == 'TIMEOUT'
    }

    @Test
    void returnsNullWhenNothingMatches() {
        assert KnownPatterns.match('everything is fine\nreally') == null
        assert KnownPatterns.match('') == null
    }
}

package io.github.tanvesh.explainer

import org.junit.Test

class SecretRedactorTest {

    private static String r(String s) { SecretRedactor.redact(s) }

    @Test
    void redactsCloudAndVcsTokens() {
        assert !r('key AKIAIOSFODNN7EXAMPLE used').contains('AKIAIOSFODNN7EXAMPLE')
        assert !r('token ghp_abcdefghijklmnopqrstuvwxyz0123456789AB').contains('ghp_abcdef')
        assert !r('slack xoxb-123456789012-abcdefghij').contains('xoxb-1234')
        assert !r('GROQ gsk_abcdefghijklmnopqrstuvwxyz123456').contains('gsk_abcdef')
        assert !r('openai sk-proj-abcdefghijklmnopqrstuvwxyz').contains('sk-proj-abc')
        assert !r('google AIzaSyA1234567890abcdefghijklmnopqrstuv').contains('AIzaSyA123')
    }

    @Test
    void redactsKeyValueSecretsButKeepsKeyName() {
        String out = r('export DB_PASSWORD=Sup3rS3cret! API_TOKEN=abc123 USER=jenkins')
        assert out.contains('DB_PASSWORD=' + SecretRedactor.MASK)
        assert out.contains('API_TOKEN=' + SecretRedactor.MASK)
        assert !out.contains('Sup3rS3cret')
        assert !out.contains('abc123')
        assert out.contains('USER=jenkins')
    }

    @Test
    void redactsHeadersUrlsJwtAndPrivateKeys() {
        assert r('Authorization: Bearer abc.def.ghi') == 'Authorization: Bearer ' + SecretRedactor.MASK
        assert r('git clone https://bot:hunter2@github.com/acme/x.git') ==
                'git clone https://bot:' + SecretRedactor.MASK + '@github.com/acme/x.git'
        assert !r('jwt eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.dozjgNryP4J3jVmNHl0w5N').contains('eyJhbGci')
        String pem = 'before\n-----BEGIN RSA PRIVATE KEY-----\nMIIEow\nabc\n-----END RSA PRIVATE KEY-----\nafter'
        assert r(pem) == 'before\n' + SecretRedactor.MASK + '\nafter'
    }

    @Test
    void leavesOrdinaryLogLinesAlone() {
        String line = '[ERROR] OrderService.java:[58,31] cannot find symbol'
        assert r(line) == line
        assert r('Tests run: 12, Failures: 0') == 'Tests run: 12, Failures: 0'
        assert r(null) == ''
    }
}

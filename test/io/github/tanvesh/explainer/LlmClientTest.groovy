package io.github.tanvesh.explainer

import com.sun.net.httpserver.HttpServer
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.junit.After
import org.junit.Before
import org.junit.Test

/** Talks to a real local HTTP server that imitates an OpenAI-compatible endpoint. */
class LlmClientTest {

    HttpServer server
    List<Map> requests = []
    List<Integer> statuses = []   // status to return per call, in order; default 200

    @Before
    void start() {
        server = HttpServer.create(new InetSocketAddress('127.0.0.1', 0), 0)
        server.createContext('/v1/chat/completions') { exchange ->
            Map body = new JsonSlurper().parse(exchange.requestBody) as Map
            requests << [auth: exchange.requestHeaders.getFirst('Authorization'),
                         path: exchange.requestURI.path, body: body]
            int status = statuses ? statuses.remove(0) : 200
            String reply = status == 200 ?
                    JsonOutput.toJson([choices: [[message: [role: 'assistant', content: '{"summary":"ok"}']]]]) :
                    '{"error":{"message":"bad request"}}'
            byte[] bytes = reply.getBytes('UTF-8')
            exchange.sendResponseHeaders(status, bytes.length)
            exchange.responseBody.withStream { it.write(bytes) }
        }
        server.start()
    }

    @After
    void stop() {
        server.stop(0)
    }

    LlmClient client(String key = 'test-key') {
        new LlmClient(baseUrl: "http://127.0.0.1:${server.address.port}/v1/", model: 'tiny', apiKey: key)
    }

    @Test
    void postsChatCompletionWithAuthAndJsonMode() {
        assert client().chat([[role: 'user', content: 'hi']]) == '{"summary":"ok"}'
        Map req = requests[0]
        assert req.path == '/v1/chat/completions'
        assert req.auth == 'Bearer test-key'
        assert req.body.model == 'tiny'
        assert req.body.messages[0].content == 'hi'
        assert req.body.response_format == [type: 'json_object']
    }

    @Test
    void omitsAuthHeaderWhenNoKey() {
        client('').chat([[role: 'user', content: 'hi']])
        assert requests[0].auth == null
    }

    @Test
    void retriesWithoutJsonModeWhenProviderRejectsIt() {
        statuses = [400, 200]
        assert client().chat([[role: 'user', content: 'hi']]) == '{"summary":"ok"}'
        assert requests.size() == 2
        assert requests[0].body.response_format
        assert !requests[1].body.containsKey('response_format')
    }

    @Test
    void raisesHttpErrorsWithStatus() {
        statuses = [500]
        try {
            client().chat([[role: 'user', content: 'hi']])
            assert false: 'expected an exception'
        } catch (LlmHttpException e) {
            assert e.status == 500
            assert e.message.contains('HTTP 500')
        }
    }

    @Test
    void isConfiguredNeedsUrlAndModel() {
        assert !new LlmClient().isConfigured()
        assert !new LlmClient(baseUrl: 'http://x').isConfigured()
        assert new LlmClient(baseUrl: 'http://x', model: 'm').isConfigured()
    }
}

import io.github.tanvesh.explainer.Explainer
import io.github.tanvesh.explainer.Explanation
import io.github.tanvesh.explainer.LlmClient
import io.github.tanvesh.explainer.Renderer

/**
 * Run the explainer on any saved log, without Jenkins:
 *
 *   ./gradlew explain --args="demo/logs/java-oom.log"
 *   ./gradlew explain --args="--markdown build.log"
 *   cat build.log | ./gradlew explain --args="-"
 *
 * LLM settings come from LLM_BASE_URL, LLM_MODEL and LLM_API_KEY. Without them it uses the
 * built-in rules only.
 */
class ExplainLog {

    static void main(String[] args) {
        List<String> opts = args.findAll { it.startsWith('--') }
        List<String> files = args.findAll { !it.startsWith('--') }
        if (files.size() != 1 || opts.contains('--help')) {
            System.err.println('Usage: ExplainLog [--json|--markdown] <logfile | ->')
            System.exit(files ? 0 : 2)
        }

        String log = files[0] == '-' ? System.in.getText('UTF-8') : new File(files[0]).getText('UTF-8')
        String name = files[0] == '-' ? 'stdin' : new File(files[0]).name.replaceAll(/\.log$/, '')

        Map<String, String> env = System.getenv()
        LlmClient llm = null
        if (env.LLM_BASE_URL && env.LLM_MODEL) {
            llm = new LlmClient(baseUrl: env.LLM_BASE_URL, model: env.LLM_MODEL, apiKey: env.LLM_API_KEY ?: '')
        }

        Map info = [jobName: name, buildNumber: 'local']
        Explanation e = new Explainer(llm: llm).explain(log, info)

        if (opts.contains('--json')) {
            println Renderer.json(e)
        } else if (opts.contains('--markdown')) {
            println Renderer.markdown(e, info)
        } else {
            println Renderer.console(e)
        }
    }
}

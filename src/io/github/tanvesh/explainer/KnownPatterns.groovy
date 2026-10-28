package io.github.tanvesh.explainer

import com.cloudbees.groovy.cps.NonCPS

/**
 * Deterministic, offline classification of common CI failures.
 *
 * Used two ways: as a hint inside the LLM prompt (cheaper, more accurate answers), and as the
 * whole answer when no LLM is configured or the LLM call fails, so the step never leaves a
 * failed build without an explanation.
 */
class KnownPatterns implements Serializable {

    /** Each rule: category, title, regexes (any match), suggested fixes. Most specific first. */
    static final List<Map> RULES = [
        [category: 'OUT_OF_MEMORY', title: 'The build ran out of memory',
         patterns: [/java\.lang\.OutOfMemoryError/, /JavaScript heap out of memory/,
                    /(?i)exit code 137/, /(?i)\bOOMKilled\b/, /Cannot allocate memory/],
         fixes: ['Raise the heap for the failing tool (MAVEN_OPTS=-Xmx2g, NODE_OPTIONS=--max-old-space-size=4096, Gradle org.gradle.jvmargs).',
                 'Give the agent or container more memory, or run fewer parallel jobs on it.',
                 'Look for a test or build step that loads far more data than usual.']],

        [category: 'DISK_FULL', title: 'The agent ran out of disk space',
         patterns: [/No space left on device/, /(?i)disk quota exceeded/],
         fixes: ['Clean the workspace (cleanWs() in post { always }) and old builds (buildDiscarder).',
                 'Prune Docker images and volumes on the agent: docker system prune -af.',
                 'Add disk-space monitoring on agents so this alerts before builds fail.']],

        [category: 'DOCKER_DAEMON', title: 'Docker is not reachable from the agent',
         patterns: [/Cannot connect to the Docker daemon/, /docker\.sock: connect: permission denied/,
                    /error during connect: .*docker/],
         fixes: ['Check the Docker service is running on the agent (systemctl status docker).',
                 'Add the jenkins user to the docker group, or mount /var/run/docker.sock for containerised agents.',
                 'Confirm DOCKER_HOST points at the right daemon if you use a remote one.']],

        [category: 'SCRIPT_APPROVAL', title: 'A pipeline method needs in-process script approval',
         patterns: [/Scripts not permitted to use/, /RejectedAccessException/],
         fixes: ['An admin can approve the signature in Manage Jenkins > In-process Script Approval.',
                 'Better: move the logic into a trusted shared library or use a pipeline step instead.']],

        [category: 'COMPILATION_ERROR', title: 'The code does not compile',
         patterns: [/cannot find symbol/, /incompatible types/, /\berror TS\d{4}:/, /SyntaxError:/,
                    /IndentationError:/, /error\[E\d{4}\]/, /\.go:\d+:\d+: /,
                    /COMPILATION ERROR/, /Compilation failed/, /Execution failed for task ':.*compile.*'/],
         fixes: ['Fix the first compiler error shown; later ones are often caused by it.',
                 'Pull the latest main branch and build locally to reproduce.',
                 'Check that a dependency upgrade did not change an API you call.']],

        [category: 'DEPENDENCY_RESOLUTION', title: 'A dependency could not be downloaded or resolved',
         patterns: [/Could not resolve dependencies/, /Could not find artifact/, /Could not resolve all (files|dependencies)/,
                    /npm ERR! code (ERESOLVE|E404|ETARGET)/, /No matching distribution found/,
                    /ResolutionImpossible/, /Unable to find a matching version/, /go: .* unknown revision/],
         fixes: ['Check the version exists in the registry and the name is spelled right.',
                 'If it is a private package, check the registry credentials on this agent.',
                 'For npm peer-dependency conflicts, align versions rather than relying on --legacy-peer-deps.']],

        [category: 'TEST_FAILURE', title: 'One or more tests failed',
         patterns: [/Tests run: \d+, Failures: [1-9]/, /Tests run: \d+, Failures: \d+, Errors: [1-9]/,
                    /There are test failures/, /=+ \d+ failed/, /FAILED \(failures=\d+/,
                    /\b[1-9]\d* failing\b/, /Tests:\s+[1-9]\d* failed/, /--- FAIL: /],
         fixes: ['Open the failing test named in the log and run it locally.',
                 'If it passes locally, look for order dependence, time zones, or shared test data.',
                 'If it is flaky, quarantine it with a ticket instead of re-running until green.']],

        [category: 'NETWORK', title: 'A network call from the build failed',
         patterns: [/Connection refused/, /Could not resolve host/, /UnknownHostException/,
                    /(?i)connection timed out/, /(?i)read timed out/, /ECONNRESET/, /ETIMEDOUT/,
                    /SSLHandshakeException/, /certificate verify failed/],
         fixes: ['Check the target service is up and reachable from the agent (curl it from the agent).',
                 'Check proxy and firewall settings on the agent.',
                 'For certificate errors, import the corporate CA into the JDK or system trust store.']],

        [category: 'AUTH', title: 'Authentication or permission failure',
         patterns: [/(?i)\b401 Unauthorized\b/, /(?i)\b403 Forbidden\b/, /(?i)authentication failed/,
                    /Permission denied \(publickey\)/, /(?i)access denied/, /(?i)invalid credentials/],
         fixes: ['Check the credential ID used by the job still exists and has not expired.',
                 'Check the account behind the credential still has access to the resource.',
                 'For Git over SSH, confirm the deploy key is added to the repository.']],

        [category: 'FILE_PERMISSION', title: 'A file or directory permission problem',
         patterns: [/(?i)permission denied/, /EACCES/],
         fixes: ['Check file ownership in the workspace; a previous build may have run as root in a container.',
                 'Make scripts executable in Git: git update-index --chmod=+x script.sh.']],

        [category: 'MISSING_TOOL', title: 'A required tool is not installed on the agent',
         patterns: [/: command not found/, /is not recognized as an internal or external command/,
                    /No such file or directory.*(mvn|npm|node|python|java|docker|kubectl)\b/],
         fixes: ['Install the tool on the agent, or run the stage in a container image that has it.',
                 'Use the Jenkins tools {} block to provision a specific version.']],

        [category: 'TIMEOUT', title: 'The build or a step timed out',
         patterns: [/Timeout has been exceeded/, /Build timed out/, /Cancelling nested steps due to timeout/],
         fixes: ['Find the step that hung in the log and check what it was waiting for.',
                 'Raise the timeout only if the step is genuinely slow, not stuck.']],
    ]

    /** Returns the first matching rule plus the evidence line, or null when nothing matches. */
    @NonCPS
    static Map match(String log) {
        if (!log) {
            return null
        }
        List<String> lines = log.readLines()
        for (Map rule : RULES) {
            for (String p : (rule.patterns as List<String>)) {
                def pattern = ~p
                // Search from the end: the last occurrence is closest to the real failure.
                for (int i = lines.size() - 1; i >= 0; i--) {
                    if ((lines[i] =~ pattern).find()) {
                        return [category: rule.category, title: rule.title,
                                fixes: rule.fixes, evidence: lines[i].trim()]
                    }
                }
            }
        }
        return null
    }
}

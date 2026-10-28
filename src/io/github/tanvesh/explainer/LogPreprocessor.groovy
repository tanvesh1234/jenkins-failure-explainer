package io.github.tanvesh.explainer

import com.cloudbees.groovy.cps.NonCPS

/**
 * Turns a raw Jenkins console log into a short, clean excerpt worth sending to an LLM.
 *
 * Steps: strip console notes and ANSI colour codes, remember which pipeline stage each
 * line belongs to, drop pipeline noise, redact secrets, then keep the lines around errors
 * plus the tail of the log. A 20 MB log becomes a few hundred lines with the evidence intact.
 */
class LogPreprocessor implements Serializable {

    int contextLines = 6       // lines kept before/after each error line
    int tailLines = 60         // last lines always kept, the failure is usually here
    int maxErrorWindows = 12   // cap on separate error regions
    int maxChars = 12000       // hard cap on the excerpt sent to the LLM

    private static final def CONSOLE_NOTE = ~/\u001B\[8mha:\/\/\/\/[^\u001B]*\u001B\[0m/
    private static final def ANSI = ~/\u001B\[[0-9;?]*[ -\/]*[@-~]/
    private static final def TIMESTAMP_PREFIX = ~/^\[?\d{4}-\d{2}-\d{2}[T ]\d{2}:\d{2}:\d{2}(?:[.,]\d+)?Z?\]?\s?/
    private static final def STAGE_START = ~/^\[Pipeline\] \{ \((.+)\)$/
    private static final def STAGE_END = ~/^\[Pipeline\] \/\/ stage$/
    private static final def PIPELINE_NOISE = ~/^\[Pipeline\] .*/

    // Lines that usually carry the actual reason a build failed.
    private static final def ERROR_LINE = ~/(?i)(\berror\b|\bfailed\b|\bfailure\b|\bfatal\b|exception|\bcaused by\b|npm err!|traceback|\bkilled\b|exit code [1-9]|script returned exit code|cannot |could not |unable to |not found|denied|refused|timed out|timeout (?:has been )?exceeded)/

    // Error-ish lines that are almost always harmless noise.
    private static final def FALSE_POSITIVE = ~/(?i)(\b0 errors?\b|errors?: 0\b|failures: 0, errors: 0|-DfailIfNoTests|\[INFO\] Tests run: \d+, Failures: 0, Errors: 0)/

    @NonCPS
    LogExcerpt process(String rawLog) {
        List<String> raw = (rawLog ?: '').readLines()
        List<String> lines = []
        List<String> stageOfLine = []
        String currentStage = null

        raw.each { String line ->
            String clean = stripFormatting(line)
            def stageMatch = (clean =~ STAGE_START)
            if (stageMatch.matches()) {
                currentStage = stageMatch.group(1)
            } else if (clean ==~ STAGE_END) {
                currentStage = null
            }
            if (clean ==~ PIPELINE_NOISE) {
                return
            }
            lines << clean
            stageOfLine << currentStage
        }

        // Redact line by line (and once more on the joined excerpt for multi-line secrets).
        lines = lines.collect { SecretRedactor.redact(it) }

        List<Integer> errorIdx = []
        lines.eachWithIndex { String l, int i ->
            if ((l =~ ERROR_LINE).find() && !(l =~ FALSE_POSITIVE).find()) {
                errorIdx << i
            }
        }

        // The failed stage is the last real stage that logged an error. Lines after the stage
        // block ("ERROR: script returned exit code 1") have no stage, and post actions are skipped.
        String failedStage = null
        for (int k = errorIdx.size() - 1; k >= 0; k--) {
            String st = stageOfLine[errorIdx[k]]
            if (st && !st.startsWith('Declarative: ')) {
                failedStage = st
                break
            }
        }

        // Keep the LAST error windows: earlier ones are often warnings that did not stop the build.
        List<Integer> windows = errorIdx.size() > maxErrorWindows ?
                errorIdx[-maxErrorWindows..-1] : errorIdx

        Set<Integer> keep = new TreeSet<Integer>()
        windows.each { int i ->
            int from = Math.max(0, i - contextLines)
            int to = Math.min(lines.size() - 1, i + contextLines)
            (from..to).each { keep << it }
        }
        if (lines) {
            int tailFrom = Math.max(0, lines.size() - tailLines)
            (tailFrom..(lines.size() - 1)).each { keep << it }
        }

        StringBuilder sb = new StringBuilder()
        int prev = -1
        keep.each { int i ->
            if (prev >= 0 && i > prev + 1) {
                sb.append("... [${i - prev - 1} lines omitted] ...\n")
            } else if (prev < 0 && i > 0) {
                sb.append("... [${i} lines omitted] ...\n")
            }
            sb.append(lines[i]).append('\n')
            prev = i
        }

        String excerpt = SecretRedactor.redact(sb.toString())
        if (excerpt.length() > maxChars) {
            // Keep the end: that is where the final error lives.
            excerpt = '... [excerpt truncated] ...\n' + excerpt.substring(excerpt.length() - maxChars)
        }

        return new LogExcerpt(
                text: excerpt,
                failedStage: failedStage,
                totalLines: raw.size(),
                keptLines: keep.size(),
                errorLineCount: errorIdx.size())
    }

    @NonCPS
    static String stripFormatting(String line) {
        String s = line.replaceAll(CONSOLE_NOTE, '')
        s = s.replaceAll(ANSI, '')
        s = s.replaceFirst(TIMESTAMP_PREFIX, '')
        return s.replaceAll(/\s+$/, '')
    }
}

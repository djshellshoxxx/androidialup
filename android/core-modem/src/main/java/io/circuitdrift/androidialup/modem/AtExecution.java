package io.circuitdrift.androidialup.modem;

import java.util.List;
import java.util.Optional;

/**
 * Outcome of executing one command line.
 *
 * @param outputLines informational lines followed by the formatted terminal result; empty in
 *     quiet mode or when the line ends in a pending action such as dial
 * @param effects side effects for the state owner, in execution order
 * @param terminalResult the final result code, or empty when the result is deferred (dial/answer)
 */
public record AtExecution(
        List<String> outputLines, List<AtEffect> effects, Optional<ResultCode> terminalResult) {

    public AtExecution {
        outputLines = List.copyOf(outputLines);
        effects = List.copyOf(effects);
        terminalResult = terminalResult == null ? Optional.empty() : terminalResult;
    }
}

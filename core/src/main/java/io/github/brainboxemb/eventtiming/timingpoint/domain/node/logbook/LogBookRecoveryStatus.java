package io.github.brainboxemb.eventtiming.timingpoint.domain.node.logbook;

import io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNodeTypes.Problem;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.checkArgument;

/**
 * Immutable result of one LogBook startup recovery attempt.
 *
 * <p>This describes only LogBook recovery facts. It does not represent the
 * current OPEN/CLOSED state of the owning TimingNode.</p>
 */
public final class LogBookRecoveryStatus {
    private final boolean timingDataTailRepaired;
    private final List<Problem> problems;

    public LogBookRecoveryStatus(
            boolean timingDataTailRepaired,
            List<Problem> problems) {
        checkArgument(problems != null, "problems must not be null");
        for (Problem problem : problems) {
            checkArgument(problem != null, "problem must not be null");
        }

        this.timingDataTailRepaired = timingDataTailRepaired;
        this.problems = problems.isEmpty()
                ? Collections.<Problem>emptyList()
                : Collections.unmodifiableList(new ArrayList<Problem>(problems));
    }

    /** Reports whether startup repaired an incomplete persisted-data tail. */
    public boolean timingDataTailRepaired() {
        return timingDataTailRepaired;
    }

    /** Returns immutable problems found while recovering persisted TimingData. */
    public List<Problem> problems() {
        return problems;
    }

    /** Reports whether startup recovery produced one or more problems. */
    public boolean hasProblems() {
        return !problems.isEmpty();
    }
}

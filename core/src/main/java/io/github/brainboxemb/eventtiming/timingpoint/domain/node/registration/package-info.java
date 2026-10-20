/**
 * Task Lane-owned registration projections derived from committed LogBook TimingData.
 *
 * <p>This package contains retained node state, not persistence records and not presentation
 * result models. DataTask replays committed TimingData in sequence order into
 * {@link io.github.brainboxemb.eventtiming.timingpoint.domain.node.registration.ActiveRegistrations}.
 * Each actual registration validity change is then reflected in
 * {@link io.github.brainboxemb.eventtiming.timingpoint.domain.node.registration.RegisteredTeams}
 * using the current RaceData team assignment.</p>
 *
 * <p>ActiveRegistrations preserves chronological ADD rows, including rows later invalidated by
 * REV. RegisteredTeams maintains the separate current unique-team membership needed by the smart
 * display. Their read APIs expose only those maintained facts: bounded chronological visits,
 * unique-team counts/full visits and membership transition events.</p>
 *
 * <p>Elapsed-time calculations, ranking result models, CAN encoding and per-display
 * synchronization state do not belong here. A production query/result type is added only when an
 * application or I/O consumer actually requires that behavior; tests verify the retained state
 * rather than defining extra production interfaces.</p>
 */
package io.github.brainboxemb.eventtiming.timingpoint.domain.node.registration;

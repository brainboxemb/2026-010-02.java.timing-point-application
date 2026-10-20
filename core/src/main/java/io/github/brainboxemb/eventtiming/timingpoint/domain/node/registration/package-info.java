/**
 * Active-WP registration projections derived from committed LogBook TimingData.
 *
 * <p>The LogBook is the full historical authority across every LocationId and every OPEN/CLOSE
 * period. These objects deliberately are not global history. They exist as usable state only
 * while a TimingNode has one WP open.</p>
 *
 * <p>OPEN(locationId) clears the previous projection and DataTask replays the complete LogBook
 * from sequence one. ActiveRegistrations accepts only ADD/REV records for that LocationId, so
 * registrations from earlier OPEN periods of the same WP return while records from other WPs are
 * ignored. CLOSE deactivates and clears both projections. Reopening the same WP reconstructs it
 * again from LogBook rather than preserving a separate in-memory history.</p>
 *
 * <p>ActiveRegistrations keeps chronological ADD rows, including rows later invalidated by REV.
 * RegisteredTeams derives the current unique-team set from those valid occurrences and current
 * RaceData assignments. Historical reconstruction is silent; after catch-up, only real live
 * membership transitions publish +/- events. A smart display uses a full burst after rebuild and
 * incremental events afterwards.</p>
 *
 * <p>Rows and read methods carry no LocationId because the active projection itself already has
 * exactly one WP scope. CAN encoding, per-display synchronization state, persistence and calculated
 * presentation/query models remain outside this package.</p>
 */
package io.github.brainboxemb.eventtiming.timingpoint.domain.node.registration;

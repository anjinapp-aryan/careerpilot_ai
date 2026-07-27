package ai.careerpilot.career.monitor;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * Phase 11.5 — a bounded, per-user history of previously surfaced {@link CareerAlert}s. {@link
 * CareerMonitor} uses {@link #wasRecentlySurfaced} to avoid re-alerting on the same category
 * every run — a resume that's been outdated for a month shouldn't generate a fresh alert every
 * time {@link CareerMonitor#monitor} runs within the cooldown window.
 */
public interface CareerTimeline {

    void record(CareerAlert alert);

    boolean wasRecentlySurfaced(UUID userId, CareerAlertType type, Duration cooldown);

    List<CareerAlert> recentFor(UUID userId, int limit);
}

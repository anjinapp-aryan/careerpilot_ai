package ai.careerpilot.service.profile.event;

import java.util.UUID;

/**
 * Published when a user saves job preferences. Consumed by the Candidate Profile module to
 * re-merge the preference snapshot into the cached resume intelligence — WITHOUT a new LLM
 * call (the expensive extraction is keyed by the resume fingerprint). Decoupled via Spring
 * events so a profile failure never affects the preferences save.
 */
public record PreferencesUpdatedEvent(UUID userId) {}

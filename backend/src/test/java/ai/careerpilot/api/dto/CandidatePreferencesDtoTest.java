package ai.careerpilot.api.dto;

import ai.careerpilot.domain.CandidatePreferences;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Excluded-roles mapping across the DTO ↔ entity boundary (comma-joined TEXT column ↔ JSON array
 * on the wire), plus null-safety. Mirrors how preferred roles/countries are stored.
 */
class CandidatePreferencesDtoTest {

    @Test
    void toEntityCommaJoinsExcludedRoles() {
        CandidatePreferencesDto dto = new CandidatePreferencesDto(
                List.of(), List.of(), List.of(), List.of("Sales", "Marketing"),
                false, false, false, false, false, null, null, null, null);

        CandidatePreferences entity = dto.toEntity(UUID.randomUUID());

        assertEquals("Sales,Marketing", entity.getExcludedRoles());
    }

    @Test
    void fromEntitySplitsAndTrimsExcludedRoles() {
        CandidatePreferences entity = CandidatePreferences.builder()
                .userId(UUID.randomUUID()).excludedRoles("Sales, Marketing , Support").build();

        CandidatePreferencesDto dto = CandidatePreferencesDto.from(entity);

        assertEquals(List.of("Sales", "Marketing", "Support"), dto.excludedRoles());
    }

    @Test
    void emptyExcludedRolesJoinToNullColumn() {
        CandidatePreferencesDto dto = new CandidatePreferencesDto(
                List.of(), List.of(), List.of(), List.of(),
                false, false, false, false, false, null, null, null, null);

        assertNull(dto.toEntity(UUID.randomUUID()).getExcludedRoles());
    }

    @Test
    void defaultsHaveEmptyExcludedRoles() {
        assertTrue(CandidatePreferencesDto.defaults().excludedRolesOrEmpty().isEmpty());
    }

    @Test
    void excludedRolesOrEmptyIsNullSafe() {
        CandidatePreferencesDto dto = new CandidatePreferencesDto(
                List.of(), List.of(), List.of(), null,
                false, false, false, false, false, null, null, null, null);

        assertNotNull(dto.excludedRolesOrEmpty());
        assertTrue(dto.excludedRolesOrEmpty().isEmpty());
    }
}

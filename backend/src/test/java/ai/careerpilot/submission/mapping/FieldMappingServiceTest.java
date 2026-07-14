package ai.careerpilot.submission.mapping;

import ai.careerpilot.domain.CandidateProfile;
import ai.careerpilot.domain.User;
import ai.careerpilot.repo.CandidateProfileRepository;
import ai.careerpilot.repo.UserRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FieldMappingServiceTest {

    private final UserRepository users = mock(UserRepository.class);
    private final CandidateProfileRepository profiles = mock(CandidateProfileRepository.class);
    private final FieldMappingService service = new FieldMappingService(users, profiles);

    private MappedField find(FieldMappingResult result, String name) {
        return result.fields().stream().filter(f -> f.fieldName().equals(name)).findFirst()
                .orElseThrow(() -> new AssertionError("no field named " + name));
    }

    @Test
    void fullProfilePresentMapsAllDerivableFields() {
        UUID userId = UUID.randomUUID();
        User user = User.builder().id(userId).fullName("Jane Doe").email("jane@example.com").build();
        CandidateProfile profile = CandidateProfile.builder().userId(userId).yearsExperience(5)
                .skillsJson("[\"Java\",\"Spring\"]").visaRequired(false)
                .salaryTarget(new BigDecimal("120000")).homeCountry("USA").build();
        when(users.findById(userId)).thenReturn(Optional.of(user));
        when(profiles.findByUserId(userId)).thenReturn(Optional.of(profile));

        FieldMappingResult result = service.map(userId);

        assertEquals("Jane Doe", find(result, "fullName").value());
        assertFalse(find(result, "fullName").unmapped());
        assertEquals("jane@example.com", find(result, "email").value());
        assertEquals("5", find(result, "yearsExperience").value());
        assertEquals("Java, Spring", find(result, "skills").value());
        assertEquals("false", find(result, "visaRequired").value());
        assertEquals("120000", find(result, "salaryTarget").value());
        assertEquals("USA", find(result, "location").value());
    }

    @Test
    void missingUserLeavesFullNameAndEmailUnmapped() {
        UUID userId = UUID.randomUUID();
        when(users.findById(userId)).thenReturn(Optional.empty());
        when(profiles.findByUserId(userId)).thenReturn(Optional.empty());

        FieldMappingResult result = service.map(userId);

        assertTrue(find(result, "fullName").unmapped());
        assertNull(find(result, "fullName").value());
        assertTrue(find(result, "email").unmapped());
        assertNull(find(result, "email").value());
    }

    @Test
    void missingProfileLeavesProfileDerivedFieldsUnmapped() {
        UUID userId = UUID.randomUUID();
        User user = User.builder().id(userId).fullName("Jane").email("jane@example.com").build();
        when(users.findById(userId)).thenReturn(Optional.of(user));
        when(profiles.findByUserId(userId)).thenReturn(Optional.empty());

        FieldMappingResult result = service.map(userId);

        assertTrue(find(result, "yearsExperience").unmapped());
        assertTrue(find(result, "skills").unmapped());
        assertTrue(find(result, "visaRequired").unmapped());
        assertTrue(find(result, "salaryTarget").unmapped());
        assertTrue(find(result, "location").unmapped());
        assertFalse(find(result, "fullName").unmapped());
    }

    @Test
    void phoneLinkedinGithubPortfolioAreAlwaysUnmapped() {
        UUID userId = UUID.randomUUID();
        User user = User.builder().id(userId).fullName("Jane").email("jane@example.com").build();
        CandidateProfile profile = CandidateProfile.builder().userId(userId).yearsExperience(3)
                .skillsJson("[\"Java\"]").visaRequired(true).salaryTarget(new BigDecimal("1"))
                .homeCountry("India").build();
        when(users.findById(userId)).thenReturn(Optional.of(user));
        when(profiles.findByUserId(userId)).thenReturn(Optional.of(profile));

        FieldMappingResult result = service.map(userId);

        for (String field : new String[] {"phone", "linkedin", "github", "portfolio"}) {
            MappedField f = find(result, field);
            assertTrue(f.unmapped(), field + " must never be fabricated");
            assertNull(f.value(), field + " must never carry a value");
        }
    }

    @Test
    void yearsExperienceUnmappedWhenNull() {
        UUID userId = UUID.randomUUID();
        CandidateProfile profile = CandidateProfile.builder().userId(userId).yearsExperience(null).build();
        when(users.findById(userId)).thenReturn(Optional.empty());
        when(profiles.findByUserId(userId)).thenReturn(Optional.of(profile));

        FieldMappingResult result = service.map(userId);
        assertTrue(find(result, "yearsExperience").unmapped());
    }

    @Test
    void skillsUnmappedWhenBlank() {
        UUID userId = UUID.randomUUID();
        CandidateProfile profile = CandidateProfile.builder().userId(userId).skillsJson("  ").build();
        when(users.findById(userId)).thenReturn(Optional.empty());
        when(profiles.findByUserId(userId)).thenReturn(Optional.of(profile));

        FieldMappingResult result = service.map(userId);
        assertTrue(find(result, "skills").unmapped());
    }

    @Test
    void locationUnmappedWhenHomeCountryBlank() {
        UUID userId = UUID.randomUUID();
        CandidateProfile profile = CandidateProfile.builder().userId(userId).homeCountry("  ").build();
        when(users.findById(userId)).thenReturn(Optional.empty());
        when(profiles.findByUserId(userId)).thenReturn(Optional.of(profile));

        FieldMappingResult result = service.map(userId);
        assertTrue(find(result, "location").unmapped());
    }

    @Test
    void mappedFieldsResultFiltersOnlyNonUnmapped() {
        UUID userId = UUID.randomUUID();
        User user = User.builder().id(userId).fullName("Jane").email("jane@example.com").build();
        when(users.findById(userId)).thenReturn(Optional.of(user));
        when(profiles.findByUserId(userId)).thenReturn(Optional.empty());

        FieldMappingResult result = service.map(userId);
        assertTrue(result.mappedFields().stream().noneMatch(MappedField::unmapped));
        assertTrue(result.mappedFields().stream().anyMatch(f -> f.fieldName().equals("fullName")));
    }

    @Test
    void unmappedFieldsResultFiltersOnlyUnmapped() {
        UUID userId = UUID.randomUUID();
        when(users.findById(userId)).thenReturn(Optional.empty());
        when(profiles.findByUserId(userId)).thenReturn(Optional.empty());

        FieldMappingResult result = service.map(userId);
        assertTrue(result.unmappedFields().stream().allMatch(MappedField::unmapped));
        assertTrue(result.unmappedFields().stream().anyMatch(f -> f.fieldName().equals("phone")));
    }

    @Test
    void mappedFieldFactoryProducesNonUnmappedInstance() {
        MappedField f = MappedField.mapped("x", "y", "src");
        assertFalse(f.unmapped());
        assertEquals("y", f.value());
        assertEquals("src", f.source());
    }

    @Test
    void unmappedFieldFactoryProducesNullValueAndNoneSource() {
        MappedField f = MappedField.unmapped("x");
        assertTrue(f.unmapped());
        assertNull(f.value());
        assertEquals("none", f.source());
    }

    @Test
    void totalFieldCountIsElevenCanonicalFields() {
        UUID userId = UUID.randomUUID();
        when(users.findById(userId)).thenReturn(Optional.empty());
        when(profiles.findByUserId(userId)).thenReturn(Optional.empty());
        FieldMappingResult result = service.map(userId);
        assertEquals(11, result.fields().size());
    }
}

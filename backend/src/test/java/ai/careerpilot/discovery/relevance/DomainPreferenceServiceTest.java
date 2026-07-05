package ai.careerpilot.discovery.relevance;

import ai.careerpilot.domain.Job;
import ai.careerpilot.jobdiscovery.JobTaxonomy;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DomainPreferenceServiceTest {

    private final DomainPreferenceService service = new DomainPreferenceService(
            new JobTaxonomy(), "Media,Hospitality,Customer Service,Construction,Sales,Marketing,HR,Creative,BIM");

    private static Job job(String title, String description) {
        return Job.builder().title(title).description(description).company("Acme").build();
    }

    @Test
    void excludesMediaHospitalityConstructionCreativeBim() {
        assertTrue(service.isExcluded(job("Media Manager", "")));
        assertTrue(service.isExcluded(job("Hospitality Coach", "")));
        assertTrue(service.isExcluded(job("Construction Supervisor", "")));
        assertTrue(service.isExcluded(job("Creative Director", "")));
        assertTrue(service.isExcluded(job("BIM Coordinator", "")));
    }

    @Test
    void excludesFamiliesAlreadyKnownToJobTaxonomy() {
        // Sales/Marketing/HR/Customer Service are already excluded families in JobTaxonomy.
        assertTrue(service.isExcluded(job("Sales Executive", "")));
        assertTrue(service.isExcluded(job("Marketing Manager", "")));
        assertTrue(service.isExcluded(job("Customer Service Representative", "")));
    }

    @Test
    void doesNotExcludeTechRoles() {
        assertFalse(service.isExcluded(job("Senior Java Developer", "Java, Spring Boot, AWS")));
    }

    @Test
    void noPreferredDomainsIsANeutralPass() {
        Job techJob = job("Senior Java Developer", "Java, Spring Boot, AWS");
        assertTrue(service.fitsPreferredDomains(techJob, List.of()));
        assertTrue(service.fitsPreferredDomains(techJob, null));
    }

    @Test
    void excludedJobNeverFitsRegardlessOfPreferredDomains() {
        Job excludedJob = job("Media Manager", "");
        assertFalse(service.fitsPreferredDomains(excludedJob, List.of("Software", "Media")));
    }
}

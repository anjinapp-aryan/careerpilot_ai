package ai.careerpilot.story.extractor;

import ai.careerpilot.domain.Application;
import ai.careerpilot.domain.CompanyKnowledge;
import ai.careerpilot.domain.Resume;
import ai.careerpilot.repo.ApplicationRepository;
import ai.careerpilot.repo.CompanyKnowledgeRepository;
import ai.careerpilot.repo.ResumeRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class StoryExtractionEngineTest {

    private final ResumeRepository resumes = mock(ResumeRepository.class);
    private final ApplicationRepository applications = mock(ApplicationRepository.class);
    private final CompanyKnowledgeRepository companies = mock(CompanyKnowledgeRepository.class);
    private final UUID userId = UUID.randomUUID();

    @Test
    void disabledReturnsEmptyMaterialWithoutTouchingRepositories() {
        StoryExtractionEngine engine = new StoryExtractionEngine(resumes, applications, companies, false);
        var material = engine.extract(userId);
        assertTrue(material.isEmpty());
    }

    @Test
    void pullsLatestResumeTextAndSkills() {
        Resume resume = Resume.builder().id(UUID.randomUUID()).userId(userId).orgId(UUID.randomUUID())
                .filename("r.pdf").s3Key("k").parsedText("resume body").extractedSkillsJson("[\"java\"]").build();
        when(resumes.findByUserIdOrderByCreatedAtDesc(userId)).thenReturn(List.of(resume));
        when(applications.findByUserIdOrderByCreatedAtDesc(userId)).thenReturn(List.of());
        when(companies.findByUserIdOrderByUpdatedAtDesc(userId)).thenReturn(List.of());

        StoryExtractionEngine engine = new StoryExtractionEngine(resumes, applications, companies, true);
        var material = engine.extract(userId);
        assertEquals("resume body", material.resumeText());
        assertEquals("[\"java\"]", material.resumeSkills());
    }

    @Test
    void onlyOfferAndInterviewingApplicationsBecomeHighlights() {
        Application offer = Application.builder().id(UUID.randomUUID()).userId(userId).orgId(UUID.randomUUID())
                .jobId(UUID.randomUUID()).status("OFFER").build();
        Application saved = Application.builder().id(UUID.randomUUID()).userId(userId).orgId(UUID.randomUUID())
                .jobId(UUID.randomUUID()).status("SAVED").build();
        when(resumes.findByUserIdOrderByCreatedAtDesc(userId)).thenReturn(List.of());
        when(applications.findByUserIdOrderByCreatedAtDesc(userId)).thenReturn(List.of(offer, saved));
        when(companies.findByUserIdOrderByUpdatedAtDesc(userId)).thenReturn(List.of());

        StoryExtractionEngine engine = new StoryExtractionEngine(resumes, applications, companies, true);
        var material = engine.extract(userId);
        assertEquals(1, material.applicationHighlights().size());
        assertTrue(material.applicationHighlights().get(0).contains("OFFER"));
    }

    @Test
    void includesCompanyContextWhenAvailable() {
        CompanyKnowledge company = CompanyKnowledge.builder().id(UUID.randomUUID()).userId(userId)
                .companyName("Acme").normalizedName("acme").industry("Tech").knowledgeVersion(1).build();
        when(resumes.findByUserIdOrderByCreatedAtDesc(userId)).thenReturn(List.of());
        when(applications.findByUserIdOrderByCreatedAtDesc(userId)).thenReturn(List.of());
        when(companies.findByUserIdOrderByUpdatedAtDesc(userId)).thenReturn(List.of(company));

        StoryExtractionEngine engine = new StoryExtractionEngine(resumes, applications, companies, true);
        var material = engine.extract(userId);
        assertFalse(material.companyContext().isEmpty());
        assertTrue(material.companyContext().get(0).contains("Acme"));
    }

    @Test
    void isEmptyIsTrueWhenNoSignalsFound() {
        when(resumes.findByUserIdOrderByCreatedAtDesc(userId)).thenReturn(List.of());
        when(applications.findByUserIdOrderByCreatedAtDesc(userId)).thenReturn(List.of());
        when(companies.findByUserIdOrderByUpdatedAtDesc(userId)).thenReturn(List.of());
        StoryExtractionEngine engine = new StoryExtractionEngine(resumes, applications, companies, true);
        assertTrue(engine.extract(userId).isEmpty());
    }
}

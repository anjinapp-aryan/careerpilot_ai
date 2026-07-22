package ai.careerpilot.companyintel;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Phase 7.17.4 — a deterministic lookup table; same input always yields the same category. */
class TechnologyTaxonomyTest {

    @Test
    void classifiesKnownLanguages() {
        assertThat(TechnologyTaxonomy.categorize("java")).isEqualTo(TechnologyTaxonomy.LANGUAGE);
        assertThat(TechnologyTaxonomy.categorize("Python")).isEqualTo(TechnologyTaxonomy.LANGUAGE);
    }

    @Test
    void classifiesKnownFrameworks() {
        assertThat(TechnologyTaxonomy.categorize("react")).isEqualTo(TechnologyTaxonomy.FRAMEWORK);
        assertThat(TechnologyTaxonomy.categorize("Spring Boot")).isEqualTo(TechnologyTaxonomy.FRAMEWORK);
    }

    @Test
    void classifiesKnownCloudAndDatabase() {
        assertThat(TechnologyTaxonomy.categorize("aws")).isEqualTo(TechnologyTaxonomy.CLOUD);
        assertThat(TechnologyTaxonomy.categorize("postgresql")).isEqualTo(TechnologyTaxonomy.DATABASE);
    }

    @Test
    void unknownTermNeverGuessedIntoACategory() {
        assertThat(TechnologyTaxonomy.categorize("some-made-up-thing")).isEqualTo(TechnologyTaxonomy.OTHER);
        assertThat(TechnologyTaxonomy.categorize(null)).isEqualTo(TechnologyTaxonomy.OTHER);
    }

    @Test
    void categorizationIsDeterministic() {
        assertThat(TechnologyTaxonomy.categorize("kafka")).isEqualTo(TechnologyTaxonomy.categorize("kafka"));
    }

    @Test
    void categoriesListIncludesOther() {
        assertThat(TechnologyTaxonomy.categories()).contains(TechnologyTaxonomy.OTHER, TechnologyTaxonomy.LANGUAGE);
    }
}

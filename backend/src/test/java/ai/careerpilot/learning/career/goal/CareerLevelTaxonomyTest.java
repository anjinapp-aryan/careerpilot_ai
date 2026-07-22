package ai.careerpilot.learning.career.goal;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CareerLevelTaxonomyTest {

    @Test
    void classifiesKnownTitles() {
        assertThat(CareerLevelTaxonomy.classify("Staff Software Engineer")).isEqualTo(CareerLevelTaxonomy.STAFF);
        assertThat(CareerLevelTaxonomy.classify("Principal Engineer")).isEqualTo(CareerLevelTaxonomy.PRINCIPAL);
        assertThat(CareerLevelTaxonomy.classify("Engineering Manager")).isEqualTo(CareerLevelTaxonomy.ENGINEERING_MANAGER);
        assertThat(CareerLevelTaxonomy.classify("Software Architect")).isEqualTo(CareerLevelTaxonomy.ARCHITECT);
        assertThat(CareerLevelTaxonomy.classify("Tech Lead")).isEqualTo(CareerLevelTaxonomy.TECH_LEAD);
        assertThat(CareerLevelTaxonomy.classify("Senior Java Developer")).isEqualTo(CareerLevelTaxonomy.SENIOR);
        assertThat(CareerLevelTaxonomy.classify("Junior Developer")).isEqualTo(CareerLevelTaxonomy.JUNIOR);
    }

    @Test
    void unknownOrBlankTitleNeverGuessed() {
        assertThat(CareerLevelTaxonomy.classify(null)).isEqualTo(CareerLevelTaxonomy.UNKNOWN);
        assertThat(CareerLevelTaxonomy.classify("")).isEqualTo(CareerLevelTaxonomy.UNKNOWN);
        assertThat(CareerLevelTaxonomy.classify("Product Manager")).isEqualTo(CareerLevelTaxonomy.UNKNOWN);
    }

    @Test
    void rankOrdersLevelsCorrectly() {
        assertThat(CareerLevelTaxonomy.rank(CareerLevelTaxonomy.SENIOR))
                .isLessThan(CareerLevelTaxonomy.rank(CareerLevelTaxonomy.STAFF));
        assertThat(CareerLevelTaxonomy.rank(CareerLevelTaxonomy.STAFF))
                .isLessThan(CareerLevelTaxonomy.rank(CareerLevelTaxonomy.PRINCIPAL));
        assertThat(CareerLevelTaxonomy.rank(CareerLevelTaxonomy.UNKNOWN)).isEqualTo(-1);
    }

    @Test
    void levelForGoalMapsNamedGoals() {
        assertThat(CareerLevelTaxonomy.levelForGoal("Staff Engineer")).isEqualTo(CareerLevelTaxonomy.STAFF);
        assertThat(CareerLevelTaxonomy.levelForGoal("Tech Lead")).isEqualTo(CareerLevelTaxonomy.TECH_LEAD);
        assertThat(CareerLevelTaxonomy.levelForGoal("Not A Real Goal")).isEqualTo(CareerLevelTaxonomy.UNKNOWN);
    }

    @Test
    void supportedGoalsIsNonEmpty() {
        assertThat(CareerLevelTaxonomy.supportedGoals()).isNotEmpty();
    }
}

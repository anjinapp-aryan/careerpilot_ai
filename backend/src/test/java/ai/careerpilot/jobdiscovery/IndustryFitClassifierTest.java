package ai.careerpilot.jobdiscovery;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** International Job Discovery Phase 2 — {@link IndustryFitClassifier}. */
class IndustryFitClassifierTest {

    private final IndustryFitClassifier classifier = new IndustryFitClassifier();

    @Test
    void jpMorganAndPaymentsClassifyAsBanking() {
        assertThat(classifier.classify("Senior Java Engineer", "Work on payments infrastructure", "J.P. Morgan"))
                .isEqualTo(IndustryFit.BANKING);
        assertThat(classifier.classify("Engineer", "Investment banking core platform", null))
                .isEqualTo(IndustryFit.BANKING);
    }

    @Test
    void awsAndTerraformClassifyAsCloud() {
        assertThat(classifier.classify("Cloud Engineer", "AWS Terraform CloudFormation cloud migration", null))
                .isEqualTo(IndustryFit.CLOUD);
    }

    @Test
    void kubernetesAndPlatformEngineeringClassifyAsPlatform() {
        assertThat(classifier.classify("Platform Engineer", "Kubernetes distributed systems, internal platform team", null))
                .isEqualTo(IndustryFit.PLATFORM);
    }

    @Test
    void unrelatedTextClassifiesAsUnknown() {
        assertThat(classifier.classify("Barista", "Make coffee and serve customers", "Local Cafe"))
                .isEqualTo(IndustryFit.UNKNOWN);
    }

    @Test
    void countryAloneIsNeverAnInputSoItCanNeverDetermineIndustry() {
        // The classifier has no country parameter at all — this test documents that structural
        // guarantee rather than exercising a branch, since "Germany" text alone (no domain
        // keywords) must resolve UNKNOWN, never BANKING.
        assertThat(classifier.classify("Senior Engineer", "Based in Germany, great team culture", null))
                .isEqualTo(IndustryFit.UNKNOWN);
    }

    @Test
    void blankTextClassifiesAsUnknown() {
        assertThat(classifier.classify(null, null, null)).isEqualTo(IndustryFit.UNKNOWN);
        assertThat(classifier.classify("", "", "")).isEqualTo(IndustryFit.UNKNOWN);
    }

    @Test
    void bankingTakesPrecedenceOverGenericFintechAndCloudSignalsInTheSameText() {
        assertThat(classifier.classify("Senior Engineer",
                "AWS cloud infrastructure for a J.P. Morgan trading platform", null))
                .isEqualTo(IndustryFit.BANKING);
    }
}

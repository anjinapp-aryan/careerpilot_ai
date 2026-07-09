package ai.careerpilot.companyintel;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** Canonical company key: variants of the same company must share one knowledge row. */
class CompanyNameNormalizerTest {

    @Test
    void stripsLegalSuffixesAndPunctuation() {
        assertEquals("google", CompanyNameNormalizer.normalize("Google LLC"));
        assertEquals("google", CompanyNameNormalizer.normalize(" GOOGLE, Inc. "));
        assertEquals("google", CompanyNameNormalizer.normalize("google"));
    }

    @Test
    void variantsOfSameCompanyShareOneKey() {
        assertEquals(CompanyNameNormalizer.normalize("Acme Technologies Pvt Ltd"),
                CompanyNameNormalizer.normalize("ACME"));
    }

    @Test
    void keepsDistinctCompaniesDistinct() {
        assertFalse(CompanyNameNormalizer.normalize("Microsoft")
                .equals(CompanyNameNormalizer.normalize("Micron")));
    }

    @Test
    void neverNormalizesToEmpty() {
        assertFalse(CompanyNameNormalizer.normalize("Labs").isEmpty());
        assertEquals("", CompanyNameNormalizer.normalize(null));
    }

    @Test
    void deterministic() {
        assertEquals(CompanyNameNormalizer.normalize("Snowflake Inc."),
                CompanyNameNormalizer.normalize("Snowflake Inc."));
    }
}

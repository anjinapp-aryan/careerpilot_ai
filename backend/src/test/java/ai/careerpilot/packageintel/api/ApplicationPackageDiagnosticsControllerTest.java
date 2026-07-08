package ai.careerpilot.packageintel.api;

import ai.careerpilot.packageintel.PackageIntelligenceMetrics;
import ai.careerpilot.repo.ApplicationPackageRepository;
import ai.careerpilot.repo.ApplicationPackageValidationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Stock (dark) defaults must report NOT_CONFIGURED — never fabricate an UP status for the package layer. */
class ApplicationPackageDiagnosticsControllerTest {

    private ApplicationPackageDiagnosticsController controller;

    @BeforeEach
    void setUp() {
        ApplicationPackageRepository packages = mock(ApplicationPackageRepository.class);
        ApplicationPackageValidationRepository validations = mock(ApplicationPackageValidationRepository.class);
        when(packages.count()).thenReturn(0L);
        when(packages.countByValidationStatus(any())).thenReturn(0L);
        when(validations.count()).thenReturn(0L);
        when(validations.countByStatus(any())).thenReturn(0L);
        controller = new ApplicationPackageDiagnosticsController(new PackageIntelligenceMetrics(),
                packages, validations, realExecutor());
    }

    private static ThreadPoolTaskExecutor realExecutor() {
        ThreadPoolTaskExecutor e = new ThreadPoolTaskExecutor();
        e.setCorePoolSize(1);
        e.setMaxPoolSize(1);
        e.setQueueCapacity(10);
        e.initialize();
        return e;
    }

    @Test
    void reportsNotConfiguredByDefault() {
        Map<String, Object> out = controller.overview();
        assertEquals(false, out.get("enabled"));
        assertEquals("NOT_CONFIGURED", out.get("health"));
        assertEquals(false, out.get("validationEnabled"));
        assertEquals(false, out.get("triggerEnabled"));
        assertEquals(0L, out.get("packagesGenerated"));
        assertEquals(0L, out.get("validationRuns"));
        assertEquals(0L, out.get("validation.READY"));
        assertEquals(0L, out.get("packageStatus.BLOCKED"));
    }

    @Test
    void enabledValidationReportsUpWhenQueueHealthy() {
        ReflectionTestUtils.setField(controller, "validationEnabled", true);
        assertEquals("UP", controller.overview().get("health"));
    }
}

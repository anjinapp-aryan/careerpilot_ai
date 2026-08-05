package ai.careerpilot.execution.browser.form;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase E — guards the bean-wiring failures that unit tests structurally cannot see.
 *
 * <p><b>Why this file exists.</b> Two context-startup failures reached a deploy in consecutive
 * phases, both invisible to a green test suite because a unit test constructs a class directly and
 * never asks Spring to choose a constructor or supply a bean:
 * <ul>
 *   <li>Phase C — {@code CandidateAtsProfileService} injected an {@code ObjectMapper}; this
 *       application has no such bean, so the context failed to refresh.</li>
 *   <li>Phase E — {@code AnswerResolver} gained a second constructor with neither marked
 *       {@code @Autowired}, so Spring fell back to looking for a no-arg constructor and failed.</li>
 * </ul>
 * Both are the same class of defect: a compile-clean, test-green change that cannot start. These
 * assertions are cheap and run in milliseconds, unlike a full container boot.
 */
class FormBeanWiringTest {

    @Test
    @DisplayName("a component with several constructors marks exactly one injectable")
    void multipleConstructorsDeclareTheInjectableOne() {
        assertSingleInjectableConstructor(AnswerResolver.class);
    }

    @Test
    @DisplayName("no form-package component depends on an ObjectMapper bean")
    void noComponentInjectsAnObjectMapper() {
        // This application defines no ObjectMapper bean; every Jackson user constructs its own.
        // Injecting one compiles and unit-tests cleanly, then fails context refresh at boot.
        for (Class<?> type : new Class<?>[]{
                AnswerResolver.class, FormFillPlanner.class, FieldClassifier.class,
                BrowserFormAutomationEngine.class,
                ai.careerpilot.service.profile.ats.CandidateAtsProfileService.class,
                ai.careerpilot.employerquestion.EmployerAnswerService.class,
                ai.careerpilot.employerquestion.EmployerQuestionService.class}) {
            for (Constructor<?> constructor : type.getDeclaredConstructors()) {
                assertThat(constructor.getParameterTypes())
                        .withFailMessage("%s injects an ObjectMapper, which is not a bean here",
                                type.getSimpleName())
                        .doesNotContain(com.fasterxml.jackson.databind.ObjectMapper.class);
            }
        }
    }

    /**
     * Spring can autowire a class with one constructor implicitly. With more than one it must be
     * told which, or it looks for a no-arg constructor and fails.
     */
    private static void assertSingleInjectableConstructor(Class<?> type) {
        Constructor<?>[] constructors = type.getDeclaredConstructors();
        if (constructors.length <= 1) return;

        long annotated = java.util.Arrays.stream(constructors)
                .filter(c -> c.isAnnotationPresent(
                        org.springframework.beans.factory.annotation.Autowired.class))
                .count();

        assertThat(annotated)
                .withFailMessage("%s has %d constructors and %d marked @Autowired — Spring cannot "
                                + "choose, and context startup will fail",
                        type.getSimpleName(), constructors.length, annotated)
                .isEqualTo(1);
    }
}

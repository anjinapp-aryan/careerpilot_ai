package ai.careerpilot.execution.browser.form;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Phase 12C — counters for form filling, kept separate from the three existing browser metrics
 * classes on the same principle they are separate from each other: this answers "how well can we
 * complete a form?", which is a different question from submission outcomes
 * ({@code BrowserAutomationMetrics}), capacity ({@code BrowserPoolMetrics}), or browser process
 * health ({@code BrowserLifecycleMetrics}).
 *
 * <p>{@code uploadFailures} is the signal that matters most operationally: it counts uploads that
 * were <em>attempted and could not be verified</em>. A non-zero value means the engine correctly
 * refused to claim an attachment it could not prove — the alternative would be applications
 * reaching employers with no resume.
 *
 * <p>Hand-rolled {@link AtomicLong} counters, matching this codebase's established convention.
 */
@Component
public class FormAutomationMetrics {

    private final AtomicLong attempts = new AtomicLong();
    private final AtomicLong successes = new AtomicLong();
    private final AtomicLong failures = new AtomicLong();
    private final AtomicLong fieldsFilled = new AtomicLong();
    private final AtomicLong fieldsFailed = new AtomicLong();
    private final AtomicLong uploads = new AtomicLong();
    private final AtomicLong uploadFailures = new AtomicLong();
    private final AtomicLong validationErrors = new AtomicLong();

    public void recordAttempt() { attempts.incrementAndGet(); }
    public void recordSuccess() { successes.incrementAndGet(); }
    public void recordFailure() { failures.incrementAndGet(); }
    public void recordFieldFilled() { fieldsFilled.incrementAndGet(); }
    public void recordFieldFailed() { fieldsFailed.incrementAndGet(); }
    public void recordUpload() { uploads.incrementAndGet(); }
    public void recordUploadFailure() { uploadFailures.incrementAndGet(); }
    public void recordValidationError() { validationErrors.incrementAndGet(); }

    public Map<String, Object> snapshot() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("formAttempts", attempts.get());
        out.put("formSuccesses", successes.get());
        out.put("formFailures", failures.get());
        out.put("formFieldsFilled", fieldsFilled.get());
        out.put("formFieldsFailed", fieldsFailed.get());
        out.put("formUploads", uploads.get());
        out.put("formUploadFailures", uploadFailures.get());
        out.put("formValidationErrors", validationErrors.get());
        return out;
    }
}

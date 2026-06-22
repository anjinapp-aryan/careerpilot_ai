package ai.careerpilot.service;

import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Map;

/**
 * Renders the agent's structured {@code optimized_resume} blob into downloadable
 * documents. DOCX uses Apache POI (XWPF). All methods are defensive: a render failure
 * returns {@code null} (DOCX) or an empty string (TXT) so it can never break the
 * workflow — the caller persists the version row regardless, and the text form is
 * always recoverable from {@code optimized_text}.
 */
@Service
public class ResumeDocumentService {

    private static final Logger log = LoggerFactory.getLogger(ResumeDocumentService.class);

    public static final String DOCX_CONTENT_TYPE =
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
    public static final String TXT_CONTENT_TYPE = "text/plain";

    /**
     * Plain-text serialization of the optimized resume. Prefers {@code full_markdown}
     * (already a complete resume); otherwise assembles the structured sections.
     */
    public String toPlainText(Map<String, Object> optimizedResume) {
        if (optimizedResume == null || optimizedResume.isEmpty()) return "";
        String md = asString(optimizedResume.get("full_markdown"));
        if (md != null && !md.isBlank()) return md;

        StringBuilder sb = new StringBuilder();
        String summary = asString(optimizedResume.get("executive_summary"));
        if (summary != null && !summary.isBlank()) {
            sb.append("EXECUTIVE SUMMARY\n").append(summary).append("\n\n");
        }
        List<String> experience = asStringList(optimizedResume.get("professional_experience"));
        if (!experience.isEmpty()) {
            sb.append("PROFESSIONAL EXPERIENCE\n");
            experience.forEach(line -> sb.append("- ").append(line).append("\n"));
            sb.append("\n");
        }
        List<String> skills = asStringList(optimizedResume.get("skills_section"));
        if (!skills.isEmpty()) {
            sb.append("SKILLS\n").append(String.join(", ", skills)).append("\n");
        }
        return sb.toString().trim();
    }

    /**
     * Render the optimized resume to DOCX bytes. Returns {@code null} on any failure
     * (the caller falls back to the stored text / TXT download).
     */
    public byte[] toDocx(Map<String, Object> optimizedResume) {
        if (optimizedResume == null || optimizedResume.isEmpty()) return null;
        try (XWPFDocument doc = new XWPFDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            heading(doc, "Executive Summary");
            body(doc, asString(optimizedResume.get("executive_summary")));

            List<String> experience = asStringList(optimizedResume.get("professional_experience"));
            if (!experience.isEmpty()) {
                heading(doc, "Professional Experience");
                experience.forEach(line -> bullet(doc, line));
            }

            List<String> skills = asStringList(optimizedResume.get("skills_section"));
            if (!skills.isEmpty()) {
                heading(doc, "Skills");
                body(doc, String.join(" · ", skills));
            }

            doc.write(out);
            return out.toByteArray();
        } catch (Exception e) {
            log.error("DOCX render failed: {}", e.toString(), e);
            return null;
        }
    }

    private void heading(XWPFDocument doc, String text) {
        XWPFParagraph p = doc.createParagraph();
        p.setSpacingBefore(200);
        XWPFRun r = p.createRun();
        r.setBold(true);
        r.setFontSize(13);
        r.setText(text == null ? "" : text);
    }

    private void body(XWPFDocument doc, String text) {
        if (text == null || text.isBlank()) return;
        XWPFParagraph p = doc.createParagraph();
        XWPFRun r = p.createRun();
        r.setFontSize(11);
        r.setText(text);
    }

    private void bullet(XWPFDocument doc, String text) {
        if (text == null || text.isBlank()) return;
        XWPFParagraph p = doc.createParagraph();
        p.setIndentationLeft(360);
        XWPFRun r = p.createRun();
        r.setFontSize(11);
        r.setText("• " + text);
    }

    private List<String> asStringList(Object v) {
        if (v instanceof List<?> list) {
            return list.stream().filter(java.util.Objects::nonNull).map(Object::toString).toList();
        }
        return List.of();
    }

    private String asString(Object v) {
        return v == null ? null : v.toString();
    }
}

package ai.careerpilot.submission.mapping;

import java.util.List;

/** Phase 7.16 — the full set of mapped/unmapped canonical fields for one user. */
public record FieldMappingResult(List<MappedField> fields) {

    public List<MappedField> unmappedFields() {
        return fields.stream().filter(MappedField::unmapped).toList();
    }

    public List<MappedField> mappedFields() {
        return fields.stream().filter(f -> !f.unmapped()).toList();
    }
}

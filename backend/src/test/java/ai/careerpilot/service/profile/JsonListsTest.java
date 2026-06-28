package ai.careerpilot.service.profile;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Round-trip + null/garbage safety for the List&lt;String&gt; ↔ JSONB mapping. */
class JsonListsTest {

    @Test
    void roundTripsCleanList() {
        String json = JsonLists.toJson(List.of("Java", "Spring Boot"));
        assertEquals(List.of("Java", "Spring Boot"), JsonLists.toList(json));
    }

    @Test
    void emptyAndNullSerializeToNullColumn() {
        assertNull(JsonLists.toJson(List.of()));
        assertNull(JsonLists.toJson(null));
        assertNull(JsonLists.toJson(List.of("", "  ")));
    }

    @Test
    void nullOrBlankOrGarbageParsesToEmptyList() {
        assertTrue(JsonLists.toList(null).isEmpty());
        assertTrue(JsonLists.toList("").isEmpty());
        assertTrue(JsonLists.toList("not json").isEmpty());
        assertTrue(JsonLists.toList("{\"a\":1}").isEmpty());
    }

    @Test
    void trimsAndDropsBlanks() {
        assertEquals(List.of("Java", "AWS"), JsonLists.toList("[\" Java \", \"\", \"AWS\"]"));
    }
}

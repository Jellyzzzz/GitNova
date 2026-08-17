package com.gitnova.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RepoKeyTest {

    @Test
    void shouldRenderCanonicalOwnerAndRepositoryIdentifiers() {
        RepoKey key = RepoKey.of(12, 34);

        assertEquals(12, key.ownerId());
        assertEquals(34, key.repoId());
        assertEquals("12/34", key.value());
        assertEquals(key, RepoKey.parseCanonical("12/34"));
    }

    @Test
    void shouldRejectNonPositiveIdentifiers() {
        assertThrows(IllegalArgumentException.class, () -> RepoKey.of(0, 1));
        assertThrows(IllegalArgumentException.class, () -> RepoKey.of(1, 0));
        assertThrows(IllegalArgumentException.class, () -> RepoKey.of(-1, 1));
        assertThrows(IllegalArgumentException.class, () -> RepoKey.of(1, -1));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "",
            " ",
            "01/2",
            "1/02",
            "0/1",
            "1/0",
            "/1/2",
            "1/2/",
            "1/2/3",
            "1\\2",
            "../1",
            "1/../2",
            "owner/repository"
    })
    void shouldRejectNonCanonicalValues(String value) {
        assertThrows(IllegalArgumentException.class, () -> RepoKey.parseCanonical(value));
    }

    @Test
    void shouldRejectNullCanonicalValue() {
        assertThrows(NullPointerException.class, () -> RepoKey.parseCanonical(null));
    }
}

package com.gitnova.gitobject;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GitObjectIdTest {

    private static final String SHA1 =
            "0123456789abcdef0123456789abcdef01234567";

    @Test
    void shouldPreserveAValidLowercaseSha1() {
        assertEquals(SHA1, GitObjectId.of(SHA1).value());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "",
            "0123456789abcdef0123456789abcdef0123456",
            "0123456789abcdef0123456789abcdef012345678",
            "0123456789ABCDEF0123456789ABCDEF01234567",
            "gggggggggggggggggggggggggggggggggggggggg",
            "../0123456789abcdef0123456789abcdef01234567"
    })
    void shouldRejectAnythingOtherThanFortyLowercaseHexCharacters(String value) {
        assertThrows(IllegalArgumentException.class, () -> GitObjectId.of(value));
    }

    @Test
    void shouldRejectNullObjectId() {
        assertThrows(NullPointerException.class, () -> GitObjectId.of(null));
    }
}

package com.gitnova.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class FakeObjectStorageTest {

    @Test
    void shouldListOnlyObjectsInRequestedRepository() {
        FakeObjectStorage storage = new FakeObjectStorage();
        storage.writeObject("1/10", "sha-a", new byte[]{1});
        storage.writeObject("1/10", "sha-b", new byte[]{2});
        storage.writeObject("2/20", "sha-c", new byte[]{3});

        assertEquals(Set.of("sha-a", "sha-b"), storage.listObjects("1/10"));
        assertEquals(Set.of(), storage.listObjects("404/404"));
    }

    @Test
    void shouldPromoteAndOpenCopiedObjectBytes(@TempDir Path temporaryDirectory)
            throws Exception {
        FakeObjectStorage storage = new FakeObjectStorage();
        byte[] expected = new byte[]{0, 1, 2, -1};
        Path stagedObject = temporaryDirectory.resolve("staged-object");
        Files.write(stagedObject, expected);

        storage.promoteObject("1/10", "object-id", stagedObject);

        try (InputStream input = storage.openObject("1/10", "object-id")) {
            assertArrayEquals(expected, input.readAllBytes());
        }
    }
}

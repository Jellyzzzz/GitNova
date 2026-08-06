package com.gitnova.storage;

import org.junit.jupiter.api.Test;

import java.util.Set;

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
}

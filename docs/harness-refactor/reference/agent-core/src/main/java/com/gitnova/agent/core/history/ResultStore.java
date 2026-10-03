package com.gitnova.agent.core.history;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Path;
public interface ResultStore {
 record Ref(String objectId, String sha256, long size, boolean captureTruncated) {}
 interface Capture extends AutoCloseable {
   OutputStream stream();
   Ref finish() throws IOException;
   void close() throws IOException;
 }
 Capture capture(String captureId, long maxBytes) throws IOException;
 Path requireVerified(Ref ref) throws IOException;
}

package com.gitnova.agent.core.engine;

import java.io.IOException;
import java.util.List;
import com.gitnova.agent.core.model.ModelTypes;
/** Host input and the Engine STOP decision share one implementation/lock; HTTP is an optional adapter. */
public interface ExecutionControl {
 record Steer(String inputId, ModelTypes.Message message, String acceptedEventId) {}
 /** Runs under the Control lock; returns only after the fact is durably appended. */
 @FunctionalInterface
 interface Persistence<T> {
  T persist() throws IOException;
 }
 void checkActive();
 void cancel(String reason);
 boolean isCancelled();
 void registerCancellable(Runnable cancelAction);
 void clearCancellable();
 /** Check open/capacity first, persist, then enqueue and return that same Steer. Failure does not enqueue. */
 Steer acceptSteer(Persistence<Steer> persistAcceptance) throws IOException;
 List<Steer> drainSteers();
 /** Returns null while a steer must be applied; otherwise persists the answer before closing input. */
 <T> T finishIfNoSteer(Persistence<T> persistAnswer) throws IOException;
 void closeInput();
}

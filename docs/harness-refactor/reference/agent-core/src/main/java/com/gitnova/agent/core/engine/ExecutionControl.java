package com.gitnova.agent.core.engine;

import java.time.Instant;
import java.util.List;
import java.util.function.Supplier;
/** Both HTTP controls and STOP decision use the same implementation/lock. */
public interface ExecutionControl {
 record Steer(String commandId, String message, String acceptedEventId) {}
 void checkActive();
 void cancel(String reason);
 boolean isCancelled();
 void registerCancellable(Runnable cancelAction);
 void clearCancellable();
 void acceptSteer(Steer steer, Runnable persistAcceptance);
 List<Steer> drainSteers();
 /** Returns null when an accepted steer must be applied first; does not publish on that path. */
 <T> T finishIfNoSteer(Supplier<T> persistAnswerAndCloseInput);
 void closeInput();
}

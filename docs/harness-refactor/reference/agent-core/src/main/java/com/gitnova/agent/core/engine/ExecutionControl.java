package com.gitnova.agent.core.engine;

import java.util.List;
import java.util.function.Supplier;
import com.gitnova.agent.core.model.ModelTypes;
/** Host input and the Engine STOP decision share one implementation/lock; HTTP is an optional adapter. */
public interface ExecutionControl {
 record Steer(String inputId, ModelTypes.Message message, String acceptedEventId) {}
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

package org.bukkit.plugin;

import org.bukkit.event.Event;
import org.bukkit.event.EventException;
import org.bukkit.event.Listener;
import org.jetbrains.annotations.NotNull;
import com.google.common.base.Preconditions;

/**
 * Interface which defines the class for event call backs to plugins
 */
public interface EventExecutor {
    public void execute(@NotNull Listener listener, @NotNull Event event) throws EventException;

    // Lumance start - upstream factory, missing from our EventExecutor. Builds an executor that
    // invokes the given method reflectively with a single event argument. The executor only fires
    // when the event is actually an instance of the method's parameter type; Bukkit may deliver a
    // sibling event under the same registration, and a typed listener would simply ignore it.
    /**
     * Creates an executor that invokes the given method.
     *
     * @param method the method to invoke
     * @param eventClass the event type the method accepts
     * @return an executor delegating to the given method
     * @throws IllegalArgumentException if the method is not a valid event handler
     */
    @NotNull
    static EventExecutor create(@NotNull java.lang.reflect.Method method, @NotNull Class<? extends Event> eventClass) {
        Preconditions.checkArgument(method != null, "method cannot be null");
        Preconditions.checkArgument(eventClass != null, "eventClass cannot be null");
        Preconditions.checkArgument(method.getParameterCount() == 1,
                "method must take exactly one argument");
        method.setAccessible(true);
        return new EventExecutor() {
            @Override
            public void execute(@NotNull Listener listener, @NotNull Event event) throws EventException {
                // Bukkit hands an executor every event registered under its eventClass, which may
                // be a sibling of the method's declared parameter type. Reflective invoke throws
                // IllegalArgumentException("argument type mismatch") in that case, so filter by the
                // actual parameter type the way a normal typed listener would.
                if (!method.getParameterTypes()[0].isInstance(event)) {
                    return;
                }
                try {
                    method.invoke(listener, event);
                } catch (java.lang.reflect.InvocationTargetException ex) {
                    throw new EventException(ex.getCause());
                } catch (Throwable t) {
                    throw new EventException(t);
                }
            }
        };
    }
    // Lumance end
}

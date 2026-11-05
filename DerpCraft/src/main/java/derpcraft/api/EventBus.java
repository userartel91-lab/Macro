package derpcraft.api;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Typed events: {@code ctx.on(Events.PlayerDamage.class, (game, e) -> e.amount /= 2)}. Every class in {@link Events}
 * can be listened to. Handlers run on the game thread in the order mods registered them; an exception in one is
 * logged and the others still run.
 */
public final class EventBus {
    private EventBus() {}

    private static final Map<Class<?>, List<Events.Handler<?>>> handlers = new HashMap<>();

    static <E> void add(Class<E> type, Events.Handler<E> h) { handlers.computeIfAbsent(type, k -> new ArrayList<>()).add(h); }

    /** True if anyone listens to this event type (skip building the event otherwise). */
    public static boolean has(Class<?> type) { return handlers.containsKey(type); }

    /** Sends an event to its handlers and returns it (check isCancelled / changed fields). */
    @SuppressWarnings("unchecked")
    public static <E> E post(GameApi game, E event) {
        List<Events.Handler<?>> l = handlers.get(event.getClass());
        if (l == null) return event;
        for (Events.Handler<?> h : l) {
            try { ((Events.Handler<E>) h).handle(game, event); }
            catch (Throwable t) { System.err.println("[mod] " + event.getClass().getSimpleName() + " handler failed: " + t); t.printStackTrace(); }
        }
        return event;
    }

    /** Removes all handlers (when mods are reloaded). */
    public static void clear() { handlers.clear(); }
}

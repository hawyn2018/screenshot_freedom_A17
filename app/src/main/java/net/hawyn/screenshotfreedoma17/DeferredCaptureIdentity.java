package net.hawyn.screenshotfreedoma17;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/** One-shot caller association for verified asynchronous framework capture entry points. */
final class DeferredCaptureIdentity<T> {
    private final int limit;
    private final List<Ticket<T>> pending = new ArrayList<>();
    private long generation;

    DeferredCaptureIdentity(int limit) {
        if (limit < 1) throw new IllegalArgumentException("capture identity capacity");
        this.limit = limit;
    }

    synchronized Ticket<T> record(Object arguments, Object listener, T caller) {
        prune();
        if (listener == null || caller == null) return null;
        for (Ticket<T> ticket : pending) {
            if (ticket.matches(arguments, listener)) {
                // Reusing the exact pair before consumption makes generations ambiguous.
                // Keep a weak tombstone so a later request cannot impersonate an older one.
                ticket.ambiguous = true;
                return null;
            }
        }
        if (pending.size() >= limit) return null;
        Ticket<T> ticket = new Ticket<>(++generation, arguments, listener, caller);
        pending.add(ticket);
        return ticket;
    }

    synchronized Ticket<T> take(Object arguments, Object listener) {
        prune();
        for (Iterator<Ticket<T>> iterator = pending.iterator(); iterator.hasNext();) {
            Ticket<T> ticket = iterator.next();
            if (ticket.matches(arguments, listener)) {
                if (ticket.ambiguous) return null;
                iterator.remove();
                return ticket;
            }
        }
        return null;
    }

    synchronized void discard(Ticket<T> ticket) {
        if (ticket != null && !ticket.ambiguous) pending.remove(ticket);
    }

    synchronized void markUncertain(Ticket<T> ticket) {
        if (ticket != null) ticket.ambiguous = true;
    }

    synchronized boolean hasPending() { prune(); return !pending.isEmpty(); }
    synchronized int size() { prune(); return pending.size(); }

    private void prune() { pending.removeIf(ticket -> !ticket.alive()); }

    static final class Ticket<T> {
        final long generation;
        final T caller;
        private final WeakReference<Object> arguments;
        private final WeakReference<Object> listener;
        private final boolean nullArguments;
        volatile boolean nativeInvoked;
        private boolean ambiguous;

        Ticket(long generation, Object arguments, Object listener, T caller) {
            this.generation = generation;
            this.caller = caller;
            this.arguments = new WeakReference<>(arguments);
            this.listener = new WeakReference<>(listener);
            nullArguments = arguments == null;
        }

        private boolean alive() { return listener.get() != null && (nullArguments || arguments.get() != null); }
        private boolean matches(Object args, Object callback) {
            return listener.get() == callback && callback != null
                    && (nullArguments ? args == null : arguments.get() == args && args != null);
        }
    }
}

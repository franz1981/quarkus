package io.quarkus.arc.impl;

import java.lang.annotation.Annotation;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;

import jakarta.enterprise.context.ContextNotActiveException;
import jakarta.enterprise.context.RequestScoped;
import jakarta.enterprise.context.spi.Contextual;
import jakarta.enterprise.context.spi.CreationalContext;

import org.jboss.logging.Logger;

import io.quarkus.arc.ContextInstanceHandle;
import io.quarkus.arc.CurrentContext;
import io.quarkus.arc.InjectableBean;
import io.quarkus.arc.ManagedContext;
import io.quarkus.arc.impl.EventImpl.Notifier;

/**
 * The built-in context for {@link RequestScoped}.
 *
 * @author Martin Kouba
 */
class RequestContext implements ManagedContext {

    private static final Logger LOG = Logger.getLogger("io.quarkus.arc.requestContext");

    private final CurrentContext<RequestContextState> currentContext;

    private final Notifier<Object> initializedNotifier;
    private final Notifier<Object> beforeDestroyedNotifier;
    private final Notifier<Object> destroyedNotifier;

    public RequestContext(CurrentContext<RequestContextState> currentContext, Notifier<Object> initializedNotifier,
            Notifier<Object> beforeDestroyedNotifier, Notifier<Object> destroyedNotifier) {
        this.currentContext = currentContext;
        this.initializedNotifier = initializedNotifier;
        this.beforeDestroyedNotifier = beforeDestroyedNotifier;
        this.destroyedNotifier = destroyedNotifier;
    }

    @Override
    public Class<? extends Annotation> getScope() {
        return RequestScoped.class;
    }

    @SuppressWarnings("unchecked")
    @Override
    public <T> T getIfActive(Contextual<T> contextual, Function<Contextual<T>, CreationalContext<T>> creationalContextFun) {
        Objects.requireNonNull(contextual, "Contextual must not be null");
        Objects.requireNonNull(creationalContextFun, "CreationalContext supplier must not be null");
        InjectableBean<T> bean = (InjectableBean<T>) contextual;
        if (!Scopes.scopeMatches(this, bean)) {
            throw Scopes.scopeDoesNotMatchException(this, bean);
        }
        RequestContextState ctxState = currentContext.get();
        if (ctxState == null) {
            // Context is not active!
            return null;
        }
        var map = ctxState.map();
        ContextInstanceHandle<T> instance = (ContextInstanceHandle<T>) map.get(contextual);
        if (instance == null) {
            CreationalContext<T> creationalContext = creationalContextFun.apply(contextual);
            // Bean instance does not exist - create one if we have CreationalContext
            instance = new ContextInstanceHandleImpl<T>((InjectableBean<T>) contextual,
                    contextual.create(creationalContext), creationalContext);
            map.put(contextual, instance);
        }
        return instance.get();
    }

    @Override
    public <T> T get(Contextual<T> contextual, CreationalContext<T> creationalContext) {
        T result = getIfActive(contextual,
                CreationalContextImpl.unwrap(Objects.requireNonNull(creationalContext, "CreationalContext must not be null")));
        if (result == null) {
            throw notActive();
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    @Override
    public <T> T get(Contextual<T> contextual) {
        Objects.requireNonNull(contextual, "Contextual must not be null");
        InjectableBean<T> bean = (InjectableBean<T>) contextual;
        if (!Scopes.scopeMatches(this, bean)) {
            throw Scopes.scopeDoesNotMatchException(this, bean);
        }
        RequestContextState state = currentContext.get();
        if (state == null) {
            throw notActive();
        }
        ContextInstanceHandle<T> instance = (ContextInstanceHandle<T>) state.map().get(contextual);
        return instance == null ? null : instance.get();
    }

    @Override
    public boolean isActive() {
        return currentContext.get() != null;
    }

    @Override
    public void destroy(Contextual<?> contextual) {
        RequestContextState state = currentContext.get();
        if (state == null) {
            // Context is not active
            throw notActive();
        }
        ContextInstanceHandle<?> instance = state.map().remove(contextual);
        if (instance != null) {
            instance.destroy();
        }
    }

    @Override
    public ContextState activateInitial() {
        if (LOG.isTraceEnabled()) {
            String stack = Arrays.stream(Thread.currentThread().getStackTrace())
                    .skip(2)
                    .limit(7)
                    .map(se -> "\n\t" + se.toString())
                    .collect(Collectors.joining());
            LOG.tracef("Activate %s %s\n\t...", "new", stack);
        }
        var state = new RequestContextState();
        assert currentContext.get() == null;
        currentContext.set(state);
        // Fire an event with qualifier @Initialized(RequestScoped.class) if there are any observers for it
        fireIfNotEmpty(initializedNotifier);
        return state;
    }

    @Override
    public void activate(ContextState initialState) {
        if (LOG.isTraceEnabled()) {
            traceActivate(initialState);
        }
        if (initialState == null) {
            currentContext.set(new RequestContextState());
            // Fire an event with qualifier @Initialized(RequestScoped.class) if there are any observers for it
            fireIfNotEmpty(initializedNotifier);
        } else {
            if (initialState instanceof RequestContextState) {
                currentContext.set((RequestContextState) initialState);
            } else {
                throw new IllegalArgumentException("Invalid initial state: " + initialState.getClass().getName());
            }
        }
    }

    private void traceActivate(ContextState initialState) {
        String stack = Arrays.stream(Thread.currentThread().getStackTrace())
                .skip(2)
                .limit(7)
                .map(se -> "\n\t" + se.toString())
                .collect(Collectors.joining());
        LOG.tracef("Activate %s %s\n\t...",
                initialState != null ? Integer.toHexString(initialState.hashCode()) : "new", stack);
    }

    @Override
    public ContextState getState() {
        RequestContextState state = currentContext.get();
        if (state == null) {
            // Thread local not set - context is not active!
            throw notActive();
        }
        return state;
    }

    public ContextState getStateIfActive() {
        return currentContext.get();
    }

    @Override
    public void deactivate() {
        if (LOG.isTraceEnabled()) {
            traceDeactivate();
        }
        currentContext.remove();
    }

    private static void traceDeactivate() {
        String stack = Arrays.stream(Thread.currentThread().getStackTrace())
                .skip(2)
                .limit(7)
                .map(se -> "\n\t" + se.toString())
                .collect(Collectors.joining());
        LOG.tracef("Deactivate%s\n\t...", stack);
    }

    @Override
    public void destroy() {
        destroy(currentContext.get());
    }

    @Override
    public void destroy(ContextState state) {
        if (LOG.isTraceEnabled()) {
            traceDestroy(state);
        }
        if (state == null) {
            // nothing to destroy
            return;
        }
        if (state instanceof RequestContextState) {
            RequestContextState reqState = ((RequestContextState) state);
            var map = reqState.invalidate();
            if (map != null) {
                // Fire an event with qualifier @BeforeDestroyed(RequestScoped.class) if there are any observers for it
                fireIfNotEmpty(beforeDestroyedNotifier);
                if (!map.isEmpty()) {
                    //Performance: avoid an iterator on the map elements
                    map.forEach(this::destroyContextElement);
                    // no need for map::clear because it's already invalidated
                }
                // Fire an event with qualifier @Destroyed(RequestScoped.class) if there are any observers for it
                fireIfNotEmpty(destroyedNotifier);
            }
        } else {
            throw new IllegalArgumentException("Invalid state implementation: " + state.getClass().getName());
        }
    }

    private static void traceDestroy(ContextState state) {
        String stack = Arrays.stream(Thread.currentThread().getStackTrace())
                .skip(2)
                .limit(7)
                .map(se -> "\n\t" + se.toString())
                .collect(Collectors.joining());
        LOG.tracef("Destroy %s%s\n\t...", state != null ? Integer.toHexString(state.hashCode()) : "", stack);
    }

    private void destroyContextElement(Contextual<?> contextual, ContextInstanceHandle<?> contextInstanceHandle) {
        try {
            contextInstanceHandle.destroy();
        } catch (Exception e) {
            throw new IllegalStateException("Unable to destroy instance" + contextInstanceHandle.get(), e);
        }
    }

    private void fireIfNotEmpty(Notifier<Object> notifier) {
        if (notifier != null && !notifier.isEmpty()) {
            try {
                notifier.notify(toString());
            } catch (Exception e) {
                LOG.warn("An error occurred during delivery of the container lifecycle event for qualifiers "
                        + notifier.eventMetadata.getQualifiers(), e);
            }
        }
    }

    private ContextNotActiveException notActive() {
        String msg = "Request context is not active - you can activate the request context for a specific method using the @ActivateRequestContext interceptor binding";
        return new ContextNotActiveException(msg);
    }

    static class RequestContextState implements ContextState {

        private static final VarHandle MAP_UPDATER;

        static {
            try {
                MAP_UPDATER = MethodHandles.lookup().findVarHandle(RequestContext.class, "map", Map.class);
            } catch (ReflectiveOperationException e) {
                throw new Error(e);
            }
        }

        private Map<Contextual<?>, ContextInstanceHandle<?>> map;

        RequestContextState() {
            // lazily-initialize the context state with 2 elements capacity map
            // because destroy iterates over the whole capacity, regardless
            // the amount of elements in!
            map = new ConcurrentHashMap<>(1, 0.75f, 1);
            // this ensure safe-publication of map without getting the cost of VarHandle::fullFence
            VarHandle.storeStoreFence();
        }

        @Override
        public Map<InjectableBean<?>, Object> getContextualInstances() {
            var map = map();
            if (map == null) {
                return Map.of();
            }
            return map.values().stream()
                    .collect(Collectors.toUnmodifiableMap(ContextInstanceHandle::getBean, ContextInstanceHandle::get));
        }

        Map<Contextual<?>, ContextInstanceHandle<?>> map() {
            return (Map<Contextual<?>, ContextInstanceHandle<?>>) MAP_UPDATER.getAcquire(this);
        }

        /**
         * @return {@code != null} if the state was successfully invalidated, {@code null} otherwise
         */
        Map<Contextual<?>, ContextInstanceHandle<?>> invalidate() {
            // Atomically sets the value just like AtomicBoolean.compareAndSet(boolean, boolean)
            return (Map<Contextual<?>, ContextInstanceHandle<?>>) MAP_UPDATER.getAndSet(this, null);
        }

        @Override
        public boolean isValid() {
            return map() != null;
        }

    }

}

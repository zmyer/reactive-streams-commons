package reactivestreams.commons.publisher;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Function;

import org.reactivestreams.*;

import reactivestreams.commons.util.*;

/**
 * Subscribes to the source Publisher asynchronously through a scheduler function or
 * ExecutorService.
 * 
 * @param <T> the value type
 */
public final class PublisherSubscribeOn<T> extends PublisherSource<T, T> {

    final Function<Runnable, Runnable> scheduler;
    
    final boolean eagerCancel;
    
    final boolean requestOn;
    
    public PublisherSubscribeOn(
            Publisher<? extends T> source, 
            Function<Runnable, Runnable> scheduler,
            boolean eagerCancel, 
            boolean requestOn) {
        super(source);
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.eagerCancel = eagerCancel;
        this.requestOn = requestOn;
    }

    public PublisherSubscribeOn(
            Publisher<? extends T> source, 
            ExecutorService executor,
            boolean eagerCancel, 
            boolean requestOn) {
        super(source);
        Objects.requireNonNull(executor, "executor");
        this.scheduler = r -> {
            Future<?> f = executor.submit(r);
            return () -> f.cancel(true);
        };
        this.eagerCancel = eagerCancel;
        this.requestOn = requestOn;
    }

    @Override
    public void subscribe(Subscriber<? super T> s) {
        if (eagerCancel) {
            if (requestOn) {
                PublisherSubscribeOnClassic<T> parent = new PublisherSubscribeOnClassic<>(s, scheduler);
                s.onSubscribe(parent);
                
                Runnable f = scheduler.apply(() -> source.subscribe(parent));
                parent.setFuture(f);
            } else {
                PublisherSubscribeOnEagerDirect<T> parent = new PublisherSubscribeOnEagerDirect<>(s);
                s.onSubscribe(parent);
                
                Runnable f = scheduler.apply(() -> source.subscribe(parent));
                parent.setFuture(f);
            }
        } else {
            if (requestOn) {
                scheduler.apply(() -> {
                    source.subscribe(new PublisherSubscribeOnNonEager<>(s, scheduler));
                });
            } else {
                scheduler.apply(() -> source.subscribe(s));
            }
        }
    }
    
    static final class PublisherSubscribeOnNonEager<T> implements Subscriber<T>, Subscription {
        final Subscriber<? super T> actual;

        final Function<Runnable, Runnable> scheduler;
        
        Subscription s;
        
        public PublisherSubscribeOnNonEager(Subscriber<? super T> actual,
                Function<Runnable, Runnable> scheduler) {
            this.actual = actual;
            this.scheduler = scheduler;
        }
        
        @Override
        public void onSubscribe(Subscription s) {
            if (SubscriptionHelper.validate(this.s, s)) {
                
                this.s = s;
                
                actual.onSubscribe(this);
            }
        }
        
        @Override
        public void onNext(T t) {
            actual.onNext(t);
        }
        
        @Override
        public void onError(Throwable t) {
            actual.onError(t);
        }
        
        @Override
        public void onComplete() {
            actual.onComplete();
        }
        
        @Override
        public void request(long n) {
            scheduler.apply(() -> s.request(n));
        }
        
        @Override
        public void cancel() {
            s.cancel();
        }
    }
    
    static final class PublisherSubscribeOnEagerDirect<T> 
    extends DeferredSubscription
    implements Subscriber<T> {
        final Subscriber<? super T> actual;

        volatile Runnable future;
        @SuppressWarnings("rawtypes")
        static final AtomicReferenceFieldUpdater<PublisherSubscribeOnEagerDirect, Runnable> FUTURE =
                AtomicReferenceFieldUpdater.newUpdater(PublisherSubscribeOnEagerDirect.class, Runnable.class, "future");
        
        static final Runnable CANCELLED = () -> { };
        
        public PublisherSubscribeOnEagerDirect(Subscriber<? super T> actual) {
            this.actual = actual;
        }
        
        @Override
        public void onSubscribe(Subscription s) {
            set(s);
        }
        
        @Override
        public void onNext(T t) {
            actual.onNext(t);
        }
        
        @Override
        public void onError(Throwable t) {
            actual.onError(t);
        }
        
        @Override
        public void onComplete() {
            actual.onComplete();
        }
        
        @Override
        public void cancel() {
            super.cancel();
            Runnable a = future;
            if (a != CANCELLED) {
                a = FUTURE.getAndSet(this, CANCELLED);
                if (a != null && a != CANCELLED) {
                    a.run();
                }
            }
        }
        
        void setFuture(Runnable run) {
            if (!FUTURE.compareAndSet(this, null, run)) {
                run.run();
            }
        }
    }
    
    static final class PublisherSubscribeOnClassic<T>
    extends DeferredSubscription implements Subscriber<T> {
        final Subscriber<? super T> actual;
        
        final Function<Runnable, Runnable> scheduler;

        Collection<Runnable> tasks;
        
        volatile boolean disposed;

        volatile Runnable future;
        @SuppressWarnings("rawtypes")
        static final AtomicReferenceFieldUpdater<PublisherSubscribeOnClassic, Runnable> FUTURE =
                AtomicReferenceFieldUpdater.newUpdater(PublisherSubscribeOnClassic.class, Runnable.class, "future");

        static final Runnable CANCELLED = () -> { };

        static final Runnable FINISHED = () -> { };

        public PublisherSubscribeOnClassic(Subscriber<? super T> actual, Function<Runnable, Runnable> scheduler) {
            this.actual = actual;
            this.scheduler = scheduler;
            this.tasks = new LinkedList<>();
        }
        
        @Override
        public void onSubscribe(Subscription s) {
            set(s);
        }
        
        @Override
        public void onNext(T t) {
            actual.onNext(t);
        }
        
        @Override
        public void onError(Throwable t) {
            actual.onError(t);
        }
        
        @Override
        public void onComplete() {
            actual.onComplete();
        }
        
        @Override
        public void request(long n) {
            if (SubscriptionHelper.validate(n)) {
                ScheduledRequest sr = new ScheduledRequest(n);
                add(sr);
                
                Runnable f = scheduler.apply(sr::request);
                
                sr.setFuture(f);
            }
        }
        
        @Override
        public void cancel() {
            super.cancel();
            Runnable a = future;
            if (a != CANCELLED) {
                a = FUTURE.getAndSet(this, CANCELLED);
                if (a != null && a != CANCELLED) {
                    a.run();
                }
            }
            dispose();
        }
        
        void setFuture(Runnable run) {
            if (!FUTURE.compareAndSet(this, null, run)) {
                run.run();
            }
        }

        boolean add(Runnable run) {
            if (!disposed) {
                synchronized (this) {
                    if (!disposed) {
                        tasks.add(run);
                        return true;
                    }
                }
            }
            run.run();
            return false;
        }

        void delete(Runnable run) {
            if (!disposed) {
                synchronized (this) {
                    if (!disposed) {
                        tasks.remove(run);
                    }
                }
            }
        }
        
        void dispose() {
            if (disposed) {
                return;
            }
            
            Collection<Runnable> list;
            synchronized (this) {
                if (disposed) {
                    return;
                }
                disposed = true;
                list = tasks;
                tasks = null;
            }
            
            for (Runnable r : list) {
                r.run();
            }
        }
        
        void requestInner(long n) {
            super.request(n);
        }
        
        final class ScheduledRequest 
        extends AtomicReference<Runnable>
        implements Runnable {
            /** */
            private static final long serialVersionUID = 2284024836904862408L;
            
            final long n;
            
            public ScheduledRequest(long n) {
                this.n = n;
            }
            
            @Override
            public void run() {
                for (;;) {
                    Runnable a = get();
                    if (a == FINISHED) {
                        return;
                    }
                    if (compareAndSet(a, CANCELLED)) {
                        delete(this);
                        return;
                    }
                }
            }
            
            void request() {
                requestInner(n);

                for (;;) {
                    Runnable a = get();
                    if (a == CANCELLED) {
                        return;
                    }
                    if (compareAndSet(a, FINISHED)) {
                        delete(this);
                        return;
                    }
                }
            }
            
            void setFuture(Runnable f) {
                for (;;) {
                    Runnable a = get();
                    if (a == FINISHED) {
                        return;
                    }
                    if (a == CANCELLED) {
                        f.run();
                        return;
                    }
                    if (compareAndSet(null, f)) {
                        return;
                    }
                }
            }
        }
    }
}
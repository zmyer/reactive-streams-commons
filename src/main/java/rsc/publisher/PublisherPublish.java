package rsc.publisher;

import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.concurrent.atomic.AtomicLongFieldUpdater;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;
import java.util.function.Function;
import java.util.function.Supplier;

import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import rsc.flow.Fuseable;
import rsc.flow.FusionMode;
import rsc.flow.FusionSupport;
import rsc.util.BackpressureHelper;
import rsc.util.EmptySubscription;
import rsc.util.ExceptionHelper;
import rsc.util.SubscriptionHelper;
import rsc.util.UnsignalledExceptions;

/**
 * Shares a sequence for the duration of a function that may transform it and
 * consume it as many times as necessary without causing multiple subscriptions
 * to the upstream.
 * 
 * @param <T> the source value type
 * @param <R> the output value type
 */
@FusionSupport(input = { FusionMode.SYNC, FusionMode.ASYNC }, output = {FusionMode.SYNC, FusionMode.ASYNC})
public final class PublisherPublish<T, R> extends PublisherSource<T, R> implements Fuseable {

    final Function<? super Px<T>, ? extends Publisher<? extends R>> transform;
    
    final Supplier<? extends Queue<T>> queueSupplier;
    
    final int prefetch;

    public PublisherPublish(Publisher<? extends T> source, 
            Function<? super Px<T>, ? extends Publisher<? extends R>> transform,
            int prefetch, Supplier<? extends Queue<T>> queueSupplier) {
        super(source);
        if (prefetch < 1) {
            throw new IllegalArgumentException("prefetch > 0 required but it was " + prefetch);
        }
        this.prefetch = prefetch;
        this.transform = Objects.requireNonNull(transform, "transform");
        this.queueSupplier = Objects.requireNonNull(queueSupplier, "queueSupplier");
    }

    @Override
    public void subscribe(Subscriber<? super R> s) {
        
        PublisherPublishMulticaster<T, R> multicast = new PublisherPublishMulticaster<>(prefetch, queueSupplier);
        
        Publisher<? extends R> out;
        
        try {
            out = transform.apply(multicast);
        } catch (Throwable ex) {
            ExceptionHelper.throwIfFatal(ex);
            EmptySubscription.error(s, ex);
            return;
        }
        
        if (out == null) {
            EmptySubscription.error(s, new NullPointerException("The transform returned a null Publisher"));
            return;
        }
        
        if (out instanceof Fuseable) {
            out.subscribe(new CancelFuseableMulticaster<>(s, multicast));
        } else {
            out.subscribe(new CancelMulticaster<>(s, multicast));
        }
        
        source.subscribe(multicast);
    }

    static final class PublisherPublishMulticaster<T, R> extends Px<T> implements Subscriber<T> {
        
        final int limit;
        
        final int prefetch;
        
        final Supplier<? extends Queue<T>> queueSupplier;
        
        Queue<T> queue;
        
        volatile Subscription s;
        @SuppressWarnings("rawtypes")
        static final AtomicReferenceFieldUpdater<PublisherPublishMulticaster, Subscription> S =
                AtomicReferenceFieldUpdater.newUpdater(PublisherPublishMulticaster.class, Subscription.class, "s");

        volatile int wip;
        @SuppressWarnings("rawtypes")
        static final AtomicIntegerFieldUpdater<PublisherPublishMulticaster> WIP =
                AtomicIntegerFieldUpdater.newUpdater(PublisherPublishMulticaster.class, "wip");
        
        volatile PublishClientSubscription<T>[] subscribers;
        @SuppressWarnings("rawtypes")
        static final AtomicReferenceFieldUpdater<PublisherPublishMulticaster, PublishClientSubscription[]> SUBSCRIBERS =
                AtomicReferenceFieldUpdater.newUpdater(PublisherPublishMulticaster.class, PublishClientSubscription[].class, "subscribers");
        
        @SuppressWarnings("rawtypes")
        static final PublishClientSubscription[] EMPTY = new PublishClientSubscription[0];

        @SuppressWarnings("rawtypes")
        static final PublishClientSubscription[] TERMINATED = new PublishClientSubscription[0];

        volatile boolean done;

        volatile boolean connected;
        
        volatile boolean cancelled;
        
        Throwable error;

        int produced;
        
        int sourceMode;
        
        @SuppressWarnings("unchecked")
        public PublisherPublishMulticaster(int prefetch, Supplier<? extends Queue<T>> queueSupplier) {
            this.prefetch = prefetch;
            this.limit = prefetch - (prefetch >> 2);
            this.queueSupplier = queueSupplier;
            this.subscribers = EMPTY;
        }
        
        @Override
        public void subscribe(Subscriber<? super T> s) {
            PublishClientSubscription<T> pcs = new PublishClientSubscription<>(this, s);
            s.onSubscribe(pcs);
            
            if (add(pcs)) {
                if (pcs.once != 0) {
                    removeAndDrain(pcs);
                } else {
                    drain();
                }
            } else {
                Throwable ex = error;
                if (ex != null) {
                    s.onError(ex);
                } else {
                    s.onComplete();
                }
            }
        }
        
        @Override
        public void onSubscribe(Subscription s) {
            if (SubscriptionHelper.setOnce(S, this, s)) {
                
                if (s instanceof QueueSubscription) {
                    @SuppressWarnings("unchecked")
                    QueueSubscription<T> qs = (QueueSubscription<T>) s;
                    
                    int m = qs.requestFusion(Fuseable.ANY);
                    if (m == Fuseable.SYNC) {
                        sourceMode = m;
                        
                        queue = qs;
                        done = true;
                        connected = true;
                        
                        drain();
                        
                        return;
                    } else
                    if (m == Fuseable.ASYNC) {
                        sourceMode = m;
                        
                        queue = qs;
                        connected = true;

                        s.request(prefetch);
                        
                        return;
                    }
                }
                
                try {
                    queue = queueSupplier.get();
                } catch (Throwable ex) {
                    ExceptionHelper.throwIfFatal(ex);
                    onError(ex);
                    return;
                }
                connected = true;
                
                s.request(prefetch);
            }
        }
        
        @Override
        public void onNext(T t) {
            if (done) {
                UnsignalledExceptions.onNextDropped(t);
                return;
            }
            
            if (sourceMode != Fuseable.ASYNC) {
                if (!queue.offer(t)) {
                    onError(new IllegalStateException("Queue full?!"));
                    return;
                }
            }
            drain();
        }
        
        @Override
        public void onError(Throwable t) {
            if (done) {
                UnsignalledExceptions.onErrorDropped(t);
                return;
            }
            error = t;
            done = true;
            drain();
        }
        
        @Override
        public void onComplete() {
            done = true;
            drain();
        }
        
        void drain() {
            if (WIP.getAndIncrement(this) != 0) {
                return;
            }
            
            if (sourceMode == Fuseable.SYNC) {
                drainSync();
            } else {
                drainAsync();
            }
        }
        
        @SuppressWarnings("unchecked")
        void drainSync() {
            int missed = 1;
            
            for (;;) {
                
                if (connected) {
                    
                    if (cancelled) {
                        queue.clear();
                        return;
                    }

                    final Queue<T> queue = this.queue;
                    
                    PublishClientSubscription<T>[] a = subscribers;
                    int n = a.length;
                    
                    if (n != 0) {
                        
                        long r = Long.MAX_VALUE;
                        
                        for (int i = 0; i < n; i++) {
                            r = Math.min(r, a[i].requested);
                        }
                        
                        long e = 0L;
                        
                        while (e != r) {
                            
                            if (cancelled) {
                                queue.clear();
                                return;
                            }
                            
                            T v;
                            
                            try {
                                v = queue.poll();
                            } catch (Throwable ex) {
                                ExceptionHelper.throwIfFatal(ex);
                                queue.clear();
                                error = ex;
                                a = SUBSCRIBERS.getAndSet(this, TERMINATED);
                                for (int i = 0; i < n; i++) {
                                    a[i].actual.onError(ex);
                                }
                                return;
                            }
                            
                            if (v == null) {
                                a = SUBSCRIBERS.getAndSet(this, TERMINATED);
                                for (int i = 0; i < n; i++) {
                                    a[i].actual.onComplete();
                                }
                                return;
                            }
                            
                            for (int i = 0; i < n; i++) {
                                a[i].actual.onNext(v);
                            }
                            
                            e++;
                        }
                        
                        if (e == r) {
                            if (cancelled) {
                                queue.clear();
                                return;
                            }
                            boolean empty;
                            try {
                                empty = queue.isEmpty();
                            } catch (Throwable ex) {
                                ExceptionHelper.throwIfFatal(ex);
                                queue.clear();
                                error = ex;
                                a = SUBSCRIBERS.getAndSet(this, TERMINATED);
                                for (int i = 0; i < n; i++) {
                                    a[i].actual.onError(ex);
                                }
                                return;
                            }
                            
                            if (empty) {
                                a = SUBSCRIBERS.getAndSet(this, TERMINATED);
                                for (int i = 0; i < n; i++) {
                                    a[i].actual.onComplete();
                                }
                                return;
                            }
                        }
                        
                        if (e != 0L) {
                            for (int i = 0; i < n; i++) {
                                a[i].produced(e);
                            }
                        }
                    }
                }
                
                missed = WIP.addAndGet(this, -missed);
                if (missed == 0) {
                    break;
                }
            }
        }
        
        @SuppressWarnings("unchecked")
        void drainAsync() {
            int missed = 1;
            
            int p = produced;
            
            for (;;) {
                
                if (connected) {
                    if (cancelled) {
                        queue.clear();
                        return;
                    }

                    final Queue<T> queue = this.queue;
                    
                    PublishClientSubscription<T>[] a = subscribers;
                    int n = a.length;
                    
                    if (n != 0) {
                        
                        long r = Long.MAX_VALUE;
                        
                        for (int i = 0; i < n; i++) {
                            r = Math.min(r, a[i].requested);
                        }
                        
                        long e = 0L;
                        
                        while (e != r) {
                            if (cancelled) {
                                queue.clear();
                                return;
                            }
                            
                            boolean d = done;
                            
                            T v;
                            
                            try {
                                v = queue.poll();
                            } catch (Throwable ex) {
                                ExceptionHelper.throwIfFatal(ex);
                                s.cancel();
                                queue.clear();
                                error = ex;
                                a = SUBSCRIBERS.getAndSet(this, TERMINATED);
                                for (int i = 0; i < n; i++) {
                                    a[i].actual.onError(ex);
                                }
                                return;
                            }
                            
                            boolean empty = v == null;
                            
                            if (d) {
                                Throwable ex = error;
                                if (ex != null) {
                                    queue.clear();
                                    a = SUBSCRIBERS.getAndSet(this, TERMINATED);
                                    for (int i = 0; i < n; i++) {
                                        a[i].actual.onError(ex);
                                    }
                                    return;
                                } else
                                if (empty) {
                                    a = SUBSCRIBERS.getAndSet(this, TERMINATED);
                                    for (int i = 0; i < n; i++) {
                                        a[i].actual.onComplete();
                                    }
                                    return;
                                }
                            }
                            
                            if (empty) {
                                break;
                            }
                            
                            for (int i = 0; i < n; i++) {
                                a[i].actual.onNext(v);
                            }
                            
                            e++;
                            
                            if (++p == limit) {
                                s.request(p);
                                p = 0;
                            }
                        }
                        
                        if (e == r) {
                            if (cancelled) {
                                queue.clear();
                                return;
                            }
                            
                            boolean d = done;
                            
                            boolean empty;
                            try {
                                empty = queue.isEmpty();
                            } catch (Throwable ex) {
                                ExceptionHelper.throwIfFatal(ex);
                                s.cancel();
                                queue.clear();
                                error = ex;
                                a = SUBSCRIBERS.getAndSet(this, TERMINATED);
                                for (int i = 0; i < n; i++) {
                                    a[i].actual.onError(ex);
                                }
                                return;
                            }

                            if (d) {
                                Throwable ex = error;
                                if (ex != null) {
                                    queue.clear();
                                    a = SUBSCRIBERS.getAndSet(this, TERMINATED);
                                    for (int i = 0; i < n; i++) {
                                        a[i].actual.onError(ex);
                                    }
                                    return;
                                } else
                                if (empty) {
                                    a = SUBSCRIBERS.getAndSet(this, TERMINATED);
                                    for (int i = 0; i < n; i++) {
                                        a[i].actual.onComplete();
                                    }
                                    return;
                                }
                            }

                        }
                        
                        if (e != 0L) {
                            for (int i = 0; i < n; i++) {
                                a[i].produced(e);
                            }
                        }
                    }
                    
                }
                
                produced = p;
                
                missed = WIP.addAndGet(this, -missed);
                if (missed == 0) {
                    break;
                }
            }
        }
        
        boolean add(PublishClientSubscription<T> s) {
            for (;;) {
                PublishClientSubscription<T>[] a = subscribers;

                if (a == TERMINATED) {
                    return false;
                }
                
                int n = a.length;
                
                @SuppressWarnings("unchecked")
                PublishClientSubscription<T>[] b = new PublishClientSubscription[n + 1];
                System.arraycopy(a, 0, b, 0, n);
                b[n] = s;
                if (SUBSCRIBERS.compareAndSet(this, a, b)) {
                    return true;
                }
            }
        }
        
        @SuppressWarnings("unchecked")
        void removeAndDrain(PublishClientSubscription<T> s) {
            for (;;) {
                PublishClientSubscription<T>[] a = subscribers;

                if (a == TERMINATED || a == EMPTY) {
                    return;
                }
                
                int n = a.length;
                int j = -1;
                
                for (int i = 0; i < n; i++) {
                    if (a[i] == s) {
                        j = i;
                        break;
                    }
                }
                
                if (j < 0) {
                    return;
                }
                
                PublishClientSubscription<T>[] b;
                if (n == 1) {
                    b = EMPTY;
                } else {
                    b = new PublishClientSubscription[n - 1];
                    System.arraycopy(a, 0, b, 0, j);
                    System.arraycopy(a, j + 1, b, j, n - j - 1);
                }
                if (SUBSCRIBERS.compareAndSet(this, a, b)) {
                    drain();
                    return;
                }
            }
        }
        
        void cancel() {
            if (!cancelled) {
                cancelled = true;
                terminate();
            }
        }
        
        @SuppressWarnings("unchecked")
        void terminate() {
            SubscriptionHelper.terminate(S, this);
            subscribers = TERMINATED;
            if (WIP.getAndIncrement(this) == 0) {
                if (connected) {
                    queue.clear();
                }
            }
        }
    }
    
    static final class PublishClientSubscription<T> 
    implements Subscription {
        
        final PublisherPublishMulticaster<T, ?> parent;
        
        final Subscriber<? super T> actual;
        
        volatile long requested;
        @SuppressWarnings("rawtypes")
        static final AtomicLongFieldUpdater<PublishClientSubscription> REQUESTED =
                AtomicLongFieldUpdater.newUpdater(PublishClientSubscription.class, "requested");

        volatile int once;
        @SuppressWarnings("rawtypes")
        static final AtomicIntegerFieldUpdater<PublishClientSubscription> ONCE =
                AtomicIntegerFieldUpdater.newUpdater(PublishClientSubscription.class, "once");

        public PublishClientSubscription(PublisherPublishMulticaster<T, ?> parent, Subscriber<? super T> actual) {
            this.parent = parent;
            this.actual = actual;
        }

        @Override
        public void request(long n) {
            if (SubscriptionHelper.validate(n)) {
                BackpressureHelper.getAndAddCap(REQUESTED, this, n);
                parent.drain();
            }
        }
        
        @Override
        public void cancel() {
            if (ONCE.compareAndSet(this, 0, 1)) {
                parent.removeAndDrain(this);
            }
        }
        
        void produced(long n) {
            if (requested != Long.MAX_VALUE) {
                REQUESTED.addAndGet(this, -n);
            }
        }
    }
    
    static final class CancelMulticaster<T> implements Subscriber<T>, QueueSubscription<T> {
        final Subscriber<? super T> actual;
        
        final PublisherPublishMulticaster<?, ?> parent;

        Subscription s;
        
        public CancelMulticaster(Subscriber<? super T> actual, PublisherPublishMulticaster<?, ?> parent) {
            this.actual = actual;
            this.parent = parent;
        }

        @Override
        public void request(long n) {
            s.request(n);
        }

        @Override
        public void cancel() {
            s.cancel();
            parent.cancel();
        }

        @Override
        public void onSubscribe(Subscription s) {
            this.s = s;
            actual.onSubscribe(this);
        }

        @Override
        public void onNext(T t) {
            actual.onNext(t);
        }

        @Override
        public void onError(Throwable t) {
            parent.terminate();
            actual.onError(t);
        }

        @Override
        public void onComplete() {
            parent.terminate();
            actual.onComplete();
        }
        
        @Override
        public int requestFusion(int requestedMode) {
            return NONE;
        }
        
        @Override
        public void clear() {
            // should not be called because fusion is always rejected
        }
        
        @Override
        public boolean isEmpty() {
            // should not be called because fusion is always rejected
            return false;
        }
        
        @Override
        public int size() {
            // should not be called because fusion is always rejected
            return 0;
        }
        
        @Override
        public T poll() {
            // should not be called because fusion is always rejected
            return null;
        }
    }

    static final class CancelFuseableMulticaster<T> implements Subscriber<T>, QueueSubscription<T> {
        final Subscriber<? super T> actual;
        
        final PublisherPublishMulticaster<?, ?> parent;

        QueueSubscription<T> s;
        
        public CancelFuseableMulticaster(Subscriber<? super T> actual, PublisherPublishMulticaster<?, ?> parent) {
            this.actual = actual;
            this.parent = parent;
        }

        @Override
        public void request(long n) {
            s.request(n);
        }

        @Override
        public void cancel() {
            s.cancel();
            parent.cancel();
        }

        @SuppressWarnings("unchecked")
        @Override
        public void onSubscribe(Subscription s) {
            this.s = (QueueSubscription<T>)s;
            actual.onSubscribe(this);
        }

        @Override
        public void onNext(T t) {
            actual.onNext(t);
        }

        @Override
        public void onError(Throwable t) {
            parent.terminate();
            actual.onError(t);
        }

        @Override
        public void onComplete() {
            parent.terminate();
            actual.onComplete();
        }
        
        @Override
        public int requestFusion(int requestedMode) {
            return s.requestFusion(requestedMode);
        }
        
        @Override
        public T poll() {
            return s.poll();
        }
        
        @Override
        public boolean isEmpty() {
            return s.isEmpty();
        }
        
        @Override
        public int size() {
            return s.size();
        }
        
        @Override
        public void clear() {
            s.clear();
        }
    }

}

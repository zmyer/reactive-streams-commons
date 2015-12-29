package reactivestreams.commons;

import java.util.Objects;
import java.util.function.Predicate;

import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import reactivestreams.commons.internal.SubscriptionHelper;
import reactivestreams.commons.internal.ScalarDelayedArbiter;

/**
 * Emits a single boolean true if all values of the source sequence match
 * the predicate.
 * <p>
 * The implementation uses short-circuit logic and completes with false if
 * the predicate doesn't match a value.
 * 
 * @param <T> the source value type
 */
public final class PublisherAll<T> implements Publisher<Boolean> {

    final Publisher<? extends T> source;
    
    final Predicate<? super T> predicate;

    public PublisherAll(Publisher<? extends T> source, Predicate<? super T> predicate) {
        this.source = Objects.requireNonNull(source, "source");
        this.predicate = Objects.requireNonNull(predicate, "predicate");
    }
    
    @Override
    public void subscribe(Subscriber<? super Boolean> s) {
        source.subscribe(new PublisherAllSubscriber<T>(s, predicate));
    }
    
    static final class PublisherAllSubscriber<T> implements Subscriber<T>, Subscription {
        final Subscriber<? super Boolean> actual;
        
        final Predicate<? super T> predicate;

        final ScalarDelayedArbiter<Boolean> delayed;
        
        Subscription s;
        
        boolean done;
        
        public PublisherAllSubscriber(Subscriber<? super Boolean> actual, Predicate<? super T> predicate) {
            this.actual = actual;
            this.predicate = predicate;
            this.delayed = new ScalarDelayedArbiter<>(actual);
        }

        @Override
        public void request(long n) {
            delayed.request(n);
        }

        @Override
        public void cancel() {
            s.cancel();
            delayed.cancel();
        }

        @Override
        public void onSubscribe(Subscription s) {
            if (SubscriptionHelper.validate(this.s, s)) {
                this.s = s;
                actual.onSubscribe(this);
                
                s.request(Long.MAX_VALUE);
            }
        }

        @Override
        public void onNext(T t) {

            if (done) {
                return;
            }
            
            boolean b;

            try {
                b = predicate.test(t);
            } catch (Throwable e) {
                done = true;
                s.cancel();
                
                actual.onError(e);
                return;
            }
            if (!b) {
                done = true;
                s.cancel();
                
                delayed.set(false);
            }
        }

        @Override
        public void onError(Throwable t) {
            if (done) {
                return;
            }
            done = true;

            actual.onError(t);
        }

        @Override
        public void onComplete() {
            if (done) {
                return;
            }
            done = true;
            delayed.set(true);
        }
        
        
    }
}

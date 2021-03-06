package rsc.publisher;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicLongFieldUpdater;
import java.util.function.Consumer;

import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import rsc.flow.Loopback;
import rsc.flow.Producer;
import rsc.flow.Trackable;
import rsc.util.BackpressureHelper;
import rsc.util.ExceptionHelper;
import rsc.subscriber.SubscriptionHelper;
import rsc.util.UnsignalledExceptions;

/**
 * Drops values if the subscriber doesn't request fast enough.
 *
 * @param <T> the value type
 */
public final class PublisherDrop<T> extends PublisherSource<T, T> {

    static final Consumer<Object> NOOP = t -> {

    };

    final Consumer<? super T> onDrop;

    public PublisherDrop(Publisher<? extends T> source) {
        super(source);
        this.onDrop = NOOP;
    }


    public PublisherDrop(Publisher<? extends T> source, Consumer<? super T> onDrop) {
        super(source);
        this.onDrop = Objects.requireNonNull(onDrop, "onDrop");
    }


    @Override
    public long getPrefetch() {
        return Long.MAX_VALUE;
    }

    @Override
    public void subscribe(Subscriber<? super T> s) {
        source.subscribe(new PublisherDropSubscriber<>(s, onDrop));
    }

    static final class PublisherDropSubscriber<T>
            implements Subscriber<T>, Subscription, Producer, Loopback, Trackable {

        final Subscriber<? super T> actual;

        final Consumer<? super T> onDrop;

        Subscription s;

        volatile long requested;
        @SuppressWarnings("rawtypes")
        static final AtomicLongFieldUpdater<PublisherDropSubscriber> REQUESTED =
          AtomicLongFieldUpdater.newUpdater(PublisherDropSubscriber.class, "requested");

        boolean done;

        public PublisherDropSubscriber(Subscriber<? super T> actual, Consumer<? super T> onDrop) {
            this.actual = actual;
            this.onDrop = onDrop;
        }

        @Override
        public void request(long n) {
            if (SubscriptionHelper.validate(n)) {
                BackpressureHelper.getAndAddCap(REQUESTED, this, n);
            }
        }

        @Override
        public void cancel() {
            s.cancel();
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
                try {
                    onDrop.accept(t);
                } catch (Throwable e) {
                    UnsignalledExceptions.onNextDropped(t);
                }
                return;
            }

            if (requested != 0L) {

                actual.onNext(t);

                if (requested != Long.MAX_VALUE) {
                    REQUESTED.decrementAndGet(this);
                }

            } else {
                try {
                    onDrop.accept(t);
                } catch (Throwable e) {
                    cancel();
                    ExceptionHelper.throwIfFatal(e);
                    onError(ExceptionHelper.unwrap(e));
                }
            }
        }

        @Override
        public void onError(Throwable t) {
            if (done) {
                UnsignalledExceptions.onErrorDropped(t);
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

            actual.onComplete();
        }

        @Override
        public boolean isStarted() {
            return s != null && !done;
        }

        @Override
        public boolean isTerminated() {
            return done;
        }

        @Override
        public Object downstream() {
            return actual;
        }

        @Override
        public long requestedFromDownstream() {
            return requested;
        }

        @Override
        public Object connectedInput() {
            return onDrop;
        }

        @Override
        public Object connectedOutput() {
            return null;
        }
    }
}

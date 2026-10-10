package com.finoniq.docql;

import java.io.ByteArrayOutputStream;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

/**
 * A response body subscriber that collects at most its limit and then
 * cancels the subscription, so a misbehaving endpoint cannot exhaust memory
 * (D-06). The handler picks {@code SUCCESS_BODY_LIMIT + 1} for a 2xx — so a
 * body over the 64 MiB cap stays detectable and {@code DocQL} can flag it as
 * unusable — and {@code ERROR_BODY_READ_LIMIT} for every other status.
 */
final class BoundedBodySubscriber implements HttpResponse.BodySubscriber<byte[]> {

    private final int limit;
    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final CompletableFuture<byte[]> body = new CompletableFuture<>();
    private Flow.Subscription subscription;

    BoundedBodySubscriber(int limit) {
        this.limit = limit;
    }

    /** The bounded handler: one cap for 2xx, the error cap for everything else. */
    static HttpResponse.BodyHandler<byte[]> handler() {
        return info -> new BoundedBodySubscriber(
                info.statusCode() / 100 == 2 ? DocQL.SUCCESS_BODY_LIMIT + 1 : DocQL.ERROR_BODY_READ_LIMIT);
    }

    @Override
    public CompletionStage<byte[]> getBody() {
        return body;
    }

    @Override
    public void onSubscribe(Flow.Subscription subscription) {
        this.subscription = subscription;
        subscription.request(1);
    }

    @Override
    public void onNext(List<ByteBuffer> items) {
        if (body.isDone()) {
            return; // already cancelled at the limit; drop residual buffers
        }
        for (ByteBuffer item : items) {
            int n = Math.min(item.remaining(), limit - out.size());
            byte[] chunk = new byte[n];
            item.get(chunk);
            out.write(chunk, 0, n);
            if (out.size() >= limit) {
                break;
            }
        }
        if (out.size() >= limit) {
            subscription.cancel();
            body.complete(out.toByteArray());
        } else {
            subscription.request(1);
        }
    }

    @Override
    public void onError(Throwable throwable) {
        body.completeExceptionally(throwable);
    }

    @Override
    public void onComplete() {
        body.complete(out.toByteArray());
    }
}

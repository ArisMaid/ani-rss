package ani.rss.persistence;

import ani.rss.entity.Ani;
import ani.rss.exception.ApiProblemException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import static org.junit.jupiter.api.Assertions.*;

class SubscriptionOverrideTest {
    @TempDir Path directory;
    Ani subscription(String id,String title) { return new Ani().setId(id).setTitle(title).setSeason(1).setUrl("https://example.test/"+id); }
    SubscriptionRepository repository() { return new SubscriptionRepository(new CopyOnWriteArrayList<>(),()->directory.resolve("ani.json")); }
    @Test void duplicatesRequireExplicitConfirmationAndReplacementKeepsOwnershipId() {
        var repository=repository(); var original=subscription("owned","Same").setCurrentEpisodeNumber(7).setLastDownloadTime(123L);
        repository.addOrReplace(original,false);
        assertEquals("SUBSCRIPTION_EXISTS",assertThrows(ApiProblemException.class,()->repository.addOrReplace(subscription("new","Same"),false)).code());
        Ani updated=repository.addOrReplace(subscription("new","Same"),true);
        assertEquals("owned",updated.getId()); assertEquals(7,updated.getCurrentEpisodeNumber()); assertEquals(123L,updated.getLastDownloadTime());
        assertEquals(1,repository.snapshot().size()); assertEquals("https://example.test/new",repository.snapshot().get(0).getUrl());
    }
    @Test void idAndTitleHittingDifferentSubscriptionsCannotReplaceTwoOwners() {
        var repository=repository(); repository.addOrReplace(subscription("one","First"),false); repository.addOrReplace(subscription("two","Second"),false);
        assertThrows(IllegalArgumentException.class,()->repository.addOrReplace(subscription("one","Second"),true));
        assertEquals(2,repository.snapshot().size()); assertEquals("First",repository.snapshot().get(0).getTitle());
    }
    @Test void failedPersistenceDoesNotRemoveThePreviousSubscription() {
        var runtime=new CopyOnWriteArrayList<Ani>(); runtime.add(subscription("old","Same"));
        var repository=new SubscriptionRepository(runtime,()->directory.resolve("ani.json"),(path,content)->{throw new java.io.IOException("injected failure");});
        repository.markCommitted(runtime);
        assertThrows(IllegalStateException.class,()->repository.addOrReplace(subscription("new","Same"),true));
        assertEquals("https://example.test/old",repository.snapshot().get(0).getUrl());
    }
    @Test void concurrentAddsCannotCreateDuplicateTitles() throws Exception {
        var repository=repository(); var failures=new java.util.concurrent.atomic.AtomicInteger();
        var executor=java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var tasks=List.<java.util.concurrent.Callable<Void>>of(()->{try{repository.addOrReplace(subscription("a","Same"),false);}catch(ApiProblemException e){failures.incrementAndGet();}return null;},
                    ()->{try{repository.addOrReplace(subscription("b","Same"),false);}catch(ApiProblemException e){failures.incrementAndGet();}return null;});
            for(var future:executor.invokeAll(tasks)) future.get();
        } finally { executor.shutdownNow(); }
        assertEquals(1,repository.snapshot().size()); assertEquals(1,failures.get());
    }
}

package com.drillnotebook.app.service.memory;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.drillnotebook.app.repository.MemoryRepository;
import com.drillnotebook.app.repository.MemorySuppressionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** 用可控的慢查询验证超时、单任务复用和旧任务不能覆盖新快照。 */
class MemoryIndexServiceTest {
    private final MemoryRepository memory = mock(MemoryRepository.class);
    private final MemoryVaultService vault = mock(MemoryVaultService.class);
    private final MemoryCryptoService.MemoryKey key = mock(MemoryCryptoService.MemoryKey.class);
    private final AtomicLong revision = new AtomicLong(1);
    private final CountDownLatch started = new CountDownLatch(1);
    private final CountDownLatch release = new CountDownLatch(1);
    private MemoryIndexService indexes;

    @BeforeEach void setUp() throws Exception {
        when(key.vaultId()).thenReturn("vault");
        when(vault.currentKey()).thenReturn(Optional.of(key));
        when(memory.readVault("vault")).thenAnswer(call -> Optional.of(
                new MemoryRepository.VaultState("vault", 1, revision.get())));
        indexes = new MemoryIndexService(memory, vault, mock(MemoryCryptoService.class),
                mock(MemorySuppressionRepository.class), new ObjectMapper());
    }

    @AfterEach void close() {
        release.countDown();
        indexes.shutdown();
    }

    private void blockFirstBuild() throws Exception {
        when(memory.findPage(eq("vault"), eq("ACTIVE"), isNull(), isNull(), isNull(), isNull(), anyInt()))
                .thenAnswer(call -> {
                    started.countDown();
                    assertTrue(release.await(5, TimeUnit.SECONDS));
                    return List.of();
                }).thenReturn(List.of());
        indexes.requestBuild("vault", 1, 1);
        assertTrue(started.await(5, TimeUnit.SECONDS));
    }

    @Test void timedOutReadersReuseTheBuildAndDoNotBlockChatIndefinitely() throws Exception {
        blockFirstBuild();
        assertTimeoutPreemptively(java.time.Duration.ofSeconds(1), () -> {
            assertTrue(indexes.awaitReady("vault", 1, 1, System.nanoTime() + 20_000_000L).isEmpty());
            assertTrue(indexes.awaitReady("vault", 1, 1, System.nanoTime() + 20_000_000L).isEmpty());
        });
        verify(memory, times(1)).findPage(anyString(), anyString(), isNull(), isNull(), isNull(), isNull(), anyInt());
        release.countDown();
        assertTrue(indexes.awaitReady("vault", 1, 1, System.nanoTime() + 2_000_000_000L).isPresent());
    }

    @Test void aLateOlderBuildCannotReplaceTheNewerSynchronousSnapshot() throws Exception {
        blockFirstBuild();
        revision.set(2);
        var latest = indexes.buildNow("vault", 1, 2);
        release.countDown();
        // 再排一份新任务，等待它完成，确保单线程队列中的旧任务已退出。
        revision.set(3);
        var newest = indexes.awaitReady("vault", 1, 3, System.nanoTime() + 2_000_000_000L).orElseThrow();
        assertEquals(2, latest.revision());
        assertEquals(3, newest.revision());
        assertSame(newest, indexes.ready("vault", 1, 3).orElseThrow());
    }

    @Test void readersJoinTheSynchronousBuildWithoutRestartingIt() throws Exception {
        when(memory.findPage(eq("vault"), eq("ACTIVE"), isNull(), isNull(), isNull(), isNull(), anyInt()))
                .thenAnswer(call -> {
                    started.countDown();
                    assertTrue(release.await(5, TimeUnit.SECONDS));
                    return List.of();
                });
        var writer = Executors.newSingleThreadExecutor();
        try {
            var building = writer.submit(() -> indexes.buildNow("vault", 1, 1));
            assertTrue(started.await(5, TimeUnit.SECONDS));
            assertTrue(indexes.awaitReady("vault", 1, 1, System.nanoTime() + 20_000_000L).isEmpty());
            release.countDown();
            var built = building.get(2, TimeUnit.SECONDS);
            assertSame(built, indexes.awaitReady("vault", 1, 1, System.nanoTime() + 2_000_000_000L)
                    .orElseThrow());
            verify(memory, times(1)).findPage(anyString(), anyString(), isNull(), isNull(), isNull(), isNull(),
                    anyInt());
        } finally {
            release.countDown();
            writer.shutdownNow();
        }
    }

    @Test void lockDuringBuildCannotRepublishDecryptedData() throws Exception {
        blockFirstBuild();
        when(key.erased()).thenReturn(true);
        when(vault.currentKey()).thenReturn(Optional.empty());
        indexes.clear();
        release.countDown();
        assertTrue(indexes.awaitReady("vault", 1, 1, System.nanoTime() + 20_000_000L).isEmpty());
    }

    @Test void changedDatabaseVersionIsNotPublishedUnderTheOldIdentity() throws Exception {
        blockFirstBuild();
        revision.set(2);
        release.countDown();
        assertTrue(indexes.awaitReady("vault", 1, 1, System.nanoTime() + 2_000_000_000L).isEmpty());
        assertEquals(2, indexes.awaitReady("vault", 1, 2, System.nanoTime() + 2_000_000_000L)
                .orElseThrow().revision());
    }
}

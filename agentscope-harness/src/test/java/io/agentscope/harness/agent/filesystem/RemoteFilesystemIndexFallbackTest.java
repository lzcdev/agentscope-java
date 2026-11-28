/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.harness.agent.filesystem;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.filesystem.model.GlobResult;
import io.agentscope.harness.agent.filesystem.model.GrepResult;
import io.agentscope.harness.agent.filesystem.model.LsResult;
import io.agentscope.harness.agent.filesystem.remote.RemoteFilesystem;
import io.agentscope.harness.agent.filesystem.remote.store.InMemoryStore;
import io.agentscope.harness.agent.workspace.WorkspaceIndex;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Regression tests for issue #3197: the remote store is authoritative for enumeration.
 *
 * <p>The optional {@link WorkspaceIndex} is node-local and best-effort. Even when it holds a
 * non-empty but partial view of a prefix, {@code ls}, {@code glob} and {@code grep} must return
 * every matching file in the store — a sibling node may have written a file this index has not
 * seen. An empty-result-only scenario would pass even with a fast path that returns early, so
 * these tests deliberately use a partial index to pin the store-authoritative behavior.
 */
class RemoteFilesystemIndexFallbackTest {

    private static final RuntimeContext CTX = RuntimeContext.empty();

    /**
     * Index holds only {@code a.md}; the store holds both {@code a.md} and {@code b.md}.
     * A fast path that trusts the index would miss {@code b.md}.
     */
    private RemoteFilesystem fsWithPartialIndex() {
        InMemoryStore store = new InMemoryStore();
        RemoteFilesystem fs = new RemoteFilesystem(store, List.of("shared"));
        assertTrue(fs.write(CTX, "/memory/a.md", "needle in a").isSuccess());
        assertTrue(fs.write(CTX, "/memory/b.md", "needle in b").isSuccess());

        WorkspaceIndex index = mock(WorkspaceIndex.class);
        when(index.hasPrefix("/memory/")).thenReturn(true);
        when(index.hasPrefix("/memory")).thenReturn(true);
        when(index.listByPrefix("/memory/")).thenReturn(List.of("/memory/a.md"));
        when(index.listByPrefix("/memory")).thenReturn(List.of("/memory/a.md"));
        fs.withIndex(index);
        return fs;
    }

    @Test
    void ls_returnsStoreFiles_whenIndexIsPartial() {
        LsResult result = fsWithPartialIndex().ls(CTX, "/memory");
        assertTrue(result.isSuccess());
        assertEquals(2, result.entries().size(), () -> "ls must list both store files: " + result);
        assertEquals("/memory/a.md", result.entries().get(0).path());
        assertEquals("/memory/b.md", result.entries().get(1).path());
    }

    @Test
    void glob_returnsStoreFiles_whenIndexIsPartial() {
        GlobResult result = fsWithPartialIndex().glob(CTX, "*.md", "/memory");
        assertTrue(result.isSuccess());
        assertEquals(
                2, result.matches().size(), () -> "glob must match both store files: " + result);
        assertEquals("/memory/a.md", result.matches().get(0).path());
        assertEquals("/memory/b.md", result.matches().get(1).path());
    }

    @Test
    void grep_returnsStoreFiles_whenIndexIsPartial() {
        GrepResult result = fsWithPartialIndex().grep(CTX, "needle", "/memory", "*.md");
        assertTrue(result.isSuccess());
        assertEquals(
                2, result.matches().size(), () -> "grep must match both store files: " + result);
        assertEquals("/memory/a.md", result.matches().get(0).path());
        assertEquals("/memory/b.md", result.matches().get(1).path());
    }
}

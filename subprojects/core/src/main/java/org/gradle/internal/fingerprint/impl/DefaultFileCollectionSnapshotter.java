/*
 * Copyright 2019 the original author or authors.
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

package org.gradle.internal.fingerprint.impl;

import org.gradle.api.file.FileCollection;
import org.gradle.api.internal.file.FileCollectionInternal;
import org.gradle.api.internal.file.FileCollectionStructureVisitor;
import org.gradle.api.internal.file.FileTreeInternal;
import org.gradle.api.internal.file.collections.FileSystemMirroringFileTree;
import org.gradle.api.tasks.util.PatternSet;
import org.gradle.internal.execution.fingerprint.FileCollectionSnapshotter;
import org.gradle.internal.file.FileType;
import org.gradle.internal.file.Stat;
import org.gradle.internal.fingerprint.GenericFileTreeSnapshotter;
import org.gradle.internal.snapshot.CompositeFileSystemSnapshot;
import org.gradle.internal.snapshot.FileSystemSnapshot;
import org.gradle.internal.vfs.FileSystemAccess;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class DefaultFileCollectionSnapshotter implements FileCollectionSnapshotter {
    private final FileSystemAccess fileSystemAccess;
    private final GenericFileTreeSnapshotter genericFileTreeSnapshotter;
    private final Stat stat;

    public DefaultFileCollectionSnapshotter(FileSystemAccess fileSystemAccess, GenericFileTreeSnapshotter genericFileTreeSnapshotter, Stat stat) {
        this.fileSystemAccess = fileSystemAccess;
        this.genericFileTreeSnapshotter = genericFileTreeSnapshotter;
        this.stat = stat;
    }

    @Override
    public FileSystemSnapshot snapshot(FileCollection fileCollection) {
        return snapshotResult(fileCollection).getSnapshot();
    }

    @Override
    public Result snapshotResult(FileCollection fileCollection) {
        SnapshottingVisitor visitor = new SnapshottingVisitor();
        ((FileCollectionInternal) fileCollection).visitStructure(visitor);
        return new DefaultResult(CompositeFileSystemSnapshot.of(visitor.getRoots()), visitor.isOnlyFileTrees());
    }

    private static class DefaultResult implements Result {
        private final FileSystemSnapshot fileSystemSnapshot;
        private final boolean isFileTree;

        public DefaultResult(FileSystemSnapshot fileSystemSnapshot, boolean isFileTree) {
            this.fileSystemSnapshot = fileSystemSnapshot;
            this.isFileTree = isFileTree;
        }

        @Override
        public FileSystemSnapshot getSnapshot() {
            return fileSystemSnapshot;
        }

        @Override
        public boolean isTree() {
            return isFileTree;
        }
    }

    private class SnapshottingVisitor implements FileCollectionStructureVisitor {
        private final List<FileSystemSnapshot> roots = new ArrayList<>();
        private Boolean isOnlyFileTrees;

        @Override
        public void visitCollection(FileCollectionInternal.Source source, Iterable<File> contents) {
            for (File file : contents) {
                fileSystemAccess.read(file.getAbsolutePath(), roots::add);
            }
            isOnlyFileTrees = false;
        }

        @Override
        public void visitGenericFileTree(FileTreeInternal fileTree, FileSystemMirroringFileTree sourceTree) {
            roots.add(genericFileTreeSnapshotter.snapshotFileTree(fileTree));
            isOnlyFileTrees = false;
        }

        @Override
        public void visitFileTree(File root, PatternSet patterns, FileTreeInternal fileTree) {
            fileSystemAccess.read(
                root.getAbsolutePath(),
                new PatternSetSnapshottingFilter(patterns, stat),
                snapshot -> {
                    if (snapshot.getType() != FileType.Missing) {
                        roots.add(snapshot);
                    }
                }
            );
            if (isOnlyFileTrees == null) {
                isOnlyFileTrees = true;
            }
        }

        @Override
        public void visitFileTreeBackedByFile(File file, FileTreeInternal fileTree, FileSystemMirroringFileTree sourceTree) {
            fileSystemAccess.read(file.getAbsolutePath(), roots::add);
            isOnlyFileTrees = false;
        }

        public List<FileSystemSnapshot> getRoots() {
            return roots;
        }

        public boolean isOnlyFileTrees() {
            return isOnlyFileTrees != null && isOnlyFileTrees;
        }
    }
}

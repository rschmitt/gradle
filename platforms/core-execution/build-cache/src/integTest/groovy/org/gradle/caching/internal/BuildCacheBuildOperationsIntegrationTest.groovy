/*
 * Copyright 2017 the original author or authors.
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

package org.gradle.caching.internal

import com.google.common.collect.Iterables
import org.gradle.api.internal.tasks.execution.ExecuteTaskBuildOperationType
import org.gradle.caching.BuildCacheException
import org.gradle.caching.internal.operations.BuildCacheArchivePackBuildOperationType
import org.gradle.caching.internal.operations.BuildCacheArchiveUnpackBuildOperationType
import org.gradle.caching.internal.operations.BuildCacheLocalLoadBuildOperationType
import org.gradle.caching.internal.operations.BuildCacheLocalStoreBuildOperationType
import org.gradle.caching.internal.operations.BuildCacheRemoteDisabledDueToFailureProgressDetails
import org.gradle.caching.internal.operations.BuildCacheRemoteLoadBuildOperationType
import org.gradle.caching.internal.operations.BuildCacheRemoteStoreBuildOperationType
import org.gradle.caching.local.internal.DefaultBuildCacheTempFileStore
import org.gradle.caching.local.internal.LocalBuildCacheService
import org.gradle.integtests.fixtures.AbstractIntegrationSpec
import org.gradle.integtests.fixtures.BuildOperationsFixture
import org.gradle.integtests.fixtures.TestBuildCache
import org.gradle.internal.io.NullOutputStream
import org.gradle.util.internal.TextUtil
import spock.lang.Shared

class BuildCacheBuildOperationsIntegrationTest extends AbstractIntegrationSpec {

    @Shared
    String localCacheClass = "LocalBuildCache"
    @Shared
    String remoteCacheClass = "RemoteBuildCache"

    def operations = new BuildOperationsFixture(executer, testDirectoryProvider)

    void local(String loadBody, String storeBody) {
        register(localCacheClass, loadBody, storeBody, true)
    }

    void remote(String loadBody, String storeBody) {
        register(remoteCacheClass, loadBody, storeBody)
    }

    def setup() {
        executer.beforeExecute { it.withBuildCacheEnabled() }
    }

    void register(String className, String loadBody, String storeBody, boolean isLocal = false) {
        settingsFile << """
            class ${className} extends AbstractBuildCache {}
            class ${className}ServiceFactory implements BuildCacheServiceFactory<${className}> {
                ${className}Service createBuildCacheService(${className} configuration, Describer describer) {
                    return new ${className}Service(configuration)
                }
            }
            class ${className}Service implements BuildCacheService ${isLocal ? ", ${LocalBuildCacheService.name}" : ""} {
                ${className}Service(${className} configuration) {
                }

                @Override
                boolean load(BuildCacheKey key, BuildCacheEntryReader reader) throws BuildCacheException {
                    ${isLocal ? "" : loadBody ?: ""}
                }

                @Override
                void store(BuildCacheKey key, BuildCacheEntryWriter writer) throws BuildCacheException {
                    ${isLocal ? "" : storeBody ?: ""}
                }

                // @Override
                void loadLocally(BuildCacheKey key, Action<? super File> reader) {
                    ${isLocal ? loadBody ?: "" : ""}
                }

                // @Override
                void storeLocally(BuildCacheKey key, File file) {
                    ${isLocal ? storeBody ?: "" : ""}
                }

                void withTempFile(BuildCacheKey key, Action<? super File> action) {
                    new $DefaultBuildCacheTempFileStore.name(new File("${TextUtil.normaliseFileSeparators(file("tmp").absolutePath)}")).withTempFile(key, action)
                }

                @Override
                void close() throws IOException {
                }
            }

            buildCache {
                registerBuildCacheService(${className}, ${className}ServiceFactory)
            }
        """
    }

    String cacheableTask() {
        """
            @CacheableTask
            class CustomTask extends DefaultTask {

                @Input
                String val = "foo"

                @Input
                List<String> paths = []

                @OutputDirectory
                File dir = project.file("build/dir")

                @OutputDirectory
                File otherDir = project.file("build/otherDir")

                @TaskAction
                void generate() {
                    paths.each {
                        def f = new File(dir, it)
                        f.parentFile.mkdirs()
                        f.text = val
                    }
                }
            }
        """
    }

    def "emits pack/unpack and store/load operations for local"() {
        def localCache = new TestBuildCache(file("local-cache"))
        settingsFile << localCache.localCacheConfiguration()

        when:
        buildFile << cacheableTask() << """
            apply plugin: "base"
            tasks.create("t", CustomTask).paths << "out1" << "out2"
        """

        succeeds("t")

        then:
        operations.none(BuildCacheRemoteLoadBuildOperationType)
        operations.none(BuildCacheRemoteStoreBuildOperationType)
        def localMissLoadOp = operations.only(BuildCacheLocalLoadBuildOperationType)
        def packOp = operations.only(BuildCacheArchivePackBuildOperationType)
        def storeOp = operations.only(BuildCacheLocalStoreBuildOperationType)

        def cacheKey = localMissLoadOp.details.cacheKey
        cacheKey != null
        def archiveSize = localCache.cacheArtifact(cacheKey.toString()).length()

        !localMissLoadOp.result.hit

        packOp.details.cacheKey == cacheKey

        packOp.result.archiveSize == archiveSize
        packOp.result.archiveEntryCount == 5

        storeOp.details.cacheKey == cacheKey
        storeOp.details.archiveSize == archiveSize
        storeOp.result.stored

        when:
        succeeds("clean", "t")

        then:
        operations.none(BuildCacheRemoteStoreBuildOperationType)
        operations.none(BuildCacheRemoteLoadBuildOperationType)
        operations.none(BuildCacheLocalStoreBuildOperationType)

        def localHitLoadOp = operations.only(BuildCacheLocalLoadBuildOperationType)
        def unpackOp = operations.only(BuildCacheArchiveUnpackBuildOperationType)

        localHitLoadOp.details.cacheKey == cacheKey
        localHitLoadOp.result.hit == true
        localHitLoadOp.result.archiveSize == archiveSize

        unpackOp.details.cacheKey == cacheKey

        // Not all of the tar.gz bytes need to be read in order to unpack the archive.
        // On Linux at least, the archive may have redundant padding bytes
        // Furthermore, the exact amount of padding appears to be non deterministic.
        def cacheArtifact = localCache.cacheArtifact(unpackOp.details.cacheKey.toString())
        def sizeDiff = cacheArtifact.length() - unpackOp.details.archiveSize.toLong()
        sizeDiff > -100 && sizeDiff < 100

        unpackOp.result.archiveEntryCount == 5
    }

    def "records load failure for #exceptionType"() {
        def localCache = new TestBuildCache(file("local-cache"))
        settingsFile << localCache.localCacheConfiguration()

        when:
        remote("throw new ${exceptionType.name}('!')", "writer.writeTo(new ${NullOutputStream.name}())")
        settingsFile << """
            buildCache { remote($remoteCacheClass) }
        """
        buildFile << cacheableTask() << """
            apply plugin: "base"
            tasks.create("t", CustomTask).paths << "out1" << "out2"
        """
        def failureMessage = "${exceptionType.name}: !"

        executer.withStackTraceChecksDisabled()
        succeeds("t")

        then:
        def failedLoadOp = operations.only(BuildCacheRemoteLoadBuildOperationType)
        def cacheKey = failedLoadOp.details.cacheKey
        cacheKey != null
        failedLoadOp.result == null

        failedLoadOp.failure == failureMessage

        def taskBuildOp = operations.only(ExecuteTaskBuildOperationType)
        def remoteDisableProgress = Iterables.getOnlyElement(taskBuildOp.progress(BuildCacheRemoteDisabledDueToFailureProgressDetails))
        with(remoteDisableProgress.details) {
            buildCacheConfigurationIdentifier == ':'
            it.cacheKey == cacheKey
            failure != null
            operationType == 'LOAD'
        }

        where:
        exceptionType << [RuntimeException, IOException]
    }

    def "records store failure for #exceptionType"() {
        def localCache = new TestBuildCache(file("local-cache"))
        settingsFile << localCache.localCacheConfiguration()

        when:
        remote("", "throw new ${exceptionType.name}('!')")
        settingsFile << """
            buildCache {
                remote($remoteCacheClass).push = true
            }
        """
        buildFile << cacheableTask() << """
            apply plugin: "base"
            tasks.create("t", CustomTask).paths << "out1" << "out2"
        """

        executer.withStackTraceChecksDisabled()
        succeeds("t")

        then:
        def failedLoadOp = operations.only(BuildCacheRemoteStoreBuildOperationType)
        def cacheKey = failedLoadOp.details.cacheKey
        cacheKey != null
        failedLoadOp.result == null
        failedLoadOp.failure == "${exceptionType.name}: !"

        def taskBuildOp = operations.only(ExecuteTaskBuildOperationType)
        def remoteDisableProgress = Iterables.getOnlyElement(taskBuildOp.progress(BuildCacheRemoteDisabledDueToFailureProgressDetails))
        with(remoteDisableProgress.details) {
            buildCacheConfigurationIdentifier == ':'
            it.cacheKey == cacheKey
            failure != null
            operationType == 'STORE'
        }

        where:
        exceptionType << [RuntimeException, BuildCacheException, IOException]
    }

    def "records unpack failure"() {
        def localCache = new TestBuildCache(file("local-cache"))
        settingsFile << localCache.localCacheConfiguration()

        buildFile << cacheableTask() << """
            apply plugin: "base"
            tasks.create("t", CustomTask).paths << "out1" << "out2"
        """

        run("t")

        // Corrupt cached artifact
        localCache.listCacheFiles().each {
            it.bytes = [1, 2, 3, 4]
        }

        when:
        fails("clean", "t")

        then:
        def failedUnpackOp = operations.only(BuildCacheArchiveUnpackBuildOperationType)
        failedUnpackOp.details.cacheKey != null
        failedUnpackOp.result == null
        failedUnpackOp.failure =~ /java.util.zip.ZipException: Not in GZIP format/
    }

    def "records ops for miss then store"() {
        def localCache = new TestBuildCache(file("local-cache"))
        settingsFile << localCache.localCacheConfiguration()

        given:
        remote("", "writer.writeTo(new ${NullOutputStream.name}())")

        settingsFile << """
            buildCache {
                $config
            }
        """

        buildFile << cacheableTask() << """
            apply plugin: "base"
            tasks.create("t", CustomTask).paths << "out1" << "out2"
        """

        when:
        succeeds("t")

        then:
        def remoteMissLoadOp = operations.only(BuildCacheRemoteLoadBuildOperationType)
        if (localEnabled) {
            operations.only(BuildCacheLocalLoadBuildOperationType)
        } else {
            operations.none(BuildCacheLocalLoadBuildOperationType)
        }

        def packOp = operations.only(BuildCacheArchivePackBuildOperationType)
        def remoteStoreOp = operations.only(BuildCacheRemoteStoreBuildOperationType)
        def cacheKey = packOp.details.cacheKey

        remoteStoreOp.details.cacheKey == cacheKey

        def localCacheArtifact = localCache.cacheArtifact(cacheKey.toString())
        if (localStore) {
            assert packOp.result.archiveSize == localCacheArtifact.length()
            def localStoreOp = operations.only(BuildCacheLocalStoreBuildOperationType)
            assert localStoreOp.details.cacheKey == cacheKey
            assert localStoreOp.details.archiveSize == localCacheArtifact.length()
            assert localStoreOp.result.stored
        } else {
            assert !localCacheArtifact.exists()
            operations.none(BuildCacheLocalStoreBuildOperationType)
        }

        packOp.result.archiveEntryCount == 5
        remoteStoreOp.details.archiveSize == packOp.result.archiveSize

        operations.orderedSerialSiblings(remoteMissLoadOp, packOp, remoteStoreOp)

        where:
        localStore | localEnabled | config
        true       | true         | "remote($remoteCacheClass) { push = true }"
        false      | true         | "local.push = false; remote($remoteCacheClass) { push = true }"
        false      | false        | "local.enabled = false; remote($remoteCacheClass) { push = true }"
    }

    def "records ops for remote hit"() {
        def buildCache = new TestBuildCache(testDirectory.file("build-cache-dir").createDir())
        settingsFile << buildCache.localCacheConfiguration()

        given:
        buildFile << cacheableTask() << """
            apply plugin: "base"
            tasks.create("t", CustomTask).paths << "out1" << "out2"
        """
        succeeds("t")
        remote("", "writer.writeTo(new ${NullOutputStream.name}())")
        def initialPackOp = operations.only(BuildCacheArchivePackBuildOperationType)
        def artifactFileCopy = file("artifact")
        // move it out of the local for us to use
        assert buildCache.cacheArtifact(initialPackOp.details.cacheKey.toString()).renameTo(artifactFileCopy)
        assert !buildCache.cacheArtifact(initialPackOp.details.cacheKey.toString()).exists()

        when:
        settingsFile.text = ""
        remote("reader.readFrom(new File('${TextUtil.normaliseFileSeparators(artifactFileCopy.absolutePath)}').newInputStream())", "writer.writeTo(new ${NullOutputStream.name}())")
        settingsFile << """
            buildCache {
                ${buildCache.localCacheConfiguration()}
                $config
            }
        """

        succeeds("clean", "t")

        then:
        def remoteHitLoadOp = operations.only(BuildCacheRemoteLoadBuildOperationType)
        def unpackOp = operations.only(BuildCacheArchiveUnpackBuildOperationType)

        unpackOp.details.cacheKey == remoteHitLoadOp.details.cacheKey
        def localCacheArtifact = buildCache.cacheArtifact(remoteHitLoadOp.details.cacheKey.toString())
        if (localStore) {
            assert remoteHitLoadOp.result.archiveSize == localCacheArtifact.length()
        } else {
            assert !localCacheArtifact.exists()
        }

        unpackOp.result.archiveEntryCount == 5
        unpackOp.details.archiveSize == remoteHitLoadOp.result.archiveSize

        operations.orderedSerialSiblings(remoteHitLoadOp, unpackOp)

        where:
        config << [
            "remote($remoteCacheClass)",
            "local.push = false; remote($remoteCacheClass)",
            "local.enabled = false; remote($remoteCacheClass)",
        ]
        localStore << [
            true, false, false
        ]
    }

}

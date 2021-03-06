/*
 * Copyright 2016 the original author or authors.
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

package org.gradle.caching.http.internal

import org.gradle.integtests.fixtures.AbstractIntegrationSpec
import spock.lang.Timeout

@Timeout(120)
class HttpBuildCacheServiceIntegrationTest extends AbstractIntegrationSpec implements HttpBuildCacheFixture {

    static final String ORIGINAL_HELLO_WORLD = """
            public class Hello {
                public static void main(String... args) {
                    System.out.println("Hello World!");
                }
            }
        """
    static final String CHANGED_HELLO_WORLD = """
            public class Hello {
                public static void main(String... args) {
                    System.out.println("Hello World with Changes!");
                }
            }
        """

    def setup() {
        httpBuildCacheServer.start()
        settingsFile << useHttpBuildCache(httpBuildCacheServer.uri)

        buildFile << """
            apply plugin: "java"
        """

        file("src/main/java/Hello.java") << ORIGINAL_HELLO_WORLD
        file("src/main/resources/resource.properties") << """
            test=true
        """
    }

    def "no task is re-executed when inputs are unchanged"() {
        when:
        withBuildCache().succeeds "jar"
        then:
        skippedTasks.empty

        expect:
        withBuildCache().succeeds "clean"

        when:
        withBuildCache().succeeds "jar"
        then:
        skippedTasks.containsAll ":compileJava"
    }

    def "outputs are correctly loaded from cache"() {
        buildFile << """
            apply plugin: "application"
            mainClassName = "Hello"
        """
        withBuildCache().run "run"
        withBuildCache().run "clean"
        expect:
        withBuildCache().succeeds "run"
    }

    def "tasks get cached when source code changes back to previous state"() {
        expect:
        withBuildCache().succeeds "jar" assertTaskNotSkipped ":compileJava" assertTaskNotSkipped ":jar"

        when:
        file("src/main/java/Hello.java").text = CHANGED_HELLO_WORLD
        then:
        withBuildCache().succeeds "jar" assertTaskNotSkipped ":compileJava" assertTaskNotSkipped ":jar"

        when:
        file("src/main/java/Hello.java").text = ORIGINAL_HELLO_WORLD
        then:
        withBuildCache().succeeds "jar"
        result.assertTaskSkipped ":compileJava"
    }

    def "clean doesn't get cached"() {
        withBuildCache().run "assemble"
        withBuildCache().run "clean"
        withBuildCache().run "assemble"
        when:
        withBuildCache().succeeds "clean"
        then:
        nonSkippedTasks.contains ":clean"
    }

    def "cacheable task with cache disabled doesn't get cached"() {
        buildFile << """
            compileJava.outputs.cacheIf { false }
        """

        withBuildCache().run "compileJava"
        withBuildCache().run "clean"

        when:
        withBuildCache().succeeds "compileJava"
        then:
        // :compileJava is not cached, but :jar is still cached as its inputs haven't changed
        nonSkippedTasks.contains ":compileJava"
    }

    def "non-cacheable task with cache enabled gets cached"() {
        file("input.txt") << "data"
        buildFile << """
            class NonCacheableTask extends DefaultTask {
                @InputFile inputFile
                @OutputFile outputFile

                @TaskAction copy() {
                    project.mkdir outputFile.parentFile
                    outputFile.text = inputFile.text
                }
            }
            task customTask(type: NonCacheableTask) {
                inputFile = file("input.txt")
                outputFile = file("\$buildDir/output.txt")
                outputs.cacheIf { true }
            }
            compileJava.dependsOn customTask
        """

        when:
        withBuildCache().run "jar"
        then:
        nonSkippedTasks.contains ":customTask"

        when:
        withBuildCache().run "clean"
        withBuildCache().succeeds "jar"
        then:
        skippedTasks.contains ":customTask"
    }

    def "credentials can be specified via DSL"() {
        httpBuildCacheServer.withBasicAuth("user", "pass")
        settingsFile << """
            buildCache {
                remote.credentials {
                    username = "user"
                    password = "pass"
                }
            }
        """

        when:
        withBuildCache().succeeds "jar"
        then:
        skippedTasks.empty
        httpBuildCacheServer.authenticationAttempts == ['Basic'] as Set

        expect:
        withBuildCache().succeeds "clean"

        when:
        withBuildCache().succeeds "jar"
        then:
        skippedTasks.containsAll ":compileJava"
        httpBuildCacheServer.authenticationAttempts == ['Basic'] as Set
    }

    def "credentials can be specified via URL"() {
        httpBuildCacheServer.withBasicAuth("user", 'pass%:-0]#')
        settingsFile.text = useHttpBuildCache(getUrlWithCredentials("user", 'pass%:-0]#'))

        when:
        withBuildCache().succeeds "jar"
        then:
        skippedTasks.empty
        httpBuildCacheServer.authenticationAttempts == ['Basic'] as Set

        expect:
        withBuildCache().succeeds "clean"

        when:
        httpBuildCacheServer.reset()
        httpBuildCacheServer.withBasicAuth("user", "pass%:-0]#")
        withBuildCache().succeeds "jar"
        then:
        skippedTasks.containsAll ":compileJava"
        httpBuildCacheServer.authenticationAttempts == ['Basic'] as Set
    }

    def "credentials from DSL override credentials in URL"() {
        httpBuildCacheServer.withBasicAuth("user", "pass")
        settingsFile.text = useHttpBuildCache(getUrlWithCredentials("user", "wrongPass"))
        settingsFile << """
            buildCache {
                remote.credentials {
                    username = "user"
                    password = "pass"
                }
            }
        """

        when:
        withBuildCache().succeeds "jar"
        then:
        skippedTasks.empty
        httpBuildCacheServer.authenticationAttempts == ['Basic'] as Set
    }

    private URI getUrlWithCredentials(String user, String password) {
        def uri = httpBuildCacheServer.uri
        return new URI(uri.getScheme(), "${user}:${password}", uri.getHost(), uri.getPort(), uri.getPath(), uri.getQuery(), uri.getFragment())
    }

    def "build does not leak credentials in cache URL"() {
        httpBuildCacheServer.withBasicAuth("correct-username", "correct-password")
        settingsFile << """
            buildCache {
                remote.credentials {
                    username = "correct-username"
                    password = "correct-password"
                }
            }
        """

        when:
        executer.withArgument("--info")
        withBuildCache().succeeds "assemble"
        then:
        !result.output.contains("correct-username")
        !result.output.contains("correct-password")
    }

    def "incorrect credentials cause build to fail"() {
        httpBuildCacheServer.withBasicAuth("user", "pass")
        settingsFile << """
            buildCache {
                remote.credentials {
                    username = "incorrect-user"
                    password = "incorrect-pass"
                }
            }
        """

        when:
        withBuildCache().fails "jar"
        then:
        failureCauseContains "response status 401: Unauthorized"
        // Make sure we don't log the password
        !output.contains("incorrect-pass")
        !errorOutput.contains("incorrect-pass")
    }

    def "unknown host causes the build cache to be disabled"() {
        settingsFile << """        
            buildCache {
                remote {
                    url = "http://invalid.invalid/"
                }
            }
        """

        when:
        executer.withStackTraceChecksDisabled()
        withBuildCache().succeeds "jar"

        then:
        output.contains("java.net.UnknownHostException: invalid.invalid")
        output.contains("The remote build cache was disabled during the build due to errors.")
    }
}

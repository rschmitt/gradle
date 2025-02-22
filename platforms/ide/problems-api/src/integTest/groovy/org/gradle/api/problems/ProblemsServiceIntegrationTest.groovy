/*
 * Copyright 2023 the original author or authors.
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

package org.gradle.api.problems

import org.gradle.integtests.fixtures.AbstractIntegrationSpec

class ProblemsServiceIntegrationTest extends AbstractIntegrationSpec {

    def setup() {
        enableProblemsApiCheck()
        buildFile """
            tasks.register("reportProblem", ProblemReportingTask)
        """
    }

    def "can emit a problem with mandatory fields"() {
        given:
        buildFile """
            import org.gradle.api.problems.Problem
            import org.gradle.api.problems.Severity
            import org.gradle.internal.deprecation.Documentation

            abstract class ProblemReportingTask extends DefaultTask {
                @Inject
                protected abstract Problems getProblems();

                @TaskAction
                void run() {
                    problems.forNamespace("org.example.plugin").reporting {
                        it.label("label")
                        .stackLocation()
                        .category("type")
                    }
                }
            }
        """

        when:
        run("reportProblem")

        then:
        def problems = this.collectedProblems
        problems.size() == 1
        problems[0]["label"] == "label"
        problems[0]["problemCategory"]["category"] == "type"
        problems[0]["locations"][0] == [type:"file", length:null, column:null, line:14, path: "build file '$buildFile.absolutePath'"]
        problems[0]["locations"][1] == [
            type:"task",
            buildTreePath: ":reportProblem"
        ]
    }

    def "can emit a problem with user-manual documentation"() {
        given:
        buildFile """
            import org.gradle.api.problems.Problem
            import org.gradle.api.problems.Severity
            import org.gradle.internal.deprecation.Documentation

            abstract class ProblemReportingTask extends DefaultTask {
                @Inject
                protected abstract Problems getProblems();

                @TaskAction
                void run() {
                    problems.forNamespace("org.example.plugin").reporting {
                        it.label("label")
                        .documentedAt(Documentation.userManual("test-id", "test-section"))
                        .category("type")
                    }
                }
            }
            """

        when:
        run("reportProblem")

        then:
        this.collectedProblems.size() == 1
        def link = this.collectedProblems[0]["documentationLink"]
        link["properties"]["page"] == "test-id"
        link["properties"]["section"] == "test-section"
        link["url"].startsWith("https://docs.gradle.org")
        link["consultDocumentationMessage"].startsWith("For more information, please refer to https://docs.gradle.org")
    }

    def "can emit a problem with upgrade-guide documentation"() {
        given:
        buildFile """
            import org.gradle.api.problems.Problem
            import org.gradle.api.problems.Severity
            import org.gradle.internal.deprecation.Documentation


            abstract class ProblemReportingTask extends DefaultTask {
                @Inject
                protected abstract Problems getProblems();

                @TaskAction
                void run() {
                    problems.forNamespace("org.example.plugin").reporting {
                        it.label("label")
                        .documentedAt(Documentation.upgradeGuide(8, "test-section"))
                        .category("type")
                        }
                }
            }
            """

        when:
        run("reportProblem")

        then:
        this.collectedProblems.size() == 1
        def link = this.collectedProblems[0]["documentationLink"]
        link["properties"]["page"] == "upgrading_version_8"
        link["properties"]["section"] == "test-section"
        link["url"].startsWith("https://docs.gradle.org")
        link["consultDocumentationMessage"].startsWith("Consult the upgrading guide for further information: https://docs.gradle.org")
    }

    def "can emit a problem with dsl-reference documentation"() {
        given:
        buildFile """
            import org.gradle.api.problems.Problem
            import org.gradle.api.problems.internal.InternalProblems
            import org.gradle.api.problems.Severity
            import org.gradle.internal.deprecation.Documentation

            abstract class ProblemReportingTask extends DefaultTask {
                @Inject
                protected abstract Problems getProblems();

                @TaskAction
                void run() {
                    problems.forNamespace("org.example.plugin").reporting {
                        it.label("label")
                        .documentedAt(Documentation.dslReference(Problem.class, "label"))
                        .category("type")
                    }
                }
            }
            """

        when:
        run("reportProblem")

        then:
        this.collectedProblems.size() == 1
        def link = this.collectedProblems[0]["documentationLink"]
        link["properties"]["targetClass"] == Problem.class.name
        link["properties"]["property"] == "label"
    }

    def "can emit a problem with partially specified location"() {
        given:
        buildFile """
            import org.gradle.api.problems.Problem
            import org.gradle.api.problems.Severity
            import org.gradle.internal.deprecation.Documentation

            abstract class ProblemReportingTask extends DefaultTask {
                @Inject
                protected abstract Problems getProblems();

                @TaskAction
                void run() {
                    problems.forNamespace("org.example.plugin").reporting {
                        it.label("label")
                        .fileLocation("test-location", null, null, null)
                        .category("type")
                    }
                }
            }
            """

        when:
        run("reportProblem")

        then:
        this.collectedProblems.size() == 1
        this.collectedProblems[0]["locations"][0] == [
            "type": "file",
            "path": "test-location",
            "line": null,
            "column": null,
            "length": null
        ]
    }

    def "can emit a problem with fully specified location"() {
        given:
        buildFile """
            import org.gradle.api.problems.Problem
            import org.gradle.api.problems.Severity
            import org.gradle.internal.deprecation.Documentation

            abstract class ProblemReportingTask extends DefaultTask {
                @Inject
                protected abstract Problems getProblems();

                @TaskAction
                void run() {
                    problems.forNamespace("org.example.plugin").reporting {
                        it.label("label")
                        .fileLocation("test-location", 1, 2, 3)
                        .category("type")
                    }
                }
            }
            """

        when:
        run("reportProblem")


        def problems = this.collectedProblems
        then:
        problems.size() == 1
        problems[0]["locations"][0] == [
            "type": "file",
            "path": "test-location",
            "line": 1,
            "column": 2,
            "length": 3
        ]

        def taskPath = problems[0]["locations"][1]
        taskPath["type"] == "task"
        taskPath["buildTreePath"] == ":reportProblem"
    }

    def "can emit a problem with plugin location specified"() {
        given:
        buildFile """
            import org.gradle.api.problems.Problem
            import org.gradle.api.problems.Severity
            import org.gradle.internal.deprecation.Documentation

            abstract class ProblemReportingTask extends DefaultTask {
                @Inject
                protected abstract Problems getProblems();

                @TaskAction
                void run() {
                    problems.forNamespace("org.example.plugin").reporting {
                        it.label("label")
                        .pluginLocation("org.example.pluginid")
                        .category("type")
                    }
                }
            }
            """

        when:
        run("reportProblem")

        then:
        this.collectedProblems.size() == 1
        def problem = this.collectedProblems[0]

        def fileLocation = problem["locations"][0]
        fileLocation["type"] == "pluginId"
        fileLocation["pluginId"] == "org.example.pluginid"
    }

    def "can emit a problem with a severity"(Severity severity) {
        given:
        buildFile """
            import org.gradle.api.problems.Problem
            import org.gradle.api.problems.Severity
            import org.gradle.internal.deprecation.Documentation

            abstract class ProblemReportingTask extends DefaultTask {
                @Inject
                protected abstract Problems getProblems();

                @TaskAction
                void run() {
                    problems.forNamespace("org.example.plugin").reporting {
                        it.label("label")
                        .category("type")
                        .solution("solution")
                        .severity(Severity.${severity.name()})
                    }
                }
            }
            """

        when:
        run("reportProblem")

        then:
        this.collectedProblems.size() == 1
        this.collectedProblems[0]["severity"] == severity.name()

        where:
        severity << Severity.values()
    }

    def "can emit a problem with a solution"() {
        given:
        buildFile """
            import org.gradle.api.problems.Problem
            import org.gradle.api.problems.ProblemReporter
            import org.gradle.api.problems.Severity
            import org.gradle.internal.deprecation.Documentation

            abstract class ProblemReportingTask extends DefaultTask {
                @Inject
                protected abstract Problems getProblems();

                @TaskAction
                void run() {
                    problems.forNamespace("org.example.plugin").reporting {
                        it.label("label")
                        .category("type")
                        .solution("solution")
                    }
                }
            }
            """

        when:
        run("reportProblem")

        then:
        this.collectedProblems.size() == 1
        this.collectedProblems[0]["solutions"] == [
            "solution"
        ]
    }

    def "can emit a problem with exception cause"() {
        given:
        buildFile """
            import org.gradle.api.problems.Problem
            import org.gradle.api.problems.ProblemReporter
            import org.gradle.api.problems.Severity
            import org.gradle.internal.deprecation.Documentation

            abstract class ProblemReportingTask extends DefaultTask {
                @Inject
                protected abstract Problems getProblems();

                @TaskAction
                void run() {
                    problems.forNamespace("org.example.plugin").reporting {
                        it.label("label")
                        .category("type")
                        .withException(new RuntimeException("test"))
                    }
                }
            }
            """

        when:
        run("reportProblem")

        then:
        this.collectedProblems.size() == 1
        this.collectedProblems[0]["exception"]["message"] == "test"
        !(this.collectedProblems[0]["exception"]["stackTrace"] as List<String>).isEmpty()
    }

    def "can emit a problem with additional data"() {
        given:
        buildFile """
            import org.gradle.api.problems.Problem
            import org.gradle.api.problems.ProblemReporter
            import org.gradle.api.problems.Severity
            import org.gradle.internal.deprecation.Documentation

            abstract class ProblemReportingTask extends DefaultTask {
                @Inject
                protected abstract Problems getProblems();

                @TaskAction
                void run() {
                    problems.forNamespace("org.example.plugin").reporting {
                        it.label("label")
                        .category("type")
                        .additionalData("key", "value")
                    }
                }
            }
            """

        when:
        run("reportProblem")

        then:
        this.collectedProblems.size() == 1
        this.collectedProblems[0]["additionalData"] == [
            "key": "value"
        ]
    }

    def "cannot emit a problem with invalid additional data"() {
        given:
        disableProblemsApiCheck()

        buildFile """
            import org.gradle.api.problems.Problem
            import org.gradle.api.problems.ProblemReporter
            import org.gradle.api.problems.Severity
            import org.gradle.internal.deprecation.Documentation

            abstract class ProblemReportingTask extends DefaultTask {
                @Inject
                protected abstract Problems getProblems();

                @TaskAction
                void run() {
                    problems.forNamespace("org.example.plugin").reporting {
                        it.label("label")
                        .category("type")
                        .additionalData("key", ["collections", "are", "not", "supported", "yet"])
                    }
                }
            }
            """

        when:
        def failure = fails("reportProblem")

        then:
        failure.assertHasCause('ProblemBuilder.additionalData() supports values of type String, but java.util.ArrayList as given.')
    }

    def "can throw a problem with a wrapper exception"() {
        given:
        buildFile """
            abstract class ProblemReportingTask extends DefaultTask {
                @Inject
                protected abstract Problems getProblems();

                @TaskAction
                void run() {
                    problems.forNamespace("org.example.plugin").throwing {
                        spec -> spec
                            .label("label")
                            .category("type")
                            .withException(new RuntimeException("test"))
                    }
                }
            }
            """

        when:

        fails("reportProblem")

        then:
        this.collectedProblems.size() == 1
        this.collectedProblems[0]["exception"]["message"] == "test"
    }

    def "can rethrow a problem with a wrapper exception"() {
        given:
        buildFile """
            abstract class ProblemReportingTask extends DefaultTask {
                @Inject
                protected abstract Problems getProblems();

                @TaskAction
                void run() {
                    def exception = new RuntimeException("test")
                    problems.forNamespace("org.example.plugin").rethrowing(exception) { it
                        .label("label")
                        .category("type")
                    }
                }
            }
            """

        when:
        fails("reportProblem")

        then:
        this.collectedProblems.size() == 1
        this.collectedProblems[0]["exception"]["message"] == "test"
    }

    def "can rethrow a problem with a wrapper exception"() {
        given:
        buildFile """
            abstract class ProblemReportingTask extends DefaultTask {
                @Inject
                protected abstract Problems getProblems();

                @TaskAction
                void run() {
                    try {
                        def exception = new RuntimeException("test")
                        problems.forNamespace("org.example.plugin").throwing { spec -> spec
                            .label("inner")
                            .category("type")
                            .withException(exception)
                        }
                    } catch (RuntimeException ex) {
                        problems.forNamespace("org.example.plugin").rethrowing(ex) { spec -> spec
                            .label("outer")
                            .category("type")
                        }
                    }
                }
            }
            """

        when:
        fails("reportProblem")

        then:
        this.collectedProblems.size() == 2
        this.collectedProblems[0]["label"] == "inner"
        this.collectedProblems[1]["label"] == "outer"
    }
}

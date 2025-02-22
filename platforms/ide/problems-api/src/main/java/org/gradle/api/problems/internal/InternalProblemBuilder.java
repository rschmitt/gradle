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

package org.gradle.api.problems.internal;

import org.gradle.api.problems.DocLink;
import org.gradle.api.problems.Severity;

import javax.annotation.Nullable;

public interface InternalProblemBuilder extends ProblemBuilder {

    InternalProblemBuilder taskPathLocation(String buildTreePath);

    @Override
    InternalProblemBuilder label(String label, Object... args);

    @Override
    InternalProblemBuilder category(String category, String... details);

    @Override
    InternalProblemBuilder documentedAt(DocLink doc);

    @Override
    InternalProblemBuilder documentedAt(String url);

    @Override
    InternalProblemBuilder fileLocation(String path, @Nullable Integer line, @Nullable Integer column, @Nullable Integer length);

    @Override
    InternalProblemBuilder pluginLocation(String pluginId);

    @Override
    InternalProblemBuilder stackLocation();

    @Override
    InternalProblemBuilder details(String details);

    @Override
    InternalProblemBuilder solution(String solution);

   @Override
    InternalProblemBuilder additionalData(String key, Object value);

    @Override
    InternalProblemBuilder withException(RuntimeException e);

    @Override
    InternalProblemBuilder severity(Severity severity);
}

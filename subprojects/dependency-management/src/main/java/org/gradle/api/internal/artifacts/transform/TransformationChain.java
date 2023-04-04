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

package org.gradle.api.internal.artifacts.transform;

import org.gradle.api.Action;

import javax.annotation.Nullable;

/**
 * A series of {@link TransformationStep}s.
 */
public class TransformationChain {

    private final TransformationChain init;
    private final TransformationStep last;
    private final int stepsCount;

    public TransformationChain(@Nullable TransformationChain init, TransformationStep last) {
        this.init = init;
        this.last = last;
        this.stepsCount = init == null
            ? 1
            : (init.stepsCount() + 1);
    }

    @Nullable
    public TransformationChain getInit() {
        return init;
    }

    public TransformationStep getLast() {
        return last;
    }

    public int stepsCount() {
        return stepsCount;
    }

    public boolean requiresDependencies() {
        return (init != null && init.requiresDependencies()) || last.requiresDependencies();
    }

    public String getDisplayName() {
        String lastDisplayName = last.getDisplayName();
        return init == null
            ? lastDisplayName
            : init.getDisplayName() + " -> " + lastDisplayName;
    }

    public void visitTransformationSteps(Action<? super TransformationStep> action) {
        if (init != null) {
            init.visitTransformationSteps(action);
        }
        action.execute(last);
    }
}

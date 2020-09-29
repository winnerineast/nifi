/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.nifi.stateless.core;

import org.apache.nifi.components.state.StateMap;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public class StatelessStateMap implements StateMap {
    private final Map<String, String> stateValues;
    private final long version;

    public StatelessStateMap(final Map<String, String> stateValues, final long version) {
        this.stateValues = stateValues == null ? Collections.<String, String>emptyMap() : new HashMap<>(stateValues);
        this.version = version;
    }

    public long getVersion() {
        return version;
    }

    public String get(final String key) {
        return stateValues.get(key);
    }

    public Map<String, String> toMap() {
        return Collections.unmodifiableMap(stateValues);
    }
}

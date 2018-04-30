/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.flume.sink.xmen;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Created by ElonLo on 11/15/2017.
 */
public class WriterLinkedHashMap extends LinkedHashMap<String, BucketWriter> {
    private List<BucketWriter> removeWriters;
    private int maxOpenFiles;

    public WriterLinkedHashMap(int maxOpenFiles) {
        super(16, 0.75f, true);
        this.maxOpenFiles = maxOpenFiles;
        this.removeWriters = new ArrayList<>();
    }

    @Override
    protected boolean removeEldestEntry(Map.Entry<String, BucketWriter> eldest) {
        BucketWriter writer = eldest.getValue();
        if (size() > maxOpenFiles && !removeWriters.contains(writer)) {
            this.removeWriters.add(writer);
            return true;
        } else {
            return false;
        }
    }

    public void check() {
        if (size() <= 0) {
            return;
        }

        List<String> removeKeys = new ArrayList<>();
        for (Map.Entry<String, BucketWriter> entry : entrySet()) {
            BucketWriter writer = entry.getValue();
            if (writer.isExpire()) {
                removeKeys.add(entry.getKey());
                if (!removeWriters.contains(writer)) {
                    this.removeWriters.add(writer);
                }
            }
        }

        for (String key : removeKeys) {
            remove(key);
        }

        //删除
        for (BucketWriter writer : removeWriters) {
            writer.closeAndRename();
        }
        removeWriters.clear();
    }

    public void setMaxOpenFiles(int maxOpenFiles) {
        this.maxOpenFiles = maxOpenFiles;
    }
}

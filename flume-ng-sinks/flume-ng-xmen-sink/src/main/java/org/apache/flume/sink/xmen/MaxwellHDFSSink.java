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

import com.google.gson.Gson;
import org.apache.flume.sink.hdfs.HDFSWriterFactory;

/**
 * 通过Maxwell收集数据到HDFS SINK
 * @author ElonLo
 *
 */
public class MaxwellHDFSSink extends HDFSBaseSink{
    private HDFSHeadInfo hdfsHeadInfo;

    public MaxwellHDFSSink() {
    }

    @Override
    protected boolean fillingData(String line) {
        if (!JsonUtils.isGoodJson(line)) {
            System.err.println("The line is not json format. line = " + line);
            return false;
        }
        Gson gson = new Gson();
        hdfsHeadInfo = gson.fromJson(line, HDFSHeadInfo.class);
        if (hdfsHeadInfo == null) {
            System.err.println("The line is not json format. line = " + line);
            return false;
        }

        return true;
    }

    @Override
    protected String getWriterKey(String writeTime) {
        StringBuilder builder = new StringBuilder();
        builder.append(hdfsHeadInfo.database);
        builder.append("_");
        builder.append(hdfsHeadInfo.table);
        builder.append("_");
        builder.append(hdfsHeadInfo.topic);
        builder.append("_");
        builder.append(writeTime);
        return builder.toString();
    }

    @Override
    protected String getWriterPath(int id) {
        StringBuilder builder = new StringBuilder();
        builder.append(this.filePath);
        builder.append("raw");
        builder.append(DIRECTORY_DELIMITER);
        builder.append(hdfsHeadInfo.topic);
        builder.append(DIRECTORY_DELIMITER);
        builder.append(hdfsHeadInfo.database);
        builder.append(DIRECTORY_DELIMITER);
        builder.append(hdfsHeadInfo.table);
        builder.append(DIRECTORY_DELIMITER);
        builder.append(hdfsHeadInfo.table);
        builder.append(".log.");
        builder.append(id);
        builder.append(".");
        builder.append(System.currentTimeMillis());
        builder.append(defaultInUseSuffix);

        return builder.toString();
    }

    @Override
    protected long getWriterTime() {
        return System.currentTimeMillis();
    }
}

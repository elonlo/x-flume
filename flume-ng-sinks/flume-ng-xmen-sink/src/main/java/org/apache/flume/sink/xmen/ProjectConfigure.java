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
import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;

/**
 * Created by ElonLo on 11/5/2017.
 */
public class ProjectConfigure {
    protected static final Logger LOG = LoggerFactory.getLogger(ProjectConfigure.class);
    public class BaseConfigure {
        public int id = 0;
        public boolean switchOn = true;
        public long fileCloseTime = 600000;
        public int maxOpenFiles = 5000;
        public String region = "default";
        public long batchSize = 100;
        public String fileType = "DataStream";

        @Override
        public String toString() {
            return "BaseConfigure{" +
                    "id=" + id +
                    ", switchOn=" + switchOn +
                    ", fileCloseTime=" + fileCloseTime +
                    ", maxOpenFiles=" + maxOpenFiles +
                    ", region='" + region + '\'' +
                    ", batchSize=" + batchSize +
                    ", fileType='" + fileType + '\'' +
                    '}';
        }
    }

    private String path;
    private long lastModifyTime;

    public ProjectConfigure(String path) {
        this.path = path;
    }

    public BaseConfigure checkUpdate() {
        if (StringUtils.isEmpty(path)) {
            return null;
        }
        File file = new File(path);
        if (file.lastModified() <= lastModifyTime) {
            return null;
        }
        lastModifyTime = file.lastModified();
        Reader reader = null;
        try {
            reader = new FileReader(file);
            Gson gson = new Gson();
            BaseConfigure conf = gson.fromJson(reader, BaseConfigure.class);
            if (conf != null) {
                LOG.info(conf.toString());
                return conf;
            }
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
        return null;
    }
}

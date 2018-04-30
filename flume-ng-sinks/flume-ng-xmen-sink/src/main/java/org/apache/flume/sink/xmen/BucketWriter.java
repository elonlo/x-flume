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

import org.apache.flume.Context;
import org.apache.flume.Event;
import org.apache.flume.sink.hdfs.HDFSWriter;
import org.apache.flume.sink.hdfs.HDFSWriterFactory;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

/**
 * Created by ElonLo on 11/14/2017.
 */
public class BucketWriter {
    private static final Logger LOG = LoggerFactory.getLogger(BucketWriter.class);
    private static final String defaultInUseSuffix = ".tmp";
    private static final String DIRECTORY_DELIMITER = System.getProperty("file.separator");
    private static final HDFSWriterFactory writerFactory;
    static {
        writerFactory = new HDFSWriterFactory();
    }

    private boolean bOpen;
    private Context context;
    private FileSystem fs;
    private HDFSWriter writer;
    String writerKey;
    private String path;
    private String fileType;
    private long createTime;
    private long closeTime;

    public BucketWriter(Context context, FileSystem fs, String writerKey, String path, String fileType, long closeTime) throws IOException {
        this.context = context;
        this.fs = fs;
        this.writerKey = writerKey;
        this.path = path;
        this.fileType = fileType;
        this.createTime = System.currentTimeMillis();
        this.closeTime = closeTime;

        this.init();

        this.bOpen = true;
    }

    public void append(Event event) throws IOException {
        writer.append(event);
    }

    public void flush() throws IOException {
        writer.sync();
    }

    public String getWriterKey() {
        return writerKey;
    }

    /**
     * 该文件读写是否完毕
     * @return
     */
    public boolean isExpire() {
        return System.currentTimeMillis() - createTime >= closeTime;
    }

    public boolean isOpen() {
        return bOpen;
    }

    public void closeAndRename() {
        close();
        rename();
    }

    public String getPath() {
        return path;
    }

    public void close() {
        try {
            writer.close();
            bOpen = false;
        } catch (IOException e) {
            LOG.error("Close Error: " + path);
            e.printStackTrace();
        }
    }

    private void init() throws IOException {
        Path destPath = new Path(path.substring(0, path.lastIndexOf(DIRECTORY_DELIMITER)));
        fs.mkdirs(destPath);
        writer = writerFactory.getWriter(fileType);
        writer.configure(context);
        writer.open(path);
    }

    private void rename() {
        String newPath = path.substring(0, path.lastIndexOf(defaultInUseSuffix));
        Path srcPath = new Path(path);
        Path dstPath = new Path(newPath);
        try {
            if (fs.exists(srcPath)) {
                fs.rename(srcPath, dstPath);
                LOG.info("Rename " + srcPath + " to " + dstPath);
            }
        } catch (IOException e) {
            LOG.error("Rename Error: " + path);
            e.printStackTrace();
        }
    }
}

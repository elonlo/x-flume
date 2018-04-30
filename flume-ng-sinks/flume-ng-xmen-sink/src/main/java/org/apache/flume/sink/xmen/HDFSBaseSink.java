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

import com.google.common.base.Preconditions;
import org.apache.commons.lang.StringUtils;
import org.apache.flume.*;
import org.apache.flume.conf.Configurable;
import org.apache.flume.instrumentation.SinkCounter;
import org.apache.flume.sink.AbstractSink;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Created by ElonLo on 8/12/2017.
 */
public abstract class HDFSBaseSink extends AbstractSink implements Configurable {
    protected static final Logger LOG = LoggerFactory.getLogger(HDFSBaseSink.class);
    protected static final String defaultInUseSuffix = ".tmp";
    protected final static SimpleDateFormat timeFormat = new SimpleDateFormat("yyyy-MM-dd-HH");
    protected static String DIRECTORY_DELIMITER = System.getProperty("file.separator");

    protected Context context;
    protected String filePath;
    protected FileSystem fileSystem;
    protected long batchSize;
    protected String fileType;
    protected String region;
    protected ScheduledExecutorService scheduler;
    protected WriterLinkedHashMap bucketWriterMap;
    protected SinkCounter sinkCounter;
    private ProjectConfigure projectConfigure;

    //controller
    private int id;
    private boolean switchOn;
    private long fileCloseTime;
    private int maxOpenFiles;

    //stat
    private long statEventCount = 0;
    private long statTakeTime = 0;
    private long statAppendTime = 0;
    private long statSyncTime = 0;
    private long statAllTime = 0;
    private long t1;
    private long t2;
    private long tStart;
    private long tEnd;
    private int txnEventCount;

    public HDFSBaseSink() {}

    @Override
    public Sink.Status process() throws EventDeliveryException {
        bucketWriterMap.check();

        //if sink is switch off, then just return
        if ( !this.switchOn ) {
            LOG.warn("HdfsSink is set off, just return.");
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
            }
            return Status.READY;
        }

        t1 = System.currentTimeMillis();
        ArrayList<BucketWriter> writerList = new ArrayList<>();
        Channel channel = this.getChannel();
        Transaction transaction = channel.getTransaction();
        transaction.begin();
        txnEventCount = 0;
        BucketWriter writer = null;

        try {
            while ((long)txnEventCount < this.batchSize) {
                tStart = System.currentTimeMillis(); //stat start
                Event event = channel.take();
                tEnd = System.currentTimeMillis(); //stat end
                statTakeTime += tEnd - tStart;
                if (event == null)
                    break;
                String line = new String(event.getBody(), "UTF-8");
                if (!StringUtils.isEmpty(line)) {
                    if (fillingData(line)) {
                        String writeTime = timeFormat.format(getWriterTime());
                        String writerKey = getWriterKey(writeTime);

                        writer = bucketWriterMap.get(writerKey);

                        if (writer == null) {
                            String path = getWriterPath(this.id);
                            writer = new BucketWriter(context, fileSystem, writerKey, path, fileType, fileCloseTime);
                            bucketWriterMap.put(writerKey, writer);
                        }

                        tStart = System.currentTimeMillis(); //stat start
                        writer.append(event);
                        tEnd = System.currentTimeMillis(); //stat end
                        statAppendTime += tEnd - tStart;

                        if (!writerList.contains(writer)) {
                            writerList.add(writer);
                        }
                    }
                } else {
                    System.err.println("The line is empty. ignore!");
                }

                ++txnEventCount;
            }

            tStart = System.currentTimeMillis(); //stat start
            for (BucketWriter bucketWriter : writerList) {
                if (bucketWriter.isOpen()) {
                    bucketWriter.flush();
                }
            }
            tEnd = System.currentTimeMillis(); //stat end
            statSyncTime += tEnd - tStart;

            if (txnEventCount == 0) {
                this.sinkCounter.incrementBatchEmptyCount();
            } else if ((long)txnEventCount == this.batchSize) {
                this.sinkCounter.incrementBatchCompleteCount();
            } else {
                this.sinkCounter.incrementBatchUnderflowCount();
            }
            statEventCount += txnEventCount;
            transaction.commit();

            t2 = System.currentTimeMillis();
            statAllTime += t2 - t1;

            //print stat info
            if (statEventCount > 0) {
                LOG.info("HdfsSink-TIME-STAT sink[" + this.getName()
                        + "] writers[" + writerList.size()
                        + "] eventcount[" + statEventCount
                        + "] all[" + statAllTime
                        + "] take[" + statTakeTime
                        + "] append[" + statAppendTime
                        + "] sync[" + statSyncTime
                        + "]");
            }
            statEventCount = 0;
            statAllTime = 0;
            statTakeTime = 0;
            statAppendTime = 0;
            statSyncTime = 0;

            if (txnEventCount < 1) {
                return Sink.Status.BACKOFF;
            }
            this.sinkCounter.addToEventDrainSuccessCount((long)txnEventCount);
            return Sink.Status.READY;
        } catch (FileNotFoundException e) {
            transaction.rollback();
            LOG.error("FileNotFoundException remove the file. path: " + (writer != null ? writer.getPath() : "null"));
            e.printStackTrace();
            if (writer != null) {
                writer.close();
                bucketWriterMap.remove(writer.getWriterKey());
            }
            return Sink.Status.BACKOFF;
        } catch (IOException e) {
            transaction.rollback();
            LOG.error("IOException rollback, path: " + (writer != null ? writer.getPath() : "null"));
            e.printStackTrace();
            return Sink.Status.BACKOFF;
        } catch (Throwable th) {
            transaction.rollback();
            LOG.error("Throwable rollback, path" + (writer != null ? writer.getPath() : "null"));
            th.printStackTrace();
            if (th instanceof Error) {
                throw (Error)th;
            }
            throw new EventDeliveryException(th);
        }
        finally {
            transaction.close();
        }
    }

    @Override
    public void configure(Context context) {
        // TODO Auto-generated method stub
        this.context = context;
        this.projectConfigure = new ProjectConfigure(context.getString("project_conf_path", ""));
        //初始化变量
        ProjectConfigure.BaseConfigure baseSinkConf = projectConfigure.checkUpdate();
        copyBaseSinkConf(baseSinkConf);

        Preconditions.checkArgument(this.batchSize > 0, "batchSize must be greater than 0");

        this.filePath = (String) Preconditions.checkNotNull((Object)context.getString("hdfs.path"), "hdfs.path is required");
        if (this.filePath.endsWith(DIRECTORY_DELIMITER)) {
            this.filePath += DIRECTORY_DELIMITER;
        }

        if (this.sinkCounter == null) {
            this.sinkCounter = new SinkCounter(this.getName());
        }
    }

    @Override
    public synchronized void start() {
        // TODO Auto-generated method stub
        LOG.info("HDFSSink start");
        this.bucketWriterMap = new WriterLinkedHashMap(maxOpenFiles);
        Configuration config = new Configuration();
        config.setBoolean("fs.automatic.close", false);
        try {
            this.fileSystem = new Path(this.filePath).getFileSystem(config);
        }
        catch (IOException ex) {
            LOG.error(ex.getMessage(), ex);
        }
        this.scheduler = Executors.newSingleThreadScheduledExecutor();
        this.checkConfScheduler();
        this.sinkCounter.start();
        super.start();
    }

    @Override
    public synchronized void stop() {
        LOG.info("HDFSSink stop");
        // TODO Auto-generated method stub
        if (this.scheduler != null) {
            this.scheduler.shutdown();
            this.scheduler = null;
        }

        closeAllWriter();

        try {
            this.fileSystem.close();
        }
        catch (IOException pathEntry) {
            // empty catch block
        }
        this.fileSystem = null;
        this.sinkCounter.stop();
        super.stop();
    }

    public String toString() {
        return "{ Sink type:" + this.getClass().getSimpleName() + ", name:" + this.getName() + " }";
    }

    private void checkConfScheduler() {

        this.scheduler.scheduleWithFixedDelay(new Runnable() {
            @Override
            public void run() {
                try {
                    Calendar cal = Calendar.getInstance();
                    LOG.info("check configure file. date = " + cal.getTime());

                    ProjectConfigure.BaseConfigure configure = projectConfigure.checkUpdate();
                    if (configure != null) {
                        copyBaseSinkConf(configure);
                        bucketWriterMap.setMaxOpenFiles(maxOpenFiles);
                    }
                } catch (Throwable e) {
                    LOG.error("checkConfScheduler: " + e.toString());
                }
            }
        }, 10, 30, TimeUnit.SECONDS);
    }

    private void copyBaseSinkConf(ProjectConfigure.BaseConfigure conf) {
        this.id = conf.id;
        this.switchOn = conf.switchOn;
        this.fileCloseTime = conf.fileCloseTime;
        this.maxOpenFiles = conf.maxOpenFiles;
        this.region = conf.region;
        this.batchSize = conf.batchSize > 0 ? conf.batchSize : batchSize;
        this.fileType = conf.fileType;
    }

    private void closeAllWriter() {
        LOG.info("closeAllHDFSFile begin");
        if (bucketWriterMap.size() <= 0) {
            return;
        }
        for (Map.Entry<String, BucketWriter> entry : bucketWriterMap.entrySet()) {
            entry.getValue().closeAndRename();
        }
        bucketWriterMap.clear();
        LOG.info("closeAllHDFSFile end");
    }

    protected abstract boolean fillingData(String line);

    protected abstract String getWriterKey(String writeTime);

    protected abstract String getWriterPath(int id);

    protected abstract long getWriterTime();
}

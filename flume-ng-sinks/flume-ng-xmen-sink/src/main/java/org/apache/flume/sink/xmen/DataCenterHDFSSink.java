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

import org.apache.commons.lang.StringUtils;

import java.text.SimpleDateFormat;
import java.util.regex.Pattern;

/**
 * 数据中心定制化HDFS SINK
 * BIVersion|AppId|Topic|triggerTime|platform|ZoneID
 * @author ElonLo
 *
 */
public class DataCenterHDFSSink extends HDFSBaseSink{
    private SimpleDateFormat dayTimeFormat = new SimpleDateFormat("yyyyMMdd");
    private String[] hdfsHeadInfos;

	public DataCenterHDFSSink() {
	}
	
    @Override
    protected boolean fillingData(String line) {
        hdfsHeadInfos = null;
        String[] tmpHeadInfos = line.split("\\|");
        if (tmpHeadInfos != null && tmpHeadInfos.length >= 6 &&
                isDouble(tmpHeadInfos[0]) &&
                isNumber(tmpHeadInfos[1]) &&
                StringUtils.isNotEmpty(tmpHeadInfos[2]) &&
                isNumber(tmpHeadInfos[3])) {
            hdfsHeadInfos = tmpHeadInfos;
            return true;
        } else {
            LOG.error("The line data format error. line = " + line);
        }
        return false;
    }

    @Override
    protected String getWriterKey(String writeTime) {
        StringBuilder builder = new StringBuilder();
        builder.append(hdfsHeadInfos[1]);
        builder.append("_");
        builder.append(hdfsHeadInfos[2]);
        builder.append("_");
        builder.append(this.region);
        builder.append("_");
        builder.append(hdfsHeadInfos[5]);
        builder.append("_");
        builder.append(writeTime);
        return builder.toString();
    }

    @Override
    protected String getWriterPath(int id) {
        StringBuilder builder = new StringBuilder();
        builder.append(this.filePath);
        builder.append(hdfsHeadInfos[1]);
        builder.append(DIRECTORY_DELIMITER);
        builder.append("raw");
        builder.append(DIRECTORY_DELIMITER);
        builder.append(this.region);
        builder.append(DIRECTORY_DELIMITER);
        builder.append(hdfsHeadInfos[5]);
        builder.append(DIRECTORY_DELIMITER);
        builder.append(hdfsHeadInfos[2]);
        builder.append(DIRECTORY_DELIMITER);
        builder.append(dayTimeFormat.format(getWriterTime()));
        builder.append(DIRECTORY_DELIMITER);
        builder.append(hdfsHeadInfos[2]);
        builder.append(".log.");
        builder.append(id);
        builder.append(".");
        builder.append(System.currentTimeMillis());
        builder.append(defaultInUseSuffix);
        return builder.toString();
    }

    @Override
    protected long getWriterTime() {
        return Long.valueOf(hdfsHeadInfos[3]);
    }

    private boolean isNumber(String str) {
        if (StringUtils.isEmpty(str)) {
            return false;
        }
        Pattern pattern = Pattern.compile("^[-\\+]?[\\d]*$");
        return pattern.matcher(str).matches();
    }

    private boolean isDouble(String str) {
        if (StringUtils.isEmpty(str)) {
            return false;
        }
        Pattern pattern = Pattern.compile("^[-\\+]?[.\\d]*$");
        return pattern.matcher(str).matches();
    }
}

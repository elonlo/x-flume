/**
 Licensed to the Apache Software Foundation (ASF) under one or more
 contributor license agreements.  See the NOTICE file distributed with
 this work for additional information regarding copyright ownership.
 The ASF licenses this file to You under the Apache License, Version 2.0
 (the "License"); you may not use this file except in compliance with
 the License.  You may obtain a copy of the License at

 http://www.apache.org/licenses/LICENSE-2.0

 Unless required by applicable law or agreed to in writing, software
 distributed under the License is distributed on an "AS IS" BASIS,
 WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 See the License for the specific language governing permissions and
 limitations under the License.
 limitations under the License.
 */
package org.apache.flume.sink.kafka;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.*;

/**
 * Created by raytheon-ld on 2017/12/8.
 */
public class KafkaConfigureTest {
    @Before
    public void setUp() throws Exception {

    }

    @After
    public void tearDown() throws Exception {
    }

    @Test
    public void testCheckUpdate() throws Exception {
        KafkaConfigure configure = new KafkaConfigure("kafka_conf.json");
        KafkaConfigure.BaseConfigure baseConfigure = configure.checkUpdate();
        System.out.println(baseConfigure.toString());
    }

    @Test
    public void testPattern() throws Exception {
        String line = "1.3|1000|log_login|1512716506169|windows|0|12000103|10001782|301|1|1512529600|1512716506|103.232.87.13|Rt50SZJRUe529554|kuaiyin_android";
        String pattern = "^\\d+.\\d+\\|\\d+\\|([a-zA-Z0-9_-]*)\\|";
        // Create a Pattern object
        Pattern r = Pattern.compile(pattern);
        // Now create matcher object.
        Matcher m = r.matcher(line);
        if (m.find( )) {
            System.out.println("Found value: " + m.group(1) );
        }
    }
}
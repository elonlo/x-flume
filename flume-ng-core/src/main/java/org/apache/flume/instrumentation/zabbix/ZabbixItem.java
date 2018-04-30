/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.flume.instrumentation.zabbix;

public final class ZabbixItem {

    private final String key;
    private final String value;
    private final String hostname;

    /**
     * Create a literal value item.
     * 
     * @param key
     *            The monitoring server's key for this statistic.
     * @param value
     *            The literal value.
     */

    public ZabbixItem(final String key, final String value) {
        if (key == null || "".equals(key.trim())) {
            throw new IllegalArgumentException("empty key");
        }
        if (value == null) {
            throw new IllegalArgumentException("null value for key '" + key
                    + "'");
        }

        this.key = key;
        this.value = value;
        this.hostname = null;
    }
    
    public ZabbixItem(final String key, final String value,
            final String hostname) {
        if (key == null || "".equals(key.trim())) {
            throw new IllegalArgumentException("empty key");
        }
        if (value == null) {
            throw new IllegalArgumentException("null value for key '" + key
                    + "'");
        }
        if (hostname == null) {
            throw new IllegalArgumentException("null value for hostname '"
                    + hostname + "'");
        }

        this.key = key;
        this.value = value;
        this.hostname = hostname;
    }

    /**
     * @return The current hostname for this item.
     * @throws Exception
     */
    public String getHostName() throws Exception {
        return hostname;
        // return JMXHelper.Query(value, attribute);
    }

    /**
     * Find the item's key.
     * 
     * @return The monitoring server's key for this item.
     */
    public String getKey() {
        return key;
    }

    /**
     * @return The current value for this item.
     * @throws Exception
     *             When the item could not be queried in the platform's mbean
     *             server.
     */
    public String getValue() throws Exception {
        return value;
        // return JMXHelper.Query(value, attribute);
    }

    @Override
    public String toString() {
        return "ZabbixItem [key=" + key + ", value=" + value + ", hostname="
                + hostname + "]";
    }
    
    

}
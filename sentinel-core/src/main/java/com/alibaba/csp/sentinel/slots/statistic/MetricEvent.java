/*
 * Copyright 1999-2018 Alibaba Group Holding Ltd.
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
package com.alibaba.csp.sentinel.slots.statistic;

/**
 * @author Eric Zhao
 */
public enum MetricEvent {

    /**
     * Normal pass.
     */
    PASS, //没有被sentinel拦截的请求
    /**
     * Normal block.
     */
    BLOCK, //被sentinel拦截的请求
    EXCEPTION,//调用tracer.trace（）记录的异常请求（业务异常同样也统计在 SUCCESS中）
    SUCCESS, //所有没有被sentinel拦截的请求（包括正常响应和业务异常请求）
    RT,

    /**
     * Passed in future quota (pre-occupied, since 1.5.0).
     */
    OCCUPIED_PASS
}

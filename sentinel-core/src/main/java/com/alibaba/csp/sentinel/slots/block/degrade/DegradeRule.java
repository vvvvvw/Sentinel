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
package com.alibaba.csp.sentinel.slots.block.degrade;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import com.alibaba.csp.sentinel.concurrent.NamedThreadFactory;
import com.alibaba.csp.sentinel.context.Context;
import com.alibaba.csp.sentinel.node.ClusterNode;
import com.alibaba.csp.sentinel.node.DefaultNode;
import com.alibaba.csp.sentinel.slots.block.AbstractRule;
import com.alibaba.csp.sentinel.slots.block.RuleConstant;
import com.alibaba.csp.sentinel.slots.clusterbuilder.ClusterBuilderSlot;

/**
 * <p>
 * Degrade is used when the resources are in an unstable state, these resources
 * will be degraded within the next defined time window. There are two ways to
 * measure whether a resource is stable or not:
 * 降级被使用在资源处于不稳定状态的时候，这些不稳定的资源
 * 会在下一个时间窗口内降级。 衡量资源是否稳定有两种方法：
 *
 * </p>
 * <ul>
 * <li>
 * Average response time ({@code DEGRADE_GRADE_RT}): When
 * the average RT exceeds the threshold ('count' in 'DegradeRule', in milliseconds), the
 * resource enters a quasi-degraded state. If the RT of next coming 5
 * requests still exceed this threshold, this resource will be downgraded, which
 * means that in the next time window (defined in 'timeWindow', in seconds) all the
 * access to this resource will be blocked.
 * 平均响应时间：当平均响应时间超过阈值的时候，资源进入准降级状态。如果接下来的5个请求仍然超过 rt阈值，则该资源就会被降级，也就意味着在下一个时间
 * 窗口内所有对该资源的请求都会被拦截（todo？ 秒级时间窗口还是分钟级时间窗口？）
 * </li>
 * <li>
 * Exception ratio: When the ratio of exception count per second and the
 * success qps exceeds the threshold, access to the resource will be blocked in
 * the coming window.
 * 异常率：当总qps和异常qps大于阈值的时候，在下一个时间窗口内（todo？ 秒级时间窗口还是分钟级时间窗口？）对该资源的请求会被拦截
 * </li>
 * </ul>
 *
 * @author jialiang.linjl
 */
public class DegradeRule extends AbstractRule {

    private static final int RT_MAX_EXCEED_N = 5;

    @SuppressWarnings("PMD.ThreadPoolCreationRule")
    private static ScheduledExecutorService pool = Executors.newScheduledThreadPool(
        Runtime.getRuntime().availableProcessors(), new NamedThreadFactory("sentinel-degrade-reset-task", true));

    public DegradeRule() {}

    public DegradeRule(String resourceName) {
        setResource(resourceName);
    }

    /**
     * RT threshold or exception ratio threshold count.
     */
    private double count;

    /**
     * Degrade recover timeout (in seconds) when degradation occurs.
     */
    private int timeWindow;

    /**
     * Degrade strategy (0: average RT, 1: exception ratio).
     */
    private int grade = RuleConstant.DEGRADE_GRADE_RT;

    //熔断状态开关，true：表示被熔断；false：表示没有被熔断
    private final AtomicBoolean cut = new AtomicBoolean(false);

    public int getGrade() {
        return grade;
    }

    public DegradeRule setGrade(int grade) {
        this.grade = grade;
        return this;
    }

    //在上一个秒级滑动窗口中检测到rt或者错误率大于阈值 准备熔断时，还有检查连续的RT_MAX_EXCEED_N（默认5个）流量是否都 大于阈值或者错误，如果是的话才会真正熔断；只要有一个rt小于阈值或者没有错误，passCount就会重置为0
    private AtomicLong passCount = new AtomicLong(0);

    public double getCount() {
        return count;
    }

    public DegradeRule setCount(double count) {
        this.count = count;
        return this;
    }

    private boolean isCut() {
        return cut.get();
    }

    private void setCut(boolean cut) {
        this.cut.set(cut);
    }

    public AtomicLong getPassCount() {
        return passCount;
    }

    public int getTimeWindow() {
        return timeWindow;
    }

    public DegradeRule setTimeWindow(int timeWindow) {
        this.timeWindow = timeWindow;
        return this;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof DegradeRule)) {
            return false;
        }
        if (!super.equals(o)) {
            return false;
        }

        DegradeRule that = (DegradeRule)o;

        if (count != that.count) {
            return false;
        }
        if (timeWindow != that.timeWindow) {
            return false;
        }
        if (grade != that.grade) {
            return false;
        }
        return true;
    }

    @Override
    public int hashCode() {
        int result = super.hashCode();
        result = 31 * result + new Double(count).hashCode();
        result = 31 * result + timeWindow;
        result = 31 * result + grade;
        return result;
    }

    @Override
    public boolean passCheck(Context context, DefaultNode node, int acquireCount, Object... args) {
        if (cut.get()) { //如果已经被熔断，直接拦截
            return false;
        }

        ClusterNode clusterNode = ClusterBuilderSlot.getClusterNode(this.getResource());//获取cluster
        if (clusterNode == null) {
            return true;
        }

        if (grade == RuleConstant.DEGRADE_GRADE_RT) { //如果是rt降级规则
            double rt = clusterNode.avgRt(); //从秒级滑动窗口中获取平均响应时间（包括异常和正常请求）
            if (rt < this.count) { //如果该资源在秒级滑动窗口中的平均响应时间小于阈值，则将 passCount重置为0
                passCount.set(0);
                return true;
            }

            // Sentinel will degrade the service only if count exceeds.
            if (passCount.incrementAndGet() < RT_MAX_EXCEED_N) { //否则平均rt大于阈值时，passCount+1；如果passCount大于阈值（默认为5），拦截
                return true;
            }
        } else if (grade == RuleConstant.DEGRADE_GRADE_EXCEPTION_RATIO) { //如果是错误率降级规则
            double exception = clusterNode.exceptionQps(); //异常qps
            double success = clusterNode.successQps(); //总的未被拦截的请求qps
            double total = clusterNode.totalQps(); //总qps（包括拦截和没有被拦截的）
            // if total qps less than RT_MAX_EXCEED_N, pass.
            if (total < RT_MAX_EXCEED_N) { //如果 总的qps小于5，不熔断
                return true;
            }

            double realSuccess = success - exception;
            if (realSuccess <= 0 && exception < RT_MAX_EXCEED_N) { //如果没有正常响应且异常qps小于5，则不熔断
                return true;
            }

            if (exception / success < count) { //如果异常率小于阈值，不熔断
                return true;
            }
        } else if (grade == RuleConstant.DEGRADE_GRADE_EXCEPTION_COUNT) {
            double exception = clusterNode.totalException();
            if (exception < count) { //如果异常数量小于阈值，不熔断
                return true;
            }
        }

        if (cut.compareAndSet(false, true)) {
            ResetTask resetTask = new ResetTask(this); //启动延时任务，时间窗口后重置计数器和降级状态开关
            pool.schedule(resetTask, timeWindow, TimeUnit.SECONDS);
        }

        return false;
    }

    @Override
    public String toString() {
        return "DegradeRule{" +
            "resource=" + getResource() +
            ", grade=" + grade +
            ", count=" + count +
            ", limitApp=" + getLimitApp() +
            ", timeWindow=" + timeWindow +
            "}";
    }

    private static final class ResetTask implements Runnable {

        private DegradeRule rule;

        ResetTask(DegradeRule rule) {
            this.rule = rule;
        }

        @Override
        public void run() {
            rule.getPassCount().set(0);
            rule.cut.set(false);
        }
    }
}


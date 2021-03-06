/**
 * Licensed to JumpMind Inc under one or more contributor
 * license agreements.  See the NOTICE file distributed
 * with this work for additional information regarding
 * copyright ownership.  JumpMind Inc licenses this file
 * to you under the GNU General Public License, version 3.0 (GPLv3)
 * (the "License"); you may not use this file except in compliance
 * with the License.
 *
 * You should have received a copy of the GNU General Public License,
 * version 3.0 (GPLv3) along with this library; if not, see
 * <http://www.gnu.org/licenses/>.
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.jumpmind.symmetric.job;

import java.util.Date;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.commons.lang.StringUtils;
import org.jumpmind.symmetric.ISymmetricEngine;
import org.jumpmind.symmetric.SymmetricException;
import org.jumpmind.symmetric.common.Constants;
import org.jumpmind.symmetric.common.ParameterConstants;
import org.jumpmind.symmetric.model.JobDefinition;
import org.jumpmind.symmetric.model.JobDefinition.ScheduleType;
import org.jumpmind.symmetric.model.JobDefinition.StartupType;
import org.jumpmind.symmetric.model.Lock;
import org.jumpmind.symmetric.service.IParameterService;
import org.jumpmind.util.RandomTimeSlot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.jmx.export.annotation.ManagedAttribute;
import org.springframework.jmx.export.annotation.ManagedMetric;
import org.springframework.jmx.export.annotation.ManagedOperation;
import org.springframework.jmx.export.annotation.ManagedResource;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.support.CronTrigger;

@ManagedResource(description = "The management interface for a job")
abstract public class AbstractJob implements Runnable, IJob {

    protected final Logger log = LoggerFactory.getLogger(getClass());

    private String jobName;

    private JobDefinition jobDefinition;

    private AtomicBoolean paused = new AtomicBoolean(false);

    private Date lastFinishTime;

    private AtomicBoolean running = new AtomicBoolean(false);

    private long lastExecutionTimeInMs;

    private long totalExecutionTimeInMs;

    private long numberOfRuns;

    private boolean started;

    private boolean hasNotRegisteredMessageBeenLogged = false;

    protected ISymmetricEngine engine;

    private ThreadPoolTaskScheduler taskScheduler;

    private ScheduledFuture<?> scheduledJob;

    private RandomTimeSlot randomTimeSlot;

    protected AbstractJob(String jobName, ISymmetricEngine engine, ThreadPoolTaskScheduler taskScheduler) {
        this.engine = engine;
        this.taskScheduler = taskScheduler;
        this.jobName = jobName;
        IParameterService parameterService = engine.getParameterService();
        this.randomTimeSlot = new RandomTimeSlot(parameterService.getExternalId(),
                parameterService.getInt(ParameterConstants.JOB_RANDOM_MAX_START_TIME_MS));        
    }

    public void start() {
        if (this.scheduledJob == null && engine != null
                && !engine.getClusterService().isInfiniteLocked(getName())) {
            if (jobDefinition.getScheduleType() == ScheduleType.CRON) {
                String cronExpression = jobDefinition.getSchedule();
                log.info("Starting job '{}' with cron expression: '{}'", jobName, cronExpression);
                try {                    
                    this.scheduledJob = taskScheduler.schedule(this, new CronTrigger(cronExpression));
                } catch (Exception ex) {
                    throw new SymmetricException("Failed to schedule job '" + jobName + "' with schedule '" + 
                            getJobDefinition().getSchedule() + "'", ex);
                }
                started = true;
            } else {
                long timeBetweenRunsInMs = getTimeBetweenRunsInMs();
                if (timeBetweenRunsInMs <= 0) {
                    return;
                }

                int startDelay = randomTimeSlot.getRandomValueSeededByExternalId();
                long currentTimeMillis = System.currentTimeMillis();
                long lastRunTime = currentTimeMillis - timeBetweenRunsInMs;
                Lock lock = engine.getClusterService().findLocks().get(getName());
                if (lock != null && lock.getLastLockTime() != null) {
                    long newRunTime = lock.getLastLockTime().getTime();
                    if (lastRunTime < newRunTime) {
                        lastRunTime = newRunTime;
                    }
                }
                Date firstRun = new Date(lastRunTime + timeBetweenRunsInMs + startDelay);
                log.info("Starting {} on periodic schedule: every {}ms with the first run at {}", new Object[] {jobName,
                        timeBetweenRunsInMs, firstRun});
                this.scheduledJob = taskScheduler.scheduleWithFixedDelay(this,
                        firstRun, timeBetweenRunsInMs);
                started = true;
            }
        }
    }

    protected long getTimeBetweenRunsInMs() {
        long timeBetweenRunsInMs = -1;
        try {
            timeBetweenRunsInMs = Long.parseLong(jobDefinition.getSchedule());
            if (timeBetweenRunsInMs <= 0) {
                log.error("Failed to schedule job '" + jobName + "' because of an invalid schedule '" + 
                        getJobDefinition().getSchedule() + "'");
                return -1;
            }
        } catch (NumberFormatException ex) {
            log.error("Failed to schedule job '" + jobName + "' because of an invalid schedule '" + 
                    getJobDefinition().getSchedule() + "'", ex);
            return -1;
        }
        return timeBetweenRunsInMs;
    }

    public boolean stop() {
        boolean success = false;
        if (this.scheduledJob != null) {
            success = this.scheduledJob.cancel(true);
            this.scheduledJob = null;
            if (success) {
                log.info("The {} job has been cancelled", jobName);
                started = false;
            } else {
                log.warn("Failed to cancel this job, {}", jobName);
            }
        }
        return success;
    }

    public String getName() {
        return jobName;
    }

    public JobDefinition getJobDefinition() {
        return jobDefinition;
    }

    public void setJobDefinition(JobDefinition jobDefinition) {
        this.jobDefinition = jobDefinition;
    }

    @ManagedOperation(description = "Run this job if it isn't already running")
    public boolean invoke() {
        return invoke(true);
    }

    @Override
    public boolean invoke(boolean force) {
        IParameterService parameterService = engine.getParameterService();
        boolean ok = checkPrerequsites(force);
        if (!ok) {
            return false;
        }

        try {
            MDC.put("engineName", engine.getEngineName());
            long startTime = System.currentTimeMillis();
            try {
                if (!running.compareAndSet(false, true)) { // This ensures this job only runs once on this instance.
                    log.info("Job '{}' is already running on another thread and will not run at this time.", getName());
                    return false;
                }
                if (parameterService.is(ParameterConstants.SYNCHRONIZE_ALL_JOBS)) {
                    synchronized (AbstractJob.class) {
                        doJob(force);
                    }
                } else {
                    doJob(force);
                }

            } finally {
                lastFinishTime = new Date();
                long endTime = System.currentTimeMillis();
                lastExecutionTimeInMs = endTime - startTime;
                totalExecutionTimeInMs += lastExecutionTimeInMs;
                if (lastExecutionTimeInMs > Constants.LONG_OPERATION_THRESHOLD) {
                    engine.getStatisticManager().addJobStats(jobName,
                            startTime, endTime, 0);
                }
                numberOfRuns++;
                running.set(false);
            }
        } catch (final Throwable ex) {
            log.error("Exception while executing job '" + getName() + "'", ex);
        } 

        return true;
    }

    /**
     * @return
     */
    protected boolean checkPrerequsites(boolean force) {
        if (engine == null) {
            log.info("Could not find a reference to the SymmetricEngine while running job '{}'", getName());
            return false;
        }
        if (Thread.interrupted()) {
            log.warn("This thread was interrupted.  Not executing the job '{}' until the interrupted status has cleared", getName());
            return false;
        }
        if (!engine.isStarted()) {
            log.info("The engine is not currently started, will not run job '{}'", getName());
            return false;
        }
        if (running.get()) {
            log.info("Job '{}' is already marked as running, will not run again now.", getName());
            return false;            
        }
        if (paused.get() && !force) {
            log.info("Job '{}' is paused and will not run at this time.", getName());
            return false;
        }
        if (jobDefinition.isRequiresRegistration() && !engine.getRegistrationService().isRegisteredWithServer()) {      
            if (!hasNotRegisteredMessageBeenLogged) {
                log.info("Did not run the '{}' job because the engine is not registered.", getName());
                hasNotRegisteredMessageBeenLogged = true;
            }
        }

        return true;
    }

    /*
     * This method is called from the job
     */
    public void run() {
        MDC.put("engineName", engine != null ? engine.getEngineName() : "unknown");
        invoke(false);
    }

    protected abstract void doJob(boolean force) throws Exception;

    @Override
    @ManagedOperation(description = "Pause this job")
    public void pause() {
        setPaused(true);
    }

    @Override
    @ManagedOperation(description = "Resume the job")
    public void unpause() {
        setPaused(false);
    }

    public void setPaused(boolean paused) {
        this.paused.set(paused);
    }

    @Override
    @ManagedAttribute(description = "If true, this job has been paused")
    public boolean isPaused() {
        return paused.get();
    }

    @Override
    @ManagedAttribute(description = "If true, this job has been started")
    public boolean isStarted() {
        return started;
    }

    @Override
    @ManagedMetric(description = "The amount of time this job spent in execution during it's last run")
    public long getLastExecutionTimeInMs() {
        return lastExecutionTimeInMs;
    }

    @Override
    @ManagedAttribute(description = "The last time this job completed execution")
    public Date getLastFinishTime() {
        return lastFinishTime;
    }

    @Override
    @ManagedAttribute(description = "If true, the job is already running")
    public boolean isRunning() {
        return running.get();
    }

    @Override
    @ManagedMetric(description = "The number of times this job has been run during the lifetime of the JVM")
    public long getNumberOfRuns() {
        return numberOfRuns;
    }

    @Override
    @ManagedMetric(description = "The total amount of time this job has spent in execution during the lifetime of the JVM")
    public long getTotalExecutionTimeInMs() {
        return totalExecutionTimeInMs;
    }

    @Override
    @ManagedMetric(description = "The total amount of time this job has spend in execution during the lifetime of the JVM")
    public long getAverageExecutionTimeInMs() {
        if (numberOfRuns > 0) {
            return totalExecutionTimeInMs / numberOfRuns;
        } else {
            return 0;
        }
    }

    public abstract JobDefaults getDefaults();

    public StartupType getStartupType() {
        // TODO check override parameters...
        return jobDefinition.getStartupType();
    }

    public ISymmetricEngine getEngine() {
        return engine;
    }

    public String getJobName() {
        return jobName;
    }

    public void setJobName(String jobName) {
        this.jobName = jobName;
    }

    public void setEngine(ISymmetricEngine engine) {
        this.engine = engine;
    }

    public ThreadPoolTaskScheduler getTaskScheduler() {
        return taskScheduler;
    }

    public void setTaskScheduler(ThreadPoolTaskScheduler taskScheduler) {
        this.taskScheduler = taskScheduler;
    }
}

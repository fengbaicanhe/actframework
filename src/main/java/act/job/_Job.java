package act.job;

import act.util.DestroyableBase;
import org.osgl._;
import org.osgl.exception.NotAppliedException;
import org.osgl.util.C;
import org.osgl.util.E;
import org.osgl.util.S;

import java.util.List;
import java.util.UUID;

class _Job extends DestroyableBase implements Runnable {

    private String id;
    private boolean oneTime;
    private AppJobManager manager;
    private JobTrigger trigger;
    private _.Func0<?> worker;
    private List<_Job> parallelJobs = C.newList();
    private List<_Job> followingJobs = C.newList();
    private List<_Job> precedenceJobs = C.newList();

    _Job(AppJobManager manager) {
        E.NPE(manager);
        this.manager = manager;
        id = uuid();
    }

    _Job(String id, AppJobManager manager) {
        E.NPE(manager);
        this.id = (null == id) ? uuid() : id;
        this.manager = manager;
    }

    _Job(String id, AppJobManager manager, _.Func0<?> worker) {
        E.NPE(worker, manager);
        this.id = id;
        this.manager = manager;
        this.worker = worker;
    }

    _Job(String id, AppJobManager manager, _.Func0<?> worker, boolean oneTime) {
        E.NPE(worker, manager);
        this.id = id;
        this.manager = manager;
        this.worker = worker;
        this.oneTime = oneTime;
    }

    @Override
    protected void releaseResources() {
        worker = null;
        manager = null;
        parallelJobs.clear();
        followingJobs.clear();
        precedenceJobs.clear();
        super.releaseResources();
    }

    _Job setOneTime() {
        oneTime = true;
        return this;
    }

    boolean isOneTime() {
        return oneTime;
    }

    final String id() {
        return id;
    }

    final void trigger(JobTrigger trigger) {
        E.NPE(trigger);
        this.trigger = trigger;
    }

    final _Job addParallelJob(_Job thatJob) {
        parallelJobs.add(thatJob);
        if (isOneTime()) {
            thatJob.setOneTime();
        }
        return this;
    }

    final _Job addFollowingJob(_Job thatJob) {
        followingJobs.add(thatJob);
        if (isOneTime()) {
            thatJob.setOneTime();
        }
        return this;
    }

    final _Job addPrecedenceJob(_Job thatJob) {
        precedenceJobs.add(thatJob);
        if (isOneTime()) {
            thatJob.setOneTime();
        }
        return this;
    }

    @Override
    public void run() {
        invokeParallelJobs();
        runPrecedenceJobs();
        doJob();
        if (isOneTime()) {
            manager().removeJob(this);
        }
        runFollowingJobs();
    }

    protected void doJob() {
        try {
            if (null != worker) {
                worker.apply();
            }
        } finally {
            scheduleNextInvocation();
        }
    }

    private void runPrecedenceJobs() {
        for (_Job precedence : precedenceJobs) {
            precedence.run();
        }
    }

    private void runFollowingJobs() {
        for (_Job post : followingJobs) {
            post.run();
        }
    }

    private void invokeParallelJobs() {
        for (_Job alongWith : parallelJobs) {
            manager.now(alongWith);
        }
    }

    protected final AppJobManager manager() {
        return manager;
    }

    protected void scheduleNextInvocation() {
        if (null != trigger) trigger.scheduleFollowingCalls(manager(), this);
    }

    private static _Job of(String jobId, final Runnable runnable, AppJobManager manager, boolean oneTime) {
        return new _Job(jobId, manager, new _.F0() {
            @Override
            public Object apply() throws NotAppliedException, _.Break {
                runnable.run();
                return null;
            }
        }, oneTime);
    }

    private static _Job of(final Runnable runnable, AppJobManager manager, boolean oneTime) {
        return of(S.uuid(), runnable, manager, oneTime);
    }

    static _Job oneTime(final Runnable runnable, AppJobManager manager) {
        return of(runnable, manager, true);
    }

    static _Job oneTime(String jobId, final Runnable runnable, AppJobManager manager) {
        return of(jobId, runnable, manager, true);
    }

    static _Job multipleTimes(final Runnable runnable, AppJobManager manager) {
        return of(runnable, manager, false);
    }

    static _Job multipleTimes(String jobId, final Runnable runnable, AppJobManager manager) {
        return of(jobId, runnable, manager, false);
    }

    private static String uuid() {
        return UUID.randomUUID().toString();
    }
}
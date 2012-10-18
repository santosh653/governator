package com.netflix.governator.lifecycle.warmup;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.netflix.governator.annotations.WarmUp;
import com.netflix.governator.lifecycle.LifecycleManager;
import com.netflix.governator.lifecycle.LifecycleMethods;
import com.netflix.governator.lifecycle.LifecycleState;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class WarmUpManager
{
    private final LifecycleManager lifecycleManager;
    private final SetStateMixin setState;
    private final int nThreads;

    // guarded by synchronization
    private long updateCount = 0;

    public WarmUpManager(LifecycleManager lifecycleManager, SetStateMixin setState, int nThreads)
    {
        this.lifecycleManager = lifecycleManager;
        this.setState = setState;
        this.nThreads = nThreads;
    }

    public void warmUp(long maxMs) throws InterruptedException
    {
        long                startMs = System.currentTimeMillis();
        DependencyNode      root = lifecycleManager.getDAGManager().buildTree();
        ExecutorService     service = Executors.newFixedThreadPool(nThreads, new ThreadFactoryBuilder().setDaemon(true).setNameFormat("GovernatorWarmUpManager-%d").build());
        try
        {
            for(;;)
            {
                long        elapsed = System.currentTimeMillis() - startMs;
                long        thisWait = maxMs - elapsed;
                if ( thisWait <= 0 )
                {
                    break;  // TODO
                }

                long        localUpdateCount = getUpdateCount();
                if ( internalIterator(root, service) )
                {
                    break;
                }
                waitForUpdateCountChange(localUpdateCount, startMs, maxMs);
            }
        }
        finally
        {
            service.shutdownNow();
        }
    }

    private boolean internalIterator(DependencyNode node, ExecutorService service)
    {
        boolean     needsWarmup = (getNodeState(node) == LifecycleState.PRE_WARMING_UP);
        boolean     isDone = !needsWarmup;

        if ( needsWarmup && isReadyToWarmUp(node) )
        {
            Object                  obj = lifecycleManager.getDAGManager().getObject(node.getKey());
            if ( obj == null )
            {
                // TODO
            }
            LifecycleMethods        lifecycleMethods = lifecycleManager.getDAGManager().getLifecycleMethods(node.getKey());
            warmupObject(service, obj, lifecycleMethods);
        }

        for ( DependencyNode child : node.getChildren() )
        {
            if ( !internalIterator(child, service) )
            {
                isDone = false;
            }
        }
        return isDone;
    }

    private boolean isReadyToWarmUp(DependencyNode node)
    {
        for ( DependencyNode child : node.getChildren() )
        {
            LifecycleState childState = getNodeState(child);
            if ( (childState == LifecycleState.PRE_WARMING_UP) || (childState == LifecycleState.WARMING_UP) )
            {
                // The original node has a direct child that is not warmed up
                return false;
            }
            if ( !isReadyToWarmUp(child) )
            {
                // The original node has an indirect child that is not warmed up
                return false;
            }
        }
        // Since no direct or indirect children are not warm,
        // this node is ready to warm up.
        return true;
    }

    private void warmupObject(ExecutorService service, final Object obj, LifecycleMethods lifecycleMethods)
    {
        final Collection<Method>  methods = (lifecycleMethods != null) ? lifecycleMethods.methodsFor(WarmUp.class) : null;
        if ( (methods == null) || (methods.size() == 0) )
        {
            changeState(obj, LifecycleState.ACTIVE);
            return;
        }

        // TODO - enforce a max time for object to warmUp

        setState.setState(obj, LifecycleState.WARMING_UP);
        service.submit
        (
            new Runnable()
            {
                @Override
                public void run()
                {
                    try
                    {
                        for ( Method method : methods )
                        {
                            method.invoke(obj);
                        }
                        changeState(obj, LifecycleState.ACTIVE);
                    }
                    catch ( Throwable e )
                    {
                        // TODO
                        changeState(obj, LifecycleState.ERROR);
                    }
                }
            }
        );
    }

    private synchronized void changeState(Object obj, LifecycleState state)
    {
        setState.setState(obj, state);
        ++updateCount;
        notifyAll();
    }

    private synchronized long getUpdateCount()
    {
        return updateCount;
    }

    private synchronized void waitForUpdateCountChange(long localUpdateCount, long startMs, long maxMs) throws InterruptedException
    {
        while ( localUpdateCount == updateCount )
        {
            long        elapsed = System.currentTimeMillis() - startMs;
            long        thisWait = maxMs - elapsed;
            if ( thisWait <= 0 )
            {
                break;
            }
            wait(thisWait);
        }
    }

    private LifecycleState getNodeState(DependencyNode node)
    {
        Object obj = lifecycleManager.getDAGManager().getObject(node.getKey());
        return (obj != null) ? lifecycleManager.getState(obj) : LifecycleState.LATENT;
    }
}

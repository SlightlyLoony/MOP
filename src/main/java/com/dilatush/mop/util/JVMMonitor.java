package com.dilatush.mop.util;

import com.dilatush.mop.Message;

import static com.dilatush.util.General.isNotNull;

/**
 * Implements a simple monitor for the health of a JVM.
 *
 * @author Tom Dilatush  tom@dilatush.com
 */
public class JVMMonitor {

    private long usedBytes;            // memory allocated and actually being used, both code and data...
    private long freeBytes;            // memory allocated by not currently in use...
    private long allocatedBytes;       // memory allocated (sum of used and free memory)...
    private long availableBytes;       // memory that is currently unallocated, but which the JVM will try to allocate if needed...
    private long maxBytes;             // memory that the JVM could use (sum of allocated and available)...
    private int  cpus;                 // number of CPUs available to the JVM...
    private int  totalThreads;         // total number of active threads in the JVM...
    private int  newThreads;           // new threads that have not yet started...
    private int  runningThreads;       // running threads...
    private int  blockedThreads;       // threads blocked on a monitor...
    private int  waitingThreads;       // threads waiting for another thread...
    private int  timedWaitingThreads;  // threads waiting for another thread, up to a certain time...
    private int  terminatedThreads;    // threads that have terminated...


    /**
     * Runs this monitor and fills the specified message with the results.
     *
     * @param _message the message to be filled.
     */
    public void fill( final Message _message ) {

        // first run the monitor...
        run();

        // then fill in all the results...
        _message.putDotted( "monitor.jvm.usedBytes",            usedBytes           );
        _message.putDotted( "monitor.jvm.freeBytes",            freeBytes           );
        _message.putDotted( "monitor.jvm.allocatedBytes",       allocatedBytes      );
        _message.putDotted( "monitor.jvm.availableBytes",       availableBytes      );
        _message.putDotted( "monitor.jvm.maxBytes",             maxBytes            );
        _message.putDotted( "monitor.jvm.cpus",                 cpus                );
        _message.putDotted( "monitor.jvm.totalThreads",         totalThreads        );
        _message.putDotted( "monitor.jvm.newThreads",           newThreads          );
        _message.putDotted( "monitor.jvm.runningThreads",       runningThreads      );
        _message.putDotted( "monitor.jvm.blockedThreads",       blockedThreads      );
        _message.putDotted( "monitor.jvm.waitingThreads",       waitingThreads      );
        _message.putDotted( "monitor.jvm.timedWaitingThreads",  timedWaitingThreads );
        _message.putDotted( "monitor.jvm.terminatedThreads",    terminatedThreads   );
    }


    public void run() {

        // first we investigate memory, running the gc first to eliminate dead objects...
        Runtime runtime = Runtime.getRuntime();
        runtime.gc();
        long free = runtime.freeMemory();
        long max = runtime.maxMemory();
        long total = runtime.totalMemory();

        usedBytes      = total - free;
        freeBytes      = free;
        allocatedBytes = total;
        availableBytes = max - total;
        maxBytes       = max;

        // now we see how many CPUs we can use...
        cpus = runtime.availableProcessors();

        // now we see how many threads we're running and what state they're in...
        totalThreads = newThreads = runningThreads = blockedThreads = waitingThreads = timedWaitingThreads = terminatedThreads = 0;
        ThreadGroup group = Thread.currentThread().getThreadGroup();
        while( isNotNull( group.getParent() ) )
            group = group.getParent();
        Thread[] threads = new Thread[ group.activeCount() * 4 / 3 ];
        int count = group.enumerate( threads );
        for( int i =  0; i < count; i++ ) {
            Thread t = threads[i];
            totalThreads++;
            switch( t.getState() ) {
                case NEW:           newThreads++;          break;
                case BLOCKED:       blockedThreads++;      break;
                case WAITING:       waitingThreads++;      break;
                case RUNNABLE:      runningThreads++;      break;
                case TERMINATED:    terminatedThreads++;   break;
                case TIMED_WAITING: timedWaitingThreads++; break;
            }
        }
    }
}

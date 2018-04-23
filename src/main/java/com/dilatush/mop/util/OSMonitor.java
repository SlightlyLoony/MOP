package com.dilatush.mop.util;

import com.dilatush.mop.Message;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.dilatush.mop.util.OS.LINUX;
import static com.dilatush.mop.util.OS.OSX;
import static com.dilatush.util.Strings.isEmpty;
import static java.lang.Thread.sleep;

/**
 * Implements some simple operating system monitoring for Linux and OSX.
 *
 * @author Tom Dilatush  tom@dilatush.com
 */
public class OSMonitor {

    private static final Executor osInfoEx       = new Executor( "uname -mnrs" );
    private static final Executor osxMemInfoEx1   = new Executor( "sysctl hw.memsize" );
    private static final Executor osxMemInfoEx2   = new Executor( "vm_stat" );
    private static final Executor linuxMemInfoEx = new Executor( "free -b" );
    private static final Executor osxCPUInfoEx   = new Executor( "iostat -C" );
    private static final Executor linuxCPUInfoEx = new Executor( "cat /proc/stat" );

    private static final Pattern osInfoPat
            = Pattern.compile( "(\\S+)\\s+(\\S+)\\s+(\\S+)\\s+(\\S+)\\s+" );
    private static final Pattern osxMemInfoPat
            = Pattern.compile( ".*\\.memsize:\\s+(\\d+).*page size of (\\d+).* active:\\s+(\\d+).* wired down:\\s++(\\d+).*", Pattern.DOTALL );
    private static final Pattern linuxMemInfoPat
            = Pattern.compile( ".*Mem:\\s+(\\d+).*buffers/cache:\\s+(\\d+).*Swap:\\s+(\\d+).*", Pattern.DOTALL );
    private static final Pattern osxCPUInfoPat
            = Pattern.compile( ".*(?:(?:[\\d.]+)\\s+){6}([\\d.]+)\\s+.*", Pattern.DOTALL );
    private static final Pattern linuxCPUInfoPat
            = Pattern.compile( "cpu\\s+(\\d+)\\s+(\\d+)\\s+(\\d+)\\s+(\\d+)\\s+(\\d+)\\s+(\\d+)\\s+(\\d+)\\s+.*", Pattern.DOTALL );

    private boolean valid;
    private OS os;
    private String kernelName;
    private String hostName;
    private String kernelVersion;
    private String architecture;
    private String errorMessage;
    private long totalMemory;
    private long usedMemory;
    private long freeMemory;
    private float cpuBusyPct;
    private float cpuIdlePct;


    /**
     * Runs this monitor and fills the specified message with the results.
     *
     * @param _message the message to be filled.
     */
    public void fill( final Message _message ) {

        // first run the monitor...
        run();

        // no matter what, fill in the validity...
        _message.putDotted( "monitor.os.valid", valid );

        // if the results were not valid, fill in the error message and leave...
        if( !valid ) {
            _message.putDotted( "monitor.os.errorMessage", errorMessage );
            return;
        }

        // otherwise, fill in everything we've learned...
        _message.putDotted( "monitor.os.os",            (os == LINUX) ? "Linux" : "OSX" );
        _message.putDotted( "monitor.os.hostName",      hostName                        );
        _message.putDotted( "monitor.os.kernelName",    kernelName                      );
        _message.putDotted( "monitor.os.kernelVersion", kernelVersion                   );
        _message.putDotted( "monitor.os.architecture",  architecture                    );
        _message.putDotted( "monitor.os.totalMemory",   totalMemory                     );
        _message.putDotted( "monitor.os.usedMemory",    usedMemory                      );
        _message.putDotted( "monitor.os.freeMemory",    freeMemory                      );
        _message.putDotted( "monitor.os.cpuBusyPct",    cpuBusyPct                      );
        _message.putDotted( "monitor.os.cpuIdlePct",    cpuIdlePct                      );
    }


    /**
     * Runs this monitor, executing operating system commands to find its current state.  After running, the various getters of this class return
     * the results.
     */
    public void run() {

        // mark results invalid until we've successfully completed...
        valid = false;
        errorMessage = null;

        // first find out what kind of OS we're running on...
        String result = osInfoEx.run();
        if( isEmpty( result ) ) {
            errorMessage = "Command uname failed";
            return;
        }
        Matcher mat = osInfoPat.matcher( result );
        if( mat.matches() ) {
            kernelName = mat.group( 1 );
            hostName = mat.group( 2 );
            kernelVersion = mat.group( 3 );
            architecture = mat.group( 4 ) ;
            if( "Linux".equals( kernelName ) )       os = LINUX;
            else if( "Darwin".equals( kernelName ) ) os = OSX;
            else {
                errorMessage = "Unrecognized kernel name: " + kernelName;
                return;
            }
        }
        else {
            errorMessage = "Unrecognized uname output: " + result;
            return;
        }

        // then do the rest of our investigation in an OS-dependent way...
        if( os == OSX )        runOSX();
        else /* os == LINUX */ runLinux();
    }


    private void runOSX() {

        // find our total memory, free, and used...
        String result = osxMemInfoEx1.run() + osxMemInfoEx2.run();
        if( isEmpty( result ) ) {
            errorMessage = "Command sysctl or vm_stat failed";
            return;
        }
        Matcher mat = osxMemInfoPat.matcher( result );
        if( mat.matches() ) {
            int pageSize = Integer.parseInt( mat.group( 2 ) );
            long active = pageSize * Long.parseLong( mat.group( 3 ) );
            long wired = pageSize * Long.parseLong( mat.group( 4 ) );
            totalMemory = Long.parseLong( mat.group( 1 ) );
            usedMemory = active + wired;
            freeMemory = totalMemory - usedMemory;
        }
        else {
            errorMessage = "Unrecognized sysctl or vm_stat output: " + result;
            return;
        }

        // find our cpu busy and idle percent (for OSX, it's the last minute average)...
        result = osxCPUInfoEx.run();
        if( isEmpty( result ) ) {
            errorMessage = "Command iostat failed";
            return;
        }
        mat = osxCPUInfoPat.matcher( result );
        if( mat.matches() ) {
            cpuBusyPct = Float.parseFloat( mat.group( 1 ) );
            cpuIdlePct = 100.0f - cpuBusyPct;
        }
        else {
            errorMessage = "Unrecognized iostat output: " + result;
            return;
        }

        valid = true;
    }


    private void runLinux() {

        // find our total memory, free, and used...
        String result = linuxMemInfoEx.run();
        if( isEmpty( result ) ) {
            errorMessage = "Command free failed";
            return;
        }
        Matcher mat = linuxMemInfoPat.matcher( result );
        if( mat.matches() ) {
            long total = Long.parseLong( mat.group( 1 ) );
            long used = Long.parseLong( mat.group( 2 ) );
            long swap = Long.parseLong( mat.group( 3 ) );
            totalMemory = total + swap;
            usedMemory = used + swap;
            freeMemory = totalMemory - usedMemory;
        }
        else {
            errorMessage = "Unrecognized free output: " + result;
            return;
        }

        // find our cpu busy and idle percent (for Linux, we sample it twice, a second apart )...
        long[] start = linuxCPU();
        if( start == null ) return;
        try { sleep( 1000 ); } catch( InterruptedException _e ) { /* do nothing... */ }
        long[] stop = linuxCPU();
        if( stop == null ) return;
        long total = stop[0] - start[0];
        long idle  = stop[1] - start[1];
        cpuIdlePct = 100.0f * idle / total;
        cpuBusyPct = 100.0f - cpuIdlePct;

        valid = true;
    }


    private long[] linuxCPU() {
        String result = linuxCPUInfoEx.run();
        if( isEmpty( result ) ) {
            errorMessage = "Command cat failed";
            return null;
        }
        Matcher mat = linuxCPUInfoPat.matcher( result );
        if( mat.matches() ) {
            long user    = Long.parseLong( mat.group( 1 ) );
            long nice    = Long.parseLong( mat.group( 2 ) );
            long system  = Long.parseLong( mat.group( 3 ) );
            long idle    = Long.parseLong( mat.group( 4 ) );
            long iowait  = Long.parseLong( mat.group( 5 ) );
            long irq     = Long.parseLong( mat.group( 6 ) );
            long softirq = Long.parseLong( mat.group( 7 ) );
            long[] times = new long[2];
            times[0] = user + nice + system + idle + iowait + irq + softirq;
            times[1] = idle;
            return times;
        }
        else {
            errorMessage = "Unrecognized cat output: " + result;
            return null;
        }
    }
}

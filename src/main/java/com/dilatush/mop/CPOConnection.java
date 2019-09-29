package com.dilatush.mop;

import com.dilatush.mop.cpo.Authenticator;
import com.dilatush.util.Base64;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.TimerTask;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

import static com.dilatush.util.General.isNotNull;
import static com.dilatush.util.General.isNull;
import static java.lang.Thread.sleep;

/**
 * @author Tom Dilatush  tom@dilatush.com
 */
public class CPOConnection {

    private static final Logger LOGGER                 = Logger.getLogger( new Object(){}.getClass().getEnclosingClass().getCanonicalName());

    private static final int CPO_CONNECT_TIMEOUT_MS   = 5000;
    private static final int MAX_OUTGOING_QUEUE_MSGS  = 100;
    private static final int DEFAULT_MAX_MESSAGE_SIZE = 300;
    private static final int PING_CHECK_INTERVAL_MS   = 100;
    private static final int READ_BUFFER_SIZE         = 1024;

    private final PostOffice                  po;
    private final String                      cpoHost;
    private final int                         cpoPort;
    private final byte[]                      secret;
    private final LinkedBlockingDeque<byte[]> outgoing;
    private final Object                      writeSync;

    private volatile Reader          reader;
    private volatile Writer          writer;
    private volatile Shuttler        shuttler;
    private volatile MessageDeframer deframer;
    private volatile byte[]          lastWritten;


    private volatile Socket    socket;
    private volatile long      pingIntervalMS;
    private volatile boolean   connected;
    private volatile Instant   lastConnectTime;
    private volatile boolean   reconnectScheduled;
    private AtomicInteger      maxMessageSize;
    private AtomicLong         timeSinceLastPingMS;
    private AtomicLong         connections;
    private AtomicLong         rxMessages;
    private AtomicLong         txMessages;
    private AtomicLong         rxBytes;
    private AtomicLong         txBytes;


    /**
     * Creates a new instance of this class, associated with the specified post office and using the central post office at the specified host and
     * port.  The specified secret will be used for authentication and encryption of this connection.
     *
     * @param _po the post office this instance is associated with.
     * @param _cpoHost the host (or dotted-form IP) of the central post office to connect to.
     * @param _cpoPort the TCP port of the central post office to connect to.
     * @param _secret the secret to use for authentication and encryption of communications with the central post office.
     */
    public CPOConnection( final PostOffice _po, final String _cpoHost, final int _cpoPort, final byte[] _secret ) {
        po             = _po;
        cpoHost        = _cpoHost;
        cpoPort        = _cpoPort;
        secret         = _secret;

        connections         = new AtomicLong( 0 );
        rxMessages          = new AtomicLong( 0 );
        txMessages          = new AtomicLong( 0 );
        rxBytes             = new AtomicLong( 0 );
        txBytes             = new AtomicLong( 0 );
        timeSinceLastPingMS = new AtomicLong( 0 );
        maxMessageSize      = new AtomicInteger( DEFAULT_MAX_MESSAGE_SIZE );

        connected      = false;
        writeSync      = new Object();
        outgoing       = new LinkedBlockingDeque<>( MAX_OUTGOING_QUEUE_MSGS );
        shuttler       = new Shuttler();

        // schedule a periodic ping check...
        po.timer.schedule( new PingCheck(), PING_CHECK_INTERVAL_MS, PING_CHECK_INTERVAL_MS );

        // schedule a connection for real soon now...
        po.timer.schedule( new Connector(), 1 );
    }


    /**
     * Shuts down this connection instance, including all subordinate threads.
     */
    public void shutdown() {

        // close our socket...
        killSocket();

        // kill our shuttler...
        shuttler.interrupt();

        // kill our reader...
        killReader();

        // kill our writer...
        killWriter();
    }


    /**
     * Returns true if this instance has successfully connected to the central post office.
     *
     * @return true if this instance has successfully connected to the central post office.
     */
    public boolean isConnected() {
        return connected;
    }


    public byte[] getSecret() {
        return secret;
    }


    /**
     * Deliver the specified message to the central post office.
     *
     * @param _message the message to deliver.
     */
    public void deliver( final Message _message ) {

        byte[] serialized = _message.serialize();
        txBytes.addAndGet( serialized.length );
        txMessages.incrementAndGet();

        // TODO: how should we handle overflow?  Right now we're just throwing away messages if we overflow...
        outgoing.offerFirst(  serialized );
    }


    public long getConnections() {
        return connections.get();
    }


    public long getRxMessages() {
        return rxMessages.get();
    }


    public long getTxMessages() {
        return txMessages.get();
    }


    public long getRxBytes() {
        return rxBytes.get();
    }


    public long getTxBytes() {
        return txBytes.get();
    }


    public Instant getLastConnectTime() {
        return lastConnectTime;
    }


    private void deliverNext( final Message _message ) {

        synchronized( writeSync ) {

            // TODO: how should we handle overflow?  Right now we're just throwing away messages if we overflow...
            // first we take the last thing written and stuff it back for a resend...
            if( isNotNull( (Object) lastWritten ) )
                outgoing.offerLast( lastWritten );

            // then we stuff the given message in for immediate sending...
            outgoing.offerLast( _message.serialize() );
        }
    }


    private void getWriteBytes() throws InterruptedException {

        byte[] newBytes = null;
        while( isNull( (Object) newBytes ) ) {

            synchronized( writeSync ) {

                newBytes = outgoing.pollLast();
                if( isNotNull( (Object) newBytes ) )
                    lastWritten = newBytes;
            }

            // if we didn't get anything, wait briefly before we check again...
            if( isNull( (Object) newBytes ) ) sleep( 5 );
        }
    }


    // We call this if any problem occurs while reading or writing data.
    private synchronized void handleRunningProblem( final String _msg, final Throwable _throwable ) {

        // if we're shutting down, then get out of here as we're not going to reconnect...
        if( po.shutdown ) return;

        // if we've already scheduled a reconnection, ignore it...
        if( reconnectScheduled ) return;
        reconnectScheduled = true;

        LOGGER.log( Level.INFO, _msg, _throwable );
        connected = false;

        // make sure the socket really is closed and nulled...
        if( isNotNull( socket ) ) {
            if( !socket.isClosed() ) {
                try {
                    LOGGER.finest( "Closing socket" );
                    socket.close();
                }
                catch( IOException _e ) {
                    // we ignore this, as there's naught we could do anyway...
                }
            }
            socket = null;
        }

        // now we make sure that the reader and writer have terminated...
        if( isNotNull( reader ) )
            if( isNotNull( reader.readStream ) )
                try { reader.readStream.close();  } catch( IOException _e ) { /* ignore any errors */ }
        if( isNotNull( writer ) ) {
            writer.interrupt();    // because Writer is likely in getWriteBytes(), looping to look for stuff to write...
            if( isNotNull( writer.writeStream ) )
                try {
                    writer.writeStream.close();
                }
                catch( IOException _e ) { /* ignore any errors */ }
        }
        reader = null;
        writer = null;

        // schedule an attempt to connect in a half second...
        LOGGER.info( "Scheduling reconnection in 500 ms" );
        po.timer.schedule( new Connector(), 500 );
    }


    /* package */ void killSocket() {
        try {
            socket.close();
        }
        catch( IOException _e ) {
            // ignore...
        }
    }


    /* package */ void readTestException() {
        reader.testException = true;
    }


    /* package */ void writeTestException() {
        writer.testException = true;
    }


    /* package */ void killWriter() {
        writer.interrupt();
    }


    /* package */ void killReader() {
        try {
            reader.readStream.close();
        }
        catch( IOException _e ) {
            // ignore...
        }
    }


    private class Shuttler extends Thread {

        private Shuttler() {
            setName( po.name + " Shuttler" );
            setDaemon( true );
            start();
        }


        public void run() {

            while( true ) {

                try {
                    Message msg = po.getCpoMailbox().take();
                    deliver( msg );
                }
                catch( InterruptedException _e ) {
                    break;
                }
            }
        }
    }


    private class Connector extends TimerTask {

        public void run() {

            try {

                // loop here until we're successfully connected...
                while( isNull( socket ) || !socket.isConnected() || socket.isClosed() || socket.isInputShutdown() || socket.isOutputShutdown() ) {

                    try {

                        // if we don't already have an open connection, try to make one...
                        if( isNull( socket ) || socket.isClosed() || !socket.isConnected() || socket.isInputShutdown() || socket.isOutputShutdown() ) {

                            // create our new socket and attempt to connect...
                            SocketAddress cpoAddress = new InetSocketAddress( InetAddress.getByName( cpoHost ), cpoPort );
                            socket = new Socket();
                            socket.connect( cpoAddress, CPO_CONNECT_TIMEOUT_MS );
                        }
                    }
                    catch( IOException _e ) {

                        LOGGER.log( Level.SEVERE, "Problem when attempting to connect; will try again", _e );

                        // wait a bit and try again...
                        sleep( 500 );
                    }
                }

                // we only get here if we successfully connected by TCP...

                // set up our deframer...
                deframer = new MessageDeframer( maxMessageSize );

                // start up our reader/writer threads...
                reader = new Reader();
                writer = new Writer();

                // send our connect message...
                String from = po.prefix + "po";
                String type = (connections.get() == 0) ? "manage.connect" : "manage.reconnect";
                Message connectMsg = new Message( from, "central.po", type, po.getNextID(), null, false );
                Authenticator auth = new Authenticator( secret, po.name, connectMsg.id );
                connectMsg.put( "authenticator", Base64.encode( auth.getAuthenticator() ) );
                LOGGER.finest( "Sending: " + connectMsg.toString() );
                deliverNext( connectMsg );
                reconnectScheduled = false;

                LOGGER.finest( "TCP connected to CPO" );
            }
            catch( InterruptedException _e ) {
                // naught to do; just leave...
            }

            // this is essential - if we don't cancel, then this instance will run again!
            cancel();
        }
    }


    private class Reader extends Thread {

        private InputStream      readStream;
        private byte[]           buffer;
        private volatile boolean testException;


        private Reader() {
            setName( po.name + " CPO Connection Reader" );
            setDaemon( true );
            buffer = new byte[READ_BUFFER_SIZE];  // allocate a buffer that can read enough bytes for most messages...
            start();
        }


        public void run() {

            try {

                readStream = socket.getInputStream();

                while( true ) {

                    // test harness...
                    if( testException ) {
                        testException = false;
                        throw new IOException( "Test exception" );
                    }

                    // block until we read some bytes...
                    int bytesRead = readStream.read( buffer );

                    // if we reached the end-of-stream (input stream closed), handle it as an error...
                    if( bytesRead < 0 ) {
                        handleRunningProblem( "End of stream detected", null );
                        break;
                    }

                    // if we actually got some bytes, so process them...
                    if( bytesRead > 0 ) {

                        // keep track of how many we've added to the deframer...
                        int bytesAdded = 0;

                        // track the total bytes read...
                        rxBytes.addAndGet( bytesRead );

                        // loop so long as we still have bytes to add to the deframer...
                        while( bytesAdded < bytesRead ) {

                            // first add bytes to our deframer...
                            bytesAdded += deframer.addBytes( buffer, 0, bytesRead );

                            // send any complete messages...
                            while( true ) {

                                // get a frame, if we got a complete one...
                                byte[] frame = deframer.getFrame();
                                if( isNull( (Object) frame ) ) break;  // no more complete frames...

                                // decode the received message...
                                // TODO: need error handling here, in case the JSON is invalid...
                                Message rxMsg = new Message( new String( frame, StandardCharsets.UTF_8 ) );
                                rxMessages.incrementAndGet();

                                // if this is a connection management message, handle it here...
                                if( (po.name + ".po").equals( rxMsg.to ) ) {

                                    switch( rxMsg.type ) {

                                        case "manage.connect":   handleConnect( rxMsg, false ); break;
                                        case "manage.reconnect": handleConnect( rxMsg, true  ); break;
                                        case "manage.ping":      handlePing();                             break;

                                        default:
                                            po.send( rxMsg );  // other management messages get handled in the post office...
                                    }
                                }

                                // otherwise, just pass the message to our post office for routing...
                                else {
                                    if( rxMsg.isEncrypted() )
                                        rxMsg.decrypt( secret );
                                    po.send( rxMsg );
                                }
                            }
                        }
                    }
                }
            }
            catch( IOException _e ) {
                handleRunningProblem( "Problem while reading", _e );
            }
        }


        private void handlePing() {

            LOGGER.finest( "Received ping" );
            timeSinceLastPingMS.set( 0 );

            // send a pong...
            LOGGER.finest( "Sending pong" );
            Message message = new Message( po.name + ".po", "central.po", "manage.pong", po.getNextID(), null, false );
            deliver( message );
        }


        private void handleConnect( final Message _message, final boolean isReconnect ) {
            maxMessageSize.set( _message.optInt( "maxMessageSize", maxMessageSize.get() ) );
            pingIntervalMS = _message.getLong( "pingIntervalMS" );
            LOGGER.info( po.name + " connected to cpo with max message size: " + maxMessageSize.get() + " bytes, and "
                    + pingIntervalMS + " ms ping interval" );
            deframer.resize( maxMessageSize );
            timeSinceLastPingMS.set( 0 );  // we're starting over on the ping time check...
            connected = true;
            connections.incrementAndGet();
            lastConnectTime = Instant.now();

            // if this is an initial connection, refresh any subscriptions we've made to foreign sources...
            if( !isReconnect ) po.handleSubscriptionRefresh();
        }
    }


    private class PingCheck extends TimerTask {

        /**
         * If it's been too long since the most recent ping was received, reconnect.
         */
        @Override
        public void run() {

            // if we're not connected, don't check...
            if( !connected ) return;

            long pingTime = timeSinceLastPingMS.addAndGet( PING_CHECK_INTERVAL_MS );

            // if it's been 150% of the agreed ping interval,
            if( pingTime >= ((pingIntervalMS << 1) + pingIntervalMS) >> 1 ) {
                handleRunningProblem( "Failed to receive ping in time", null );
            }
        }
    }


    private class Writer extends Thread {

        private volatile OutputStream     writeStream;
        private volatile boolean          testException;


        private Writer() {
            setName( po.name + " CPO Connection Writer" );
            setDaemon( true );
            start();
        }


        public void run() {

            try {
                writeStream = socket.getOutputStream();

                while( true ) {

                    try {
                        // get some bytes to send out...
                        getWriteBytes();
                        LOGGER.finest( "Writing " + new String( lastWritten, StandardCharsets.UTF_8 ) + " to output stream " + writeStream.toString() );

                        // test harness...
                        if( testException ) {
                            testException = false;
                            throw new IOException( "Test exception" );
                        }

                        // send them...
                        writeStream.write( lastWritten );
                    }
                    catch( InterruptedException | IOException _e ) {
                        handleRunningProblem( "Problem while writing", _e );
                        break;
                    }
                }
            }
            catch( IOException _e ) {
                handleRunningProblem( "Problem while getting or writing to output stream", _e );
            }
        }
    }
}

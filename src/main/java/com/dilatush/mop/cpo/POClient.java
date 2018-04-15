package com.dilatush.mop.cpo;

import com.dilatush.mop.Message;
import com.dilatush.util.Base64;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.concurrent.BlockingDeque;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.atomic.AtomicLong;

import static com.dilatush.util.General.isNotNull;
import static com.dilatush.util.General.isNull;

/**
 * @author Tom Dilatush  tom@dilatush.com
 */
/* package */ class POClient {

    private static final Logger LOG = LogManager.getLogger();

    private static final int OUTGOING_QUEUE_SIZE = 100;  // number of messages that can be held in outgoing queue...

    /* package */ final String                    name;
    /* package */ final String                    secretBase64;
    /* package */ final byte[]                    secretBytes;
    /* package */ final BlockingDeque<ByteBuffer> outgoing;

    private final Object                writeSync = new Object();

    /* package */ volatile POConnection connection;    // post office connection currently associated with this post office (null if none)...
    /* package */ AtomicLong            connections;   // number of times we've connected to this client po...
    /* package */ AtomicLong            rxMessages;
    /* package */ AtomicLong            txMessages;
    /* package */ AtomicLong            rxBytes;
    /* package */ AtomicLong            txBytes;
    /* package */ volatile ByteBuffer   currentWrite;  // the buffer currently being written...
    /* package */ Instant               lastConnectTime;


    /* package */ POClient( final String _name, final String _secretBase64 ) {
        name         = _name;
        secretBase64 = _secretBase64;
        secretBytes  = Base64.decodeBytes( secretBase64 );
        connections  = new AtomicLong( 0 );
        rxBytes      = new AtomicLong( 0 );
        txBytes      = new AtomicLong( 0 );
        rxMessages   = new AtomicLong( 0 );
        txMessages   = new AtomicLong( 0 );
        outgoing     = new LinkedBlockingDeque<>( OUTGOING_QUEUE_SIZE );
        currentWrite = null;
    }


    /**
     * Deliver the specified message to this client post office.
     *
     * @param _message the message to route to this client post office.
     */
    /* package */ void deliver( final Message _message ) {

        // if we're called without a message, log the fact and do nothing...
        if( isNull( _message ) ) {
            LOG.error( "Attempt made to deliver null message" );
            return;
        }

        // serialize our message and add it to our outgoing buffer...
        byte[] serializedBytes = _message.serialize();
        txBytes.addAndGet( serializedBytes.length );
        txMessages.incrementAndGet();
        ByteBuffer serialized = ByteBuffer.wrap( serializedBytes );
        outgoing.offerFirst( serialized );   // TODO: if the buffer is full, how should we handle it?  Now it's just dumping overflows...

        // if we have an associated connection, update our selector key to show that we have bytes that need to be written...
        if( isNotNull( connection ) ) connection.showWriteInterest();
    }


    /**
     * Inserts the given package in the outgoing message queue such that it will be the next one to be transmitted.  If there was a partially
     * transmitted message present, it is re-inserted so that it will be the second one transmitted.  This method is intended for use by a
     * connect or reconnect operation.
     *
     * @param _message the message to deliver.
     */
    /* package */ void deliverNext( final Message _message ) {

        // sanity check...
        if( isNull( _message ) ) throw new IllegalArgumentException( "Attempt to deliverNext with a null message" );

        // synchronize with any attempts to get new data to write...
        synchronized( writeSync ) {

            // if we have a potentially partially sent message, reinsert the complete message for resending...
            if( isNotNull( currentWrite ) ) {
                ByteBuffer serialized = ByteBuffer.wrap( currentWrite.array() );
                outgoing.offerLast( serialized );
                currentWrite = null;
            }

            // now insert the specified message...
            ByteBuffer serialized = ByteBuffer.wrap( _message.serialize() );
            outgoing.offerFirst( serialized );   // TODO: if the buffer is full, how should we handle it?  Now it's just dumping overflows...

            // if we have an associated connection, update our selector key to show that we have bytes that need to be written...
            if( isNotNull( connection ) ) connection.showWriteInterest();
        }
    }


    /**
     * Returns a buffer containing data to be transmitted, or {@code null} if there is none.
     *
     * @return the buffer with data to write, or {@code null} if there is none.
     */
    /* package */ ByteBuffer getWriteData() {

        // synchronize with any attempts to deliverNext()...
        synchronized( writeSync ) {

            // if we have a buffer being written, and it has more data to write, that's our source...
            if( isNotNull( currentWrite ) && (currentWrite.remaining() > 0) )
                return currentWrite;

            // otherwise, let's see if there is more data queued up...
            currentWrite = outgoing.pollLast();

            // if we have no more data, then clear our write interest...
            if( isNull( currentWrite ) && isNotNull( connection ) )
                connection.clearWriteInterest();

            return currentWrite;
        }
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
}

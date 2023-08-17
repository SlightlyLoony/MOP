package com.dilatush.mop.cpo;

import com.dilatush.mop.Message;
import com.dilatush.mop.MessageDeframer;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

import static com.dilatush.util.General.*;

/**
 * Instances of this class contain the state of a client post office connection to the central post office.
 *
 * @author Tom Dilatush  tom@dilatush.com
 */
public class POConnection {

    private static final Logger LOGGER                 = getLogger();

    public static final String CONNECTION_NAME = "-={([connectionName])}=-";   // name of attribute we add to provide connection name to router...

    /* package */ final String            name;
    /* package */ final SocketChannel     socket;
    /* package */ final MessageDeframer   deframer;
    /* package */ final CentralPostOffice cpo;
    /* package */ final AtomicLong        timeSinceLastPongMS;

    /* package */ volatile POClient client;  // post office state associated with this connection (null if post office not authenticated yet)...

    /* package */ volatile boolean open;


    /* package */ POConnection( final CentralPostOffice _cpo, final SocketChannel _client, final String _name, final AtomicInteger _maxMessageSize ) {
        cpo                 = _cpo;
        socket              = _client;
        name                = _name;
        deframer            = new MessageDeframer( _maxMessageSize );
        timeSinceLastPongMS = new AtomicLong( 0 );
        open                = true;
    }


    /* package */ void receiveBytes( final ByteBuffer _bytes ) {

        // update the bytes received...
        if( isNotNull( client ))
            client.rxBytes.addAndGet( _bytes.limit() );

        // loop as long as we still have bytes to append to our deframer...
        while( _bytes.remaining() > 0 ) {

            LOGGER.finest( "Rx bytes remaining: " + _bytes.remaining() );

            // if closed, ignore these...
            if( !open ) return;

            // add the received bytes to our deframer...
            deframer.addBytes( _bytes );

            // see if we've received any complete messages...
            while( true ) {

                // try to extract a frame...
                byte[] frame = deframer.getFrame();

                // if we got nothing, it's time to leave...
                //noinspection RedundantCast
                if( isNull( (Object) frame ) ) break;

                try {

                    // try to extract a message...
                    // TODO: handle JSON decoding errors...
                    Message msg = new Message( new String( frame, StandardCharsets.UTF_8 ) );
                    LOGGER.finest( "Received: " + msg );
                    if( isNotNull( client ) )
                        client.rxMessages.incrementAndGet();

                    // if this message is going to the cpo, add a connection name attribute for use by the router...
                    if( "central.po".equals( msg.to ) )
                        msg.put( CONNECTION_NAME, name );

                    // send it to the central post office...
                    cpo.receiveMessage( client, msg );
                }
                catch( Exception _e ) {
                    // getting here means we had a problem decoding the received message - we log and ignore...
                    LOGGER.log( Level.SEVERE, "Could not decode received message: " + new String( frame, StandardCharsets.UTF_8 ), _e );
                }
            }
        }

        // clear our input buffer to show we got 'em all...
        _bytes.clear();
    }


    /* package */ void showWriteInterest() {
        SelectionKey key = socket.keyFor( cpo.handler.selector );
        if( isNotNull( key ) ) key.interestOps( key.interestOps() | SelectionKey.OP_WRITE );
    }


    /* package */ void clearWriteInterest() {
        SelectionKey key = socket.keyFor( cpo.handler.selector );
        if( isNotNull( key ) ) key.interestOps( key.interestOps() & ~SelectionKey.OP_WRITE );
    }


    /**
     * Closes this connection.
     */
    /* package */ void close() {
        if( isNotNull( client ) ) client.connection = null;
        client = null;
        open = false;
        if( isNotNull( socket ) ) {
            try {
                socket.close();
            }
            catch( IOException _e ) {
                // ignore...
            }
        }
    }
}

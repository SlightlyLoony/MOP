package com.dilatush.mop.cpo;

import com.dilatush.mop.Message;
import com.dilatush.mop.MessageDeframer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicLong;

import static com.dilatush.util.General.isNotNull;
import static com.dilatush.util.General.isNull;

/**
 * Instances of this class contain the state of a client post office connection to the central post office.
 *
 * @author Tom Dilatush  tom@dilatush.com
 */
public class POConnection {

    private static final Logger LOG = LogManager.getLogger();

    public static final String CONNECTION_NAME = "-={([connectionName])}=-";   // name of attribute we add to provide connection name to router...

    /* package */ final String            name;
    /* package */ final SocketChannel     socket;
    /* package */ final MessageDeframer   deframer;
    /* package */ final CentralPostOffice cpo;
    /* package */ final AtomicLong        timeSinceLastPongMS;

    /* package */ volatile POClient client;  // post office state associated with this connection (null if post office not authenticated yet)...

    /* package */ volatile boolean open;


    /* package */ POConnection( final CentralPostOffice _cpo, final SocketChannel _client, final String _name, final int _maxMessageSize ) {
        cpo                 = _cpo;
        socket              = _client;
        name                = _name;
        deframer            = new MessageDeframer( _maxMessageSize );
        timeSinceLastPongMS = new AtomicLong( 0 );
        open                = true;
    }


    /* package */ void receiveBytes( final ByteBuffer _bytes ) {

        // if closed, ignore these...
        if( !open ) return;

        // add the received bytes to our deframer...
        if( isNotNull( client ))
            client.rxBytes.addAndGet( _bytes.limit() );
        deframer.addBytes( _bytes );

        // see if we've received any complete messages...
        while( true ) {

            // try to extract a frame...
            byte[] frame = deframer.getFrame();

            // if we got nothing, it's time to leave...
            if( isNull( (Object) frame ) ) break;

            try {

                // try to extract a message...
                // TODO: handle JSON decoding errors...
                Message msg = new Message( new String( frame, StandardCharsets.UTF_8 ) );
                LOG.debug( "Received: " + msg.toString() );
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
                LOG.error( "Could not decode received message: " + new String( frame, StandardCharsets.UTF_8 ), _e );
            }
        }
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

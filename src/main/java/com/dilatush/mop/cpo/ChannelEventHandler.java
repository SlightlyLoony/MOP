package com.dilatush.mop.cpo;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.Iterator;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import static com.dilatush.util.General.*;

/**
 * Implements a handler for all the NIO events (connecting, reading, writing) associated with the central post office's network communications.
 * This handler runs in its own thread, and we go to some trouble to keep this thread alive as the entire central post office fails if this thread
 * dies.
 *
 * @author Tom Dilatush  tom@dilatush.com
 */
/* package */ class ChannelEventHandler extends Thread {

    private static final Logger LOGGER                 = getLogger();

    /* package */ Selector            selector;
    private       ServerSocketChannel channel;
    private final CentralPostOffice   cpo;

    /* package */ volatile boolean testReadException;
    /* package */ volatile boolean testWriteException;


    /* package */ ChannelEventHandler( final CentralPostOffice _cpo ) {
        cpo = _cpo;
        setName( cpo.config.name + " Channel Event Handler" );
        setDaemon( false );  // this will be, in effect, the main thread for the Central Post Office...
    }


    /**
     * Opens the server's channel so that client post offices may connect.
     *
     */
    /* package */ synchronized void open() {

        LOGGER.info( "Opening server channel socket" );

        try {
            // initialize the server's basic objects...
            selector = Selector.open();
            channel = ServerSocketChannel.open();
            channel.bind( new InetSocketAddress( cpo.config.localAddress, cpo.config.port ) );
            channel.configureBlocking( false );
            channel.register( selector, SelectionKey.OP_ACCEPT, null );
        }
        catch( IOException _e ) {
            throw new IllegalStateException( "Problem when initializing server", _e );
        }
    }


    // Used for testing and shutdown only...
    /* package */ synchronized void close() {
        try {
            channel.close();
        }
        catch( IOException _e ) {
            // ignore...
        }
    }


    /**
     * The channel event handling loop.
     */
    public void run() {

        while( true ) {

            // catch anything that might be recovered from, and keep on trucking...
            try {
                select();

                // the only way out of this loop is an interrupt or shutdown...
                if( cpo.shutdown || isInterrupted() ) break;
            }

            // we get here for any exception other than IOException, which is caught inside select()...
            catch( Exception _e ) {

                // log this, so we know what happened...
                LOGGER.log( Level.SEVERE, "Exception escaped from channel event handler; ignoring", _e );
            }
        }
    }


    // handles one select operation...
    private void select() {

        // wait for something to happen - blocks until event or interruption...
        try {
            // blocks until a channel is newly ready for some operation...
            // by using a timeout, asynchronous interestOps() calls work correctly; without it, just blocks until a connect or read happens...
            selector.select(1);

            // if we're shutting down, just get out of here...
            if( cpo.shutdown ) return;

            // get the set of keys (one per channel) that is ready for operations of interest...
            Set<SelectionKey> keys = selector.selectedKeys();

            // if there are zero keys in our selection set, then just exit (where we'll immediately re-select)...
            if( keys.size() == 0 ) {
                if( !channel.isOpen() )
                    handleServerSocketClosed();
                return;
            }

            // handle the keys we got...
            handleSelectedKeys( keys );
        }
        catch( IOException _e ) {
            // we're not sure what could cause this exception, but we suspect whatever it is must be fatal...
            LOGGER.log( Level.SEVERE, "Fatal exception during selector.select():", _e );
            interrupt();
        }
    }


    // handle the set of keys just selected, one key per channel...
    private void handleSelectedKeys( Set<SelectionKey> _keys ) {

        // iterate over all the selection keys we got...
        Iterator<SelectionKey> it = _keys.iterator();
        while( it.hasNext() ) {

            // take a key to work with, immediately removing it...
            SelectionKey key = it.next();
            it.remove();

            // if the key is invalid, ignore it...
            if( !key.isValid() ) continue;

            // in the tests below, it's necessary to check for key validity each time, as it could be killed anytime at all...

            // if we're ready to accept a connection, do it (this is only going to occur on the server socket channel)...
            if( key.isValid() && key.isAcceptable() ) handleAccept( key );

            // if we have data ready to read, do it (this is only going to happen on a client connection channel)...
            if( key.isValid() && key.isReadable() ) handleRead( key );

            // if we are ready to write data to (this is only going to happen on a client connection channel with data ready to write)...
            if( key.isValid() && key.isWritable() ) handleWrite( key );
        }
    }


    // Handle presence of "accept" in selection key.  Creates a client connection, registers it, and enables receiving.
    @SuppressWarnings( "unused" )
    private void handleAccept( final SelectionKey _key ) {

        // create our client socket...
        SocketChannel client = null;

        try {
            // get the socket channel for our new client connection...
            client = channel.accept();

            // configure the channel...
            client.configureBlocking( false );

            // register with our selector, enabling read interest by default...
            SelectionKey key = client.register( selector, SelectionKey.OP_READ, null );

            // get our client's name...
            String clientName = client.getRemoteAddress().toString();

            // create a connection state holder for it, and register it...
            // we do this last so that any exceptions would already have occurred, and we won't end up with an orphan state object...
            POConnection connection = new POConnection( cpo, client, clientName, cpo.config.maxMessageSize );
            cpo.connections.put( connection.name, connection );

            // attach our connection instance to this key, so we don't have to keep looking it up...
            key.attach( connection );
        }
        catch( IOException _e ) {

            // if we get an exception here, then our channel.accept() must have failed for some reason...
            // this is before we have any state on the connection, so we'll just make sure the socket is disconnected, log it, and leave...
            LOGGER.log( Level.SEVERE, "Exception occurred while accepting a connection", _e );
            if( client != null ) try { client.close(); } catch( IOException _e1 ) { /* naught to do here */ }
        }
    }


    // Handle presence of "read" in selection key.  Reads a batch of bytes and queues them for later processing.
    private void handleRead( final SelectionKey _key ) {

        try {
            // a little setup...
            @SuppressWarnings( "resource" ) SocketChannel client = (SocketChannel) _key.channel();
            ByteBuffer buffer = ByteBuffer.allocate( cpo.config.maxMessageSize.get() + 10 );  // enough space for a complete max size message...

            // read until there are no more bytes to read, or we've filled our buffer...
            boolean doneReading = false;
            while( !doneReading && (buffer.remaining() > 0) ) {

                // test harness...
                if( testReadException ) {
                    testReadException = false;
                    throw new IOException( "Test exception" );
                }

                // read some bytes...
                int bytesRead = client.read( buffer );

                // if we hit end of channel, then our client closed this connection on us...
                if( bytesRead < 0 ) throw new IOException( "Unexpected channel close" );

                // if we didn't read any bytes, then there are no more left to read...
                doneReading = (bytesRead == 0);
            }

            // if we read any bytes, queue them up for processing...
            if( buffer.position() > 0 ) {
                buffer.flip();
                cpo.rxbytes.add( new RxBytes( (POConnection) _key.attachment(), buffer ) );
            }
        }
        catch( IOException _e ) {

            // make sure our channel is closed and its key is canceled...
            try {
                _key.cancel();
                _key.channel().close();
            }
            catch( IOException _e1 ) {
                // naught to do here, just ignore it...
            }

            cpo.closeConnection( (POConnection) _key.attachment() );
        }
    }


    // Handle presence of "write" in selection key.  Writes a batch of bytes from the outgoing message queue of the relevant client connection.
    @SuppressWarnings( "resource" )
    private void handleWrite( final SelectionKey _key ) {

        try {
            // get the connection associated with this key...
            POConnection connection = (POConnection) _key.attachment();

            // if there was no connection or no client, clear the write interest before we get into CPU consumption trouble...
            if( isNull( connection ) || isNull( connection.client ) ) {
                _key.interestOps( _key.interestOps() & ~SelectionKey.OP_WRITE );
                return;
            }

            // grab a buffer of message data...
            ByteBuffer serialized = connection.client.getWriteData();

            // test harness...
            if( isNotNull( serialized ) && testWriteException ) {
                testWriteException = false;
                throw new IOException( "Test exception" );
            }

            // if we got some, then write out as much as we can...
            if( isNotNull( serialized ) ) {
                ((SocketChannel) (_key.channel())).write( serialized );
            }
        }
        catch( IOException _e ) {

            // make sure our channel is closed and its key is canceled...
            try {
                _key.cancel();
                _key.channel().close();
            }
            catch( IOException _e1 ) {
                // naught to do here, just ignore it...
            }

            cpo.closeConnection( (POConnection) _key.attachment() );
        }
    }


    // We get here if the select loop detected the server socket being closed...
    private void handleServerSocketClosed() {

        LOGGER.info( "Detected closed server channel socket" );

        // then we reopen the channel socket, if we're not in shutdown mode...
        if( !cpo.shutdown ) open();
    }
}

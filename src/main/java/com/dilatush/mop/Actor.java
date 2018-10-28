package com.dilatush.mop;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

import static com.dilatush.util.General.isNotNull;
import static com.dilatush.util.General.isNull;
import static com.dilatush.util.Strings.isEmpty;

/**
 * Abstract base class for all actor implementations.  Much of this class' function had to do with registering message handlers and dispatching
 * received messages.  Each message received will be dispatched to at most one message handler, the one that is most qualified.  The message handlers
 * are searched from most qualified to least (see table below for the exact order), and the first one that is found is called.  Note that the searches
 * for direct and published messages are independent; one can have separate message handlers for direct and published messages that otherwise have
 * identical criteria.  If <i>no</i> matching message handlers are found, then the message is simply ignored (not dispatched to <i>any</i> handler).
 * The search order:
 * <ol>
 *     <li>fully qualified (from po.mailbox, type major.minor must match)</li>
 *     <li>fully qualified, major type (from po.mailbox, type major must match)</li>
 *     <li>full type (type major.minor must match)</li>
 *     <li>major type (type major must match)</li>
 *     <li>default (no match required)</li>
 * </ol>
 *
 * @author Tom Dilatush  tom@dilatush.com
 */
public abstract class Actor {

    private static final Logger LOGGER                 = Logger.getLogger( new Object(){}.getClass().getEnclosingClass().getCanonicalName());

    public final PostOffice                 po;
    public final String                     name;
    public final Mailbox                    mailbox;
    public final Map<String,MessageHandler> handlers;  // note 1...

    // Notes:
    //
    // 1.  Indexed by a string constructed as follows, in the order given:
    //     a.  A single character message type, "P" for publish, "D" for direct
    //     b.  A single character match level: "F" for po.mailbox.major.minor, "f" for po.mailbox.major, "T" for major.minor, and "t" for major
    //     c.  If the match level is "F" or "f", the po.mailbox we're matching
    //     d.  If the match level is "F" or "f", the separator ":"
    //     e.  The major type
    //     f.  If the match level is "F" or "T", a period and the minor type
    //     g.  A single character "P" indexes the default publish message handler
    //     h.  A single character "D" indexes the default direct message handler
    //
    //     Examples:
    //       Ptsensor                matches published messages from any source with a major type "sensor"
    //       DTpump.disabled         matches direct messages from any source with a major.minor type of "pump.disabled"
    //       D                       matches any direct message
    //       DFntp.monitor:status.up matches direct messages from po.mailbox "ntp.monitor" with a major.minor type of "status.up"

    private final Dispatcher dispatcher;


    protected Actor( final PostOffice _po, final String _name ) {

        po         = _po;
        name       = _name;
        mailbox    = po.createMailbox( _name );
        dispatcher = new Dispatcher();
        handlers   = new ConcurrentHashMap<>();
    }


    /**
     * Shut down this instance, including subordinate threads.  Subclasses that need further shutdown behavior <i>must</i> override this method,
     * calling super.shutdown() inside it.
     */
    public void shutdown() {

        // kill our dispatcher...
        dispatcher.interrupt();

        LOGGER.info( "Actor " + name + " has shut down" );
    }


    /**
     * Register the specified message handler as the default direct message handler, invoked to handle any direct messages that doesn't match any other 
     * message handler's criteria.
     *
     * @param _handler the direct message handler to register.
     * @return the handle for the registered message handler (used for deregistration only; otherwise may be ignored).
     */
    protected Object registerDirectMessageHandler( final MessageHandler _handler ) {
        return registerMessageHandler( _handler, false, null, null, null );
    }


    /**
     * Register the specified message handler to handle direct messages with the specified major type.  This message handler will be invoked when 
     * direct messages are received that don't match the criteria for any more specific registered message handler, but do match the specified  major 
     * message type. The minor message type is not used for matching.
     *
     * @param _handler the direct message handler to register.
     * @param _major the major type to match.
     * @return the handle for the registered message handler (used for deregistration only; otherwise may be ignored).
     */
    protected Object registerDirectMessageHandler( final MessageHandler _handler, final String _major ) {
        return registerMessageHandler( _handler, false, null, _major, null );
    }


    /**
     * Register the specified message handler to handle direct messages with the specified major and minor types.  This message handler will be 
     * invoked when direct messages are received that don't match the criteria for any more specific registered message handler, but do match the 
     * specified  major and minor message type.
     *
     * @param _handler the direct message handler to register.
     * @param _major the major type to match.
     * @param _minor the minor type to match.
     * @return the handle for the registered message handler (used for deregistration only; otherwise may be ignored).
     */
    protected Object registerDirectMessageHandler( final MessageHandler _handler, final String _major, final String _minor ) {
        return registerMessageHandler( _handler, false, null, _major, _minor );
    }


    /**
     * Register the specified message handler to handle fully-qualified direct messages.  This message handler will be invoked when direct messages 
     * are received that don't match the criteria for any more specific registered message handler, but do match the specified from (po.mailbox) 
     * address <i>and</i> the specified major message type.  The minor message type is not used for matching.
     *
     * @param _handler the direct message handler to register.
     * @param _from the from address (po.mailbox) to match.
     * @param _major the major type to match.
     * @return the handle for the registered message handler (used for deregistration only; otherwise may be ignored).
     */
    protected Object registerFQDirectMessageHandler( final MessageHandler _handler, final String _from, final String _major ) {
        return registerMessageHandler( _handler, false, _from, _major, null );
    }


    /**
     * Register the specified message handler to handle fully-qualified direct messages.  This message handler will be invoked when direct messages 
     * are received that match the specified from (po.mailbox) address <i>and</i> the specified major and minor message type.
     *
     * @param _handler the direct message handler to register.
     * @param _from the from address (po.mailbox) to match.
     * @param _major the major type to match.
     * @param _minor the minor type to match.
     * @return the handle for the registered message handler (used for deregistration only; otherwise may be ignored).
     */
    protected Object registerFQDirectMessageHandler( final MessageHandler _handler, final String _from, final String _major, final String _minor ) {
        return registerMessageHandler( _handler, false, _from, _major, _minor );
    }


    /**
     * Register the specified message handler as the default publish message handler, invoked to handle any publish messages that doesn't match any 
     * other message handler's criteria.
     *
     * @param _handler the publish message handler to register.
     * @return the handle for the registered message handler (used for deregistration only; otherwise may be ignored).
     */
    protected Object registerPublishtMessageHandler( final MessageHandler _handler ) {
        return registerMessageHandler( _handler, true, null, null, null );
    }


    /**
     * Register the specified message handler to handle publish messages with the specified major type.  This message handler will be invoked when 
     * publish messages are received that don't match the criteria for any more specific registered message handler, but do match the specified  major 
     * message type. The minor message type is not used for matching.
     *
     * @param _handler the publish message handler to register.
     * @param _major the major type to match.
     * @return the handle for the registered message handler (used for deregistration only; otherwise may be ignored).
     */
    protected Object registerPublishMessageHandler( final MessageHandler _handler, final String _major ) {
        return registerMessageHandler( _handler, true, null, _major, null );
    }


    /**
     * Register the specified message handler to handle publish messages with the specified major and minor types.  This message handler will be 
     * invoked when publish messages are received that don't match the criteria for any more specific registered message handler, but do match the 
     * specified  major and minor message type.
     *
     * @param _handler the publish message handler to register.
     * @param _major the major type to match.
     * @param _minor the minor type to match.
     * @return the handle for the registered message handler (used for deregistration only; otherwise may be ignored).
     */
    protected Object registerPublishMessageHandler( final MessageHandler _handler, final String _major, final String _minor ) {
        return registerMessageHandler( _handler, true, null, _major, _minor );
    }


    /**
     * Register the specified message handler to handle fully-qualified publish messages.  This message handler will be invoked when publish messages 
     * are received that don't match the criteria for any more specific registered message handler, but do match the specified from (po.mailbox) 
     * address <i>and</i> the specified major message type.  The minor message type is not used for matching.
     *
     * @param _handler the publish message handler to register.
     * @param _from the from address (po.mailbox) to match.
     * @param _major the major type to match.
     * @return the handle for the registered message handler (used for deregistration only; otherwise may be ignored).
     */
    protected Object registerFQPublishMessageHandler( final MessageHandler _handler, final String _from, final String _major ) {
        return registerMessageHandler( _handler, true, _from, _major, null );
    }


    /**
     * Register the specified message handler to handle fully-qualified publish messages.  This message handler will be invoked when publish messages 
     * are received that match the specified from (po.mailbox) address <i>and</i> the specified major and minor message type.
     *
     * @param _handler the publish message handler to register.
     * @param _from the from address (po.mailbox) to match.
     * @param _major the major type to match.
     * @param _minor the minor type to match.
     * @return the handle for the registered message handler (used for deregistration only; otherwise may be ignored).
     */
    protected Object registerFQPublishMessageHandler( final MessageHandler _handler, final String _from, final String _major, final String _minor ) {
        return registerMessageHandler( _handler, true, _from, _major, _minor );
    }


    // Handles all the mechanics of handler registration for all the preceding registration convenience methods.
    // Returns a (sort of) opaque handle that can be used for deregistration.
    private Object registerMessageHandler( final MessageHandler _handler,
                                         final boolean _isPublish, final String _from, final String _major, final String _minor  ) {

        // a little sanity checking...
        if( isNull( _handler ) ) throw new IllegalArgumentException( "Missing handler to register" );
        if( isEmpty( _major ) && !isEmpty( _minor ) ) throw new IllegalArgumentException( "Missing major type" );
        if( !isEmpty( _from ) && isEmpty( _major ) && isEmpty( _minor ) ) throw new IllegalArgumentException( "Missing major and minor type" );

        // a little analysis...
        boolean isFQ = !isEmpty( _from  );
        boolean isMM = !isEmpty( _minor );
        boolean isDefault = isEmpty( _from ) && isEmpty( _major ) && isEmpty( _minor );

        // build our index string...
        StringBuilder sb = new StringBuilder();
        sb.append( _isPublish ? 'P' : 'D' );
        if( !isDefault ) {
            sb.append( isFQ ? (isMM ? 'F' : 'f') : (isMM ? 'T' : 't') );
            if( isFQ ) {
                sb.append( _from );
                sb.append( ':' );
            }
            sb.append( _major );
            if( isMM ) {
                sb.append( '.' );
                sb.append( _minor );
            }
        }

        // register our new handler...
        String key = sb.toString();
        handlers.put( key, _handler );

        // return the key in case deregistration is needed...
        return key;
    }


    /**
     * Deregisters the previously registered message handler with the specified handle (the handle was returned by the registration method).  Any
     * kind of message handler may be deregistered this way.  Returns true if the message handler was successfully deregistered, false if there was no
     * registered message handler with the specified handle.
     *
     * @param _handle the handle returned when the message handler was registered.
     * @return true if the message handler was deregistered.
     */
    protected boolean deregisterMessageHandler( final Object _handle ) {

        // sanity check...
        if( !( _handle instanceof String) ) throw new IllegalArgumentException( "Invalid handle for deregistration" );

        // attempt to remove and return the result...
        return isNotNull( handlers.remove( (String) _handle ) );
    }


    /**
     * The interface that all message handlers must implement.
     */
    @FunctionalInterface
    protected interface MessageHandler {

        /**
         * Invoked when a message is received that matches the criteria specified when registering this handler.
         *
         * @param _message the message received.
         */
        void onMessage( final Message _message );
    }


    private class Dispatcher extends Thread {

        private Dispatcher() {
            setName( name + " Dispatcher" );
            setDaemon( true );
            start();
        }


        public void run() {

            while( true ) {

                try {

                    // wait until a message shows up...
                    Message message = mailbox.take();

                    // now we make up to five searches, looking for message handlers that can process this message...
                    if( handled( message, fromFullKey(    message ) ) ) continue;
                    if( handled( message, fromPartialKey( message ) ) ) continue;
                    if( handled( message, fullKey(        message ) ) ) continue;
                    if( handled( message, partialKey(     message ) ) ) continue;
                    if( handled( message, defaultKey(     message ) ) ) continue;

                    // if we get here, there was no matching handler, so just log it and leave...
                    LOGGER.info( "No handler for " + message.toString() );
                }
                catch( InterruptedException _e ) {
                    break;
                }
                catch( Exception _e ) {

                    // we get here only on an unchecked exception occurring somewhere in the code above...
                    // almost certainly that means the error occurred in the handler, and we'll have no idea why...
                    // so we'll do nothing here, and just try for another message...
                    LOGGER.log( Level.SEVERE, "Unhandled exception in message handler", _e );
                }
            }
        }


        private String fromFullKey( final Message _message ) {
            return String.valueOf( _message.isPublish() ? 'P' : 'D' ) + 'F' + _message.from + ':' + _message.type;
        }


        private String fromPartialKey( final Message _message ) {
            return String.valueOf( _message.isPublish() ? 'P' : 'D' ) + 'f' + _message.from + ':' + _message.majorType;
        }


        private String fullKey( final Message _message ) {
            return String.valueOf( _message.isPublish() ? 'P' : 'D' ) + 'T' + _message.type;
        }


        private String partialKey( final Message _message ) {
            return String.valueOf( _message.isPublish() ? 'P' : 'D' ) + 't' + _message.majorType;
        }


        private String defaultKey( final Message _message ) {
            return String.valueOf( _message.isPublish() ? 'P' : 'D' );
        }


        // Looks for a message handler matching the given key.  If found, invokes the handler and returns true.  Otherwise, returns false.
        private boolean handled( final Message _message, final String _key ) {
            MessageHandler handler = handlers.get( _key );
            if( isNotNull( handler ) ) {
                handler.onMessage( _message );
                return true;
            }
            return false;
        }
    }
}

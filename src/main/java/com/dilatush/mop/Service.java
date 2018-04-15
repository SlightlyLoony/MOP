package com.dilatush.mop;

/**
 * Implemented by classes that provide a MOP service.
 *
 * @author Tom Dilatush  tom@dilatush.com
 */
public abstract class Service extends Thread {
//
//    protected final MOPContext context;
//    protected final PostOffice po;
//    protected final Handle mailboxHandle;
//    protected final Map<Handle,TimeService.MessageHandler> dispatcher;
//    protected final String name;
//
//
//    protected Service( final MOPContext _context, final String _name ) {
//        context = _context;
//        po = context.po;
//        name = _name;
//        mailboxHandle = po.createMailbox();
//        setDaemon( true );
//        setName( _name + " Message Receiver" );
//        dispatcher = new HashMap<>();
//        start();
//    }
//
//
//    @Override
//    public void run() {
//
//        boolean stop = false;
//        while( !stop ) {
//
//            Message msg = null;
//            try {
//                msg = po.take( mailboxHandle );
//                onMessage( msg );
//            }
//            catch( InterruptedException _e ) {
//                stop = true;
//            }
//        }
//    }
//
//
//    public abstract void onMessage( final Message _message );
//
//
//    @FunctionalInterface
//    protected interface MessageHandler {
//        void handle( final Message _message );
//    }
//
//
//    public String getServiceName() {
//        return name;
//    }
}

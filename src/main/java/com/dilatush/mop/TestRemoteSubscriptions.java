package com.dilatush.mop;

import com.dilatush.mop.cpo.CentralPostOffice;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Timer;
import java.util.TimerTask;

import static java.lang.Thread.sleep;

/**
 * Runs a simple remote subscriptions test of the MOP, with two client post offices and a central post office all running in the same process.
 *
 * @author Tom Dilatush  tom@dilatush.com
 */
public class TestRemoteSubscriptions {

    private static Logger LOG;


    public static void main( String[] args ) throws InterruptedException {

        // set up our console logger...
        System.setProperty( "log4j.configurationFile", "TestLog.json" );
        LOG = LogManager.getLogger();

        // create our central post office and start it up...
        CentralPostOffice cpo = new CentralPostOffice( "CentralPostOfficeConfig.json" );
        cpo.start();

        // create our post offices...
        PostOffice test1 = new PostOffice( "Test1.json" );
        PostOffice test2 = new PostOffice( "Test2.json" );

        // create our test actors...
        TestTimerActor actor1 = new TestTimerActor( test1 );
        TestSubscriberActor actor2 = new TestSubscriberActor( test2 );

        // wait until both post offices have connected...
        while( !test1.isConnected() || !test2.isConnected() )
            sleep( 10 );
        LOG.info( "test1 and test2 connected" );

        // start our tests...
        actor1.start();
        actor2.start( true );

        sleep( 5000 );

        // test client connection recovery...
        test1.shutdown();
        actor1.shutdown();
        sleep( 100 );
        test1 = new PostOffice( "Test1.json" );
        actor1 = new TestTimerActor( test1 );
        actor1.start();

        sleep( 5000 );

        // test central post office recovery...
        cpo.shutdown();
        sleep( 100 );
        cpo = new CentralPostOffice( "CentralPostOfficeConfig.json" );
        cpo.start();

        sleep( 20000 );

        // shut down our little world...
        cpo.shutdown();
        test1.shutdown();
        test2.shutdown();
        actor1.shutdown();
        actor2.shutdown();

        sleep( 1000 );

        cpo.hashCode();
    }


    private static class TestSubscriberActor extends Actor {

        protected TestSubscriberActor( final PostOffice _po ) {
            super( _po, "subscriber" );
        }


        public void start( final boolean _subscribe ) {
            registerFQPublishMessageHandler( this::onTimerMessage,"test1.timer", "periodic", "1000ms" );
            if( _subscribe )
                mailbox.subscribe( "test1.timer", "periodic.1000ms" );
        }

        public void onTimerMessage( final Message _message ) {
            LOG.info( "Got timer!" );
        }
    }



    // generates "periodic.1000ms" messages every second...
    private static class TestTimerActor extends Actor {

        private final Timer timer;


        public TestTimerActor( final PostOffice _po ) {
            super( _po, "timer" );
            timer = new Timer( "TestTimerActor Timer", true );
        }


        public void shutdown() {
            timer.cancel();
            super.shutdown();
        }


        public void start() {
            timer.scheduleAtFixedRate( new PublishTask(), 1000, 1000 );
        }


        private class PublishTask extends TimerTask {
            @Override
            public void run() {
                Message msg = mailbox.createPublishMessage( "periodic.1000ms" );
                mailbox.send( msg );
            }
        }
    }
}

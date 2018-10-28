package com.dilatush.mop;

import com.dilatush.mop.cpo.CentralPostOffice;

import java.util.logging.Logger;

import static java.lang.Thread.sleep;

/**
 * Runs a simple encryption test of the MOP, with two client post offices and a central post office all running in the same process.
 *
 * @author Tom Dilatush  tom@dilatush.com
 */
public class TestEncryption {

    private static final Logger LOGGER                 = Logger.getLogger( new Object(){}.getClass().getEnclosingClass().getCanonicalName());


    public static void main( String[] args ) throws InterruptedException {

        // create our central post office and start it up...
        CentralPostOffice cpo = new CentralPostOffice( "CentralPostOfficeConfig.json" );
        cpo.start();

        // create our post offices...
        PostOffice test1 = new PostOffice( "Test1.json" );
        PostOffice test2 = new PostOffice( "Test2.json" );

        // create our test actors...
        TestEncryptActor actor1 = new TestEncryptActor( test1 );
        TestDecryptActor actor2 = new TestDecryptActor( test2 );

        // wait until both post offices have connected...
        while( !test1.isConnected() || !test2.isConnected() )
            sleep( 10 );
        LOGGER.info( "test1 and test2 connected" );

        // start our tests...
        actor1.start();
        actor2.start();

        sleep( 1000 );

        // shut down our little world...
        cpo.shutdown();
        test1.shutdown();
        test2.shutdown();
        actor1.shutdown();
        actor2.shutdown();

        sleep( 1000 );

        cpo.hashCode();
    }


    private static class TestEncryptActor extends Actor {

        protected TestEncryptActor( final PostOffice _po ) {
            super( _po, "encrypt" );
        }


        public void start() {
            Message test = mailbox.createDirectMessageExpectingReply( "test2.decrypt" );
            test.putDotted( "test.a", "who wants to know?" );
            test.putDotted( "test.b", 1234567.89 );
            mailbox.encrypt( test, "test.a", "test.b" );
            mailbox.send( test );
        }
    }



    // generates "periodic.1000ms" messages every second...
    private static class TestDecryptActor extends Actor {


        public TestDecryptActor( final PostOffice _po ) {
            super( _po, "decrypt" );
            registerDirectMessageHandler( this::handler );
        }


        public void shutdown() {
            super.shutdown();
        }


        public void start() {
        }


        public void handler( final Message _message ) {
            String msg = "Received message: " + _message.getStringDotted( "test.a" ) + ", " + _message.getDoubleDotted( "test.b" );
            LOGGER.info( msg );
        }
    }
}

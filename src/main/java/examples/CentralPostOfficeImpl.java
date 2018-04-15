package examples;

import com.dilatush.mop.cpo.CentralPostOffice;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;

import static com.dilatush.util.General.isNotNull;
import static java.lang.Thread.sleep;

/**
 * An example implementation of a central post office.  Accepts the following arguments on the command line (note that if the log configuration file
 * path is specified, the CPO configuration file path <i>must</i> also be specified):
 * <ol>
 *    <li>central post office configuration file path (default is "CPOConfig.json")</li>
 *    <li>logger configuration file path (default is "CPOLog.json")</li>
 * </ol>
 * @author Tom Dilatush  tom@dilatush.com
 */
public class CentralPostOfficeImpl {

    private static Logger LOG;

    public static void main( final String[] _args ) throws InterruptedException {

        // first we get and check the command line arguments...

        // the CPO configuration file...
        String config = "CPOConfig.json";   // the default...
        if( isNotNull( (Object) _args ) && (_args.length > 0) ) config = _args[0];
        if( !new File( config ).exists() ) {
            System.out.println( "CPO configuration file " + config + " does not exist!" );
            return;
        }

        // the logger configuration file...
        String logger = "CPOLog.json";
        if( isNotNull( (Object) _args ) && (_args.length > 1) ) logger = _args[1];
        if( !new File( config ).exists() ) {
            System.out.println( "CPO log configuration file " + config + " does not exist!" );
            return;
        }

        // set up our logger...
        System.setProperty( "log4j.configurationFile", logger );
        LOG = LogManager.getLogger();

        // now we start up our central post office...
        CentralPostOffice cpo = new CentralPostOffice( config );
        cpo.start();

        // now we just hang around forever, unless something interrupts us...
        while( true ) sleep( 1000 );
    }
}

package examples;

import com.dilatush.mop.cpo.CentralPostOffice;

import java.util.logging.Logger;

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

    @SuppressWarnings( "FieldCanBeLocal" )
    private static Logger LOGGER;

    public static void main( final String[] _args ) throws InterruptedException {

        // set the logging configuration file location (must do before any logging actions occur)...
        System.getProperties().setProperty( "java.util.logging.config.file", "logging.properties" );
        LOGGER = Logger.getLogger( new Object(){}.getClass().getEnclosingClass().getSimpleName() );

        LOGGER.info( "CPO starting..." );

        // now we start up our central post office...
        CentralPostOffice cpo = new CentralPostOffice( "configurationCPO.java", "secretsCPO.json" );
        cpo.start();

        // now we just leave, as cpo is running in a non-daemon thread...
    }
}

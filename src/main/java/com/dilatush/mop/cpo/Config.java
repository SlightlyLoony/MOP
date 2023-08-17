package com.dilatush.mop.cpo;

import com.dilatush.util.Files;
import com.dilatush.util.Outcome;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import static com.dilatush.util.General.getLogger;
import static com.dilatush.util.Strings.isEmpty;

/**
 * Represents the current configuration of a central post office.  Certain components of this configuration need to be persistent; these can be read from
 * a configuration file, and written to a configuration file.
 *
 * @author Tom Dilatush  tom@dilatush.com
 */
public class Config {

    private static final Logger LOGGER                 = getLogger();

    private static final String FIELD_SECRET           = "secret";
    private static final String FIELD_NAME             = "name";
    private static final String FIELD_MANAGER          = "manager";

    /* package */ final String        localAddress;
    /* package */ final int           port;
    /* package */ final AtomicInteger maxMessageSize;
    /* package */ final String        name;
    /* package */ final long          pingIntervalMS;

    /* package */ Map<String,POClient> clients;  // the state known of client post offices, post office name -> client...


    private Config( final JavaConfig _javaConfig, final Map<String,POClient> _clients  ) {

        clients        = _clients;
        localAddress   = _javaConfig.localAddress;
        port           = _javaConfig.port;
        name           = _javaConfig.name;
        maxMessageSize = new AtomicInteger( _javaConfig.maxMessageSize );
        pingIntervalMS = _javaConfig.pingIntervalMS;
    }


    public static Config initializeConfig( final String _configFilePath, final String _secretsFilePath ) {

        // get our configuration...
        JavaConfig javaConfig = new JavaConfig();
        Outcome<?> result = javaConfig.init( "CPOConfigurator", _configFilePath );

        // if our configuration is not valid, just get out of here...
        if( !result.ok() ) {
            LOGGER.severe( "Aborting; configuration is invalid\n" + result.msg() );
            System.exit( 1 );
        }

        // read the secrets file into a JSON object...
        if( isEmpty( _secretsFilePath ) ) throw new IllegalArgumentException( "Secrets file path missing or empty" );
        String json = Files.readToString( new File( _secretsFilePath ) );
        if( isEmpty( json ) ) throw new IllegalArgumentException( "Secrets file missing or empty: " + _configFilePath );
        JSONArray jsonClients = new JSONArray( json );

        // read in our persistent fields...
        Map<String,POClient> clients = new ConcurrentHashMap<>();
        for( int i = 0; i < jsonClients.length(); i++ ) {
            JSONObject jsonClient = jsonClients.getJSONObject( i );
            String name = jsonClient.getString( FIELD_NAME );
            String secretBase64 = jsonClient.getString( FIELD_SECRET );
            POClient po = new POClient( name, secretBase64 );
            if( jsonClient.optBoolean( FIELD_MANAGER, false ) )
                po.setManager( true );
            clients.put( name, po );
        }

        return new Config( javaConfig, clients );
    }


    public void write( final String _secretsFilePath ) {

        // create a JSON object that contains our persistent configuration information...
        JSONArray  jsonClients     = new JSONArray();
        Collection<POClient> clientSet = clients.values();
        for( POClient po : clientSet ) {
            JSONObject jsonClient = new JSONObject();
            jsonClient.put( FIELD_NAME, po.name );
            jsonClient.put( FIELD_SECRET, po.secretBase64 );
            jsonClient.put( FIELD_MANAGER, po.manager );
            jsonClients.put( jsonClient );
        }

        // then stringify it and write it to a file...
        Files.writeToFile( new File( _secretsFilePath ), jsonClients.toString() );
    }
}

import com.dilatush.mop.cpo.JavaConfig;
import com.dilatush.util.config.Configurator;
import com.dilatush.util.config.AConfig;
import com.dilatush.util.ip.IPv4Address;
import java.time.Duration;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.Map;
import java.util.List;
import java.util.Arrays;

/**
 * Configurator for the central post office running on Beast.
 */

public class CPOConfigurator implements Configurator {

    public void config( final AConfig _config ) {

        // set up our configuration object...
        JavaConfig config     = (JavaConfig) _config;
        config.name           = "Central Post Office";
        config.maxMessageSize = 25000;
        config.pingIntervalMS = 10000;
        config.port           = 4000;
        config.localAddress   = "0.0.0.0";
    }
}

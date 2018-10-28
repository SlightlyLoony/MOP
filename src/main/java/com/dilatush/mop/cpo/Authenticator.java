package com.dilatush.mop.cpo;

import com.dilatush.util.Checks;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

/**
 * Produces an authentication token from a shared secret, the post office name, and a message ID.  The resulting token can either be used by a client
 * post office to authenticate itself to the central post office, or by the central post office to verify an authenticator it receives.
 *
 * @author Tom Dilatush  tom@dilatush.com
 */
public class Authenticator {

    private static final String SHA256_PROVIDER = "SHA-256";

    private byte[] authenticator;


    public Authenticator( final byte[] _secret, final String _poName, final String _id ) {

        Checks.required( (Object) _secret );
        Checks.notEmpty( _poName, _id );

        try {
            MessageDigest digest = MessageDigest.getInstance( SHA256_PROVIDER );
            digest.update( _secret );
            digest.update( _poName.getBytes( StandardCharsets.UTF_8 ) );
            digest.update( _id.getBytes( StandardCharsets.UTF_8 ) );
            authenticator = digest.digest();
        }
        catch( NoSuchAlgorithmException _e ) {
            throw new IllegalStateException( "SHA-256 provider is not available" );
        }
    }


    public byte[] getAuthenticator() {
        return authenticator;
    }


    public boolean verify( final byte[] _authenticator ) {
        return Arrays.equals( authenticator, _authenticator );
    }
}

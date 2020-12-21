package com.dilatush.mop;

import com.dilatush.util.Base64;
import com.dilatush.util.General;
import com.dilatush.util.HJSONObject;
import com.dilatush.util.Strings;
import org.json.JSONObject;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.logging.Logger;

import static com.dilatush.util.Strings.isNonEmpty;

/**
 * Instances of this class represent unique messages with the MOP framework.  Instances are serializable to JSON and deserializable from JSON.  Note that
 * while messages are mutable, it is <i>not</i> permissable to modify a message instance once it has been sent.  Any such attempt will lead to unpredictable
 * behavior.<br><br>
 *
 * Instances of this class may be serialized for network transmission and deserialized from the network to be reinstantiated on the receiving side.
 * The format on the wire is a simple frame consisting of three parts, all UTF-encoded:
 * <ul>
 *     <li>Prefix: "[[[xxx]", where "xxx" is the base 64 encoded byte length of the contents</li>
 *     <li>Contents: a JSON string with the contents of the encoded instance</li>
 *     <li>Suffix: "]]"</li>
 * </ul>
 *
 * @author Tom Dilatush  tom@dilatush.com
 */
public class Message extends HJSONObject {

    private static final Logger LOGGER                 = Logger.getLogger( new Object(){}.getClass().getEnclosingClass().getCanonicalName());

    public final static String ENVELOPE_NAME = "-={([env])}=-";  // chosen to be descriptive AND exceedingly unlikely to suffer from collision...
    public final static String FROM_ATTR     = "from";
    public final static String TO_ATTR       = "to";
    public final static String TYPE_ATTR     = "type";
    public final static String ID_ATTR       = "id";
    public final static String REPLY_ATTR    = "reply";
    public final static String EXPECT_ATTR   = "expect";
    public final static String SECURE_DATA   = ENVELOPE_NAME + ".secure";

    public final String  from;
    public final String  fromPO;
    public final String  to;
    public final String  type;
    public final String  id;
    public final String  reply;
    public final boolean expect;
    public final String  majorType;


    /**
     * Creates a new instance of this class from the specified JSON string.  Throws an {@link IllegalArgumentException} if the JSON string is missing or
     * zero-length, or if the JSON is missing any required fields, and a {@link org.json.JSONException} if the string contains malformed JSON.  Note that
     * <i>what</i> fields are required depends on what type of message it is (direct or published, reply expected, etc.).
     *
     * @param _json The JSON string to create a message from.
     */
    public Message( final String _json ) {
        super( _json );

        // extract envelope attributes...
        JSONObject envelope = optJSONObject( ENVELOPE_NAME );
        if( General.isNull( envelope ) ) throw new IllegalArgumentException( "JSON does not contain an envelope" );
        from      = envelope.optString ( FROM_ATTR,   null  );
        to        = envelope.optString ( TO_ATTR,     null  );
        type      = envelope.optString ( TYPE_ATTR,   null  );
        id        = envelope.optString ( ID_ATTR,     null  );
        reply     = envelope.optString ( REPLY_ATTR,  null  );
        expect    = envelope.optBoolean( EXPECT_ATTR, false );
        majorType = getPrefix( type );
        fromPO    = getPrefix( from );

        validate( from, type, id );
    }


    // Creates a new instance of this class from the specified parameters after validating them.  Throws a IllegalArgumentException on a validation error.
    public Message( final String _from, final String _to, final String _type, final String _id, final String _reply, final boolean _expect ) {

        // validate...
        validate( _from, _type, _id );

        from      = _from;
        to        = _to;
        type      = _type;
        id        = _id;
        reply     = _reply;
        expect    = _expect;
        majorType = getPrefix( type );
        fromPO    = getPrefix( from );

        // stuff our envelope attributes...
        JSONObject envelope = new JSONObject();
        envelope.put    ( FROM_ATTR, from   );
        envelope.putOpt ( TO_ATTR,   to     );
        envelope.putOpt ( TYPE_ATTR, type   );
        envelope.put    ( ID_ATTR,   id     );
        envelope.putOpt ( REPLY_ATTR, reply );
        if( expect )
            envelope.put( EXPECT_ATTR, true );
        put( ENVELOPE_NAME, envelope );
    }


    private static String getPrefix( final String _dottedValue ) {
        if( Strings.isEmpty( _dottedValue ) ) return "";
        if( !_dottedValue.contains( "." ) ) return _dottedValue;
        return _dottedValue.substring( 0, _dottedValue.lastIndexOf( '.' ) );
    }


    /**
     * Returns true if this message contains encrypted data.
     *
     * @return true if this message contains encrypted data.
     */
    public boolean isEncrypted() {
        return hasDotted( SECURE_DATA );
    }


    /**
     * Decrypts any encrypted fields with _secretFrom, then re-encrypts them with _secretTo.  This is used by the central post office to decrypt a
     * message coming from one post office and re-encrypting it for the next post office.
     *
     * @param _secretFrom the secret shared between the "from" post office and the central post office.
     * @param _secretTo the secret shared between the "to" post office and the central post office.
     */
    public void reEncrypt( final byte[] _secretFrom, final byte[] _secretTo ) {

        // if we have no encrypted data, just skedaddle...
        if( !isEncrypted() ) return;

        try {

            // first thing we do is get our encrypted bytes...
            byte[] encrypted = Base64.decodeBytes( getStringDotted( SECURE_DATA ) );

            // then we decrypt them...
            Cipher cipher = Cipher.getInstance( "AES/CBC/PKCS5Padding" );
            cipher.init( Cipher.DECRYPT_MODE, getKey( _secretFrom ), getInitializationVector() );
            byte[] data = cipher.doFinal( encrypted );

            // and re-encrypt them...
            cipher.init( Cipher.ENCRYPT_MODE, getKey( _secretTo ), getInitializationVector() );
            byte[] secureBytes = cipher.doFinal( data );
            String secureString = Base64.encode( secureBytes );

            // and stuff 'em back in...
            putDotted( SECURE_DATA, secureString );
        }
        catch( Exception _e ) {
            throw new IllegalStateException( "re-encryption problem", _e );
        }

    }


    /**
     * Encrypts the specified fields, using the specified secret (which is shared with the central post office).  The specified fields are
     * removed from the message, and the encrypted version stored in the envelope, in a field called "secure".  The encryption key is constructed from
     * the shared secret, the message ID, and the from address.  If no fields are specified, or if a field is specified that is not actually present
     * in the message, an {@link IllegalArgumentException} will be thrown.
     *
     * @param _secret the secret shared with the central post office.
     * @param _fields the fields to be encrypted (dotted hierarchical form is ok).
     */
    public void encrypt( final byte[] _secret, final String... _fields ) {

        // sanity checks...
        if( null == _secret ) throw new IllegalArgumentException( "Missing secret" );
        if( (null == _fields) || (_fields.length == 0) ) throw new IllegalArgumentException( "Missing fields to encrypt" );

        // first we remove the specified fields and put them into a new object, in the same hierarchy...
        HJSONObject holder = new HJSONObject();
        for( String field : _fields ) {

            // if we don't have the specified field, it's a problem...
            if( !hasDotted( field ) ) throw new IllegalArgumentException( "Missing specified field: " + field );

            // move the field...
            holder.putDotted( field, removeDotted( field ) );
        }

        // now we turn it into bytes (UTF-8 encoded JSON)...
        byte[] data = holder.toString().getBytes( StandardCharsets.UTF_8 );

        // at last it's time to actually encrypt...
        String secureString;
        try {
            Cipher cipher = Cipher.getInstance( "AES/CBC/PKCS5Padding" );
            cipher.init( Cipher.ENCRYPT_MODE, getKey( _secret ), getInitializationVector() );
            byte[] secureBytes = cipher.doFinal( data );
            secureString = Base64.encode( secureBytes );
        }
        catch( Exception _e ) {
            throw new IllegalStateException( "Encryption problem", _e );
        }

        // stuff it away and we're done...
        putDotted( SECURE_DATA, secureString );
    }


    /**
     * Decrypts the secure data found in this message, if any, and restores the data as it was prior to encryption.
     *
     * @param _secret the secret shared with the central post office.
     */
    public void decrypt( final byte[] _secret ) {

        // if there is no secure data field, just leave...
        if( !hasDotted( SECURE_DATA ) )
            return;

        // first thing we do is get our encrypted bytes...
        byte[] encrypted = Base64.decodeBytes( getStringDotted( SECURE_DATA ) );

        // then we decrypt them...
        byte[] jsonBytes;
        try {
            Cipher cipher = Cipher.getInstance( "AES/CBC/PKCS5Padding" );
            cipher.init( Cipher.DECRYPT_MODE, getKey( _secret ), getInitializationVector() );
            jsonBytes = cipher.doFinal( encrypted );
        }
        catch( Exception _e ) {
            throw new IllegalStateException( "Decryption problem", _e );
        }

        // now turn them back into JSON and get our holding object...
        JSONObject holder = new JSONObject( new String( jsonBytes, StandardCharsets.UTF_8) );

        // remove the secure data...
        removeDotted( SECURE_DATA );

        // add all the values in our holder to this message...
        mover( holder, "" );
    }


    // recursive depth-first JSONObject traverser.
    private void mover( final JSONObject _currentObject, final String _currentPath ) {

        for( String key : _currentObject.keySet() ) {

            Object value = _currentObject.get( key );
            if( value instanceof JSONObject )
                mover( (JSONObject) value, makeKey( _currentPath, key ) );
            else
                putDotted( makeKey( _currentPath, key ), value );
        }
    }


    private String makeKey( final String _currentPath, final String _element ) {
        if( "".equals( _currentPath ) ) return _element;
        return _currentPath + "." + _element;
    }


    // we generate our key from a hash of our secret, the ID, and the from address...
    private SecretKeySpec getKey( final byte[] _secret ) {

        try {

            // first we get our 32 bytes of hash...
            MessageDigest digest = MessageDigest.getInstance( "SHA-256" );
            digest.update( _secret );
            digest.update( from.getBytes( StandardCharsets.UTF_8 ) );
            digest.update( id.getBytes( StandardCharsets.UTF_8 ) );
            byte[] hash = digest.digest();

            // now we make these bytes into a key...
            return new SecretKeySpec( hash, "AES" );
        }
        catch( NoSuchAlgorithmException _e ) {
            throw new IllegalStateException( "SHA-256 provider is not available" );
        }
    }


    // we generate an IV from a hash of the ID and the from address...
    private IvParameterSpec getInitializationVector() {

        try {

            // first we get our 32 bytes of hash...
            MessageDigest digest = MessageDigest.getInstance( "SHA-256" );
            digest.update( from.getBytes( StandardCharsets.UTF_8 ) );
            digest.update( id.getBytes( StandardCharsets.UTF_8 ) );
            byte[] hash = digest.digest();

            // now we xor the first 16 bytes with the second 16 bytes to make our 128 bit (16 byte) IV...
            byte[] iv = new byte[16];
            for( int i = 0; i < 16; i++ )
                iv[i] = (byte)(hash[i] ^ hash[i + 16]);

            return new IvParameterSpec( iv );
        }
        catch( NoSuchAlgorithmException _e ) {
            throw new IllegalStateException( "SHA-256 provider is not available" );
        }
    }


    /**
     * Returns true if this instance represents a direct message.
     *
     * @return true if this instance represents a direct message.
     */
    public boolean isDirect() {
        return isNonEmpty( to );
    }


    /**
     * Returns true if this instance represents a publish message.
     *
     * @return true if this instance represents a publish message.
     */
    public boolean isPublish() {
        return !isDirect();
    }


    /**
     * Returns true if this instance represents a reply message.
     *
     * @return true if this instance represents a reply message.
     */
    public boolean isReply() {
        return isDirect() && isNonEmpty( reply );
    }


    /**
     * Returns true if a reply is expected to this message.
     *
     * @return true if a reply is expected to this message.
     */
    public boolean isReplyExpected() {
        return expect;
    }


    /**
     * Creates a new instance of this class that represents a direct message.  This method should be called only by the associated {@link Mailbox} instance.
     * Throws an {@link IllegalArgumentException} if any of the specified parameters are invalid.
     *
     * @param _to the mailbox ID of the mailbox the message is being sent to.
     * @param _type the optional message type, which in the case of a direct message may be any string.
     * @param _expect true if a reply to this message is expected.
     * @param _from the mailbox ID of the mailbox the message is being sent from.
     * @param _id the unique ID of this message.
     * @return the newly created message instance.
     */
    /* package */ static Message createDirectMessage( final String _to, final String _type, final boolean _expect, final String _from, final String _id ) {
        return new Message( _from, _to, _type, _id, null, _expect );
    }


    /**
     * Creates a new instance of this class that represents a direct message.  This method should be called only by the associated {@link Mailbox} instance.
     * Throws an {@link IllegalArgumentException} if any of the specified parameters are invalid.
     *
     * @param _message the message being replied to.
     * @param _type the optional message type, which in the case of a direct message may be any string.
     * @param _id the unique ID of this message.
     * @return the newly created message instance.
     */
    /* package */ static Message createReplyMessage( final Message _message, final String _type, final String _id ) {
        return new Message( _message.to, _message.from, _type, _id, _message.id, false );
    }


    /**
     * Creates a new instance of this class that represents a publish message.  This method should be called only by the associated {@link Mailbox} instance.
     * Throws an {@link IllegalArgumentException} if any of the specified parameters are invalid.
     *
     * @param _type the type (major and minor type) of this message.
     * @param _from the mailbox ID of the mailbox this message is being sent from.
     * @param _id the unique ID of this message.
     * @return the newly created message instance.
     */
    /* package */ static Message createPublishMessage( final String _type, final String _from, final String _id ) {
        return new Message( _from, null, _type, _id, null, false );
    }


    /**
     * Returns a JSON string representing the contents of this message and its envelope.
     *
     * @return the JSON string representing the contents of this message and its envelope.
     */
    public String toJSON() {
        return super.toString();
    }


    /**
     * Return the JSON string as the representative string.
     *
     * @return the JSON string as the representative string.
     */
    public String toString() {
        return toJSON();
    }


    /**
     * Returns a byte array containing the UTF-8 encoded serialized bytes representing this instance.  See class comment for a description of the
     * serialization format.
     *
     * @return a byte array containing the UTF-8 encoded serialized bytes representing this instance.
     */
    public byte[] serialize() {

        // first we get our contents into bytes...
        byte[] contents = toJSON().getBytes( StandardCharsets.UTF_8 );

        // now we construct our prefix...
        byte[] prefix = ("[[[" + Base64.encode( contents.length ) + "]").getBytes( StandardCharsets.UTF_8 );

        // and finally our suffix...
        byte[] suffix = "]]".getBytes( StandardCharsets.UTF_8 );

        // then concatenate them and we're done...
        byte[] result = new byte[ contents.length + prefix.length + suffix.length ];
        System.arraycopy( prefix, 0, result, 0, prefix.length );
        System.arraycopy( contents, 0, result, prefix.length, contents.length );
        System.arraycopy( suffix, 0, result, prefix.length + contents.length, suffix.length );
        return result;
    }


    // Throws an IllegalArgumentException if the specified parameters are invalid; otherwise, does nothing.
    private void validate( final String _from, final String _type, final String _id ) {
        if( Strings.isEmpty( _from ) )                             throw new IllegalArgumentException( "Message missing valid 'from' attribute" );
        if( Strings.isEmpty( _from ) && Strings.isEmpty( _type ) ) throw new IllegalArgumentException( "Message missing both 'to' and 'type' attributes" );
        if( Strings.isEmpty( _id ) )                               throw new IllegalArgumentException( "Message missing valid 'id' attribute" );
    }
}

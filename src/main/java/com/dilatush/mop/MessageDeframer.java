package com.dilatush.mop;

import com.dilatush.util.Base64;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import static com.dilatush.util.General.getLogger;
import static com.dilatush.util.General.isNull;

/**
 * Instances of this class accept batches of bytes (typically read from the network) and identify frames that contain serialized messages.  These
 * identified frames can be retrieved either as byte arrays or as deserialized messages.
 * <br>
 * Instances of this class are mutable and <i>not</i> threadsafe.
 *
 * @author Tom Dilatush  tom@dilatush.com
 */
public class MessageDeframer {

    @SuppressWarnings( "unused" )
    private static final Logger LOGGER                 = getLogger();

    private static final byte OPEN = '[';
    private static final byte CLOSE = ']';


    private int maxMessageSize;
    private ByteBuffer buffer;    // note 1...
    private boolean frameOpenDetected;
    private int frameLength;


    // Notes:
    // 1.  The position of this byte buffer is the position of the next character to be scanned, and the limit is the next character to be added.
    //     Therefore, limit-position gives the number of bytes left to be processed, capacity-limit the number of bytes that could be added.  Inside
    //     any method of this class, those uses might be different, but between calls the preceding must hold.


    public MessageDeframer( final AtomicInteger _maxMessageSize ) {
        maxMessageSize = _maxMessageSize.get();
        buffer = allocateBuffer();
        frameOpenDetected = false;
        frameLength = 0;
        buffer.limit( 0 );  // this marks our buffer as empty...
    }


    /**
     * Clears all bytes from the buffer, as if just created.
     */
    @SuppressWarnings( "unused" )
    private void clear() {
        frameOpenDetected = false;
        frameLength = 0;
        buffer.limit( 0 );  // this marks our buffer as empty...
    }


    /**
     * Changes the internal buffer size to accommodate the specified maximum message size.  The new maximum message size may only be larger than the
     * current maximum message size, or no action will be taken
     *
     * @param _newMaxMessageSize the new maximum message size.
     */
    public void resize( final AtomicInteger _newMaxMessageSize ) {

        // if we're asking for a smaller (or same) sized buffer, just leave...
        int newMaxMessageSize = _newMaxMessageSize.get();
        if( newMaxMessageSize <= maxMessageSize ) return;

        // allocate new buffer of the right size...
        maxMessageSize = newMaxMessageSize;
        ByteBuffer newBuffer = allocateBuffer();

        // copy any bytes we already have into the new one...
        newBuffer.put( buffer );
        newBuffer.flip();
        buffer = newBuffer;
    }


    private ByteBuffer allocateBuffer() {
        int bufferSize = 5 * (maxMessageSize + 6 + 4);  // if this is smaller than the biggest possible read buffer, we have a problem...
        return ByteBuffer.allocate( bufferSize );
    }


    /**
     * Appends bytes in the specified buffer to this instance.  As many bytes as this instance has the capacity to add are appended.  Upon return,
     * the position is the next byte remaining in the specified buffer, and limit - position is the number of bytes that were not added.
     *
     * @param _buffer the bytes to append to this instance.
     */
    public void addBytes( final ByteBuffer _buffer ) {

        // sanity checks...
        if( isNull( _buffer ) )
            throw new IllegalArgumentException( "Missing buffer to append from" );
        if( _buffer.remaining() <= 0)
            throw new IllegalArgumentException( "Specified buffer has no bytes to append" );

        // figure out how many bytes we can append, and configure the source buffer accordingly...
        int appendCount = Math.min( buffer.capacity() - buffer.limit(), _buffer.remaining() );
        int specifiedBufferLimit = _buffer.limit();
        _buffer.limit( _buffer.position() + appendCount );

        // remember our old position, so we can put it back later...
        int pos = buffer.position();

        // setup to copy our bytes to the right place...
        buffer.position( buffer.limit() );
        buffer.limit( buffer.capacity() );

        // copy our bytes...
        buffer.put( _buffer );

        // get the specified buffer's limit back to its original state...
        _buffer.limit( specifiedBufferLimit );

        // get our position and limit to the right place...
        buffer.limit( buffer.position() );
        buffer.position( pos );
    }


    /**
     * Appends bytes in the specified byte array to this instance, returning the number actually appended.  As many bytes as this instance has the
     * capacity to add are appended.
     *
     * @param _bytes The byte array containing the bytes to append to this instance
     * @param _offset The offset into the byte array to start appending from.
     * @param _length The length of the bytes to append.
     * @return the number of bytes actually appended
     */
    public int addBytes( final byte[] _bytes, final int _offset, final int _length ) {

        // sanity checks...
        if( _bytes == null )
            throw new IllegalArgumentException( "Missing bytes to append" );
        if( _length - _offset <= 0 )
            throw new IllegalArgumentException( "Specified buffer has no bytes to append" );

        // figure out how many bytes we can append...
        int appendCount = Math.min( buffer.capacity() - buffer.limit(), _length - _offset );

        // remember our old position, so we can put it back later...
        int pos = buffer.position();

        // setup to copy our bytes to the right place...
        buffer.position( buffer.limit() );
        buffer.limit( buffer.capacity() );

        // copy our bytes...
        buffer.put( _bytes, _offset, appendCount );

        // get our source and limit to the right place...
        buffer.limit( buffer.position() );
        buffer.position( pos );

        return appendCount;
    }


    /**
     * Returns the next frame that is fully contained within this instance, or {@code null} if there are no frames available.  If a frame is returned,
     * its bytes are removed from this instance.
     *
     * @return the next frame that is fully contained within this instance, or {@code null} if there are no frames available.
     */
    public byte[] getFrame() {

        // On entry to this function, this instance is in one of two states: either all bytes starting from the current position are unprocessed, or
        // the current position is the first byte of a frame (immediately following a previously detected frame open).  The frameOpenDetected variable
        // records this state.  The length of a detected frame is in frameLength.

        // loop until either we find a frame or we run out of bytes to scan...
        byte[] frame = null;
        do {
            // if we haven't already detected a frame, let's see if we can find the open...
            if( !frameOpenDetected ) scanForFrameOpen();

            // if we couldn't find an open, or we did but don't have all the bytes for the frame, bail out after (possibly) compacting...
            if( !frameOpenDetected || ((buffer.limit() - buffer.position()) < frameLength + 2) ) {
                compactIfNeeded();
                return null;
            }

            // getting here means we have a frame open and all the bytes for the frame...
            // if we have a proper close, return the frame bytes after (possibly) compacting...
            if( (CLOSE == buffer.get( buffer.position() + frameLength )) && (CLOSE == buffer.get( buffer.position() + frameLength + 1 )) ) {
                frame = new byte[frameLength];
                buffer.get( frame );
                buffer.position( buffer.position() + 2 );  // get past the close...
                compactIfNeeded();
                frameOpenDetected = false;
            }
        }
        while( frame == null );

        return frame;
    }


    // Compacts the internal buffer if more than 25% of it has been scanned and now contains useless bytes.
    private void compactIfNeeded() {

        // if the scan point is more than 25% into the buffer, compact...
        if( buffer.position() >= (buffer.capacity() >> 2 ) ) {
            int newLimit = buffer.limit() - buffer.position();
            buffer.compact();
            buffer.position( 0 );
            buffer.limit( newLimit );
        }
    }


    // Scan the bytes in this instance until we find a complete frame, the start of a frame, or determine that there's naught to be found.
    // frameOpenDetected is set true, and frameLength is set if a complete open sequence is detected.
    private void scanForFrameOpen() {

        // scan until we find a frame or run out of bytes to scan...
        boolean done = !buffer.hasRemaining();
        while( !done ) {

            // scan until we find a valid beginning-of-frame ("[[["
            // done is set true if there are no more bytes to scan
            // otherwise the position is at the byte following the "[[[" we found
            done = scanFrameOpen();

            // if we scanned all our bytes, get outta here...
            if( done ) continue;

            // scan the frame length characters ("xxx]", where "xxx" is 2..4 base 64 characters...
            // frameOpenDetected is set true, and frameLength is set, if a complete open frame sequence is detected
            // done is set true if there are no more bytes to scan
            done = scanFrameLength();

            // if we get here without detecting a frame, it's possible that we need to rescan after adding more bytes, so reset our position...
            if( done && !frameOpenDetected ) buffer.reset();
        }
    }


    // Scan the bytes in this instance until we find "[[[" or run out of bytes to scan.
    private boolean scanFrameOpen() {

        // scan until we find the three sequential opens...
        int openCount = 0;
        boolean done = false;
        while( !done & (openCount < 3) ) {
            if( OPEN == buffer.get() ) {
                openCount++;
                if( openCount == 1 ) buffer.mark();  // remember where this started, in case we have to rewind to here...
            }
            else openCount = 0;
            done = !buffer.hasRemaining();
        }

        // if we ran out of characters, go back to our marked position (if we have one) so we can rescan it after bytes are added...
        if( done ) if( openCount > 0 ) buffer.reset();

        return done;
    }


    // Scan the base 64 encoded frame length and the ] termination character.
    // return true if there are no more bytes to scan
    // frameOpenDetected is set true, and frameLength is set, if a full frame open is detected.
    private boolean scanFrameLength() {

        // scan the 2, 3, or 4 characters of the base 64 encoded frame length, terminated by a close...
        StringBuilder frameLengthChars = new StringBuilder( 4 );
        boolean lengthDone = false;
        boolean done = false;
        while( !done & !lengthDone ) {

            byte thisByte = buffer.get();
            done = !buffer.hasRemaining();

            // if we just scanned a close character, then we need to test for validity of the length...
            if( thisByte == CLOSE ) {

                // if we have at least enough characters, we have a possible winner here...
                if( frameLengthChars.length() >= 2 ) {
                    frameLength = (int) Base64.decodeLong( frameLengthChars.toString() );

                    // if the frame is longer than the maximum, reject it...
                    if( frameLength > maxMessageSize ) lengthDone = true;

                    // otherwise, we DID get a winner...
                    else {
                        frameOpenDetected = true;
                        done = true;
                    }
                }

                // it's not a properly formed frame start, so time to keep scanning if we have any characters left...
                else lengthDone = true;
            }

            // if we just scanned a base 64 character, it's potentially part of the length...
            else if( Base64.isValidBase64Char( (char) thisByte ) ){

                // if we still could use more base 64 characters, just add it and get out of here...
                if( frameLengthChars.length() < 4 ) {
                    frameLengthChars.append( (char) thisByte );
                }

                // if this would be the fifth base 64 character, then it's not a properly formed frame...
                else lengthDone = true;
            }

            // if we got some other character, then it's not a properly formed frame...
            else {
                buffer.position( buffer.position() - 1 );  // it's possible we just got another open, so back up just in case...
                lengthDone = true;
            }
        }
        return done;
    }


    /**
     * Returns the number of bytes that can be safely added to this instance before getting frames.
     *
     * @return the number of bytes that can be safely added to this instance before getting frames.
     */
    @SuppressWarnings( "unused" )
    public int capacity() {
        return buffer.capacity() - buffer.limit();
    }
}

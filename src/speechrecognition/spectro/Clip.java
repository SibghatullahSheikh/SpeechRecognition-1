/*
 * Created on Jul 8, 2008
 * 
 * Adopted from Spectro-Edit.
 *
 * Spectro-Edit is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 3 of the License, or
 * (at your option) any later version.
 *
 * Spectro-Edit is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>. 
 */
package speechrecognition.spectro;

//import java.awt.Rectangle;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.UnsupportedAudioFileException;
//import javax.swing.event.UndoableEditListener;
//import javax.swing.undo.UndoableEditSupport;

/**
 * A Clip represents an audio clip of some length. The clip is split up
 * into a series of equal-size frames of spectral information.  The frames
 * of spectral information can be accessed in random order, and the clip
 * can also provide an AudioInputStream of the current spectral information
 * for playback or saving to a traditional PCM (WAV or AIFF) audio file.
 */
public class Clip {

    private static final Logger logger = Logger.getLogger(Clip.class.getName());

    /**
     * The audio format this class works with. Input audio will be converted to this
     * format automatically, and output data will always be created in this format.
     */
    private static final AudioFormat AUDIO_FORMAT = new AudioFormat(16000, 16, 1, true, true);

    public static final int DEFAULT_FRAME_SIZE = 2048;
    private static final int DEFAULT_OVERLAP = 8;
    
    protected final List<Frame> frames = new ArrayList<Frame>();
    
    /**
     * Number of samples per frame. Currently must be a power of 2 (this is a requirement
     * of many DFT routines).
     */
    private final int frameSize;
    
    /**
     * The amount of overlap: this is the number of frames that will carry information
     * about the same sample. A value of 1 means no overlap; 2 means frames will overlap
     * to cover every sample twice, and so on.  More overlap means better time resolution.
     */
    private final int overlap;
    
    /**
     * The amount that the time samples are divided by before sending to the transformation,
     * and the amount they're multiplied after being transformed back.
     */
    private double spectralScale = 10000.0;

//    private final UndoableEditSupport undoEventSupport = new UndoableEditSupport();

    private final String name;
    
    /**
     * Creates a new Clip based on the acoustical information in the given audio
     * file.
     * <p>
     * TODO: this could be time-consuming, so spectral conversion should be done
     * in a background thread.
     * 
     * @param file
     *            The audio file to read. Currently, single-channel WAV and AIFF
     *            are supported.
     * @throws UnsupportedAudioFileException
     *             If the given file can't be read because it's not of a
     *             supported type.
     * @throws IOException
     *             If the file can't be read for more basic reasons, such
     *             as nonexistence.
     */
    public static Clip newInstance(File file) throws UnsupportedAudioFileException, IOException {
        AudioFormat desiredFormat = AUDIO_FORMAT;
        BufferedInputStream in = new BufferedInputStream(AudioFileUtils.readAsMono(desiredFormat, file));
        return new Clip(file.getAbsolutePath(), in, DEFAULT_FRAME_SIZE, DEFAULT_OVERLAP);
    }
    
    /**
     * Creates a new clip from the audio data in the given input stream.
     * 
     * @param name The name of this clip. Could be the file name it was read from, or
     * something supplied by the user.
     * @param in The audio data to read. Must have the following characteristics:
     * <ul>
     *  <li>Contains bytes in the format that an AudioInputStream in the format
     *      specified by {@link #AUDIO_FORMAT}
     *  <li>Supports mark() and reset(). (This can be accomplished by wrapping
     *      your stream in a BufferedInputStream)
     * </ul>
     * @throws IOException If reading the input stream fails for any reason. 
     */
    private Clip(String name, InputStream in, int frameSize, int overlap) throws IOException {
        this.name = name;
        this.frameSize = frameSize;
        this.overlap = overlap;
        WindowFunction windowFunc = new VorbisWindowFunction(frameSize);
        byte[] buf = new byte[frameSize * 2]; // 16-bit mono samples
        int n;
        in.mark(buf.length * 2);
        while ( (n = readFully(in, buf)) != -1) {
            logger.finest("Read "+n+" bytes");
            if (n != buf.length) {
                // this should only happen at the end of the input file (last frame)
                logger.warning("Only read "+n+" of "+buf.length+" bytes at frame " + frames.size());
                
                // pad with silence or there will be audible junk at end of clip
                for (int i = n; i < buf.length; i++) {
                    buf[i] = 0;
                }
            }
            double[] samples = new double[frameSize];
            for (int i = 0; i < frameSize; i++) {
                int hi = buf[2*i];// & 0xff; // need sign extension
                int low = buf[2*i + 1] & 0xff;
                int sampVal = ( (hi << 8) | low);
                samples[i] = (sampVal / spectralScale);
            }
            
            frames.add(new Frame(samples, windowFunc));
            in.reset();
            long bytesToSkip = (frameSize * 2) / overlap;
            long bytesSkipped;
            if ( (bytesSkipped = in.skip(bytesToSkip)) != bytesToSkip) {
                logger.info("Skipped " + bytesSkipped + " bytes, but wanted " + bytesToSkip + " at frame " + frames.size());
            }
            in.mark(buf.length * 2);
        }
        
        logger.info(String.format("Read %d frames from %s (%d bytes). frameSize=%d overlap=%d\n", frames.size(), name, frames.size() * buf.length, frameSize, overlap));
    }
    
    /**
     * Fills the given buffer by reading the given input stream repeatedly
     * until the buffer is full. The only conditions that will prevent buf
     * from being filled by the time this method returns are if the input
     * stream indicates an EOF condition or an IO error occurs.
     * 
     * @param in The input stream to read
     * @param buf The buffer to fill with bytes from the input stream
     * @return The number of bytes actually read into buf
     * @throws IOException If an IO error occurs
     */
    private int readFully(InputStream in, byte[] buf) throws IOException {
        int offset = 0;
        int length = buf.length;
        int bytesRead = 0;
        while ( (offset < buf.length) && ((bytesRead = in.read(buf, offset, length)) != -1) ) {
            logger.finest("read " + bytesRead + " bytes at offset " + offset);
            length -= bytesRead;
            offset += bytesRead;
        }
        if (offset > 0) {
            logger.fine("Returning " + offset + " bytes read into buf");
            return offset;
        } else {
            logger.fine("Returning EOF");
            return -1;
        }
    }
    
    public String getName() {
        return name;    
    }
    /**
     * Returns the number of time samples per frame.
     */
    public int getFrameTimeSamples() {
        return frameSize;
    }

    /**
     * Returns the number of frequency samples per frame.
     */
    public int getFrameFreqSamples() {
        return frameSize;
    }

    /**
     * Returns the number of frames.
     * @return
     */
    public int getFrameCount() {
        return frames.size();
    }
    
    /**
     * Returns the <i>i</i>th frame.
     * 
     * @param i The frame number--frame numbering starts with 0.
     * @return The <i>i</i>th frame. The returned frame is mutable; modifying
     * its data permanently alters the acoustic qualities of this clip.
     */
    public Frame getFrame(int i) {
        return frames.get(i);
    }
    
    /**
     * Returns the number of frames that overlap to produce any given time sample.
     * An overlap of at least 2 is required in order to produce a click-free result
     * after modifying the specral information. Larger values give better time
     * resolution at the cost of a linear increase in memory and CPU consumption.
     */
    public int getOverlap() {
        return overlap;
    }

    /**
     * Returns the audio data in this clip as an AudioInputStream.
     * @return
     */
    public AudioInputStream getAudio() {
        return getAudio(0);
    }
    
    public AudioInputStream getAudio(int sample) {
        return getAudio(sample, Integer.MAX_VALUE);
    }

    /**
     * Returns the time-domain audio data for some or all of this clip.
     * 
     * @param sample
     *            The starting sample position for the returned audio stream.
     * @param length
     *            The maximum number of samples to include in the audio stream.
     *            If this length argument specifies a sample position past the
     *            end of the clip, it will be ignored (extra padding will not be
     *            added).
     * @return an audio stream in the AUDIO_FORMAT format that begins with the
     *         sound at the given start sample and ends either at the natural
     *         end of this clip, or after returning <code>length</code> samples.
     */
    public AudioInputStream getAudio(int sample, int length) {
        // TODO prefill overlap buffer with previous frame's data
        // TODO calculate sample offset into the initial frame
        final int initialFrame = sample / getFrameTimeSamples();
        
        InputStream audioData = new InputStream() {

            /**
             * Next frame to decode for playback.
             */
            int nextFrame = initialFrame;
            
            /**
             * A data structure that holds all the current frames of floating point samples
             * and performs the overlap-and-combine operation for us.
             */
            OverlapBuffer overlapBuffer = new OverlapBuffer(frameSize, overlap);
            
            /**
             * The current sample data. Only the lower 16 bits are significant.
             */
            int currentSample;
            
            /**
             * Flag to indicate if the current byte being read from the input stream
             * is the high byte or the low byte of a single 16-bit sample.
             */
            boolean currentByteHigh = true;
            
            int emptyFrameCount = 0;

            @Override
            public int available() throws IOException {
                return Integer.MAX_VALUE;
            }
            
            @Override
            public int read() throws IOException {
                if (overlapBuffer.needsNewFrame()) {
                    if (nextFrame < frames.size()) {
                        Frame f = frames.get(nextFrame++);
                        overlapBuffer.addFrame(f.asTimeData());
                    } else {
                        overlapBuffer.addEmptyFrame();
                        emptyFrameCount++;
                    }
                }
                
                if (emptyFrameCount >= overlap) {
                    return -1;
                } else if (currentByteHigh) {
                    currentSample = (int) (overlapBuffer.next() * spectralScale);
                    currentByteHigh = false;
                    return (currentSample >> 8) & 0xff;
                } else {
                    currentByteHigh = true;
                    return currentSample & 0xff;
                }
                
            }
            
        };
        int clipLength = getFrameCount() * getFrameTimeSamples() * (AUDIO_FORMAT.getSampleSizeInBits() / 8) / overlap;
        return new AudioInputStream(audioData, AUDIO_FORMAT, Math.min(length, clipLength));
    }

    public double getSamplingRate() {
        return AUDIO_FORMAT.getSampleRate();
    }

    /**
     * Creates a new Clip instance based on the given range of frames in the
     * current clip.
     * <p>
     * Currently, the audio data of the sub clip is independent of the clip it
     * was obtained from. This is not really a desirable situation (it would be
     * better if the underlying data were shared), so don't count on this
     * behaviour!
     * 
     * @param startFrame
     *            The first frame of this clip that should appear in the subclip
     * @param nFrames
     *            The number of frames of this clip that should appear in the subclip.
     * @param newFrameSize
     *            The frame size of the subclip. This determines the frequency
     *            resolution--larger frames give more vertical (frequency)
     *            resolution. Must be a power of two.
     * @param newOverlap
     *            The degree of overlap for frames in the subclip. Larger values
     *            give more horizontal (time) resolution.
     * @return
     */
    public Clip subClip(int startFrame, int nFrames, int newFrameSize, int newOverlap) {
        InputStream in = null;
        try {
            // decode existing
            in = new BufferedInputStream(getAudio(startFrame * frameSize, nFrames * frameSize));

            // create new clip with new settings
            Clip subClip = new Clip("Part of " + name, in, newFrameSize, newOverlap);
            return subClip;
        } catch (IOException ex) {
            AssertionError err = new AssertionError("Unexpected IO Exception during clip resampling");
            err.initCause(ex);
            throw err;
        } finally {
            try {
                if (in != null) in.close();
            } catch (IOException ex) {
                logger.log(Level.WARNING, "Failed to close input stream after creating subclip", ex);
            }
        }
    }

}

package me.guillaumin.android.osmtracker.gps;

import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;

import me.guillaumin.android.osmtracker.OSMTracker;

import android.os.SystemClock;
import android.util.Log;
import static junit.framework.Assert.*;


public abstract class GpsInputReader {

	// Debugging
    private static final String TAG = GpsInputReader.class.getSimpleName();
    private static final boolean D = OSMTracker.DEBUG;

    static final String NMEA_CHARSET = "ISO-8859-1";

    static final long READ_TIMEOUT_MS = 200;

	static final int LOOKS_NOT_LIKE_GPS_MSG = 0;
	static final int LOOKS_LIKE_TRUNCATED_MSG = -1;

	private InputStream in;
	private byte buf[];
	private int pos;

	public GpsInputReader(InputStream inputStream) {
		this(inputStream, 2048);
	}

	public GpsInputReader(InputStream in, final int size) {
		if (size <= 0) {
			throw new IllegalArgumentException("size <= 0");
		}
		this.in = in;
		buf = new byte[size];
		pos=0;
	}

	/**
	 * Called after read() with received data
	 *
	 * @param buf
	 * @param offset
	 * @param length
	 */
	protected abstract void onRawDataReceived(final byte[] buf, int offset, int length);

	/**
	 * Called for every fully received NMEA sentence
	 * @param nmea
	 */
	protected abstract void onNmeaReceived(String nmea);

	/**
	 * Called for every fully received SiRF message
	 * @param buf Input buffer
	 * @param offset Offset of received message
	 * @param length Message length
	 */
	protected abstract void onSirfReceived(final byte[] buf, int offset, int length);

	/**
	 * Called when input buffer is flushed out
	 */
	protected abstract void onBufferFlushed();

	/**
	 *
	 * @return LOOKS_NOT_LIKE_GPS_MSG - does not looks like NMEA message;
	 * @return LOOKS_LIKE_TRUNCATED_MSG - may be truncated NMEA message;
	 * @return >0 -  looks like NMEA message, returns message size, including <CR><LF>
	 * TODO: add tests
	 */
	final private int looksLikeNmea(final int startPos) {
		int p;
		int c;

		// Too short
		if (pos == startPos)
			return LOOKS_NOT_LIKE_GPS_MSG;

		p = startPos;

		// NMEA Starting character $
		if (buf[p++] != '$')
			return LOOKS_NOT_LIKE_GPS_MSG;

        /* 2-characters TalkerID and first 2 characters of message type ($GPxx, $PSRF) */
		for(; p<5+startPos; ++p) {
			if (p >= this.pos)
				return LOOKS_LIKE_TRUNCATED_MSG;
			c = buf[p];
			if( ! ((c >= 'a' && c <= 'z')
					|| (c >= 'A' && c <= 'Z')) )
				return LOOKS_NOT_LIKE_GPS_MSG;
		}
		if (D) assertEquals(5+startPos, p);

		/* Rest of message type and fields */
		for(;;++p) {
            if (p >= this.pos)
                return LOOKS_LIKE_TRUNCATED_MSG;
            c = buf[p];
            // <CR>
            if (c == '\r') {
                break;
             // Optional checksum (*XX<CR><LF>)
            }else if (c == '*') {
            	int i;
            	int tHi, tLo;
            	int providedCsum, ourCsum;

            	if (p+3 >= this.pos)
            		return LOOKS_LIKE_TRUNCATED_MSG;
            	if (buf[p+3] != '\r')
            		return LOOKS_NOT_LIKE_GPS_MSG;

            	tHi = Character.digit(buf[p+1], 16);
            	tLo = Character.digit(buf[p+2], 16);
            	if ( (tHi < 0) || (tLo < 0))
            		return LOOKS_NOT_LIKE_GPS_MSG;
            	providedCsum = ((tHi & 0x0f) << 4) | (tLo & 0x0f);
            	ourCsum = 0;
            	for(i=startPos+1; i<p; i++)
            		ourCsum = ourCsum ^ (buf[i] & 0x7f);
            	if (ourCsum != providedCsum) {
            		Log.d(TAG, String.format("NMEA Checksum mismatch. 0x%H != 0x%H", ourCsum, providedCsum));
            		return LOOKS_NOT_LIKE_GPS_MSG;
            	}
            	p += 3;
            	break;
            }
		} /* content */

		/* <CR><LF> */
		if (D) assertEquals('\r', buf[p]);
		if (++p >= pos)
			return LOOKS_LIKE_TRUNCATED_MSG;
		if (buf[p] != '\n')
			return LOOKS_NOT_LIKE_GPS_MSG;

		return p+1-startPos;
	}

	/**
	 *
	 * @return LOOKS_NOT_LIKE_GPS_MSG - does not looks like SiRF message;
	 * @return LOOKS_LIKE_TRUNCATED_MSG - may be truncated SiRF message;
	 * @return >0 -  looks like SiRF message, returns message size
	 * TODO: add tests
	 */
	final private int looksLikeSirf(final int startPos) {
		int ourCsum, providedCsum;
		int payloadSize;

		if (this.pos - startPos < 2)
			return LOOKS_LIKE_TRUNCATED_MSG;

		/* Start sequence 0xA0 0xA2 */
		if ( ((buf[startPos+0]&0xff) != 0xa0)  || ((buf[startPos+1]&0xff) != 0xa2))
			return LOOKS_NOT_LIKE_GPS_MSG;

		if (pos < startPos+8)
			return LOOKS_LIKE_TRUNCATED_MSG;

		/* Payload size */
		payloadSize = (((int)buf[startPos+2]&0xff) << 8) | ((int)buf[startPos+3]&0xff);
		if (payloadSize > 1023) {
			Log.d(TAG, String.format("Wrong payload size. %d > 1023", payloadSize));
			return LOOKS_NOT_LIKE_GPS_MSG;
		}

		/* Payload */
		if (startPos+payloadSize+8 > pos)
			return LOOKS_LIKE_TRUNCATED_MSG;

		/* Checksum */
		providedCsum = (((int)buf[startPos+4+payloadSize]&0xff) << 8) |
				((int)buf[startPos+4+payloadSize+1]&0xff);

		/* End sequence 0xB0 0xB3 */
		if ((buf[startPos+4+payloadSize+2]&0xff) != 0xb0
				|| (buf[startPos+4+payloadSize+3]&0xff) != 0xb3) {
			Log.d(TAG, String.format("Wrong SiRF end seq: %x %x",
					(buf[startPos+4+payloadSize+2]&0xff), (buf[startPos+4+payloadSize+3]&0xff) ));
			return LOOKS_NOT_LIKE_GPS_MSG;
		}

		ourCsum = sirfCsum(buf, startPos+4, payloadSize);

		if (ourCsum != providedCsum) {
			Log.d(TAG, String.format("SiRF checksum mismatch 0x%4H != 0x%4H",
					providedCsum, ourCsum));
			return LOOKS_NOT_LIKE_GPS_MSG;
		}

		return payloadSize + 8;
	}


	final private static int sirfCsum(final byte[] buf,
			final int startPos,
			final int payloadSize) {
		int i;
		int csum;
		int payloadEnd;

		csum=0;

		payloadEnd = startPos+payloadSize;
		for (i=startPos; i < payloadEnd; i++)
			csum = 0x7fff & (csum + (buf[i] & 0xff));

		return csum;
	}


	public void loop() throws IOException {
		int rcvd, rcvdTotal;
		int p;
		long tmout;
		int truncatedMsgPos, secondTruncatedMsgPos;

		mainloop: for(;;) {

			/* Read data */
			if (D) assertTrue(pos != buf.length);
			rcvdTotal = 0;
			tmout = SystemClock.uptimeMillis()+READ_TIMEOUT_MS;
			do {
				rcvd = in.read(buf, pos, buf.length - pos);
				if (rcvd < 0) {
					Log.d(TAG, String.format("read() = %d", rcvd));
					return;
				}
				rcvdTotal += rcvd;
				pos += rcvd;
				if (pos == buf.length) {
					break;
				}
				synchronized(this) {
					try {
						wait(50);
					} catch(InterruptedException ie) {
						break mainloop;
					}
				}
				/* No data for 50ms */
				if (in.available() == 0) {
					if (D) Log.v(TAG, "No data available for 50ms");
					break;
				}
			} while (SystemClock.uptimeMillis()<tmout);

			if (rcvdTotal > 0) onRawDataReceived(buf, pos-rcvdTotal, rcvdTotal);

			/* Handle all received messages */
			p=0;
			truncatedMsgPos = secondTruncatedMsgPos = -1;
			while(p<pos) {
				/* Check for NMEA message */
				if (buf[p] == '$') {
					int msgSize = looksLikeNmea(p);
					if (msgSize > 0) {
						/* NMEA message found */
						try {
							final String nmeaMsg = new String(buf, p, msgSize, NMEA_CHARSET);
							if (nmeaMsg.length() != 0) {
								onNmeaReceived(nmeaMsg);
								p += msgSize;
								truncatedMsgPos = secondTruncatedMsgPos = -1;
							}else {
								Log.d(TAG, "Conversion from NMEA_CHARSET failed");
								++p;
							}
						} catch (UnsupportedEncodingException uee) {
							Log.d(TAG, "Conversion from NMEA_CHARSET failed", uee);
							++p;
						}
					}else if (msgSize == LOOKS_LIKE_TRUNCATED_MSG) {
						if (truncatedMsgPos < 0) {
							truncatedMsgPos = p;
						} else if (secondTruncatedMsgPos < 0) {
							secondTruncatedMsgPos = p;
						}
						++p;
					}else {
						if (D) assertEquals(LOOKS_NOT_LIKE_GPS_MSG, msgSize);
						++p;
					}
				/* Check for SiRF message */
				}else if ((buf[p] & 0xff) == 0xa0) {
					int msgSize = looksLikeSirf(p);
					if (msgSize > 0) {
						/* SiRF message found */
						onSirfReceived(buf, p, msgSize);
						p += msgSize;
						truncatedMsgPos = secondTruncatedMsgPos = -1;
					}else if (msgSize == LOOKS_LIKE_TRUNCATED_MSG) {
						if (truncatedMsgPos < 0) {
							truncatedMsgPos = p;
						} else if (secondTruncatedMsgPos < 0) {
							secondTruncatedMsgPos = p;
						}
						++p;
					}else {
						if (D) assertEquals(LOOKS_NOT_LIKE_GPS_MSG, msgSize);
						++p;
					}
				}else {
					++p;
				}
			} /* while(p<pos) */

			/* Handle last possibly truncated GPS message */
			if ((truncatedMsgPos == 0) && (this.pos == this.buf.length)) {
				/* Buffer full */
				truncatedMsgPos = secondTruncatedMsgPos;
				if (truncatedMsgPos < 0) {
					Log.d(TAG, String.format("Skipped %d garbage bytes", (int)this.buf.length));
				}
			}
			if (truncatedMsgPos < 0) {
				this.pos = 0;
				onBufferFlushed();
			}else if (truncatedMsgPos != 0) {
				if (D) Log.v(TAG, "Received truncated message");
				System.arraycopy(buf, truncatedMsgPos, buf, 0,
						this.pos-truncatedMsgPos);
				this.pos -= truncatedMsgPos;
				truncatedMsgPos = secondTruncatedMsgPos = -1;
			}
		} /* for(;;) */
	}

}

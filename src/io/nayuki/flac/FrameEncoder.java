/* 
 * FLAC library (Java)
 * Copyright (c) Project Nayuki. All rights reserved.
 * https://www.nayuki.io/
 */

package io.nayuki.flac;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;


final class FrameEncoder {
	
	/*---- Fields ----*/
	
	private final int sampleOffset;
	private final int sampleDepth;
	private final int sampleRate;
	public final int blockSize;
	private final int channelAssignment;
	private int encodedBitLength;
	private SubframeEncoder[] subEncoders;
	
	
	
	/*---- Constructors ----*/
	
	public FrameEncoder(int sampleOffset, long[][] data, int sampleDepth, int sampleRate) {
		// Set fields
		this.sampleOffset = sampleOffset;
		this.sampleDepth = sampleDepth;
		this.sampleRate = sampleRate;
		this.blockSize = data[0].length;
		
		
		int numChannels = data.length;
		subEncoders = new SubframeEncoder[numChannels];
		if (numChannels != 2) {
			channelAssignment = numChannels - 1;
			for (int i = 0; i < subEncoders.length; i++)
				subEncoders[i] = SubframeEncoder.computeBest(data[i], sampleDepth);
		} else {  // Explore the 4 stereo encoding modes
			long[] left  = data[0];
			long[] right = data[1];
			long[] mid  = new long[blockSize];
			long[] side = new long[blockSize];
			for (int i = 0; i < blockSize; i++) {
				mid[i] = (left[i] + right[i]) >> 1;
				side[i] = left[i] - right[i];
			}
			SubframeEncoder leftEnc  = SubframeEncoder.computeBest(left , sampleDepth);
			SubframeEncoder rightEnc = SubframeEncoder.computeBest(right, sampleDepth);
			SubframeEncoder midEnc   = SubframeEncoder.computeBest(mid  , sampleDepth);
			SubframeEncoder sideEnc  = SubframeEncoder.computeBest(side , sampleDepth + 1);
			int leftSize  = leftEnc .getEncodedBitLength();
			int rightSize = rightEnc.getEncodedBitLength();
			int midSize   = midEnc  .getEncodedBitLength();
			int sideSize  = sideEnc .getEncodedBitLength();
			int mode1Size = leftSize + rightSize;
			int mode8Size = leftSize + sideSize;
			int mode9Size = rightSize + sideSize;
			int mode10Size = midSize + sideSize;
			int minimum = Math.min(Math.min(mode1Size, mode8Size), Math.min(mode9Size, mode10Size));
			if (mode1Size == minimum) {
				channelAssignment = 1;
				subEncoders[0] = leftEnc;
				subEncoders[1] = rightEnc;
			} else if (mode8Size == minimum) {
				channelAssignment = 8;
				subEncoders[0] = leftEnc;
				subEncoders[1] = sideEnc;
			} else if (mode9Size == minimum) {
				channelAssignment = 9;
				subEncoders[0] = sideEnc;
				subEncoders[1] = rightEnc;
			} else if (mode10Size == minimum) {
				channelAssignment = 10;
				subEncoders[0] = midEnc;
				subEncoders[1] = sideEnc;
			} else
				throw new AssertionError();
		}
		
		// Add up subframe sizes
		encodedBitLength = 0;
		for (SubframeEncoder enc : subEncoders)
			encodedBitLength += enc.getEncodedBitLength();
		
		// Count length of header (always in whole bytes)
		try {
			ByteArrayOutputStream bout = new ByteArrayOutputStream();
			try (BitOutputStream bitout = new BitOutputStream(bout)) {
				encodeHeader(data, bitout);
			}
			bout.close();
			encodedBitLength += bout.toByteArray().length * 8;
		} catch (IOException e) {
			throw new AssertionError(e);
		}
		
		// Count padding and footer
		encodedBitLength = (encodedBitLength + 7) / 8 * 8;  // Round up to nearest byte
		encodedBitLength += 16;  // CRC-16
	}
	
	
	
	/*---- Public methods ----*/
	
	public int getEncodedBitLength() {
		return encodedBitLength;
	}
	
	
	public void encode(long[][] data, BitOutputStream out) throws IOException {
		// Check arguments
		Objects.requireNonNull(data);
		Objects.requireNonNull(out);
		if (data[0].length != blockSize)
			throw new IllegalArgumentException();
		
		encodeHeader(data, out);
		if (0 <= channelAssignment && channelAssignment <= 7) {
			for (int i = 0; i < data.length; i++)
				subEncoders[i].encode(data[i], out);
		} else if (8 <= channelAssignment || channelAssignment <= 10) {
			long[] left  = data[0];
			long[] right = data[1];
			long[] mid  = new long[blockSize];
			long[] side = new long[blockSize];
			for (int i = 0; i < blockSize; i++) {
				mid[i] = (left[i] + right[i]) >> 1;
				side[i] = left[i] - right[i];
			}
			if (channelAssignment == 8) {
				subEncoders[0].encode(left, out);
				subEncoders[1].encode(side, out);
			} else if (channelAssignment == 9) {
				subEncoders[0].encode(side, out);
				subEncoders[1].encode(right, out);
			} else if (channelAssignment == 10) {
				subEncoders[0].encode(mid, out);
				subEncoders[1].encode(side, out);
			} else
				throw new AssertionError();
		} else
			throw new AssertionError();
		out.alignToByte();
		out.writeInt(16, out.getCrc16());
	}
	
	
	
	/*---- Private I/O methods ----*/
	
	private void encodeHeader(long[][] data, BitOutputStream out) throws IOException {
		out.resetCrcs();
		out.writeInt(14, 0x3FFE);  // Sync
		out.writeInt(1, 0);  // Reserved
		out.writeInt(1, 1);  // Blocking strategy
		
		int blockSizeCode = getBlockSizeCode(blockSize);
		out.writeInt(4, blockSizeCode);
		int sampleRateCode = getSampleRateCode(sampleRate);
		out.writeInt(4, sampleRateCode);
		
		out.writeInt(4, channelAssignment);
		out.writeInt(3, SAMPLE_DEPTH_CODES.get(sampleDepth));
		out.writeInt(1, 0);  // Reserved
		
		// Variable-length: 1 to 7 bytes
		writeUtf8Integer(sampleOffset, out);  // Sample position
		
		// Variable-length: 0/8/16 bits
		if (blockSizeCode == 6)
			out.writeInt(8, blockSize - 1);
		else if (blockSizeCode == 7)
			out.writeInt(16, blockSize - 1);
		
		// Variable-length: 0/8/16 bits
		if (sampleRateCode == 12)
			out.writeInt(8, sampleRate);
		else if (sampleRateCode == 13)
			out.writeInt(16, sampleRate);
		else if (sampleRateCode == 14)
			out.writeInt(16, sampleRate / 10);
		
		out.writeInt(8, out.getCrc8());
	}
	
	
	private static void writeUtf8Integer(long val, BitOutputStream out) throws IOException {
		if (val < 0 || val >= (1L << 36))
			throw new IllegalArgumentException();
		int bitLen = 64 - Long.numberOfLeadingZeros(val);
		if (bitLen <= 7)
			out.writeInt(8, (int)val);
		else {
			int n = (bitLen - 2) / 5;
			out.writeInt(8, (0xFF80 >>> n) | (int)(val >>> (n * 6)));
			for (int i = n - 1; i >= 0; i--)
				out.writeInt(8, 0x80 | ((int)(val >>> (i * 6)) & 0x3F));
		}
	}
	
	
	
	/*---- Private helper integer pure functions ----*/
	
	private static int getBlockSizeCode(int blockSize) {
		int result;  // Uint4
		if (BLOCK_SIZE_CODES.containsKey(blockSize))
			result = BLOCK_SIZE_CODES.get(blockSize);
		else if (1 <= blockSize && blockSize <= 256)
			result = 6;
		else if (1 <= blockSize && blockSize <= 65536)
			result = 7;
		else  // blockSize < 1 || blockSize > 65536
			throw new IllegalArgumentException();
		
		if ((result >>> 4) != 0)
			throw new AssertionError();
		return result;
	}
	
	
	private static int getSampleRateCode(int sampleRate) {
		int result;  // Uint4
		if (SAMPLE_RATE_CODES.containsKey(sampleRate))
			result = SAMPLE_RATE_CODES.get(sampleRate);
		else if (0 <= sampleRate && sampleRate < 256)
			result = 12;
		else if (0 <= sampleRate && sampleRate < 65536)
			result = 13;
		else if (0 <= sampleRate && sampleRate < 655360 && sampleRate % 10 == 0)
			result = 14;
		else
			result = 0;
		
		if ((result >>> 4) != 0)
			throw new AssertionError();
		return result;
	}
	
	
	
	/*---- Tables of constants ----*/
	
	private static final Map<Integer,Integer> BLOCK_SIZE_CODES = new HashMap<>();
	static {
		BLOCK_SIZE_CODES.put(  192,  1);
		BLOCK_SIZE_CODES.put(  576,  2);
		BLOCK_SIZE_CODES.put( 1152,  3);
		BLOCK_SIZE_CODES.put( 2304,  4);
		BLOCK_SIZE_CODES.put( 4608,  5);
		BLOCK_SIZE_CODES.put(  256,  8);
		BLOCK_SIZE_CODES.put(  512,  9);
		BLOCK_SIZE_CODES.put( 1024, 10);
		BLOCK_SIZE_CODES.put( 2048, 11);
		BLOCK_SIZE_CODES.put( 4096, 12);
		BLOCK_SIZE_CODES.put( 8192, 13);
		BLOCK_SIZE_CODES.put(16384, 14);
		BLOCK_SIZE_CODES.put(32768, 15);
	}
	
	
	private static final Map<Integer,Integer> SAMPLE_DEPTH_CODES = new HashMap<>();
	static {
		for (int i = 1; i <= 32; i++)
			SAMPLE_DEPTH_CODES.put(i, 0);
		SAMPLE_DEPTH_CODES.put( 8, 1);
		SAMPLE_DEPTH_CODES.put(12, 2);
		SAMPLE_DEPTH_CODES.put(16, 4);
		SAMPLE_DEPTH_CODES.put(20, 5);
		SAMPLE_DEPTH_CODES.put(24, 6);
	}
	
	
	private static final Map<Integer,Integer> SAMPLE_RATE_CODES = new HashMap<>();
	static {
		SAMPLE_RATE_CODES.put( 88200,  1);
		SAMPLE_RATE_CODES.put(176400,  2);
		SAMPLE_RATE_CODES.put(192000,  3);
		SAMPLE_RATE_CODES.put(  8000,  4);
		SAMPLE_RATE_CODES.put( 16000,  5);
		SAMPLE_RATE_CODES.put( 22050,  6);
		SAMPLE_RATE_CODES.put( 24000,  7);
		SAMPLE_RATE_CODES.put( 32000,  8);
		SAMPLE_RATE_CODES.put( 44100,  9);
		SAMPLE_RATE_CODES.put( 48000, 10);
		SAMPLE_RATE_CODES.put( 96000, 11);
	}
	
}

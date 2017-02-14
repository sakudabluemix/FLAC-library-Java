/* 
 * FLAC library (Java)
 * Copyright (c) Project Nayuki. All rights reserved.
 * https://www.nayuki.io/
 */

package io.nayuki.flac.encode;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Objects;
import io.nayuki.flac.decode.FrameMetadata;


/* 
 * Calculates/estimates the encoded size of a frame of audio sample data
 * (including the frame header), and also performs the encoding to an output stream.
 */
final class FrameEncoder {
	
	/*---- Static functions ----*/
	
	public static SizeEstimate<FrameEncoder> computeBest(int sampleOffset, long[][] data, int sampleDepth, int sampleRate, SubframeEncoder.SearchOptions opt) {
		FrameEncoder enc = new FrameEncoder(sampleOffset, data, sampleDepth, sampleRate);
		int numChannels = data.length;
		@SuppressWarnings("unchecked")
		SizeEstimate<SubframeEncoder>[] encoderInfo = new SizeEstimate[numChannels];
		if (numChannels != 2) {
			enc.metadata.channelAssignment = numChannels - 1;
			for (int i = 0; i < encoderInfo.length; i++)
				encoderInfo[i] = SubframeEncoder.computeBest(data[i], sampleDepth, opt);
		} else {  // Explore the 4 stereo encoding modes
			long[] left  = data[0];
			long[] right = data[1];
			long[] mid  = new long[data[0].length];
			long[] side = new long[mid.length];
			for (int i = 0; i < mid.length; i++) {
				mid[i] = (left[i] + right[i]) >> 1;
				side[i] = left[i] - right[i];
			}
			SizeEstimate<SubframeEncoder> leftInfo  = SubframeEncoder.computeBest(left , sampleDepth, opt);
			SizeEstimate<SubframeEncoder> rightInfo = SubframeEncoder.computeBest(right, sampleDepth, opt);
			SizeEstimate<SubframeEncoder> midInfo   = SubframeEncoder.computeBest(mid  , sampleDepth, opt);
			SizeEstimate<SubframeEncoder> sideInfo  = SubframeEncoder.computeBest(side , sampleDepth + 1, opt);
			long mode1Size = leftInfo.sizeEstimate + rightInfo.sizeEstimate;
			long mode8Size = leftInfo.sizeEstimate + sideInfo.sizeEstimate;
			long mode9Size = rightInfo.sizeEstimate + sideInfo.sizeEstimate;
			long mode10Size = midInfo.sizeEstimate + sideInfo.sizeEstimate;
			long minimum = Math.min(Math.min(mode1Size, mode8Size), Math.min(mode9Size, mode10Size));
			if (mode1Size == minimum) {
				enc.metadata.channelAssignment = 1;
				encoderInfo[0] = leftInfo;
				encoderInfo[1] = rightInfo;
			} else if (mode8Size == minimum) {
				enc.metadata.channelAssignment = 8;
				encoderInfo[0] = leftInfo;
				encoderInfo[1] = sideInfo;
			} else if (mode9Size == minimum) {
				enc.metadata.channelAssignment = 9;
				encoderInfo[0] = sideInfo;
				encoderInfo[1] = rightInfo;
			} else if (mode10Size == minimum) {
				enc.metadata.channelAssignment = 10;
				encoderInfo[0] = midInfo;
				encoderInfo[1] = sideInfo;
			} else
				throw new AssertionError();
		}
		
		// Add up subframe sizes
		long size = 0;
		enc.subEncoders = new SubframeEncoder[encoderInfo.length];
		for (int i = 0; i < enc.subEncoders.length; i++) {
			size += encoderInfo[i].sizeEstimate;
			enc.subEncoders[i] = encoderInfo[i].encoder;
		}
		
		// Count length of header (always in whole bytes)
		try {
			ByteArrayOutputStream bout = new ByteArrayOutputStream();
			try (BitOutputStream bitout = new BitOutputStream(bout)) {
				enc.metadata.writeHeader(bitout);
			}
			bout.close();
			size += bout.toByteArray().length * 8;
		} catch (IOException e) {
			throw new AssertionError(e);
		}
		
		// Count padding and footer
		size = (size + 7) / 8 * 8;  // Round up to nearest byte
		size += 16;  // CRC-16
		return new SizeEstimate<>(size, enc);
	}
	
	
	
	/*---- Fields ----*/
	
	public FrameMetadata metadata;
	private SubframeEncoder[] subEncoders;
	
	
	
	/*---- Constructors ----*/
	
	public FrameEncoder(int sampleOffset, long[][] data, int sampleDepth, int sampleRate) {
		metadata = new FrameMetadata();
		metadata.sampleOffset = sampleOffset;
		metadata.sampleDepth = sampleDepth;
		metadata.sampleRate = sampleRate;
		metadata.blockSize = data[0].length;
		metadata.channelAssignment = data.length - 1;
	}
	
	
	
	/*---- Public methods ----*/
	
	public void encode(long[][] data, BitOutputStream out) throws IOException {
		// Check arguments
		Objects.requireNonNull(data);
		Objects.requireNonNull(out);
		if (data[0].length != metadata.blockSize)
			throw new IllegalArgumentException();
		
		metadata.writeHeader(out);
		
		int chanAsgn = metadata.channelAssignment;
		if (0 <= chanAsgn && chanAsgn <= 7) {
			for (int i = 0; i < data.length; i++)
				subEncoders[i].encode(data[i], out);
		} else if (8 <= chanAsgn || chanAsgn <= 10) {
			long[] left  = data[0];
			long[] right = data[1];
			long[] mid  = new long[metadata.blockSize];
			long[] side = new long[metadata.blockSize];
			for (int i = 0; i < metadata.blockSize; i++) {
				mid[i] = (left[i] + right[i]) >> 1;
				side[i] = left[i] - right[i];
			}
			if (chanAsgn == 8) {
				subEncoders[0].encode(left, out);
				subEncoders[1].encode(side, out);
			} else if (chanAsgn == 9) {
				subEncoders[0].encode(side, out);
				subEncoders[1].encode(right, out);
			} else if (chanAsgn == 10) {
				subEncoders[0].encode(mid, out);
				subEncoders[1].encode(side, out);
			} else
				throw new AssertionError();
		} else
			throw new AssertionError();
		out.alignToByte();
		out.writeInt(16, out.getCrc16());
	}
	
}

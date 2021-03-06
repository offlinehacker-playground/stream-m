/*
	This file is part of "stream.m" software, a video broadcasting tool
	compatible with Google's WebM format.
	Copyright (C) 2011 Varga Bence

	This program is free software: you can redistribute it and/or modify
	it under the terms of the GNU General Public License as published by
	the Free Software Foundation, either version 3 of the License, or
	(at your option) any later version.

	This program is distributed in the hope that it will be useful,
	but WITHOUT ANY WARRANTY; without even the implied warranty of
	MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
	GNU General Public License for more details.

	You should have received a copy of the GNU General Public License
	along with this program.  If not, see <http://www.gnu.org/licenses/>.
*/

class StreamingState implements StreamInputState {
	
	private static final long ID_CLUSTER = 0x1F43B675;
	private static final long ID_SIMPLEBLOCK = 0xA3;
	private static final long ID_TIMECODE = 0xE7;
	
	private static final long MINIMAL_FRAGMENT_LENGTH = 100 * 1024;
	
	private long clusterTimeCode = 0;
	
	private StreamInput input;
	private Stream stream;
	private long videoTrackNumber;
	
	private MovieFragment fragment;
	
	public StreamingState(StreamInput input, Stream stream, long videoTrackNumber) {
		this.input = input;
		this.stream = stream;
		this.videoTrackNumber = videoTrackNumber;
		fragment = new MovieFragment();
	}
		
	public int processData(byte[] buffer, int offset, int length) {
		
		int endOffset = offset + length;
		EBMLElement elem;
		
		while (offset < endOffset - 12) {
			elem = new EBMLElement(buffer, offset, length);
			//System.out.println(elem);
			
			// clusters do not need to be fully loaded, so that is an exception
			if (elem.getEndOffset() > endOffset && elem.getId() != ID_CLUSTER) {
				
				/* The element is not fully loaded: we need more data, so we end
				 * this processing cycle. The StreamInput will fill the buffer
				 * and take care of the yet unprocessed element. We signal this
				 * by sending the element's offset back. The StreamInput will
				 * keep the data beyound this offset and the next processing cycle
				 * will begin with this element.
				 */
				return elem.getElementOffset();
			}

			/* Timecode for this cluster. We use a flat processing model (we do
			 * not care about the nesting of elements), so this timecode will
			 * live until we get the next one. It will be the Timecode element
			 * of the next Cluster (according to standard).
			 */
			if (elem.getId() == ID_TIMECODE) {
				
				// we have the timecode, so open a new cluster in our movie fragment
				clusterTimeCode = EBMLElement.loadUnsigned(buffer, elem.getDataOffset(), (int)elem.getDataSize());
				//System.out.println("tc: " + clusterTimeCode);
				
				// cluster opened
				fragment.openCluster(clusterTimeCode);
				
			} else if (elem.getId() == ID_SIMPLEBLOCK) {
				
				// sample (video or audio) recieved
				
				int trackNum = buffer[elem.getDataOffset()] & 0xff;
				if ((trackNum & 0x80) == 0)
					throw new RuntimeException("Track numbers > 127 are not implemented.");
				trackNum ^= 0x80;
				
				//System.out.print(trackNum + " ");

				// the offset of a video keyframe or -1
				int videoKeyOffset = -1;
				
				// check if this is a video frame
				if (trackNum == videoTrackNumber) {
					//System.out.print("video ");
					
					int flags = buffer[elem.getDataOffset() + 3] & 0xff;
					if ((flags & 0x80) != 0) {
						// keyframe
						
						if (fragment.length() >= MINIMAL_FRAGMENT_LENGTH) {
							
							// closing current cluster (of the curent fragment)
							fragment.closeCluster();
							
							// send the complete fragment to the stream coordinator
							stream.pushFragment(fragment);
							
							// create a new fragment
							fragment = new MovieFragment();
							
							// set up new fragment's timecode
							fragment.openCluster(clusterTimeCode);

							// notification about starting the input process
							stream.postEvent(new ServerEvent(input, stream, ServerEvent.INPUT_FRAGMENT_START));
							
							if ((flags & 0x60) == 0) {
								// no lacing
								videoKeyOffset = elem.getDataOffset() + 4;
							}
						}
					}
				}

				// saving the block
				//fragment.appendBlock(buffer, elem.getElementOffset(), elem.getElementSize());
				fragment.appendKeyBlock(buffer, elem.getElementOffset(), elem.getElementSize(), videoKeyOffset);

				//System.out.println();
			
			} // end: ID_SIMPLEBLOCK
			
			if (elem.getId() == ID_CLUSTER || elem.getDataSize() >= 0x100000000L) {
				offset = elem.getDataOffset();
			} else {
				offset = elem.getEndOffset();
			}
		}
		
		return offset;
	}
}

/*
 * Copyright 2008-2009, David Karnok 
 * The file is part of the Open Imperium Galactica project.
 * 
 * The code should be distributed under the LGPL license.
 * See http://www.gnu.org/licenses/lgpl.html for details.
 */
package hu.openig.ani;

import hu.openig.ani.Framerates.Rates;
import hu.openig.utils.IOUtils;

import java.awt.image.BufferedImage;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import javax.imageio.ImageIO;


/**
 * Image compression test program.
 * @author karnokd
 */
public final class ImgCompress {
	/** Private constructor. */
	private ImgCompress() {
		// utility program
	}
	/**
	 * Indexes the colors on the raw image.
	 * @param rawImage the raw RGBA image
	 * @return a map from RGB int to color index.
	 */
	static Map<Integer, Integer> indexColors(int[] rawImage) {
		Map<Integer, Integer> result = new HashMap<Integer, Integer>(256);
		int idx = 0;
		for (int i : rawImage) {
			if (!result.containsKey(i)) {
				result.put(i, idx++);
			}
		}
		
		return result;
	}
	/**
	 * Remaps the raw image int array to raw image byte array using the color map.
	 * Note that colorMap should have &lt;= 256 entries
	 * @param rawImage the raw RGB array
	 * @param colorMap the color remapper from color to index
	 * @return the index map
	 */
	static byte[] remapToBytes(int[] rawImage, Map<Integer, Integer> colorMap) {
		byte[] result = new byte[rawImage.length];
		for (int i = 0; i < rawImage.length; i++) {
			if (rawImage[i] != 0) {
				result[i] = colorMap.get(rawImage[i]).byteValue();
			} else {
				result[i] = -1;
			}
		}
		return result;
	}
	/**
	 * Reorder bytes as they were a subsequent of square * square segments of the original image.
	 * @param raw the raw data
	 * @param scan the scan line length in bytes
	 * @param squareSize the square size
	 * @return the reordered byte data
	 */
	static byte[] reorderSquared(byte[] raw, int scan, int squareSize) {
		byte[] result = new byte[raw.length];
		int scanBuckets = scan / squareSize; // the buckect count
		for (int i = 0; i < raw.length; i++) {
			int x = i % scan;
			int y = i / scan;
			
			int xbucket = x / squareSize;
			int xrel = x % squareSize;
			int ybucket = y / squareSize;
			int yrel = y % squareSize;
			int bucketAddr = (ybucket * scanBuckets + xbucket) * squareSize * squareSize;
			result[bucketAddr + yrel * squareSize + xrel] = raw[i];
			
		}
		
		return result;
	}
	/**
	 * Unreorder the square * square segments.
	 * @param raw the reordered image
	 * @param scan the original scan length
	 * @param squareSize the size of the square segments
	 * @return the unreordered bytes
	 */
	static byte[] unreorderSquared(byte[] raw, int scan, int squareSize) {
		byte[] result = new byte[raw.length];
		int scanBuckets = scan / squareSize; // the buckect count
		for (int i = 0; i < raw.length; i++) {
			int segIdx = i / (squareSize * squareSize);
			int segOffs = i % (squareSize * squareSize);
			int xrel = segOffs % squareSize;
			int yrel = segOffs / squareSize;
			
			int segX = segIdx % scanBuckets;
			int segY = segIdx / scanBuckets;
			
			int addr = (segX * squareSize + xrel) + (segY * squareSize + yrel) * scan;
			result[addr] = raw[i];
		}
		return result;
	}
	/**
	 * Transcode into a differential format.
	 * @param raw the raw data
	 * @return the differentiated value
	 */
	static byte[] differentiate(byte[] raw) {
		byte[] result = new byte[raw.length];
		
		byte last = 0;
		for (int i = 0; i < raw.length; i++) {
			result[i] = (byte)(last - raw[i]);
			last = raw[i];
		}
		
		return result;
	}
	/**
	 * Undifferentiate the given data.
	 * @param raw the raw data
	 * @return the undifferentiated data
	 */
	static byte[] undifferentiate(byte[] raw) {
		byte[] result = new byte[raw.length];
		
		byte last = 0;
		for (int i = 0; i < raw.length; i++) {
			result[i] = (byte)(last - raw[i]);
			last = result[i];
		}
		
		return result;
	}
	/** 
	 * Test various compression methods on one image. 
	 * @throws IOException on error
	 */
	static void testMethods() throws IOException {
		File src = new File("c:/games/ighu/youtube/digi060e.ani/digi060e.ani-00000a.png");
		
		BufferedImage img = ImageIO.read(src);
		
		
		int[] raw = new int[img.getWidth() * img.getHeight()];
		
		System.out.printf("Image: %d x %d, %d bytes compressed, %d bytes uncompressed%n", 
				img.getWidth(), img.getHeight(), src.length(), raw.length
				);
		
		img.getRGB(0, 0, img.getWidth(), img.getHeight(), raw, 0, img.getWidth());
		
		Map<Integer, Integer> colors = indexColors(raw);
		
		System.out.printf("  Distinct colors: %d%n", colors.size());
		
		byte[] raw8 = remapToBytes(raw, colors);
		
		ByteArrayOutputStream bout = new ByteArrayOutputStream(raw8.length);
		GZIPOutputStream gout = new GZIPOutputStream(bout);

		gout.write(raw8);
		gout.close();
		
		System.out.printf("Linear%n");
		
		System.out.printf("  GZipped: %d bytes (%.3f %% | %.3f %%)%n", 
				bout.size(), bout.size() * 100f / src.length(),
				bout.size() * 100f / raw8.length);
		
		bout = new ByteArrayOutputStream(raw8.length);
		ZipOutputStream zout = new ZipOutputStream(bout);
		zout.setLevel(9);
		zout.putNextEntry(new ZipEntry(src.getName()));
		zout.write(raw8);
		zout.close();
		
		System.out.printf("  ZIPped: %d bytes (%.3f %% | %.3f %%)%n", 
				bout.size(), bout.size() * 100f / src.length(),
				bout.size() * 100f / raw8.length);
		
		// --------------------------------------------------------------------
		byte[] r1 = reorderSquared(raw8, img.getWidth(), 8);
		byte[] r2 = unreorderSquared(r1, img.getWidth(), 8);
		
		System.out.printf("Reordered: %s%n", Arrays.equals(raw8, r2));
		
		bout = new ByteArrayOutputStream(raw8.length);
		gout = new GZIPOutputStream(bout);

		gout.write(r1);
		gout.close();
		
		System.out.printf("  GZipped: %d bytes (%.3f %% | %.3f %%)%n", 
				bout.size(), bout.size() * 100f / src.length(),
				bout.size() * 100f / r1.length);
		
		bout = new ByteArrayOutputStream(raw8.length);
		zout = new ZipOutputStream(bout);
		zout.setLevel(9);
		zout.putNextEntry(new ZipEntry(src.getName()));
		zout.write(r1);
		zout.close();
		
		System.out.printf("  ZIPped: %d bytes (%.3f %% | %.3f %%)%n", 
				bout.size(), bout.size() * 100f / src.length(),
				bout.size() * 100f / r1.length);
		
		// --------------------------------------------------------------------
		r1 = differentiate(raw8);
		r2 = undifferentiate(r1);
		
		System.out.printf("Differentiated: %s%n", Arrays.equals(raw8, r2));
		
		bout = new ByteArrayOutputStream(raw8.length);
		gout = new GZIPOutputStream(bout);

		gout.write(r1);
		gout.close();
		
		System.out.printf("  GZipped: %d bytes (%.3f %% | %.3f %%)%n", 
				bout.size(), bout.size() * 100f / src.length(),
				bout.size() * 100f / r1.length);
		
		bout = new ByteArrayOutputStream(raw8.length);
		zout = new ZipOutputStream(bout);
		zout.setLevel(9);
		zout.putNextEntry(new ZipEntry(src.getName()));
		zout.write(r1);
		zout.close();
		
		System.out.printf("  ZIPped: %d bytes (%.3f %% | %.3f %%)%n", 
				bout.size(), bout.size() * 100f / src.length(),
				bout.size() * 100f / r1.length);
		
		// --------------------------------------------------------------------
		r1 = differentiate(differentiate(raw8));
		r2 = undifferentiate(undifferentiate(r1));
		
		System.out.printf("Differentiated x 2: %s%n", Arrays.equals(raw8, r2));
		
		bout = new ByteArrayOutputStream(raw8.length);
		gout = new GZIPOutputStream(bout);

		gout.write(r1);
		gout.close();
		
		System.out.printf("  GZipped: %d bytes (%.3f %% | %.3f %%)%n", 
				bout.size(), bout.size() * 100f / src.length(),
				bout.size() * 100f / r1.length);
		
		bout = new ByteArrayOutputStream(raw8.length);
		zout = new ZipOutputStream(bout);
		zout.setLevel(9);
		zout.putNextEntry(new ZipEntry(src.getName()));
		zout.write(r1);
		zout.close();
		
		System.out.printf("  ZIPped: %d bytes (%.3f %% | %.3f %%)%n", 
				bout.size(), bout.size() * 100f / src.length(),
				bout.size() * 100f / r1.length);
		// --------------------------------------------------------------------
	}
	/**
	 * Differential encode the given named and postfixed sequence of images or audio.
	 * @param imageSeqName the path and name prefix of the images
	 * @param numLength the length of sequence counter
	 * @param imageSeqExt the image extension
	 * @throws IOException if an I/O error occurs
	 */
	static void doDifferentialAniCoding(String imageSeqName, int numLength, String imageSeqExt) throws IOException {
		StringBuilder b = new StringBuilder();
		for (int i = 0; i < numLength; i++) {
			b.append('#');
		}
//		System.out.printf("%s-%s%s%n", imageSeqName, b, imageSeqExt);
		int i = 0;
		int w = 0;
		int h = 0;
		long originalSizes = 0;
		while (true) {
			File src = new File(String.format("%s-%0" + numLength + "d%s", imageSeqName, i, imageSeqExt));
			if (!src.canRead()) {
				break;
			}
			if (originalSizes == 0) {
				BufferedImage img = ImageIO.read(src);
				w = img.getWidth();
				h = img.getHeight();
			}
			originalSizes += src.length();
			i++;
//			if (i % 100 == 0) {
//				System.out.println("|");
//			} else
//			if (i % 10 == 0) {
//				System.out.print("*");
//			} else {
//				System.out.print(".");
//			}
		}
//		System.out.println();
		int cnt = i;
		// store common palette
		File dst = new File(imageSeqName);
		String n = dst.getName();
		dst = dst.getParentFile().getParentFile();
		dst = new File(dst, n + "2009.GZ");
		OutputStream out = new GZIPOutputStream(new FileOutputStream(dst), 1024 * 1024);
		writeLEInt(out, w);
		writeLEInt(out, h);
		writeLEInt(out, cnt);
		Rates r = new Framerates().getRates(new File(imageSeqName).getName(), 1);
		writeLEInt(out, (int)(r.fps * 1000)); // number of frames per second * 1000
		
		out.write('A'); // indication for audio segment
		File audio = new File(imageSeqName + ".wav");
		includeAudio(out, audio);
		// begin differential store
		int[] lastRaw = null;
		i = 0;
		Map<Integer, Integer> currentMap = new HashMap<Integer, Integer>(256);
		while (true) {
			File src = new File(String.format("%s-%0" + numLength + "d%s", imageSeqName, i, imageSeqExt));
			if (!src.canRead()) {
				break;
			}
			BufferedImage img = ImageIO.read(src);
			w = img.getWidth();
			h = img.getHeight();
			int[] raw = new int[img.getWidth() * img.getHeight()];
			img.getRGB(0, 0, img.getWidth(), img.getHeight(), raw, 0, img.getWidth());
			
			Map<Integer, Integer> imagePal = indexColors(raw);
			if (!imagePal.keySet().equals(currentMap.keySet())) {
				out.write('P'); // indication for palette segment
				if (imagePal.size() > 255) {
					System.err.println("Too much color: " + imagePal.size() + " @ " + src);
					break;
				}
				out.write(imagePal.size());
				Map<Integer, Integer> reverse = new HashMap<Integer, Integer>(imagePal.size() + 1);
				for (Map.Entry<Integer, Integer> e : imagePal.entrySet()) {
					reverse.put(e.getValue(), e.getKey());
				}
				for (int j = 0; j < reverse.size(); j++) {
					int c = reverse.get(j);
					out.write((c & 0xFF0000) >> 16);
					out.write((c & 0xFF00) >> 8);
					out.write((c & 0xFF) >> 0);
				}
				currentMap = imagePal;
			}
			out.write('I'); // indication for image segment
			
			if (lastRaw == null) {
				out.write(remapToBytes(raw, currentMap));
			} else {
				int[] altered = raw.clone();
				// replace the pixels which are the same on both images with 255
				for (int j = 0; j < raw.length; j++) {
					if (raw[j] == lastRaw[j]) {
						altered[j] = -1;
					}
				}
				out.write(remapToBytes(altered, currentMap));
			}
			lastRaw = raw;
			i++;
//			if (i % 100 == 0) {
//				System.out.println("|");
//			} else
//			if (i % 10 == 0) {
//				System.out.print("*");
//			} else {
//				System.out.print(".");
//			}
		}
		out.write('X');
		out.close();
		System.out.printf("%n%s | Original: %d, Compressed: %d, Ratio: %.3f%n", dst.getName(), originalSizes, dst.length(), (dst.length() * 100d / originalSizes));
	}
	/**
	 * Adds the given raw audio data into the output stream starting with a length int then the raw data.
	 * @param out the output stream
	 * @param audio the audio file
	 * @throws IOException if an IO error occurs
	 */
	private static void includeAudio(OutputStream out, File audio) throws IOException {
//		System.out.printf("%s", audio);
		if (audio.canRead()) {
			byte[] data = IOUtils.load(audio);
			int len = 0;
			int offset = 0;
			// locate the 'data' segment
			for (int i = 0; i < data.length - 4; i++) {
				if (data[i] == 'd' && data[i + 1] == 'a' && data[i + 2] == 't' && data[i + 3] == 'a') {
					for (int j = 0; j < 4; j++) {
						len |= ((data[i + j + 4] & 0xFF) << (j * 8));
					}
					offset = i + 8;
					break;
				}
			}
			if (len > 0) {
//				System.out.printf(": Audio: %d - %d%n", offset, len);
				writeLEInt(out, len);
				out.write(data, offset, len);
				return;
			}
		}
//		System.out.println(": No audio");
		out.write(0);
		out.write(0);
		out.write(0);
		out.write(0);
	}
	/**
	 * Writes an integer value in little endian order into the output stream.
	 * @param out the output stream
	 * @param value the value
	 * @throws IOException if an error occurs
	 */
	static void writeLEInt(OutputStream out, int value) throws IOException {
		out.write((value & 0xFF) >> 0);
		out.write((value & 0xFF00) >> 8);
		out.write((value & 0xFF0000) >> 16);
		out.write((value & 0xFF000000) >> 24);
	}
	/**
	 * Differential encode the given raw file filename.
	 * @param filename the path and name prefix of the images
	 * @throws IOException if an I/O error occurs
	 */
	public static void doDifferentialAniCoding(String filename) throws IOException {
		RawAni ra = new RawAni(filename);
		int w = ra.width;
		int h = ra.height;
		long originalSizes = ra.raf.length();
		// store common palette
		File dst = new File(filename + ".2009a.gz");
		OutputStream out = new GZIPOutputStream(new FileOutputStream(dst), 1024 * 1024);
		writeLEInt(out, w);
		writeLEInt(out, h);
		writeLEInt(out, ra.frames);
		writeLEInt(out, (int)(ra.fps * 1000)); // number of frames per second * 1000
		
		// begin differential store
		int[] lastRaw = null;
		Map<Integer, Integer> currentMap = new HashMap<Integer, Integer>(256);
		for (int i = 0; i < ra.frames; i++) {
			int[] raw = new int[w * h];
			ra.readFrame(i, raw);
			
			Map<Integer, Integer> imagePal = indexColors(raw);
			if (!imagePal.keySet().equals(currentMap.keySet())) {
				out.write('P'); // indication for palette segment
				if (imagePal.size() > 255) {
					// find the least used color, then find the closest color, then replace it
					int[] occurs = new int[imagePal.size()];
					int[] ocolor = new int[imagePal.size()];
					for (int j = 0; j < raw.length; j++) {
						occurs[imagePal.get(raw[i])]++;
						ocolor[imagePal.get(raw[i])] = raw[i];
					}
					int min = Integer.MAX_VALUE;
					int minColor = 0;
					for (int j = 0; j < occurs.length; j++) {
						if (occurs[j] < min) {
							min = occurs[j];
							minColor = ocolor[j];
						}
					}
					long mind = Long.MAX_VALUE;
					int minc = 0;
					for (int j = 0; j < ocolor.length; j++) {
						long d = distance(minColor, ocolor[j]);
						if (d > 0 && mind > d) {
							mind = d;
							minc = ocolor[j];
						}
					}
					for (int j = 0; j < raw.length; j++) {
						if (raw[j] == minColor) {
							raw[j] = minc;
						}
					}
					imagePal = indexColors(raw);
					System.err.println("Too much color: " + imagePal.size() + " @ " + filename);
					System.err.printf("%d | Color %08X replaced with %08X%n", i, minColor, minc);
//					break;
				}
				out.write(imagePal.size());
				Map<Integer, Integer> reverse = new HashMap<Integer, Integer>(imagePal.size() + 1);
				for (Map.Entry<Integer, Integer> e : imagePal.entrySet()) {
					reverse.put(e.getValue(), e.getKey());
				}
				for (int j = 0; j < reverse.size(); j++) {
					int c = reverse.get(j);
					out.write((c & 0xFF0000) >> 16);
					out.write((c & 0xFF00) >> 8);
					out.write((c & 0xFF) >> 0);
				}
				currentMap = imagePal;
			}
			out.write('I'); // indication for image segment
			
			if (lastRaw == null) {
				out.write(remapToBytes(raw, currentMap));
			} else {
				int[] altered = raw.clone();
				// replace the pixels which are the same on both images with 255
				for (int j = 0; j < raw.length; j++) {
					if (raw[j] == lastRaw[j]) {
						altered[j] = 0;
					}
				}
				out.write(remapToBytes(altered, currentMap));
			}
			lastRaw = raw;
		}
		out.write('X');
		out.close();
		System.out.printf("%n%s | Original: %d, Compressed: %d, Ratio: %.3f%n", dst.getName(), originalSizes, dst.length(), (dst.length() * 100d / originalSizes));
	}
	/**
	 * Compute the distance square between two RGBA colors.
	 * @param rgb1 the first color
	 * @param rgb2 the second color
	 * @return the distance
	 */
	static long distance(int rgb1, int rgb2) {
		int r1 = (rgb1 & 0xFF0000) >> 16;
		int g1 = (rgb1 & 0xFF00) >> 8;
		int b1 = (rgb1 & 0xFF) >> 0;
		int r2 = (rgb1 & 0xFF0000) >> 16;
		int g2 = (rgb1 & 0xFF00) >> 8;
		int b2 = (rgb1 & 0xFF) >> 0;
		return (r1 * r1 + b1 * b1 + g1 * g1 - (r2 * r2 + b2 * b2 + g2 * g2));
	}
	/**
	 * Decompress the image to the raw format again.
	 * @param in the input stream
	 * @param out the output stream 
	 * @throws IOException on IO error
	 */
	public static void decompressToRaw(DataInputStream in, DataOutputStream out) throws IOException {
		int w = Integer.reverseBytes(in.readInt());
		int h = Integer.reverseBytes(in.readInt());
		int frames = Integer.reverseBytes(in.readInt());
		int fps = Integer.reverseBytes(in.readInt());
		
		out.writeShort(w);
		out.writeShort(h);
		out.writeShort(frames);
		out.writeShort(fps);
		
		int[] palette = new int[256];
		int[] currimage = new int[w * h];
		byte[] bytebuffer = new byte[w * h];
		
		int c = 0;
		while ((c = in.read()) != -1) {
			if (c == 'X') {
				break;
			} else
			if (c == 'P') {
				int len = in.read();
				for (int j = 0; j < len; j++) {
					palette[j] = 0xFF;
					for (int k = 0; k < 3; k++) {
						palette[j] = (palette[j] << 8) | in.read();
					}
				}
			} else
			if (c == 'I') {
				in.read(bytebuffer);
				for (int i = 0; i < bytebuffer.length; i++) {
					int c0 = palette[bytebuffer[i] & 0xFF];
//					if (c0 == 0) {
//						currimage[i] = lastimage[i];
//					} else {
//						currimage[i] = c0;
//					}
//					out.writeInt(currimage[i]);
					out.writeInt(c0);
				}
				
			}
		}
		in.close();
		out.close();
	}
	/**
	 * Main program.
	 * @param args the arguments
	 * @throws Exception on error
	 */
	public static void main(String[] args) throws Exception {
		decompressToRaw(new DataInputStream(new GZIPInputStream(new FileInputStream("d:\\Games\\IGHU\\youtube\\1_hid.ani.raw.2009a.gz"), 1024 * 1024)), 
				new DataOutputStream(new BufferedOutputStream(new FileOutputStream("d:\\Games\\IGHU\\youtube\\1_hid.ani.raw.2009a"), 1024 * 1024)));
		if (false) {
			return;
		}
//		testMethods();
		File[] files = new File("d:\\Games\\IGHU\\youtube").listFiles();
		if (files != null) {
			int n = Runtime.getRuntime().availableProcessors();
			ExecutorService exec = Executors.newFixedThreadPool(n);
			for (File f : files) {
				if (f.isDirectory()) {
					continue;
				}
				final String name = f.getAbsolutePath();
				if (name.endsWith(".raw")) {
					exec.submit(new Runnable() {
						@Override
						public void run() {
							try {
								doDifferentialAniCoding(name);
							} catch (Throwable e) {
								e.printStackTrace();
							}
						}
					});
				}
			}
			exec.shutdown();
		}
	}

}

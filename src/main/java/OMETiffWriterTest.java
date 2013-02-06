import fiji.util.gui.GenericDialogPlus;
import ij.IJ;
import ij.plugin.PlugIn;
import ij.process.ImageProcessor;
import ij.process.ShortProcessor;

import java.io.File;
import java.io.IOException;


public class OMETiffWriterTest implements PlugIn {
	private static int WIDTH = 512;
	private static int HEIGHT = 384;
	private static int BITDEPTH = 10;
	private static double UMPERPIX = (10D / 23D);

	@Override
	public void run(String arg) {
		if (arg != null) {
			String[] args = arg.split(" ");
			if (args.length == 4) {
				try {
					main(args);
				} catch (IOException e) {
					IJ.handleException(e);
				}
				return;
			}
		}

		GenericDialogPlus gd = new GenericDialogPlus("OME-Tiff Writer test");
		gd.addNumericField("stacks", 2, 0);
		gd.addNumericField("depth", 5, 0);
		gd.addNumericField("time_points", 2, 0);
		gd.addFileField("output_directory", new File(IJ.getDirectory("imagej"), "test-output").getAbsolutePath());
		gd.showDialog();
		if (gd.wasCanceled()) {
			return;
		}

		int stacks = (int)gd.getNextNumber();
		int depth = (int)gd.getNextNumber();
		int timePoints = (int)gd.getNextNumber();

		File outDir = new File(gd.getNextString());

		run(stacks, depth, timePoints, outDir);
	}

	public static void main(String[] args) throws IOException {
		int stacks = args.length < 1 ? 2 : Integer.parseInt(args[0]);
		int depth = args.length < 2 ? 5 : Integer.parseInt(args[1]);
		int timePoints = args.length < 3 ? 2 : Integer.parseInt(args[2]);
		File outDir;
		if (args.length < 4) {
			outDir = File.createTempFile("test-output", "");
			outDir.delete();
		} else {
			outDir = new File(args[3]);
		}
		run(stacks, depth, timePoints, outDir);
	}

	public static void run(int stacks, int depth, int timePoints, File outDir) {
		if(!outDir.exists() && !outDir.mkdirs())
			throw new RuntimeException("Couldn't make directory " + outDir);

		AcqRow[] rows = new AcqRow[stacks];
		for(int xyt=0; xyt < stacks; ++xyt)
			rows[xyt] = new AcqRow(new String[] {"Picard XY Stage", "Picard Twister", "t"},
					"Picard Stage", "1:1:" + depth);

		OMETIFFHandler handler = new OMETIFFHandler(BITDEPTH, WIDTH, HEIGHT, UMPERPIX, outDir, rows, timePoints, 13);

		long millis = System.currentTimeMillis();

		for(int t=0; t < timePoints; ++t) {
			for(int xyt=0; xyt < stacks; ++xyt) {
				try {
					handler.beginStack(0);
				} catch(Exception e) {
					ij.IJ.handleException(e);
					return;
				}

				for(int z=1; z <= depth; ++z) {
					long now = System.currentTimeMillis();
					try {
						final ImageProcessor ip = makeSlice(xyt, t, z, now - millis);
						millis = System.currentTimeMillis();
						handler.processSlice(ip, 0, 0, z, xyt, t);
					} catch(Exception e) {
						ij.IJ.handleException(e);
						return;
					}
				}

				try {
					handler.finalizeStack(0);
				} catch(Exception e) {
					ij.IJ.handleException(e);
					return;
				}
			}
		}

		try {
			handler.finalizeAcquisition();
		} catch (Exception e) {
			ij.IJ.handleException(e);
		}

		ij.IJ.log("Finished writing OME-TIFFs.");

		ij.IJ.run("Bio-Formats");
	}

	private static ImageProcessor makeSlice(int v, int t, int z, long millis) {
		final ImageProcessor result = new ShortProcessor(WIDTH, HEIGHT);

		// make a gradient
		double angle = z * Math.PI / BITDEPTH;
		double min = 0xcfff * Math.sin(t * Math.PI / 12);
		double max = 0xcfff * Math.cos(t * Math.PI / 8);
		short[] pixels = (short[])result.getPixels();
		for (int y = 0; y < HEIGHT; y++) {
			  for (int x = 0; x < WIDTH; x++) {
				int dx = x - WIDTH / 2;
				int dy = y - HEIGHT / 2;
				double alpha = Math.atan2(dy, dx) - angle;
				if (alpha < 0) alpha += 2 * Math.PI;
				else if (alpha >= 2 * Math.PI) alpha -= 2 * Math.PI;
				double value = min + (max - min) * alpha / 2 / Math.PI;
				pixels[x + WIDTH * y] = (short)(int)value;
			  }
		}
		// write time and z as text
		result.setColor(0xffff);
		result.drawString("View: " + v, 10, 50);
		result.drawString("Time: " + t, 10, 100);
		result.drawString("z-slice: " + z, 10, 150);
		result.drawString("previous slice writing took " + millis + " ms", 10, 200);

		return result;
	}
}

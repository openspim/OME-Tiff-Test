import java.io.File;

import ij.plugin.PlugIn;
import ij.process.ImageProcessor;
import ij.process.ShortProcessor;


public class OMETiffWriterTest implements PlugIn {
	private static int WIDTH = 512;
	private static int HEIGHT = 384;
	private static int BITDEPTH = 10;
	private static double UMPERPIX = (10D / 23D);

	@Override
	public void run(String arg) {
		String[] args = arg.split(" ");

		if(args.length < 5) {
			args = new String[] {
				"OMETIFFHandler.java",
				"2",
				"5",
				"2",
				"C:\\Documents and Settings\\LOCI\\Desktop\\handlertest\\output"
			};
		}

		int stacks = Integer.parseInt(args[1]);
		int depth = Integer.parseInt(args[2]);
		int timePoints = Integer.parseInt(args[3]);

		File outDir = new File(args[4]);
		if(!outDir.exists() && !outDir.mkdirs())
			throw new Error("Couldn't mkdir.");

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

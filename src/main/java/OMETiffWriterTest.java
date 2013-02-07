import fiji.util.gui.GenericDialogPlus;
import ij.IJ;
import ij.ImageJ;
import ij.ImagePlus;
import ij.Menus;
import ij.WindowManager;
import ij.plugin.PlugIn;
import ij.process.ImageProcessor;
import ij.process.ShortProcessor;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Hashtable;


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
		long maxdt = -1;

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
						if(now - millis > maxdt)
							maxdt = now - millis;

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

		IJ.log("Finished writing OME-TIFFs.");

		// We can't assume the user has no images open...
		int oldCount = WindowManager.getImageCount();

		String series = "";
		for (int i = 1; i <= stacks; i++) {
			series += " series_" + i;
		}

		// force IJ.init() to be run
		if (IJ.getInstance() == null) {
			new ImageJ();
		}

		@SuppressWarnings("unchecked")
		Hashtable<String, String> commands = Menus.getCommands();
		if (commands.get("Bio-Formats") == null) {
			commands.put("Bio-Formats", "loci.plugins.LociImporter(\"location=[Local machine] windowless=false \")");
		}

		IJ.run("Bio-Formats", "autoscale display_metadata display_ome-xml open=["
			+ new File(outDir, "spim_TL01_Angle0.ome.tiff").getAbsolutePath()
			+ "] color_mode=Default view=Hyperstack stack_order=XYCZT" + series);

		int errors = 0;
		int count = WindowManager.getImageCount();
		if (count - oldCount != stacks) {
			IJ.log("Expected " + stacks + " images, but got only " + (count - oldCount));
			errors++;
		}
		if (count > oldCount) {
			for(int i=oldCount+1; i <= count; ++i) {
				ImagePlus img = WindowManager.getImage(i);
				if	(img == null) {
					IJ.log("Image " + i + " was null!");
					++errors;
				} else {
					if (depth != img.getNSlices()) {
						IJ.log(String.format("Image %d: Expected %d slices but got %d.",i-oldCount,depth,img.getNSlices()));
						errors++;
					}
					if (timePoints != img.getNFrames()) {
						IJ.log(String.format("Image %d: Expected %d frames but got %d.",i-oldCount,timePoints,img.getNFrames()));
						errors++;
					}

					for(int t=0; t < img.getNFrames(); ++t) {
						for(int z=0; z < img.getNSlices(); ++z) {
							if(img.getStack().getProcessor(img.getStackIndex(1, z, t)).getMax() == 0) {
								IJ.log(String.format("Image %d, timepoint %d, slice %d: Blank frame!", i, t, z));
								++errors;
							}
						}
					}
				}
			}
		}
		if(maxdt > 1e5) {
			IJ.log("Maximum time to write a slice was > 10s!");
			++errors;
		}
		if (errors == 0) {
			IJ.log("No errors found!");
		}
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

/* This plugin is written by Joost Willemse 
 * For any questions concerning the plugin you can contact me at jwillemse@biology.leidenuniv.nl
 * 
 * The reason for developping this plugin is to allow thresholding on other statistics than only on intensity
 * A paper explaining the methods used and potential implementations is in preparation
 */
package LeidenUniv.Tools;
import ij.IJ;
import ij.ImagePlus;
import ij.plugin.*;
import ij.gui.*;
import ij.plugin.filter.*;
import java.awt.*;
import ij.process.*;
import java.awt.event.*;
import ij.ImageJ;
import javax.swing.JProgressBar;
import ij.plugin.Thresholder;

/**
 * @author willemsejj
 * This plugin is created by Joost Willemse @ leiden University
 * It can be used to threshold images on other factors than just intensity
 * It basically applies a filter to the image and allows you to set the 
 * threshold on that filtered image without needing to see the intermediate
 * 
 */
public class Special_Threshold implements PlugIn, DialogListener, ActionListener {
	private ij.ImagePlus image, overlay;
	private ImageProcessor ip;
	private GenericDialog gd;
	private int prevA, prevT, prevC, prevB;
	private FloatProcessor bt;
	// image property members
	private int width;
	private int height;
	private Button okB;
	private Thread t;
	private Roi ro;
	private boolean showImage;
	private String [] Choices = {"Mean", "Median", "Modal", "Standard deviation", "Maximum grey value", "Minimm grey value", "Integrated density", "Skewness", "Kurtosis", "Harmonic Mean", "Geometric Mean", "Winsorized Mean", "Quadruple Mean", "Cubic Mean","Left to Right symmetry","Top to Bottom symmetry","Top-left to Right-bottom symmetry","Bottom-left to Top-right symmetry","4-axis average Symmetry","FFT Stdev","FFT Mean","FFT Kurtosis", "FFT Skewness"}; 
	ImageJ cij = IJ.getInstance();
	JProgressBar pb =new JProgressBar();
	int a, T, c, B;
	boolean show;
	public final static Object obj = new Object();
	
	/**
	 * Is used to run the plugin, the arguments are not used
	 */
	public void run(String arg) {
		image = IJ.getImage();
		ip = image.getProcessor();
		// get width and height
		width = ip.getWidth();
		height = ip.getHeight();

		bt = new FloatProcessor(width, height);
		overlay = new ImagePlus("overlay", bt);
		//overlay.show();

		gd = new GenericDialog("Filter");
		prevA=3;
		prevC= 0;
		prevT=80;
		prevB=20;
		showImage=false;
		gd.addSlider("Kernel layers ", 1, 21, 3);
		gd.addMessage("1 means only the pixel itself, \n2 means also 1 neighbouring cell, \n3 means 2 neighbouring cells\nand so on");
		gd.addChoice("Filter based on: ", Choices, Choices[0]);
		gd.addSlider("Top Threshold in % ", 0, 100, prevT);
		gd.addSlider("Bottom Threshold in % ", 0, 100, prevB);
		gd.setModal(false);
		gd.addCheckbox("Show intermediate image", showImage);
		
		gd.addDialogListener(this);
		gd.addMessage("Progress");
		gd.add(pb);
		getThreshold(prevT,prevC, prevB, true);
		gd.showDialog();
		
		Button [] bts = gd.getButtons();
		okB = bts[0];
		okB.addActionListener(this);
		bts[1].addActionListener(this);

	}

// for implementation in macro, info needed is ImagePlus, Kernel size, dark/light background, threshold in %, segmentation method

	/**
	 * @param im, the imageplus that needs to be masked
	 * @param ks, kernel size (radius)
	 * @param Ltresh, lower threshold level to use
	 * @param Thresh, top threshold level to use
	 * @param Method The method used for filtering
	 * 
	 * This is the part of the plugin that can be called from another plugin
	 * It returns a binary image that can be used for further processing
	 * 
	 * @return The masked image
	 */
	public ImagePlus getMask(ImagePlus im, int ks, int Ltresh, int Thresh, int Method){
		image = im;
		prevA = ks;
		prevT=Thresh;
		ip = image.getProcessor();
		// get width and height
		width = ip.getWidth();
		height = ip.getHeight();

		bt = new FloatProcessor(width, height);
		overlay = new ImagePlus("overlay", bt);
		
		getThreshold(prevT,Method, Ltresh, true);
		while (t.isAlive()){
			IJ.log("running");
			try {
				Thread.sleep(100);
			} catch(InterruptedException ex){}
		}
		
		
		//overlay.show();
		overlay.setRoi(ro);
		//IJ.run(overlay, "Clear Outside", "");
		//IJ.setForegroundColor(255, 255, 255);
		//IJ.run(overlay, "Fill", "slice");
		//wfud.show();
		//IJ.run(overlay, "Remove Overlay", "");	
		//wfud.show();
		ByteProcessor maskbp =overlay.createRoiMask();
		ImagePlus mask = new ImagePlus("Mask",(ImageProcessor)maskbp);
		return mask;
	}

	/**
	 * Checks for interaction with the dialog
	 */
	public boolean dialogItemChanged(GenericDialog gd1, AWTEvent ev){
		Scrollbar Selected=(Scrollbar)(gd1.getSliders().elementAt(0));
		Scrollbar SelectedT=(Scrollbar)(gd1.getSliders().elementAt(1));
		Scrollbar SelectedB=(Scrollbar)(gd1.getSliders().elementAt(2));
		
		a=Selected.getValue();
		T=SelectedT.getValue();
		B=SelectedB.getValue();
		c =gd1.getNextChoiceIndex();
		show = gd1.getNextBoolean();
		boolean recalc = true;
		if (a!=prevA){
			prevA=a;
		} else if (c!=prevC){
			prevC=c;
		} else if (T!=prevT){
			prevT=T;
			recalc=false;
		} else if (B!=prevB){
			prevB=B;
			recalc=false;
		} else if (show!=showImage){
			showImage=show;
			if (show==true){
				overlay.show();
				recalc=false;
			} else {
				overlay.hide();
				recalc=false;
			}
		}
		
		getThreshold(T,c, B, recalc);
		
		return true;
	}

	/**
	 * @param Tval Current threshold setting
	 * @param Cval Current method for thresholding
	 * @param Bval current lower threshold setting
	 * @param recalc recalculate or not
	 * 
	 * This method gets the threshold
	 */
	public void getThreshold(int Tval, int Cval, int Bval, boolean recalc){
		final int cCval=Cval; // needs to be final to be called from inner class
		final int cTval=Tval; // needs to be final to be called from inner class
		final int cBval=Bval; // needs to be final to be called from inner class
		if (recalc){
			// clear the progressbar initially
			t = new Thread(){
				public void run(){
					int xw=0,yw=0;
					for (int i=0; i<width;i++){ // runs from 0 to width
						pb.setValue((i*100)/width+1);
						for (int j=0;j<height;j++){ // runs from 0 to height
							
							int xm = Math.max(i-prevA,0);
							int ym = Math.max(j-prevA,0);
							
							int xe = i+prevA;
							int ye = j+prevA;
							
							if (xe>width-1){ // if endpoint falls outside
								xw = width-i+prevA;
								
							} else if (i-prevA<0) { // cannot start lower than 0 but should not take the whole region then
								xw = prevA+i;
							} else {
								xw=2*prevA+1;
							}
			
							if (ye>height-1){ // if endpoint falls outside
								yw = height-j+prevA;
							} else if (j-prevA<0){
								yw = prevA+j;
							} else {
								yw=2*prevA+1;
							}
							
							Roi cr = new Roi(xm,ym,xw,yw);
							
							image.setRoi(cr);
							//IJ.run(image, "Fill", "slice");
							ImageStatistics is=null;
							try { 
								is = cr.getStatistics();
							} catch  (Exception e) {
								IJ.log(""+xm+", "+ym+", "+xw+", "+yw);
								IJ.log(e.toString());
								e.printStackTrace();
							}
							
							switch (cCval){ 
								//{"Mean", "Median", "Modal", "Standard deviation", "Maximum grey value", "Minimm grey value", "Integrated density", "Skewness", "Kurtosis"
								// "Harmonic Mean", "Geometric Mean", "Winsorized Mean", "Quadruple Mean", "Cubic Mean","Left to Right symmetry","Top to Bottom symmetry","Top-left to Right-bottom symmetry","Bottom-left to Top-right symmetry"  
								case 0: bt.putPixelValue(i,j,is.mean);
										break;
								case 1:bt.putPixelValue(i,j,is.median);
										break;
								case 2:bt.putPixelValue(i,j,is.mode);
										break;
								case 3:bt.putPixelValue(i,j,is.stdDev);
										break;
								case 4:bt.putPixelValue(i,j,is.max);
										break;
								case 5:bt.putPixelValue(i,j,is.min);
										break;
								case 6:bt.putPixelValue(i,j,is.mean*xw*yw);
										break;					
								case 7:bt.putPixelValue(i,j,is.skewness);
										break;					
								case 8:bt.putPixelValue(i,j,is.kurtosis);
										break;
								case 9:bt.putPixelValue(i,j,getHarmonicMean(xm,ym,xw,yw,ip)); // for harmonic mean
										break; 					
								case 10:bt.putPixelValue(i,j,getGeometricMean(xm,ym,xw,yw,ip)); // for geometric mean
										break;					
								case 11:bt.putPixelValue(i,j,(is.min+is.max)/2); // for winsorized mean
										break;					
								case 12:bt.putPixelValue(i,j,getPowerMean(xm,ym,xw,yw,ip,2)); // for quadruple mean
										break;					
								case 13:bt.putPixelValue(i,j,getPowerMean(xm,ym,xw,yw,ip,3)); // for cubic mean
										break;					
								case 14:bt.putPixelValue(i,j,getLRSymmetry(xm,ym,xw,yw, ip)); //Left Right symmetry 
										break;					
								case 15:bt.putPixelValue(i,j,getTBSymmetry(xm,ym,xw,yw, ip)); // Top Bottom symmetry
										break;
								case 16:bt.putPixelValue(i,j,getTLBRSymmetry(xm,ym,xw,yw,ip)); // top-left to right bottom symmetry
										break;
								case 17:bt.putPixelValue(i,j,getTRBLSymmetry(xm,ym,xw,yw,ip)); // bottom left to top right symmetry
										break;				
								case 18:bt.putPixelValue(i,j,(getTRBLSymmetry(xm,ym,xw,yw,ip)+getLRSymmetry(xm,ym,xw,yw,ip)+getTBSymmetry(xm,ym,xw,yw,ip)+getTLBRSymmetry(xm,ym,xw,yw,ip))* 0.25);
								// now average symmertry is put
								//(i,j,Math.pow(Math.pow(getTRBLSymmetry(xm,ym,xw,yw,ip),2)+Math.pow(getLRSymmetry(xm,ym,xw,yw,ip),2)+Math.pow(getTBSymmetry(xm,ym,xw,yw,ip),2)+Math.pow(getTLBRSymmetry(xm,ym,xw,yw,ip),2), 0.25));
										// all symmertry cubic combined
										break;
								case 19:bt.putPixelValue(i,j,getFFTStdev(xm,ym,xw,yw,ip));
										// all symmertry cubic combined
										break;
								case 20:bt.putPixelValue(i,j,getFFTMean(xm,ym,xw,yw,ip));
										// all symmertry cubic combined
										break;
								case 21:bt.putPixelValue(i,j,getFFTKurt(xm,ym,xw,yw,ip));
										// all symmertry cubic combined
										break;
								case 22:bt.putPixelValue(i,j,getFFTSkew(xm,ym,xw,yw,ip));
										// all symmertry cubic combined
										break;
							}//switch
						} //j
					} //i

					IJ.run(overlay, "Enhance Contrast", "saturated=0.0");
					ImageStatistics ois = overlay.getStatistics();
					double minS = ois.min;
					double maxS = ois.max;
					double diff = maxS-minS;
					double steps = diff/100;
			
					IJ.setRawThreshold(overlay, ois.min+cBval*steps, ois.min+cTval*steps, null);
					ro = ThresholdToSelection.run(overlay);
					Overlay ov = new Overlay(ro);
					ov.setFillColor(Color.red);
					image.setOverlay(ov);
				} // public void
				
			}; //thread
			t.start();
		}

		//iw.updateImage(overlay);
		//overlay.updateAndDraw();
		IJ.run(overlay, "Enhance Contrast", "saturated=0.0");
		ImageStatistics ois = overlay.getStatistics();
		double minS = ois.min;
		double maxS = ois.max;
		double diff = maxS-minS;
		double steps = diff/100;
		IJ.setRawThreshold(overlay, ois.min+cBval*steps, ois.min+cTval*steps, null);
		ro = ThresholdToSelection.run(overlay);
		image.setRoi(ro);
		Overlay ov = new Overlay(ro);
		ov.setFillColor(Color.red);
		image.setOverlay(ov);
	}

	/**
	 * @param xm middle x pixel
	 * @param ym middle y pixel
	 * @param xw width over x
	 * @param yw height over y
	 * @param ip imageprocessor to calculte this on
	 * @return the new double value that goes into the overlay image
	 */
	public double getFFTKurt(int xm, int ym, int xw, int yw, ImageProcessor ip){
		ImagePlus Fimp = FFT.forward(image);
		//IJ.log(""+Fimp.getStatistics(0x40000).kurtosis);
		return Fimp.getStatistics(0x40000).kurtosis;
	}
	/**
	 * @param xm middle x pixel
	 * @param ym middle y pixel
	 * @param xw width over x
	 * @param yw height over y
	 * @param ip imageprocessor to calculte this on
	 * @return the new double value that goes into the overlay image
	 */
	public double getFFTSkew(int xm, int ym, int xw, int yw, ImageProcessor ip){
		ImagePlus Fimp = FFT.forward(image);
		return Fimp.getStatistics(0x20000).skewness;
	}
	
	/**
	 * @param xm middle x pixel
	 * @param ym middle y pixel
	 * @param xw width over x
	 * @param yw height over y
	 * @param ip imageprocessor to calculte this on
	 * @return the new double value that goes into the overlay image
	 */
	public double getFFTMean(int xm, int ym, int xw, int yw, ImageProcessor ip){
		ImagePlus Fimp = FFT.forward(image);
		return Fimp.getStatistics().mean;
	}
	
	/**
	 * @param xm middle x pixel
	 * @param ym middle y pixel
	 * @param xw width over x
	 * @param yw height over y
	 * @param ip imageprocessor to calculte this on
	 * @return the new double value that goes into the overlay image
	 */
	public double getFFTStdev(int xm, int ym, int xw, int yw, ImageProcessor ip){
		ImagePlus Fimp = FFT.forward(image);
		return Fimp.getStatistics().stdDev;
	}

	/**
	 * @param xm middle x pixel
	 * @param ym middle y pixel
	 * @param xw width over x
	 * @param yw height over y
	 * @param ip imageprocessor to calculte this on
	 * @param pow the power of the mean that you want to calculate
	 * @return the new double value that goes into the overlay image
	 */
	public double getPowerMean(int xm, int ym, int xw, int yw, ImageProcessor ip, double pow){
		double sum=0;
		for (int j=0;j<yw;j++){	
			for (int i=0;i<xw;i++){
				sum+=Math.pow((double)ip.getPixel(xm+i,ym+j),pow);
			}
		}
		sum=sum/(xw*yw);
		sum=Math.pow(sum,(1/pow));
		return sum;	
	}

	/**
	 * @param xm middle x pixel
	 * @param ym middle y pixel
	 * @param xw width over x
	 * @param yw height over y
	 * @param ip imageprocessor to calculte this on
	 * @return the new double value that goes into the overlay image
	 */
	public double getGeometricMean(int xm, int ym, int xw, int yw, ImageProcessor ip){
		double sum=1;
		for (int j=0;j<yw;j++){	
			for (int i=0;i<xw;i++){
				sum+=(double)ip.getPixel(xm+i,ym+j)*sum;
			}
		}
		sum=Math.pow(sum,(1.0/(double)(xw*yw)));

		return sum;
		
	}
	
	/**
	 * @param xm middle x pixel
	 * @param ym middle y pixel
	 * @param xw width over x
	 * @param yw height over y
	 * @param ip imageprocessor to calculte this on
	 * @return the new double value that goes into the overlay image
	 */
	public double getHarmonicMean(int xm, int ym, int xw, int yw, ImageProcessor ip){
		double sum=0;
		for (int j=0;j<yw;j++){	
			for (int i=0;i<xw;i++){
				sum+=(1/(double)ip.getPixel(xm+i,ym+j));
			}
		}
		sum=sum/(yw*xw);
		return (1/sum);
		
	}

	/**
	 * @param xm middle x pixel
	 * @param ym middle y pixel
	 * @param xw width over x
	 * @param yw height over y
	 * @param ip imageprocessor to calculte this on
	 * @return the new double value that goes into the overlay image
	 */
	public double getLRSymmetry(int xm, int ym, int xw, int yw, ImageProcessor ip){ // Imageprocessor needs to be added for general functionality
		if (xw==1 && yw==1){ // for only one pixel symmetry is always 1
			return 1;
		} else if (xw==1){ // one pixel wide always 1
			return 1;
		} else if (yw==1){ // one pixel high, xw wide, depends on even/uneven by using the fact that odd int/2 are always floored we can use 1 formula
			double diff=0;
			double sum=0;
			for (int i=0;i<xw/2;i++){
				diff+=Math.abs(ip.getPixel(xm+i,ym)-ip.getPixel(xm+xw-1-i,ym));
				sum+=ip.getPixel(xm+i, ym)+ip.getPixel(xm+xw-1-i,ym);
			}
			return (1-(diff/sum));
		} else { //multiple rows, need to be checked, xw wide, depends on even/uneven
			double diff=0;
			double sum=0;
			for (int j=0;j<yw;j++){	
				for (int i=0;i<xw/2;i++){
					diff+=Math.abs(ip.getPixel(xm+i,ym+j)-ip.getPixel(xm+xw-1-i,ym+j));
					sum+=ip.getPixel(xm+i,ym+j)+ip.getPixel(xm+xw-1-i,ym+j);
				}
			}
			return (1-(diff/sum));
		}
	}

	/**
	 * @param xm middle x pixel
	 * @param ym middle y pixel
	 * @param xw width over x
	 * @param yw height over y
	 * @param ip imageprocessor to calculte this on
	 * @return the new double value that goes into the overlay image
	 */
	public double getTBSymmetry(int xm, int ym, int xw, int yw, ImageProcessor ip){
		if (xw==1 && yw==1){ // for only one pixel symmetry is always 1
			return 1;
		} else if (yw==1){ // one pixel high always 1
			return 1;
		} else if (xw==1){ // one pixel wide, depends on even/uneven by using the fact that odd int/2 are always floored we can use 1 formula
			double diff=0;
			double sum=0;
			for (int i=0;i<yw/2;i++){
				diff+=Math.abs(ip.getPixel(xm,ym+i)-ip.getPixel(xm,ym+yw-1-i));
				sum+=ip.getPixel(xm, ym+i)+ip.getPixel(xm,ym+yw-1-i);
			}
			return (1-(diff/sum));
		} else { //multiple rows, need to be checked, xw wide, depends on even/uneven
			double diff=0;
			double sum=0;
			for (int j=0;j<xw;j++){	
				for (int i=0;i<yw/2;i++){
					diff+=Math.abs(ip.getPixel(xm+j,ym+i)-ip.getPixel(xm+j,ym+yw-1-i));
					sum+=ip.getPixel(xm+j, ym+i)+ip.getPixel(xm+j,ym+yw-1-i);
				}
			}
			return (1-(diff/sum));
		}
	}

	/**
	 * @param xm middle x pixel
	 * @param ym middle y pixel
	 * @param xw width over x
	 * @param yw height over y
	 * @param ip imageprocessor to calculte this on
	 * @return the new double value that goes into the overlay image
	 */
	public double getTLBRSymmetry(int xm, int ym, int xw, int yw, ImageProcessor ip){
		if (xw==1 || yw==1){ // for only one pixel symmetry is always 1
			return 1;
		} else { //multiple rows, need to be checked, xw wide, depends on even/uneven
			double diff=0;
			double sum=0;
			for (int j=0;j<xw/2;j++){	
				for (int i=0;i<yw/2;i++){
					diff+=Math.abs(ip.getPixel(xm+xw-1-j,ym+i)-ip.getPixel(xm+j,ym+yw-1-i));
					sum+=ip.getPixel(xm+xw-1-j, ym+i)+ip.getPixel(xm+j,ym+yw-1-i);
				}
			}
			return (1-(diff/sum));
		}
	}

	/**
	 * @param xm middle x pixel
	 * @param ym middle y pixel
	 * @param xw width over x
	 * @param yw height over y
	 * @param ip imageprocessor to calculte this on
	 * @return the new double value that goes into the overlay image
	 */
	public double getTRBLSymmetry(int xm, int ym, int xw, int yw, ImageProcessor ip){
		if (xw==1 || yw==1){ // for only one pixel symmetry is always 1
			return 1;
		} else { //multiple rows, need to be checked, xw wide, depends on even/uneven
			double diff=0;
			double sum=0;
			for (int j=0;j<xw/2;j++){	// need to process till end to prevent erronous data
				for (int i=0;i<yw/2;i++){ // need to process till end to prevent erronous data
					diff+=Math.abs(ip.getPixel(xm+j,ym+i)-ip.getPixel(xm+xw-1-j,ym+yw-1-i));
					sum+=ip.getPixel(xm+j, ym+i)+ip.getPixel(xm+xw-1-j,ym+yw-1-i);
				}
			}
			return (1-(diff/sum));
		}
	}

	/**
	 * Controls what needs to be done when a button is clicked in the dialog
	 */
	public void actionPerformed(ActionEvent e){
		Object source =e.getSource();
		if (source == okB){
			prevA=a;
			prevC=c;
			prevT=T;
			prevB=B;
			// here we need to implement the 3D, 4D options
			int [] Dim = image.getDimensions();
			if (Dim[2]==1 && Dim[3]==1 && Dim[4]==1){ 
				image.setRoi(ro);
				IJ.run(image, "Clear Outside", "slice");
				IJ.setForegroundColor(255, 255, 255);
				IJ.setBackgroundColor(0, 0, 0);
				IJ.run(image, "Fill", "slice");
				IJ.run(image, "Remove Overlay", "");
				IJ.run(image, "Select None", "");
				ByteProcessor bp= Thresholder.createMask(overlay);
				image.setProcessor(bp);
			} else {
				//IJ.log(""+T+" "+c+" "+back);
				GenericDialog choices = new GenericDialog("Multi dimensional");
				boolean cs=false, zs=false, ts=false;
				choices.addCheckbox("Apply to all channels",cs);
				choices.addCheckbox("Apply to all slices (z)", zs);
				choices.addCheckbox("Apply to all frames (t)", ts);
				choices.showDialog();

				cs=choices.getNextBoolean();
				zs=choices.getNextBoolean();
				ts=choices.getNextBoolean();
				//IJ.log(""+is.size());
				if (cs && ts && zs){
					for (int i=1; i<=Dim[2];i++){
						for (int j=1;j<=Dim[3];j++){
							for (int k=1;k<=Dim[4];k++){
								Mask3D(i,j,k);								
							}
						}
					}
					image.updateAndDraw();
				} else if (cs&&ts){
					int slice =image.getZ();
					for (int i=1; i<=Dim[2];i++){
						for (int k=1;k<=Dim[4];k++){
							Mask3D(i,slice,k);								
						}
					}
					image.updateAndDraw();
				}else if (cs && zs){
					int frame =image.getT();
					for (int i=1; i<=Dim[2];i++){
						for (int j=1;j<=Dim[3];j++){
							Mask3D(i,j,frame);								
						}
					}
					image.updateAndDraw();
				}else if (ts&&zs){
					int channel =image.getC();
					for (int j=1;j<=Dim[3];j++){
						for (int k=1;k<=Dim[4];k++){
							Mask3D(channel,j,k);								
						}
					}
					image.updateAndDraw();
				} else if (cs){
					int frame = image.getT();// get current time frame
					int slice =image.getZ(); // get current zstack
					for (int i=1; i<=Dim[2];i++){
						Mask3D(i,slice,frame);
					}
					image.updateAndDraw();
				} else if (zs){
					int channel = image.getC();
					int frame = image.getT();// get current time frame
					for (int i=1; i<=Dim[3];i++){
						Mask3D(channel,i,frame);
					}
					image.updateAndDraw();
				} else if (ts){
					int channel = image.getC();
					int slice = image.getZ();// get current slice
					for (int i=1; i<=Dim[4];i++){
						Mask3D(channel,slice,i);
					}
					image.updateAndDraw();
				}
				
				if (!cs&&!ts&&!zs){//handle only the single layer of the stack
					image.setRoi(ro);
					IJ.run(image, "Clear Outside", "slice");
					IJ.setForegroundColor(255, 255, 255);
					IJ.setBackgroundColor(0, 0, 0);
					IJ.run(image, "Fill", "slice");
					IJ.run(image, "Remove Overlay", "");
					IJ.run(image, "Select None", "");
				}
			}
							
		} else {
			IJ.run(image, "Remove Overlay", "");			
		}
	}

	/**
	 * For making the mask in 3D
	 * @param channel The channel to work on
	 * @param slice The slide to work on
	 * @param frame The frame to work on
	 */
	public void Mask3D(int channel, int slice, int frame){
		image.setPosition(channel,slice,frame);
		ip = image.getProcessor();
		width = ip.getWidth();
		height = ip.getHeight();
		bt = new FloatProcessor(width, height);
		overlay = new ImagePlus("overlay", bt);
		getThreshold2(T,c, B, true);
		IJ.run(overlay, "Create Selection", "");
		ro=overlay.getRoi();
		image.setRoi(ro);
		IJ.setForegroundColor(255, 255, 255);
		IJ.setBackgroundColor(0, 0, 0);
		IJ.run(image, "Clear Outside", "slice");
		IJ.run(image, "Fill", "slice");
		IJ.run(image, "Remove Overlay", "");
		IJ.run(image, "Select None", "");
	}

	/**
	 * @param Tval Current top threshold setting
	 * @param Cval Current method for thresholding
	 * @param Bval current bottom threshold setting
	 * @param recalc recalculate or not
	 * 
	 * This method gets the threshold
	 */
	public void getThreshold2(int Tval, int Cval, int Bval, boolean recalc){
		final int cCval=Cval; // needs to be final to be called from inner class
		final int cTval=Cval; // needs to be final to be called from inner class
		final int cBval=Cval; // needs to be final to be called from inner class
		if (recalc){
			// clear the progressbar initially
			int xw=0,yw=0;
			for (int i=0; i<width;i++){ // runs from 0 to width
				pb.setValue((i*100)/width+1);
				for (int j=0;j<height;j++){ // runs from 0 to height
					
					int xm = Math.max(i-prevA,0);
					int ym = Math.max(j-prevA,0);
					
					int xe = i+prevA;
					int ye = j+prevA;
					
					if (xe>width-1){ // if endpoint falls outside
						xw = width-i+prevA;
						
					} else if (i-prevA<0) { // cannot start lower than 0 but should not take the whole region then
						xw = prevA+i;
					} else {
						xw=2*prevA-1;
					}
	
					if (ye>height-1){ // if endpoint falls outside
						yw = height-j+prevA;
					} else if (j-prevA<0){
						yw = prevA+j;
					} else {
						yw=2*prevA-1;
					}
					
					Roi cr = new Roi(xm,ym,xw,yw);
					
					image.setRoi(cr);
					//IJ.run(image, "Fill", "slice");
					ImageStatistics is=null;
					try { 
						is = cr.getStatistics();
					} catch  (Exception e) {
						IJ.log(""+xm+", "+ym+", "+xw+", "+yw);
						IJ.log(e.toString());
						e.printStackTrace();
					}
					
					switch (cCval){ 
						//{"Mean", "Median", "Modal", "Standard deviation", "Maximum grey value", "Minimm grey value", "Integrated density", "Skewness", "Kurtosis"
						// "Harmonic Mean", "Geometric Mean", "Winsorized Mean", "Quadruple Mean", "Cubic Mean","Left to Right symmetry","Top to Bottom symmetry","Top-left to Right-bottom symmetry","Bottom-left to Top-right symmetry"  
						case 0: bt.putPixelValue(i,j,is.mean);
								break;
						case 1:bt.putPixelValue(i,j,is.median);
								break;
						case 2:bt.putPixelValue(i,j,is.mode);
								break;
						case 3:bt.putPixelValue(i,j,is.stdDev);
								break;
						case 4:bt.putPixelValue(i,j,is.max);
								break;
						case 5:bt.putPixelValue(i,j,is.min);
								break;
						case 6:bt.putPixelValue(i,j,is.mean*xw*yw);
								break;					
						case 7:bt.putPixelValue(i,j,is.skewness);
								break;					
						case 8:bt.putPixelValue(i,j,is.kurtosis);
								break;
						case 9:bt.putPixelValue(i,j,getHarmonicMean(xm,ym,xw,yw,ip)); // for harmonic mean
								break; 					
						case 10:bt.putPixelValue(i,j,getGeometricMean(xm,ym,xw,yw,ip)); // for geometric mean
								break;					
						case 11:bt.putPixelValue(i,j,(is.min+is.max)/2); // for winsorized mean
								break;					
						case 12:bt.putPixelValue(i,j,getPowerMean(xm,ym,xw,yw,ip,2)); // for quadruple mean
								break;					
						case 13:bt.putPixelValue(i,j,getPowerMean(xm,ym,xw,yw,ip,3)); // for cubic mean
								break;					
						case 14:bt.putPixelValue(i,j,getLRSymmetry(xm,ym,xw,yw, ip)); //Left Right symmetry 
								break;					
						case 15:bt.putPixelValue(i,j,getTBSymmetry(xm,ym,xw,yw, ip)); // Top Bottom symmetry
								break;
						case 16:bt.putPixelValue(i,j,getTLBRSymmetry(xm,ym,xw,yw,ip)); // top-left to right bottom symmetry
								break;
						case 17:bt.putPixelValue(i,j,getTRBLSymmetry(xm,ym,xw,yw,ip)); // bottom left to top right symmetry
								break;				
						case 18:bt.putPixelValue(i,j,Math.pow(Math.pow(getTRBLSymmetry(xm,ym,xw,yw,ip),2)+Math.pow(getLRSymmetry(xm,ym,xw,yw,ip),2)+Math.pow(getTBSymmetry(xm,ym,xw,yw,ip),2)+Math.pow(getTLBRSymmetry(xm,ym,xw,yw,ip),2), 0.25));
								// all symmertry cubic combined
								break;
						case 19:bt.putPixelValue(i,j,getFFTStdev(xm,ym,xw,yw,ip));
								// all symmertry cubic combined
								break;
						case 20:bt.putPixelValue(i,j,getFFTMean(xm,ym,xw,yw,ip));
								// all symmertry cubic combined
								break;
						case 21:bt.putPixelValue(i,j,getFFTKurt(xm,ym,xw,yw,ip));
								// all symmertry cubic combined
								break;
						case 22:bt.putPixelValue(i,j,getFFTSkew(xm,ym,xw,yw,ip));
								// all symmertry cubic combined
								break;
					}//switch
				} //j
			} //i

			IJ.run(overlay, "Enhance Contrast", "saturated=0.0");
			ImageStatistics ois = overlay.getStatistics();
			double minS = ois.min;
			double maxS = ois.max;
			double diff = maxS-minS;
			double steps = diff/100;
			IJ.log(""+cBval+", "+cTval);
			IJ.setRawThreshold(overlay, ois.min+cBval*steps, ois.min+cTval*steps, null);
			ro = ThresholdToSelection.run(overlay);
			Overlay ov = new Overlay(ro);
			ov.setFillColor(Color.red);
			image.setOverlay(ov);
		}
	}
    /**
     * Purely for testing purposes
     * @param args are not used
     */
    public static void main(String[] args) {
    	ImagePlus imp = IJ.openImage("http://imagej.nih.gov/ij/images/blobs.gif");
    	imp.show();
    	Special_Threshold sp = new Special_Threshold();
    	sp.run("");
    }
}

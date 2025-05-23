/*-
 * #%L
 * This file is part of QuPath.
 * %%
 * Copyright (C) 2018 - 2022 QuPath developers, The University of Edinburgh
 * %%
 * QuPath is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 * 
 * QuPath is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License 
 * along with QuPath.  If not, see <https://www.gnu.org/licenses/>.
 * #L%
 */

package qupath.lib.display;

import qupath.lib.awt.common.BufferedImageTools;
import qupath.lib.color.ColorDeconvolutionHelper;
import qupath.lib.color.ColorDeconvolutionStains;
import qupath.lib.color.ColorToolsAwt;
import qupath.lib.color.ColorTransformer;
import qupath.lib.color.ColorTransformer.ColorTransformMethod;
import qupath.lib.common.ColorTools;
import qupath.lib.images.ImageData;

import java.awt.image.BufferedImage;
import java.awt.image.ColorModel;
import java.awt.image.IndexColorModel;
import java.util.Objects;

class ColorDeconvolutionInfo extends AbstractSingleChannelInfo {

	private transient int stainNumber;
	private transient ColorDeconvolutionStains stains;
	private transient ColorModel colorModel = null;
	private transient ColorModel colorModelInverted = null;
	private transient Integer color;

	private ColorTransformMethod method;

	public ColorDeconvolutionInfo(final ImageData<BufferedImage> imageData, ColorTransformMethod method) {
		super(imageData);
		this.method = method;
		switch (method) {
		case Stain_1:
			stainNumber = 1;
			break;
		case Stain_2:
			stainNumber = 2;
			break;
		case Stain_3:
			stainNumber = 3;
			break;
		default:
			stainNumber = -1;
		}
		setMinMaxAllowed(0f, 3f);
		setMinDisplay(0);
		setMaxDisplay(1.5f);
	}

	final void ensureStainsUpdated() {
		ImageData<BufferedImage> imageData = getImageData();
		stains = imageData == null ? null : imageData.getColorDeconvolutionStains();
		Integer newColor = null;
		boolean createColorModel = false;
		if (stainNumber < 0) {
			newColor = ColorTools.packRGB(255, 255, 255);
			if (!Objects.equals(newColor, color)) {
				colorModel = ColorTransformer.getDefaultColorModel(method);
				createColorModel = true;
			}
		} else if (stains != null) {
			newColor = stains.getStain(stainNumber).getColor();
			if (!Objects.equals(newColor, color)) {
				colorModel = ColorToolsAwt.getIndexColorModel(stains.getStain(stainNumber), true);				
				createColorModel = true;
			}
		}
		color = newColor;
		if (createColorModel && colorModel instanceof IndexColorModel) {
			var s = stains.getStain(stainNumber);
			int c = s.getColor();
			colorModelInverted = ColorToolsAwt.createIndexColorModel(
					255 - ColorTools.red(c),
					255 - ColorTools.green(c),
					255 - ColorTools.blue(c),
					true);
		}
	}

	private static boolean isRGB(BufferedImage img) {
		return BufferedImageTools.is8bitColorType(img.getType());
	}

	@Override
	public float getValue(BufferedImage img, int x, int y) {
		ensureStainsUpdated();
		if (stains == null)
			return 0f;
		if (isRGB(img)) {
			return getValueRGB(img, x, y);
		} else {
			float r = img.getRaster().getSampleFloat(x, y, 0);
			float g = img.getRaster().getSampleFloat(x, y, 1);
			float b = img.getRaster().getSampleFloat(x, y, 2);
			if (method == ColorTransformer.ColorTransformMethod.Optical_density_sum) {
				return r + g + b;
			} else {
				var invMat = stains.getMatrixInverse();
				return (float) (r * invMat[0][stainNumber - 1] + g * invMat[1][stainNumber - 1] + b * invMat[2][stainNumber - 1]);
			}
		}
	}

	private float getValueRGB(BufferedImage img, int x, int y) {
		int rgb = img.getRGB(x, y);
		if (method == null)
			return ColorTransformer.colorDeconvolveRGBPixel(rgb, stains, stainNumber-1);
		else if (method == ColorTransformMethod.Optical_density_sum) {
			int r = ColorTools.red(rgb);
			int g = ColorTools.green(rgb);
			int b = ColorTools.blue(rgb);
			return (float)(ColorDeconvolutionHelper.makeOD(r, stains.getMaxRed()) +
					ColorDeconvolutionHelper.makeOD(g, stains.getMaxGreen()) +
					ColorDeconvolutionHelper.makeOD(b, stains.getMaxBlue()));
		} else
			return ColorTransformer.getPixelValue(rgb, method, stains);
	}

	@Override
	public synchronized float[] getValues(BufferedImage img, int x, int y, int w, int h, float[] array) {
		ensureStainsUpdated();
		if (stains == null) {
			if (array == null)
				return new float[w * h];
			return array;
		}
		if (isRGB(img)) {
			return getValuesRGB(img, x, y, w, h, array);
		} else {
			if (method == ColorTransformer.ColorTransformMethod.Optical_density_sum) {
				// Reuse the array if we can
				float[] r = ColorDeconvolutionHelper.getOpticalDensities(img.getRaster(), 0, stains.getMaxRed(), array);
				float[] g = ColorDeconvolutionHelper.getOpticalDensities(img.getRaster(), 1, stains.getMaxGreen(), null);
				float[] b = ColorDeconvolutionHelper.getOpticalDensities(img.getRaster(), 2, stains.getMaxBlue(), null);
				array = r;
				for (int i = 0; i < array.length; i++) {
					array[i] = r[i] + g[i] + b[i];
				}
				return array;
			} else {
				return ColorDeconvolutionHelper.colorDeconvolve(img, stains, stainNumber - 1, array);
			}
		}
	}

	private float[] getValuesRGB(BufferedImage img, int x, int y, int w, int h, float[] array) {
		int[] buffer = RGBDirectChannelInfo.getRGBIntBuffer(img);
		if (buffer == null)
			buffer = img.getRGB(x, y, w, h, null, 0, w);
		return ColorTransformer.getTransformedPixels(buffer, method, array, stains);
	}

	@Override
	protected ColorModel getColorModel(ChannelDisplayMode mode) {
		switch (mode) {
		// Default visualization for deconvolved is with a white background
		case GRAYSCALE:
		case INVERTED_GRAYSCALE:
			return CM_GRAYSCALE_INVERTED;
		case INVERTED_COLOR:
			return colorModelInverted;
		case COLOR:
		default:
			return colorModel;
		}
	}
	
	
	@Override
	public String getName() {
		ensureStainsUpdated();
		if (stainNumber > 0) {
			if (stains == null)
				return "Stain " + stainNumber + " (missing)";
			else
				return stains.getStain(stainNumber).getName();
		}
		if (method != null)
			return method.toString();
		return "Unknown color deconvolution transform";
	}

	@Override
	public boolean doesSomething() {
		return true;
	}

	@Override
	public boolean isAdditive() {
		return false;
	}

	@Override
	public Integer getColor() {
		if (color == null)
			ensureStainsUpdated();
		return color;
	}

	@Override
	public boolean isMutable() {
		return true;
	}
	
	@Override
	public ColorTransformMethod getMethod() {
		return method;
	}

}
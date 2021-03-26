package qupath.lib.analysis.images;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import qupath.lib.analysis.images.SimpleImages.FloatArraySimpleImage;

@SuppressWarnings("javadoc")
public class TestSimpleImages {
	
	@Test
	public void test_simpleImageCreation() {
		float[] data = new float[600*800];
		for (int i = 0; i < 600*800; i++) {
			data[i] = (float) (i*1.5);
		}
		
		SimpleImage img = SimpleImages.createFloatImage(200, 400);
		SimpleImage img2 = SimpleImages.createFloatImage(data, 600, 800);
		
		// Check dimensions
		assertEquals(200, img.getWidth());
		assertEquals(400, img.getHeight());
		assertEquals(600, img2.getWidth());
		assertEquals(800, img2.getHeight());
		
		// Check getting pixel values
		for (int i = 0; i < 200*400; i++) {
			assertEquals(img.getValue(i%img.getWidth(), i/img.getWidth()), 0);
		}
		for (int i = 0; i < 600*800; i++) {
			assertEquals(img2.getValue(i%img2.getWidth(), i/img2.getWidth()), (float) (i*1.5));
		}
		
		// Check getting invalid pixel values (boundary values)
		Assertions.assertThrows(IndexOutOfBoundsException.class, () -> img2.getValue(600, 799));
		Assertions.assertThrows(IndexOutOfBoundsException.class, () -> img2.getValue(599, 800));
		
		// Check type
		assertTrue(img instanceof FloatArraySimpleImage);
		
		// Check arrays
		FloatArraySimpleImage imgConverted = (FloatArraySimpleImage)img2;
		// Same array object
		assertEquals(data, imgConverted.getArray(true));
		// Same array content
		assertArrayEquals(data, imgConverted.getArray(false));
		
		// Check setting pixel values
		imgConverted.setValue(100, 100, (float)5.3);
		assertEquals((float)5.3, imgConverted.getValue(100, 100));

		// Check setting invalid pixel values (boundary values)
		Assertions.assertThrows(IndexOutOfBoundsException.class, () -> imgConverted.setValue(600, 799, (float)1.1111));
		Assertions.assertThrows(IndexOutOfBoundsException.class, () -> imgConverted.setValue(599, 800, (float)1.1111));
	}
}
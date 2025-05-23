/*-
 * #%L
 * This file is part of QuPath.
 * %%
 * Copyright (C) 2014 - 2016 The Queen's University of Belfast, Northern Ireland
 * Contact: IP Management (ipmanagement@qub.ac.uk)
 * Copyright (C) 2018 - 2020 QuPath developers, The University of Edinburgh
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

package qupath.lib.gui.viewer.tools.handlers;

import java.awt.geom.Point2D;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryCollection;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.simplify.VWSimplifier;
import org.locationtech.jts.util.GeometricShapeFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javafx.scene.Cursor;
import javafx.scene.input.MouseEvent;
import qupath.lib.gui.prefs.PathPrefs;
import qupath.lib.gui.viewer.tools.QuPathPenManager;
import qupath.lib.gui.viewer.tools.QuPathPenManager.PenInputManager;
import qupath.lib.objects.PathAnnotationObject;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathObjects;
import qupath.lib.objects.PathTileObject;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.objects.hierarchy.PathObjectHierarchy;
import qupath.lib.regions.ImagePlane;
import qupath.lib.roi.RoiTools;
import qupath.lib.roi.GeometryTools;
import qupath.lib.roi.ROIs;
import qupath.lib.roi.RectangleROI;
import qupath.lib.roi.interfaces.ROI;

/**
 * Tool for drawing (and subtract from) freehand regions, optionally adapting brush size to magnification.
 * 
 * @author Pete Bankhead
 *
 */
public class BrushToolEventHandler extends AbstractPathROIToolEventHandler {
	
	private static final Logger logger = LoggerFactory.getLogger(BrushToolEventHandler.class);
	
	/**
	 * A collection of classes that should be ignored when
	 */
	static Set<PathClass> reservedPathClasses = Collections.singleton(
			PathClass.StandardPathClasses.REGION
			);
	
	double lastRequestedCursorDiameter = Double.NaN;
	Cursor requestedCursor;
	
	/**
	 * The object currently being edited by the Brush.
	 * This is set when the mouse is pressed, to avoid relying on QuPathViewer.getSelectedObject() 
	 * (in case something has sneakily changed this).
	 */
	private PathObject currentObject;
	
	Point2D lastPoint;
	
//	/**
//	 * Cache the last 50 cursors we saw
//	 */
//	private static Map<String, Cursor> cursorCache = new LinkedHashMap<String, Cursor>() {
//		private static final long serialVersionUID = 1L;
//		@Override
//		protected boolean removeEldestEntry(Map.Entry<String, Cursor> eldest) {
//	        return size() > 50;
//	    }
//	};
	
	/**
	 * Returns false.
	 */
	@Override
	protected boolean preferReturnToMove() {
		return false;
	}
	
	protected Cursor getRequestedCursor() {
		// Display of image cursors seems buggy, at least on macOS?
		// TODO: Check if image cursors are buggy on all platforms or may be reinstated
		return Cursor.CROSSHAIR;
//		if (PathPrefs.getUseTileBrush())
//			return Cursor.CROSSHAIR;
//		
//		double res = 0.05;
//		
//		double diameter = getBrushDiameter() / viewer.getDownsampleFactor();
//		if (requestedCursor != null && Math.abs(diameter - lastRequestedCursorDiameter) < res)
//			return requestedCursor;
//		
//		Color color = viewer.getSuggestedOverlayColorFX();
//		String key = color.toString() + (int)Math.round(diameter * (1.0/res));
//		requestedCursor = cursorCache.get(key);
//		if (requestedCursor != null)
//			return requestedCursor;
//		
//		Ellipse e = new Ellipse(diameter/2, diameter/2);
//		e.setFill(null);
//		e.setStroke(color);
//		Image image = e.snapshot(snapshotParameters, null);
//		requestedCursor = new ImageCursor(image, image.getWidth()/2, image.getHeight()/2);
//		
//		this.registerTool(viewer);
//
//		cursorCache.put(key, requestedCursor);
//
//		return requestedCursor;
	}
	
	
	@Override
	public void mouseExited(MouseEvent e) {
//		ensureCursorType(Cursor.DEFAULT);
	}
	
	@Override
	public void mouseEntered(MouseEvent e) {
//		ensureCursorType(getRequestedCursor());
	}
	
	
	@Override
	public void mouseMoved(MouseEvent e) {
		ensureCursorType(getRequestedCursor());
	}
	
	
	@Override
	public void mousePressed(MouseEvent e) {
		if (!e.isPrimaryButtonDown() || e.isConsumed()) {
            return;
        }
		
		ensureCursorType(getRequestedCursor());
		
		var viewer = getViewer();
		PathObjectHierarchy hierarchy = viewer.getHierarchy();
		if (hierarchy == null)
			return;
		
		PathObject currentObject = viewer.getSelectedObject();
		
		// Ignore the current object if it belongs to a different image plane
		if (currentObject == null ||
				PathPrefs.selectionModeStatus().get() ||
				!(currentObject instanceof PathAnnotationObject) || 
				(!currentObject.isEditable()) || 
				currentObject.getROI().getZ() != viewer.getZPosition() || 
				currentObject.getROI().getT() != viewer.getTPosition()) {
			currentObject = null;
		}
		
		// Determine if we are creating a new object
//		boolean createNew = currentObject == null || e.getClickCount() > 1;// || (!currentObject.getROI().contains(p.getX(), p.getY()) && !e.isAltDown());
		Point2D p = mouseLocationToImage(e, false, requestPixelSnapping());
		double xx = p.getX();
		double yy = p.getY();
		if (xx < 0 || yy < 0 || xx >= viewer.getServerWidth() || yy >= viewer.getServerHeight()) {
			// Even if clicking outside the image, we want the brush to function when dragged inside the image
			if (currentObject != null) {
				this.currentObject = currentObject;
				lastPoint = p;
				// Ensure handles go away if we have an object
				viewer.getROIEditor().setROI(null);
			} else {
				this.currentObject = null;
			}
			return;
		}
		
//		boolean createNew = currentObject == null || !(currentObject instanceof PathAnnotationObject) || (currentObject.hasChildren()) || (PathPrefs.getBrushCreateNewObjects() && !ROIHelpers.areaContains(currentObject.getROI(), p.getX(), p.getY()) && !isSubtractMode(e));
		boolean createNew = currentObject == null || 
				(!e.isShiftDown() && PathPrefs.brushCreateNewObjectsProperty().get() && !RoiTools.areaContains(currentObject.getROI(), p.getX(), p.getY()) && !isSubtractMode(e));
		if (isSubtractMode(e))
			createNew = false;
		
		// See if, rather than creating something, we can instead reactivate a current object
		boolean multipleClicks = e.getClickCount() > 1;
		if (!PathPrefs.selectionModeStatus().get() && (multipleClicks || (createNew && !e.isShiftDown()))) {
			// See if, rather than creating something, we can instead reactivate a current object
			if (multipleClicks) {
				PathObject objectSelectable = ToolUtils.getSelectableObject(viewer, p.getX(), p.getY(), e.getClickCount() - 1);
				if (objectSelectable != null && objectSelectable.isEditable() && objectSelectable.hasROI() && objectSelectable.getROI().isArea()) {
					createNew = false;
					viewer.setSelectedObject(objectSelectable);
					currentObject = objectSelectable;
				} else if (createNew) {
					viewer.setSelectedObject(null);
					currentObject = null;
				}
			} else if (!PathPrefs.selectionModeStatus().get()) {
					List<PathObject> listSelectable = ToolUtils.getSelectableObjectList(viewer, p.getX(), p.getY());
					PathObject objectSelectable = null;
					for (PathObject temp : listSelectable) {
						if (temp.isEditable() && temp instanceof PathAnnotationObject && temp.hasROI() && temp.getROI().isArea()) { //temp.getROI() instanceof AreaROI) {
							objectSelectable = temp;
							break;
						}
					}
					if (objectSelectable != null) {
						createNew = false;
						viewer.setSelectedObject(objectSelectable);
						currentObject = objectSelectable;
					} else if (createNew) {
						viewer.setSelectedObject(null);
						currentObject = null;
					}
			}
		}
		
		// Can only modify annotations
		if (!createNew && !(currentObject != null && currentObject.isAnnotation() && currentObject.isEditable() && RoiTools.isShapeROI(currentObject.getROI())))
			return;
						
		// Get the parent, in case we need to constrain the shape
//		PathObject parent = null;
//		if (currentObject != null) {
//			parent = currentObject.getParent();
//		}
//		var currentObject2 = currentObject;
//		if (parent == null || parent.isDetection()) {
//			parent = getSelectableObjectList(p.getX(), p.getY())
//					.stream()
//					.filter(o -> !o.isDetection() && o != currentObject2)
//					.findFirst()
//					.orElseGet(() -> null);
//		}
//		setConstrainedAreaParent(hierarchy, parent, currentObject);
		updatingConstrainingObjects(viewer, xx, yy, Collections.singleton(currentObject));

		
		// Need to remove the object from the hierarchy while editing it
		if (!createNew && currentObject != null) {
			hierarchy.removeObjectWithoutUpdate(currentObject, true);
		}

		ROI shapeROI = createNew ? null : currentObject.getROI();
		if (createNew) {
			creatingTiledROI = false; // Reset this
			this.currentObject = createNewAnnotation(e, p.getX(), p.getY());
			viewer.getROIEditor().setROI(null);
		} else {
			this.currentObject = getUpdatedObject(e, shapeROI, currentObject, -1);
			viewer.setSelectedObject(this.currentObject);
			viewer.getROIEditor().setROI(null); // Avoids handles appearing?
		}		
		lastPoint = p;
	}
	

	
	
	@Override
	public void mouseDragged(MouseEvent e) {
		// Note: if the 'freehand' part of the polygon creation isn't desired, just comment out this whole method
		super.mouseDragged(e);
		
		ensureCursorType(getRequestedCursor());
		if (!e.isPrimaryButtonDown()) {
            return;
        }
		
		// Can only modify annotations
		var viewer = getViewer();
		PathObject pathObject = viewer.getSelectedObject();
		if (pathObject == null || !pathObject.isAnnotation() || !pathObject.isEditable())
			return;
		if (pathObject != currentObject) {
			logger.debug("Selected object has changed from {} to {}", currentObject, pathObject);
			return;
		}

		ROI currentROI = pathObject.getROI();
		if (currentROI == null)
			return;

        PathObject pathObjectUpdated = getUpdatedObject(e, currentROI, pathObject, -1);

		if (pathObject != pathObjectUpdated) {
			viewer.setSelectedObject(pathObjectUpdated, PathPrefs.selectionModeStatus().get());
		} else {
			viewer.repaint();
		}
	}
	
	
	protected boolean isSubtractMode(MouseEvent e) {
		PenInputManager manager = QuPathPenManager.getPenManager();
		if (manager.isEraser())
			return true;
		return e != null && (e.isAltDown() && !PathPrefs.selectionModeStatus().get());
	}
	
	
	private PathObject getUpdatedObject(MouseEvent e, ROI shapeROI, PathObject currentObject, double flatness) {
		Point2D p = mouseLocationToImage(e, false, requestPixelSnapping());
		
		// Don't do anything if outside the image bounds
		if (p.getX() < 0 || p.getY() < 0 || p.getX() >= getViewer().getServerWidth() || p.getY() >= getViewer().getServerHeight())
			return currentObject;
		
		var viewer = getViewer();
		ImagePlane plane = shapeROI == null ? ImagePlane.getPlane(viewer.getZPosition(), viewer.getTPosition()) : shapeROI.getImagePlane();
		Geometry shapeNew;
		boolean subtractMode = isSubtractMode(e);
		Geometry shapeCurrent = shapeROI == null ? null : shapeROI.getGeometry();
		
		Geometry shapeDrawn = createShape(e, p.getX(), p.getY(),
				PathPrefs.useTileBrushProperty().get() && !e.isShiftDown(),
				subtractMode ? null : shapeCurrent);
		
		if (shapeDrawn == null)
			return currentObject;
		
		// Do our pixel snapping now, with the simpler geometry (rather than latter when things are already complex)
		if (requestPixelSnapping())
			shapeDrawn = GeometryTools.roundCoordinates(shapeDrawn);
		
		// Make sure we don't have any linestrings/points
		shapeDrawn = GeometryTools.ensurePolygonal(shapeDrawn);
		
		lastPoint = p;
		try {
			if (shapeROI != null) {
				// Check to see if any changes are required at all
				if (shapeDrawn == null || (subtractMode && !shapeCurrent.intersects(shapeDrawn)) || 
						(!subtractMode && shapeCurrent.covers(shapeDrawn)))
					return currentObject;
				
				// TODO: Consider whether a preference should be used rather than the shift key?
				// Anyhow, this will switch to 'dodge' mode, and avoid overlapping existing annotations
				boolean avoidOtherAnnotations = requestParentClipping(e);
				if (subtractMode) {
					// If subtracting... then just subtract
					shapeNew = shapeROI.getGeometry().difference(shapeDrawn);
				} else if (avoidOtherAnnotations) {
					shapeNew = shapeCurrent.union(shapeDrawn);
					shapeNew = refineGeometryByParent(shapeNew);
				} else {
					// Just add, regardless of whether there are other annotations below or not
					var temp = shapeROI.getGeometry();
					try {
						shapeNew = temp.union(shapeDrawn);
					} catch (Exception e2) {
						shapeNew = shapeROI.getGeometry();
					}
				}
			} else {
				shapeNew = shapeDrawn;
			}
			
			// If we aren't snapping, at least remove some vertices
			if (!requestPixelSnapping()) {
				try {
					shapeNew = VWSimplifier.simplify(shapeNew, 0.1);
				} catch (Exception e2) {
                    logger.error("Error simplifying ROI: {}", e2.getMessage(), e2);
				}
			}
			
			// Make sure we fit inside the image
			shapeNew = GeometryTools.constrainToBounds(shapeNew, 0, 0, viewer.getServerWidth(), viewer.getServerHeight());

			// Sometimes we can end up with a GeometryCollection containing lines/non-areas... if so, remove these
			if (shapeNew instanceof GeometryCollection) {
				shapeNew = GeometryTools.ensurePolygonal(shapeNew);
			}
					
			ROI roiNew = GeometryTools.geometryToROI(shapeNew, plane);
			
			if (currentObject instanceof PathAnnotationObject) {
				((PathAnnotationObject)currentObject).setROI(roiNew);
				return currentObject;
			}
			
//			shapeNew = new PathAreaROI(new Area(shapeNew.getShape()));
			PathObject pathObjectNew = PathObjects.createAnnotationObject(roiNew, PathPrefs.autoSetAnnotationClassProperty().get());
			if (currentObject != null) {
				pathObjectNew.setName(currentObject.getName());
				pathObjectNew.setColor(currentObject.getColor());
				pathObjectNew.setPathClass(currentObject.getPathClass());
			}
			return pathObjectNew;

		} catch (Exception ex) {
			logger.error("Error updating ROI", ex);
			return currentObject;
		}
	}
	
	
	@Override
	public void mouseReleased(MouseEvent e) {
		super.mouseReleased(e);
		
		ensureCursorType(Cursor.DEFAULT);
		
		if (e.isConsumed())
			return;

		if (currentObject != null) {
			commitObjectToHierarchy(e, currentObject);
		}

		lastPoint = null;
		this.currentObject = null;

		resetConstrainingObjects();
	}
	
	
	protected double getBrushDiameter() {
		PenInputManager manager = QuPathPenManager.getPenManager();
		double scale = manager.getPressure();
		var viewer = getViewer();
		double brushDiameter = PathPrefs.brushDiameterProperty().get() * scale;
		if (PathPrefs.brushScaleByMagProperty().get())
			return brushDiameter * viewer.getDownsampleFactor();
		else
			return brushDiameter;
	}
	
	
	/**
	 * Create a new Geometry using the specified tool, assuming a user click/drag at the provided x &amp; y coordinates.
	 * @param e 
	 * 
	 * @param x
	 * @param y
	 * @param useTiles If true, request generating a shape from existing tile objects.
	 * @param addToShape If provided, it can be assumed that any new shape ought to be added to this one.
	 *                   The purpose is that this method may (optionally) use the shape to refine the one it will generate, 
	 *                   e.g. to avoid having isolated or jagged boundaries.
	 * @return
	 */
	protected Geometry createShape(MouseEvent e, double x, double y, boolean useTiles, Geometry addToShape) {
		
		// See if we're on top of a tile
		if (useTiles) {
			List<PathObject> listSelectable = ToolUtils.getSelectableObjectList(getViewer(), x, y);
			for (PathObject temp : listSelectable) {
//				if ((temp instanceof PathDetectionObject) && temp.getROI() instanceof PathArea)
				if (temp instanceof PathTileObject && temp.hasROI() && temp.getROI().isArea() && !(temp.getROI() instanceof RectangleROI)) {
					creatingTiledROI = true;
					return temp.getROI().getGeometry();
				}
			}
			// If we're currently creating a tiled, ROI, but now not clicked on a tile, just return
			if (creatingTiledROI)
				return null;
		}
		
		// Compute a diameter scaled according to the pressure being applied
		double diameter = Math.max(1, getBrushDiameter());
		
		Geometry geometry;
		if (lastPoint == null) {
			var shapeFactory = new GeometricShapeFactory(getGeometryFactory());
			shapeFactory.setCentre(new Coordinate(x, y));
			shapeFactory.setSize(diameter);
//			shapeFactory.setCentre(new Coordinate(x-diameter/2, y-diameter/2));
			geometry = shapeFactory.createEllipse();
		} else {
			if (lastPoint.distanceSq(x, y) == 0)
				return null;
			var factory = getGeometryFactory();
			geometry = factory.createLineString(new Coordinate[] {
					new Coordinate(lastPoint.getX(), lastPoint.getY()),
					new Coordinate(x, y)}).buffer(diameter/2.0);
		}
		
		return geometry;
	}
	
	private boolean creatingTiledROI = false;
	
	protected GeometryFactory getGeometryFactory() {
        return GeometryTools.getDefaultFactory();
	}

	@Override
	protected ROI createNewROI(MouseEvent e, double x, double y, ImagePlane plane) {
		creatingTiledROI = false;
		lastPoint = null;
		Geometry geom = createShape(e, x, y, PathPrefs.useTileBrushProperty().get(), null);
		if (geom == null || geom.isEmpty())
			return ROIs.createEmptyROI();
		var viewer = getViewer();
		geom = GeometryTools.roundCoordinates(geom);
		geom = GeometryTools.constrainToBounds(geom, 0, 0, viewer.getServerWidth(), viewer.getServerHeight());
		return GeometryTools.geometryToROI(geom, plane);
	}
	
}

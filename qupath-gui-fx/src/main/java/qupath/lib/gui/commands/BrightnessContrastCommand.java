/*-
 * #%L
 * This file is part of QuPath.
 * %%
 * Copyright (C) 2014 - 2016 The Queen's University of Belfast, Northern Ireland
 * Contact: IP Management (ipmanagement@qub.ac.uk)
 * Copyright (C) 2018 - 2023 QuPath developers, The University of Edinburgh
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

package qupath.lib.gui.commands;

import java.awt.image.BufferedImage;
import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import javafx.beans.Observable;
import javafx.event.ActionEvent;
import javafx.scene.Node;
import javafx.scene.control.ToggleButton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.beans.binding.ObjectBinding;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.beans.value.ObservableValue;
import javafx.collections.FXCollections;
import javafx.collections.transformation.FilteredList;
import javafx.event.EventHandler;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.chart.NumberAxis;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ColorPicker;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.Slider;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.control.cell.CheckBoxTableCell;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.scene.text.Text;
import javafx.scene.text.TextAlignment;
import javafx.stage.Stage;
import qupath.lib.analysis.stats.Histogram;
import qupath.lib.common.GeneralTools;
import qupath.lib.display.ChannelDisplayInfo;
import qupath.lib.display.DirectServerChannelInfo;
import qupath.lib.display.ImageDisplay;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.charts.HistogramPanelFX;
import qupath.lib.gui.charts.HistogramPanelFX.HistogramData;
import qupath.lib.gui.charts.HistogramPanelFX.ThresholdedChartWrapper;
import qupath.fx.dialogs.Dialogs;
import qupath.lib.gui.prefs.PathPrefs;
import qupath.lib.gui.tools.ColorToolsFX;
import qupath.fx.utils.GridPaneUtils;
import qupath.lib.gui.viewer.QuPathViewer;
import qupath.lib.images.ImageData;
import qupath.lib.images.servers.ImageChannel;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.images.servers.ImageServerMetadata;

/**
 * Command to show a Brightness/Contrast dialog to adjust the image display.
 * 
 * @author Pete Bankhead
 *
 */
public class BrightnessContrastCommand implements Runnable {
	
	private static Logger logger = LoggerFactory.getLogger(BrightnessContrastCommand.class);
	
	private static DecimalFormat df = new DecimalFormat("#.###");

	/**
	 * Style used for labels that display warning text.
	 */
	private static String WARNING_STYLE = "-fx-text-fill: red;";

	private QuPathGUI qupath;
	private QuPathViewer viewer;
	private ImageDisplay imageDisplay;

	private ImageDataPropertyChangeListener imageDataPropertyChangeListener;
	
	private Slider sliderMin;
	private Slider sliderMax;
	private Slider sliderGamma;
	private Stage dialog;
	
	private boolean slidersUpdating = false;

	private TableView<ChannelDisplayInfo> table = new TableView<>();
	private StringProperty filterText = new SimpleStringProperty("");
	private BooleanProperty useRegex = new SimpleBooleanProperty(false);
	private ObjectBinding<Predicate<ChannelDisplayInfo>> predicate = createChannelDisplayPredicateBinding(filterText);

	private ColorPicker picker = new ColorPicker();
	
	private HistogramPanelFX histogramPanel = new HistogramPanelFX();
	private ThresholdedChartWrapper chartWrapper = new ThresholdedChartWrapper(histogramPanel.getChart());
	
	private Tooltip chartTooltip = new Tooltip(); // Basic stats go here now
	private ContextMenu popup;
	private BooleanProperty showGrayscale = new SimpleBooleanProperty(false);
	private BooleanProperty invertBackground = new SimpleBooleanProperty(false);
	
	private BrightnessContrastKeyListener keyListener = new BrightnessContrastKeyListener();
	
	/**
	 * Constructor.
	 * @param qupath
	 */
	public BrightnessContrastCommand(final QuPathGUI qupath) {
		this.qupath = qupath;
		this.qupath.imageDataProperty().addListener(this::handleImageDataChange);
		// Add 'pure' red, green & blue to the available color picker colors
		picker.getCustomColors().addAll(
				ColorToolsFX.getCachedColor(255, 0, 0),
				ColorToolsFX.getCachedColor(0, 255, 0),
				ColorToolsFX.getCachedColor(0, 0, 255),
				ColorToolsFX.getCachedColor(255, 255, 0),
				ColorToolsFX.getCachedColor(0, 255, 255),
				ColorToolsFX.getCachedColor(255, 0, 255));
	}

	@Override
	public void run() {
		if (dialog == null)
			dialog = createDialog();
		dialog.show();
	}


	private Stage createDialog() {
		if (!isInitialized())
			initializeSliders();

		initializePopup();

		handleImageDataChange(null, null, qupath.getImageData());

		BorderPane pane = new BorderPane();

		GridPane box = new GridPane();
		String blank = "      ";
		Label labelMin = new Label("Min display");
		Tooltip tooltipMin = new Tooltip("Set minimum lookup table value - double-click the value to edit manually");
		Label labelMinValue = new Label(blank);
		labelMinValue.setTooltip(tooltipMin);
		labelMin.setTooltip(tooltipMin);
		sliderMin.setTooltip(tooltipMin);
		labelMin.setLabelFor(sliderMin);
		labelMinValue.textProperty().bind(createSliderTextBinding(sliderMin));
		box.add(labelMin, 0, 0);
		box.add(sliderMin, 1, 0);
		box.add(labelMinValue, 2, 0);

		Label labelMax = new Label("Max display");
		Tooltip tooltipMax = new Tooltip("Set maximum lookup table value - double-click the value to edit manually");
		labelMax.setTooltip(tooltipMax);
		Label labelMaxValue = new Label(blank);
		labelMaxValue.setTooltip(tooltipMax);
		sliderMax.setTooltip(tooltipMax);
		labelMax.setLabelFor(sliderMax);
		labelMaxValue.textProperty().bind(createSliderTextBinding(sliderMax));
		box.add(labelMax, 0, 1);
		box.add(sliderMax, 1, 1);
		box.add(labelMaxValue, 2, 1);
		box.setVgap(5);

		Label labelGamma = new Label("Gamma");
		Label labelGammaValue = new Label(blank);
		Tooltip tooltipGamma = new Tooltip("Set gamma value, for all viewers.\n"
				+ "Double-click the value to edit manually, shift-click to reset to 1.\n"
				+ "It is recommended to leave this value at 1, to avoid unnecessary nonlinear contrast adjustment.");
		labelGammaValue.setTooltip(tooltipGamma);
		labelGammaValue.textProperty().bind(createGammaLabelBinding(sliderGamma.valueProperty()));
		sliderGamma.setTooltip(tooltipGamma);
		labelGamma.setLabelFor(sliderGamma);
		labelGamma.setTooltip(tooltipGamma);
		labelGammaValue.setOnMouseClicked(this::handleGammaLabelClicked);
		labelGammaValue.styleProperty().bind(createGammaLabelStyleBinding(sliderGamma.valueProperty()));

		box.add(labelGamma, 0, 2);
		box.add(sliderGamma, 1, 2);
		box.add(labelGammaValue, 2, 2);

		GridPane.setFillWidth(sliderMin, Boolean.TRUE);
		GridPane.setFillWidth(sliderMax, Boolean.TRUE);
		box.prefWidthProperty().bind(pane.widthProperty());
		box.setPadding(new Insets(5, 0, 5, 0));
		GridPane.setHgrow(sliderMin, Priority.ALWAYS);
		GridPane.setHgrow(sliderMax, Priority.ALWAYS);

		// In the absence of a better way, make it possible to enter display range values
		// manually by double-clicking on the corresponding label
		labelMinValue.setOnMouseClicked(this::handleMinLabelClick);
		labelMaxValue.setOnMouseClicked(this::handleMaxLabelClick);

		Button btnAuto = new Button("Auto");
		btnAuto.setOnAction(this::handleAutoButtonClicked);

		Button btnReset = new Button("Reset");
		btnReset.setOnAction(this::handleResetButtonClicked);

		Stage dialog = new Stage();
		dialog.initOwner(qupath.getStage());
		dialog.setTitle("Brightness & contrast");

		// Create color/channel display table
		table = new TableView<>(imageDisplay == null ? FXCollections.observableArrayList() : imageDisplay.availableChannels());
		var textPlaceholder = new Text("No channels available");
		textPlaceholder.setStyle("-fx-fill: -fx-text-base-color;");
		table.setPlaceholder(textPlaceholder);
		table.addEventHandler(KeyEvent.KEY_PRESSED, new CopyTableListener());

		table.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
		table.getSelectionModel().selectedItemProperty().addListener(this::handleSelectedChannelChanged);

		TableColumn<ChannelDisplayInfo, ChannelDisplayInfo> col1 = new TableColumn<>("Channel");
		col1.setCellValueFactory(this::channelCellValueFactory);
		col1.setCellFactory(column -> new ChannelDisplayTableCell());

		col1.setSortable(false);
		TableColumn<ChannelDisplayInfo, Boolean> col2 = new TableColumn<>("Show");
		col2.setCellValueFactory(this::showChannelCellValueFactory);
		col2.setCellFactory(column -> new ShowChannelDisplayTableCell());
		col2.setSortable(false);
		col2.setEditable(true);
		col2.setResizable(false);


		// Handle color change requests when an appropriate row is double-clicked
		table.setRowFactory(this::createTableRow);

		table.getColumns().add(col1);
		table.getColumns().add(col2);
		table.setEditable(true);
		table.setColumnResizePolicy(TableView.UNCONSTRAINED_RESIZE_POLICY);
		col1.prefWidthProperty().bind(table.widthProperty().subtract(col2.widthProperty()).subtract(25)); // Hack... space for a scrollbar

		BorderPane paneColor = new BorderPane();
		BorderPane paneTableAndFilter = new BorderPane(table);
		Node textFilterNode = createTextFilter();
		paneTableAndFilter.setBottom(textFilterNode);

		paneColor.setCenter(paneTableAndFilter);

		CheckBox cbShowGrayscale = new CheckBox("Show grayscale");
		cbShowGrayscale.selectedProperty().bindBidirectional(showGrayscale);
		cbShowGrayscale.setTooltip(new Tooltip("Show single channel with grayscale lookup table"));
		if (imageDisplay != null)
			cbShowGrayscale.setSelected(!imageDisplay.useColorLUTs());
		showGrayscale.addListener(this::handleDisplaySettingInvalidated);

		CheckBox cbInvertBackground = new CheckBox("Invert background");
		cbInvertBackground.selectedProperty().bindBidirectional(invertBackground);
		cbInvertBackground.setTooltip(new Tooltip("Invert the background for display (i.e. switch between white and black).\n"
				+ "Use cautiously to avoid becoming confused about how the 'original' image looks (e.g. brightfield or fluorescence)."));
		if (imageDisplay != null)
			cbInvertBackground.setSelected(imageDisplay.useInvertedBackground());
		invertBackground.addListener(this::handleDisplaySettingInvalidated);

		CheckBox cbKeepDisplaySettings = new CheckBox("Keep settings");
		cbKeepDisplaySettings.selectedProperty().bindBidirectional(PathPrefs.keepDisplaySettingsProperty());
		cbKeepDisplaySettings.setTooltip(new Tooltip("Retain same display settings where possible when opening similar images"));

		FlowPane paneCheck = new FlowPane();
		paneCheck.setVgap(5);
		paneCheck.getChildren().add(cbShowGrayscale);
		paneCheck.getChildren().add(cbInvertBackground);
		paneCheck.getChildren().add(cbKeepDisplaySettings);
		paneCheck.setHgap(10);
		paneCheck.setPadding(new Insets(5, 0, 0, 0));
		paneColor.setBottom(paneCheck);
		pane.setCenter(paneColor);

		// Create brightness/contrast panel
		BorderPane panelSliders = new BorderPane();
		panelSliders.setTop(box);
		GridPane panelButtons = GridPaneUtils.createColumnGridControls(
				btnAuto,
				btnReset
		);
		panelSliders.setBottom(panelButtons);
		panelSliders.setPadding(new Insets(5, 0, 5, 0));

		BorderPane paneMinMax = new BorderPane();
		paneMinMax.setPrefHeight(280);
		paneMinMax.setTop(panelSliders);

		histogramPanel.setShowTickLabels(false);
		histogramPanel.getChart().setAnimated(false);
		var chartPane = chartWrapper.getPane();
		paneMinMax.setCenter(chartPane);
		chartPane.setPrefWidth(200);

		var labelWarning = new Label("Inverted background - interpret colors cautiously!");
		labelWarning.setTooltip(new Tooltip("Inverting the background uses processing trickery that reduces the visual information in the image.\n"
				+ "Be careful about interpreting colors, especially for images with multiple channels"));
		labelWarning.setStyle(WARNING_STYLE);
		labelWarning.setAlignment(Pos.CENTER);
		labelWarning.setTextAlignment(TextAlignment.CENTER);
		labelWarning.visibleProperty().bind(cbInvertBackground.selectedProperty().and(cbShowGrayscale.selectedProperty().not()));
		labelWarning.setMaxWidth(Double.MAX_VALUE);
		labelWarning.managedProperty().bind(labelWarning.visibleProperty()); // Remove if not visible

		var labelWarningGamma = new Label("Gamma is not equal to 1.0 - shift+click to reset");
		labelWarningGamma.setOnMouseClicked(this::handleGammaWarningClicked);
		labelWarningGamma.setTooltip(new Tooltip("Adjusting the gamma results in a nonlinear contrast adjustment -\n"
				+ "in science, such changes should usually be disclosed in any figure legends"));
		labelWarningGamma.setStyle(WARNING_STYLE);
		labelWarningGamma.setAlignment(Pos.CENTER);
		labelWarningGamma.setTextAlignment(TextAlignment.CENTER);
		labelWarningGamma.visibleProperty().bind(sliderGamma.valueProperty().isNotEqualTo(1.0, 0.0));
		labelWarningGamma.setMaxWidth(Double.MAX_VALUE);
		labelWarningGamma.managedProperty().bind(labelWarningGamma.visibleProperty()); // Remove if not visible

		var vboxWarnings = new VBox();
		vboxWarnings.getChildren().setAll(labelWarning, labelWarningGamma);

		paneMinMax.setBottom(vboxWarnings);

		pane.setBottom(paneMinMax);
		pane.setPadding(new Insets(10, 10, 10, 10));

		Scene scene = new Scene(pane, 350, 580);
		scene.addEventHandler(KeyEvent.KEY_TYPED, keyListener);
		dialog.setScene(scene);
		dialog.setMinWidth(300);
		dialog.setMinHeight(400);
		dialog.setMaxWidth(600);

		updateTable();

		if (!table.getItems().isEmpty())
			table.getSelectionModel().select(0);
		updateDisplay(getCurrentInfo(), true);
		updateHistogram();
		updateSliders();

		// Update sliders when receiving focus - in case the display has been updated elsewhere
		dialog.focusedProperty().addListener(this::handleDialogFocusChanged);

		return dialog;
	}


	private ObservableValue<ChannelDisplayInfo> channelCellValueFactory(
			TableColumn.CellDataFeatures<ChannelDisplayInfo, ChannelDisplayInfo> features) {
		return new SimpleObjectProperty<>(features.getValue());
	}

	private ObservableValue<Boolean> showChannelCellValueFactory(
			TableColumn.CellDataFeatures<ChannelDisplayInfo, Boolean> features) {
		SimpleBooleanProperty property = new SimpleBooleanProperty(
				imageDisplay.selectedChannels().contains(features.getValue()));
		// TODO: Check if repaint code can be removed here
		property.addListener((v, o, n) -> {
			imageDisplay.setChannelSelected(features.getValue(), n);
			table.refresh();
			Platform.runLater(() -> viewer.repaintEntireImage());
		});
		return property;
	}

	private static ObservableValue<String> createGammaLabelBinding(ObservableValue<? extends Number> gammaValue) {
		return Bindings.createStringBinding(() ->
						GeneralTools.formatNumber(gammaValue.getValue().doubleValue(), 2),
				gammaValue);
	}

	private static ObservableValue<String> createGammaLabelStyleBinding(ObservableValue<? extends Number> gammaValue) {
		return Bindings.createStringBinding(() -> {
			if (gammaValue.getValue().doubleValue() == 1.0)
				return null;
			return WARNING_STYLE;
		}, gammaValue);
	}

	private void handleGammaLabelClicked(MouseEvent event) {
		if (event.getClickCount() >= 3 || event.isShiftDown()) {
			// Reset gamma to 1.0
			sliderGamma.setValue(1.0);
		} else {
			var newGamma = Dialogs.showInputDialog("Gamma", "Set gamma value", sliderGamma.getValue());
			if (newGamma != null)
				sliderGamma.setValue(newGamma);
		}
	}

	private void handleMaxLabelClick(MouseEvent event) {
		if (event.getClickCount() != 2)
			return;
		ChannelDisplayInfo infoVisible = getCurrentInfo();
		if (infoVisible == null)
			return;

		Double value = Dialogs.showInputDialog("Display range", "Set display range maximum", (double)infoVisible.getMaxDisplay());
		if (value != null && !Double.isNaN(value)) {
			sliderMax.setValue(value);
			// Update display directly if out of slider range
			if (value < sliderMax.getMin() || value > sliderMax.getMax()) {
				imageDisplay.setMinMaxDisplay(infoVisible, (float)infoVisible.getMinDisplay(), (float)value.floatValue());
				updateSliders();
				viewer.repaintEntireImage();
			}
		}
	}


	private void handleMinLabelClick(MouseEvent event) {
		if (event.getClickCount() != 2)
			return;
		ChannelDisplayInfo infoVisible = getCurrentInfo();
		if (infoVisible == null)
			return;

		Double value = Dialogs.showInputDialog("Display range", "Set display range minimum", (double)infoVisible.getMinDisplay());
		if (value != null && !Double.isNaN(value)) {
			sliderMin.setValue(value);
			// Update display directly if out of slider range
			if (value < sliderMin.getMin() || value > sliderMin.getMax()) {
				imageDisplay.setMinMaxDisplay(infoVisible, value.floatValue(), infoVisible.getMaxDisplay());
				updateSliders();
				viewer.repaintEntireImage();
			}
		}
	}


	/**
	 * Simple invalidation listener to request an image repaint when a display setting changes.
	 */
	private void handleDisplaySettingInvalidated(Observable observable) {
		if (imageDisplay == null)
			return;
		Platform.runLater(() -> viewer.repaintEntireImage());
		table.refresh();
	}


	private void handleGammaWarningClicked(MouseEvent e) {
		if (e.isShiftDown())
			sliderGamma.setValue(1.0);
	}


	private ObservableValue<String> createSliderTextBinding(Slider slider) {
		return Bindings.createStringBinding(() -> {
			double value = slider.getValue();
			if (value == (int)value)
				return String.format("%d", (int) value);
			else if (value < 1)
				return String.format("%.3f", value);
			else if (value < 10)
				return String.format("%.2f", value);
			else
				return String.format("%.1f", value);
		}, slider.valueProperty());
	}


	private void handleResetButtonClicked(ActionEvent e) {
		for (ChannelDisplayInfo info : table.getSelectionModel().getSelectedItems()) {
			imageDisplay.setMinMaxDisplay(info, info.getMinAllowed(), info.getMaxAllowed());
		}
		sliderMin.setValue(sliderMin.getMin());
		sliderMax.setValue(sliderMax.getMax());
		sliderGamma.setValue(1.0);
	}

	private void handleAutoButtonClicked(ActionEvent e) {
		if (imageDisplay == null)
			return;
		ChannelDisplayInfo info = getCurrentInfo();
		double saturation = PathPrefs.autoBrightnessContrastSaturationPercentProperty().get()/100.0;
		imageDisplay.autoSetDisplayRange(info, saturation);
		for (ChannelDisplayInfo info2 : table.getSelectionModel().getSelectedItems()) {
			imageDisplay.autoSetDisplayRange(info2, saturation);
		}
		updateSliders();
		applyMinMaxSliderChanges();
	}
	
	private boolean isInitialized() {
		return sliderMin != null && sliderMax != null;
	}
	
	private void initializeSliders() {
		sliderMin = new Slider(0, 255, 0);
		sliderMax = new Slider(0, 255, 255);
		sliderMin.valueProperty().addListener(this::handleMinMaxSliderValueChange);
		sliderMax.valueProperty().addListener(this::handleMinMaxSliderValueChange);
		sliderGamma = new Slider(0.01, 5, 0.01);
		sliderGamma.valueProperty().bindBidirectional(PathPrefs.viewerGammaProperty());
	}
	
	private Node createTextFilter() {
		TextField tfFilter = new TextField("");
		tfFilter.textProperty().bindBidirectional(filterText);
		tfFilter.setTooltip(new Tooltip("Enter text to find specific channels by name"));
		tfFilter.setPromptText("Filter channels by name");
		predicate.addListener((v, o, n) -> updatePredicate());
		BorderPane pane = new BorderPane(tfFilter);
		ToggleButton btnRegex = new ToggleButton(".*");
		btnRegex.setTooltip(new Tooltip("Use regular expressions for channel filter"));
		btnRegex.selectedProperty().bindBidirectional(useRegex);
		pane.setRight(btnRegex);
		return pane;
	}


	private TableRow<ChannelDisplayInfo> createTableRow(TableView<ChannelDisplayInfo> table) {
		TableRow<ChannelDisplayInfo> row = new TableRow<>();
		row.setOnMouseClicked(e -> handleTableRowMouseClick(row, e));
		return row;
	}

	private void handleTableRowMouseClick(TableRow<ChannelDisplayInfo> row, MouseEvent event) {
		if (event.getClickCount() != 2)
			return;

		ChannelDisplayInfo info = row.getItem();
		var imageData = viewer.getImageData();
		if (info instanceof DirectServerChannelInfo && imageData != null) {
			DirectServerChannelInfo multiInfo = (DirectServerChannelInfo)info;
			int c = multiInfo.getChannel();
			var channel = imageData.getServer().getMetadata().getChannel(c);

			Color color = ColorToolsFX.getCachedColor(multiInfo.getColor());
			picker.setValue(color);


			Dialog<ButtonType> colorDialog = new Dialog<>();
			colorDialog.setTitle("Channel properties");

			colorDialog.getDialogPane().getButtonTypes().setAll(ButtonType.APPLY, ButtonType.CANCEL);

			var paneColor = new GridPane();
			int r = 0;
			var labelName = new Label("Channel name");
			var tfName = new TextField(channel.getName());
			labelName.setLabelFor(tfName);
			GridPaneUtils.addGridRow(paneColor, r++, 0, "Enter a name for the current channel", labelName, tfName);
			var labelColor = new Label("Channel color");
			labelColor.setLabelFor(picker);
			GridPaneUtils.addGridRow(paneColor, r++, 0, "Choose the color for the current channel", labelColor, picker);
			paneColor.setVgap(5.0);
			paneColor.setHgap(5.0);

			colorDialog.getDialogPane().setContent(paneColor);
			Optional<ButtonType> result = colorDialog.showAndWait();
			if (result.orElse(ButtonType.CANCEL) == ButtonType.APPLY) {
				String name = tfName.getText().trim();
				if (name.isEmpty()) {
					Dialogs.showErrorMessage("Set channel name", "The channel name must not be empty!");
					return;
				}
				Color color2 = picker.getValue();
				if (color == color2 && name.equals(channel.getName()))
					return;

				// Update the server metadata
				int colorUpdated = ColorToolsFX.getRGB(color2);
				if (imageData != null) {
					var server = imageData.getServer();
					var metadata = server.getMetadata();
					var channels = new ArrayList<>(metadata.getChannels());
					channels.set(c, ImageChannel.getInstance(name, colorUpdated));
					var metadata2 = new ImageServerMetadata.Builder(metadata)
							.channels(channels).build();
					imageData.updateServerMetadata(metadata2);
				}

				// Update the display
				multiInfo.setLUTColor(
						(int)(color2.getRed() * 255),
						(int)(color2.getGreen() * 255),
						(int)(color2.getBlue() * 255)
				);

				// Add color property
				imageDisplay.saveChannelColorProperties();
				viewer.repaintEntireImage();
				updateHistogram();
				table.refresh();
			}
		}
	}


	/**
	 * Respond to changes in the main selected channel in the table
	 */
	private void handleSelectedChannelChanged(ObservableValue<? extends ChannelDisplayInfo> observableValue,
											  ChannelDisplayInfo oldValue, ChannelDisplayInfo newValue) {
		updateHistogram();
		updateSliders();
	}


	/**
	 * Update sliders when receiving focus - in case the display has been updated elsewhere
	 */
	private void handleDialogFocusChanged(ObservableValue<? extends Boolean> observable, Boolean oldValue,
										  Boolean newValue) {
		if (newValue)
			updateSliders();
	}

	
	private void updateHistogram() {
		if (table == null || !isInitialized())
			return;
		ChannelDisplayInfo infoSelected = getCurrentInfo();
		Histogram histogram = (imageDisplay == null || infoSelected == null) ? null : imageDisplay.getHistogram(infoSelected);
//		histogram = histogramMap.get(infoSelected);
		if (histogram == null) {
//			histogramPanel.getHistogramData().clear();
			
			// Try to show RGB channels together
			if (infoSelected != null && imageDisplay != null && 
					imageDisplay.getImageData() != null && 
					imageDisplay.getImageData().getServer().isRGB() &&
					"original".equalsIgnoreCase(infoSelected.getName())) {
				List<HistogramData> data = new ArrayList<>();
				for (var c : imageDisplay.availableChannels()) {
					var method = c.getMethod();
					if (method == null)
						continue;
					switch (method) {
					case Red:
					case Green:
					case Blue:
						var hist = imageDisplay.getHistogram(c);
						if (hist != null) {
							var histogramData = HistogramPanelFX.createHistogramData(hist, true, c.getColor());
							histogramData.setNormalizeCounts(true);
							data.add(histogramData);
							if (histogram == null || hist.getMaxCount() > histogram.getMaxCount())
								histogram = hist;
						}
						break;
					default:
						break;
					}
				}
				histogramPanel.getHistogramData().setAll(data);
			} else
				histogramPanel.getHistogramData().clear();
		}
		else {
			// Any animation is slightly nicer if we can modify the current data, rather than creating a new one
			if (histogramPanel.getHistogramData().size() == 1) {
				Color color = infoSelected.getColor() == null ? ColorToolsFX.TRANSLUCENT_BLACK_FX : ColorToolsFX.getCachedColor(infoSelected.getColor());
				histogramPanel.getHistogramData().get(0).setHistogram(histogram, color);
			} else {
				HistogramData histogramData = HistogramPanelFX.createHistogramData(histogram, true, infoSelected.getColor());
				histogramData.setNormalizeCounts(true);
				histogramPanel.getHistogramData().setAll(histogramData);
			}
		}
		
		NumberAxis xAxis = (NumberAxis)histogramPanel.getChart().getXAxis();
		if (infoSelected != null && infoSelected.getMaxAllowed() == 255 && infoSelected.getMinAllowed() == 0) {
			xAxis.setAutoRanging(false);
			xAxis.setLowerBound(0);
			xAxis.setUpperBound(255);
		} else if (infoSelected != null) {
			xAxis.setAutoRanging(false);
			xAxis.setLowerBound(infoSelected.getMinAllowed());
			xAxis.setUpperBound(infoSelected.getMaxAllowed());
//			xAxis.setAutoRanging(true);
		}
		if (infoSelected != null)
			xAxis.setTickUnit(infoSelected.getMaxAllowed() - infoSelected.getMinAllowed());
		
		// Don't use the first or last count if it's an outlier
		NumberAxis yAxis = (NumberAxis)histogramPanel.getChart().getYAxis();
		if (infoSelected != null && histogram != null) {
			long maxCount = 0L;
			for (int i = 1; i < histogram.nBins()-1; i++)
				maxCount = Math.max(maxCount, histogram.getCountsForBin(i));
			if (maxCount == 0)
				maxCount = histogram.getMaxCount();
			yAxis.setAutoRanging(false);
			yAxis.setLowerBound(0);
			yAxis.setUpperBound((double)maxCount / histogram.getCountSum());
		}
		
		
		histogramPanel.getChart().getXAxis().setTickLabelsVisible(true);
		histogramPanel.getChart().getXAxis().setLabel("Pixel value");
		histogramPanel.getChart().getYAxis().setTickLabelsVisible(true);
//		histogramPanel.getChart().getYAxis().setLabel("Frequency");
		
		GridPane pane = new GridPane();
		pane.setHgap(4);
		pane.setVgap(2);
		int row = 0;
		if (histogram != null) {
			pane.add(new Label("Min"), 0, row);
			pane.add(new Label(df.format(histogram.getMinValue())), 1, row);
			row++;
			pane.add(new Label("Max"), 0, row);
			pane.add(new Label(df.format(histogram.getMaxValue())), 1, row);
			row++;
			pane.add(new Label("Mean"), 0, row);
			pane.add(new Label(df.format(histogram.getMeanValue())), 1, row);
			row++;
			pane.add(new Label("Std.dev"), 0, row);
			pane.add(new Label(df.format(histogram.getStdDev())), 1, row);
			row++;
		}
		chartTooltip.setGraphic(pane);
		
		if (row == 0)
			Tooltip.uninstall(histogramPanel.getChart(), chartTooltip);
		else
			Tooltip.install(histogramPanel.getChart(), chartTooltip);
		
	}
	
	
	private void updateDisplay(ChannelDisplayInfo channel, boolean selected) {
		if (imageDisplay == null)
			return;
		if (channel != null)
			imageDisplay.setChannelSelected(channel, selected);
		
		// If the table isn't null, we are displaying something
		if (table != null) {
			updateHistogram();
			table.refresh();
		}
		viewer.repaintEntireImage();
	}
	
	
	private void toggleDisplay(ChannelDisplayInfo channel) {
		if (channel == null)
			updateDisplay(null, true);
		else
			updateDisplay(channel, !imageDisplay.selectedChannels().contains(channel));
	}


	private ChannelDisplayInfo getCurrentInfo() {
		ChannelDisplayInfo info = table.getSelectionModel().getSelectedItem();
		// Default to first, if we don't have a selection
		if (info == null && !table.getItems().isEmpty())
			info = table.getItems().get(0);
		return info;
	}


	private void handleMinMaxSliderValueChange(ObservableValue<? extends Number> observable, Number oldValue, Number newValue) {
		applyMinMaxSliderChanges();
	}

	private void applyMinMaxSliderChanges() {
		if (slidersUpdating)
			return;
		ChannelDisplayInfo infoVisible = getCurrentInfo();
		if (infoVisible == null)
			return;
		double minValue = sliderMin.getValue();
		double maxValue = sliderMax.getValue();
		imageDisplay.setMinMaxDisplay(infoVisible, (float)minValue, (float)maxValue);
		viewer.repaintEntireImage();
	}
	
	
	private void updateSliders() {
		if (!isInitialized())
			return;
		ChannelDisplayInfo infoVisible = getCurrentInfo();
		if (infoVisible == null) {
			sliderMin.setDisable(true);
			sliderMax.setDisable(true);
			return;
		}
		float range = infoVisible.getMaxAllowed() - infoVisible.getMinAllowed();
		int n = (int)range;
		boolean is8Bit = range == 255 && infoVisible.getMinAllowed() == 0 && infoVisible.getMaxAllowed() == 255;
		if (is8Bit)
			n = 256;
		else if (n <= 20)
			n = (int)(range / .001);
		else if (n <= 200)
			n = (int)(range / .01);
		slidersUpdating = true;
		
		double maxDisplay = Math.max(infoVisible.getMaxDisplay(), infoVisible.getMinDisplay());
		double minDisplay = Math.min(infoVisible.getMaxDisplay(), infoVisible.getMinDisplay());
		double minSlider = Math.min(infoVisible.getMinAllowed(), minDisplay);
		double maxSlider = Math.max(infoVisible.getMaxAllowed(), maxDisplay);
		
		sliderMin.setMin(minSlider);
		sliderMin.setMax(maxSlider);
		sliderMin.setValue(infoVisible.getMinDisplay());
		sliderMax.setMin(minSlider);
		sliderMax.setMax(maxSlider);
		sliderMax.setValue(infoVisible.getMaxDisplay());
		
		if (is8Bit) {
			sliderMin.setMajorTickUnit(1);
			sliderMax.setMajorTickUnit(1);
			sliderMin.setMinorTickCount(n);
			sliderMax.setMinorTickCount(n);
		} else {
			sliderMin.setMajorTickUnit(1);
			sliderMax.setMajorTickUnit(1);
			sliderMin.setMinorTickCount(n);
			sliderMax.setMinorTickCount(n);
		}
		slidersUpdating = false;
		sliderMin.setDisable(false);
		sliderMax.setDisable(false);
		
		chartWrapper.getThresholds().clear();
		chartWrapper.addThreshold(sliderMin.valueProperty());
		chartWrapper.addThreshold(sliderMax.valueProperty());
		chartWrapper.setIsInteractive(true);
//		chartWrapper.getThresholds().setAll(sliderMin.valueProperty(), sliderMax.valueProperty());
		
//		histogramPanel.setVerticalLines(new double[]{infoVisible.getMinDisplay(), infoVisible.getMaxDisplay()}, ColorToolsFX.TRANSLUCENT_BLACK_FX);
	}
	

	/**
	 * Popup menu to toggle additive channels on/off.
	 */
	private void initializePopup() {
		popup = new ContextMenu();
		
		MenuItem miTurnOn = new MenuItem("Show channels");
		miTurnOn.setOnAction(e -> setTableSelectedChannels(true));
		miTurnOn.disableProperty().bind(showGrayscale);
		MenuItem miTurnOff = new MenuItem("Hide channels");
		miTurnOff.setOnAction(e -> setTableSelectedChannels(false));
		miTurnOff.disableProperty().bind(showGrayscale);
		MenuItem miToggle = new MenuItem("Toggle channels");
		miToggle.setOnAction(e -> toggleTableSelectedChannels());
		miToggle.disableProperty().bind(showGrayscale);
		
		popup.getItems().addAll(
				miTurnOn,
				miTurnOff,
				miToggle
				);
	}
	
	/**
	 * Request that channels currently selected (highlighted) in the table have their 
	 * selected status changed accordingly.  This allows multiple channels to be turned on/off 
	 * in one step.
	 * @param showChannels
	 * 
	 * @see #toggleTableSelectedChannels()
	 */
	private void setTableSelectedChannels(boolean showChannels) {
		if (!isInitialized())
			return;
		for (ChannelDisplayInfo info : table.getSelectionModel().getSelectedItems()) {
			imageDisplay.setChannelSelected(info, showChannels);
		}
		table.refresh();
		if (viewer != null) {
//			viewer.updateThumbnail();
			viewer.repaintEntireImage();
		}
	}

	/**
	 * Request that channels currently selected (highlighted) in the table have their 
	 * selected status inverted.  This allows multiple channels to be turned on/off 
	 * in one step.
	 * 
	 * @see #setTableSelectedChannels(boolean)
	 */
	private void toggleTableSelectedChannels() {
		if (!isInitialized())
			return;
		Set<ChannelDisplayInfo> selected = new HashSet<>(imageDisplay.selectedChannels());
		for (ChannelDisplayInfo info : table.getSelectionModel().getSelectedItems()) {
			imageDisplay.setChannelSelected(info, !selected.contains(info));
		}
		table.refresh();
		if (viewer != null) {
			viewer.repaintEntireImage();
		}
	}
	
	private void updatePredicate() {
		var items = table.getItems();
		if (items instanceof FilteredList) {
			((FilteredList<ChannelDisplayInfo>)items).setPredicate(predicate.get());
		}
	}
	
	void updateTable() {
		if (!isInitialized())
			return;

		// Update table appearance (maybe colors changed etc.)
		if (imageDisplay == null) {
			table.setItems(FXCollections.emptyObservableList());
		} else {
			table.setItems(imageDisplay.availableChannels().filtered(predicate.get()));
			showGrayscale.bindBidirectional(imageDisplay.useGrayscaleLutProperty());
			invertBackground.bindBidirectional(imageDisplay.useInvertedBackgroundProperty());
		}
		table.refresh();
		
		// If all entries are additive, allow bulk toggling by right-click
		int n = table.getItems().size();
		if (n > 0 || n == table.getItems().stream().filter(c -> c.isAdditive()).count()) {
			table.setContextMenu(popup);
		} else {
			table.setContextMenu(null);
		}
	}


	private void handleImageDataChange(ObservableValue<? extends ImageData<BufferedImage>> source,
			ImageData<BufferedImage> imageDataOld, ImageData<BufferedImage> imageDataNew) {
		// TODO: Consider different viewers but same ImageData
		if (imageDataOld == imageDataNew)
			return;
				
		QuPathViewer viewerNew = qupath.getViewer();
		if (viewer != viewerNew) {
			if (viewer != null)
				viewer.getView().removeEventHandler(KeyEvent.KEY_TYPED, keyListener);
			if (viewerNew != null)
				viewerNew.getView().addEventHandler(KeyEvent.KEY_TYPED, keyListener);
			viewer = viewerNew;
		}
		
		if (imageDisplay != null) {
			showGrayscale.unbindBidirectional(imageDisplay.useGrayscaleLutProperty());
			imageDisplay.useGrayscaleLutProperty().unbindBidirectional(showGrayscale);
			
			invertBackground.unbindBidirectional(imageDisplay.useInvertedBackgroundProperty());
			imageDisplay.useInvertedBackgroundProperty().unbindBidirectional(invertBackground);
		}
		
		imageDisplay = viewer == null ? null : viewer.getImageDisplay();
		
		if (imageDataOld != null)
			imageDataOld.removePropertyChangeListener(imageDataPropertyChangeListener);
		if (imageDataNew != null)
			imageDataNew.addPropertyChangeListener(imageDataPropertyChangeListener);
		
		updateTable();
		
//		updateHistogramMap();
		// Update if we aren't currently initializing
		updateHistogram();
		updateSliders();
	}


	private ObjectBinding<Predicate<ChannelDisplayInfo>> createChannelDisplayPredicateBinding(StringProperty filterText) {
		return Bindings.createObjectBinding(() -> {
			if (useRegex.get())
				return createChannelDisplayPredicateFromRegex(filterText.get());
			else
				return createChannelDisplayPredicateFromText(filterText.get());
		}, filterText, useRegex);
	}

	private static Predicate<ChannelDisplayInfo> createChannelDisplayPredicateFromRegex(String regex) {
		if (regex == null || regex.isBlank())
			return info -> true;
		try {
			Pattern pattern = Pattern.compile(regex);
			return info -> channelDisplayFromRegex(info, pattern);
		} catch (PatternSyntaxException e) {
			logger.warn("Invalid channel display: {} ({})", regex, e.getMessage());
			return info -> false;
		}
	}

	private static boolean channelDisplayFromRegex(ChannelDisplayInfo info, Pattern pattern) {
		return pattern.matcher(info.getName()).find();
	}

	private static Predicate<ChannelDisplayInfo> createChannelDisplayPredicateFromText(String filterText) {
		if (filterText == null || filterText.isBlank())
			return info -> true;
		String text = filterText.toLowerCase();
		return info -> channelDisplayContainsText(info, text);
	}

	private static boolean channelDisplayContainsText(ChannelDisplayInfo info, String text) {
		return info.getName().toLowerCase().contains(text);
	}


	/**
	 * Listener to respond to changes in the ImageData properties (which includes the image type)
	 */
	private class ImageDataPropertyChangeListener implements PropertyChangeListener {

		@Override
		public void propertyChange(PropertyChangeEvent evt) {
			// Don't respond to changes in the ImageDisplay (which we may have triggered...)
			if (evt.getPropertyName().equals("qupath.lib.display.ImageDisplay"))
				return;

			if (!Platform.isFxApplicationThread()) {
				Platform.runLater(() -> propertyChange(evt));
				return;
			}

			logger.trace("Property change: {}", evt);

			// Update display if we changed something relevant, including
			// - server metadata (including channel names/LUTs)
			// - image type
			// Note that we don't need to respond to all changes
			if (evt.getPropertyName().equals("serverMetadata") ||
					((evt.getSource() instanceof ImageData<?>) && evt.getPropertyName().equals("imageType")))
				imageDisplay.refreshChannelOptions();

			updateTable();
			updateSliders();

			if (viewer != null) {
				viewer.repaintEntireImage();
			}
		}

	}


	/**
	 * Listener to update the display when the user presses a key.
	 */
	private class BrightnessContrastKeyListener implements EventHandler<KeyEvent> {

		@Override
		public void handle(KeyEvent event) {
			if (imageDisplay == null)
				return;
			String character = event.getCharacter();
			if (character != null && character.length() > 0) {
				int c = (int)event.getCharacter().charAt(0) - '0';
				if (c >= 1 && c <= Math.min(9, imageDisplay.availableChannels().size())) {
					if (table != null) {
						table.getSelectionModel().clearAndSelect(c-1);
					}
					toggleDisplay(imageDisplay.availableChannels().get(c-1));
					event.consume();
				}
			}
		}
		
	}

	/**
	 * Listener to support copy & based directly on the channel table.
	 * This provides a way to quickly extract channel names, or update channel names from the clipboard.
	 */
	private class CopyTableListener implements EventHandler<KeyEvent> {
		
		private KeyCombination copyCombo = new KeyCodeCombination(KeyCode.C, KeyCombination.SHORTCUT_DOWN);
		private KeyCombination pasteCombo = new KeyCodeCombination(KeyCode.V, KeyCombination.SHORTCUT_DOWN);

		private KeyCombination spaceCombo = new KeyCodeCombination(KeyCode.SPACE, KeyCombination.SHORTCUT_ANY);
		private KeyCombination enterCombo = new KeyCodeCombination(KeyCode.ENTER, KeyCombination.SHORTCUT_ANY);

		@Override
		public void handle(KeyEvent event) {
			if (copyCombo.match(event)) {
				doCopy(event);
				event.consume();
			} else if (pasteCombo.match(event)) {
				doPaste(event);
				event.consume();
			} else if (spaceCombo.match(event) || enterCombo.match(event)) {
				var channel = table.getSelectionModel().getSelectedItem();
				if (imageDisplay != null && channel != null) {
					updateDisplay(channel, !imageDisplay.selectedChannels().contains(channel));
				}
				event.consume();
			}
		}
		
		/**
		 * Copy the channel names to the clipboard
		 * @param event
		 */
		void doCopy(KeyEvent event) {
			var names = table.getSelectionModel().getSelectedItems().stream().map(c -> c.getName()).toList();
			var clipboard = Clipboard.getSystemClipboard();
			var content = new ClipboardContent();
			content.putString(String.join(System.lineSeparator(), names));
			clipboard.setContent(content);
		}

		/**
		 * Paste channel names from the clipboard, if possible
		 * @param event
		 */
		void doPaste(KeyEvent event) {
			ImageData<BufferedImage> imageData = viewer.getImageData();
			if (imageData == null)
				return;
			ImageServer<BufferedImage> server = imageData.getServer();
			
			var clipboard = Clipboard.getSystemClipboard();
			var string = clipboard.getString();
			if (string == null)
				return;
			var selected = new ArrayList<>(table.getSelectionModel().getSelectedItems());
			if (selected.isEmpty())
				return;
			
			if (server.isRGB()) {
				logger.warn("Cannot set channel names for RGB images");
			}
			var names = string.lines().toList();
			if (selected.size() != names.size()) {
				Dialogs.showErrorNotification("Paste channel names", "The number of lines on the clipboard doesn't match the number of channel names to replace!");
				return;
			}
			if (names.size() != new HashSet<>(names).size()) {
				Dialogs.showErrorNotification("Paste channel names", "Channel names should be unique!");
				return;
			}
			var metadata = server.getMetadata();
			var channels = new ArrayList<>(metadata.getChannels());
			List<String> changes = new ArrayList<>();
			for (int i = 0; i < selected.size(); i++) {
				if (!(selected.get(i) instanceof DirectServerChannelInfo))
					continue;
				var info = (DirectServerChannelInfo)selected.get(i);
				if (info.getName().equals(names.get(i)))
					continue;
				int c = info.getChannel();
				var oldChannel = channels.get(c);
				var newChannel = ImageChannel.getInstance(names.get(i), channels.get(c).getColor());
				changes.add(oldChannel.getName() + " -> " + newChannel.getName());
				channels.set(c, newChannel);
			}
			List<String> allNewNames = channels.stream().map(c -> c.getName()).toList();
			Set<String> allNewNamesSet = new LinkedHashSet<>(allNewNames);
			if (allNewNames.size() != allNewNamesSet.size()) {
				Dialogs.showErrorMessage("Channel", "Cannot paste channels - names would not be unique \n(check log for details)");
				for (String n : allNewNamesSet)
					allNewNames.remove(n);
				logger.warn("Requested channel names would result in duplicates: " + String.join(", ", allNewNames));
				return;
			}
			if (changes.isEmpty()) {
				logger.debug("Channel names pasted, but no changes to make");
			}
			else {
				var dialog = new Dialog<ButtonType>();
				dialog.getDialogPane().getButtonTypes().addAll(ButtonType.APPLY, ButtonType.CANCEL);
				dialog.setTitle("Channels");
				dialog.setHeaderText("Confirm new channel names?");
				dialog.getDialogPane().setContent(new TextArea(String.join("\n", changes)));
				if (dialog.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.APPLY) {
					var newMetadata = new ImageServerMetadata.Builder(metadata)
							.channels(channels).build();
					imageData.updateServerMetadata(newMetadata);
				}
			}
		}
		
	}


	/**
	 * Table cell to display the main information about a channel (name, color).
	 */
	private static class ChannelDisplayTableCell extends TableCell<ChannelDisplayInfo, ChannelDisplayInfo> {

		@Override
		protected void updateItem(ChannelDisplayInfo item, boolean empty) {
			super.updateItem(item, empty);
			if (item == null || empty) {
				setText(null);
				setGraphic(null);
				return;
			}
			setText(item.getName());
			Rectangle square = new Rectangle(0, 0, 10, 10);
			Integer rgb = item.getColor();
			if (rgb == null)
				square.setFill(Color.TRANSPARENT);
			else
				square.setFill(ColorToolsFX.getCachedColor(rgb));
			setGraphic(square);
		}

	}

	/**
	 * Table cell to handle the "show" status for a channel.
	 */
	private class ShowChannelDisplayTableCell extends CheckBoxTableCell<ChannelDisplayInfo, Boolean> {

		public ShowChannelDisplayTableCell() {
			super();
			addEventFilter(MouseEvent.MOUSE_CLICKED, this::filterMouseClicks);
		}

		private void filterMouseClicks(MouseEvent event) {
			// Select cells when clicked - means a click anywhere within the row forces selection.
			// Previously, clicking within the checkbox didn't select the row.
			if (event.isPopupTrigger())
				return;
			int ind = getIndex();
			var tableView = getTableView();
			if (ind < tableView.getItems().size()) {
				if (event.isShiftDown())
					tableView.getSelectionModel().select(ind);
				else
					tableView.getSelectionModel().clearAndSelect(ind);
				var channel = getTableRow().getItem();
				// Handle clicks within the cell but outside the checkbox
				if (event.getTarget() == this && channel != null && imageDisplay != null) {
					updateDisplay(channel, !imageDisplay.selectedChannels().contains(channel));
				}
				event.consume();
			}
		}

	}

}
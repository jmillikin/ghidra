/* ###
 * IP: GHIDRA
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ghidra.app.plugin.core.debug.gui.watch;

import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Predicate;

import javax.swing.*;
import javax.swing.table.TableColumn;
import javax.swing.table.TableColumnModel;

import org.jdom.Element;

import docking.ActionContext;
import docking.WindowPosition;
import docking.action.DockingAction;
import docking.action.ToggleDockingAction;
import docking.action.builder.ActionBuilder;
import docking.widgets.table.*;
import docking.widgets.table.DefaultEnumeratedColumnTableModel.EnumeratedTableColumn;
import ghidra.app.context.ListingActionContext;
import ghidra.app.context.ProgramLocationActionContext;
import ghidra.app.plugin.core.data.AbstractSettingsDialog;
import ghidra.app.plugin.core.debug.DebuggerCoordinates;
import ghidra.app.plugin.core.debug.DebuggerPluginPackage;
import ghidra.app.plugin.core.debug.gui.DebuggerResources;
import ghidra.app.plugin.core.debug.gui.DebuggerResources.*;
import ghidra.app.plugin.core.debug.gui.register.DebuggerRegisterActionContext;
import ghidra.app.plugin.core.debug.gui.register.RegisterRow;
import ghidra.app.services.*;
import ghidra.async.AsyncDebouncer;
import ghidra.async.AsyncTimer;
import ghidra.base.widgets.table.DataTypeTableCellEditor;
import ghidra.docking.settings.*;
import ghidra.framework.model.DomainObject;
import ghidra.framework.model.DomainObjectChangeRecord;
import ghidra.framework.options.SaveState;
import ghidra.framework.options.annotation.AutoOptionDefined;
import ghidra.framework.options.annotation.HelpInfo;
import ghidra.framework.plugintool.*;
import ghidra.framework.plugintool.annotation.AutoServiceConsumed;
import ghidra.pcode.exec.trace.TraceSleighUtils;
import ghidra.program.model.address.*;
import ghidra.program.model.data.DataType;
import ghidra.program.model.lang.Register;
import ghidra.program.model.listing.*;
import ghidra.program.model.symbol.Symbol;
import ghidra.program.model.util.CodeUnitInsertionException;
import ghidra.program.util.ProgramLocation;
import ghidra.program.util.ProgramSelection;
import ghidra.trace.model.*;
import ghidra.trace.model.Trace.TraceMemoryBytesChangeType;
import ghidra.trace.model.Trace.TraceMemoryStateChangeType;
import ghidra.trace.model.program.TraceProgramView;
import ghidra.trace.model.time.schedule.TraceSchedule;
import ghidra.trace.util.TraceAddressSpace;
import ghidra.util.*;
import ghidra.util.database.UndoableTransaction;
import ghidra.util.exception.CancelledException;
import ghidra.util.table.GhidraTable;
import ghidra.util.table.GhidraTableFilterPanel;
import ghidra.util.table.column.AbstractGColumnRenderer;

public class DebuggerWatchesProvider extends ComponentProviderAdapter {
	private static final String KEY_ROW_COUNT = "rowCount";
	private static final String PREFIX_ROW = "row";

	interface WatchTypeSettings {
		String NAME = DebuggerResources.NAME_WATCH_TYPE_SETTINGS;
		String DESCRIPTION = DebuggerResources.DESCRIPTION_WATCH_TYPE_SETTINGS;
		String HELP_ANCHOR = "type_settings";

		static ActionBuilder builder(Plugin owner) {
			String ownerName = owner.getName();
			return new ActionBuilder(NAME, ownerName)
					.description(DESCRIPTION)
					.popupMenuPath(NAME)
					.helpLocation(new HelpLocation(ownerName, HELP_ANCHOR));
		}
	}

	protected static class WatchDataSettingsDialog extends AbstractSettingsDialog {
		private final WatchRow row;

		public WatchDataSettingsDialog(WatchRow row) {
			super("Data Type Settings", row.getDataType().getSettingsDefinitions(),
				row.getSettings());
			this.row = row;
		}

		@Override
		protected Settings getSettings() {
			return super.getSettings();
		}

		@Override
		protected void okCallback() {
			super.okCallback();
		}

		@Override
		protected String[] getSuggestedValues(StringSettingsDefinition settingsDefinition) {
			if (!settingsDefinition.supportsSuggestedValues()) {
				return null;
			}
			return settingsDefinition.getSuggestedValues(row.getSettings());
		}

		@Override
		protected void applySettings() throws CancelledException {
			copySettings(getSettings(), row.getSettings(), getSettingsDefinitions());
			row.settingsChanged();
		}
	}

	protected enum WatchTableColumns implements EnumeratedTableColumn<WatchTableColumns, WatchRow> {
		EXPRESSION("Expression", String.class, WatchRow::getExpression, WatchRow::setExpression),
		ADDRESS("Address", Address.class, WatchRow::getAddress),
		SYMBOL("Symbol", Symbol.class, WatchRow::getSymbol),
		VALUE("Value", String.class, WatchRow::getRawValueString, WatchRow::setRawValueString, //
				WatchRow::isRawValueEditable),
		TYPE("Type", DataType.class, WatchRow::getDataType, WatchRow::setDataType),
		REPR("Repr", String.class, WatchRow::getValueString, WatchRow::setValueString, //
				WatchRow::isValueEditable),
		ERROR("Error", String.class, WatchRow::getErrorMessage);

		private final String header;
		private final Function<WatchRow, ?> getter;
		private final BiConsumer<WatchRow, Object> setter;
		private final Predicate<WatchRow> editable;
		private final Class<?> cls;

		@SuppressWarnings("unchecked")
		<T> WatchTableColumns(String header, Class<T> cls, Function<WatchRow, T> getter,
				BiConsumer<WatchRow, T> setter, Predicate<WatchRow> editable) {
			this.header = header;
			this.cls = cls;
			this.getter = getter;
			this.setter = (BiConsumer<WatchRow, Object>) setter;
			this.editable = editable;
		}

		<T> WatchTableColumns(String header, Class<T> cls, Function<WatchRow, T> getter,
				BiConsumer<WatchRow, T> setter) {
			this(header, cls, getter, setter, null);
		}

		<T> WatchTableColumns(String header, Class<T> cls, Function<WatchRow, T> getter) {
			this(header, cls, getter, null, null);
		}

		@Override
		public Class<?> getValueClass() {
			return cls;
		}

		@Override
		public Object getValueOf(WatchRow row) {
			return getter.apply(row);
		}

		@Override
		public String getHeader() {
			return header;
		}

		@Override
		public void setValueOf(WatchRow row, Object value) {
			setter.accept(row, value);
		}

		@Override
		public boolean isEditable(WatchRow row) {
			return setter != null && (editable == null || editable.test(row));
		}
	}

	protected static class WatchTableModel
			extends DefaultEnumeratedColumnTableModel<WatchTableColumns, WatchRow> {
		public WatchTableModel() {
			super("Watches", WatchTableColumns.class);
		}
	}

	protected static void copySettings(Settings src, Settings dst, SettingsDefinition[] defs) {
		for (SettingsDefinition sd : defs) {
			sd.copySetting(src, dst);
		}
	}

	protected static boolean sameCoordinates(DebuggerCoordinates a, DebuggerCoordinates b) {
		if (!Objects.equals(a.getTrace(), b.getTrace())) {
			return false;
		}
		if (!Objects.equals(a.getRecorder(), b.getRecorder())) {
			return false; // May need to read target
		}
		if (!Objects.equals(a.getTime(), b.getTime())) {
			return false;
		}
		if (!Objects.equals(a.getThread(), b.getThread())) {
			return false;
		}
		if (!Objects.equals(a.getFrame(), b.getFrame())) {
			return false;
		}
		return true;
	}

	class ForDepsListener extends TraceDomainObjectListener {
		public ForDepsListener() {
			listenForUntyped(DomainObject.DO_OBJECT_RESTORED, this::objectRestored);
			listenFor(TraceMemoryBytesChangeType.CHANGED, this::bytesChanged);
			listenFor(TraceMemoryStateChangeType.CHANGED, this::stateChanged);
		}

		private void objectRestored(DomainObjectChangeRecord rec) {
			changed.add(current.getView().getMemory());
			changeDebouncer.contact(null);
		}

		private void bytesChanged(TraceAddressSpace space, TraceAddressSnapRange range) {
			if (space.getThread() == current.getThread() || space.getThread() == null) {
				changed.add(range.getRange());
				changeDebouncer.contact(null);
			}
		}

		private void stateChanged(TraceAddressSpace space, TraceAddressSnapRange range) {
			if (space.getThread() == current.getThread() || space.getThread() == null) {
				changed.add(range.getRange());
				changeDebouncer.contact(null);
			}
		}
	}

	class WatchDataTypeEditor extends DataTypeTableCellEditor {
		public WatchDataTypeEditor() {
			super(plugin.getTool());
		}

		@Override
		protected DataType resolveSelection(DataType dataType) {
			if (dataType == null) {
				return null;
			}
			try (UndoableTransaction tid =
				UndoableTransaction.start(currentTrace, "Resolve DataType")) {
				return currentTrace.getDataTypeManager().resolve(dataType, null);
			}
		}
	}

	class WatchValueCellRenderer extends AbstractGColumnRenderer<String> {
		@Override
		public Component getTableCellRendererComponent(GTableCellRenderingData data) {
			super.getTableCellRendererComponent(data);
			WatchRow row = (WatchRow) data.getRowObject();
			if (!row.isKnown()) {
				if (data.isSelected()) {
					setForeground(watchStaleSelColor);
				}
				else {
					setForeground(watchStaleColor);
				}
			}
			else if (row.isChanged()) {
				if (data.isSelected()) {
					setForeground(watchChangesSelColor);
				}
				else {
					setForeground(watchChangesColor);
				}
			}
			return this;
		}

		@Override
		public String getFilterString(String t, Settings settings) {
			return t;
		}
	}

	final DebuggerWatchesPlugin plugin;

	DebuggerCoordinates current = DebuggerCoordinates.NOWHERE;
	private Trace currentTrace; // Copy for transition

	@AutoServiceConsumed
	private DebuggerListingService listingService; // For goto and selection
	// TODO: Allow address marking
	@AutoServiceConsumed
	private DebuggerTraceManagerService traceManager; // For goto time (emu mods)
	@AutoServiceConsumed
	protected DebuggerStateEditingService editingService;
	@AutoServiceConsumed
	DebuggerStaticMappingService mappingService;
	@SuppressWarnings("unused")
	private final AutoService.Wiring autoServiceWiring;

	@AutoOptionDefined(
		name = DebuggerResources.OPTION_NAME_COLORS_WATCH_STALE, //
		description = "Text color for watches whose value is not known", //
		help = @HelpInfo(anchor = "colors"))
	protected Color watchStaleColor = DebuggerResources.DEFAULT_COLOR_WATCH_STALE;
	@AutoOptionDefined(
		name = DebuggerResources.OPTION_NAME_COLORS_WATCH_STALE_SEL, //
		description = "Selected text color for watches whose value is not known", //
		help = @HelpInfo(anchor = "colors"))
	protected Color watchStaleSelColor = DebuggerResources.DEFAULT_COLOR_WATCH_STALE_SEL;
	@AutoOptionDefined(
		name = DebuggerResources.OPTION_NAME_COLORS_WATCH_CHANGED, //
		description = "Text color for watches whose value just changed", //
		help = @HelpInfo(anchor = "colors"))
	protected Color watchChangesColor = DebuggerResources.DEFAULT_COLOR_WATCH_CHANGED;
	@AutoOptionDefined(
		name = DebuggerResources.OPTION_NAME_COLORS_WATCH_CHANGED_SEL, //
		description = "Selected text color for watches whose value just changed", //
		help = @HelpInfo(anchor = "colors"))
	protected Color watchChangesSelColor = DebuggerResources.DEFAULT_COLOR_WATCH_CHANGED_SEL;

	private final AddressSet changed = new AddressSet();
	private final AsyncDebouncer<Void> changeDebouncer =
		new AsyncDebouncer<>(AsyncTimer.DEFAULT_TIMER, 100);
	private ForDepsListener forDepsListener = new ForDepsListener();

	private JPanel mainPanel = new JPanel(new BorderLayout());

	protected final WatchTableModel watchTableModel = new WatchTableModel();
	protected GhidraTable watchTable;
	protected GhidraTableFilterPanel<WatchRow> watchFilterPanel;

	ToggleDockingAction actionEnableEdits;
	DockingAction actionApplyDataType;
	DockingAction actionSelectRange;
	DockingAction actionSelectAllReads;
	DockingAction actionAdd;
	DockingAction actionRemove;
	DockingAction actionDataTypeSettings;

	DockingAction actionAddFromLocation;
	DockingAction actionAddFromRegister;

	private DebuggerWatchActionContext myActionContext;

	public DebuggerWatchesProvider(DebuggerWatchesPlugin plugin) {
		super(plugin.getTool(), DebuggerResources.TITLE_PROVIDER_WATCHES, plugin.getName());
		this.plugin = plugin;

		this.autoServiceWiring = AutoService.wireServicesConsumed(plugin, this);

		setIcon(DebuggerResources.ICON_PROVIDER_WATCHES);
		setHelpLocation(DebuggerResources.HELP_PROVIDER_WATCHES);
		setWindowMenuGroup(DebuggerPluginPackage.NAME);

		buildMainPanel();

		setDefaultWindowPosition(WindowPosition.RIGHT);
		createActions();

		setVisible(true);
		contextChanged();

		changeDebouncer.addListener(__ -> doCheckDepsAndReevaluate());
	}

	@Override
	public ActionContext getActionContext(MouseEvent event) {
		if (myActionContext != null) {
			return myActionContext;
		}
		return super.getActionContext(event);
	}

	protected void buildMainPanel() {
		watchTable = new GhidraTable(watchTableModel);
		mainPanel.add(new JScrollPane(watchTable));
		watchFilterPanel = new GhidraTableFilterPanel<>(watchTable, watchTableModel);
		mainPanel.add(watchFilterPanel, BorderLayout.SOUTH);

		watchTable.getSelectionModel().addListSelectionListener(evt -> {
			if (evt.getValueIsAdjusting()) {
				return;
			}
			contextChanged();
		});
		watchTable.addMouseListener(new MouseAdapter() {
			@Override
			public void mouseClicked(MouseEvent e) {
				if (e.getClickCount() != 2 || e.getButton() != MouseEvent.BUTTON1) {
					return;
				}
				navigateToSelectedWatch();
			}
		});

		TableColumnModel columnModel = watchTable.getColumnModel();
		TableColumn addrCol = columnModel.getColumn(WatchTableColumns.ADDRESS.ordinal());
		addrCol.setCellRenderer(CustomToStringCellRenderer.MONO_OBJECT);
		TableColumn valCol = columnModel.getColumn(WatchTableColumns.VALUE.ordinal());
		valCol.setCellRenderer(new WatchValueCellRenderer());
		TableColumn typeCol = columnModel.getColumn(WatchTableColumns.TYPE.ordinal());
		typeCol.setCellEditor(new WatchDataTypeEditor());
	}

	@Override
	public void contextChanged() {
		myActionContext =
			new DebuggerWatchActionContext(this, watchFilterPanel.getSelectedItems(), watchTable);
		super.contextChanged();
	}

	protected void navigateToSelectedWatch() {
		if (myActionContext == null) {
			return;
		}
		WatchRow row = myActionContext.getWatchRow();
		if (row == null) {
			return;
		}
		int modelCol = watchTable.convertColumnIndexToModel(watchTable.getSelectedColumn());
		Throwable error = row.getError(); // I don't care the selected column for errors
		if (error != null) {
			Msg.showError(this, getComponent(), "Evaluation error",
				"Could not evaluate watch", error);
		}
		else if (modelCol == WatchTableColumns.ADDRESS.ordinal()) {
			Address address = row.getAddress();
			if (address != null) {
				navigateToAddress(address);
			}
		}
		else if (modelCol == WatchTableColumns.REPR.ordinal()) {
			Object val = row.getValueObj();
			if (val instanceof Address) {
				navigateToAddress((Address) val);
			}
		}
	}

	protected void navigateToAddress(Address address) {
		if (listingService == null) {
			return;
		}
		if (address.isMemoryAddress()) {
			listingService.goTo(address, true);
			return;
		}
	}

	protected void createActions() {
		actionEnableEdits = DebuggerResources.EnableEditsAction.builder(plugin)
				.enabledWhen(c -> current.getTrace() != null)
				.onAction(c -> {
				})
				.buildAndInstallLocal(this);
		actionApplyDataType = ApplyDataTypeAction.builder(plugin)
				.withContext(DebuggerWatchActionContext.class)
				.enabledWhen(ctx -> current.getTrace() != null && selHasDataType(ctx))
				.onAction(this::activatedApplyDataType)
				.buildAndInstallLocal(this);
		actionSelectRange = SelectWatchRangeAction.builder(plugin)
				.withContext(DebuggerWatchActionContext.class)
				.enabledWhen(ctx -> current.getTrace() != null && listingService != null &&
					selHasMemoryRanges(ctx))
				.onAction(this::activatedSelectRange)
				.buildAndInstallLocal(this);
		actionSelectAllReads = SelectWatchReadsAction.builder(plugin)
				.withContext(DebuggerWatchActionContext.class)
				.enabledWhen(ctx -> current.getTrace() != null && listingService != null &&
					selHasMemoryReads(ctx))
				.onAction(this::activatedSelectReads)
				.buildAndInstallLocal(this);
		actionAdd = AddAction.builder(plugin)
				.onAction(this::activatedAdd)
				.buildAndInstallLocal(this);
		actionRemove = RemoveAction.builder(plugin)
				.withContext(DebuggerWatchActionContext.class)
				.enabledWhen(ctx -> !ctx.getWatchRows().isEmpty())
				.onAction(this::activatedRemove)
				.buildAndInstallLocal(this);

		actionDataTypeSettings = WatchTypeSettings.builder(plugin)
				.withContext(DebuggerWatchActionContext.class)
				.enabledWhen(this::selIsOneWithDataType)
				.onAction(this::activatedDataTypeSettings)
				.buildAndInstallLocal(this);

		// Pop-up context actions
		actionAddFromLocation = WatchAction.builder(plugin)
				.withContext(ProgramLocationActionContext.class)
				.enabledWhen(this::hasDynamicLocation)
				.onAction(this::activatedAddFromLocation)
				.buildAndInstall(tool);
		actionAddFromRegister = WatchAction.builder(plugin)
				.withContext(DebuggerRegisterActionContext.class)
				.enabledWhen(this::hasValidWatchRegister)
				.onAction(this::activatedAddFromRegister)
				.buildAndInstall(tool);
	}

	protected boolean selHasDataType(DebuggerWatchActionContext ctx) {
		for (WatchRow row : ctx.getWatchRows()) {
			Address address = row.getAddress();
			if (row.getDataType() != null && address != null && address.isMemoryAddress() &&
				row.getValueLength() != 0) {
				return true;
			}
		}
		return false;
	}

	protected boolean selHasMemoryRanges(DebuggerWatchActionContext ctx) {
		for (WatchRow row : ctx.getWatchRows()) {
			AddressRange rng = row.getRange();
			if (rng != null && rng.getAddressSpace().isMemorySpace()) {
				return true;
			}
		}
		return false;
	}

	protected boolean selHasMemoryReads(DebuggerWatchActionContext ctx) {
		for (WatchRow row : ctx.getWatchRows()) {
			AddressSet set = row.getReads();
			if (set == null) {
				continue;
			}
			for (AddressRange rng : set) {
				if (rng.getAddressSpace().isMemorySpace()) {
					return true;
				}
			}
		}
		return false;
	}

	protected boolean selIsOneWithDataType(DebuggerWatchActionContext ctx) {
		WatchRow row = ctx.getWatchRow();
		return row != null && row.getDataType() != null;
	}

	private void activatedApplyDataType(DebuggerWatchActionContext context) {
		if (current.getTrace() == null) {
			return;
		}
		List<String> errs = new ArrayList<>();
		for (WatchRow row : context.getWatchRows()) {
			DataType dataType = row.getDataType();
			if (dataType == null) {
				continue;
			}
			Address address = row.getAddress();
			if (address == null) {
				continue;
			}
			if (!address.isMemoryAddress()) {
				continue;
			}
			int size = row.getValueLength();
			if (size == 0) {
				continue;
			}

			// Using the view will handle the "from-now-until-whenever" logic.
			Listing listing = current.getView().getListing();
			// Avoid a transaction that just replaces it with an equivalent....
			Data existing = listing.getDefinedDataAt(address);
			if (existing != null) {
				if (existing.getDataType().isEquivalent(dataType)) {
					return;
				}
			}
			try (UndoableTransaction tid =
				UndoableTransaction.start(current.getTrace(), "Apply Watch Data Type")) {
				try {
					listing.clearCodeUnits(row.getAddress(), row.getRange().getMaxAddress(), false);
					Data data = listing.createData(address, dataType, size);
					copySettings(row.getSettings(), data, dataType.getSettingsDefinitions());
				}
				catch (CodeUnitInsertionException e) {
					errs.add(address + " " + dataType + "(" + size + "): " + e.getMessage());
				}
			}
		}
		if (!errs.isEmpty()) {
			StringBuffer msg = new StringBuffer("One or more types could not be applied:");
			for (String line : errs) {
				msg.append("\n    ");
				msg.append(line);
			}
			Msg.showError(this, getComponent(), "Apply Data Type", msg.toString());
		}
	}

	private void activatedSelectRange(DebuggerWatchActionContext context) {
		if (listingService == null) {
			return;
		}
		AddressSet sel = new AddressSet();
		for (WatchRow row : context.getWatchRows()) {
			AddressRange rng = row.getRange();
			if (rng != null) {
				sel.add(rng);
			}
		}
		listingService.setCurrentSelection(new ProgramSelection(sel));
	}

	private void activatedSelectReads(DebuggerWatchActionContext context) {
		if (listingService == null) {
			return;
		}
		AddressSet sel = new AddressSet();
		for (WatchRow row : context.getWatchRows()) {
			AddressSet reads = row.getReads();
			if (reads != null) {
				sel.add(reads);
			}
		}
		listingService.setCurrentSelection(new ProgramSelection(sel));
	}

	private void activatedAdd(ActionContext ignored) {
		addWatch("");
	}

	private void activatedRemove(DebuggerWatchActionContext context) {
		watchTableModel.deleteWith(context.getWatchRows()::contains);
	}

	private void activatedDataTypeSettings(DebuggerWatchActionContext context) {
		WatchRow row = context.getWatchRow();
		if (row == null) {
			return;
		}
		DataType type = row.getDataType();
		if (type == null) {
			return;
		}
		tool.showDialog(new WatchDataSettingsDialog(row));
	}

	private ProgramLocation getDynamicLocation(ProgramLocation someLoc) {
		if (someLoc == null) {
			return null;
		}
		TraceProgramView view = current.getView();
		if (view == null) {
			return null;
		}
		Program program = someLoc.getProgram();
		if (program == null) {
			return null;
		}
		if (program instanceof TraceProgramView) {
			return someLoc;
		}
		return mappingService.getDynamicLocationFromStatic(view, someLoc);
	}

	private AddressSetView getDynamicAddresses(Program program, AddressSetView set) {
		if (program instanceof TraceProgramView) {
			return set;
		}
		if (set == null) {
			return null;
		}
		return mappingService.getOpenMappedViews(program, set)
				.entrySet()
				.stream()
				.filter(e -> e.getKey().getTrace() == current.getTrace())
				.filter(e -> e.getKey().getSpan().contains(current.getSnap()))
				.flatMap(e -> e.getValue().stream())
				.map(r -> r.getDestinationAddressRange())
				.collect(AddressCollectors.toAddressSet());
	}

	private boolean hasDynamicLocation(ProgramLocationActionContext context) {
		ProgramLocation dynLoc = getDynamicLocation(context.getLocation());
		return dynLoc != null;
	}

	private boolean tryForSelection(ProgramLocationActionContext context) {
		AddressSetView dynSel = getDynamicAddresses(context.getProgram(), context.getSelection());
		if (dynSel == null || dynSel.isEmpty()) {
			return false;
		}
		for (AddressRange rng : dynSel) {
			addWatch(TraceSleighUtils
					.generateExpressionForRange(current.getTrace().getBaseLanguage(), rng));
		}
		return true;
	}

	private boolean tryForDataInListing(ProgramLocationActionContext context) {
		if (!(context instanceof ListingActionContext)) {
			return false;
		}
		ListingActionContext lac = (ListingActionContext) context;
		CodeUnit cu = lac.getCodeUnit();
		if (cu == null) {
			return false;
		}
		AddressSet cuAs = new AddressSet();
		cuAs.add(cu.getMinAddress(), cu.getMaxAddress());
		AddressSetView dynCuAs = getDynamicAddresses(context.getProgram(), cuAs);

		// Verify mapping is complete and contiguous
		if (dynCuAs.getNumAddressRanges() != 1) {
			return false;
		}
		AddressRange dynCuRng = dynCuAs.getFirstRange();
		if (dynCuRng.getLength() != cu.getLength()) {
			return false;
		}

		WatchRow row = addWatch(TraceSleighUtils
				.generateExpressionForRange(current.getTrace().getBaseLanguage(), dynCuRng));
		if (cu instanceof Data) {
			Data data = (Data) cu;
			// TODO: Problems may arise if trace and program have different data organizations
			row.setDataType(data.getDataType());
		}
		return true;
	}

	private boolean trySingleAddress(ProgramLocationActionContext context) {
		ProgramLocation dynLoc = getDynamicLocation(context.getLocation());
		if (dynLoc == null) {
			return false;
		}
		addWatch(TraceSleighUtils.generateExpressionForRange(current.getTrace().getBaseLanguage(),
			new AddressRangeImpl(dynLoc.getAddress(), dynLoc.getAddress())));
		return true;
	}

	private void activatedAddFromLocation(ProgramLocationActionContext context) {
		if (tryForSelection(context)) {
			return;
		}
		if (tryForDataInListing(context)) {
			return;
		}
		trySingleAddress(context);
	}

	private boolean hasValidWatchRegister(DebuggerRegisterActionContext context) {
		RegisterRow row = context.getSelected();
		if (row == null) {
			return false;
		}
		if (row.getRegister().isProcessorContext()) {
			return false;
		}
		return true;
	}

	private void activatedAddFromRegister(DebuggerRegisterActionContext context) {
		RegisterRow regRow = context.getSelected();
		if (regRow == null) {
			return;
		}
		Register reg = regRow.getRegister();
		if (reg.isProcessorContext()) {
			return;
		}
		WatchRow watchRow = addWatch(reg.getName());
		watchRow.setDataType(regRow.getDataType());
	}

	public WatchRow addWatch(String expression) {
		WatchRow row = new WatchRow(this, "");
		row.setCoordinates(current);
		watchTableModel.add(row);
		row.setExpression(expression);
		return row;
	}

	@Override
	public JComponent getComponent() {
		return mainPanel;
	}

	private void removeOldListeners() {
		if (currentTrace == null) {
			return;
		}
		currentTrace.removeListener(forDepsListener);
	}

	private void addNewListeners() {
		if (currentTrace == null) {
			return;
		}
		currentTrace.addListener(forDepsListener);
	}

	private void doSetTrace(Trace trace) {
		if (currentTrace == trace) {
			return;
		}
		removeOldListeners();
		this.currentTrace = trace;
		addNewListeners();
	}

	public void coordinatesActivated(DebuggerCoordinates coordinates) {
		if (sameCoordinates(current, coordinates)) {
			current = coordinates;
			return;
		}
		current = coordinates;

		doSetTrace(current.getTrace());

		setRowsContext(coordinates);

		if (current.isAliveAndReadsPresent()) {
			readTarget();
		}
		reevaluate();
		Swing.runIfSwingOrRunLater(() -> watchTableModel.fireTableDataChanged());
	}

	public synchronized void setRowsContext(DebuggerCoordinates coordinates) {
		for (WatchRow row : watchTableModel.getModelData()) {
			row.setCoordinates(coordinates);
		}
	}

	public synchronized void readTarget() {
		for (WatchRow row : watchTableModel.getModelData()) {
			row.doTargetReads();
		}
	}

	public synchronized void doCheckDepsAndReevaluate() {
		for (WatchRow row : watchTableModel.getModelData()) {
			AddressSet reads = row.getReads();
			if (reads == null || reads.intersects(changed)) {
				row.doTargetReads();
				row.reevaluate();
			}
		}
		changed.clear();
		Swing.runIfSwingOrRunLater(() -> {
			watchTableModel.fireTableDataChanged();
			contextChanged();
		});
	}

	public void reevaluate() {
		for (WatchRow row : watchTableModel.getModelData()) {
			row.reevaluate();
		}
		changed.clear();
	}

	public void writeConfigState(SaveState saveState) {
		List<WatchRow> rows = List.copyOf(watchTableModel.getModelData());
		saveState.putInt(KEY_ROW_COUNT, rows.size());
		for (int i = 0; i < rows.size(); i++) {
			WatchRow row = rows.get(i);
			String stateName = PREFIX_ROW + i;
			SaveState rowState = new SaveState();
			row.writeConfigState(rowState);
			saveState.putXmlElement(stateName, rowState.saveToXml());
		}
	}

	public void readConfigState(SaveState saveState) {
		int rowCount = saveState.getInt(KEY_ROW_COUNT, 0);
		List<WatchRow> rows = new ArrayList<>();
		for (int i = 0; i < rowCount; i++) {
			String stateName = PREFIX_ROW + i;
			Element rowElement = saveState.getXmlElement(stateName);
			if (rowElement != null) {
				WatchRow r = new WatchRow(this, "");
				SaveState rowState = new SaveState(rowElement);
				r.readConfigState(rowState);
				rows.add(r);
			}
		}
		watchTableModel.clear();
		watchTableModel.addAll(rows);
	}

	public boolean isEditsEnabled() {
		return actionEnableEdits.isSelected();
	}

	public void goToTime(TraceSchedule time) {
		traceManager.activateTime(time);
	}
}

/**
 * Copyright (c) 2011-2014, SpaceToad and the BuildCraft Team
 * http://www.mod-buildcraft.com
 *
 * BuildCraft is distributed under the terms of the Minecraft Mod Public
 * License 1.0, or MMPL. Please check the contents of the license located in
 * http://www.mod-buildcraft.com/MMPL-1.0.txt
 */
package buildcraft.transport;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;
import java.util.Random;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;
import net.minecraft.world.WorldSettings.GameType;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

import net.minecraftforge.common.util.ForgeDirection;

import buildcraft.BuildCraftTransport;
import buildcraft.api.core.IIconProvider;
import buildcraft.api.gates.ActionState;
import buildcraft.api.gates.IAction;
import buildcraft.api.gates.ITrigger;
import buildcraft.api.transport.IPipe;
import buildcraft.api.transport.IPipeTile;
import buildcraft.api.transport.PipeWire;
import buildcraft.core.IDropControlInventory;
import buildcraft.core.inventory.InvUtils;
import buildcraft.core.network.TilePacketWrapper;
import buildcraft.core.utils.Utils;
import buildcraft.transport.gates.ActionSlot;
import buildcraft.transport.gates.GateFactory;
import buildcraft.transport.pipes.events.PipeEvent;

public abstract class Pipe<T extends PipeTransport> implements IDropControlInventory, IPipe {

	@SuppressWarnings("rawtypes")
	private static Map<Class, TilePacketWrapper> networkWrappers = new HashMap<Class, TilePacketWrapper>();
	private static Map<Class<? extends Pipe>, Map<Class<? extends PipeEvent>, EventHandler>> eventHandlers = new HashMap<Class<? extends Pipe>, Map<Class<? extends PipeEvent>, EventHandler>>();

	public int[] signalStrength = new int[]{0, 0, 0, 0};
	public TileGenericPipe container;
	public final T transport;
	public final Item item;
	public boolean[] wireSet = new boolean[]{false, false, false, false};
	public final Gate[] gates = new Gate[ForgeDirection.VALID_DIRECTIONS.length];

	private boolean internalUpdateScheduled = false;
	private boolean initialized = false;
	private boolean closed = false;

	private ArrayList<ActionState> actionStates = new ArrayList<ActionState>();

	public Pipe(T transport, Item item) {
		this.transport = transport;
		this.item = item;

		if (!networkWrappers.containsKey(this.getClass())) {
			networkWrappers
					.put(this.getClass(), new TilePacketWrapper(new Class[]{TileGenericPipe.class, this.transport.getClass()}));
		}
	}

	public void setTile(TileEntity tile) {
		this.container = (TileGenericPipe) tile;
		transport.setTile((TileGenericPipe) tile);
	}

	public void resolveActions() {
		for (Gate gate : gates) {
			if (gate != null) {
				gate.resolveActions();
			}
		}
	}

	//	public final void handlePipeEvent(PipeEvent event) {
//		try {
//			Method method = getClass().getDeclaredMethod("eventHandler", event.getClass());
//			method.invoke(this, event);
//		} catch (Exception ex) {
//		}
//	}
	private static class EventHandler {

		public final Method method;

		public EventHandler(Method method) {
			this.method = method;
		}
	}

	public final void handlePipeEvent(PipeEvent event) {
		Map<Class<? extends PipeEvent>, EventHandler> handlerMap = eventHandlers.get(getClass());

		if (handlerMap == null) {
			handlerMap = new HashMap<Class<? extends PipeEvent>, EventHandler>();
			eventHandlers.put(getClass(), handlerMap);
		}

		EventHandler handler = handlerMap.get(event.getClass());

		if (handler == null) {
			handler = makeEventHandler(event, handlerMap);
		}

		if (handler.method == null) {
			return;
		}

		try {
			handler.method.invoke(this, event);
		} catch (Exception ex) {
		}
	}

	private EventHandler makeEventHandler(PipeEvent event, Map<Class<? extends PipeEvent>, EventHandler> handlerMap) {
		EventHandler handler;

		try {
			Method method = getClass().getDeclaredMethod("eventHandler", event.getClass());
			handler = new EventHandler(method);
		} catch (Exception ex) {
			handler = new EventHandler(null);
		}

		handlerMap.put(event.getClass(), handler);
		return handler;
	}

	public boolean blockActivated(EntityPlayer entityplayer) {
		return false;
	}

	public void onBlockPlaced() {
		transport.onBlockPlaced();
	}

	public void onBlockPlacedBy(EntityLivingBase placer) {
	}

	public void onNeighborBlockChange(int blockId) {
		transport.onNeighborBlockChange(blockId);

	}

	public boolean canPipeConnect(TileEntity tile, ForgeDirection side) {
		Pipe<?> otherPipe;

		if (tile instanceof TileGenericPipe) {
			otherPipe = ((TileGenericPipe) tile).pipe;
			if (!BlockGenericPipe.isFullyDefined(otherPipe)) {
				return false;
			}

			if (!PipeConnectionBans.canPipesConnect(getClass(), otherPipe.getClass())) {
				return false;
			}
		}

		return transport.canPipeConnect(tile, side);
	}

	/**
	 * Should return the textureindex used by the Pipe Item Renderer, as this is
	 * done client-side the default implementation might not work if your
	 * getTextureIndex(Orienations.Unknown) has logic. Then override this
	 */
	public int getIconIndexForItem() {
		return getIconIndex(ForgeDirection.UNKNOWN);
	}

	/**
	 * Should return the IIconProvider that provides icons for this pipe
	 *
	 * @return An array of icons
	 */
	@SideOnly(Side.CLIENT)
	public abstract IIconProvider getIconProvider();

	/**
	 * Should return the index in the array returned by GetTextureIcons() for a
	 * specified direction
	 *
	 * @param direction - The direction for which the indexed should be
	 * rendered. Unknown for pipe center
	 *
	 * @return An index valid in the array returned by getTextureIcons()
	 */
	public abstract int getIconIndex(ForgeDirection direction);

	public void updateEntity() {
		transport.updateEntity();

		if (internalUpdateScheduled) {
			internalUpdate();
			internalUpdateScheduled = false;
		}

		closed = false;
		actionStates.clear();

		// Update the gate if we have any
		for (Gate gate : gates) {
			if (gate == null) {
				continue;
			}
			if (container.getWorldObj().isRemote) {
				// on client, only update the graphical pulse if needed
				gate.updatePulse();
			} else {
				// on server, do the internal gate update
				gate.resolveActions();
				gate.tick();
			}
		}
	}

	private void internalUpdate() {
		updateSignalState();
	}

	public void writeToNBT(NBTTagCompound data) {
		transport.writeToNBT(data);

		// Save gate if any
		for (int i = 0; i < ForgeDirection.VALID_DIRECTIONS.length; i++) {
			final String key = "Gate[" + i + "]";
			Gate gate = gates[i];
			if (gate != null) {
				NBTTagCompound gateNBT = new NBTTagCompound();
				gate.writeToNBT(gateNBT);
				data.setTag(key, gateNBT);
			} else {
				data.removeTag(key);
			}
		}

		for (int i = 0; i < 4; ++i) {
			data.setBoolean("wireSet[" + i + "]", wireSet[i]);
		}
	}

	public void readFromNBT(NBTTagCompound data) {
		transport.readFromNBT(data);

		for (int i = 0; i < ForgeDirection.VALID_DIRECTIONS.length; i++) {
			final String key = "Gate[" + i + "]";
			gates[i] = data.hasKey(key) ? GateFactory.makeGate(this, data.getCompoundTag(key)) : null;
		}

		// Legacy support
		if (data.hasKey("Gate")) {
			for (int i = 0; i < ForgeDirection.VALID_DIRECTIONS.length; i++) {
				transport.container.setGate(GateFactory.makeGate(this, data.getCompoundTag("Gate")), i);
			}
			data.removeTag("Gate");
		}

		for (int i = 0; i < 4; ++i) {
			wireSet[i] = data.getBoolean("wireSet[" + i + "]");
		}
	}

	public boolean needsInit() {
		return !initialized;
	}

	public void initialize() {
		transport.initialize();
		updateSignalState();
		initialized = true;
	}

	private void readNearbyPipesSignal(PipeWire color) {
		boolean foundBiggerSignal = false;

		for (ForgeDirection o : ForgeDirection.VALID_DIRECTIONS) {
			TileEntity tile = container.getTile(o);

			if (tile instanceof TileGenericPipe) {
				TileGenericPipe tilePipe = (TileGenericPipe) tile;

				if (BlockGenericPipe.isFullyDefined(tilePipe.pipe)) {
					if (isWireConnectedTo(tile, color)) {
						foundBiggerSignal |= receiveSignal(tilePipe.pipe.signalStrength[color.ordinal()] - 1, color);
					}
				}
			}
		}

		if (!foundBiggerSignal && signalStrength[color.ordinal()] != 0) {
			signalStrength[color.ordinal()] = 0;
			// worldObj.markBlockNeedsUpdate(container.xCoord, container.yCoord, zCoord);
			container.scheduleRenderUpdate();

			for (ForgeDirection o : ForgeDirection.VALID_DIRECTIONS) {
				TileEntity tile = container.getTile(o);

				if (tile instanceof TileGenericPipe) {
					TileGenericPipe tilePipe = (TileGenericPipe) tile;

					if (BlockGenericPipe.isFullyDefined(tilePipe.pipe)) {
						tilePipe.pipe.internalUpdateScheduled = true;
					}
				}
			}
		}
	}

	public void updateSignalState() {
		for (PipeWire c : PipeWire.values()) {
			updateSignalStateForColor(c);
		}
	}

	private void updateSignalStateForColor(PipeWire wire) {
		if (!wireSet[wire.ordinal()]) {
			return;
		}

		// STEP 1: compute internal signal strength

		boolean readNearbySignal = true;
		for (Gate gate : gates) {
			if (gate != null && gate.broadcastSignal.get(wire.ordinal())) {
				receiveSignal(255, wire);
				readNearbySignal = false;
			}
		}

		if (readNearbySignal) {
			readNearbyPipesSignal(wire);
		}

		// STEP 2: transmit signal in nearby blocks

		if (signalStrength[wire.ordinal()] > 1) {
			for (ForgeDirection o : ForgeDirection.VALID_DIRECTIONS) {
				TileEntity tile = container.getTile(o);

				if (tile instanceof TileGenericPipe) {
					TileGenericPipe tilePipe = (TileGenericPipe) tile;

					if (BlockGenericPipe.isFullyDefined(tilePipe.pipe) && tilePipe.pipe.wireSet[wire.ordinal()]) {
						if (isWireConnectedTo(tile, wire)) {
							tilePipe.pipe.receiveSignal(signalStrength[wire.ordinal()] - 1, wire);
						}
					}
				}
			}
		}
	}

	private boolean receiveSignal(int signal, PipeWire color) {
		if (container.getWorldObj() == null) {
			return false;
		}

		int oldSignal = signalStrength[color.ordinal()];

		if (signal >= signalStrength[color.ordinal()] && signal != 0) {
			signalStrength[color.ordinal()] = signal;
			internalUpdateScheduled = true;

			if (oldSignal == 0) {
				container.scheduleRenderUpdate();
			}

			return true;
		} else {
			return false;
		}
	}

	public boolean inputOpen(ForgeDirection from) {
		return transport.inputOpen(from);
	}

	public boolean outputOpen(ForgeDirection to) {
		return transport.outputOpen(to);
	}

	public void onEntityCollidedWithBlock(Entity entity) {
	}

	public boolean canConnectRedstone() {
		for (Gate gate : gates) {
			if (gate != null) {
				return true;
			}
		}
		return false;
	}

	private int getMaxRedstoneOutput() {
		int max = 0;

		for (ForgeDirection dir : ForgeDirection.VALID_DIRECTIONS) {
			Gate gate = gates[dir.ordinal()];

			if (gate != null && gate.getRedstoneOutput() > max) {
				max = gate.getRedstoneOutput();
			}
		}

		return max;
	}

	public int isPoweringTo(int side) {
		ForgeDirection o = ForgeDirection.getOrientation(side).getOpposite();

		TileEntity tile = container.getTile(o);

		if (tile instanceof TileGenericPipe && container.isPipeConnected(o)) {
			return 0;
		} else {
			return getMaxRedstoneOutput();
		}
	}

	public int isIndirectlyPoweringTo(int l) {
		return isPoweringTo(l);
	}

	public void randomDisplayTick(Random random) {
	}

	// / @Override TODO: should be in IPipe
	public boolean isWired() {
		for (PipeWire color : PipeWire.values()) {
			if (isWired(color)) {
				return true;
			}
		}

		return false;
	}

	public boolean isWired(PipeWire color) {
		return wireSet[color.ordinal()];
	}

	@Deprecated
	public boolean hasGate() {
		for (ForgeDirection direction : ForgeDirection.VALID_DIRECTIONS) {
			if (hasGate(direction)) {
				return true;
			}
		}
		return false;
	}

	public boolean hasGate(ForgeDirection side) {
		return container.hasGate(side);
	}

	protected void notifyBlocksOfNeighborChange(ForgeDirection side) {
		container.getWorldObj().notifyBlocksOfNeighborChange(container.xCoord + side.offsetX, container.yCoord + side.offsetY, container.zCoord + side.offsetZ, BuildCraftTransport.genericPipeBlock);
	}

	protected void updateNeighbors(boolean needSelf) {
		if (needSelf) {
			container.getWorldObj().notifyBlocksOfNeighborChange(container.xCoord, container.yCoord, container.zCoord, BuildCraftTransport.genericPipeBlock);
		}
		for (ForgeDirection side : ForgeDirection.VALID_DIRECTIONS) {
			notifyBlocksOfNeighborChange(side);
		}
	}

	public void dropItem(ItemStack stack) {
		InvUtils.dropItems(container.getWorldObj(), stack, container.xCoord, container.yCoord, container.zCoord);
	}

	public void onBlockRemoval() {

		if (getWorld().getWorldInfo().getGameType() != GameType.CREATIVE) {
			for (ItemStack stack : computeItemDrop()) {
				dropItem(stack);
			}
		}
	}

	public ArrayList<ItemStack> computeItemDrop() {
		ArrayList<ItemStack> result = new ArrayList<ItemStack>();

		for (PipeWire pipeWire : PipeWire.VALUES) {
			if (wireSet[pipeWire.ordinal()]) {
				result.add(pipeWire.getStack());
			}
		}

		for (Gate gate : gates) {
			if (gate != null) {
				result.add(gate.getGateItem());
			}
		}

		for (ForgeDirection direction : ForgeDirection.VALID_DIRECTIONS) {
			if (container.hasFacade(direction)) {
				result.add (container.getFacade(direction));
			}

			if (container.hasPlug(direction)) {
				result.add (new ItemStack(BuildCraftTransport.plugItem));
			}

			if (container.hasRobotStation(direction)) {
				result.add (new ItemStack(BuildCraftTransport.robotStationItem));
			}
		}

		return result;
	}

	public boolean isTriggerActive(ITrigger trigger) {
		return false;
	}

	public LinkedList<IAction> getActions() {
		LinkedList<IAction> result = new LinkedList<IAction>();

		result.add(BuildCraftTransport.actionPipeClose);

		return result;
	}

	public void resetGates() {
		for (int i = 0; i < gates.length; i++) {
			Gate gate = gates[i];
			if (gate != null) {
				gate.resetGate();
			}
			gates[i] = null;
		}

		internalUpdateScheduled = true;
		container.scheduleRenderUpdate();
	}

	protected void actionsActivated(Collection<ActionSlot> actions) {
	}

	public TileGenericPipe getContainer() {
		return container;
	}

	public boolean isWireConnectedTo(TileEntity tile, PipeWire color) {
		if (!(tile instanceof TileGenericPipe)) {
			return false;
		}

		TileGenericPipe tilePipe = (TileGenericPipe) tile;

		if (!BlockGenericPipe.isFullyDefined(tilePipe.pipe)) {
			return false;
		}

		if (!tilePipe.pipe.wireSet[color.ordinal()]) {
			return false;
		}

		return tilePipe.pipe.transport instanceof PipeTransportStructure || transport instanceof PipeTransportStructure
				|| Utils.checkPipesConnections(
						container, tile);
	}

	public void dropContents() {
		transport.dropContents();
	}

	/**
	 * If this pipe is open on one side, return it.
	 */
	public ForgeDirection getOpenOrientation() {
		int connectionsNum = 0;

		ForgeDirection targetOrientation = ForgeDirection.UNKNOWN;

		for (ForgeDirection o : ForgeDirection.VALID_DIRECTIONS) {
			if (container.isPipeConnected(o)) {

				connectionsNum++;

				if (connectionsNum == 1) {
					targetOrientation = o;
				}
			}
		}

		if (connectionsNum > 1 || connectionsNum == 0) {
			return ForgeDirection.UNKNOWN;
		}

		return targetOrientation.getOpposite();
	}

	@Override
	public boolean doDrop() {
		return true;
	}

	/**
	 * Called when TileGenericPipe.invalidate() is called
	 */
	public void invalidate() {
	}

	/**
	 * Called when TileGenericPipe.validate() is called
	 */
	public void validate() {
	}

	/**
	 * Called when TileGenericPipe.onChunkUnload is called
	 */
	public void onChunkUnload() {
	}

	public World getWorld() {
		return container.getWorldObj();
	}

	@Override
	public int x() {
		return container.xCoord;
	}

	@Override
	public int y() {
		return container.yCoord;
	}

	@Override
	public int z() {
		return container.zCoord;
	}

	@Override
	public IPipeTile getTile() {
		return container;
	}

	@Override
	public TileEntity getAdjacentTile(ForgeDirection dir) {
		return container.getTile(dir);
	}

	public void close() {
		closed = true;
	}

	public boolean isClosed() {
		return closed;
	}

	private void pushActionState(ActionState state) {
		actionStates.add(state);
	}

	private Collection<ActionState> getActionStates() {
		return actionStates;
	}
}

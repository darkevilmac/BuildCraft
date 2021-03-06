/**
 * Copyright (c) 2011-2014, SpaceToad and the BuildCraft Team
 * http://www.mod-buildcraft.com
 *
 * BuildCraft is distributed under the terms of the Minecraft Mod Public
 * License 1.0, or MMPL. Please check the contents of the license located in
 * http://www.mod-buildcraft.com/MMPL-1.0.txt
 */
package buildcraft.commander;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;

import buildcraft.api.robots.EntityRobotBase;
import buildcraft.api.robots.IRequestProvider;
import buildcraft.api.robots.StackRequest;
import buildcraft.core.TileBuildCraft;
import buildcraft.core.inventory.SimpleInventory;
import buildcraft.core.inventory.StackHelper;
import buildcraft.core.network.RPC;
import buildcraft.core.network.RPCHandler;
import buildcraft.core.network.RPCSide;
import buildcraft.core.robots.ResourceIdRequest;
import buildcraft.core.robots.RobotRegistry;

public class TileRequester extends TileBuildCraft implements IInventory, IRequestProvider {

	public static final int NB_ITEMS = 20;

	private SimpleInventory inv = new SimpleInventory(NB_ITEMS, "items", 64);
	private SimpleInventory requests = new SimpleInventory(NB_ITEMS, "requests", 64);

	public TileRequester() {

	}

	@RPC(RPCSide.SERVER)
	public void setRequest(int index, ItemStack stack) {
		if (worldObj.isRemote) {
			RPCHandler.rpcServer(this, "setRequest", index, stack);
			return;
		}

		requests.setInventorySlotContents(index, stack);
	}

	public ItemStack getRequest(int index) {
		return requests.getStackInSlot(index);
	}

	@Override
	public int getSizeInventory() {
		return inv.getSizeInventory();
	}

	@Override
	public ItemStack getStackInSlot(int slotId) {
		return inv.getStackInSlot(slotId);
	}

	@Override
	public ItemStack decrStackSize(int slotId, int count) {
		return inv.decrStackSize(slotId, count);
	}

	@Override
	public ItemStack getStackInSlotOnClosing(int slotId) {
		return inv.getStackInSlotOnClosing(slotId);
	}

	@Override
	public void setInventorySlotContents(int slotId, ItemStack itemStack) {
		inv.setInventorySlotContents(slotId, itemStack);
	}

	@Override
	public String getInventoryName() {
		return inv.getInventoryName();
	}

	@Override
	public boolean hasCustomInventoryName() {
		return inv.hasCustomInventoryName();
	}

	@Override
	public int getInventoryStackLimit() {
		return inv.getInventoryStackLimit();
	}

	@Override
	public boolean isUseableByPlayer(EntityPlayer entityPlayer) {
		return inv.isUseableByPlayer(entityPlayer);
	}

	@Override
	public void openInventory() {
		inv.openInventory();
	}

	@Override
	public void closeInventory() {
		inv.closeInventory();
	}

	@Override
	public boolean isItemValidForSlot(int i, ItemStack itemStack) {
		if (requests.getStackInSlot(i) == null) {
			return false;
		} else if (!StackHelper.isMatchingItem(requests.getStackInSlot(i), itemStack)) {
			return false;
		} else {
			return inv.isItemValidForSlot(i, itemStack);
		}
	}

	@Override
	public void writeToNBT(NBTTagCompound nbt) {
		super.writeToNBT(nbt);

		NBTTagCompound invNBT = new NBTTagCompound();
		inv.writeToNBT(invNBT);
		nbt.setTag("inv", invNBT);

		NBTTagCompound reqNBT = new NBTTagCompound();
		requests.writeToNBT(reqNBT);
		nbt.setTag("req", reqNBT);
	}

	@Override
	public void readFromNBT(NBTTagCompound nbt) {
		super.readFromNBT(nbt);

		inv.readFromNBT(nbt.getCompoundTag("inv"));
		requests.readFromNBT(nbt.getCompoundTag("req"));
	}

	public boolean isFulfilled(int i) {
		if (requests.getStackInSlot(i) == null) {
			return true;
		} else if (inv.getStackInSlot(i) == null) {
			return false;
		} else {
			return StackHelper.isMatchingItem(requests.getStackInSlot(i), inv.getStackInSlot(i))
					&& inv.getStackInSlot(i).stackSize >= requests.getStackInSlot(i).stackSize;
		}
	}

	@Override
	public int getNumberOfRequests() {
		return NB_ITEMS;
	}

	@Override
	public StackRequest getAvailableRequest(int i) {
		if (requests.getStackInSlot(i) == null) {
			return null;
		} else if (isFulfilled(i)) {
			return null;
		} else if (RobotRegistry.getRegistry(worldObj).isTaken(new ResourceIdRequest(this, i))) {
			return null;
		} else {
			StackRequest r = new StackRequest();

			r.index = i;
			r.stack = requests.getStackInSlot(i);
			r.requester = this;

			return r;
		}
	}

	@Override
	public boolean takeRequest(int i, EntityRobotBase robot) {
		if (requests.getStackInSlot(i) == null) {
			return false;
		} else if (isFulfilled(i)) {
			return false;
		} else {
			return RobotRegistry.getRegistry(worldObj).take(new ResourceIdRequest(this, i), robot);
		}
	}

	@Override
	public ItemStack provideItemsForRequest(int i, ItemStack stack) {
		ItemStack existingStack = inv.getStackInSlot(i);

		if (requests.getStackInSlot(i) == null) {
			return stack;
		} else if (existingStack == null) {
			int maxQty = requests.getStackInSlot(i).stackSize;

			if (stack.stackSize <= maxQty) {
				inv.setInventorySlotContents(i, stack);

				return null;
			} else {
				ItemStack newStack = stack.copy();
				newStack.stackSize = maxQty;
				stack.stackSize -= maxQty;

				inv.setInventorySlotContents(i, newStack);

				return stack;
			}
		} else if (!StackHelper.isMatchingItem(stack, existingStack)) {
			return stack;
		} else if (existingStack == null || StackHelper.isMatchingItem(stack, requests.getStackInSlot(i))) {
			int maxQty = requests.getStackInSlot(i).stackSize;

			if (existingStack.stackSize + stack.stackSize <= maxQty) {
				existingStack.stackSize += stack.stackSize;
				return null;
			} else {
				stack.stackSize -= maxQty - existingStack.stackSize;
				existingStack.stackSize = maxQty;
				return stack;
			}
		} else {
			return stack;
		}
	}
}

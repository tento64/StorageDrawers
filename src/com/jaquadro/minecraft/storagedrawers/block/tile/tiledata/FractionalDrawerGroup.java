package com.jaquadro.minecraft.storagedrawers.block.tile.tiledata;

import com.jaquadro.minecraft.chameleon.block.tiledata.TileDataShim;
import com.jaquadro.minecraft.storagedrawers.api.storage.EmptyDrawerAttributes;
import com.jaquadro.minecraft.storagedrawers.api.storage.IDrawer;
import com.jaquadro.minecraft.storagedrawers.api.storage.IDrawerAttributes;
import com.jaquadro.minecraft.storagedrawers.api.storage.IDrawerGroup;
import com.jaquadro.minecraft.storagedrawers.api.storage.attribute.LockAttribute;
import com.jaquadro.minecraft.storagedrawers.inventory.ItemStackHelper;
import com.jaquadro.minecraft.storagedrawers.storage.BaseDrawerData;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.CapabilityInject;
import net.minecraftforge.common.capabilities.ICapabilityProvider;
import net.minecraftforge.common.util.Constants;
import net.minecraftforge.common.util.INBTSerializable;

import javax.annotation.Nonnull;

public class FractionalDrawerGroup extends TileDataShim implements IDrawerGroup
{
    private FractionalStorage storage;
    private FractionalDrawer[] slots;

    public FractionalDrawerGroup (ICapabilityProvider capProvider, int slotCount) {
        storage = new FractionalStorage(capProvider, this, slotCount);

        slots = new FractionalDrawer[slotCount];
        for (int i = 0; i < slotCount; i++) {
            slots[i] = new FractionalDrawer(storage, i);
        }
    }

    @Override
    public int getDrawerCount () {
        return slots.length;
    }

    @Override
    public FractionalDrawer getDrawer (int slot) {
        return slots[slot];
    }

    @Override
    public void readFromNBT (NBTTagCompound tag) {
        if (tag.hasKey("Drawers"))
            storage.deserializeNBT(tag.getCompoundTag("Drawers"));
    }

    @Override
    public NBTTagCompound writeToNBT (NBTTagCompound tag) {
        tag.setTag("Drawers", storage.serializeNBT());
        return tag;
    }

    private static class FractionalStorage implements INBTSerializable<NBTTagCompound>
    {
        @CapabilityInject(IDrawerAttributes.class)
        static Capability<IDrawerAttributes> ATTR_CAPABILITY = null;

        private FractionalDrawerGroup group;
        private int slotCount;
        private ItemStack[] protoStack;
        private int[] convRate;
        private int pooledCount;

        IDrawerAttributes attrs;

        public FractionalStorage (ICapabilityProvider capProvider, FractionalDrawerGroup group, int slotCount) {
            this.group = group;
            this.slotCount = slotCount;

            protoStack = new ItemStack[slotCount];
            for (int i = 0; i < slotCount; i++)
                protoStack[i] = ItemStack.EMPTY;

            convRate = new int[slotCount];

            attrs = capProvider.getCapability(ATTR_CAPABILITY, null);
            if (attrs == null)
                attrs = new EmptyDrawerAttributes();
        }

        @Nonnull
        public ItemStack getStack (int slot) {
            return protoStack[slot];
        }

        @Nonnull
        public ItemStack baseStack () {
            return protoStack[0];
        }

        public int baseRate () {
            return convRate[0];
        }

        public FractionalDrawer setStoredItem (int slot, @Nonnull ItemStack itemPrototype) {
            itemPrototype = ItemStackHelper.getItemPrototype(itemPrototype);
            if (itemPrototype.isEmpty()) {
                reset();
                return group.getDrawer(slot);
            }

            if (baseRate() == 0) {
                populateSlots(itemPrototype);
                for (int i = 0; i < slotCount; i++) {
                    if (BaseDrawerData.areItemsEqual(protoStack[i], itemPrototype)) {
                        slot = i;
                        pooledCount = 0;
                    }
                }

                for (int i = 0; i < slotCount; i++)
                    group.getDrawer(i).reset();

                onItemChanged();
            }

            return group.getDrawer(slot);
        }

        public int getStoredCount (int slot) {
            if (convRate[slot] == 0)
                return 0;

            if (attrs.isUnlimitedVending())
                return Integer.MAX_VALUE;

            return pooledCount / convRate[slot];
        }

        public void setStoredItemCount (int slot, int amount) {
            if (convRate[slot] == 0)
                return;

            if (attrs.isUnlimitedVending())
                return;

            int oldCount = pooledCount;

            pooledCount = (pooledCount % convRate[slot]) + convRate[slot] * amount;
            pooledCount = Math.min(pooledCount, getMaxCapacity(0) * convRate[0]);
            pooledCount = Math.max(pooledCount, 0);

            if (pooledCount == oldCount)
                return;

            if (pooledCount == 0 && !attrs.isItemLocked(LockAttribute.LOCK_POPULATED))
                reset();
            else
                onAmountChanged();
        }

        public int adjustStoredItemCount (int slot, int amount) {
            if (convRate[slot] == 0 || amount == 0)
                return amount;

            if (amount > 0) {
                if (attrs.isUnlimitedVending())
                    return 0;

                int poolMax = getMaxCapacity(0) * convRate[0];
                if (poolMax < 0)
                    poolMax = Integer.MAX_VALUE;

                int canAdd = (poolMax - pooledCount) / convRate[slot];
                int willAdd = Math.min(amount, canAdd);
                if (willAdd == 0)
                    return amount;

                pooledCount += convRate[slot] * willAdd;

                onAmountChanged();

                if (attrs.isVoid())
                    return 0;

                return amount - willAdd;
            }
            else {
                amount = -amount;

                int canRemove = pooledCount / convRate[slot];
                int willRemove = Math.min(amount, canRemove);
                if (willRemove == 0)
                    return amount;

                pooledCount -= willRemove;

                if (pooledCount == 0 && !attrs.isItemLocked(LockAttribute.LOCK_POPULATED))
                    reset();
                else
                    onAmountChanged();

                return amount - willRemove;
            }
        }

        public int getMaxCapacity (int slot) {
            if (baseStack().isEmpty() || convRate[slot] == 0)
                return 0;

            if (attrs.isUnlimitedStorage() || attrs.isUnlimitedVending())
                return Integer.MAX_VALUE / convRate[slot];

            return baseStack().getItem().getItemStackLimit(baseStack()) * getStackCapacity() * (baseRate() / convRate[slot]);
        }

        public int getMaxCapacity (int slot, @Nonnull ItemStack itemPrototype) {
            if (attrs.isUnlimitedStorage() || attrs.isUnlimitedVending()) {
                if (convRate[slot] == 0)
                    return Integer.MAX_VALUE;
                return Integer.MAX_VALUE / convRate[slot];
            }

            if (baseStack().isEmpty()) {
                int itemStackLimit = 64;
                if (!itemPrototype.isEmpty())
                    itemStackLimit = itemPrototype.getItem().getItemStackLimit(itemPrototype);
                return itemStackLimit * getStackCapacity();
            }

            if (BaseDrawerData.areItemsEqual(protoStack[slot], itemPrototype))
                return getMaxCapacity(slot);

            return 0;
        }

        public int getRemainingCapacity (int slot) {
            if (baseStack().isEmpty() || convRate[slot] == 0)
                return 0;

            if (attrs.isUnlimitedVending())
                return Integer.MAX_VALUE;

            int rawMaxCapacity = baseStack().getItem().getItemStackLimit(baseStack()) * getStackCapacity() * baseRate();
            int rawRemaining = rawMaxCapacity - pooledCount;

            return rawRemaining / convRate[slot];
        }

        public boolean isEmpty (int slot) {
            return protoStack[slot].isEmpty();
        }

        public boolean isEnabled (int slot) {
            if (baseStack().isEmpty())
                return true;

            return !protoStack[slot].isEmpty();
        }

        private void reset () {
            pooledCount = 0;

            for (int i = 0; i < slotCount; i++) {
                protoStack[i] = ItemStack.EMPTY;
                convRate[i] = 0;
            }

            for (int i = 0; i < slotCount; i++)
                group.getDrawer(i).reset();

            onItemChanged();
        }

        @Override
        public NBTTagCompound serializeNBT () {
            NBTTagList itemList = new NBTTagList();
            for (int i = 0; i < slotCount; i++) {
                if (protoStack[i].isEmpty())
                    continue;

                NBTTagCompound itemTag = new NBTTagCompound();
                protoStack[i].writeToNBT(itemTag);

                NBTTagCompound slotTag = new NBTTagCompound();
                slotTag.setByte("Slot", (byte)i);
                slotTag.setInteger("Conv", convRate[i]);
                slotTag.setTag("Item", itemTag);

                itemList.appendTag(slotTag);
            }

            NBTTagCompound tag = new NBTTagCompound();
            tag.setInteger("Count", pooledCount);
            tag.setTag("Items", itemList);

            return tag;
        }

        @Override
        public void deserializeNBT (NBTTagCompound tag) {
            for (int i = 0; i < slotCount; i++) {
                protoStack[i] = ItemStack.EMPTY;
                convRate[i] = 0;
            }

            pooledCount = tag.getInteger("Count");

            NBTTagList itemList = tag.getTagList("Items", Constants.NBT.TAG_COMPOUND);
            for (int i = 0; i < itemList.tagCount(); i++) {
                NBTTagCompound slotTag = itemList.getCompoundTagAt(i);
                int slot = slotTag.getByte("Slot");

                protoStack[slot] = new ItemStack(slotTag.getCompoundTag("Item"));
                convRate[slot] = slotTag.getByte("Conv");
            }

            for (int i = 0; i < slotCount; i++)
                group.getDrawer(i).reset();

            onItemChanged();
        }

        private void populateSlots(@Nonnull ItemStack stack) {
            
        }

        protected int getStackCapacity() {
            return 0;
        }

        protected void onItemChanged() { }

        protected void onAmountChanged() { }
    }

    private static class FractionalDrawer extends BaseDrawerData
    {
        private FractionalStorage storage;
        private int slot;

        IDrawerAttributes attrs;

        public FractionalDrawer (FractionalStorage storage, int slot) {
            this.storage = storage;
            this.slot = slot;
        }

        @Nonnull
        @Override
        public ItemStack getStoredItemPrototype () {
            return storage.getStack(slot);
        }

        @Nonnull
        @Override
        public IDrawer setStoredItem (@Nonnull ItemStack itemPrototype) {
            return storage.setStoredItem(slot, itemPrototype);
        }

        @Override
        public int getStoredItemCount () {
            return storage.getStoredCount(slot);
        }

        @Override
        public void setStoredItemCount (int amount) {
            storage.setStoredItemCount(slot, amount);
        }

        @Override
        public int adjustStoredItemCount (int amount) {
            return storage.adjustStoredItemCount(slot, amount);
        }

        @Override
        public int getMaxCapacity () {
            return storage.getMaxCapacity(slot);
        }

        @Override
        public int getMaxCapacity (@Nonnull ItemStack itemPrototype) {
            return storage.getMaxCapacity(slot, itemPrototype);
        }

        @Override
        public int getRemainingCapacity () {
            return storage.getRemainingCapacity(slot);
        }

        @Override
        public boolean canItemBeStored (@Nonnull ItemStack itemPrototype) {
            if (getStoredItemPrototype().isEmpty() && !attrs.isItemLocked(LockAttribute.LOCK_EMPTY))
                return true;

            return areItemsEqual(itemPrototype);
        }

        @Override
        public boolean canItemBeExtracted (@Nonnull ItemStack itemPrototype) {
            return areItemsEqual(itemPrototype);
        }

        @Override
        public boolean isEmpty () {
            return storage.isEmpty(slot);
        }

        @Override
        public boolean isEnabled () {
            return storage.isEnabled(slot);
        }

        @Override
        protected void reset () {
            super.reset();
            refreshOreDictMatches();
        }

        @Override
        public NBTTagCompound serializeNBT () {
            // Handled by group
            return new NBTTagCompound();
        }

        @Override
        public void deserializeNBT (NBTTagCompound nbt) {
            // Handled by group
        }
    }
}

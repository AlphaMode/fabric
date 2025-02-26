/*
 * Copyright (c) 2016, 2017, 2018, 2019 FabricMC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.fabricmc.fabric.impl.transfer.item;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.util.Hand;

import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.item.PlayerInventoryStorage;
import net.fabricmc.fabric.api.transfer.v1.storage.StoragePreconditions;
import net.fabricmc.fabric.api.transfer.v1.storage.base.SingleSlotStorage;
import net.fabricmc.fabric.api.transfer.v1.transaction.TransactionContext;
import net.fabricmc.fabric.api.transfer.v1.transaction.base.SnapshotParticipant;

class PlayerInventoryStorageImpl extends InventoryStorageImpl implements PlayerInventoryStorage {
	private final DroppedStacks droppedStacks;
	private final PlayerInventory playerInventory;

	PlayerInventoryStorageImpl(PlayerInventory playerInventory) {
		super(playerInventory);
		this.droppedStacks = new DroppedStacks();
		this.playerInventory = playerInventory;
	}

	@Override
	public long insert(ItemVariant resource, long maxAmount, TransactionContext transaction) {
		return offer(resource, maxAmount, transaction);
	}

	@Override
	public long offer(ItemVariant resource, long amount, TransactionContext tx) {
		StoragePreconditions.notBlankNotNegative(resource, amount);
		long initialAmount = amount;

		List<SingleSlotStorage<ItemVariant>> mainSlots = getSlots().subList(0, PlayerInventory.MAIN_SIZE);

		// Stack into the main stack first and the offhand stack second.
		for (Hand hand : Hand.values()) {
			SingleSlotStorage<ItemVariant> handSlot = getHandSlot(hand);

			if (handSlot.getResource().equals(resource)) {
				amount -= handSlot.insert(resource, amount, tx);

				if (amount == 0) return initialAmount;
			}
		}

		// Otherwise insert into the main slots, first iteration tries to stack, second iteration inserts into empty slots.
		for (int iteration = 0; iteration < 2; iteration++) {
			boolean allowEmptySlots = iteration == 1;

			for (SingleSlotStorage<ItemVariant> slot : mainSlots) {
				if (!slot.isResourceBlank() || allowEmptySlots) {
					amount -= slot.insert(resource, amount, tx);
				}

				if (amount == 0) return initialAmount;
			}
		}

		return initialAmount - amount;
	}

	@Override
	public void drop(ItemVariant resource, long amount, TransactionContext tx) {
		StoragePreconditions.notBlankNotNegative(resource, amount);

		// Drop in the world on the server side (will be synced by the game with the client).
		// Dropping items is server-side only because it involves randomness.
		if (amount > 0 && !playerInventory.player.world.isClient()) {
			droppedStacks.addDrop(resource, amount, tx);
		}
	}

	@Override
	public SingleSlotStorage<ItemVariant> getHandSlot(Hand hand) {
		if (Objects.requireNonNull(hand) == Hand.MAIN_HAND) {
			if (PlayerInventory.isValidHotbarIndex(playerInventory.selectedSlot)) {
				return getSlot(playerInventory.selectedSlot);
			} else {
				throw new RuntimeException("Unexpected player selected slot: " + playerInventory.selectedSlot);
			}
		} else if (hand == Hand.OFF_HAND) {
			return getSlot(PlayerInventory.OFF_HAND_SLOT);
		} else {
			throw new UnsupportedOperationException("Unknown hand: " + hand);
		}
	}

	private class DroppedStacks extends SnapshotParticipant<Integer> {
		final List<ItemVariant> droppedKeys = new ArrayList<>();
		final List<Long> droppedCounts = new ArrayList<>();

		void addDrop(ItemVariant key, long count, TransactionContext transaction) {
			updateSnapshots(transaction);
			droppedKeys.add(key);
			droppedCounts.add(count);
		}

		@Override
		protected Integer createSnapshot() {
			return droppedKeys.size();
		}

		@Override
		protected void readSnapshot(Integer snapshot) {
			// effectively cancel dropping the stacks
			int previousSize = snapshot;

			while (droppedKeys.size() > previousSize) {
				droppedKeys.remove(droppedKeys.size() - 1);
				droppedCounts.remove(droppedCounts.size() - 1);
			}
		}

		@Override
		protected void onFinalCommit() {
			// actually drop the stacks
			for (int i = 0; i < droppedKeys.size(); ++i) {
				ItemVariant key = droppedKeys.get(i);

				while (droppedCounts.get(i) > 0) {
					int dropped = (int) Math.min(key.getItem().getMaxCount(), droppedCounts.get(i));
					playerInventory.player.dropStack(key.toStack(dropped));
					droppedCounts.set(i, droppedCounts.get(i) - dropped);
				}
			}

			droppedKeys.clear();
			droppedCounts.clear();
		}
	}
}

/*
 * This file is part of GriefDefender, licensed under the MIT License (MIT).
 *
 * Copyright (c) bloodmc
 * Copyright (c) contributors
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package com.griefdefender.task;

import com.griefdefender.GDBootstrap;
import com.griefdefender.GDPlayerData;
import com.griefdefender.GriefDefenderPlugin;
import com.griefdefender.api.GriefDefender;
import com.griefdefender.api.claim.Claim;
import com.griefdefender.api.economy.BankTransactionType;
import com.griefdefender.api.permission.option.Options;
import com.griefdefender.claim.GDClaim;
import com.griefdefender.claim.GDClaimManager;
import com.griefdefender.configuration.GriefDefenderConfig;
import com.griefdefender.economy.GDBankTransaction;
import com.griefdefender.event.GDCauseStackManager;
import com.griefdefender.event.GDTaxClaimEvent;
import com.griefdefender.permission.GDPermissionManager;
import com.griefdefender.permission.GDPermissionUser;
import com.griefdefender.util.TaskUtil;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.OfflinePlayer;
import org.bukkit.scheduler.BukkitRunnable;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.Set;
import java.util.UUID;

public class TaxApplyTask extends BukkitRunnable {

    private final UUID worldUniqueId;
    final Economy economy;
    final GriefDefenderConfig<?> activeConfig;
    private int bankTransactionLogLimit = 60;

    public TaxApplyTask(UUID worldUniqueId) {
        this.worldUniqueId = worldUniqueId;
        this.economy = GriefDefenderPlugin.getInstance().getVaultProvider().getApi();
        this.activeConfig = GriefDefenderPlugin.getActiveConfig(this.worldUniqueId);
        int taxHour = GriefDefenderPlugin.getActiveConfig(worldUniqueId).getConfig().claim.taxApplyHour;
        long delay = TaskUtil.computeDelay(taxHour, 0, 0);
        this.runTaskTimer(GDBootstrap.getInstance(), delay, 1728000L);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    @Override
    public void run() {
        // don't do anything when there are no claims
        GDClaimManager claimManager = GriefDefenderPlugin.getInstance().dataStore.getClaimWorldManager(this.worldUniqueId);
        ArrayList<Claim> claimList = (ArrayList<Claim>) claimManager.getWorldClaims();
        if (claimList.size() == 0) {
            return;
        }

        this.bankTransactionLogLimit = this.activeConfig.getConfig().claim.bankTransactionLogLimit;
        Iterator<GDClaim> iterator = ((ArrayList) claimList.clone()).iterator();
        while (iterator.hasNext()) {
            GDClaim claim = iterator.next();
            final GDPlayerData playerData = claim.getOwnerPlayerData();
            if (claim.isWilderness()) {
                continue;
            }
            if (playerData == null) {
                continue;
            }

            if (!playerData.dataInitialized) {
                continue;
            }

            if (claim.isAdminClaim()) {
                // search for town
                final Set<Claim> children = claim.getChildren(false);
                for (Claim child : children) {
                    if (child.isTown()) {
                        handleTownTax((GDClaim) child, playerData);
                    } else if (child.isBasicClaim()) {
                        handleClaimTax((GDClaim) child, playerData, false);
                    }
                }
            } else {
                if (claim.isTown()) {
                    handleTownTax(claim, playerData);
                } else if (claim.isBasicClaim()){
                    handleClaimTax(claim, playerData, false);
                }
            }
        }
    }

    private void handleClaimTax(GDClaim claim, GDPlayerData playerData, boolean inTown) {
        final GDPermissionUser user = playerData.getSubject();
        final OfflinePlayer player = user.getOfflinePlayer();
        double taxRate = GDPermissionManager.getInstance().getInternalOptionValue(user, Options.TAX_RATE, claim, playerData);
        double taxOwed = claim.getEconomyData().getTaxBalance() + (claim.getClaimBlocks() * taxRate);
        GDCauseStackManager.getInstance().pushCause(player);
        GDTaxClaimEvent event = new GDTaxClaimEvent(claim, taxRate, taxOwed);
        GriefDefender.getEventManager().post(event);
        if (event.cancelled()) {
            return;
        }
        final double taxBalance = claim.getEconomyData().getTaxBalance();
        taxRate = event.getTaxRate();
        taxOwed = taxBalance + (claim.getClaimBlocks() * taxRate);
        final EconomyResponse response = this.economy.withdrawPlayer(player, taxOwed);
        if (!response.transactionSuccess()) {
            final Instant localNow = Instant.now();
            Instant taxPastDueDate = claim.getEconomyData().getTaxPastDueDate().orElse(null);
            if (taxPastDueDate == null) {
                claim.getEconomyData().setTaxPastDueDate(Instant.now());
            } else {
                final int taxExpirationDays = GDPermissionManager.getInstance().getInternalOptionValue(user, Options.TAX_EXPIRATION, claim, playerData).intValue();
                if (taxExpirationDays > 0) {
                    claim.getInternalClaimData().setExpired(true);
                    if (taxExpirationDays == 0) {
                        claim.getInternalClaimData().setExpired(true);
                        claim.getData().save();
                    } else if (taxPastDueDate.plus(Duration.ofDays(taxExpirationDays)).isBefore(localNow)) {
                        claim.getInternalClaimData().setExpired(true);
                        claim.getData().save();
                    }
                }
            }
            final double totalTaxOwed = taxBalance + taxOwed;
            claim.getEconomyData().setTaxBalance(totalTaxOwed);
            claim.getEconomyData().addBankTransaction(new GDBankTransaction(BankTransactionType.TAX_FAIL, Instant.now(), taxOwed));
        } else {
            claim.getEconomyData().addBankTransaction(new GDBankTransaction(BankTransactionType.TAX_SUCCESS, Instant.now(), taxOwed));
            claim.getEconomyData().setTaxPastDueDate(null);
            claim.getEconomyData().setTaxBalance(0);
            claim.getInternalClaimData().setExpired(false);

            if (inTown) {
                final GDClaim town = claim.getTownClaim();
                town.getData()
                    .getEconomyData()
                    .addBankTransaction(new GDBankTransaction(BankTransactionType.TAX_SUCCESS, Instant.now(), taxOwed));
                this.economy.bankDeposit(town.getEconomyAccountId().get().toString(), taxOwed);
            }
            claim.getData().save();
        }
    }

    private void handleTownTax(GDClaim town, GDPlayerData playerData) {
        final UUID townAccountId = town.getEconomyAccountId().orElse(null);
        if (townAccountId == null) {
            // Virtual Accounts not supported by Economy Plugin so ignore
            return;
        }
        Set<Claim> children = town.getChildren(true);
        for (Claim child : children) {
            // resident tax
            if (child.isBasicClaim()) {
                handleClaimTax((GDClaim) child, playerData, true);
            }
        }
        if (town.getOwnerUniqueId().equals(playerData.playerID)) {
            handleClaimTax(town, playerData, false);
        }
    }
}

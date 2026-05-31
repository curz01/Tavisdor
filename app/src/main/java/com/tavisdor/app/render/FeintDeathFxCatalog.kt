package com.tavisdor.app.render

import com.tavisdor.app.dungeon.Cell

/**
 * Archer Feint Death cast FX: [scythe.png] on the staff spell rise path.
 */
object FeintDeathFxCatalog {

    fun buildFxRequest(partyCell: Cell): WeaponFxRequest =
        WeaponFxRequest(
            attackerCell = partyCell,
            defenderCell = partyCell,
            kind = WeaponFxKind.FEINT_DEATH_RISE,
            castFromPartyIcon = true,
        )
}

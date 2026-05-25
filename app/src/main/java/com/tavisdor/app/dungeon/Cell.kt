package com.tavisdor.app.dungeon

/**
 * A single 1x1 grid square. Floor-local coordinates (x = column, y = row),
 * origin top-left, +x right, +y down - matches Android's pixel coordinate
 * orientation to keep the rendering math simple.
 */
data class Cell(val x: Int, val y: Int)

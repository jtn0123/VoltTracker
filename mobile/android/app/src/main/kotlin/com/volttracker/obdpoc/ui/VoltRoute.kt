package com.volttracker.obdpoc.ui

/**
 * Pages pushed over the tabs: Settings (the gear, from anywhere), Car › Health, Health › Live
 * signals, Health › Freeze frame, Live signals › All readings, and Settings' Adapter page opened
 * straight from Health.
 */
enum class VoltRoute { SETTINGS, HEALTH, ADAPTER, SIGNALS, FREEZE_FRAME, ALL_READINGS }

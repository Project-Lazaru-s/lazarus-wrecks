package com.lazarus.wrecks;

/** Pure numbers behind the Mechanics skills, kept apart from Immersive Vehicles so they can be tested alone. */
final class MechanicMath {
    /** A vehicle that jumps farther than this in one second was teleported or reloaded, not driven. */
    static final double MAX_STEP = 60;
    /** A tank that drops more than this in one second was drained or siphoned, not burned by the engine. */
    static final double MAX_BURN = 200;

    private MechanicMath() {}

    /** Blocks driven since the last sample, ignoring jumps. */
    static double moved(double fromX, double fromY, double fromZ, double x, double y, double z) {
        double d = Math.sqrt((x - fromX) * (x - fromX) + (y - fromY) * (y - fromY) + (z - fromZ) * (z - fromZ));
        return d > MAX_STEP ? 0 : d;
    }

    /** Fuel to give back: a share of what the engine burned since the last sample; refuelling and draining give none. */
    static double refund(double before, double after, double share) {
        double burned = before - after;
        return burned > 0 && burned <= MAX_BURN ? burned * share : 0;
    }

    /** Damage removed by one repair: a share of the vehicle's health, never more than the damage it has. */
    static double repaired(double damage, double health, double share) {
        return Math.max(0, Math.min(damage, health * share));
    }
}

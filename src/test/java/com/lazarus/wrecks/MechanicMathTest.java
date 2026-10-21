package com.lazarus.wrecks;

/** Checks for the Mechanics skill numbers. Run with: gradlew testMechanic (part of check). */
public final class MechanicMathTest {
    public static void main(String[] args) {
        assert MechanicMath.moved(0, 64, 0, 3, 64, 4) == 5;
        assert MechanicMath.moved(0, 64, 0, 100, 64, 0) == 0 : "a teleport is not driving";
        assert Math.abs(MechanicMath.refund(1000, 990, 0.15) - 1.5) < 1e-9;
        assert MechanicMath.refund(990, 1000, 0.15) == 0 : "refuelling gives nothing back";
        assert MechanicMath.refund(1000, 500, 0.15) == 0 : "draining the tank gives nothing back";
        assert MechanicMath.refund(1000, 1000, 0.15) == 0;
        assert MechanicMath.repaired(30, 100, 0.10) == 10;
        assert MechanicMath.repaired(5, 100, 0.20) == 5 : "never past full health";
        assert MechanicMath.repaired(0, 100, 0.20) == 0;
        System.out.println("MechanicMathTest ok");
    }
}

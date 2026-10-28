package io.github.evolve.core;

/** Soldiers are independent of the civilian population; training runs at the end of a fastLoop. */
public record GarrisonState(int soldiers, double trainingProgress, double trainingRate) {
    public static GarrisonState empty() { return new GarrisonState(0, 0, 0); }
}

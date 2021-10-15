package taintAnalysis.pointsToAnalysis;

import taintAnalysis.pointsToAnalysis.pointsToSet.PointsToSet;
import soot.Value;

/** The type that a variable will points to at the end of a method */
enum PointsToType {
    // Default type
    Unknown,
    // The variable points to a new heap location
    New,
    // The variable points to another special variable's original location,
    // which is like substitution
    Sub
}

/**
 * This class simulates variable in java.
 */
public class Variable {
    // The value of the base reference(e.g. for a.f, its value is a)
    private Value value;
    // If this variable will point to a new location at last,
    // then this is the points-to set of this variable
    private PointsToSet pointsToSet;
    // If this variable will point to location
    // that other special variable originally pointed to,
    // then this is the reference to the special variable
    // (Note that special variable includes base object, return value and parameters)
    private Reference reference;
    // It marks whether the points-to set of this variable is allocated or substituted.
    private PointsToType pointsToType;

    /** Naive constructor */
    public Variable() {
        this.value = null;
        this.pointsToSet = null;
        this.reference = null;
        this.pointsToType = PointsToType.Unknown;
    }

    public Variable(Value value, PointsToSet pointsToSet, Reference reference) {
        this.value = value;
        this.pointsToSet = pointsToSet;
        this.reference = reference;
        this.pointsToType = PointsToType.Unknown;
    }


    public Value getValue() { return value; }

    public PointsToSet getPointsToSet() { return pointsToSet; }

    public Reference getReference() { return reference; }

    public PointsToType getPointsToType() { return pointsToType; }

    public void setValue(Value value) { this.value = value; }

    public void setPointsToSet(PointsToSet pointsToSet) { this.pointsToSet = pointsToSet; }

    public void setReference(Reference reference) { this.reference = reference; }

    public void setPointsToType(PointsToType pointsToType) { this.pointsToType = pointsToType; }
}

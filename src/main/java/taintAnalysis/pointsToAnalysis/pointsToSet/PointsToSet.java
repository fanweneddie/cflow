package taintAnalysis.pointsToAnalysis.pointsToSet;

import soot.*;

import taintAnalysis.UniqueStmt;
import taintAnalysis.pointsToAnalysis.AbstractLoc;
import taintAnalysis.Taint;

import java.util.List;
import java.util.Objects;
import java.util.Map;

/**
*  This class describes the points-to set of a reference.
*  It has two subclasses: ObjRefPointsToSet and ArrRefPointsToSet,
*  which represents the points-to set of a reference to an object and an array, respectively.
*/
public abstract class PointsToSet {

    // The location that the variable points to
    protected AbstractLoc location;

    /** Several naive constructors for PointsToSet */
    public PointsToSet() { this.location = null; }

    public PointsToSet(AbstractLoc location) { this.location = new AbstractLoc(location); }

    public AbstractLoc getLocation() { return location; }

    public void setLocation(AbstractLoc location) {
        this.location = location;
    }

    /**
     * Get the points-to set of a field in an object, or an element in an array
     * @param accessPath        the access path of a field or an element
     * @return                  the corresponding points-to set,
     *                          or null of the accessPath is broken
     */
    public PointsToSet getFieldOrElePts(List<SootField> accessPath) {
        // The points-to set of a field or an element
        PointsToSet fieldOrElePts = this;
        // Access the fields based on the accessPath
        for (SootField fieldOrEle : accessPath) {
            // Check whether fieldOrEle is null.
            // If so, it means that we are accessing an element in array
            // 1. Access a field
            if (fieldOrEle != null) {
                assert(fieldOrElePts instanceof ObjRefPointsToSet);
                fieldOrElePts = ((ObjRefPointsToSet) fieldOrElePts).getFieldPtm().get(fieldOrEle);
            }
            // 2. Access an element
            else {
                assert(fieldOrElePts instanceof ArrRefPointsToSet);
                fieldOrElePts = ((ArrRefPointsToSet) fieldOrElePts).getElePts();
            }
            // The access path is broken
            if (fieldOrElePts == null)
                return null;
        }
        return fieldOrElePts;
    }

    /**
     * Append an allocation or invoke statement into the call string of context
     * @param allocStmt     the allocation to add
     */
    public abstract void addContext(UniqueStmt allocStmt);

    /**
     * set the location type of this points-to set to a new type
     * We only do this type transform for refType.
     * @param newType       the given new type
     */
    public void setLocationType(Type newType) {
        // Special case
        if (location == null || newType == null) {
            return;
        }
        // Only for refTyoe
        if (!(newType instanceof RefType) || !(location.getType() instanceof RefType)) {
            return;
        }
        // Set type
        location.setType(newType);
    }


    /**
     * Hash code only depends on location
     */
    @Override
    public int hashCode() {
        return Objects.hash(location);
    }

    @Override
    public abstract boolean equals(Object o);
}

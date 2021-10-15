package taintAnalysis.pointsToAnalysis.pointsToSet;

import soot.*;

import taintAnalysis.UniqueStmt;
import taintAnalysis.pointsToAnalysis.AbstractLoc;
import taintAnalysis.pointsToAnalysis.Context;

import java.util.*;

import static assertion.Assert.assertNotNull;

/**
*  This class describes a points-to set of a reference to an array.
*  Beside the location set of this reference,
*  it also contains a points-to set of the element(We just set one element for approximation).
*/
public class ArrRefPointsToSet extends PointsToSet {
    // Points-to set for array element
    private PointsToSet elePts;

    /** Several naive constructors for ArrRefPointsToSet */
    public ArrRefPointsToSet() {
        super();
        this.elePts = null;
    }

    public ArrRefPointsToSet(AbstractLoc location) {
        super(location);
        this.elePts = null;
    }

    /** Copy constructor */
    public ArrRefPointsToSet(ArrRefPointsToSet arrRefPts) {
        super();
        assertNotNull(arrRefPts);
        // Copy locSet
        location = new AbstractLoc(arrRefPts.getLocation());

        // Copy element points-to set
        this.elePts = arrRefPts.getElePts();
    }

    /**
     * Create a new points-to set for a variable at a program point.
     * @param context       the context of the method
     * @param objectType    the type of the object, it must be an instance of ArrayType
     */
    public ArrRefPointsToSet(Context context, Type objectType) {
        super();
        // Precondition check
        assert(objectType instanceof ArrayType);

        // Get the locSet for the base object
        this.location = new AbstractLoc(context, objectType);
        // Initialize elePts based on the type of element
        Type eleType = ((ArrayType) objectType).getArrayElementType();
        if (eleType instanceof ArrayType || eleType instanceof RefType) {
            AbstractLoc eleLoc = new AbstractLoc(context, eleType);
            if (eleType instanceof RefType) {
                this.elePts = new ObjRefPointsToSet(eleLoc);
            } else {
                this.elePts = new ArrRefPointsToSet(eleLoc);
            }
        } else {
            this.elePts = null;
        }
    }

    public ArrRefPointsToSet(AbstractLoc location, PointsToSet elePts) {
        super(location);
        this.elePts = elePts;
    }

    public ArrRefPointsToSet(ObjRefPointsToSet objRefPointsToSet, Type objectType) {
        super(objRefPointsToSet.location);
        location.setType(objectType);
        this.elePts = null;
    }

    public AbstractLoc getLocation() { return location; }

    public PointsToSet getElePts() { return elePts; }

    public void setElePts(PointsToSet elemPts) { this.elePts = elemPts; }

    /**
     * Merge this PointsToSet with a given PointsToSet pts.
     * We just need to merge the location set.
     * If the current elePts is null, we merge that points-to set;
     * else, we don't need to change elePts for approximation.
     * Finally, the result of merge is saved in this object.
     * @param pts       the given PointsToSet, must be an ArrRefPointsToSet
     */
    public void merge(PointsToSet pts) {
        // We have nothing to merge if pts is a null
        if (pts == null) {
            return;
        }

        // We only merge pts of the same type
        assert(pts instanceof ArrRefPointsToSet);
        ArrRefPointsToSet arrRefPts = (ArrRefPointsToSet) pts;

        // We don't need to waste time merging two same objects
        if (this == arrRefPts) {
            return;
        }

        // Merge the location by intersection
        if (location != pts.getLocation()) {
            location = null;
        }

        // Merge the elemPts if the current elemPts is null
        if (this.elePts == null) {
            this.elePts = arrRefPts.getElePts();
        }
    }

    /**
     * Append an allocation statement into the context of the location
     * of current points-to set and element points-to set
     * @param allocStmt     the newly added allocation statement
     */
    public void addContext(UniqueStmt allocStmt) {
        // add context for the location if it is not null
        if (location != null) {
            location.getContext().addAllocCallStmt(allocStmt);
        }
        // add context for the points-to set of the element
        if (elePts != null) {
            if (elePts instanceof ObjRefPointsToSet) {
                ((ObjRefPointsToSet) elePts).addContext(allocStmt);
            } else {
                ((ArrRefPointsToSet) elePts).addContext(allocStmt);
            }
        }
    }

    @Override
    public String toString() {
        String str = "location:\n";
        str += location.toString();
        str += "\n";
        if (elePts.getLocation() != null) {
            str += elePts.getLocation();
            str += "\n";
        }
        return str;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (o == null || getClass() != o.getClass())
            return false;
        ArrRefPointsToSet arrRefPointsToSet = (ArrRefPointsToSet) o;
        return Objects.equals(location, arrRefPointsToSet.location)
                && Objects.equals(elePts, arrRefPointsToSet.elePts);
    }

}
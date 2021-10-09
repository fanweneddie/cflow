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
    // points-to set for array element
    private PointsToSet elePts;

    /* Several naive constructors for ArrRefPointsToSet */
    public ArrRefPointsToSet() {
        super();
        this.elePts = null;
    }

    public ArrRefPointsToSet(Set<AbstractLoc> locSet) {
        super(locSet);
        this.elePts = null;
    }

    public ArrRefPointsToSet(AbstractLoc abstractLoc) {
        super(abstractLoc);
        this.elePts = null;
    }

    /* Copy constructor */
    public ArrRefPointsToSet(ArrRefPointsToSet arrRefPts) {
        super();
        assertNotNull(arrRefPts);
        // copy locSet
        locSet.addAll(arrRefPts.getLocSet());
        // copy element points-to set
        this.elePts = arrRefPts.getElePts();
    }

    /**
     * Create a new points-to set for a variable at a program point.
     * @param method        the method that the program point is in
     * @param context       the context of the method
     * @param uniqueStmt    the statement of program point
     * @param objectType    the type of the object, it must be an instance of ArrayType
     */
    public ArrRefPointsToSet(SootMethod method, Context context, UniqueStmt uniqueStmt, Type objectType) {
        super();
        assert(objectType instanceof ArrayType);
        // get the locSet for the base object
        AbstractLoc baseLoc = new AbstractLoc(method, context, uniqueStmt, objectType);
        this.locSet.add(baseLoc);
        // initialize elePts based on the type of element
        Type eleType = ((ArrayType) objectType).getArrayElementType();
        if (eleType instanceof ArrayType || eleType instanceof RefType) {
            AbstractLoc eleLoc = new AbstractLoc(method, context, uniqueStmt, eleType);
            if (eleType instanceof RefType) {
                this.elePts = new ObjRefPointsToSet(eleLoc);
            } else {
                this.elePts = new ArrRefPointsToSet(eleLoc);
            }
        } else {
            this.elePts = null;
        }
    }

    /* A complete constructor */
    public ArrRefPointsToSet(Set<AbstractLoc> locSet, PointsToSet elePts) {
        super(locSet);
        this.elePts = elePts;
    }

    public Set<AbstractLoc> getLocSet() { return locSet; }

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
        // we have nothing to merge if pts is a null
        if (pts == null) {
            return;
        }
        // we only merge pts of the same type
        assert(pts instanceof ArrRefPointsToSet);
        ArrRefPointsToSet arrRefPts = (ArrRefPointsToSet) pts;
        // we don't need to waste time merging two same objects
        if (this == arrRefPts) {
            return;
        }
        // merge the location set
        locSet.addAll(pts.getLocSet());

        // merge the elemPts if the current elemPts is null
        if (this.elePts == null) {
            this.elePts = arrRefPts.getElePts();
        }
    }

    /**
     * Override the equals method
     * Note that we only need to check whether locSet and  equals
     */
    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (o == null || getClass() != o.getClass())
            return false;
        ArrRefPointsToSet arrRefPointsToSet = (ArrRefPointsToSet) o;
        return Objects.equals(locSet, arrRefPointsToSet.locSet)
                && Objects.equals(elePts, arrRefPointsToSet.elePts);
    }

}
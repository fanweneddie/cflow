package taintAnalysis.pointsToAnalysis.pointsToSet;

import taintAnalysis.pointsToAnalysis.AbstractLoc;

import java.util.HashSet;
import java.util.Set;

/*
*  This class describes the points-to set of a reference.
*  It has two subclasses: ObjRefPointsToSet and ArrRefPointsToSet,
*  which represents the points-to set of a reference to an object and an array, respectively.
*/
public abstract class PointsToSet {

    // location set that the variable points to
    protected final Set<AbstractLoc> locSet;

    /* Several naive constructors for PointsToSet */
    public PointsToSet() { this.locSet = new HashSet<>(); }

    public PointsToSet(Set<AbstractLoc> locSet) { this.locSet = locSet; }

    public PointsToSet(AbstractLoc abstractLoc) {
        this.locSet = new HashSet<>();
        locSet.add(abstractLoc);
    }

    public Set<AbstractLoc> getLocSet() { return locSet; }

    /**
     * Add an abstract location into the location set of current variable
     * We will not add a null abstractLoc into the set
     * @param abstractLoc       the abstract location to be added into
     * @return   true if the abstract location is successfully added into locSet
     */
    public boolean addToPts(AbstractLoc abstractLoc) {
        if (abstractLoc == null) {
            return false;
        }
        return locSet.add(abstractLoc);
    }

    /**
     * Do a strong update to the current location set
     * @param newLocSet        the new location set
     */
    public void updatePts(Set<AbstractLoc> newLocSet) {
        if (newLocSet == null) {
            return;
        }
        locSet.clear();
        locSet.addAll(newLocSet);
    }

    /**
     * Merge this PointsToSet with a given PointsToSet pts
     * We first merge the location set
     * And then we merge the PointsToSet of their field/element objects
     * Finally, the result of merge is saved in this object
     * @param pts       the given PointsToSet
     */
    public abstract void merge(PointsToSet pts);

}

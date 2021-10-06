package taintAnalysis.pointsToAnalysis;

import fj.P;
import soot.*;
import soot.util.Chain;

import taintAnalysis.UniqueStmt;

import java.util.*;

/* This class contains set of locations
    and the points-to map of fields for a variable */
public class PointsToSet {
    // location set that the variable points to
    private final Set<AbstractLoc> locSet;
    // the points-to map between variables and points-to set of the field object
    private final Map<SootField, PointsToSet> fieldPtm;
    // pointsToSet for array element
    private final List<PointsToSet> elePtsList;
    // locSet, fieldPtm and elePtsList for nullPts
    // to avoid nullReference
    private static final Set<AbstractLoc>  nullLocSet = new HashSet<>();
    private static final Map<SootField, PointsToSet> nullFieldPtm = new HashMap<>();
    private static final List<PointsToSet> nullElePtsList = new LinkedList<>();
    // Points-to set for null reference
    private static final PointsToSet nullPts = new PointsToSet(nullLocSet, nullFieldPtm, nullElePtsList);
    /**
     * Construct an empty PointsToSet
     */
    public PointsToSet() { this(new HashSet<>(), new HashMap<>(), new LinkedList<>()); }

    /**
     * Copy the elements in locSet and fieldPtm from pointsToSet
     */
    public PointsToSet(PointsToSet pointsToSet) {

        // copy locSet
        this.locSet = new HashSet<>();
        locSet.addAll(pointsToSet.getLocSet());

        // copy fieldPtm
        this.fieldPtm = new HashMap<>();
        fieldPtm.putAll(pointsToSet.getFieldPtm());

        this.elePtsList = new LinkedList<>();
        elePtsList.addAll(pointsToSet.getElePtsList());
    }

    /**
     * Construct a pointsToSet by its abstract location of base
     */
    public PointsToSet(AbstractLoc abstractLoc) {
        this.locSet = new HashSet<>();
        this.locSet.add(abstractLoc);
        this.fieldPtm = new HashMap<>();
        this.elePtsList = new LinkedList<>();
    }


    /**
     * Create a pointsToSet for a variable at a program point
     * @param method
     * @param context
     * @param uniqueStmt
     * @param objectType
     */
    public PointsToSet(SootMethod method, Context context, UniqueStmt uniqueStmt, Type objectType) {
        // get the locSet for the base object
        AbstractLoc baseLoc = new AbstractLoc(method, context, uniqueStmt, objectType);
        this.locSet = new HashSet<>();
        this.locSet.add(baseLoc);
        // get PointsToMap for fields
        this.fieldPtm = new HashMap<>();
        assert(objectType instanceof RefType || objectType instanceof ArrayType);
        // for reference type, we set its field PointsToSet
        if (objectType instanceof RefType) {
            RefType refType = (RefType) objectType;
            Chain<SootField> fields = refType.getSootClass().getFields();
            for (SootField field : fields) {
                if (field.getType() instanceof RefType) {
                    AbstractLoc fieldLoc = new AbstractLoc(method, context, uniqueStmt, field.getType());
                    PointsToSet fieldPts = new PointsToSet(fieldLoc);
                    this.fieldPtm.put(field, fieldPts);
                }
            }
        }
        // init elePtsList as empty
        this.elePtsList = new LinkedList<>();
    }

    /**
     * A complete constructor
     */
    public PointsToSet(Set<AbstractLoc> locSet,
                       Map<SootField, PointsToSet> fieldPtm, List<PointsToSet> elePtsList) {
        this.locSet = locSet;
        this.fieldPtm = fieldPtm;
        this.elePtsList = elePtsList;
    }

    public Set<AbstractLoc> getLocSet() { return locSet; }

    public Map<SootField, PointsToSet> getFieldPtm() { return fieldPtm; }

    public List<PointsToSet> getElePtsList() { return elePtsList; }

    public static PointsToSet getNullPts() { return nullPts; }

    /**
     * Add an abstract location into the location set of current variable
     * @param abstractLoc       the abstract location to be added into
     * @return   true if the abstract location is successfully added into locSet
     */
    public boolean addToPts(AbstractLoc abstractLoc) {
        return locSet.add(abstractLoc);
    }

    /**
     * Do a strong update to the current location set
     * @param newLocSet        the new location set
     */
    public void updatePts(Set<AbstractLoc> newLocSet) {
        locSet.clear();
        locSet.addAll(newLocSet);
    }

    /**
     * Merge this PointsToSet with a given PointsToSet pts
     * We first merge the location set
     * And then we merge the PointsToSet of their field objects
     * Finally, the result of merge is saved in this object
     * @param pts       the given PointsToSet
     */
    public void merge(PointsToSet pts) {

        if (this == pts) {
            return;
        }

        // merge the location set
        locSet.addAll(pts.getLocSet());
        // recursively merge PointsToSet of fields
        for (Map.Entry<SootField, PointsToSet> entry : pts.getFieldPtm().entrySet()) {
            SootField field = entry.getKey();
            PointsToSet fieldPts = entry.getValue();
            if (fieldPtm.containsKey(field)) {
                fieldPtm.get(field).merge(fieldPts);
            } else {
                PointsToSet newPts = new PointsToSet(fieldPts);
                fieldPtm.put(field, newPts);
            }
        }
        // merge array ref
        //elePtsList.addAll(pts.getElePtsList());
   }

    /**
     * Add the pointsToSet of a field into this pointsToSet
     * @param field         the field reference
     * @param fieldPts      the pointsToSet of the field
     */
    public void addField(SootField field, PointsToSet fieldPts) {
        fieldPtm.put(field, fieldPts);
    }

    /**
     * Get the pointsToSet of a field
     * @param field     the field in this object
     * @return          the pointsToSet of that field,
     *                  or null if that field doesn't exist
     */
    public PointsToSet getFieldPts(SootField field) {
        return fieldPtm.get(field);
    }

    /**
     * Override the equals method
     * Note that we only need to check whether locSet and fieldPtm equals
     */
    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (o == null || getClass() != o.getClass())
            return false;
        PointsToSet pointsToSet = (PointsToSet) o;
        return Objects.equals(locSet, pointsToSet.locSet)
                && Objects.equals(fieldPtm, pointsToSet.fieldPtm);
    }
}

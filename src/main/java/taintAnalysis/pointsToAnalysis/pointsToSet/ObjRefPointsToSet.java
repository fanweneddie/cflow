package taintAnalysis.pointsToAnalysis.pointsToSet;

import soot.*;
import soot.util.Chain;

import taintAnalysis.UniqueStmt;
import taintAnalysis.pointsToAnalysis.AbstractLoc;
import taintAnalysis.pointsToAnalysis.Context;

import java.util.*;

import static assertion.Assert.assertNotNull;

/*
* This class describes a points-to set of a reference to an object.
* Beside the location set of this reference,
* it also contains the points-to set of each reference field.
*/
public class ObjRefPointsToSet extends PointsToSet {

    // the map from variables to points-to set of the field object
    private final Map<SootField, PointsToSet> fieldPtm;

    /* Several naive constructors for ObjRefPointsToSet */
    public ObjRefPointsToSet() {
        super();
        this.fieldPtm = new HashMap<>();
    }

    public ObjRefPointsToSet(Set<AbstractLoc> locSet) {
        super(locSet);
        this.fieldPtm = new HashMap<>();
    }

    public ObjRefPointsToSet(AbstractLoc abstractLoc) {
        super(abstractLoc);
        this.fieldPtm = new HashMap<>();
    }

    /* Copy constructor */
    public ObjRefPointsToSet(ObjRefPointsToSet objRefPts) {
        super();
        assertNotNull(objRefPts);
        // copy locSet
        locSet.addAll(objRefPts.getLocSet());

        // copy fieldPtm
        this.fieldPtm = new HashMap<>();
        fieldPtm.putAll(objRefPts.getFieldPtm());
    }

    /**
     * Create a new points-to set for a variable at a program point
     * @param method        the method that the program point is in
     * @param context       the context of the method
     * @param uniqueStmt    the statement of program point
     * @param objectType    the type of the object, it must be an instance of RefType
     */
    public ObjRefPointsToSet(SootMethod method, Context context, UniqueStmt uniqueStmt, Type objectType) {
        super();
        // precondition check
        assert(objectType instanceof RefType);

        // initialize the locSet for the base object and points-to map for fields
        AbstractLoc baseLoc = new AbstractLoc(method, context, uniqueStmt, objectType);
        this.locSet.add(baseLoc);
        this.fieldPtm = new HashMap<>();

        // for a field, if it is a reference type, we set its points-to set
        // also, for fields of each field, we set their points-to set as nullObjRefPts as an approximation
        RefType refType = (RefType) objectType;
        Chain<SootField> fields = refType.getSootClass().getFields();
        for (SootField field : fields) {
            if (field.getType() instanceof RefType || field.getType() instanceof ArrayType) {
                // construct the points-to set of a field based on its type
                AbstractLoc fieldLoc = new AbstractLoc(method, context, uniqueStmt, field.getType());
                PointsToSet fieldPts;
                if (field.getType() instanceof RefType) {
                    fieldPts = new ObjRefPointsToSet(fieldLoc);
                } else {
                    fieldPts = new ArrRefPointsToSet(fieldLoc);
                }
                this.fieldPtm.put(field, fieldPts);
            }
        }
    }

    /* A complete constructor */
    public ObjRefPointsToSet(Set<AbstractLoc> locSet, Map<SootField, PointsToSet> fieldPtm) {
        super(locSet);
        this.fieldPtm = fieldPtm;
    }

    public Map<SootField, PointsToSet> getFieldPtm() { return fieldPtm; }

    /**
     * Merge this ObjRefPointsToSet with a given ObjRefPointsToSet
     * We first merge the location set
     * And then we merge the PointsToSet of their field objects
     * Finally, the result of merge is saved in this object
     * @param pts           the given PointsToSet, must be an ObjRefPointsToSet
     */
    public void merge(PointsToSet pts) {
        // we have nothing to merge if pts is a null
        if (pts == null) {
            return;
        }
        // we only merge pts of the same type
        assert(pts instanceof ObjRefPointsToSet);
        ObjRefPointsToSet objRefPts = (ObjRefPointsToSet) pts;
        // we don't need to waste time merging two same objects
        if (this == objRefPts) {
            return;
        }
        // merge the location set
        locSet.addAll(objRefPts.getLocSet());

        // recursively merge PointsToSet of fields
        for (Map.Entry<SootField, PointsToSet> entry : objRefPts.getFieldPtm().entrySet()) {
            SootField field = entry.getKey();
            PointsToSet fieldPts = entry.getValue();
            // we should assume that the class of two points-to set equals
            if (fieldPtm.containsKey(field)) {
                assert (fieldPtm.get(field).getClass() == fieldPts.getClass());
                if (fieldPtm.get(field) == null) {
                    fieldPtm.put(field, fieldPts);
                } else {
                    fieldPtm.get(field).merge(fieldPts);
                }
            } else {
                PointsToSet newPts;
                if (fieldPts instanceof ObjRefPointsToSet) {
                    newPts = new ObjRefPointsToSet((ObjRefPointsToSet)fieldPts);
                } else if (fieldPts instanceof ArrRefPointsToSet) {
                    newPts = new ArrRefPointsToSet((ArrRefPointsToSet)fieldPts);
                } else {
                    newPts = null;
                }
                fieldPtm.put(field, newPts);
            }
        }
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
     * Override the equals method.
     * Note that we only need to check whether locSet equals he
     */
    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (o == null || getClass() != o.getClass())
            return false;
        PointsToSet pointsToSet = (PointsToSet) o;
        return Objects.equals(locSet, pointsToSet.locSet);
    }

}
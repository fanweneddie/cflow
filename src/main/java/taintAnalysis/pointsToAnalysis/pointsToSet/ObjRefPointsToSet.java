package taintAnalysis.pointsToAnalysis.pointsToSet;

import soot.*;
import soot.util.Chain;

import taintAnalysis.UniqueStmt;
import taintAnalysis.pointsToAnalysis.AbstractLoc;
import taintAnalysis.pointsToAnalysis.Context;

import java.util.*;

import static assertion.Assert.assertNotNull;

/**
* This class describes a points-to set of a reference to an object.
* Beside the location set of this reference,
* it also contains the points-to set of each reference field.
*/
public class ObjRefPointsToSet extends PointsToSet {

    // The map from variables to points-to set of the field object
    private final Map<SootField, PointsToSet> fieldPtm;

    /** Several naive constructors for ObjRefPointsToSet */
    public ObjRefPointsToSet() {
        super();
        this.fieldPtm = new HashMap<>();
    }

    public ObjRefPointsToSet(AbstractLoc location) {
        super(location);
        this.fieldPtm = new HashMap<>();
    }

    /** Copy constructor */
    public ObjRefPointsToSet(ObjRefPointsToSet objRefPts) {
        super();
        assertNotNull(objRefPts);
        // copy location
        location = new AbstractLoc(objRefPts.getLocation());

        // copy fieldPtm
        this.fieldPtm = new HashMap<>();
        fieldPtm.putAll(objRefPts.getFieldPtm());
    }

    /**
     * Create a new points-to set for a variable at a program point.
     * @param context       the context of the method
     * @param objectType    the type of the object, it must be an instance of RefType
     */
    public ObjRefPointsToSet(Context context, Type objectType) {
        super();
        // Precondition check
        assert(objectType instanceof RefType);

        // Initialize the location for the base object and points-to map for fields
        this.location = new AbstractLoc(context, objectType);
        this.fieldPtm = new HashMap<>();

        // For each field, if it is a reference type, we set its points-to set.
        // Also, for fields of each field, we set their points-to set as nullObjRefPts as an approximation
        RefType refType = (RefType) objectType;
        Chain<SootField> fields = refType.getSootClass().getFields();
        for (SootField field : fields) {
            if (field.getType() instanceof RefType || field.getType() instanceof ArrayType) {
                // Construct the points-to set of a field based on its type
                AbstractLoc fieldLoc = new AbstractLoc(context, field.getType());
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

    /** A complete constructor */
    public ObjRefPointsToSet(AbstractLoc location, Map<SootField, PointsToSet> fieldPtm) {
        super(location);
        this.fieldPtm = fieldPtm;
    }

    public Map<SootField, PointsToSet> getFieldPtm() { return fieldPtm; }

    /**
     * Merge this ObjRefPointsToSet with a given ObjRefPointsToSet.
     * We first merge the location set.
     * Then we merge the points-to set of their field objects.
     * Finally, the result of merge is saved in this object.
     * @param pts           the given points-to set, must be an ObjRefPointsToSet
     */
    public void merge(PointsToSet pts) {
        // We have nothing to merge if pts is a null
        if (pts == null) {
            return;
        }

        // We only merge pts of the same type
        assert(pts instanceof ObjRefPointsToSet);
        ObjRefPointsToSet objRefPts = (ObjRefPointsToSet) pts;

        // We don't need to waste time merging two same objects
        if (this == objRefPts) {
            return;
        }

        // Merge the location by intersection
        if (location != pts.getLocation()) {
            location = null;
        }

        // Recursively merge PointsToSet of fields
        for (Map.Entry<SootField, PointsToSet> entry : objRefPts.getFieldPtm().entrySet()) {
            // Get each field and its points-to set in objRefPts
            SootField field = entry.getKey();
            PointsToSet fieldPts = entry.getValue();
            // Check whether the field is recorded in this fieldPtm
            // 1. Merge the field points-to set
            if (fieldPtm.containsKey(field)) {
                assert (fieldPtm.get(field).getClass() == fieldPts.getClass());
                if (fieldPtm.get(field) == null) {
                    fieldPtm.put(field, fieldPts);
                } else {
                    fieldPtm.get(field).merge(fieldPts);
                }
            }
            // 2. Add the points-to set for the field
            else {
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
     * Append an allocation statement into the context of the location
     * of current points-to set and field points-to set
     * @param allocStmt     the newly added allocation statement
     */
    public void addContext(UniqueStmt allocStmt) {
        // add context for the location if it is not null
        if (location != null) {
            location.getContext().addAllocCallStmt(allocStmt);
        }
        // add context for the points-to set of each field
        for (Map.Entry<SootField, PointsToSet> entry : fieldPtm.entrySet()) {
            PointsToSet fieldPts = entry.getValue();
            if (fieldPts != null) {
                if(fieldPts instanceof ObjRefPointsToSet) {
                    ((ObjRefPointsToSet) fieldPts).addContext(allocStmt);
                } else {
                    ((ArrRefPointsToSet) fieldPts).addContext(allocStmt);
                }
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

    @Override
    public String toString() {
        String str = "location:\n";
        str += location.toString();
        str += "\n";
        for (Map.Entry<SootField, PointsToSet> entry : fieldPtm.entrySet()) {
            str += entry.getKey();
            str += " ";
            if (entry.getValue() != null)
                str += entry.getValue().getLocation().toString();
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
        ObjRefPointsToSet objRefPointsToSet = (ObjRefPointsToSet) o;
        return Objects.equals(location, objRefPointsToSet.location)
                && Objects.equals(fieldPtm, objRefPointsToSet.fieldPtm);
    }
}
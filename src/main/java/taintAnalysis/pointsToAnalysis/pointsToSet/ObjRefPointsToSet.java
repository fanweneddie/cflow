package taintAnalysis.pointsToAnalysis.pointsToSet;

import soot.*;
import soot.util.Chain;

import taintAnalysis.Taint;
import taintAnalysis.UniqueStmt;
import taintAnalysis.pointsToAnalysis.AbstractLoc;
import taintAnalysis.pointsToAnalysis.Context;

import java.util.*;

/**
* This class describes a points-to set of a reference to an object.
* Beside the location set of this reference,
* it also contains the points-to set of each reference field.
*/
public class ObjRefPointsToSet extends PointsToSet {

    // The map from variables to points-to set of the field object
    private final Map<SootField, PointsToSet> fieldPtm;
    // The set of taints that taint this points-to set or the field of this points-to set
    // It is modified in taint analysis
    protected final Map<SootField, Taint> taints;

    /** Several naive constructors for ObjRefPointsToSet */
    public ObjRefPointsToSet() {
        super();
        this.fieldPtm = new HashMap<>();
        this.taints = new HashMap<>();
    }

    public ObjRefPointsToSet(AbstractLoc location) {
        super(location);
        this.fieldPtm = new HashMap<>();
        this.taints = new HashMap<>();
    }

    /** Copy constructor.
     * We assert that objRefPts is not null
     */
    public ObjRefPointsToSet(ObjRefPointsToSet objRefPts) {
        super();
        // copy location
        if (objRefPts.getLocation() != null) {
            location = new AbstractLoc(objRefPts.getLocation());
        } else {
            location = null;
        }

        // copy fieldPtm
        this.fieldPtm = new HashMap<>();
        fieldPtm.putAll(objRefPts.getFieldPtm());
        // No need to copy taints since it is empty in points-to analysis
        this.taints = new HashMap<>();
    }

    /**
     * Create a new points-to set for a variable at a program point.
     * @param context       the context of the method
     * @param objectType    the type of the object, it must be an instance of RefType
     */
    public ObjRefPointsToSet(Context context, Type objectType) {
        super();

        // Initialize the fields
        this.location = new AbstractLoc(context, objectType);
        this.fieldPtm = new HashMap<>();
        this.taints = new HashMap<>();

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
        this.taints = new HashMap<>();
    }

    public Map<SootField, PointsToSet> getFieldPtm() { return fieldPtm; }

    public Map<SootField, Taint> getTaintSet() { return taints; }

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
            if (fieldPts != null && fieldPts != this) {
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

    /**
     * Add a taint into taints.
     * Before calling this method,
     * we should assure that the value of taint must be a instanceRef,
     * and the base value must points to the base of this points-to set
     * @param taint     the taint to be added
     */
    public void addTaint(Taint taint) {
        // Precondition check
        assert(taint.getPlainValue() != null);
        assert(taint.getField() != null);

        // Note that one field can only have one corresponding taint
        taints.put(taint.getField(), taint);
    }

    /**
     * Remove a taint on taints.
     * Before calling this method,
     * we should assure that the value of taint must be a instanceRef,
     * and the base value must points to the base of this points-to set
     * @param taint     the taint to be added
     */
    public void removeTaint(Taint taint) {
        // Precondition check
        assert(taint.getPlainValue() != null);
        assert(taint.getField() != null);

        // Note that one field can only have one corresponding taint
        taints.remove(taint.getField());
    }

    /**
     * Get a taint on a field points-to set.
     * @param field     the field of the potential taint
     */
    public Taint getTaint(SootField field) {
        // Note that one field can only have one corresponding taint
        return taints.get(field);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (o == null || getClass() != o.getClass())
            return false;
        ObjRefPointsToSet objRefPointsToSet = (ObjRefPointsToSet) o;
        return Objects.equals(location, objRefPointsToSet.location);
    }
}
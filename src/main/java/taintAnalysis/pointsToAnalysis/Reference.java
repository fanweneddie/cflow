package taintAnalysis.pointsToAnalysis;

import soot.SootField;
import soot.SootMethod;
import soot.Value;
import taintAnalysis.UniqueStmt;

import java.util.LinkedList;
import java.util.List;

/**
 * This class simulates the normal reference of object/array
 */
public class Reference {
    // Index of the base of reference in specialList
    // 0 is for this object, 1 is for return value, i + 2 is for the i th parameter,
    // and others for local variables.
    private final int baseIndex;
    // The access path of the referenced object
    // accessPath is null if this reference is a null reference
    private final List<SootField> accessPath;

    /** Naive constructors */
    public Reference(int baseIndex) {
        this.baseIndex = baseIndex;
        if (baseIndex == -1) {
            this.accessPath = null;
        } else {
            this.accessPath = new LinkedList<>();
        }
    }

    public Reference(int baseIndex, List<SootField> accessPath) {
        this.baseIndex = baseIndex;
        this.accessPath = new LinkedList<>(accessPath);
    }

    /** Copy constructor*/
    public Reference(Reference reference) {
        this.baseIndex = reference.baseIndex;;
        this.accessPath = new LinkedList<>(reference.accessPath);
    }

    public int getBaseIndex() { return baseIndex; }

    public List<SootField> getAccessPath() { return accessPath; }

    /** Check whether the reference is a null reference */
    public boolean isNull() { return baseIndex == -1; }

    public void setAccessPath(List<SootField> accessPath) {
        this.accessPath.clear();
        this.accessPath.addAll(accessPath);
    }

    /** Append a field in the access path */
    public boolean addField(SootField field) {
        return accessPath.add(field);
    }

}

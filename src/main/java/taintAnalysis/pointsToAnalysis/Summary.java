package taintAnalysis.pointsToAnalysis;

import soot.Value;
import taintAnalysis.pointsToAnalysis.pointsToSet.PointsToSet;
import java.util.*;

/**
 * This class stores the summary of substitution and new allocation of
 * special variables(base object, return values and parameters) of a method
 */
public class Summary {
    // Number of special variables, which equals to number of parameters + 2
    private final int specialVarNum;
    // It stores the value, points-to set and final reference
    // of this object, return value, parameters, and local variables
    // Index: 0 for this object, 1 for return value, i + 2 for parameter i,
    //        others for local variables
    // For example, in method foo() below
    // ------------------------------------
    // String foo(String a, String b) {
    //      String s1 = new String();
    //      String s2 = new String();
    //      String r = s1 + s2 + a + b;
    //      return r;
    // }
    // ------------------------------------
    // The specialVarList is like {o,r,a,b,s1,s2}
    // and specialVarNum is 4.
    private final List<Variable> specialVarList;

    /** Naive constructor */
    public Summary(int specialVarNum) {
        this.specialVarNum = specialVarNum;
        this.specialVarList = new LinkedList<>();
        for(int i = 0; i < specialVarNum; ++i) {
            this.specialVarList.add(new Variable());
        }
    }

    public int getSpecialVarNum() { return specialVarNum; }

    public List<Variable> getSpecialVarList() { return specialVarList; }

    public int getVarNum() { return specialVarList.size(); }

    /** Get the value of the index th variable in specialVarList */
    public Value getValue(int index) {
        assert(index >= 0 && index < specialVarList.size());
        if (specialVarList.get(index) == null) {
            return null;
        } else {
            return specialVarList.get(index).getValue();
        }
    }

    /** Get the points-to set of the index th variable in specialVarList */
    public PointsToSet getPointsToSet(int index) {
        assert(index >= 0 && index < specialVarList.size());
        if (specialVarList.get(index) == null) {
            return null;
        } else {
            return specialVarList.get(index).getPointsToSet();
        }
    }

    /** Get the reference of the index th variable in specialVarList */
    public Reference getReference(int index) {
        assert(index >= 0 && index < specialVarList.size());
        if (specialVarList.get(index) == null) {
            return null;
        } else {
            return specialVarList.get(index).getReference();
        }
    }

    /** Get the points-to type of the index th variable in specialVarList */
    public PointsToType getPointsToType(int index) {
        assert(index >= 0 && index < specialVarList.size());
        if (specialVarList.get(index) == null) {
            return null;
        } else {
            return specialVarList.get(index).getPointsToType();
        }
    }

    /**
     * Get the index in specialVarList of variable with value
     * @param value     the value of a variable
     * @return          the index in specialVarList
     *                  -1 if the value is not in speicalVarList
     */
    public int getIndexOfValue(Value value) {
        int size = specialVarList.size();
        for (int i = 0; i < size; ++i) {
            if (getValue(i) == value) {
                return i;
            }
        }
        return -1;
    }

    /** Set the value of the index th variable in specialVarList */
    public void setValue(int index, Value value) {
        if (specialVarList.get(index) == null) {
            specialVarList.set(index, new Variable());
        }
        specialVarList.get(index).setValue(value);
    }

    /** Set the points-to set of the index th variable in specialVarList */
    public void setPointsToSet(int index, PointsToSet pts) {
        if (specialVarList.get(index) == null) {
            specialVarList.set(index, new Variable());
        }
        specialVarList.get(index).setPointsToSet(pts);
    }

    /** Set the reference of the index th variable in specialVarList */
    public void setReference(int index, Reference ref) {
        if (specialVarList.get(index) == null) {
            specialVarList.set(index, new Variable());
        }
        specialVarList.get(index).setReference(ref);
    }

    /** Set the points-to type of the index th variable in specialVarList */
    public void setPointsToType(int index, PointsToType pointsToType) {
        if (specialVarList.get(index) == null) {
            specialVarList.set(index, new Variable());
        }
        specialVarList.get(index).setPointsToType(pointsToType);
    }

    /** Append a variable into the specialList */
    public boolean addVariable(Variable variable) {
        return specialVarList.add(variable);
    }

    /**
     * Append an empty variable into the specialList
     * @return       the index of the new added variable
     */
    public int addEmptyVariable() {
        Variable newVar = new Variable(null, null, null);
        specialVarList.add(newVar);
        return specialVarList.size() - 1;
    }

}
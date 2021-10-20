package taintAnalysis;

import soot.Body;
import soot.SootMethod;
import soot.Value;
import soot.ValueBox;
import soot.jimple.InstanceInvokeExpr;
import soot.jimple.InvokeExpr;
import soot.jimple.internal.InvokeExprBox;
import soot.jimple.internal.JInstanceFieldRef;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * How the field reference is used in a method
 */
enum FieldUseType {
    // Must be used
    Must,
    // Never be used
    Never,
    // May be used
    May
}

/**
 * This class checks the use of a field in a method
 */
public class FieldUseChecker {
    // The field use information of all methods
    private final Map<SootMethod, Map<JInstanceFieldRef, FieldUseType>> globalUseInfo;
    // The maximal depth of method analyzed
    private final int maxDepth = 2;

    public FieldUseChecker() {
        this.globalUseInfo = new HashMap<>();
    }

    /**
     * Check the use of a field reference in a method
     * @param ref           the given field reference(at caller side)
     * @param method        the given callee method
     * @param index         the index of the field reference in callee
     *                      -1 means base object, others mean parameter
     * @param depth         the depth of the given method
     * @return              the use type of ref
     */
    public FieldUseType checkUse(JInstanceFieldRef ref, SootMethod method, int index, int depth) {

        // Return if we exceed the max depth
        if (depth > maxDepth) {
            return FieldUseType.May;
        }

        // Get the use info of ref in current method
        Map<JInstanceFieldRef, FieldUseType> currUseInfo = new HashMap<>();
        if (!globalUseInfo.containsKey(method)) {
            globalUseInfo.put(method, currUseInfo);
        } else {
            currUseInfo = globalUseInfo.get(method);
        }

        // Get the body of method
        Body body = null;
        if (method.hasActiveBody()) {
            body = method.getActiveBody();
        }

        // We can't analyze the body of the method
        if (body == null) {
            currUseInfo.put(ref, FieldUseType.May);
            return FieldUseType.May;
        }

        // callerValue can be base or parameter(at caller side),
        // and it is a simple ref.
        Value calleeValue;
        if (index == -1) {
            calleeValue = body.getThisLocal();
        } else {
            calleeValue = body.getParameterLocal(index);
        }
        // Generate the field reference in callee and record it
        JInstanceFieldRef calleeRef = new JInstanceFieldRef(calleeValue, ref.getFieldRef());

        // Check whether use info of ref is recorded in summary
        // If so, return that result
        if (currUseInfo.containsKey(ref)) {
            return currUseInfo.get(ref);
        }

        // The init field use type is Never
        FieldUseType useType = FieldUseType.Never;
        // Marks whether the field may appear
        boolean mayAppearFlag = false;

        // Leverage use boxes to do a flow-insensitive intra-procedural analysis
        List<ValueBox> useBoxes = body.getUseBoxes();
        for (ValueBox valueBox : useBoxes) {
            // The used value in sink method
            // Check whether fieldUseInfo has that value.
            // If so, mark it as true.
            Value useValue = valueBox.getValue();
            // 1. For instance reference,
            // We check whether that reference equals to calleeRef
            if (useValue instanceof JInstanceFieldRef) {
                JInstanceFieldRef useInstanceRef = (JInstanceFieldRef) useValue;
                if (useInstanceRef.equivTo(calleeRef)) {
                    useType = FieldUseType.Must;
                }
            }
            // 2. For an invoke expression,
            // If its base object or parameter equals the base of calleeRef,
            // we search recursively
            else if (valueBox instanceof InvokeExprBox) {
                InvokeExpr invokeExpr = (InvokeExpr) ((InvokeExprBox) valueBox).getValue();
                Value invokeBase = null;
                if (invokeExpr instanceof InstanceInvokeExpr) {
                    invokeBase = ((InstanceInvokeExpr) invokeExpr).getBase();
                }
                SootMethod invokeMethod = invokeExpr.getMethod();

                // Check the usage of base recursively
                if (calleeRef.getBase().equivTo(invokeBase)) {
                    FieldUseType tempUseType = checkUse(calleeRef, invokeMethod, -1, depth + 1);
                    if (tempUseType == FieldUseType.Must) {
                        useType = FieldUseType.Must;
                    } else if (tempUseType == FieldUseType.May) {
                        mayAppearFlag = true;
                    }
                }
                // Check the usage of parameters recursively
                int paramNum = invokeExpr.getArgCount();
                for (int i = 0; i < paramNum; ++i) {
                    Value invokeParam = invokeExpr.getArg(i);
                    if (calleeRef.getBase().equivTo(invokeParam)) {
                        currUseInfo.put(ref, FieldUseType.May);
                        FieldUseType tempUseType = checkUse(calleeRef, invokeMethod, i, depth + 1);
                        if (tempUseType == FieldUseType.Must) {
                            useType = FieldUseType.Must;
                        } else if (tempUseType == FieldUseType.May) {
                            mayAppearFlag = true;
                        }
                    }
                }
            }
            // For Must type, just record it and break
            if (useType == FieldUseType.Must) {
                currUseInfo.put(ref, FieldUseType.Must);
                break;
            }
        }
        // Check whether the field may be used
        if (useType != FieldUseType.Must) {
            if (mayAppearFlag) {
                currUseInfo.put(ref, FieldUseType.May);
            } else {
                currUseInfo.put(ref, FieldUseType.Never);
            }
        }
        return currUseInfo.get(ref);
    }

    public FieldUseType checkUse(JInstanceFieldRef ref, SootMethod method, int index) {
        return checkUse(ref, method, index, 1);
    }

    public Map<SootMethod, Map<JInstanceFieldRef, FieldUseType>> getGlobalUseInfo() {
        return globalUseInfo;
    }

    public int getMaxDepth() {
        return maxDepth;
    }
}

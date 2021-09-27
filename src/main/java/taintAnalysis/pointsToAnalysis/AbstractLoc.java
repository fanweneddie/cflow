package taintAnalysis.pointsToAnalysis;

import soot.Type;
import soot.SootMethod;
import soot.Value;

import taintAnalysis.UniqueStmt;


/**
 * The abstract location on heap,
 * which is distinguished by allocation site and context
 */
public class AbstractLoc {
    // the method that the allocation site is in
    private final SootMethod method;
    // the context of method
    private final Context context;
    // the invoke statement of allocation.
    private final UniqueStmt allocStmt;
    // the type of object
    private final Type type;
    // whether the AbstractLoc is in constant pool
    private final boolean isConst;
    // if the AbstractLoc is in constant pool, constValue is its value
    private final Value constValue;
    // an unknown abstract location for initialization
    private static final AbstractLoc unknown = new AbstractLoc(null, null, null, null);

    /**
     * Constructor: construct an abstract location that is not in constant pool
     * it is assumed that allocStmt is an init statement
     */
    public AbstractLoc(SootMethod method, Context context, UniqueStmt allocStmt, Type type) {
        this.method = method;
        this.context = context;
        this.allocStmt = allocStmt;
        this.type = type;
        this.isConst = false;
        this.constValue = null;
    }

    public AbstractLoc(Value constValue) {
        this.method = null;
        this.context = null;
        this.allocStmt = null;
        this.type = constValue.getType();
        this.isConst = true;
        this.constValue = constValue;
    }

    public SootMethod getMethod() { return method; }

    public Context getContext() { return context; }

    public UniqueStmt getAllocStmt() { return allocStmt; }

    public Type getType() { return type; }

    public boolean getIsConst() { return isConst; }

    public Value getConstValue() { return constValue; }

    public static AbstractLoc getKnown() { return unknown; }

}

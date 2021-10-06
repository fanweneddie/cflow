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
    // an unknown abstract location for initialization
    private static final AbstractLoc unknown = new AbstractLoc(null, null, null, null);

    /**
     * Constructor: construct an abstract location that is not in constant pool
     * it is assumed that allocStmt is a call statement to init()
     */
    public AbstractLoc(SootMethod method, Context context, UniqueStmt allocStmt, Type type) {
        this.method = method;
        this.context = context;
        this.allocStmt = allocStmt;
        this.type = type;
    }

    public SootMethod getMethod() { return method; }

    public Context getContext() { return context; }

    public UniqueStmt getAllocStmt() { return allocStmt; }

    public Type getType() { return type; }

    public static AbstractLoc getKnown() { return unknown; }

}

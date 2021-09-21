package taintAnalysis.pointsToAnalysis;

import soot.Type;
import soot.SootMethod;

import taintAnalysis.UniqueStmt;

import java.util.Queue;

/**
 * The abstract location on heap,
 * which is distinguished by allocation site and context
 */
public class AbstractLoc {
    // the method that the allocation site is in
    private final SootMethod method;
    // the maximal number of nearest call statements
    // or that is k in k-limiting callString
    private final int callStringLen;
    // the string of call statements as a context
    private final Queue<UniqueStmt> callString;
    // the invoke statement of allocation.
    private final UniqueStmt allocStmt;
    // the type of object
    private final Type type;

    /**
     * Constructor: it is assumed that allocStmt is an init statement
     */
    public AbstractLoc(SootMethod method, int callStringLen, Queue<UniqueStmt> callString, UniqueStmt allocStmt, Type type) {
        this.method = method;
        this.callStringLen = callStringLen;
        this.callString = callString;
        this.allocStmt = allocStmt;
        this.type = type;
    }

    public SootMethod getMethod() { return method; }

    public int getCallStringLen() { return callStringLen; }

    public Queue<UniqueStmt> getCallString() { return callString; }

    public UniqueStmt getAllocStmt() { return allocStmt; }

    public Type getType() { return type; }

}

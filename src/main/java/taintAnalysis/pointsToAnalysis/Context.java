package taintAnalysis.pointsToAnalysis;

import taintAnalysis.UniqueStmt;
import taintAnalysis.pointsToAnalysis.pointsToSet.PointsToSet;

import java.util.*;

/**
 * The context of a method,
 * which contains callString and The points-to set of this object, return value and arguments
 */
public class Context {
    // the maximal number of the nearest call statements
    // or that is k in k-limiting callString
    private final int callStringLen;
    // the string of call statements as a context
    private final Queue<UniqueStmt> callString;
    // the list of points-to map of this object and arguments
    // its size = number of arguments + 2
    private final List<PointsToSet> summary;

    /**
     * Construct the context by passing callString and its max length
     * We need to check whether the given callString is within the max length,
     * If not, we should use k-limiting way to approximate callString
     * @param callStringLen
     * @param callString
     * @param argNum        number of arguments in the method that this context is in.
     */
    public Context(int callStringLen, Queue<UniqueStmt> callString, int argNum) {
        this.callStringLen = callStringLen;
        // approximate callString with given callStringLen
        this.callString = approximateCallString(callString);
        // initialize summary with null
        int summarySize = argNum + 2;
        this.summary = new ArrayList<>(summarySize);
        for( int i = 0; i < summarySize; ++i) {
            summary.add(null);
        }
    }

    /**
     * Make the k-limiting of the callString as a context
     * if the length of callString is greater than callStringLen,
     * we only keep the callStringLen nearest call sites
     * @param callString        the input queue of call sites
     * @return                  the callStringLen-approximate call sites
     */
    private Queue<UniqueStmt> approximateCallString(Queue<UniqueStmt> callString) {
        // get a clone of callString
        Queue<UniqueStmt> newCallString = new LinkedList<>();
        newCallString.addAll(callString);

        // remove call site at the tail of the queue
        // until there are only callStringLen call sites
        while(newCallString.size() > callStringLen) {
            newCallString.remove();
        }
        return newCallString;
    }

    /**
     * Set the points-to map of an object(this/return value/argument)
     * We use strong update to replace.
     * @param index             the index of the object in summary
     *                          by default, summary[0] is for this object,
     *                          summary[1] is for return value
     *                          summary[i+2] is for the ith argument
     * @param pointsToSet      the points-to set of an object
     */
    public void setSummary(int index, PointsToSet pointsToSet) {
        summary.set(index, pointsToSet);
    }

    public int getCallStringLen() { return callStringLen; }

    public Queue<UniqueStmt> getCallString() { return callString; }

    public List<PointsToSet> getSummary() { return summary; }

}
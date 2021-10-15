package taintAnalysis.pointsToAnalysis;

import taintAnalysis.UniqueStmt;

import java.util.*;

/**
 * The context of a statement,
 * which contains a k-limiting call string
 */
public class Context {
    // The maximal number of the nearest call statements
    // or that is k in k-limiting callString
    private final int maxCallStringLen;
    // The string of call statements,
    // where the first statement is the nearest statement,
    // and the later statements are reversed call statements.
    // e.g. for object o of program below:
    // -----------------------------
    // int main() { s1: foo(); }
    // void foo() { s2: bar(); }
    // void bar() { s3: T o = new T(); }
    // ------------------------------
    // In method bar(), o's context is [s3],
    // In method foo(), o's context is [s3,s2],
    // In method main(), o's context is [s3,s2,s1]
    private final List<UniqueStmt> callString;

    /** Naive constructor */
    public Context(int maxCallStringLen, UniqueStmt allocCallStmt) {
        this.maxCallStringLen = maxCallStringLen;
        this.callString = new LinkedList<>();
        this.callString.add(allocCallStmt);
    }

    /** Copy constructor */
    public Context(Context context) {
        this.maxCallStringLen = context.getMaxCallStringLen();
        this.callString = new LinkedList<>();
        this.callString.addAll(context.getCallString());
    }

    /**
     * Add an allocation call statement into callString.
     * Note that we should do an approximation of maxCallStringLen.
     * @param allocCallStmt     the new allocation call statement
     * @return                  true iff allocCallStmt is successfully added
     */
    public boolean addAllocCallStmt(UniqueStmt allocCallStmt) {
        if (callString.size() < maxCallStringLen) {
            return callString.add(allocCallStmt);
        } else {
            return false;
        }
    }

    public int getMaxCallStringLen() { return maxCallStringLen; }

    public List<UniqueStmt> getCallString() { return callString; }

    @Override
    public String toString() {
        String str = "callString:\n";
        for (UniqueStmt allocStmt : callString) {
            str += allocStmt.toString();
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
        Context context = (Context) o;
        return maxCallStringLen == context.maxCallStringLen &&
                Objects.equals(callString, context.callString);
    }

}
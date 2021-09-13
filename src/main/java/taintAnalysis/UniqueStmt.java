package taintAnalysis;

import soot.jimple.Stmt;
import java.util.Objects;

/**
 * I encapsulate a soot stmt with its count as UniqueStmt,
 * in order to make it unique
 */
public class UniqueStmt {
    // Soot statement
    private final Stmt stmt;
    // the count of the statement in the program
    // that means, if that statement has 3 appearances in a CFG,
    // then we set the count of each statement as 1, 2, 3
    private final int count;

    public UniqueStmt(Stmt stmt, int count) {
        this.stmt = stmt;
        this.count = count;
    }

    public Stmt getStmt() {
        return stmt;
    }

    public int getCount() {
        return count;
    }

    @Override
    public String toString() {
        String str = stmt.toString() + " at count " + count;
        return str;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (o == null || getClass() != o.getClass())
            return false;
        UniqueStmt uniqueStmt = (UniqueStmt) o;
        return Objects.equals(stmt, uniqueStmt.stmt) && count == uniqueStmt.count;
    }

    /**
     * Here we combine stmt and count to generate hashcode,
     * in order to make each one unique(for comparison in sorting)
     * @return
     */
    @Override
    public int hashCode() {
        return Objects.hash(stmt, count);
    }
}
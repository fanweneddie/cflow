package taintAnalysis;

import soot.*;
import soot.jimple.*;

import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public class Taint {

    /**
     * The type of taint transfer, which shows how this taint is generated
     */
    public enum TransferType {
        None,
        Call,
        Return
    }

    // an emptyTaint without any info, simply for a default taint
    private static final Taint emptyTaint = new Taint(null, null, null);

    // the base of the tainted object reference
    private final Value plainValue;
    // the field of the tainted object reference(if it has a field)
    private final SootField field;
    // the statement of that the tainted object is accessed at
    private final UniqueStmt uniqueStmt;
    // the method that the corresponding statement is in
    private final SootMethod method;
    // the successors of the current taint, which is an open list for dfs in path reconstruction
    private final Set<Taint> successors;
    // the type of taint transfer, which shows how this taint is generated
    private final TransferType transferType;
    // whether the current taint reaches a sink
    private boolean isSink = false;

    public static Taint getEmptyTaint() {
        return emptyTaint;
    }

    /**
     * Gets a globally unique taint object for a given pair of value and its statement context.
     * The whole value is tainted, whose taint is transferred from another taint object (can be null).
     *
     * @param t             the taint from which to transfer (null when a new taint is created)
     * @param v             the value which the taint is on
     * @param uniqueStmt    the statement context of the taint
     * @param method        the method context of the taint
     * @param taintCache    the taint cache of the method into which the taint is transferred,
     *                      used to ensure global uniqueness
     * @return The corresponding globally unique taint object
     */
    public static Taint getTaintFor(Taint t, Value v, UniqueStmt uniqueStmt, SootMethod method,
                                    Map<Taint, Taint> taintCache) {
        Taint newTaint = new Taint(v, uniqueStmt, method);
        // we use taint cache to avoid using repetitious taints.
        // that is, if a taint with identical value, uniqueStmt and method has been created before,
        // then we can use that old taint instead of the new taint
        // (btw, the old taint can be indexed by new taint because new taint has the same hashcode as old taint)
        if (taintCache.containsKey(newTaint)) {
            newTaint = taintCache.get(newTaint);
        } else {
            taintCache.put(newTaint, newTaint);
        }
        if (t != null) {
            t.addSuccessor(newTaint);
        }
        return newTaint;
    }

    /**
     * Gets a globally unique taint object whose taint is transferred from another taint object
     * with method context transfer type None.
     *
     * @param t             the taint from which to transfer
     * @param v             the value which the taint is on
     * @param uniqueStmt    the statement context of the taint
     * @param method        the method context of the taint
     * @param taintCache    the taint cache of the method into which the taint is transferred,
     *                      used to ensure global uniqueness
     * @return The corresponding globally unique taint object after transfer
     */
    public static Taint getTransferredTaintFor(Taint t, Value v, UniqueStmt uniqueStmt, SootMethod method,
                                               Map<Taint, Taint> taintCache) {
        return getTransferredTaintFor(t, v, uniqueStmt, method, taintCache, TransferType.None);
    }

    /**
     * Gets a globally unique taint object whose taint is transferred from another taint object,
     * the method context transfer type is to indicate taint transfer along call/return edges.
     *
     * @param t             the taint from which to transfer
     * @param v             the value which the taint is on
     * @param uniqueStmt    the statement context of the taint
     * @param method        the method context of the taint
     * @param taintCache    the taint cache of the method into which the taint is transferred,
     *                      used to ensure global uniqueness
     * @param transferType  the type of method context transfer
     * @return The corresponding globally unique taint object after transfer
     */
    public static Taint getTransferredTaintFor(Taint t, Value v, UniqueStmt uniqueStmt, SootMethod method,
                                               Map<Taint, Taint> taintCache, TransferType transferType) {
        Taint newTaint = new Taint(t, v, uniqueStmt, method, transferType);
        if (taintCache.containsKey(newTaint)) {
            newTaint = taintCache.get(newTaint);
        } else {
            taintCache.put(newTaint, newTaint);
        }
        t.addSuccessor(newTaint);
        return newTaint;
    }

    /**
     * Shows whether this taint taints value v
     * @param v         the value to be checked, which can be expression or reference to object
     * @return          true iff this taint taints value v
     */
    public boolean taints(Value v) {
        // Empty taint doesn't taint anything
        if (isEmpty()) return false;

        // Taint on V must taint V, taint on B.* also taints B
        if (plainValue.equivTo(v)) return true;

        if (v instanceof Expr) {
            return taints((Expr) v);
        }
        if (v instanceof Ref) {
            return taints((Ref) v);
        }
        return false;
    }

    /**
     * Shows whether this taint taints expression e
     * @param e         the expression to be checked,
     *                  which can be operated by binary, unary, cast and instanceof operator
     * @return          true iff this taint taints expression e
     */
    private boolean taints(Expr e) {
        if (e instanceof BinopExpr) {
            BinopExpr binopExpr = (BinopExpr) e;
            Value op1 = binopExpr.getOp1();
            Value op2 = binopExpr.getOp2();
            return taints(op1) || taints(op2);
        }
        if (e instanceof UnopExpr) {
            Value op = ((UnopExpr) e).getOp();
            return taints(op);
        }
        if (e instanceof CastExpr) {
            Value op = ((CastExpr) e).getOp();
            return taints(op);
        }
        if (e instanceof InstanceOfExpr) {
            Value op = ((InstanceOfExpr) e).getOp();
            return taints(op);
        }
        return false;
    }

    /**
     * Shows whether this taint taints reference r
     * @param r         the reference to be checked,
     *                  which can be a reference to an instance or an array
     * @return          true iff this taint taints reference r
     */
    private boolean taints(Ref r) {
        if (r instanceof InstanceFieldRef) {
            InstanceFieldRef fieldRef = (InstanceFieldRef) r;
            if (field == null) return false;
            return plainValue.equivTo(fieldRef.getBase()) && field.equals(fieldRef.getField());
        }
        if (r instanceof ArrayRef) {
            ArrayRef arrayRef = (ArrayRef) r;
            return plainValue.equivTo(arrayRef.getBase());
        }
        // static field ref not supported
        return false;
    }

    /**
     * a default constructor of Taint
     * create a taint on value at uniqueStmt in method, its transferType is None in default
     * @param value         the value that the taint is on
     * @param uniqueStmt    the statement context of the taint
     * @param method        the method context of the taint
     */
    private Taint(Value value, UniqueStmt uniqueStmt, SootMethod method) {
        this(null, value, uniqueStmt, method, TransferType.None);
    }

    /**
     * a complete constructor of Taint
     * create a taint on value at uniqueStmt in method,
     * which is propagated from taint transferFrom by the way of transferType
     * @param transferFrom  the taint from which to transfer
     * @param value         the value that the taint is on
     * @param uniqueStmt    the statement context of the taint
     * @param method        the method context of the taint
     * @param transferType  the type of method context transfer
     */
    private Taint(Taint transferFrom, Value value, UniqueStmt uniqueStmt,
                  SootMethod method, TransferType transferType) {
        this.uniqueStmt = uniqueStmt;
        this.method = method;
        this.successors = new HashSet<>();
        this.transferType = transferType;

        if (value instanceof Ref) {
            // if value is of ref type, ignore the taint from which to transfer
            if (value instanceof InstanceFieldRef) {
                InstanceFieldRef fieldRef = (InstanceFieldRef) value;
                this.plainValue = fieldRef.getBase();
                this.field = fieldRef.getField();
            } else {
                // array ref and static field ref is not currently supported,
                // just taint the entire value
                this.plainValue = value;
                this.field = null;
            }
        } else if (transferFrom != null) {
            // for a non-ref object-typed value, transfer taint from t
            this.plainValue = value;
            this.field = transferFrom.getField();
        } else {
            this.plainValue = value;
            this.field = null;
        }
    }

    public boolean isEmpty() {
        return plainValue == null;
    }

    public Value getPlainValue() {
        return plainValue;
    }

    public SootField getField() {
        return field;
    }

    public UniqueStmt getUniqueStmt() {
        return uniqueStmt;
    }

    public Stmt getStmt() { return uniqueStmt.getStmt(); }

    public int getCount() { return uniqueStmt.getCount(); }

    public SootMethod getMethod() {
        return method;
    }

    public Set<Taint> getSuccessors() {
        return successors;
    }

    public void addSuccessor(Taint successor) {
        this.successors.add(successor);
    }

    public TransferType getTransferType() {
        return transferType;
    }

    public boolean isSink() {
        return isSink;
    }

    public void setSink() {
        isSink = true;
    }

    @Override
    public String toString() {
        if (isEmpty()) return "Empty Taint";

        String str = "";
        if (transferType != TransferType.None) {
            str += "[" + transferType + "] ";
        }
        str += plainValue + (field != null ? "." + field : "") +
                " in " + uniqueStmt.getStmt() + " in method " + method;

        return str;
    }

    /**
     * a new way of showing a taint in string(in order to debug)
     * The form of a taint is shown below:
     * indent   object:
     *          transfertype:
     *          statement:      count:
     *          method:
     *
     * @param indent: the indent to show the relation in activation record.
     *
     * @return
     */
    public String toStringNew(String indent) {
        if (isEmpty()) return indent + "Empty Taint";

        String str = "";
        // line 1: shows the object of the taint
        str = indent + "Object: " + plainValue + (field != null ? "." + field : "") + "\n";
        // line 2: shows the transferType of the taint
        str += indent + "Type:  " + transferType + "\n";
        // line 3: shows the stmt and count of the uniqueStmt of taint
        str += indent + "Stmt:  " + uniqueStmt.getStmt() + " at " + uniqueStmt.getCount() + "\n";
        // line 4: shows the method of the taint
        str += indent + "Method: " + method;
        return str;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Taint taint = (Taint) o;
        return Objects.equals(plainValue, taint.plainValue) &&
                Objects.equals(field, taint.field) &&
                Objects.equals(uniqueStmt, taint.uniqueStmt) &&
                Objects.equals(method, taint.method) &&
                transferType == taint.transferType;
    }

    @Override
    public int hashCode() {
        return Objects.hash(plainValue, field, uniqueStmt, method, transferType);
    }

}

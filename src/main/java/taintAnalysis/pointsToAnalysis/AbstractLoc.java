package taintAnalysis.pointsToAnalysis;

import soot.Type;

import java.util.Objects;

/**
 * The abstract location on heap,
 * which is distinguished by context
 */
public class AbstractLoc {
    // The context of the allocation statement
    private final Context context;
    // The type of object
    private Type type;

    /** Naive constructor */
    public AbstractLoc(Context context, Type type) {
        this.context = new Context(context);
        this.type = type;
    }

    /** Copy constructor */
    public AbstractLoc(AbstractLoc abstractLoc) {
        this.context = new Context(abstractLoc.getContext());
        this.type = abstractLoc.getType();
    }

    public Context getContext() { return context; }

    public Type getType() { return type; }

    public void setType(Type type) {
        this.type = type;
    }

    @Override
    public String toString() {
        String str = "context:\n";
        str += context.toString();
        str += "\ntype:\n";
        str += type.toString();
        str += "\n";
        return str;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (o == null || getClass() != o.getClass())
            return false;
        AbstractLoc abstractLoc = (AbstractLoc) o;
        return Objects.equals(context, abstractLoc.context)
                && type == abstractLoc.type;
    }

}
// Testing the sink checking module
// That is, for sink(a) where a.f is tainted,
// we should check whether a.f is used in sink(a)
// Note: this should be passed only if the maxDepth in FieldUseChecker is >= 2
public class SinkCheckTest {
    
    private static int source1() {
        return 4;
    }
    
    private static A2 source2() {
        return new A2();
    }

    // a.f is used in sink1,
    // so the corresponding sink is generated
    public void SinkCheckTest1() {
        A2 a = new A2();
        a.f = source1();
        a.sink1();
    }

    // a.g is not used in sink1,
    // so no sink is generated
    public void SinkCheckTest2() {
        A2 a = new A2();
        a.g = source1();
        a.sink1();
    }

    // We assume that a is used in sink1,
    // so a sink is generated
    public void SinkCheckTest3() {
        A2 a = new A2();
        a = source2();
        a.sink1();
    }

    // a.f is used in sink1() invoked by sink2(),
    // so a sink is generated
    public void SinkCheckTest4() {
        A2 a = new A2();
        a.f = source1();
        a.sink2();
    }

    // a.f is used in bar2() invoked by sink3(),
    // no sink is generated
    public void SinkCheckTest5() {
        A2 a = new A2();
        a.f = source1();
        a.sink3();
    }

    public static void main(String[] args) {
        SinkCheckTest sct = new SinkCheckTest();
        sct.SinkCheckTest1();
        sct.SinkCheckTest2();
        sct.SinkCheckTest3();
        sct.SinkCheckTest4();
    }
}
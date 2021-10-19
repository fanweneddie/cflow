class A2 {
    public int f;
    public int g;

    // Does not return a taint
    public int foo() {
        f = 1;
        return f;
    }

    public static int source() {
        return 2;
    }

    public void sink1() {
        System.out.println(f);
    }
}

class B2 extends A2 {
    public int h;

    // Return a taint
    public int foo() {
        h = source();
        return h;
    }

}

// Testing problems brought by polymorphism
public class PolymorphismTest {

    public A2 getA2() {
        return new B2();
    }

    // A2.foo() should be called, 
    // so no taint is returned
    public int polymorphismTest1() {
        A2 a = new A2();
        int r = a.foo();
        return r;
    }

    // B2.foo() should be called, 
    // so a taint is returned
    public int polymorphismTest2() {
        A2 a = new B2();
        int r = a.foo();
        return r;
    }


    // B2.foo() should be called, 
    // so a taint is returned
    public int polymorphismTest3() {
        A2 a = getA2();
        int r = a.foo();
        return r;
    }

    // A2.foo() should be called
    // since the points-to set of a at call site is null.
    // So a taint is returned
    public int polymorphismTest4() {
        A2 a;
        if (Math.random() > 0.5) {
            a = new A2();
        } else {
            a = new B2();
        }
        int r = a.foo();
        return r;
    }

    public static void main(String[] args) {
        PolymorphismTest pmt = new PolymorphismTest();
        pmt.polymorphismTest1();
        pmt.polymorphismTest2();
        pmt.polymorphismTest3();
        pmt.polymorphismTest4();
    }
}

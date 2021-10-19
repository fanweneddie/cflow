// Testing alias problem brought by assignment
class A1 {
    public B1 b;
    public int f;
}

class B1 {
    public C1 c;
    public int g;
}

class C1 {
    public int h;
}

public class AssignAliasTest {

    private static int source() {
        return 1;
    }

    // Aliased field depth = 0
    // With points-to analysis, the return value should be tainted
    public int aliasTest1() {
        A1 a1 = new A1();
        A1 a2 = new A1();
        a1 = a2;
        a1.f = source();
        System.out.println(a2.f);
        return a2.f;
    }

    // Aliased field depth = 1
    // With points-to analysis, the return value should be tainted
    public int aliasTest2() {
        A1 a1 = new A1();
        A1 a2 = new A1();
        a1.b = a2.b;
        a1.b.g = source();
        System.out.println(a2.b.g);
        return a2.b.g;
    }

    // Aliased field depth = 2
    // With points-to analysis, the return value should be tainted
    public int aliasTest3() {
        A1 a1 = new A1();
        A1 a2 = new A1();
        a1.b.c = a2.b.c;
        a1.b.c.h = source();
        System.out.println(a2.b.c.h);
        return a2.b.c.h;
    }

    public static void main(String[] args) {
        AssignAliasTest aat = new AssignAliasTest();
        aat.aliasTest1();
        aat.aliasTest2();
        aat.aliasTest3();
    }

}
// Testing the accuracy of array
public class ArrayTest {

    private static int source() {
        return 3;
    }

    // arr[0] is tainted,
    // so it will return a taint
    public A2 arrayTest1() {
        A2 []arr = new A2[1];
        A2 a1 = new A2();
        a1.f = source();
        arr[0] = a1;
        return arr[0];
    }

    // arr[0] is tainted,
    // but arr is not tainted
    // So it will not return a taint
    public A2[] arrayTest2() {
        A2 []arr = new A2[1];
        A2 a1 = new A2();
        a1.f = source();
        arr[0] = a1;
        return arr;
    }

    public static void main(String[] args) {
        ArrayTest at = new ArrayTest();
        at.arrayTest1();
        at.arrayTest2();
    }
    
}

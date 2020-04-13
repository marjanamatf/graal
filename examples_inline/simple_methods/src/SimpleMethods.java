public class SimpleMethods {

    private int x;
    private int y;
    static int number;

    SimpleMethods(int x, int y){this.x = x; this.y = y;};

    public void setX(int x) {
        this.x = x;
    }

    public void setY(int y) {
        this.y = y;
    }

    public int getX() { return x; }

    public int getY() { return y; }

    public int getAverage(){
        int sum = getX() + getY();
        sum /= 2;
        return sum;
    }

    public static int two(){
        return 2;
    }

    public static int getNumber(){
        return number;
    }

    public static void empty(){ }

    public static void main(String[] args){
        SimpleMethods obj = new SimpleMethods(10,20);
        obj.setX(obj.getX()+5);
        obj.setY(obj.getY()+10);
        obj.getX();
        two();
        SimpleMethods.number = 2;
        getNumber();
        System.out.println(obj.getAverage());
        empty();
    }

}
package xyz.facta.jtools.comp.jproginsight;

public class CGEdge {
    String originClass;
    String originMethodSig;
    String destClass;
    String destMethodSig;

    CGEdge(String originClass, String originMethodSig, String destClass, String destMethodSig) {
        this.originClass = originClass;
        this.originMethodSig = originMethodSig;
        this.destClass = destClass;
        this.destMethodSig = destMethodSig;
    }

    public String toTSVLine() {
        return "\"" + originClass + "\"\t\"" + originMethodSig + "\"\t\"" + destClass + "\"\t\"" + destMethodSig + "\"";
    }
    @Override
    public String toString() {
        return toTSVLine();
    }
}

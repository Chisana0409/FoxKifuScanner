package jp.chisana.foxkifuscanner;

public final class MetadataSelfTest {
    public static void main(String[] args) {
        GameMetadata metadata = MetadataReader.readTextForTest(
                "chisana\n10級", "黒\n4と1/4子勝ち", "V532816174\n11級", true);
        require(metadata.blackName.equals("chisana"), "black name");
        require(metadata.blackRank.equals("10級"), "black rank");
        require(metadata.whiteName.equals("V532816174"), "white name");
        require(metadata.whiteRank.equals("11級"), "white rank");
        require(metadata.result.equals("B+8.5"), "fox child-to-point conversion");
        require(MetadataReader.parseResult("白 半目勝ち").equals("W+0.5"), "half point");
        require(MetadataReader.parseResult("黒 3目半勝ち").equals("B+3.5"), "point and half");
        require(metadata.place.equals("野狐囲碁"), "game place");
        require(MetadataReader.parseResult("黒\n3/4子").equals("B+1.5"), "split-line child conversion");
        require(MetadataReader.parseResult("黒\n中盤勝ち").equals("B+R"), "split-line resignation conversion");
        System.out.println("MetadataSelfTest: all checks passed");
    }

    private static void require(boolean condition, String name) {
        if (!condition) throw new AssertionError(name);
    }
}

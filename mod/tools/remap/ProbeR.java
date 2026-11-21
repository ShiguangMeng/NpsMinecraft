import java.nio.file.*;
public class ProbeR {
  public static void main(String[] a) throws Exception {
    RemapTool.loadProguard(Paths.get(a[0]));
    RemapTool.loadTiny(Paths.get(a[1]));
    RemapTool.buildForwardTables();
    System.out.println("fwdMethod=" + RemapTool.fwdMethod.size() + " byName=" + RemapTool.fwdMethodByName.size());
    String[] probes = {
      "net/minecraft/network/chat/Component|getString|()Ljava/lang/String;",
      "net/minecraft/client/KeyMapping|<init>|(Ljava/lang/String;ILjava/lang/String;)V",
      "net/minecraft/core/BlockPos|equals|(Ljava/lang/Object;)Z",
      "net/minecraft/resources/ResourceLocation|toString|()Ljava/lang/String;"
    };
    for (String p : probes) System.out.println("M " + p + " => " + RemapTool.fwdMethod.get(p));
    System.out.println("byName getString|()Ljava/lang/String; => " + RemapTool.fwdMethodByName.get("getString|()Ljava/lang/String;"));
    System.out.println("byName toString|()Ljava/lang/String; => " + RemapTool.fwdMethodByName.get("toString|()Ljava/lang/String;"));
    System.out.println("byName <init>|(Ljava/lang/String;ILjava/lang/String;)V => " + RemapTool.fwdMethodByName.get("<init>|(Ljava/lang/String;ILjava/lang/String;)V"));
    System.out.println("obfToMojMethod size=" + RemapTool.obfToMojMethod.size() + " obfToTgtMethod=" + RemapTool.obfToTgtMethod.size());
    System.out.println("wz|d|()Ljava/lang/String; moj=" + RemapTool.obfToMojMethod.get("wz|d|()Ljava/lang/String;"));
    System.out.println("wz|d|()Ljava/lang/String; tgt=" + RemapTool.obfToTgtMethod.get("wz|d|()Ljava/lang/String;"));
  }
}
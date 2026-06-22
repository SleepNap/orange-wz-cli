package orange.wz.patcher;

import orange.wz.provider.WzAESConstant;
import orange.wz.provider.tools.wzkey.WzKey;

/**
 * 统一 IV 解析，GMS / EMS / BMS / CLASSIC 大小写不敏感。
 */
public final class IvSupport {
    private IvSupport() {}

    public static WzKey resolve(String name) {
        String k = name.toLowerCase();
        return switch (k) {
            case "gms" -> new WzKey(1, "gms", WzAESConstant.WZ_GMS_IV, WzAESConstant.DEFAULT_KEY);
            case "ems" -> new WzKey(2, "ems", WzAESConstant.WZ_EMS_IV, WzAESConstant.DEFAULT_KEY);
            case "bms" -> new WzKey(3, "bms", WzAESConstant.WZ_BMS_IV, WzAESConstant.DEFAULT_KEY);
            case "classic" -> new WzKey(4, "classic", WzAESConstant.WZ_CLASSIC_IV, WzAESConstant.DEFAULT_KEY);
            // 兼容旧版取值：cms 与 ems 同字节、latest 与 classic 同字节
            case "cms" -> new WzKey(2, "ems", WzAESConstant.WZ_EMS_IV, WzAESConstant.DEFAULT_KEY);
            case "latest" -> new WzKey(4, "classic", WzAESConstant.WZ_CLASSIC_IV, WzAESConstant.DEFAULT_KEY);
            default -> null;
        };
    }
}
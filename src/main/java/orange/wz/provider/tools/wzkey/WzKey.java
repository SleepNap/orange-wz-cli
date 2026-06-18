package orange.wz.provider.tools.wzkey;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class WzKey {
    private Integer id;
    private String name;
    private byte[] iv;
    private byte[] userKey;
    private boolean selected;

    public WzKey() {
    }

    public WzKey(Integer id, String name, byte[] iv, byte[] userKey) {
        this.id = id;
        this.name = name;
        this.iv = iv;
        this.userKey = userKey;
    }
}

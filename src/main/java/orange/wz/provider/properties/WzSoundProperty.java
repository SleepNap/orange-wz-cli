package orange.wz.provider.properties;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import orange.wz.provider.WzImage;
import orange.wz.provider.WzObject;
import orange.wz.provider.tools.BinaryReader;
import orange.wz.provider.tools.BinaryWriter;
import orange.wz.provider.tools.WzType;

import java.util.Arrays;

/**
 * 简化版的 sound property —— xml-img-patcher 不会修改 sound 节点，
 * 只需要解析 + 保存原样字节即可。
 * 原始实现依赖 javax.sound + mp3spi 解析 mp3/wav 头，本工具去掉这部分依赖，
 * 把 header 当作字节直通处理。
 */
@Slf4j
public class WzSoundProperty extends WzExtended {
    private static final byte[] soundHeader = new byte[]{
            0x02,
            (byte) 0x83, (byte) 0xEB, 0x36, (byte) 0xE4, 0x4F, 0x52, (byte) 0xCE, 0x11, (byte) 0x9F, 0x53, 0x00, 0x20, (byte) 0xAF, 0x0B, (byte) 0xA7, 0x70,
            (byte) 0x8B, (byte) 0xEB, 0x36, (byte) 0xE4, 0x4F, 0x52, (byte) 0xCE, 0x11, (byte) 0x9F, 0x53, 0x00, 0x20, (byte) 0xAF, 0x0B, (byte) 0xA7, 0x70,
            0x00,
            0x01,
            (byte) 0x81, (byte) 0x9F, 0x58, 0x05, 0x56, (byte) 0xC3, (byte) 0xCE, 0x11, (byte) 0xBF, 0x01, 0x00, (byte) 0xAA, 0x00, 0x55, 0x59, 0x5A
    };

    private byte[] soundBytes;
    @Getter
    private int lenMs;
    private byte[] header;
    private int offset;
    private int soundDataLen;

    public WzSoundProperty(String name, WzObject parent, WzImage wzImage) {
        super(name, WzType.SOUND_PROPERTY, parent, wzImage);
    }

    public WzSoundProperty(String name, int length, byte[] header, byte[] soundBytes, WzObject parent, WzImage wzImage) {
        this(name, parent, wzImage);
        this.lenMs = length;
        this.header = header;
        this.soundBytes = soundBytes;
    }

    /**
     * patcher 不会调用此方法；保留以便外部调用时不至于报 method not found。
     * 注意：这里只是把字节存起来，并不会重建 wav/mp3 头，因此不要用它替换音频。
     */
    public void setSound(byte[] soundBytes) {
        this.soundBytes = soundBytes;
    }

    public void setData(BinaryReader reader) {
        reader.skip(1);

        soundDataLen = reader.readCompressedInt();
        lenMs = reader.readCompressedInt();

        byte[] soundHeaderBytes = reader.getBytes(soundHeader.length);
        int wavFormatLen = reader.getByte();
        byte[] waveFormatBytes = reader.getBytes(wavFormatLen);

        // 把 soundHeader + length byte + waveFormat 整个作为字节直通保存
        header = new byte[soundHeaderBytes.length + 1 + waveFormatBytes.length];
        System.arraycopy(soundHeaderBytes, 0, header, 0, soundHeaderBytes.length);
        header[soundHeaderBytes.length] = (byte) wavFormatLen;
        System.arraycopy(waveFormatBytes, 0, header, soundHeaderBytes.length + 1, waveFormatBytes.length);

        offset = reader.getPosition();
        soundBytes = reader.getBytes(soundDataLen);
    }

    public byte[] getHeader() {
        return header;
    }

    public byte[] getSoundBytes() {
        return getSoundBytes(true);
    }

    public byte[] getSoundBytes(boolean saveInMem) {
        if (soundBytes == null) {
            byte[] returnBytes = null;
            if (offset != 0) {
                BinaryReader reader = wzImage.getReader();
                int curOffset = reader.getPosition();
                reader.setPosition(offset);
                returnBytes = reader.getBytes(soundDataLen);
                reader.setPosition(curOffset);
                if (saveInMem) {
                    soundBytes = returnBytes;
                }
            }
            return returnBytes;
        }

        return soundBytes;
    }

    @Override
    public void writeValue(BinaryWriter writer) {
        byte[] soundBytes = getSoundBytes(false);
        writer.writeStringBlock(WzExtendedType.SOUND.getString(), WzImage.withoutOffsetFlag, WzImage.withOffsetFlag);
        writer.putByte((byte) 0);
        writer.writeCompressedInt(soundBytes.length);
        writer.writeCompressedInt(lenMs);
        writer.putBytes(getHeader());
        writer.putBytes(soundBytes);
    }

    @Override
    public WzSoundProperty deepClone(WzObject parent) {
        WzSoundProperty clone = new WzSoundProperty(name, parent, null);
        byte[] soundBytes = getSoundBytes(false);
        clone.soundBytes = soundBytes == null ? null : Arrays.copyOf(soundBytes, soundBytes.length);
        clone.lenMs = lenMs;
        clone.header = header == null ? null : Arrays.copyOf(header, header.length);
        clone.soundDataLen = soundDataLen;
        return clone;
    }
}

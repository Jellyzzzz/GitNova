package com.gitnova.gitobject;

/** Object type codes are protocol values; never use enum ordinal as a wire value. */
public enum ObjectType {
    COMMIT((byte) 1);

    private final byte code;

    ObjectType(byte code) {
        this.code = code;
    }

    public byte code() {
        return code;
    }

    public static ObjectType fromCode(byte code) {
        for (ObjectType type : values()) {
            if (type.code == code) {
                return type;
            }
        }
        throw new IllegalArgumentException("unsupported Git object type code: " + Byte.toUnsignedInt(code));
    }
}

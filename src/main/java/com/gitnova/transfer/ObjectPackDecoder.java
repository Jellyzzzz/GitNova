package com.gitnova.transfer;

import java.io.InputStream;

public interface ObjectPackDecoder {

    ValidatedPack decode(InputStream input, long declaredPackSize, TransferProperties limits);
}

package com.funnywork;

import java.io.IOException;
import java.io.OutputStream;

/** Account-scoped immutable bytes. Alternate storage must preserve verified hash and range semantics. */
public interface ResultBlobReader {
    boolean verified(Identity actor,String hash,long size);
    void write(Identity actor,String hash,long first,long last,OutputStream output)throws IOException;
}

package org.apache.lucene.index.codecs.intblock;

/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/** Naive int block API that writes vInts.  This is
 *  expected to give poor performance; it's really only for
 *  testing the pluggability.  One should typically use pfor instead. */

import java.io.IOException;

import org.apache.lucene.index.codecs.sep.IntIndexOutput;
import org.apache.lucene.store.IndexOutput;

/** Abstract base class that writes fixed-size blocks of ints
 *  to an IndexOutput.  While this is a simple approach, a
 *  more performant approach would directly create an impl
 *  of IntIndexOutput inside Directory.  Wrapping a generic
 *  IndexInput will likely cost performance.
 *
 * @lucene.experimental
 */
public abstract class FixedIntBlockIndexOutput extends IntIndexOutput {

  protected final IndexOutput out;
  private final int blockSize;
  protected final int[] buffer;
  private int upto;

  protected FixedIntBlockIndexOutput(IndexOutput out, int fixedBlockSize) throws IOException {
    blockSize = fixedBlockSize;
    this.out = out;
    out.writeInt(blockSize);
    buffer = new int[blockSize];
  }

  protected abstract void flushBlock() throws IOException;

  @Override
  public Index index() throws IOException {
    return new Index();
  }

  private class Index extends IntIndexOutput.Index {
    long fp;
    int upto;
    long lastFP;
    int lastUpto;

    @Override
    public void mark() throws IOException {
      fp = out.getFilePointer();
      upto = FixedIntBlockIndexOutput.this.upto;
    }

    @Override
    public void set(IntIndexOutput.Index other) throws IOException {
      Index idx = (Index) other;
      lastFP = fp = idx.fp;
      lastUpto = upto = idx.upto;
    }

    @Override
    public void write(IndexOutput indexOut, boolean absolute) throws IOException {
      if (absolute) {
        indexOut.writeVLong(fp);
        indexOut.writeVInt(upto);
      } else if (fp == lastFP) {
        // same block
        indexOut.writeVLong(0);
        assert upto >= lastUpto;
        indexOut.writeVInt(upto - lastUpto);
      } else {      
        // new block
        indexOut.writeVLong(fp - lastFP);
        indexOut.writeVInt(upto);
      }
      lastUpto = upto;
      lastFP = fp;
    }

    @Override
    public void write(IntIndexOutput indexOut, boolean absolute) throws IOException {
      if (absolute) {
        indexOut.writeVLong(fp);
        indexOut.write(upto);
      } else if (fp == lastFP) {
        // same block
        indexOut.writeVLong(0);
        assert upto >= lastUpto;
        indexOut.write(upto - lastUpto);
      } else {      
        // new block
        indexOut.writeVLong(fp - lastFP);
        indexOut.write(upto);
      }
      lastUpto = upto;
      lastFP = fp;
    }

    @Override
    public String toString() {
      return "fp=" + fp + " idx=" + upto;
    }
  }

  private boolean abort;

  @Override
  public void write(int v) throws IOException {
    buffer[upto++] = v;
    if (upto == blockSize) {
      boolean success = false;
      try {
        flushBlock();
        success = true;
      } finally {
        abort |= !success;
      }
      upto = 0;
    }
  }

  @Override
  public void close() throws IOException {
    try {
      // NOTE: entries in the block after current upto are
      // invalid
      if (!abort) {
        while(upto != 0) {
          // nocommit -- risky since in theory a "smart" int
          // encoder could do run-length-encoding and thus
          // never flush on an infinite stream of 0s; maybe
          // flush upto instead?  or random ints heh
          // stuff 0s until final block is flushed
          //System.out.println("upto=" + upto + " stuff 0; blockSize=" + blockSize);
          write(0);
        }
      }
      /*
      if (upto > 0) {
        while(upto < blockSize) {
          write(0);
          upto++;
          System.out.println("FILL");
        }
        //flushBlock();
      }
      */
    } finally {
      out.close();
    }
  }
}
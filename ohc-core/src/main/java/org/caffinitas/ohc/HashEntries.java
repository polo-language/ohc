/*
 *      Copyright (C) 2014 Robert Stupp, Koeln, Germany, robert-stupp.de
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */
package org.caffinitas.ohc;

import java.io.DataInput;
import static org.caffinitas.ohc.Constants.*;

/**
 * Encapsulates access to hash entries.
 */
public final class HashEntries
{
    static void toOffHeap(BytesSource source, long hashEntryAdr, long blkOff)
    {
        long len = source.size();
        if (source.hasArray())
        {
            // keySource provides array access (can use Unsafe.copyMemory)
            byte[] arr = source.array();
            int arrOff = source.arrayOffset();
            Uns.copyMemory(arr, arrOff, hashEntryAdr, blkOff, len);
        }
        else
        {
            // keySource has no array
            for (int p = 0; p < len; )
            {
                byte b = source.getByte(p++);
                Uns.putByte(hashEntryAdr, blkOff++, b);
            }
        }
    }

    static void init(long hash, long keyLen, long valueLen, long hashEntryAdr)
    {
        Uns.putLongVolatile(hashEntryAdr, ENTRY_OFF_HASH, hash);
        setNext(hashEntryAdr, 0L);
        setPrevious(hashEntryAdr, 0L);
        Uns.putLongVolatile(hashEntryAdr, ENTRY_OFF_KEY_LENGTH, keyLen);
        Uns.putLongVolatile(hashEntryAdr, ENTRY_OFF_VALUE_LENGTH, valueLen);
        Uns.putLongVolatile(hashEntryAdr, ENTRY_OFF_REFCOUNT, 1L);
    }

    static void valueToSink(long hashEntryAdr, BytesSink valueSink)
    {
        if (hashEntryAdr == 0L)
            return;

        long serKeyLen = getHashKeyLen(hashEntryAdr);
        if (serKeyLen < 0L)
            throw new InternalError();
        long valueLen = Uns.getLongVolatile(hashEntryAdr, ENTRY_OFF_VALUE_LENGTH);
        if (valueLen < 0L)
            throw new InternalError();
        long blkOff;

        // skip key
        blkOff = ENTRY_OFF_DATA + roundUpTo8(serKeyLen);

        if (valueLen > Integer.MAX_VALUE)
            throw new IllegalStateException("integer overflow");
        valueSink.setSize((int) valueLen);

        if (valueSink.hasArray())
        {
            // valueSink provides array access (can use Unsafe.copyMemory)
            byte[] arr = valueSink.array();
            int arrOff = valueSink.arrayOffset();
            Uns.copyMemory(hashEntryAdr, blkOff, arr, arrOff, valueLen);
        }
        else
        {
            // last-resort byte-by-byte copy
            for (int p = 0; p < valueLen; p++)
            {
                byte b = Uns.getByte(hashEntryAdr, blkOff++);
                valueSink.putByte(p, b);
            }
        }
    }

    static boolean compareKey(long hashEntryAdr, BytesSource keySource, long serKeyLen)
    {
        if (hashEntryAdr == 0L)
            return false;

        long blkOff = ENTRY_OFF_DATA;
        int p = 0;

        // array optimized version
        if (keySource.hasArray())
        {
            byte[] arr = keySource.array();
            int arrOff = keySource.arrayOffset();
            for (; p <= serKeyLen - 8; p += 8, blkOff += 8)
            {
                long lSer = Uns.getLong(hashEntryAdr, blkOff);
                long lKey = Uns.getLongFromByteArray(arr, arrOff + p);
                if (lSer != lKey)
                    return false;
            }
        }

        // last-resort byte-by-byte compare
        for (; p < serKeyLen; p++)
        {
            byte bSer = Uns.getByte(hashEntryAdr, blkOff++);
            byte bKey = keySource.getByte(p);

            if (bSer != bKey)
                return false;
        }

        return true;
    }

    // Replacement0 and replacement1 are values used by replacement strategies and stored with each hash entry.
    // For example LRUReplacementStrategy requires a 'next' and a 'previous' pointer for its double-linked-LRU-list.

    public static long getReplacement0(long hashEntryAdr)
    {
        return Uns.getLongVolatile(hashEntryAdr, ENTRY_OFF_LRU_NEXT);
    }

    public static void setReplacement0(long hashEntryAdr, long replacement)
    {
        Uns.putLongVolatile(hashEntryAdr, ENTRY_OFF_LRU_NEXT, replacement);
    }

    public static long getReplacement1(long hashEntryAdr)
    {
        return Uns.getLongVolatile(hashEntryAdr, ENTRY_OFF_LRU_PREV);
    }

    public static void setReplacement1(long hashEntryAdr, long replacement)
    {
        Uns.putLongVolatile(hashEntryAdr, ENTRY_OFF_LRU_PREV, replacement);
    }

    static long getHash(long hashEntryAdr)
    {
        return Uns.getLongVolatile(hashEntryAdr, ENTRY_OFF_HASH);
    }

    static long getNext(long hashEntryAdr)
    {
        return hashEntryAdr != 0L ? Uns.getLongVolatile(hashEntryAdr, ENTRY_OFF_NEXT) : 0L;
    }

    static void setNext(long hashEntryAdr, long nextAdr)
    {
        if (hashEntryAdr == nextAdr)
            throw new IllegalArgumentException();
        if (hashEntryAdr != 0L)
            Uns.putLongVolatile(hashEntryAdr, ENTRY_OFF_NEXT, nextAdr);
    }

    static long getPrevious(long hashEntryAdr)
    {
        return hashEntryAdr != 0L ? Uns.getLongVolatile(hashEntryAdr, ENTRY_OFF_PREVIOUS) : 0L;
    }

    static void setPrevious(long hashEntryAdr, long prevAdr)
    {
        if (hashEntryAdr == prevAdr)
            throw new IllegalArgumentException();
        if (hashEntryAdr != 0L)
            Uns.putLongVolatile(hashEntryAdr, ENTRY_OFF_PREVIOUS, prevAdr);
    }

    static long getHashKeyLen(long hashEntryAdr)
    {
        return Uns.getLongVolatile(hashEntryAdr, ENTRY_OFF_KEY_LENGTH);
    }

    static long getAllocLen(long address)
    {
        return Uns.getLongVolatile(address, ENTRY_OFF_ALLOC_LEN);
    }

    static long getValueLen(long hashEntryAdr)
    {
        return Uns.getLongVolatile(hashEntryAdr, ENTRY_OFF_VALUE_LENGTH);
    }

    static DataInput readKeyFrom(long hashEntryAdr)
    {
        return newInput(hashEntryAdr, false);
    }

    static DataInput readValueFrom(long hashEntryAdr)
    {
        return newInput(hashEntryAdr, true);
    }

    private static HashEntryInput newInput(long hashEntryAdr, boolean value)
    {
        return new HashEntryInput(hashEntryAdr, value, getHashKeyLen(hashEntryAdr), getValueLen(hashEntryAdr));
    }

    static void reference(long hashEntryAdr)
    {
        Uns.increment(hashEntryAdr, ENTRY_OFF_REFCOUNT);
    }

    static boolean dereference(long hashEntryAdr)
    {
        return Uns.decrement(hashEntryAdr, ENTRY_OFF_REFCOUNT);
    }
}
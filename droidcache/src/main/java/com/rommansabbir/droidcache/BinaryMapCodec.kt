package com.rommansabbir.droidcache

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import kotlin.collections.iterator

/**
 * Simple binary encoding for Map<String, ByteArray>.
 *
 * Format (decrypted payload):
 * [count:int]
 * repeated count times:
 *  [keyLen:short][key:utf8 bytes]
 *  [valLen:int][val:raw bytes]
 */
internal object BinaryMapCodec {

    /**
     * Encodes a map into a byte array.
     *
     * @param map The map to be encoded, with String keys and ByteArray values.
     * @return A ByteArray representing the encoded map.
     */
    fun encode(map: Map<String, ByteArray>): ByteArray {
        val baos = ByteArrayOutputStream()
        DataOutputStream(baos).use { out ->
            out.writeInt(map.size)
            for ((key, value) in map) {
                val keyBytes = key.toByteArray(Charsets.UTF_8)
                out.writeShort(keyBytes.size)
                out.write(keyBytes)

                out.writeInt(value.size)
                out.write(value)
            }
        }
        return baos.toByteArray()
    }

    /**
     * Decodes a byte array back into a map.
     *
     * @param bytes The byte array to be decoded.
     * @return A MutableMap<String, ByteArray> representing the decoded data.
     */
    fun decode(bytes: ByteArray): MutableMap<String, ByteArray> {
        val map = mutableMapOf<String, ByteArray>()
        DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            val count = input.readInt()
            repeat(count) {
                val keyLen = input.readShort().toInt()
                val keyBytes = ByteArray(keyLen)
                input.readFully(keyBytes)
                val key = String(keyBytes, Charsets.UTF_8)

                val valLen = input.readInt()
                val value = ByteArray(valLen)
                input.readFully(value)

                map[key] = value
            }
        }
        return map
    }
}

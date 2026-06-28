package org.draken.usagi.core.network.proxy.bypass

import java.io.ByteArrayOutputStream
import java.io.FilterOutputStream
import java.io.OutputStream
import kotlin.math.min
import kotlin.random.Random

class BypassStream(private val delegate: OutputStream): FilterOutputStream(delegate) {
	private var first = true
	private val buffer = ByteArrayOutputStream()

	override fun write(b: Int) {
		if (first) {
			buffer.write(b)
			val data = buffer.toByteArray()
			isFlush(data)
		} else {
			delegate.write(b)
		}
	}

	override fun write(b: ByteArray, off: Int, len: Int) {
		if (first) {
			buffer.write(b, off, len)
			val data = buffer.toByteArray()
			isFlush(data)
		} else {
			delegate.write(b, off, len)
		}
	}

	override fun flush() {
		if (first) {
			val data = buffer.toByteArray()
			if (data.isNotEmpty()) {
				first = false
				process(data)
			}
		}
		delegate.flush()
	}

	private fun isFlush(data: ByteArray) {
		if (isTlsHello(data, data.size)) {
			val payLen = u16(data, 3)
			val total = payLen + 5
			if (data.size >= total || data.size >= MAX_HELLO) {
				first = false
				process(data)
			}
		} else if (data.size >= 5) {
			first = false
			process(data)
		}
	}

	private fun process(data: ByteArray) {
		if (isTlsHello(data, data.size)) {
			writeChunk(data, data.size, delegate)
		} else {
			val reqStr = String(data, Charsets.ISO_8859_1)
			if (isHttpRequest(reqStr)) {
				val desynced = applyDesync(reqStr)
				delegate.write(desynced.toByteArray(Charsets.ISO_8859_1))
			} else {
				simpleSplit(data, data.size, delegate)
			}
		}
	}

	private fun isHttpRequest(s: String): Boolean {
		val firstLine = s.substringBefore("\r\n")
		val parts = firstLine.split(' ')
		if (parts.size < 2) return false
		val method = parts[0].uppercase()
		return method in METHOD
	}

	private fun u16(d: ByteArray, o: Int) = ((d[o].toInt() and 0xFF) shl 8) or (d[o + 1].toInt() and 0xFF)

	/* Bypass, main */
	private fun writeChunk(data: ByteArray, size: Int, out: OutputStream) {
		if (size <= 0) return
		if (isTlsHello(data, size)) {
			val sni = findSni(data, size)
			if (sni != null) writeTls(data, size, sni, out)
			else simpleSplit(data, size, out)
		} else {
			simpleSplit(data, size, out)
		}
	}

	private fun writeTls(data: ByteArray, size: Int, sni: SniInfo, out: OutputStream) {
		val payloadLen = u16(data, 3)
		val recEnd = 5 + payloadLen
		if (recEnd > size) { splitTcp(data, size, sni, out); return }

		val splits = splitPoints(sni, payloadLen)
		var off = 5
		for ((i, end) in splits.withIndex()) {
			val fragLen = end - off
			if (fragLen <= 0) continue
			out.write(0x16)
			out.write(data[1].toInt() and 0xFF)
			out.write(data[2].toInt() and 0xFF)
			out.write((fragLen shr 8) and 0xFF)
			out.write(fragLen and 0xFF)
			out.write(data, off, fragLen)
			out.flush()
			off = end
			if (i < splits.size - 1) delay()
		}
		if (recEnd < size) { out.write(data, recEnd, size - recEnd); out.flush() }
	}

	private fun splitPoints(sni: SniInfo, payLen: Int): List<Int> {
		val payEnd = 5 + payLen
		val pts = mutableListOf<Int>()
		val before = sni.offset
		if (before > 5 + MIN_FRAG) pts.add(before)
		val mid = sni.offset + sni.length / 2
		if (mid > (pts.lastOrNull() ?: 5) + MIN_FRAG && mid < payEnd - MIN_FRAG) pts.add(mid)
		val after = sni.offset + sni.length
		if (after > (pts.lastOrNull() ?: 5) + MIN_FRAG && after < payEnd - MIN_FRAG) pts.add(after)
		pts.add(payEnd)
		if (pts.size <= 1) {
			val early = 5 + min(MIN_FRAG, payLen / 3)
			if (early < payEnd - MIN_FRAG) pts.add(0, early)
		}
		return pts
	}

	private fun splitTcp(data: ByteArray, size: Int, sni: SniInfo, out: OutputStream) {
		val splits = buildList {
			add(1)
			if (sni.offset in 2 until size) add(sni.offset)
			val m = sni.offset + sni.length / 2
			if (m > (lastOrNull() ?: 0) && m < size) add(m)
			val a = sni.offset + sni.length
			if (a > (lastOrNull() ?: 0) && a < size) add(a)
		}.distinct().sorted().filter { it in 1 until size }
		var off = 0
		for ((i, pos) in splits.withIndex()) {
			val len = pos - off
			if (len > 0) { out.write(data, off, len); out.flush(); off = pos; if (i < splits.size - 1) delay() }
		}
		if (off < size) { out.write(data, off, size - off); out.flush() }
	}

	private fun simpleSplit(data: ByteArray, size: Int, out: OutputStream) {
		if (size <= 2) { out.write(data, 0, size); out.flush(); return }
		out.write(data, 0, 1); out.flush(); delay()
		out.write(data, 1, size - 1); out.flush()
	}

	private fun applyDesync(headers: String): String = headers
		.replaceFirst("Host:", mixCase("Host") + ":")
		.replaceFirst("host:", mixCase("host") + ":")
		.replace(Regex("(?i)(Host:\\s*)([^\\r\\n]+)")) { m ->
			val h = m.groupValues[2].trimEnd()
			"${m.groupValues[1]} ${if (!h.contains(':') && !h.endsWith('.')) "$h." else h}"
		}

	private fun findSni(data: ByteArray, size: Int): SniInfo? {
		if (size < 9 || data[0] != 0x16.toByte() || data[1] != 0x03.toByte()) return null
		val recLen = u16(data, 3)
		if (recLen < 42 || 5 + recLen > size) return null
		var p = 5
		if (data[p].toInt() and 0xFF != 0x01) return null
		p += 38
		if (p >= size) return null
		p += 1 + (data[p].toUByte().toInt())
		if (p + 2 > size) return null
		p += 2 + u16(data, p)
		if (p >= size) return null
		p += 1 + (data[p].toUByte().toInt())
		if (p + 2 > size) return null
		val extLen = u16(data, p)
		p += 2
		val extEnd = p + extLen
		if (extEnd > size) return null
		while (p + 4 <= extEnd) {
			val t = u16(data, p)
			val l = u16(data, p + 2)
			val dStart = p + 4
			if (dStart + l > extEnd) return null
			if (t == 0x0000 && dStart + 2 <= dStart + l) {
				var q = dStart + 2
				while (q + 3 <= dStart + l) {
					val nLen = u16(data, q + 1)
					if (data[q].toInt() and 0xFF == 0x00 && q + 3 + nLen <= dStart + l)
						return SniInfo(q + 3, nLen)
					q += 3 + nLen
				}
			}
			p = dStart + l
		}
		return null
	}

	private fun isTlsHello(data: ByteArray, size: Int) =
		size >= 9 && data[0] == 0x16.toByte() && data[1] == 0x03.toByte() && data[5].toInt() and 0xFF == 0x01

	private fun delay() = Thread.sleep(Random.nextLong(30L, 81L))

	private fun mixCase(s: String): String {
		val r = s.map { if (Random.nextBoolean()) it.uppercaseChar() else it.lowercaseChar() }.joinToString("")
		return if (r == s) s.replaceFirstChar { it.lowercaseChar() } else r
	}

	private data class SniInfo(val offset: Int, val length: Int)

	companion object {
		private const val MAX_HELLO = 32 * 1024
		private const val MIN_FRAG = 4
		private val METHOD = arrayOf("GET", "POST", "HEAD", "PUT", "DELETE", "OPTIONS", "CONNECT", "PATCH")
	}
}

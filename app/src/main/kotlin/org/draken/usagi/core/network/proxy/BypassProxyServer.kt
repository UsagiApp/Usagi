package org.draken.usagi.core.network.proxy

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.Cache
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.draken.usagi.core.exceptions.ProxyConfigException
import org.draken.usagi.core.network.DoHManager
import org.draken.usagi.core.network.DoHProvider
import org.draken.usagi.core.prefs.AppSettings
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.ConnectException
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ProtocolException
import java.net.ServerSocket
import java.net.Socket
import java.net.UnknownHostException
import kotlin.math.min
import kotlin.random.Random

class BypassProxyServer(settings: AppSettings, cache: Cache) {
	private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
	private val googleDns = DoHManager(cache, settings, false, DoHProvider.GOOGLE)
	private val cloudflareDns = DoHManager(cache, settings, false, DoHProvider.CLOUDFLARE)
	private val strictDns = DoHManager(cache, settings, false)
	private val lock = Any()

	@Volatile
	private var serverSocket: ServerSocket? = null

	fun ensureStarted(): Int = synchronized(lock) {
		val active = serverSocket
		if (active != null && !active.isClosed) return active.localPort
		stopLocked()
		runCatching {
			val server = ServerSocket()
			server.reuseAddress = true
			server.bind(InetSocketAddress(0))
			serverSocket = server
			scope.launch { acceptLoop(server) }
			server.localPort
		}.getOrElse { throw ProxyConfigException() }
	}

	fun stopIfRunning() = synchronized(lock) { stopLocked() }

	private fun stopLocked() {
		serverSocket?.runCatching { close() }
		serverSocket = null
	}

	private fun acceptLoop(server: ServerSocket) {
		while (scope.isActive && !server.isClosed) {
			val client = runCatching { server.accept() }.getOrElse {
				if (server.isClosed) return
				null
			} ?: continue
			if (!client.inetAddress.isLoopbackAddress) {
				client.runCatching { close() }
				continue
			}
			scope.launch { handleClient(client) }
		}
	}

	private suspend fun handleClient(client: Socket) {
		client.use { s ->
			runCatching {
				s.tcpNoDelay = true
				s.soTimeout = 0
				val inp = BufferedInputStream(s.getInputStream())
				val out = BufferedOutputStream(s.getOutputStream())
				val req = readHttpRequest(inp) ?: return
				val parts = req.requestLine.split(' ', limit = 3)
				if (parts.size < 2) return
				val method = parts[0].uppercase()
				if (method == "CONNECT") {
					handleConnect(parts[1], inp, out)
				} else {
					handleHttp(method, parts[1], parts.getOrNull(2) ?: "HTTP/1.1", req, inp, out)
				}
			}
		}
	}

	private suspend fun handleConnect(target: String, clientIn: InputStream, clientOut: OutputStream) {
		val url = "https://$target".toHttpUrlOrNull() ?: return
		val remote = runCatching { connectRemote(url.host, url.port) }.getOrElse {
			runCatching {
				clientOut.write(httpResponse(503))
				clientOut.flush()
			}
			return
		}
		remote.use { s ->
			s.soTimeout = 0
			val remoteIn = BufferedInputStream(s.getInputStream())
			val remoteOut = s.getOutputStream()
			clientOut.write(httpResponse(200))
			clientOut.flush()
			coroutineScope {
				val down = launch(Dispatchers.IO) { pipe(remoteIn, clientOut) }
				try {
					pipeBypassed(clientIn, remoteOut)
				} finally {
					s.runCatching { close() }
				}
				down.join()
			}
		}
	}

	private fun handleHttp(
		method: String, target: String, version: String,
		req: HttpRequest, clientIn: InputStream, clientOut: OutputStream,
	) {
		val url = target.toHttpUrlOrNull() ?: run {
			val host = req.header("Host") ?: throw ProtocolException()
			"http://$host$target".toHttpUrlOrNull() ?: throw ProtocolException()
		}
		connectRemote(url.host, url.port).use { remote ->
			remote.soTimeout = READ_TIMEOUT
			val remoteIn = BufferedInputStream(remote.getInputStream())
			val remoteOut = BufferedOutputStream(remote.getOutputStream())
			writeHttpRequest(method, version, url, req, remoteOut)
			val bodyLen = req.header("Content-Length")?.trim()?.toLongOrNull()
			if (bodyLen != null && bodyLen > 0L) copyExact(clientIn, remoteOut, bodyLen)
			remoteOut.flush()
			pipe(remoteIn, clientOut)
		}
	}

	private fun writeHttpRequest(
		method: String, version: String, url: HttpUrl, req: HttpRequest, out: OutputStream,
	) {
		val sb = StringBuilder(512)
			.append(method).append(' ').append(requestPath(url)).append(' ').append(version).append("\r\n")
			.append("Host: ").append(hostHeader(url)).append("\r\n")
		req.headers
			.filterNot { it.name.equals("Host", true) || it.name.equals("Connection", true) || it.name.equals("Proxy-Connection", true) }
			.forEach { sb.append(it.name).append(": ").append(it.value).append("\r\n") }
		sb.append("Connection: close\r\n\r\n")
		out.write(applyHttpDesync(sb.toString()).toByteArray(Charsets.ISO_8859_1))
	}

	private fun pipeBypassed(clientIn: InputStream, remoteOut: OutputStream) {
		val buf = ByteArray(BUF_SIZE)
		var first = true
		while (true) {
			val n = try { clientIn.read(buf) } catch (_: IOException) { return }
			if (n <= 0) return
			if (first) {
				first = false
				val packet = accumulate(clientIn, buf, n)
				writeBypassedChunk(packet, packet.size, remoteOut)
			} else {
				remoteOut.write(buf, 0, n)
				remoteOut.flush()
			}
		}
	}

	private fun accumulate(input: InputStream, buf: ByteArray, count: Int): ByteArray {
		if (count < 5 || buf[0] != 0x16.toByte() || buf[1] != 0x03.toByte()) return buf.copyOf(count)
		val total = (u16(buf, 3) + 5).coerceAtMost(MAX_HELLO)
		if (count >= total) return buf.copyOf(count)
		val baos = ByteArrayOutputStream(total)
		baos.write(buf, 0, count)
		val tmp = ByteArray(BUF_SIZE)
		var waited = 0L
		while (baos.size() < total && waited < 150L) {
			val avail = input.available()
			if (avail <= 0) { Thread.sleep(8L); waited += 8L; continue }
			val r = input.read(tmp, 0, min(tmp.size, min(avail, total - baos.size())))
			if (r <= 0) break
			baos.write(tmp, 0, r)
		}
		return baos.toByteArray()
	}

	private fun connectRemote(host: String, port: Int): Socket {
		val addrs = resolveHost(host)
		if (addrs.isEmpty()) throw UnknownHostException(host)
		val ordered = addrs.sortedBy { it is Inet6Address }
		var last: IOException? = null
		for (addr in ordered) {
			val s = Socket()
			try {
				s.tcpNoDelay = true
				s.connect(InetSocketAddress(addr, port), CONNECT_TIMEOUT)
				return s
			} catch (e: IOException) {
				last = e
				s.runCatching { close() }
			}
		}
		throw last ?: ConnectException("$host:$port")
	}

	private fun resolveHost(host: String): List<InetAddress> {
		for (resolver in arrayOf(googleDns, cloudflareDns, strictDns)) {
			runCatching {
				val addrs = resolver.lookup(host).filterNot { it.isLoopbackAddress || it.isAnyLocalAddress || it.isLinkLocalAddress }
				if (addrs.isNotEmpty()) return addrs
			}
		}
		runCatching {
			val addrs = InetAddress.getAllByName(host).filter { !it.isLoopbackAddress && !it.isAnyLocalAddress && !it.isLinkLocalAddress }
			if (addrs.isNotEmpty()) return addrs
		}
		return emptyList()
	}

	private fun readHttpRequest(input: InputStream): HttpRequest? {
		val raw = ByteArray(MAX_HDR)
		var n = 0
		var st = 0
		while (n < raw.size) {
			val b = input.read()
			if (b < 0) return null
			raw[n++] = b.toByte()
			st = when {
				st == 0 && b == '\r'.code -> 1
				st == 1 && b == '\n'.code -> 2
				st == 2 && b == '\r'.code -> 3
				st == 3 && b == '\n'.code -> 4
				else -> 0
			}
			if (st == 4) break
		}
		if (st != 4) throw EOFException()
		val lines = String(raw, 0, n, Charsets.ISO_8859_1).split("\r\n")
		val reqLine = lines.firstOrNull()?.takeIf { it.isNotBlank() } ?: return null
		val headers = lines.drop(1).takeWhile { it.isNotEmpty() }.mapNotNull { line ->
			val sep = line.indexOf(':')
			if (sep <= 0) null else HttpHeader(line.substring(0, sep), line.substring(sep + 1).trim())
		}
		return HttpRequest(reqLine, headers)
	}

	private fun pipe(input: InputStream, output: OutputStream) {
		val buf = ByteArray(BUF_SIZE)
		while (true) {
			val n = runCatching { input.read(buf) }.getOrElse { return }
			if (n <= 0) return
			runCatching { output.write(buf, 0, n); output.flush() }.getOrElse { return }
		}
	}

	private fun copyExact(input: InputStream, output: OutputStream, len: Long) {
		var rem = len
		val buf = ByteArray(BUF_SIZE)
		while (rem > 0L) {
			val n = input.read(buf, 0, min(buf.size.toLong(), rem).toInt())
			if (n <= 0) throw EOFException()
			output.write(buf, 0, n)
			rem -= n
		}
	}

	private fun requestPath(url: HttpUrl): String {
		val p = url.encodedPath.ifEmpty { "/" }
		return url.encodedQuery?.let { "$p?$it" } ?: p
	}

	private fun hostHeader(url: HttpUrl): String {
		val def = if (url.scheme == "https") 443 else 80
		return if (url.port == def) url.host else "${url.host}:${url.port}"
	}

	private fun u16(d: ByteArray, o: Int) = ((d[o].toInt() and 0xFF) shl 8) or (d[o + 1].toInt() and 0xFF)

	/* Bypass, main */
	private fun writeBypassedChunk(data: ByteArray, size: Int, out: OutputStream) {
		if (size <= 0) return
		if (isTlsHello(data, size)) {
			val sni = findSni(data, size)
			if (sni != null) writeTlsFragmented(data, size, sni, out)
			else simpleSplit(data, size, out)
		} else {
			simpleSplit(data, size, out)
		}
	}

	private fun writeTlsFragmented(data: ByteArray, size: Int, sni: SniInfo, out: OutputStream) {
		val payloadLen = u16(data, 3)
		val recEnd = 5 + payloadLen
		if (recEnd > size) { tcpSplit(data, size, sni, out); return }

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

	private fun tcpSplit(data: ByteArray, size: Int, sni: SniInfo, out: OutputStream) {
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

	private fun applyHttpDesync(headers: String): String = headers
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
	private data class HttpHeader(val name: String, val value: String)
	private data class HttpRequest(val requestLine: String, val headers: List<HttpHeader>) {
		fun header(name: String) = headers.firstOrNull { it.name.equals(name, ignoreCase = true) }?.value
	}

	companion object {
		private const val MAX_HDR = 32 * 1024
		private const val BUF_SIZE = 8192
		private const val CONNECT_TIMEOUT = 10_000
		private const val READ_TIMEOUT = 30_000
		private const val MAX_HELLO = 32 * 1024
		private const val MIN_FRAG = 4
		private fun httpResponse(code: Int) = "HTTP/1.1 $code\r\n\r\n".toByteArray(Charsets.ISO_8859_1)
	}
}

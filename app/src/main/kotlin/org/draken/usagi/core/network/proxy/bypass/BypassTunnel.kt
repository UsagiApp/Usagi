package org.draken.usagi.core.network.proxy.bypass

import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.Socket
import java.net.SocketAddress
import java.nio.channels.SocketChannel

class BypassTunnel(private val implSocket: Socket) : Socket() {
	override fun connect(endpoint: SocketAddress?) = implSocket.connect(endpoint)
	override fun connect(endpoint: SocketAddress?, timeout: Int) = implSocket.connect(endpoint, timeout)
	override fun bind(bindpoint: SocketAddress?) = implSocket.bind(bindpoint)
	override fun getInetAddress(): InetAddress? = implSocket.inetAddress
	override fun getLocalAddress(): InetAddress? = implSocket.localAddress
	override fun getPort(): Int = implSocket.port
	override fun getLocalPort(): Int = implSocket.localPort
	override fun getRemoteSocketAddress(): SocketAddress? = implSocket.remoteSocketAddress
	override fun getLocalSocketAddress(): SocketAddress? = implSocket.localSocketAddress
	override fun getChannel(): SocketChannel? = implSocket.channel
	override fun getInputStream(): InputStream = implSocket.getInputStream()
	override fun getOutputStream(): OutputStream = BypassStream(implSocket.getOutputStream())
	override fun setTcpNoDelay(on: Boolean) { implSocket.tcpNoDelay = on }
	override fun getTcpNoDelay(): Boolean = implSocket.tcpNoDelay
	override fun setSoLinger(on: Boolean, linger: Int) { implSocket.setSoLinger(on, linger) }
	override fun getSoLinger(): Int = implSocket.soLinger
	override fun sendUrgentData(data: Int) = implSocket.sendUrgentData(data)
	override fun setOOBInline(on: Boolean) { implSocket.oobInline = on }
	override fun getOOBInline(): Boolean = implSocket.oobInline
	override fun setSoTimeout(timeout: Int) { implSocket.soTimeout = timeout }
	override fun getSoTimeout(): Int = implSocket.soTimeout
	override fun setSendBufferSize(size: Int) { implSocket.sendBufferSize = size }
	override fun getSendBufferSize(): Int = implSocket.sendBufferSize
	override fun setReceiveBufferSize(size: Int) { implSocket.receiveBufferSize = size }
	override fun getReceiveBufferSize(): Int = implSocket.receiveBufferSize
	override fun setKeepAlive(on: Boolean) { implSocket.keepAlive = on }
	override fun getKeepAlive(): Boolean = implSocket.keepAlive
	override fun setTrafficClass(tc: Int) { implSocket.trafficClass = tc }
	override fun getTrafficClass(): Int = implSocket.trafficClass
	override fun setReuseAddress(on: Boolean) { implSocket.reuseAddress = on }
	override fun getReuseAddress(): Boolean = implSocket.reuseAddress
	override fun close() = implSocket.close()
	override fun shutdownInput() = implSocket.shutdownInput()
	override fun shutdownOutput() = implSocket.shutdownOutput()
	override fun toString(): String = implSocket.toString()
	override fun isConnected(): Boolean = implSocket.isConnected
	override fun isBound(): Boolean = implSocket.isBound
	override fun isClosed(): Boolean = implSocket.isClosed
	override fun isInputShutdown(): Boolean = implSocket.isInputShutdown
	override fun isOutputShutdown(): Boolean = implSocket.isOutputShutdown
}

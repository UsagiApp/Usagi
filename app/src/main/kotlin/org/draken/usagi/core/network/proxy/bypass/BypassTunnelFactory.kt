package org.draken.usagi.core.network.proxy.bypass

import org.draken.usagi.core.prefs.AppSettings
import java.net.InetAddress
import java.net.Socket
import javax.inject.Inject
import javax.inject.Singleton
import javax.net.SocketFactory

@Singleton
class BypassTunnelFactory @Inject constructor(
	private val settings: AppSettings,
) : SocketFactory() {
	private val delegate = getDefault()

	override fun createSocket(): Socket {
		val socket = delegate.createSocket()
		return if (settings.isProxyBypassEnabled) BypassTunnel(socket) else socket
	}

	override fun createSocket(host: String, port: Int): Socket {
		val socket = delegate.createSocket(host, port)
		return if (settings.isProxyBypassEnabled) BypassTunnel(socket) else socket
	}

	override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket {
		val socket = delegate.createSocket(host, port, localHost, localPort)
		return if (settings.isProxyBypassEnabled) BypassTunnel(socket) else socket
	}

	override fun createSocket(address: InetAddress, port: Int): Socket {
		val socket = delegate.createSocket(address, port)
		return if (settings.isProxyBypassEnabled) BypassTunnel(socket) else socket
	}

	override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress, localPort: Int): Socket {
		val socket = delegate.createSocket(address, port, localAddress, localPort)
		return if (settings.isProxyBypassEnabled) BypassTunnel(socket) else socket
	}
}

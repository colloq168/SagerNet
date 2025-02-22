/******************************************************************************
 *                                                                            *
 * Copyright (C) 2021 by nekohasekai <sekai@neko.services>                    *
 * Copyright (C) 2021 by Max Lv <max.c.lv@gmail.com>                          *
 * Copyright (C) 2021 by Mygod Studio <contact-shadowsocks-android@mygod.be>  *
 *                                                                            *
 * This program is free software: you can redistribute it and/or modify       *
 * it under the terms of the GNU General Public License as published by       *
 * the Free Software Foundation, either version 3 of the License, or          *
 *  (at your option) any later version.                                       *
 *                                                                            *
 * This program is distributed in the hope that it will be useful,            *
 * but WITHOUT ANY WARRANTY; without even the implied warranty of             *
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the              *
 * GNU General Public License for more details.                               *
 *                                                                            *
 * You should have received a copy of the GNU General Public License          *
 * along with this program. If not, see <http://www.gnu.org/licenses/>.       *
 *                                                                            *
 ******************************************************************************/

package io.nekohasekai.sagernet.tun

import android.system.ErrnoException
import io.nekohasekai.sagernet.PacketStrategy
import io.nekohasekai.sagernet.bg.VpnService
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ktx.*
import io.nekohasekai.sagernet.tun.ip.*
import io.netty.buffer.ByteBuf
import io.netty.channel.epoll.EpollDatagramChannel
import io.netty.channel.epoll.EpollEventLoopGroup
import io.netty.channel.epoll.EpollServerSocketChannel
import io.netty.channel.epoll.EpollSocketChannel
import io.netty.channel.unix.FileDescriptor
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.io.IOException
import kotlin.random.Random

class DirectTunThread(val service: VpnService) : Thread("TUN Thread") {

    companion object {
        init {
            System.loadLibrary("netty_transport_native_epoll")
        }
    }

    private var fd = 0

    @Volatile
    var running = true
    lateinit var descriptor: FileDescriptor
    val closed get() = service.data.proxy?.closed == true

    override fun interrupt() {
        super.interrupt()
        tcpForwarder.destroy()
        udpForwarder.destroy()
    }

    val socksPort = DataStore.socksPort
    val dnsPort = DataStore.localDNSPort
    val multiThreadForward = DataStore.multiThreadForward
    val uidDumper = UidDumper(multiThreadForward)
    val uidMap = service.data.proxy?.config?.uidMap ?: emptyMap()
    val enableLog = DataStore.enableLog
    val dumpUid = enableLog || uidMap.isNotEmpty()

    val eventLoop = EpollEventLoopGroup()
    val serverSocketChannelClazz = EpollServerSocketChannel::class.java
    val socketChannelClazz = EpollSocketChannel::class.java
    val datagramChannelClazz = EpollDatagramChannel::class.java

    val tcpForwarder = DirectTcpForwarder(this)
    val udpForwarder = DirectUdpForwarder(this)

    fun write(buffer: ByteBuf, length: Int) {
        descriptor.write(buffer.internalNioBuffer(0, length), 0, length)
    }

    override fun run() {
        descriptor = FileDescriptor(service.conn.fd)
        tcpForwarder.start()

        runBlocking {
            if (!multiThreadForward) {
                loopPacketsSingleThread()
            } else {
                loopPacketsMultiThread()
            }
        }
    }

    suspend fun loopPacketsSingleThread() {
        val alloc = tcpForwarder.channelFuture.channel().alloc()
        val buffer = alloc.directBuffer(VpnService.VPN_MTU)
        val bufferNio = buffer.internalNioBuffer(0, VpnService.VPN_MTU)
        do {
            try {
                buffer.clear()
                val length = descriptor.read(bufferNio, 0, VpnService.VPN_MTU)
                if (length < 20) continue
                processPacket(buffer, length)
            } catch (e: IOException) {
                running = false
                interrupt()
                break
            } catch (e: Throwable) {
                running = false
                Logs.w(e)
                interrupt()
                break
            }
        } while (running)
        buffer.release()
    }

    suspend fun loopPacketsMultiThread() {
        val alloc = tcpForwarder.channelFuture.channel().alloc()
        do {
            try {
                val buffer = alloc.directBuffer(VpnService.VPN_MTU)
                val length = descriptor.read(
                    buffer.internalNioBuffer(0, VpnService.VPN_MTU), 0, VpnService.VPN_MTU
                )
                if (length < 20) continue
                runOnDefaultDispatcher {
                    try {
                        processPacket(buffer, length)
                        buffer.release()
                    } catch (e: IOException) {
                        running = false
                        interrupt()
                    } catch (e: Throwable) {
                        running = false
                        Logs.w(e)
                        interrupt()
                    }
                }
            } catch (e: IOException) {
                running = false
                interrupt()
                break
            } catch (e: Throwable) {
                running = false
                Logs.w(e)
                interrupt()
                break
            }
        } while (running)
    }

    suspend fun processPacket(buffer: ByteBuf, length: Int) {
        try {

            val ipHeader = when (val ipVersion = buffer.getUnsignedByte(0).toInt() ushr 4) {
                4 -> DirectIPv4Header(buffer, length)
                6 -> DirectIPv6Header(buffer, length)
                else -> {
                    processOther(buffer, length)
                    return
                }
            }

            when (val protocol = ipHeader.protocol) {
                IPPROTO_TCP -> {
                    tcpForwarder.processTcp(buffer, ipHeader)
                }
                IPPROTO_UDP -> {
                    udpForwarder.processUdp(buffer, ipHeader)
                }
                IPPROTO_ICMP -> {
                    processICMP(buffer, ipHeader)
                }
                IPPROTO_ICMPv6 -> {
                    processICMPv6(buffer, ipHeader)
                }
                else -> {
                    processOther(buffer, length)
                }
            }
        } catch (e: InterruptedException) {
            interrupt()
            running = false
            return
        } catch (e: ErrnoException) {
            Logs.w(e)
            interrupt()
            running = false
            return
        } catch (e: Throwable) {
            Logs.w(e)
        }
    }

    val icmpEchoStrategy = DataStore.icmpEchoStrategy
    val icmpEchoReplyDelay = DataStore.icmpEchoReplyDelay

    private fun mkDelay(): Long {
        return when {
            icmpEchoReplyDelay <= 0 -> icmpEchoReplyDelay
            Random.nextInt(30) == 0 -> icmpEchoReplyDelay + Random.nextInt(30, 100)
            else -> icmpEchoReplyDelay + Random.nextInt(-10, 10)
        }
    }

    suspend fun processICMP(buffer: ByteBuf, ipHeader: DirectIPHeader) {

        val icmpHeader = DirectICMPHeader(ipHeader)
        if (icmpHeader.type == 8) {
            when (icmpEchoStrategy) {
                PacketStrategy.DIRECT -> write(buffer, ipHeader.packetLength)
                PacketStrategy.DROP -> return
                PacketStrategy.REPLY -> {
                    ipHeader.revertAddress()
                    icmpHeader.revertEcho()
                    delay(mkDelay())
                    write(buffer, ipHeader.packetLength)
                }
            }
        } else {
            processOther(buffer, ipHeader.packetLength)
        }

    }

    suspend fun processICMPv6(buffer: ByteBuf, ipHeader: DirectIPHeader) {

        val icmpHeader = DirectICMPv6Header(ipHeader)
        if (icmpHeader.type == 128) {
            when (icmpEchoStrategy) {
                PacketStrategy.DIRECT -> write(buffer, ipHeader.packetLength)
                PacketStrategy.DROP -> return
                PacketStrategy.REPLY -> {
                    ipHeader.revertAddress()
                    icmpHeader.revertEcho()
                    delay(mkDelay())
                    write(buffer, ipHeader.packetLength)
                }
            }
        } else {
            processOther(buffer, ipHeader.packetLength)
        }

    }

    val ipOtherStrategy = DataStore.ipOtherStrategy

    fun processOther(buffer: ByteBuf, length: Int) {
        when (ipOtherStrategy) {
            PacketStrategy.DIRECT -> write(buffer, length)
            PacketStrategy.DROP -> return
        }
    }

}
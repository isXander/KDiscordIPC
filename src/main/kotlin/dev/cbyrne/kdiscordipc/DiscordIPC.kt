/*
 *     KDiscordIPC is a library for interacting with the discord client via IPC
 *     Copyright (C) 2021  Conor Byrne
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package dev.cbyrne.kdiscordipc

import dev.cbyrne.kdiscordipc.event.DiscordEvent
import dev.cbyrne.kdiscordipc.exceptions.SocketConnectionException
import dev.cbyrne.kdiscordipc.exceptions.SocketDisconnectionException
import dev.cbyrne.kdiscordipc.listener.IPCListener
import dev.cbyrne.kdiscordipc.packet.Packet
import dev.cbyrne.kdiscordipc.packet.PacketDirection
import dev.cbyrne.kdiscordipc.packet.impl.DispatchPacket
import dev.cbyrne.kdiscordipc.packet.impl.SetActivityPacket
import dev.cbyrne.kdiscordipc.packet.impl.serverbound.HandshakePacket
import dev.cbyrne.kdiscordipc.presence.DiscordPresence
import dev.cbyrne.kdiscordipc.socket.DiscordSocket
import dev.cbyrne.kdiscordipc.socket.SocketListener

/**
 * A class for interacting with Discord via IPC
 *
 * The [presence] variable can be set at any point in time, as long as it is not when the socket has been previously opened, but is now closed.
 * If [presence] is set before [connect] is called, the [SetActivityPacket] will be sent automatically when [DiscordEvent.Ready] is dispatched.
 *
 * @see [onReadyEvent]
 * @param applicationId The ID of your application created on <a href=https://discordapp.com/developers/applications/me>the discord dev portal</a>.
 */
class DiscordIPC(private var applicationId: String) : SocketListener, IPCListener {
    constructor(applicationId: Long) : this(applicationId.toString())

    /**
     * An instance of [DiscordSocket] used to send and receive packets
     *
     * @see connect
     * @see disconnect
     * @see sendPacket
     */
    private val socket = DiscordSocket()

    /**
     * The [IPCListener] instance which will be listening for events
     *
     * @see onPacket
     */
    var listener: IPCListener? = null

    /**
     * The rich presence instance to send to the client
     *
     * Once this variable is set, as long as the socket is open, the presence will be sent via [SetActivityPacket]
     * If this variable is set before [connect] is called, the packet will be sent once [DiscordEvent.Ready] is dispatched.
     *
     * @see sendPacket
     * @see SetActivityPacket
     * @see onReadyEvent
     */
    var presence: DiscordPresence? = null
        set(value) {
            field = if (socket.isConnected && value != null) {
                sendPacket(SetActivityPacket(value))
                null
            } else {
                value
            }
        }

    /**
     * Connects to the Discord IPC socket via [DiscordSocket].
     * Once this method is called, a thread will be spawned, which will be listening for packets until the connection dies.
     *
     * @see DiscordSocket
     * @see DiscordSocket.connect
     *
     * @throws SocketConnectionException If an error has occurred during the connection
     */
    @Throws(SocketConnectionException::class)
    fun connect() {
        socket.listener = this

        socket.connect()
        sendPacket(HandshakePacket(applicationId))
    }

    /**
     * Closes the connection with the Discord IPC socket
     *
     * @throws IllegalStateException If the socket is already closed
     * @throws SocketDisconnectionException If an error has occurred when closing this socket
     */
    @Throws(IllegalStateException::class, SocketDisconnectionException::class)
    fun disconnect() {
        if (!socket.isConnected) throw IllegalStateException("This socket is already closed!")

        socket.disconnect()
    }

    /**
     * Sends a [Packet] to the Discord Client via the IPC socket
     *
     * @see Packet
     * @see HandshakePacket
     * @param packet The packet to send
     *
     * @throws IllegalStateException If the socket is not connected yet, or if the packet is of the wrong [PacketDirection]
     * @see PacketDirection
     */
    fun sendPacket(packet: Packet) {
        if (packet.direction == PacketDirection.CLIENTBOUND)
            throw IllegalStateException("You can not send a clientbound packet to the server!")

        socket.send(packet)
    }

    /**
     * Fired when [DiscordEvent.Ready] is received by the client
     * @param event A class containing all relevant info: user info, config, environment, etc.
     */
    override fun onReadyEvent(event: DiscordEvent.Ready) {
        presence?.let {
            sendPacket(SetActivityPacket(it))
        }

        presence = null
    }

    /**
     * Fired when a packet is received and decoded by [DiscordSocket]
     * @param packet The packet instance
     */
    override fun onPacket(packet: Packet) {
        when (packet) {
            is DispatchPacket -> {
                if (packet.event != null) {
                    val event = DiscordEvent.from(packet.event, packet.eventData)

                    when (event) {
                        is DiscordEvent.Ready -> {
                            listener?.onReadyEvent(event)
                            this.onReadyEvent(event)
                        }
                        is DiscordEvent.Error -> {
                            socket.disconnect()
                            listener?.onDisconnect(event.message)
                        }
                    }
                } else if (packet.packetData["cmd"]?.equals("SET_ACTIVITY") == true) {
                    listener?.onPacket(SetActivityPacket(DiscordPresence.fromNative(packet.eventData)))
                }
            }
        }

        listener?.onPacket(packet)
    }

    /**
     * Fired when the socket is closed
     *
     * @param message The reason for the socket being closed
     */
    override fun onSocketClosed(message: String) {
        listener?.onDisconnect(message)
    }
}

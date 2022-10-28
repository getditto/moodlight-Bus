package live.ditto.moodlight

import android.util.Log
import com.couchbase.lite.*
import kotlinx.coroutines.*
import live.ditto.*
import java.lang.Exception
import java.math.BigInteger
import java.util.concurrent.Semaphore

private const val TAG = "DittoBridge"

open class DataManager(
    private val ditto: Ditto,
    private val database: Database
) : DittoBusCallback, DittoBusStreamCallback {
    private var dittoBus: DittoBus = DittoExperimental.busFor(ditto)
    private var cbListener: MessageEndpointListener = MessageEndpointListener(
        MessageEndpointListenerConfiguration(
            database,
            ProtocolType.MESSAGE_STREAM
        )
    )
    private var streams: MutableMap<DittoAddress, DittoBusStream?> = mutableMapOf()
    private var activePeerManagers: MutableMap<DittoAddress, ActivePeerManager> = mutableMapOf()
    private var passivePeerManagers: MutableMap<DittoAddress, PassivePeerManager> = mutableMapOf()

    init {
        Database.log.console.domains = LogDomain.ALL_DOMAINS
        DittoLogger.minimumLogLevel = DittoLogLevel.DEBUG
        createObserver()
    }

    private fun createObserver() {
        this.dittoBus.callback = this
        ditto.observePeersV2 { remoteJson ->
            val remotePeers = DittoPeerV2Parser.parseJSON(remoteJson)

            // Find streams for peers that are gone
            val streamToRemove: MutableList<DittoAddress> = mutableListOf()
            this.streams.forEach { (key, _) ->
                if (!remotePeers.any { peer -> peer.address == key }) {
                    streamToRemove.add(key)
                }
            }

            // Remove and close streams
            for (stream in streamToRemove) {
                this.streams.remove(stream)?.let { removedStream ->
                    removedStream.close()
                }
            }

            // Now open new streams to peers who we have not tried yet
            remotePeers.forEach { peer ->

                if (!this.streams.containsKey(peer.address)) {
                    Log.d(TAG, "Opening new outgoing stream to  ${peer.deviceName}")
                    val placeHolder: DittoBusStream? = null
                    this.streams[peer.address] = placeHolder

                    // Check to prevent dual bidirectional connections between peers
                    // TODO: We should use networkId but it is only available for remote peers
                    if (peer.deviceName <= this.ditto.deviceName) {
                        this.dittoBus.openStreamToAddress(
                            peer.address, DittoBusReliability.Reliable
                        ) { stream, error ->
                            if (error != null) {
                                Log.e(
                                    TAG,
                                    "error opening stream to address ${peer.address} error ${error.localizedMessage}"
                                )
                                this.streams.remove(peer.address)
                            } else if (stream != null) {
                                stream.callback = this
                                Log.d(
                                    TAG,
                                    "opening stream with peer"
                                )
                                this.streams[peer.address] = stream
                                Log.d(
                                    TAG,
                                    "Peer registered"
                                )
                                val activePeerManager =
                                    ActivePeerManager(
                                        database,
                                        this.ditto.siteID.toString(),
                                        peer.address
                                    )
                                this.setupConnectionManager(activePeerManager, stream)
                                this.activePeerManagers[peer.address] = activePeerManager
                            }
                        }
                    }
                }
            }
        }
    }

    private fun setupConnectionManager(
        peerManager: ConnectionPeerManagerInterface,
        stream: DittoBusStream
    ) {
        peerManager.send = { data ->
            // When we enque data, we must wait until the enqueue delegate fires.
            // We use a combination of a serial queue (CB might be on concurrent queue)
            // and a semaphore that starts at value 1.
            //
            // First we dispatch on to the serial queue to order all the enqueue
            // requests. Then inside the block, we use a semaphore to wait on delegate.
            //
            // On each wait() call it will decrement by 1, and each signal()
            // increase by 1, so:
            // 1 -> wait() -> 0 -> data enqueued -> signal() -> 1 -> ready again!
            // 1 -> wait() -> 0 -> data enqueued -> wait() -> -1 -> thread blocked!
            //
            // This pattern means that because our enqueueing of data is in a serial
            // queue, if a subsequent attempt to enqueue happens before we get a
            // signal() call, we won't enqueue the data - ensuring correct backpressure.
            peerManager.semaphore.acquireUninterruptibly()
            stream.enqueueMessage(data)
        }
    }

    override fun dittoBusDidReceiveIncomingStreamFromPeer(
        busStream: DittoBusStream,
        peer: DittoAddress
    ) {
        Log.d(TAG, "dittoBusStream:didReceiveIncomingStream")
        busStream.callback = this
        streams[peer] = busStream
        val passivePeerManager = PassivePeerManager(database, peer)
        cbListener.accept(passivePeerManager.connection!!)
        this.setupConnectionManager(passivePeerManager, busStream)
        this.passivePeerManagers[peer] = passivePeerManager
    }

    override fun dittoBusDidReceiveSingleMessage(bus: DittoBus, message: DittoBusMessage) {
        //This not have implementation in the original code
        Log.d(TAG, "dittoBusStream:dittoBusDidReceiveSingleMessage")
    }

    override fun dittoBusStreamDidEnqueueDataWithMessageSequence(
        busStream: DittoBusStream,
        messageSequence: BigInteger,
        error: DittoError?
    ) {

        Log.d(TAG, "dittoBusStream: dittoBusStreamDidEnqueueDataWithMessageSequence")
        val stream = streams.filter { (_, stream) ->
            busStream.streamID == stream?.streamID
        }.map { it }.firstOrNull()?: return

        val activePeerManager =
            activePeerManagers.filter { (peer, _) -> peer == stream.key }.map { it }
                .firstOrNull()

        val passivePeerManager =
            passivePeerManagers.filter { (peer, _) -> peer == stream.key }.map { it }
                .firstOrNull()

        // When we enque data, we wait until this delegate callback fires
        // This is done via a semaphore that starts at value 1
        // On each wait() call it will decrement by 1, and each signal()
        // increase by 1, so:
        // 1 -> wait() -> 0 -> data enqueued -> signal() -> 1 -> ready again!
        // 1 -> wait() -> 0 -> data enqueued -> wait() -> -1 -> thread blocked!
        //
        // This pattern means that because our enqueueing of data is in a serial
        // queue, if a subsequent attempt to enqueue happens before we get a
        // signal() call, we won't enqueue the data - ensuring correct backpressure
        if (activePeerManager?.value != null) {
            activePeerManager.value.semaphore.release()
        } else {
            passivePeerManager?.value?.semaphore?.release()
        }

    }

    override fun dittoBusStreamDidClose(busStream: DittoBusStream, error: DittoError) {
        Log.d(TAG, "DittoBusStream:didClose")
        error.localizedMessage.let {
            Log.e(TAG, it)
        }
        var toRemoveAddress: DittoAddress? = null
        for ((dittoAddress, stream) in streams) {
            if (busStream.streamID == stream?.streamID) {
                toRemoveAddress = dittoAddress
            }
        }
        //Guard let equivalent
        val toRemoveAdd = toRemoveAddress.let { it } ?: return
        Log.d(TAG, "Eliminating closed outgoing stream $toRemoveAdd")

        streams.remove(toRemoveAdd)

        activePeerManagers.remove(toRemoveAdd)?.let {
            it.stopReplicationSync()
        }
        passivePeerManagers.remove(toRemoveAdd)?.let { peerManager ->
            peerManager.connection?.let { conn ->
                peerManager.stopReplicationSync()
                cbListener.close(conn)
            }
        }

    }

    override fun dittoBusStreamDidReceiveMessage(busStream: DittoBusStream, message: ByteArray) {
        Log.d(TAG, "dittoBusStream:didReceiveMessage: ${message.size}")
        val digestedMessage = Message.fromData(message)
        val stream = streams.filter { (_, stream) ->
            busStream.streamID == stream?.streamID
        }.map { it }.firstOrNull() ?: return

        val activePeerManager =
            activePeerManagers.filter { (peer, _) -> peer == stream.key }.map { it }
                .firstOrNull()

        val passivePeerManager =
            passivePeerManagers.filter { (peer, _) -> peer == stream.key }.map { it }
                .firstOrNull()

        if (activePeerManager != null) {
            activePeerManager.value.didReceive(digestedMessage)
        } else passivePeerManager?.value?.didReceive(digestedMessage)

    }

    override fun dittoBusStreamDidAcknowledgeReceipt(
        busStream: DittoBusStream,
        messageSequence: BigInteger
    ) {
        Log.d(TAG, "dittoBusStreamDidAcknowledgeReceipt")
    }

}

interface ConnectionPeerManagerInterface {
    var target: DittoAddress
    var send: ((ByteArray) -> Unit)?
    var semaphore: Semaphore
    var queue: CoroutineScope

    fun didReceive(message: Message)
    fun stopReplicationSync()
}

class ActivePeerManager(database: Database, uuid: String, override var target: DittoAddress) :
    ConnectionPeerManagerInterface, MessageEndpointDelegate {
    // Private Attributes
    private var replicator: Replicator? = null
    private var connection: MessageEndpointConnection? = null
    private var replicatorConnection: ReplicatorConnection? = null
    override var send: ((ByteArray) -> Unit)? = null
    override var semaphore: Semaphore = Semaphore(1)
    override var queue: CoroutineScope = CoroutineScope(Job() + Dispatchers.IO)

    init {
        this.setUpConnection(database, uuid, target)
    }

    private fun setUpConnection(database: Database, uuid: String, target: DittoAddress) {
        val id = "AP:${uuid.dropLast(10)}"
        val messageTarget = MessageEndpoint(id, target, ProtocolType.MESSAGE_STREAM, this)
        val config = ReplicatorConfiguration(database, messageTarget)
        config.isContinuous = true
        config.replicatorType = AbstractReplicatorConfiguration.ReplicatorType.PUSH_AND_PULL
        this.replicator?.addChangeListener { listener ->
            Log.d(TAG, "update from replicator ${listener.status.activityLevel}")
        }

        this.replicator = Replicator(config)
        this.replicator?.start()
    }

    companion object {
        fun equals(lhs: ActivePeerManager, rhs: ActivePeerManager): Boolean {
            return lhs.target == rhs.target
        }
    }

    override fun didReceive(message: Message) {
        this.replicatorConnection?.receive(message)
    }

    override fun stopReplicationSync() {
        this.connection?.close(null) {}
        this.replicator?.stop()
        this.replicatorConnection?.close(null)
    }


    override fun createConnection(endpoint: MessageEndpoint): MessageEndpointConnection {
        val connection = PeerConnection(this.queue)
        this.connection = connection
        connection.didConnect = { conn ->
            this.replicatorConnection = conn
        }
        connection.readyToSend = { data ->
            this.send?.invoke(data)
        }
        return connection
    }

}

class PassivePeerManager(database: Database, override var target: DittoAddress) :
    ConnectionPeerManagerInterface {

    var connection: MessageEndpointConnection? = null
    private var replicatorConnection: ReplicatorConnection? = null

    override var send: ((ByteArray) -> Unit)? = null
    override var semaphore: Semaphore = Semaphore(1)
    override var queue: CoroutineScope = CoroutineScope(Job() + Dispatchers.IO)


    init {
        this.setUpConnection(database)
    }

    private fun setUpConnection(database: Database) {
        val connection = PeerConnection(this.queue)
        this.connection = connection
        connection.didConnect = { conn ->
            this.replicatorConnection = conn
        }

        connection.readyToSend = { data ->
            this.send?.invoke(data)
        }
    }

    companion object {
        fun equals(lhs: PassivePeerManager, rhs: PassivePeerManager): Boolean {
            return lhs.target == rhs.target
        }
    }


    override fun didReceive(message: Message) {
        this.replicatorConnection?.receive(message)
    }

    override fun stopReplicationSync() {
        Log.d(TAG, "stopping replication")
        this.connection?.close(null) {}
        this.replicatorConnection?.close(null)
    }
}

class PeerConnection(private var queue: CoroutineScope) : MessageEndpointConnection {

    var didConnect: ((ReplicatorConnection) -> Unit)? = null
    var readyToSend: ((ByteArray) -> Unit)? = null

    override fun open(connection: ReplicatorConnection, completion: MessagingCompletion) {
        didConnect?.invoke(connection)
        completion.complete(true, null)
    }

    override fun close(error: Exception?, completion: MessagingCloseCompletion) {
        completion.complete()
    }

    override fun send(message: Message, completion: MessagingCompletion) {
        val data = message.toData()
        Log.d(TAG, "Sending data len ${data.size}")
        readyToSend?.invoke(data)
        completion.complete(true, null)
    }

}
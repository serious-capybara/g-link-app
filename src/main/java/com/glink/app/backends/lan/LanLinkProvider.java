/*
 * SPDX-FileCopyrightText: 2014 Albert Vaca Cintora <albertvaka@gmail.com>
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
*/
package com.glink.app.backends.lan;
import static android.content.pm.PackageManager.PERMISSION_GRANTED;
import static com.glink.app.helpers.NetworkHelperKt.isPrivateAddress;
import static com.glink.app.helpers.BoundedLineReaderKt.readLineBounded;

import android.Manifest;
import android.content.Context;
import android.net.Network;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.Pair;

import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;
import androidx.core.content.ContextCompat;

import org.json.JSONException;
import com.glink.app.backends.BaseLink;
import com.glink.app.backends.BaseLinkProvider;
import com.glink.app.DeviceHost;
import com.glink.app.DeviceInfo;
import com.glink.app.helpers.DeviceHelper;
import com.glink.app.helpers.security.SslHelper;
import com.glink.app.helpers.ThreadHelper;
import com.glink.app.helpers.TrustedDevices;
import com.glink.app.helpers.TrustedNetworkHelper;
import com.glink.app.NetworkPacket;
import com.glink.app.ui.CustomDevicesActivity;

import java.io.IOException;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import javax.net.SocketFactory;
import javax.net.ssl.SSLSocket;

import kotlin.text.Charsets;

/**
 * This LanLinkProvider creates {@link LanLink}s to other devices on the same
 * WiFi network. The first packet sent over a socket must be an
 * {@link DeviceInfo#toIdentityPacket()}.
 *
 * @see #identityPacketReceived(NetworkPacket, Socket, LanLink.ConnectionStarted, boolean)
 */
public class LanLinkProvider extends BaseLinkProvider {

    final static int UDP_PORT = 2064;
    final static int MIN_PORT = 2064;
    final static int MAX_PORT = 2128;
    final static int PAYLOAD_TRANSFER_MIN_PORT = 2096;

    final static int MAX_IDENTITY_PACKET_SIZE = 1024 * 512;
    final static int MAX_UDP_PACKET_SIZE = 1024 * 512;

    final static long MILLIS_DELAY_BETWEEN_CONNECTIONS_TO_SAME_DEVICE = 1000L;

    private final Context context;

    final HashMap<String, LanLink> visibleDevices = new HashMap<>(); // Links by device id

    final static int MAX_RATE_LIMIT_ENTRIES = 255;
    final ConcurrentHashMap<String, Long> lastConnectionTimeByDeviceId = new ConcurrentHashMap<>();
    final ConcurrentHashMap<InetAddress, Long> lastConnectionTimeByIp = new ConcurrentHashMap<>();

    private ServerSocket tcpServer;
    private DatagramSocket udpServer;

    private final MdnsDiscovery mdnsDiscovery;

    private long lastBroadcast = 0;
    private final static long delayBetweenBroadcasts = 200;

    // UDP is lossy and the first packet after a Wi-Fi (re)connect often hits a radio that is still waking up.
    // Instead of one shot we send a short burst; the receiver de-duplicates (rateLimitByDeviceId).
    private final static long[] BROADCAST_BURST_DELAYS_MS = {0, 150, 400};

    // Trailing-edge debounce: a network event that arrives inside the cooldown is NOT dropped, it is deferred.
    // (The "link properties ready" event is often the second one, and it is the one that carries a usable IP.)
    private final Handler debounceHandler = new Handler(Looper.getMainLooper());
    private final Object debounceLock = new Object();
    private boolean networkChangePending = false;
    @Nullable private Network pendingNetwork = null;

    // Discovery latency benchmark: set when discovery starts, logged when the first link comes up.
    private volatile long discoveryStartedAtNanos = 0;

    private boolean isStopped = true;

    public void onConnectionLost(BaseLink link) {
        String deviceId = link.getDeviceId();
        visibleDevices.remove(deviceId);
        super.onConnectionLost(link);
    }

    Pair<NetworkPacket, Boolean> unserializeReceivedIdentityPacket(String message) {
        NetworkPacket identityPacket;
        try {
            identityPacket = NetworkPacket.unserialize(message);
        } catch (JSONException e) {
            Log.w("GLink/LanLinkProvider", "Invalid identity packet received: " + e.getMessage());
            return null;
        }

        if (!DeviceInfo.isValidIdentityPacket(identityPacket)) {
            Log.w("GLink/LanLinkProvider", "Invalid identity packet received.");
            return null;
        }

        final String deviceId = identityPacket.getString("deviceId");
        String myId = DeviceHelper.getDeviceId(context);
        if (deviceId.equals(myId)) {
            //Ignore my own broadcast
            return null;
        }

        if (rateLimitByDeviceId(deviceId)) {
            Log.i("LanLinkProvider", "Discarding second packet from the same device " + deviceId + " received too quickly");
            return null;
        }

        boolean deviceTrusted = TrustedDevices.isTrustedDevice(context, deviceId);
        if (!deviceTrusted && !TrustedNetworkHelper.isTrustedNetwork(context)) {
            Log.i("GLink/LanLinkProvider", "Ignoring identity packet because the device is not trusted and I'm not on a trusted network.");
            return null;
        }

        return new Pair<>(identityPacket, deviceTrusted);
    }

    //They received my UDP broadcast and are connecting to me. The first thing they send should be their identity packet.
    @WorkerThread
    private void tcpPacketReceived(Socket socket) throws IOException, CertificateException {

        InetAddress address = socket.getInetAddress();

        if (!isPrivateAddress(address)) {
            Log.i("LanLinkProvider", "Discarding TCP packet from a non-local IP");
            return;
        }

        if (rateLimitByIp(address)) {
            Log.i("LanLinkProvider", "Discarding second TCP packet from the same ip " + address + " received too quickly");
            return;
        }

        String message;
        try {
            // We don't use a BufferedInputStream on purpose, since BufferedReader reads ahead and would require
            // us to keep a single BufferedInputStream instance and pass it around to make sure we don't lose data.
            // This means we are readying byte by byte directly from the OS, which is slow, but only for the handshake.
            message = readLineBounded(socket.getInputStream(), MAX_IDENTITY_PACKET_SIZE);
            //Log.e("TcpListener", "Received TCP packet: " + message);
        } catch (Exception e) {
            Log.e("GLink/LanLinkProvider", "Exception while receiving TCP packet", e);
            return;
        }

        final Pair<NetworkPacket, Boolean> pair = unserializeReceivedIdentityPacket(message);
        if (pair == null) {
            return;
        }
        final NetworkPacket identityPacket = pair.first;
        final boolean deviceTrusted = pair.second;

        Log.i("GLink/LanLinkProvider", "identity packet received from a TCP connection from " + identityPacket.getString("deviceName"));

        String targetDeviceId = identityPacket.getStringOrNull("targetDeviceId");
        Integer targetProtocolVersion = identityPacket.getIntOrNull("targetProtocolVersion");
        if (targetDeviceId != null && !targetDeviceId.equals(DeviceHelper.getDeviceId(context))) {
            Log.e("GLink/LanLinkProvider","Received a connection request for a device that isn't me: " + targetDeviceId);
            return;
        }
        if (targetProtocolVersion != null && targetProtocolVersion != DeviceHelper.PROTOCOL_VERSION) {
            Log.e("GLink/LanLinkProvider","Received a connection request for a protocol version that isn't mine: " + targetProtocolVersion);
            return;
        }

        identityPacketReceived(identityPacket, socket, LanLink.ConnectionStarted.Locally, deviceTrusted);
    }

    boolean rateLimitByIp(InetAddress address) {
        long now = System.currentTimeMillis();
        Long last = lastConnectionTimeByIp.get(address);
        if (last != null && (last + MILLIS_DELAY_BETWEEN_CONNECTIONS_TO_SAME_DEVICE > now)) {
            return true;
        }
        lastConnectionTimeByIp.put(address, now);
        if (lastConnectionTimeByIp.size() > MAX_RATE_LIMIT_ENTRIES) {
            lastConnectionTimeByIp.entrySet().removeIf(e -> e.getValue() + MILLIS_DELAY_BETWEEN_CONNECTIONS_TO_SAME_DEVICE < now);
        }
        return false;
    }

    boolean rateLimitByDeviceId(String deviceId) {
        long now = System.currentTimeMillis();
        Long last =  lastConnectionTimeByDeviceId.get(deviceId);
        if (last != null && (last + MILLIS_DELAY_BETWEEN_CONNECTIONS_TO_SAME_DEVICE > now)) {
            return true;
        }
        lastConnectionTimeByDeviceId.put(deviceId, now);
        if (lastConnectionTimeByDeviceId.size() > MAX_RATE_LIMIT_ENTRIES) {
            lastConnectionTimeByDeviceId.entrySet().removeIf(e -> e.getValue() + MILLIS_DELAY_BETWEEN_CONNECTIONS_TO_SAME_DEVICE < now);
        }
        return false;
    }

    //I've received their broadcast and should connect to their TCP socket and send my identity.
    @WorkerThread
    private void udpPacketReceived(DatagramPacket packet) {

        final InetAddress address = packet.getAddress();

        if (!isPrivateAddress(address)) {
            Log.i("LanLinkProvider", "Discarding UDP packet from a non-local IP");
            return;
        }

        if (rateLimitByIp(address)) {
            Log.i("LanLinkProvider", "Discarding second UDP packet from the same ip " + address + " received too quickly");
            return;
        }

        String message = new String(packet.getData(), Charsets.UTF_8);

        final Pair<NetworkPacket, Boolean> pair = unserializeReceivedIdentityPacket(message);
        if (pair == null) {
            return;
        }
        final NetworkPacket identityPacket = pair.first;
        final boolean deviceTrusted = pair.second;

        Log.i("GLink/LanLinkProvider", "Broadcast identity packet received from " + identityPacket.getString("deviceName"));

        int tcpPort = identityPacket.getInt("tcpPort", MIN_PORT);
        if (tcpPort < MIN_PORT || tcpPort > MAX_PORT) {
            Log.e("LanLinkProvider", "TCP port outside of glink's range");
            return;
        }

        Socket socket = null;
        try {
            socket = SocketFactory.getDefault().createSocket(address, tcpPort);
            configureSocket(socket);

            DeviceInfo myDeviceInfo = DeviceHelper.getDeviceInfo(context);
            NetworkPacket myIdentity = myDeviceInfo.toIdentityPacket();
            myIdentity.set("targetDeviceId", identityPacket.getString("deviceId"));
            myIdentity.set("targetProtocolVersion", identityPacket.getString("protocolVersion"));

            OutputStream out = socket.getOutputStream();
            out.write(myIdentity.serialize().getBytes());
            out.flush();

            identityPacketReceived(identityPacket, socket, LanLink.ConnectionStarted.Remotely, deviceTrusted);
        } catch (IOException | CertificateException | JSONException e) {
            Log.e("LanLinkProvider", "Exception receiving incoming UDP connection", e);
            if (socket != null) {
                try { socket.close(); } catch (IOException ignored) { }
            }
        }
    }

    private void configureSocket(Socket socket) {
        try {
            socket.setKeepAlive(true);
            // Small request/response packets must not wait for Nagle + delayed ACK (tens of ms each)
            socket.setTcpNoDelay(true);
        } catch (SocketException e) {
            Log.e("LanLink", "Exception", e);
        }
    }

    /**
     * Called when a new 'identity' packet is received. Those are passed here by
     * {@link #tcpPacketReceived(Socket)} and {@link #udpPacketReceived(DatagramPacket)}.
     * Should be called on a new thread since it blocks until the handshake is completed.
     *
     * @param identityPacket    identity of a remote device
     * @param socket            a new Socket, which should be used to receive packets from the remote device
     * @param connectionStarted which side started this connection
     * @param deviceTrusted     whether the packet comes from a trusted device
     */
    @WorkerThread
    private void identityPacketReceived(final NetworkPacket identityPacket, final Socket socket, final LanLink.ConnectionStarted connectionStarted, final boolean deviceTrusted) throws IOException, CertificateException {
        final String deviceId = identityPacket.getString("deviceId");
        final int protocolVersion = identityPacket.getInt("protocolVersion");

        if (deviceTrusted && isProtocolDowngrade(deviceId, protocolVersion)) {
            Log.w("GLink/LanLinkProvider", "Refusing to connect to a device using an older protocol version:" + protocolVersion);
            return;
        }

        if (deviceTrusted && !TrustedDevices.isCertificateStored(context, deviceId)) {
            Log.e("GLink/LanLinkProvider", "Device trusted but no cert stored. This should not happen.");
            return;
        }

        Log.i("GLink/LanLinkProvider", "Starting SSL handshake with " + deviceId + " trusted:" + deviceTrusted);

        // If I'm the TCP server I will be the SSL client and vice-versa.
        final boolean clientMode = (connectionStarted == LanLink.ConnectionStarted.Locally);
        final SSLSocket sslSocket = SslHelper.convertToSslSocket(context, socket, deviceId, deviceTrusted, clientMode);
        sslSocket.addHandshakeCompletedListener(event -> {
            // Start a new thread because some Android versions don't allow calling sslSocket.getOutputStream() from the callback
            ThreadHelper.execute(() -> {
                String mode = clientMode ? "client" : "server";
                try {
                    NetworkPacket secureIdentityPacket;
                    if (protocolVersion >= 8) {
                        DeviceInfo myDeviceInfo = DeviceHelper.getDeviceInfo(context);
                        NetworkPacket myIdentity = myDeviceInfo.toIdentityPacket();

                        OutputStream writer = sslSocket.getOutputStream();
                        writer.write(myIdentity.serialize().getBytes(Charsets.UTF_8));
                        writer.flush();
                        String line = readLineBounded(sslSocket.getInputStream(), MAX_IDENTITY_PACKET_SIZE);
                        // Do not trust the identity packet we received unencrypted
                        secureIdentityPacket = NetworkPacket.unserialize(line);
                        if (!DeviceInfo.isValidIdentityPacket(secureIdentityPacket)) {
                            Log.e("GLink/LanLinkProvider", "Identity packet isn't valid");
                            sslSocket.close();
                            return;
                        }
                        int newProtocolVersion = secureIdentityPacket.getInt("protocolVersion");
                        if (newProtocolVersion != protocolVersion) {
                            Log.e("GLink/LanLinkProvider", "Protocol version changed half-way through the handshake: " + protocolVersion + " -> " + newProtocolVersion);
                            sslSocket.close();
                            return;
                        }
                        String newDeviceId = secureIdentityPacket.getString("deviceId");
                        if (!newDeviceId.equals(deviceId)) {
                            Log.e("GLink/LanLinkProvider", "Device ID changed half-way through the handshake: " + deviceId + " -> " + newDeviceId);
                            sslSocket.close();
                            return;
                        }
                    } else {
                        secureIdentityPacket = identityPacket;
                    }
                    Certificate certificate = event.getPeerCertificates()[0];
                    DeviceInfo deviceInfo = DeviceInfo.fromIdentityPacketAndCert(secureIdentityPacket, certificate);
                    Log.i("GLink/LanLinkProvider", "Handshake as " + mode + " successful with " + deviceInfo.name + " secured with " + event.getCipherSuite());
                    addOrUpdateLink(sslSocket, deviceInfo);
                } catch (JSONException e) {
                    Log.e("GLink/LanLinkProvider", "Remote device doesn't correctly implement protocol version 8", e);
                    try { sslSocket.close(); } catch (IOException ignored) { }
                } catch (IOException e) {
                    Log.e("GLink/LanLinkProvider", "Handshake as " + mode + " failed with " + deviceId, e);
                    try { sslSocket.close(); } catch (IOException ignored) { }
                }
            });
        });

        //Handshake is blocking, so do it on another thread and free this thread to keep receiving new connection
        Log.d("LanLinkProvider", "Starting handshake");
        sslSocket.startHandshake();
        Log.d("LanLinkProvider", "Handshake done");
    }

    private boolean isProtocolDowngrade(String deviceId, int protocolVersion) {
        int lastKnownProtocolVersion = DeviceInfo.loadProtocolVersionFromSettings(context, deviceId);
        return lastKnownProtocolVersion > protocolVersion;
    }

    /**
     * Add or update a link in the {@link #visibleDevices} map.
     *
     * @param socket           a new Socket, which should be used to send and receive packets from the remote device
     * @param deviceInfo       remote device info
     * @throws IOException if an exception is thrown by {@link LanLink#reset(SSLSocket, DeviceInfo)}
     */
    @WorkerThread
    private void addOrUpdateLink(SSLSocket socket, DeviceInfo deviceInfo) throws IOException {
        LanLink link = visibleDevices.get(deviceInfo.id);
        if (link != null) {
            if (!link.getDeviceInfo().certificate.equals(deviceInfo.certificate)) {
                Log.e("LanLinkProvider", "LanLink was asked to replace a socket but the certificate doesn't match, aborting");
                return;
            }
            // Update existing link
            Log.d("GLink/LanLinkProvider", "Reusing same link for device " + deviceInfo.id);
            link.reset(socket, deviceInfo);
            onDeviceInfoUpdated(deviceInfo);
        } else {
            // Create a new link
            Log.d("GLink/LanLinkProvider", "Creating a new link for device " + deviceInfo.id);
            link = new LanLink(context, deviceInfo, this, socket);
            visibleDevices.put(deviceInfo.id, link);
            long started = discoveryStartedAtNanos;
            if (started != 0) {
                Log.i("GLink/Discovery", "link up with " + deviceInfo.id + " " + ((System.nanoTime() - started) / 1_000_000) + " ms after discovery start");
            }
            onConnectionReceived(link);
        }
    }

    public LanLinkProvider(Context context) {
        this.context = context;
        this.mdnsDiscovery = new MdnsDiscovery(context, this);
    }

    private void setupUdpListener() {
        if (udpServer != null) {
            return;
        }
        try {
            udpServer = new DatagramSocket(null);
            udpServer.setReuseAddress(true);
            udpServer.setBroadcast(true);
            udpServer.bind(new InetSocketAddress(UDP_PORT));
        } catch (SocketException e) {
            // We ignore this exception and continue without being able to receive broadcasts instead of crashing the app.
            Log.e("LanLinkProvider", "Error binding udp server. We can send udp broadcasts but not receive them", e);
            if (udpServer != null) {
                try { udpServer.close(); } catch (Exception ignored) {}
                udpServer = null;
            }
            return;
        }
        ThreadHelper.execute(() -> {
            Log.i("UdpListener", "Starting UDP listener");
            while (!isStopped) {
                try {
                    DatagramPacket packet = new DatagramPacket(new byte[MAX_UDP_PACKET_SIZE], MAX_UDP_PACKET_SIZE);
                    udpServer.receive(packet);
                    ThreadHelper.execute(() -> {
                        try {
                            udpPacketReceived(packet);
                        } catch (Exception e) {
                            Log.e("LanLinkProvider", "Unhandled exception receiving incoming UDP connection", e);
                        }
                    });
                } catch (IOException e) {
                    Log.e("LanLinkProvider", "UdpReceive exception", e);
                    onNetworkChange(null); // Trigger a UDP broadcast to try to get them to connect to us instead
                }
            }
            Log.w("UdpListener", "Stopping UDP listener");
        });
    }

    private void setupTcpListener() {
        if (tcpServer != null) {
            return;
        }
        try {
            tcpServer = openServerSocketOnFreePort(MIN_PORT);
        } catch (IOException e) {
            Log.e("LanLinkProvider", "Error creating tcp server", e);
            throw new RuntimeException(e);
        }
        ThreadHelper.execute(() -> {
            while (!isStopped) {
                try {
                    Socket socket = tcpServer.accept();
                    configureSocket(socket);
                    ThreadHelper.execute(() -> {
                        try {
                            tcpPacketReceived(socket);
                        } catch (IOException | CertificateException e) {
                            try { socket.close(); } catch (IOException ignored) { }
                            Log.e("LanLinkProvider", "Exception receiving incoming TCP connection", e);
                        }
                    });
                } catch (Exception e) {
                    Log.e("LanLinkProvider", "TcpReceive exception", e);
                }
            }
            Log.w("TcpListener", "Stopping TCP listener");
        });

    }

    static ServerSocket openServerSocketOnFreePort(int minPort) throws IOException {
        int tcpPort = minPort;
        while (tcpPort <= MAX_PORT) {
            try {
                ServerSocket candidateServer = new ServerSocket(tcpPort);
                Log.i("GLink/LanLink", "Using port " + tcpPort);
                return candidateServer;
            } catch (IOException e) {
                tcpPort++;
                if (tcpPort == MAX_PORT) {
                    Log.e("GLink/LanLink", "No ports available");
                    throw e; //Propagate exception
                }
            }
        }
        throw new RuntimeException("This should not be reachable");
    }

    private void broadcastUdpIdentityPacket(@Nullable Network network) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.CINNAMON_BUN && ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_LOCAL_NETWORK) != PERMISSION_GRANTED) {
            Log.w("LanLinkProvider", "Will not UDP broadcast, missing ACCESS_LOCAL_NETWORK permission");
            return;
        }
        ThreadHelper.execute(() -> {
            List<DeviceHost> hostList = CustomDevicesActivity
                    .getCustomDeviceList(context);

            if (TrustedNetworkHelper.isTrustedNetwork(context)) {
                hostList.add(DeviceHost.BROADCAST); //Default: broadcast.
            } else {
                Log.i("LanLinkProvider", "Current network isn't trusted, not broadcasting");
            }

            ArrayList<InetAddress> ipList = new ArrayList<>();
            for (DeviceHost host : hostList) {
                try {
                    ipList.add(InetAddress.getByName(host.toString()));
                } catch (UnknownHostException e) {
                    e.printStackTrace();
                }
            }

            if (TrustedNetworkHelper.isTrustedNetwork(context)) {
                try {
                    java.util.Enumeration<java.net.NetworkInterface> interfaces = java.net.NetworkInterface.getNetworkInterfaces();
                    while (interfaces != null && interfaces.hasMoreElements()) {
                        java.net.NetworkInterface ni = interfaces.nextElement();
                        if (ni.isLoopback() || !ni.isUp()) continue;
                        for (java.net.InterfaceAddress ia : ni.getInterfaceAddresses()) {
                            InetAddress bcast = ia.getBroadcast();
                            if (bcast != null && !ipList.contains(bcast)) {
                                ipList.add(bcast);
                            }
                        }
                    }
                } catch (Exception ignored) { }
            }

            if (ipList.isEmpty()) {
                return;
            }

            for (int i = 0; i < BROADCAST_BURST_DELAYS_MS.length; i++) {
                long wait = BROADCAST_BURST_DELAYS_MS[i] - (i == 0 ? 0 : BROADCAST_BURST_DELAYS_MS[i - 1]);
                if (wait > 0) {
                    try {
                        Thread.sleep(wait);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
                if (isStopped) return;
                sendUdpIdentityPacket(ipList, network);
            }
        });
    }

    @WorkerThread
    public void sendUdpIdentityPacket(List<InetAddress> ipList, @Nullable Network network) {
        if (tcpServer == null || !tcpServer.isBound()) {
            Log.i("LanLinkProvider", "Won't broadcast UDP packet if TCP socket is not ready yet");
            return;
        }

        // TODO: In protocol version 8 this packet doesn't need to contain identity info
        //       since it will be exchanged after the socket is encrypted.
        DeviceInfo myDeviceInfo = DeviceHelper.getDeviceInfo(context);
        NetworkPacket identity = myDeviceInfo.toIdentityPacket();
        identity.set("tcpPort", tcpServer.getLocalPort());

        byte[] bytes;
        try {
            bytes = identity.serialize().getBytes(Charsets.UTF_8);
        } catch (JSONException e) {
            Log.e("GLink/LanLinkProvider", "Failed to serialize identity packet", e);
            return;
        }

        DatagramSocket socket;
        try {
            socket = new DatagramSocket(null); // Create unbound socket
            socket.setReuseAddress(true);
            socket.bind(new InetSocketAddress(0)); // Bind to any local port
            if (network != null) {
                try {
                    network.bindSocket(socket);
                } catch (IOException e) {
                    Log.w("LanLinkProvider", "Couldn't bind socket to the network");
                    e.printStackTrace();
                }
            }
            socket.setBroadcast(true);
        } catch (SocketException e) {
            Log.e("GLink/LanLinkProvider", "Failed to create DatagramSocket", e);
            return;
        }

        for (InetAddress ip : ipList) {
            try {
                socket.send(new DatagramPacket(bytes, bytes.length, ip, MIN_PORT));
                //Log.i("GLink/LanLinkProvider","Udp identity packet sent to address "+client);
            } catch (IOException e) {
                Log.e("GLink/LanLinkProvider", "Sending udp identity packet failed. Invalid address? (" + ip.toString() + ")", e);
            }
        }

        socket.close();
    }

    @Override
    public void onStart() {
        //Log.i("GLink/LanLinkProvider", "onStart");
        isStopped = false;
        discoveryStartedAtNanos = System.nanoTime();

        setupUdpListener();
        setupTcpListener();

        synchronized (mdnsDiscovery) {
            mdnsDiscovery.startDiscovering();
            if (TrustedNetworkHelper.isTrustedNetwork(context)) {
                mdnsDiscovery.startAnnouncing();
            }
        }

        broadcastUdpIdentityPacket(null);
    }

    @Override
    public void onNetworkChange(@Nullable Network network) {
        if (isStopped) return;

        // Immediate discovery trigger upon network change
        Log.i("LanLinkProvider", "onNetworkChange: triggering immediate broadcast");

        lastBroadcast = System.currentTimeMillis();
        discoveryStartedAtNanos = System.nanoTime();

        setupUdpListener();
        setupTcpListener();

        broadcastUdpIdentityPacket(network);
        synchronized (mdnsDiscovery) {
            if (TrustedNetworkHelper.isTrustedNetwork(context)) {
                mdnsDiscovery.startAnnouncing();
            } else {
                mdnsDiscovery.stopAnnouncing();
            }
            mdnsDiscovery.stopDiscovering();
            mdnsDiscovery.startDiscovering();
        }
    }

    @Override
    public void onStop() {
        //Log.i("GLink/LanLinkProvider", "onStop");
        isStopped = true;
        debounceHandler.removeCallbacksAndMessages(null);
        synchronized (debounceLock) { networkChangePending = false; pendingNetwork = null; }
        synchronized (mdnsDiscovery) {
            mdnsDiscovery.stopAnnouncing();
            mdnsDiscovery.stopDiscovering();
        }
        try {
            tcpServer.close();
        } catch (Exception e) {
            Log.e("LanLink", "Exception", e);
        }
        try {
            udpServer.close();
        } catch (Exception e) {
            Log.e("LanLink", "Exception", e);
        }
    }

    @Override
    public String getName() {
        return "LanLinkProvider";
    }

    @Override
    public int getPriority() { return 20; }

    public int getTcpPort() { return tcpServer.getLocalPort(); }

}

package ioio.lib.pic;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.BindException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Simple thread to maintain connection to IOIO, buffers all IO
 * independent of the rest of operations.
 *
 */
public class IOIOConnection implements ConnectionStateCallback {
	BlockingQueue<IOIOPacket> outgoing = new LinkedBlockingQueue<IOIOPacket>();

	OutgoingHandler outgoingHandler;
	IncomingHandler incomingHandler;
	private final ListenerManager listeners;

	// mS timeout in which we consider the ioio not connected if no response.
	// ytai: i would just leave it to the client to abort(). from a GUI perspective
	// it might more sense to leave it to the human user to press "cancel" when he
	// gives up. he might be in the process of connecting the ioio and will be annoyed
	// with automatic timeout. if a certain app does want this behaviour, they can always
	// setup a thread that sleeps for 3 seconds, then fires abort() unless stopped.
	// arshan: not sure that 3seconds is the right time, but think we should have some
	// upper bound where if no connection is made we eject any pending requests of IOIO,
	// and any blocking calls in the api throw exceptions
	public static final int IOIO_TIMEOUT = 3000;

	public static final int EOF = -1;

	ServerSocket ssocket;
	Socket socket;
	int port;

	InputStream in;
	OutputStream out;

	// Connection state.
	int state = DISCONNECTED;

    public static final int CONNECTED = 1;
	public static final int DISCONNECTED = 3;
	public static final int VERIFIED = 2;

	private boolean disconnectionRequested;

	public IOIOConnection(ListenerManager listener) {
		this(Constants.IOIO_PORT, listener);
	}

	public IOIOConnection(int port, ListenerManager listeners) {
		this.port = port;
		this.listeners = listeners;
	}

	IOIOConnection(InputStream in, OutputStream out, ListenerManager listeners) {
		this.in = in;
		this.out = out;
		this.listeners = listeners;
	}

    IOIOConnection(ServerSocket ssocket, ListenerManager listeners) {
		this.ssocket = ssocket;
		this.listeners = listeners;
	}

	private boolean bindOnPortForIOIOBoard() throws IOException, BindException {
	    if (ssocket == null) {
	        ssocket = new ServerSocket(port);
	    }
        setTimeout(IOIO_TIMEOUT);
		return true;
	}

    private void handleShutdown() throws IOException {
        IOIOLogger.log("Connection is shutting down");

        if (outgoingHandler != null) {
            outgoingHandler.halt();
            outgoingHandler = null;
        }

        if (incomingHandler != null) {
            incomingHandler.halt();
            incomingHandler = null;
        }

        if (in != null) {
            in.close();
            in = null;
        }

        if (out != null) {
            out.close();
            out = null;
        }

        if (socket != null) {
            socket.close();
            socket = null;
        }
    }

	private void setTimeout(int timeout) {
		if (ssocket != null && timeout > 0) {
			try {
				ssocket.setSoTimeout(timeout);
			} catch (SocketException e) {
				e.printStackTrace();
			}
		}
	}

	/**
	 * Attempt to reconnect ( or connect if first time ).
	 *
	 * @return true if successful
	 */
	public boolean waitForBoardToConnect() {
		try {
			if (socket != null) {
				socket.close();
				socket = null;
			}
			socket = ssocket.accept();
			state = CONNECTED;
		}
		catch (SocketTimeoutException toe) {
			toe.printStackTrace();
		}
		catch (IOException e) {
			e.printStackTrace();
		}
		return isConnectedEstablished();
	}

	public boolean isConnectedEstablished() {
		return state == CONNECTED || state == VERIFIED;
	}

	/**
	 * queue a packet to be sent to the IOIO at the next
	 * opportunity.
	 * NOTE: this is not necessarily immediate.
	 * @param packet
	 */
	public void sendToIOIO(IOIOPacket packet) {
	    IOIOLogger.log("offering packet for send: " + packet.toString());
		outgoing.offer(packet);
	}

    public void disconnect() {
        disconnectionRequested = true;
        try {
            handleShutdown();
            ssocket.close();
            ssocket = null;
        } catch (IOException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }
    }

    public void join() throws InterruptedException {
        IOIOLogger.log("Waiting for threads to join");
        if (outgoingHandler != null) {
            outgoingHandler.join();
            outgoingHandler = null;
            IOIOLogger.log("Outgoing handler joined");
        }

        if (incomingHandler != null) {
            incomingHandler.join();
            IOIOLogger.log("Incoming handler joined");
            incomingHandler = null;
        }
    }

    public void start() throws BindException, IOException {
        bindOnPortForIOIOBoard();
        IOIOLogger.log("waiting for connection");
        listeners.resetListeners();
        while (!disconnectionRequested && !waitForBoardToConnect());
        if (disconnectionRequested) {
            return;
        }
        IOIOLogger.log("initial connection");
        try {
            in = socket.getInputStream();
            out = socket.getOutputStream();
        } catch (IOException e) {
            e.printStackTrace();
        }
        incomingHandler = new IncomingHandler(in, this, listeners);
        incomingHandler.start();
    }

    @Override
    public void stateChanged(ConnectionState state) {
        IOIOLogger.log("State changed to " + state);
        if (ConnectionState.CONNECTION_VERIFIED.equals(state)) {
            startOutgoingHandler();
            this.state = VERIFIED;
        }

        if (ConnectionState.SHUTTING_DOWN.equals(state)) {
            this.state = DISCONNECTED;
            try {
                handleShutdown();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    private void startOutgoingHandler() {
        if (outgoingHandler != null) {
            outgoingHandler.halt();
        }
        // clear any pending ...
        synchronized (outgoing) {
            outgoing.clear();
            outgoingHandler = new OutgoingHandler(outgoing, out);
            outgoingHandler.start();
        }
    }

    public boolean isVerified() {
        return state == VERIFIED;
    }
}
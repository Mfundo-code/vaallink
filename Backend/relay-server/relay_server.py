import socket
import threading
import time
import logging
from collections import defaultdict

# Configure logging
logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s - %(name)s - %(levelname)s - %(message)s'
)
logger = logging.getLogger('VaalLinkRelay')
logger.setLevel(logging.DEBUG)

class UdpRelay:
    def __init__(self, host='0.0.0.0', port=52000):
        self.host = host
        self.port = port
        self.sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        self.sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        self.sock.setsockopt(socket.SOL_SOCKET, socket.SO_RCVBUF, 65535)
        self.sock.bind((host, port))
        self.sessions = defaultdict(lambda: {
            'host': None, 
            'client': None, 
            'last_seen': time.time(),
            'last_heartbeat': time.time()
        })
        logger.info(f"UDP Relay running on {host}:{port}")

    def start(self):
        threading.Thread(target=self.run, daemon=True).start()
        threading.Thread(target=self.cleanup_sessions, daemon=True).start()

    def cleanup_sessions(self):
        while True:
            time.sleep(60)
            now = time.time()
            expired = []
            for session_id, session_data in self.sessions.items():
                if now - session_data['last_seen'] > 300:
                    expired.append(session_id)
            
            for session_id in expired:
                del self.sessions[session_id]
                logger.info(f"Cleaned up expired UDP session: {session_id}")

    def run(self):
        while True:
            try:
                data, addr = self.sock.recvfrom(65535)
                if len(data) < 7:
                    continue
                    
                session_id = data[:6].decode('utf-8', errors='ignore')
                is_host = data[6] == 1
                
                session = self.sessions[session_id]
                session['last_seen'] = time.time()
                
                if is_host:
                    session['host'] = addr
                    session['last_heartbeat'] = time.time()
                    logger.debug(f"Host heartbeat for session: {session_id}")
                    self.sock.sendto(b'\x01', addr)  # Send ACK
                else:
                    session['client'] = addr
                    session['last_heartbeat'] = time.time()
                    logger.debug(f"Client heartbeat for session: {session_id}")
                    self.sock.sendto(b'\x01', addr)  # Send ACK
                    
                # Forward packets if both endpoints are registered
                if session['host'] and session['client']:
                    if is_host and session['client']:
                        # Strip header and forward to client
                        self.sock.sendto(data[7:], session['client'])
                    elif not is_host and session['host']:
                        # Strip header and forward to host
                        self.sock.sendto(data[7:], session['host'])
            except Exception as e:
                logger.error(f"UDP processing error: {e}")

class TcpRelay:
    def __init__(self, host='0.0.0.0', port=52001):
        self.host = host
        self.port = port
        self.sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        self.sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        self.sock.setsockopt(socket.SOL_SOCKET, socket.SO_RCVBUF, 65535)
        self.sock.bind((host, port))
        self.sock.listen(100)
        self.sessions = {}
        logger.info(f"TCP Relay running on {host}:{port}")

    def start(self):
        threading.Thread(target=self.run, daemon=True).start()

    def run(self):
        while True:
            try:
                conn, addr = self.sock.accept()
                logger.debug(f"New TCP connection from {addr}")
                threading.Thread(target=self.handle_client, args=(conn, addr)).start()
            except Exception as e:
                logger.error(f"TCP accept error: {e}")

    def handle_client(self, conn, addr):
        try:
            # Receive and parse header
            header = conn.recv(7)
            if len(header) < 7:
                logger.warning("Invalid TCP header length")
                conn.close()
                return
                
            session_id = header[:6].decode('utf-8', errors='ignore')
            is_host = header[6] == 1
            
            # Send ACK
            conn.sendall(b'\x01')
            
            logger.info(f"New {'host' if is_host else 'client'} for session: {session_id}")
            
            if session_id not in self.sessions:
                self.sessions[session_id] = {'host': None, 'client': None}
                
            if is_host:
                self.sessions[session_id]['host'] = conn
            else:
                self.sessions[session_id]['client'] = conn
                
            # Start forwarding if both endpoints connected
            if self.sessions[session_id]['host'] and self.sessions[session_id]['client']:
                logger.info(f"Both endpoints connected for session: {session_id}")
                self.pipe_connections(session_id)
            else:
                logger.info(f"Waiting for peer for session: {session_id}")
                
        except Exception as e:
            logger.error(f"TCP connection error: {e}")
            try:
                conn.close()
            except:
                pass

    def pipe_connections(self, session_id):
        host_conn = self.sessions[session_id]['host']
        client_conn = self.sessions[session_id]['client']
        
        def forward(src, dest, label):
            try:
                while True:
                    data = src.recv(65535)
                    if not data:
                        logger.debug(f"{label} connection closed")
                        break
                    dest.sendall(data)
            except ConnectionResetError:
                logger.info(f"{label} connection reset")
            except Exception as e:
                logger.error(f"{label} forwarding error: {e}")
            finally:
                try:
                    src.close()
                except:
                    pass
                try:
                    dest.close()
                except:
                    pass
                if session_id in self.sessions:
                    del self.sessions[session_id]
                
        # Start bidirectional forwarding
        threading.Thread(
            target=forward, 
            args=(host_conn, client_conn, "Host->Client"), 
            daemon=True
        ).start()
        
        threading.Thread(
            target=forward, 
            args=(client_conn, host_conn, "Client->Host"), 
            daemon=True
        ).start()

if __name__ == "__main__":
    logger.info("Starting VaalLink Relay Servers")
    
    udp_relay = UdpRelay(port=52000)
    tcp_relay = TcpRelay(port=52001)
    
    udp_relay.start()
    tcp_relay.start()
    
    logger.info("Relay servers started. Press Ctrl+C to exit.")
    
    try:
        while True:
            time.sleep(1)
    except KeyboardInterrupt:
        logger.info("Shutting down relay servers")
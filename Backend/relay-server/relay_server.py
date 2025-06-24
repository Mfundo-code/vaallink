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

class UdpRelay:
    def __init__(self, host='0.0.0.0', port=52000):
        self.host = host
        self.port = port
        self.sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        self.sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        self.sock.bind((host, port))
        self.sessions = defaultdict(lambda: {
            'host': None, 
            'client': None, 
            'last_seen': time.time()
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
            data, addr = self.sock.recvfrom(65535)
            if len(data) < 7:
                continue
                
            session_id = data[:6].decode('utf-8', errors='ignore')
            is_host = data[6] == 1
            
            session = self.sessions[session_id]
            session['last_seen'] = time.time()
            
            if is_host:
                session['host'] = addr
                logger.info(f"Host registered for session: {session_id}")
                self.sock.sendto(b'\x01', addr)  # Send ACK
            else:
                session['client'] = addr
                logger.info(f"Client registered for session: {session_id}")
                self.sock.sendto(b'\x01', addr)  # Send ACK
                
            if session['host'] and session['client']:
                if is_host and session['client']:
                    self.sock.sendto(data[7:], session['client'])
                elif not is_host and session['host']:
                    self.sock.sendto(data[7:], session['host'])

class TcpRelay:
    def __init__(self, host='0.0.0.0', port=52001):
        self.host = host
        self.port = port
        self.sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        self.sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        self.sock.bind((host, port))
        self.sock.listen(5)
        self.sessions = {}
        logger.info(f"TCP Relay running on {host}:{port}")

    def start(self):
        threading.Thread(target=self.run, daemon=True).start()

    def run(self):
        while True:
            conn, addr = self.sock.accept()
            threading.Thread(target=self.handle_client, args=(conn, addr)).start()

    def handle_client(self, conn, addr):
        try:
            header = conn.recv(7)
            if len(header) < 7:
                return
                
            session_id = header[:6].decode('utf-8', errors='ignore')
            is_host = header[6] == 1
            
            conn.sendall(b'\x01')  # Send ACK
            
            if session_id not in self.sessions:
                self.sessions[session_id] = {'host': None, 'client': None}
                
            if is_host:
                self.sessions[session_id]['host'] = conn
                logger.info(f"Host connected for session: {session_id}")
            else:
                self.sessions[session_id]['client'] = conn
                logger.info(f"Client connected for session: {session_id}")
                
            if self.sessions[session_id]['host'] and self.sessions[session_id]['client']:
                logger.info(f"Both endpoints connected for session: {session_id}")
                self.pipe_connections(session_id)
                
        except Exception as e:
            logger.error(f"TCP connection error: {e}")

    def pipe_connections(self, session_id):
        host_conn = self.sessions[session_id]['host']
        client_conn = self.sessions[session_id]['client']
        
        def forward(src, dest):
            try:
                while True:
                    data = src.recv(4096)
                    if not data:
                        break
                    dest.sendall(data)
            except Exception as e:
                logger.error(f"Forwarding error: {e}")
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
                
        threading.Thread(target=forward, args=(host_conn, client_conn), daemon=True).start()
        threading.Thread(target=forward, args=(client_conn, host_conn), daemon=True).start()

if __name__ == "__main__":
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
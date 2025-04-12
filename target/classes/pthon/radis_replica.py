import socket
import threading
import time

class RedisReplica:
    def __init__(self, master_host, master_port, replica_port):
        self.master_host = master_host
        self.master_port = master_port
        self.replica_port = replica_port
        self.database = {}
        self.running = True

    def start_replica(self):
        threading.Thread(target=self.connect_to_master, daemon=True).start()
        self.start_client_listener()

    def connect_to_master(self):
        while self.running:
            try:
                print(f"Connecting to master at {self.master_host}:{self.master_port}...")
                master_socket = socket.create_connection((self.master_host, self.master_port))
                print("Connected to master.")
                self.handle_master_communication(master_socket)
            except (ConnectionRefusedError, socket.error):
                print("Failed to connect to master. Retrying in 5 seconds...")
                time.sleep(5)

    def handle_master_communication(self, master_socket):
        try:
            # Perform REPLCONF handshake
            master_socket.sendall(b"*3\r\n$8\r\nREPLCONF\r\n$3\r\nlistening-port\r\n$4\r\n6379\r\n")
            master_socket.sendall(b"*3\r\n$8\r\nREPLCONF\r\n$14\r\ncapa\r\n$6\r\neof\r\n")
            
            while self.running:
                data = master_socket.recv(1024)
                if not data:
                    break
                self.process_master_command(data, master_socket)
        except socket.error:
            print("Lost connection to master.")

    def process_master_command(self, data, master_socket):
        command = data.decode().strip()
        print(f"Received from master: {command}")

        if command.startswith("*2\r\n$8\r\nREPLCONF\r\n$6\r\nGETACK"):
            ack_response = "*3\r\n$8\r\nREPLCONF\r\n$3\r\nACK\r\n$1\r\n0\r\n"
            master_socket.sendall(ack_response.encode())
            print("Sent REPLCONF ACK 0 to master.")

    def start_client_listener(self):
        server_socket = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        server_socket.bind(("0.0.0.0", self.replica_port))
        server_socket.listen(5)
        print(f"Replica listening on port {self.replica_port}...")

        while self.running:
            client_socket, addr = server_socket.accept()
            threading.Thread(target=self.handle_client, args=(client_socket,), daemon=True).start()

    def handle_client(self, client_socket):
        try:
            with client_socket:
                data = client_socket.recv(1024).decode()
                command_parts = data.strip().split()
                if not command_parts:
                    return
                
                if command_parts[0].upper() == "GET" and len(command_parts) == 2:
                    key = command_parts[1]
                    value = self.database.get(key, "-1")
                    response = f"${len(value)}\r\n{value}\r\n"
                    client_socket.sendall(response.encode())
        except socket.error:
            print("Client connection error.")

if __name__ == "__main__":
    import sys
    if len(sys.argv) != 4:
        print("Usage: python redis_replica.py <MASTER_HOST> <MASTER_PORT> <REPLICA_PORT>")
        sys.exit(1)
    
    master_host, master_port, replica_port = sys.argv[1], int(sys.argv[2]), int(sys.argv[3])
    replica = RedisReplica(master_host, master_port, replica_port)
    replica.start_replica()

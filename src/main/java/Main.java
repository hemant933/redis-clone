import java.io.*;
import java.net.*;
import java.util.concurrent.*;
import java.util.*;

public class Main {
    public static void main(String[] args) {
        String dir = "/tmp/redis-data";
        String dbFilename = "dump.rdb";
        int port = 6379;

        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--dir") && i + 1 < args.length) {
                dir = args[i + 1];
            } else if (args[i].equals("--dbfilename") && i + 1 < args.length) {
                dbFilename = args[i + 1];
            } else if (args[i].equals("--port") && i + 1 < args.length) {
                try {
                    port = Integer.parseInt(args[i + 1]);
                } catch (NumberFormatException e) {
                    System.out.println("Invalid port number. Using default: 6379");
                }
            }
        }

        ServerConfig.setConfig("dir", dir);
        ServerConfig.setConfig("dbfilename", dbFilename);

        RDBLoader.loadRDBFile(dir, dbFilename);

        System.out.println("Redis-like server started on port " + port);

        try (ServerSocket serverSocket = new ServerSocket(port)) {
            serverSocket.setReuseAddress(true);

            while (true) {
                Socket clientSocket = serverSocket.accept();
                new ClientHandler(clientSocket).start();
            }
        } catch (IOException e) {
            System.out.println("Server error: " + e.getMessage());
        }
    }
}

class ReplicaClient extends Thread {
    private final String masterHost;
    private final int masterPort;

    public ReplicaClient(String masterHost, int masterPort) {
        this.masterHost = masterHost;
        this.masterPort = masterPort;
    }

    @Override
    public void run() {
        try (Socket socket = new Socket(masterHost, masterPort);
             BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
             OutputStream outputStream = socket.getOutputStream()) {
            
            outputStream.write("*3\r\n$8\r\nREPLCONF\r\n$3\r\nACK\r\n$1\r\n0\r\n".getBytes());
            outputStream.flush();
            
            while (true) {
                String input = reader.readLine();
                if (input != null && input.contains("GETACK")) {
                    outputStream.write("*3\r\n$8\r\nREPLCONF\r\n$3\r\nACK\r\n$1\r\n0\r\n".getBytes());
                    outputStream.flush();
                }
            }
        } catch (IOException e) {
            System.out.println("Replication error: " + e.getMessage());
        }
    }
}


class ServerConfig {
    private static final ConcurrentHashMap<String, String> config = new ConcurrentHashMap<>();

    public static void setConfig(String key, String value) {
        config.put(key, value);
    }

    public static String getConfig(String key) {
        return config.getOrDefault(key, "");
    }
}

class RDBLoader {
    private static final Map<String, String> database = new ConcurrentHashMap<>();
    private static final Map<String, Long> expiryTimes = new ConcurrentHashMap<>();
    private static final Map<String, String> keyTypes = new ConcurrentHashMap<>();

    public static void loadRDBFile(String dir, String dbFilename) {
        String filePath = dir + "/" + dbFilename;
        File file = new File(filePath);

        if (!file.exists()) {
            System.out.println("No RDB file found, treating database as empty.");
            return;
        }

        try (FileInputStream fis = new FileInputStream(file);
             DataInputStream dis = new DataInputStream(fis)) {

            byte[] header = new byte[9];
            dis.readFully(header);

            while (dis.available() > 0) {
                byte type = dis.readByte();
                if (type == (byte) 0xFE) break;

                boolean hasExpiry = (type == (byte) 0xFD);
                long expiryTime = -1;

                if (hasExpiry) {
                    expiryTime = dis.readLong();
                    type = dis.readByte();
                }

                int keyLength = readLength(dis);
                byte[] keyBytes = new byte[keyLength];
                dis.readFully(keyBytes);
                String key = new String(keyBytes);

                int valueLength = readLength(dis);
                byte[] valueBytes = new byte[valueLength];
                dis.readFully(valueBytes);
                String value = new String(valueBytes);

                database.put(key, value);
                keyTypes.put(key, "string");

                if (hasExpiry) {
                    expiryTimes.put(key, expiryTime);
                }
            }

            System.out.println("Loaded data from RDB: " + database);
            System.out.println("Expiry data: " + expiryTimes);
        } catch (IOException e) {
            System.out.println("Error reading RDB file: " + e.getMessage());
        }
    }

    private static int readLength(DataInputStream dis) throws IOException {
        int firstByte = dis.readUnsignedByte();
        if ((firstByte & 0xC0) == 0x00) {
            return firstByte;
        } else if ((firstByte & 0xC0) == 0x40) {
            return dis.readUnsignedByte() | ((firstByte & 0x3F) << 8);
        } else {
            return dis.readInt();
        }
    }

    public static String getValue(String key) {
        if (!database.containsKey(key)) {
            return null;
        }

        Long expiryTime = expiryTimes.get(key);
        if (expiryTime != null && System.currentTimeMillis() > expiryTime) {
            database.remove(key);
            expiryTimes.remove(key);
            keyTypes.remove(key);
            return null;
        }

        return database.get(key);
    }

    public static String getType(String key) {
        return keyTypes.getOrDefault(key, "none");
    }
}

class ClientHandler extends Thread {
    private final Socket clientSocket;

    public ClientHandler(Socket socket) {
        this.clientSocket = socket;
    }

    @Override
    public void run() {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));
             OutputStream outputStream = clientSocket.getOutputStream()) {

            String input;
            while ((input = reader.readLine()) != null) {
                System.out.println("Received: " + input);
                String[] commandParts = parseRESPCommand(reader, input);
                if (commandParts == null || commandParts.length == 0) continue;

                switch (commandParts[0].toUpperCase()) {
                    case "GET": handleGetCommand(commandParts, outputStream); break;
                    case "INFO": handleInfoCommand(commandParts, outputStream); break;
                    default: outputStream.write("-ERR unknown command\r\n".getBytes()); break;
                }
                outputStream.flush();
            }
        } catch (IOException e) {
            System.out.println("Client error: " + e.getMessage());
        } finally {
            try { clientSocket.close(); } catch (IOException e) {
                System.out.println("Error closing client socket: " + e.getMessage()); }
        }
    }

    private void handleGetCommand(String[] commandParts, OutputStream outputStream) throws IOException {
        if (commandParts.length != 2) {
            outputStream.write("-ERR invalid GET syntax\r\n".getBytes());
            return;
        }
        String value = RDBLoader.getValue(commandParts[1]);
        outputStream.write((value == null ? "$-1\r\n" : "$" + value.length() + "\r\n" + value + "\r\n").getBytes());
    }

    private void handleInfoCommand(String[] commandParts, OutputStream outputStream) throws IOException {
        if (commandParts.length != 2 || !commandParts[1].equalsIgnoreCase("replication")) {
            outputStream.write("-ERR unsupported INFO section\r\n".getBytes());
            return;
        }
        outputStream.write("$9\r\nrole:master\r\n".getBytes());
    }

    private String[] parseRESPCommand(BufferedReader reader, String firstLine) throws IOException {
        if (!firstLine.startsWith("*")) return null;
        int numArgs = Integer.parseInt(firstLine.substring(1));
        String[] commandParts = new String[numArgs];
        for (int i = 0; i < numArgs; i++) {
            reader.readLine();
            commandParts[i] = reader.readLine();
        }
        return commandParts;
    }
}

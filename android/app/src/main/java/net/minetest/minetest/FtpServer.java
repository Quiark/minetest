package net.minetest.minetest;

import android.util.Log;

import androidx.annotation.NonNull;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

public class FtpServer {
 private static final String TAG = "FtpServer";
 public static final String USERNAME = "roman";
 public static final String PASSWORD = "bu4hon2t3h7k9dhaeu";
 public static final int PORT = 2121;
 public static final int DATA_PORT = 2122;

 private final File root;
 private Thread serverThread;
 private ServerSocket serverSocket;
 private volatile boolean running;

 public FtpServer(@NonNull File root) {
  this.root = root;
 }

 public synchronized void start() {
  if (running)
   return;
  running = true;
  serverThread = new Thread(this::runServer, "MinetestFtpServer");
  serverThread.start();
 }

 public synchronized void stop() {
  running = false;
  if (serverSocket != null) {
   try {
    serverSocket.close();
   } catch (IOException e) {
    Log.w(TAG, "Error closing FTP server", e);
   }
  }
 }

 private void runServer() {
  try (ServerSocket socket = new ServerSocket(PORT)) {
   serverSocket = socket;
   while (running) {
    try {
     Socket client = socket.accept();
     new Thread(() -> handleClient(client), "MinetestFtpClient").start();
    } catch (IOException e) {
     if (running)
      Log.e(TAG, "Error accepting FTP connection", e);
    }
   }
  } catch (IOException e) {
   Log.e(TAG, "FTP server failed to start", e);
  } finally {
   running = false;
   serverSocket = null;
  }
 }

 private void handleClient(Socket client) {
  try (Socket ignored = client;
    BufferedReader reader = new BufferedReader(new InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8));
    BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(client.getOutputStream(), StandardCharsets.UTF_8))) {
   Session session = new Session(client.getLocalAddress());
   send(writer, "220 Minetest FTP ready");

   String line;
   while ((line = reader.readLine()) != null) {
    String command;
    String arg = "";
    int space = line.indexOf(' ');
    if (space >= 0) {
     command = line.substring(0, space).toUpperCase(Locale.ROOT);
     arg = line.substring(space + 1).trim();
    } else {
     command = line.toUpperCase(Locale.ROOT);
    }

    if (handleCommand(session, command, arg, reader, writer))
     break;
   }
  } catch (IOException e) {
   Log.w(TAG, "FTP client disconnected", e);
  }
 }

 private boolean handleCommand(Session session, String command, String arg, BufferedReader reader,
   BufferedWriter writer) throws IOException {
  switch (command) {
   case "USER":
    session.usernameAccepted = USERNAME.equals(arg);
    send(writer, session.usernameAccepted ? "331 User name ok, need password" : "530 Invalid user name");
    break;
   case "PASS":
    session.loggedIn = session.usernameAccepted && PASSWORD.equals(arg);
    send(writer, session.loggedIn ? "230 Login successful" : "530 Login incorrect");
    break;
   case "SYST":
    send(writer, "215 UNIX Type: L8");
    break;
   case "FEAT":
    send(writer, "211-Features");
    send(writer, " UTF8");
    send(writer, "211 End");
    break;
   case "OPTS":
    send(writer, "200 Options accepted");
    break;
   case "PWD":
   case "XPWD":
    send(writer, "257 \"" + session.cwd + "\" is current directory");
    break;
   case "TYPE":
    send(writer, "200 Type set");
    break;
   case "NOOP":
    send(writer, "200 OK");
    break;
   case "PORT":
    send(writer, "502 Active mode is not implemented; use passive mode");
    break;
   case "PASV":
    openPassiveSocket(session, writer);
    break;
   case "EPSV":
    openExtendedPassiveSocket(session, writer);
    break;
   case "CWD":
    changeDirectory(session, arg, writer);
    break;
   case "CDUP":
    changeDirectory(session, "..", writer);
    break;
   case "LIST":
   case "NLST":
    listFiles(session, arg, command.equals("NLST"), writer);
    break;
   case "RETR":
    retrieveFile(session, arg, writer);
    break;
   case "STOR":
    storeFile(session, arg, writer);
    break;
   case "DELE":
    deletePath(session, arg, false, writer);
    break;
   case "RMD":
   case "XRMD":
    deletePath(session, arg, true, writer);
    break;
   case "MKD":
   case "XMKD":
    makeDirectory(session, arg, writer);
    break;
   case "SIZE":
    fileSize(session, arg, writer);
    break;
   case "QUIT":
    send(writer, "221 Goodbye");
    closePassiveSocket(session);
    return true;
   default:
    send(writer, "502 Command not implemented");
    break;
  }
  return false;
 }

 private boolean requireLogin(Session session, BufferedWriter writer) throws IOException {
  if (session.loggedIn)
   return true;
  send(writer, "530 Not logged in");
  return false;
 }

 private void openPassiveSocket(Session session, BufferedWriter writer) throws IOException {
  if (!requireLogin(session, writer))
   return;
  closePassiveSocket(session);
  ServerSocket passiveSocket = new ServerSocket(DATA_PORT);
  session.passiveSocket = passiveSocket;
  byte[] address = session.serverAddress.getAddress();
  send(writer, String.format(Locale.ROOT, "227 Entering Passive Mode (%d,%d,%d,%d,%d,%d)",
   address[0] & 0xff, address[1] & 0xff, address[2] & 0xff, address[3] & 0xff,
   DATA_PORT / 256, DATA_PORT % 256));
 }

 private void openExtendedPassiveSocket(Session session, BufferedWriter writer) throws IOException {
  if (!requireLogin(session, writer))
   return;
  closePassiveSocket(session);
  session.passiveSocket = new ServerSocket(DATA_PORT);
  send(writer, "229 Entering Extended Passive Mode (|||" + DATA_PORT + "|)");
 }

 private Socket acceptDataSocket(Session session) throws IOException {
  if (session.passiveSocket == null)
   throw new IOException("Passive mode was not requested");
  session.passiveSocket.setSoTimeout(15000);
  try {
   return session.passiveSocket.accept();
  } finally {
   closePassiveSocket(session);
  }
 }

 private void closePassiveSocket(Session session) {
  if (session.passiveSocket != null) {
   try {
    session.passiveSocket.close();
   } catch (IOException ignored) {
   }
   session.passiveSocket = null;
  }
 }

 private void changeDirectory(Session session, String path, BufferedWriter writer) throws IOException {
  if (!requireLogin(session, writer))
   return;
  File dir = resolvePath(session, path);
  if (!dir.isDirectory()) {
   send(writer, "550 Directory not found");
   return;
  }
  session.cwd = toFtpPath(dir);
  send(writer, "250 Directory changed");
 }

 private void listFiles(Session session, String path, boolean namesOnly, BufferedWriter writer) throws IOException {
  if (!requireLogin(session, writer))
   return;
  File dir = path.isEmpty() ? resolvePath(session, ".") : resolvePath(session, path);
  if (!dir.isDirectory()) {
   send(writer, "550 Directory not found");
   return;
  }
  File[] files = dir.listFiles();
  send(writer, "150 Opening data connection");
  try (Socket data = acceptDataSocket(session);
    BufferedWriter dataWriter = new BufferedWriter(new OutputStreamWriter(data.getOutputStream(), StandardCharsets.UTF_8))) {
   if (files != null) {
    for (File file : files) {
     dataWriter.write(namesOnly ? file.getName() : listLine(file));
     dataWriter.write("\r\n");
    }
   }
  } catch (SocketTimeoutException e) {
   send(writer, "425 Data connection timed out");
   return;
  }
  send(writer, "226 Transfer complete");
 }

 private void retrieveFile(Session session, String path, BufferedWriter writer) throws IOException {
  if (!requireLogin(session, writer))
   return;
  File file = resolvePath(session, path);
  if (!file.isFile()) {
   send(writer, "550 File not found");
   return;
  }
  send(writer, "150 Opening data connection");
  try (Socket data = acceptDataSocket(session);
    InputStream in = new FileInputStream(file);
    OutputStream out = data.getOutputStream()) {
   copy(in, out);
  } catch (SocketTimeoutException e) {
   send(writer, "425 Data connection timed out");
   return;
  }
  send(writer, "226 Transfer complete");
 }

 private void storeFile(Session session, String path, BufferedWriter writer) throws IOException {
  if (!requireLogin(session, writer))
   return;
  File file = resolvePath(session, path);
  File parent = file.getParentFile();
  if (parent == null || !parent.isDirectory()) {
   send(writer, "550 Parent directory not found");
   return;
  }
  send(writer, "150 Opening data connection");
  try (Socket data = acceptDataSocket(session);
    InputStream in = data.getInputStream();
    OutputStream out = new FileOutputStream(file)) {
   copy(in, out);
  } catch (SocketTimeoutException e) {
   send(writer, "425 Data connection timed out");
   return;
  }
  send(writer, "226 Transfer complete");
 }

 private void deletePath(Session session, String path, boolean directory, BufferedWriter writer) throws IOException {
  if (!requireLogin(session, writer))
   return;
  File file = resolvePath(session, path);
  boolean ok = directory ? file.isDirectory() && file.delete() : file.isFile() && file.delete();
  send(writer, ok ? "250 Deleted" : "550 Delete failed");
 }

 private void makeDirectory(Session session, String path, BufferedWriter writer) throws IOException {
  if (!requireLogin(session, writer))
   return;
  File dir = resolvePath(session, path);
  send(writer, dir.mkdir() ? "257 Directory created" : "550 Create directory failed");
 }

 private void fileSize(Session session, String path, BufferedWriter writer) throws IOException {
  if (!requireLogin(session, writer))
   return;
  File file = resolvePath(session, path);
  if (!file.isFile()) {
   send(writer, "550 File not found");
   return;
  }
  send(writer, "213 " + file.length());
 }

 private File resolvePath(Session session, String path) throws IOException {
  String ftpPath;
  if (path == null || path.isEmpty() || path.equals("."))
   ftpPath = session.cwd;
  else if (path.startsWith("/"))
   ftpPath = path;
  else
   ftpPath = session.cwd.endsWith("/") ? session.cwd + path : session.cwd + "/" + path;

  String localPath = ftpPath.startsWith("/") ? ftpPath.substring(1) : ftpPath;
  File file = new File(root, localPath).getCanonicalFile();
  String rootPath = root.getCanonicalPath();
  if (!file.getPath().equals(rootPath) && !file.getPath().startsWith(rootPath + File.separator))
   throw new IOException("Path escapes FTP root");
  return file;
 }

 private String toFtpPath(File file) throws IOException {
  String rootPath = root.getCanonicalPath();
  String path = file.getCanonicalPath();
  if (path.equals(rootPath))
   return "/";
  return "/" + path.substring(rootPath.length() + 1).replace(File.separatorChar, '/');
 }

 private String listLine(File file) {
  String type = file.isDirectory() ? "d" : "-";
  return String.format(Locale.ROOT, "%srw-r--r-- 1 user group %d Jan 01 00:00 %s", type, file.length(), file.getName());
 }

 private void copy(InputStream in, OutputStream out) throws IOException {
  byte[] buffer = new byte[8192];
  int read;
  while ((read = in.read(buffer)) >= 0) {
   out.write(buffer, 0, read);
  }
 }

 private void send(BufferedWriter writer, String line) throws IOException {
  writer.write(line);
  writer.write("\r\n");
  writer.flush();
 }

 private static class Session {
  final InetAddress serverAddress;
  String cwd = "/";
  ServerSocket passiveSocket;
  boolean usernameAccepted;
  boolean loggedIn;

  Session(InetAddress serverAddress) {
   this.serverAddress = serverAddress;
  }
 }
}

package com.themoon.y1;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;
import org.json.JSONObject;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class Y1WebServer extends Thread {
    private static final String TAG = "Y1WebServer";
    private ServerSocket serverSocket;
    private boolean running = true;
    private final File rootFolder = new File("/storage/sdcard0"); // file manager serves the whole device, not just the app's music folder
    private Context context;
    private final ExecutorService connectionPool = Executors.newFixedThreadPool(8);
    // The File-Manager page is a compile-time-constant string; hold it in one
    // static final field so it is allocated once instead of per GET / request.
    private static final String FILE_MANAGER_HTML =
            "<!DOCTYPE html><html><head><meta charset='utf-8'><meta name='viewport' content='width=device-width, initial-scale=1'>" +
                            "<title>Y1 File Manager</title><style>" +
                            // 🚀 [design tweak] Background color and default font (based on Material Dark Theme)
                            "body{font-family:'Roboto', 'Segoe UI', sans-serif; background:#1E1E24; color:#E0E0E0; padding:20px; text-align:center; max-width:800px; margin:0 auto; padding-bottom:120px;} " +
                            "input, select, button{font-size:14px; padding:10px 16px; margin:5px; border-radius:20px; border:none; outline:none; transition:0.2s;} " +
                            "input[type=text]{width:calc(100% - 120px); background:#2A2A35; color:#E0E0E0; border-radius:12px; padding:12px;} " +

                            // 🚀 [design tweak] Button colors (purple accent & modern tone)
                            "button{background:#B39DDB; color:#121212; font-weight:600; cursor:pointer;} " + // default button (light purple)
                            "button:hover{background:#9575CD;} " +
                            "button.danger{background:#424250; color:#E57373;} " + // delete/cancel button (dark gray background, red text)
                            "button.danger:hover{background:#EF5350; color:#fff;} " +
                            "button.action{background:#383848; color:#B39DDB;} " + // sub-action button (dark background, purple text)
                            "button.action:hover{background:#454555;} " +

                            // 🚀 [design tweak] Boxes and list items
                            ".box{background:#252530; padding:20px; border-radius:16px; margin:15px 0; text-align:left; box-shadow:0 4px 6px rgba(0,0,0,0.3);} " +
                            ".item{display:flex; justify-content:space-between; align-items:center; padding:12px; border-bottom:1px solid #333340; cursor:pointer; transition:0.2s;} " +
                            ".item:last-child{border-bottom:none;} " +
                            ".item:hover{background:#2D2D3A; border-radius:8px;} " +
                            ".item-left{display:flex; align-items:center; flex-grow:1; overflow:hidden; gap:12px;} " +
                            ".item-name{white-space:nowrap; overflow:hidden; text-overflow:ellipsis; font-weight:500;} " +
                            ".thumb{width:40px; height:40px; object-fit:cover; border-radius:8px; background:#1A1A20;} " +
                            ".icon{font-size:22px; width:40px; text-align:center; color:#B39DDB;} " + // icons unified to the same light purple tone
                            ".btn-group{display:flex; gap:6px;} " +

                            // Audio player
                            "#audioBox{position:fixed; bottom:0; left:0; right:0; background:#252530; border-top:1px solid #454555; padding:15px; display:none; z-index:100; box-shadow:0 -2px 10px rgba(0,0,0,0.5);} " +
                            "audio{width:100%; max-width:800px; margin:0 auto; display:block; outline:none; border-radius:24px;} " +

                            // Drag & drop animation
                            "#uploadBox{transition:0.3s; border:2px dashed #454555;} " +
                            "#uploadBox.dragover{background:#2D2D3A; border:2px dashed #B39DDB;} " +

                            // Text editor modal
                            "#editorBox{position:fixed; top:0; left:0; width:100%; height:100%; background:rgba(18,18,22,0.95); z-index:200; padding:20px; box-sizing:border-box; display:none;} " +
                            "#editorArea{width:100%; height:calc(100% - 120px); background:#1E1E24; color:#E0E0E0; font-family:'Courier New', monospace; font-size:15px; border:1px solid #454555; border-radius:12px; padding:15px; resize:none; box-shadow:inset 0 2px 5px rgba(0,0,0,0.3);} " +
                            "#editorTitle{color:#B39DDB; margin-top:0; white-space:nowrap; overflow:hidden; text-overflow:ellipsis; font-weight:600;}" +
                            "</style></head><body>" +

                            "<h2 style='color:#E0E0E0; font-weight:600; letter-spacing:0.5px;'>📁 Y1 File Manager</h2>" +

                            // Upload / create-folder box
                            "<div class='box' id='uploadBox'>" +
                            "<div style='font-size:16px; margin-bottom:12px; font-weight:500; color:#B39DDB;'>📍 <span id='currentPathText'>/</span></div>" +
                            "<div style='text-align:center; color:#9E9E9E; font-size:13px; margin-bottom:20px; padding-bottom:15px; border-bottom:1px solid #333340;'>💡 <b>Drag & Drop</b> files anywhere in this box to upload instantly!</div>" +
                            "<div style='display:flex; gap:8px; margin-bottom:15px;'>" +
                            "<input type='text' id='fName' placeholder='New folder name...'>" +
                            "<button onclick='createFolder()'>Create</button></div>" +
                            "<div style='display:flex; gap:8px; align-items:center;'>" +
                            "<input type='file' id='fInput' multiple accept='*/*' style='flex-grow:1; color:#9E9E9E;'>" +
                            "<button onclick='uploadAll()' class='action'>Upload Here</button></div>" +
                            "<div id='status' style='margin-top:12px; color:#81C784; font-weight:600; font-size:14px;'></div>" +
                            "</div>" +

                            // Navidrome settings box
                            "<div class='box'>" +
                            "<div style='font-size:16px; margin-bottom:12px; font-weight:500; color:#B39DDB;'>🎵 Navidrome Settings</div>" +
                            "<div style='display:flex; flex-direction:column; gap:8px;'>" +
                            "<input type='text' id='navUrl' placeholder='Server URL  e.g. http://192.168.1.100:4533' style='width:100%; box-sizing:border-box;'>" +
                            "<input type='text' id='navUser' placeholder='Username'>" +
                            "<input type='password' id='navPass' placeholder='Password'>" +
                            "</div>" +
                            "<div style='margin-top:10px; display:flex; gap:8px;'>" +
                            "<button onclick='saveNavSettings()' style='flex:1;'>💾 Save</button>" +
                            "<button class='action' onclick='loadNavSettings()' style='flex:1;'>📋 Load Current</button>" +
                            "</div>" +
                            "<div id='navStatus' style='margin-top:8px; color:#81C784; font-size:13px;'></div>" +
                            "</div>" +

                            // File list box
                            "<div class='box' id='fileList'>Loading...</div>" +

                            // Floating audio player
                            "<div id='audioBox'>" +
                            "<div id='audioTitle' style='max-width:800px; margin:0 auto 12px; font-weight:600; white-space:nowrap; overflow:hidden; text-overflow:ellipsis; color:#B39DDB;'></div>" +
                            "<audio id='audioPlayer' controls controlsList='nodownload'></audio>" +
                            "</div>" +

                            // Fullscreen text editor
                            "<div id='editorBox'>" +
                            "<h3 id='editorTitle'>📝 Edit File</h3>" +
                            "<textarea id='editorArea' spellcheck='false' wrap='off'></textarea>" +
                            "<div style='display:flex; gap:12px; margin-top:15px;'>" +
                            "<button class='action' style='flex:1; background:#B39DDB; color:#121212;' onclick='saveFile()'>💾 Save Settings</button>" +
                            "<button class='danger' style='flex:1;' onclick='closeEditor()'>Cancel</button>" +
                            "</div></div>" +

                            "<script>" +
                            "let currentPath = '';" +
                            "function loadList() {" +
                            "  fetch('/api/list?dir=' + encodeURIComponent(currentPath)).then(r=>r.json()).then(data => {" +
                            "    document.getElementById('currentPathText').innerText = '/' + currentPath;" +
                            "    let html = '';" +
                            "    if(currentPath !== '') html += `<div class='item' onclick='goUp()'><div class='item-left'><div class='icon'>🔙</div><b style='color:#B39DDB;'>[Go Back]</b></div></div>`;" +
                            "    data.forEach(f => {" +
                            "      let ext = f.name.split('.').pop().toLowerCase();" +
                            "      let isImg = ['jpg','jpeg','png','webp','gif'].includes(ext);" +
                            "      let isAudio = ['mp3','flac','wav','ogg','m4a','aac'].includes(ext);" +
                            "      let isText = ['json','txt','xml','ini','md','m3u','m3u8','eq'].includes(ext);" +
                            "      let fullPath = currentPath ? currentPath + '/' + f.name : f.name;" +
                            "      let safePath = encodeURIComponent(fullPath);" +

                            "      let iconHtml = f.isDir ? `<div class='icon'>📁</div>` : " +
                            "                     isImg ? `<img src='/api/file?path=${safePath}' class='thumb' loading='lazy'>` : " +
                            "                     isAudio ? `<div class='icon'>🎵</div>` : " +
                            "                     isText ? `<div class='icon'>📝</div>` : `<div class='icon'>📄</div>`;" +

                            "      let rowAction = f.isDir ? `onclick=\"goInto('${f.name.replace(/'/g, \"\\\\'\")}')\"` : " +
                            "                      isAudio ? `onclick=\"playAudio('${safePath}', '${f.name.replace(/'/g, \"\\\\'\")}')\"` : " +
                            "                      isText ? `onclick=\"openEditor(event, '${safePath}', '${f.name.replace(/'/g, \"\\\\'\")}')\"` : " +
                            "                      isImg ? `onclick=\"window.open('/api/file?path=${safePath}', '_blank')\"` : '';" +

                            "      let editBtn = isText ? `<button class='action' onclick=\"openEditor(event, '${safePath}', '${f.name.replace(/'/g, \"\\\\'\")}')\">Edit</button>` : '';" +
                            "      let renameBtn = `<button class='action' onclick=\"renameItem(event, '${f.name.replace(/'/g, \"\\\\'\")}')\">Rename</button>`;" +

                            "      html += `<div class='item' ${rowAction}>` +" +
                            "              `<div class='item-left'>${iconHtml}<span class='item-name'>${f.name}</span></div>` +" +
                            "              `<div class='btn-group'>${editBtn}${renameBtn}<button class='danger' onclick=\"deleteItem(event, '${f.name.replace(/'/g, \"\\\\'\")}')\">Delete</button></div>` +" +
                            "              `</div>`;" +
                            "    });" +
                            "    if(data.length===0 && currentPath === '') html += '<div style=\"padding:15px; color:#9E9E9E;\">No files found.</div>';" +
                            "    document.getElementById('fileList').innerHTML = html;" +
                            "  });" +
                            "}" +
                            "function goInto(dirName) { currentPath = currentPath ? currentPath + '/' + dirName : dirName; loadList(); }" +
                            "function goUp() { let parts = currentPath.split('/'); parts.pop(); currentPath = parts.join('/'); loadList(); }" +

                            "function playAudio(path, name) {" +
                            "  document.getElementById('audioBox').style.display = 'block';" +
                            "  document.getElementById('audioTitle').innerText = '▶ ' + name;" +
                            "  let player = document.getElementById('audioPlayer');" +
                            "  player.src = '/api/file?path=' + path;" +
                            "  player.play();" +
                            "}" +

                            "let editingPath = '';" +
                            "function openEditor(e, path, name) {" +
                            "  if(e) e.stopPropagation();" +
                            "  editingPath = path;" +
                            "  document.getElementById('editorTitle').innerText = '📝 Editing: ' + name;" +
                            "  document.getElementById('editorArea').value = 'Loading content...';" +
                            "  document.getElementById('editorBox').style.display = 'block';" +
                            "  fetch('/api/file?path=' + path).then(r => r.text()).then(txt => {" +
                            "    document.getElementById('editorArea').value = txt;" +
                            "  });" +
                            "}" +
                            "function closeEditor() { document.getElementById('editorBox').style.display = 'none'; editingPath=''; }" +
                            "function saveFile() {" +
                            "  let content = document.getElementById('editorArea').value;" +
                            "  fetch('/api/save?path=' + editingPath, { method: 'POST', body: content }).then(() => {" +
                            "    alert('✅ File saved successfully!'); closeEditor();" +
                            "  }).catch(e => alert('Failed to save.'));" +
                            "}" +

                            "function createFolder() { " +
                            "  var n = document.getElementById('fName').value; if(!n) return;" +
                            "  fetch('/api/create?dir=' + encodeURIComponent(currentPath) + '&name=' + encodeURIComponent(n), {method:'POST'}).then(()=>{ document.getElementById('fName').value=''; loadList();});" +
                            "}" +
                            "function renameItem(e, oldName) { " +
                            "  e.stopPropagation();" +
                            "  var newName = prompt('Enter new name for: ' + oldName, oldName);" +
                            "  if(!newName || newName === oldName) return;" +
                            "  fetch('/api/rename?dir=' + encodeURIComponent(currentPath) + '&old=' + encodeURIComponent(oldName) + '&new=' + encodeURIComponent(newName), {method:'POST'}).then(()=>loadList());" +
                            "}" +
                            "function deleteItem(e, name) { " +
                            "  e.stopPropagation();" +
                            "  if(!confirm('Delete ' + name + '?')) return;" +
                            "  fetch('/api/delete?path=' + encodeURIComponent(currentPath ? currentPath + '/' + name : name), {method:'POST'}).then(()=>loadList());" +
                            "}" +

                            "async function uploadAll(droppedFiles) { " +
                            "  var files = droppedFiles || document.getElementById('fInput').files; var st = document.getElementById('status'); " +
                            "  if(files.length === 0) return;" +
                            "  for(var i=0; i<files.length; i++) { " +
                            "    st.innerText = 'Uploading: ' + files[i].name + ' (' + (i+1) + '/' + files.length + ')'; " +
                            "    await fetch('/api/upload?dir=' + encodeURIComponent(currentPath) + '&name=' + encodeURIComponent(files[i].name), {method:'POST', body:files[i]}); " +
                            "  } " +
                            "  st.innerText = '✅ Upload Complete!'; document.getElementById('fInput').value=''; loadList();" +
                            "}" +

                            "async function uploadFolderItems(fileList) { " +
                            "  var st = document.getElementById('status'); " +
                            "  for(var i=0; i<fileList.length; i++) { " +
                            "    let item = fileList[i]; " +
                            "    let displayPath = item.path ? item.path + item.file.name : item.file.name; " +
                            "    st.innerText = 'Uploading: ' + displayPath + ' (' + (i+1) + '/' + fileList.length + ')'; " +
                            "    let targetDir = currentPath; " +
                            "    if(item.path) { " +
                            "       let subDir = item.path.replace(/\\/$/, ''); " +
                            "       targetDir = currentPath ? currentPath + '/' + subDir : subDir; " +
                            "    } " +
                            "    await fetch('/api/upload?dir=' + encodeURIComponent(targetDir) + '&name=' + encodeURIComponent(item.file.name), {method:'POST', body:item.file}); " +
                            "  } " +
                            "  st.innerText = '✅ Folder Upload Complete!'; loadList();" +
                            "}" +

                            "let dropZone = document.getElementById('uploadBox');" +
                            "dropZone.addEventListener('dragover', function(e) { e.preventDefault(); dropZone.classList.add('dragover'); });" +
                            "dropZone.addEventListener('dragleave', function(e) { e.preventDefault(); dropZone.classList.remove('dragover'); });" +
                            "dropZone.addEventListener('drop', function(e) { " +
                            "  e.preventDefault(); dropZone.classList.remove('dragover'); " +
                            "  let items = e.dataTransfer.items; " +
                            "  if(!items) { if(e.dataTransfer.files.length > 0) uploadAll(e.dataTransfer.files); return; } " +
                            "  " +
                            "  document.getElementById('status').innerText = 'Scanning dropped items...'; " +
                            "  let filesToUpload = []; " +
                            "  let pending = 0; " +
                            "  " +
                            "  function scanEntry(item, path) { " +
                            "    if(item.isFile) { " +
                            "      pending++; " +
                            "      item.file(f => { filesToUpload.push({file: f, path: path}); pending--; checkDone(); }); " +
                            "    } else if(item.isDirectory) { " +
                            "      let dirReader = item.createReader(); " +
                            "      pending++; " +
                            "      dirReader.readEntries(entries => { " +
                            "        entries.forEach(entry => scanEntry(entry, path + item.name + '/')); " +
                            "        pending--; checkDone(); " +
                            "      }); " +
                            "    } " +
                            "  } " +
                            "  function checkDone() { " +
                            "    if(pending === 0) { " +
                            "      if(filesToUpload.length > 0) uploadFolderItems(filesToUpload); " +
                            "      else document.getElementById('status').innerText = 'No files found.'; " +
                            "    } " +
                            "  } " +
                            "  for(let i=0; i<items.length; i++) { " +
                            "    let entry = items[i].webkitGetAsEntry(); " +
                            "    if(entry) scanEntry(entry, ''); " +
                            "  } " +
                            "});" +

                            "function loadNavSettings() {" +
                            "  fetch('/api/navidrome-settings').then(r=>r.json()).then(d => {" +
                            "    document.getElementById('navUrl').value = d.url || '';" +
                            "    document.getElementById('navUser').value = d.user || '';" +
                            "    document.getElementById('navPass').value = d.pass || '';" +
                            "    document.getElementById('navStatus').innerText = d.url ? '✅ Settings loaded.' : 'ℹ️ Not configured yet.';" +
                            "  }).catch(()=>{ document.getElementById('navStatus').innerText='⚠️ Could not load.'; });" +
                            "}" +
                            "async function saveNavSettings() {" +
                            "  var url = document.getElementById('navUrl').value.trim();" +
                            "  var user = document.getElementById('navUser').value.trim();" +
                            "  var pass = document.getElementById('navPass').value;" +
                            "  if(!url||!user){document.getElementById('navStatus').innerText='⚠️ URL and username are required.';return;}" +
                            "  document.getElementById('navStatus').innerText='⏳ Saving & testing connection...';" +
                            "  var body = JSON.stringify({url:url,user:user,pass:pass});" +
                            "  try {" +
                            "    let r = await fetch('/api/navidrome-settings', {method:'POST', body:body});" +
                            "    let d = await r.json();" +
                            "    if (d.ok) { document.getElementById('navStatus').innerText='✅ Saved and connected! Open Navidrome on the Y1.'; }" +
                            "    else { document.getElementById('navStatus').innerText='⚠️ Saved, but could not connect: ' + (d.error || 'unknown error'); }" +
                            "  } catch(e) {" +
                            "    document.getElementById('navStatus').innerText='❌ Save request failed: ' + e;" +
                            "  }" +
                            "}" +

                            "window.onload = function(){ loadList(); loadNavSettings(); };" +
                            "</script></body></html>";

    public Y1WebServer(Context context) {
        this.context = context;
    }

    public void run() {
        try {
            serverSocket = new ServerSocket(8080);
            while (running) {
                Socket socket = serverSocket.accept();
                connectionPool.execute(new RequestHandler(socket));
            }
        } catch (Exception e) {
            if (running) Log.e(TAG, "Server loop stopped", e);
        }
    }

    public void stopServer() {
        running = false;
        try { if (serverSocket != null) serverSocket.close(); } catch(Exception e){ Log.w(TAG, "Error closing server socket", e); }
        connectionPool.shutdownNow();
    }

    // WifiManager.getConnectionInfo().getIpAddress() is a cached snapshot that can go stale
    // after a DHCP lease renewal or reconnect, so read the live interface address instead.
    public String getLocalIpAddress() {
        try {
            java.util.Enumeration<java.net.NetworkInterface> ifaces = java.net.NetworkInterface.getNetworkInterfaces();
            while (ifaces.hasMoreElements()) {
                java.net.NetworkInterface iface = ifaces.nextElement();
                if (!iface.isUp() || iface.isLoopback()) continue;
                java.util.Enumeration<java.net.InetAddress> addrs = iface.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    java.net.InetAddress addr = addrs.nextElement();
                    if (!addr.isLoopbackAddress() && addr instanceof java.net.Inet4Address) {
                        return addr.getHostAddress();
                    }
                }
            }
            return "Unknown IP";
        } catch (Exception ex) { Log.w(TAG, "Could not resolve local IP", ex); return "Unknown IP"; }
    }

    // Resolves a client-supplied relative path against rootFolder and rejects
    // anything that escapes it via ".." traversal (verified: Java's File(parent, child)
    // does NOT discard an absolute child, but canonicalization still collapses "..").
    private File resolveSafePath(String relativePath) throws java.io.IOException {
        File target = (relativePath == null || relativePath.isEmpty()) ? rootFolder : new File(rootFolder, relativePath);
        String rootCanonical = rootFolder.getCanonicalPath();
        String targetCanonical = target.getCanonicalPath();
        if (!targetCanonical.equals(rootCanonical) && !targetCanonical.startsWith(rootCanonical + File.separator)) {
            throw new java.io.IOException("Path escapes root folder: " + relativePath);
        }
        return target;
    }

    private void deleteFileOrFolder(File fileOrDirectory) {
        if (fileOrDirectory.isDirectory()) {
            File[] children = fileOrDirectory.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteFileOrFolder(child);
                }
            }
        }
        fileOrDirectory.delete();
    }

    private class RequestHandler implements Runnable {
        private Socket socket;
        public RequestHandler(Socket socket) { this.socket = socket; }

        private String readHeaderLine(InputStream is) throws java.io.IOException {
            StringBuilder sb = new StringBuilder();
            int c;
            while ((c = is.read()) != -1) {
                if (c == '\r') continue;
                if (c == '\n') break;
                sb.append((char) c);
            }
            return sb.toString();
        }

        public void run() {
            try {
                // One BufferedInputStream for the whole request: header lines are read
                // byte-by-byte (cheap now that they hit the buffer) and any request body
                // is read from this SAME stream so already-buffered body bytes aren't lost.
                InputStream is = new BufferedInputStream(socket.getInputStream());
                OutputStream os = socket.getOutputStream();

                String requestLine = readHeaderLine(is);
                if (requestLine == null || requestLine.isEmpty()) return;

                String[] parts = requestLine.split(" ");
                String method = parts[0];
                String path = parts[1];

                int contentLength = 0;
                String rangeHeader = null;
                String line;
                while (!(line = readHeaderLine(is)).isEmpty()) {
                    String lower = line.toLowerCase();
                    if (lower.startsWith("content-length:")) {
                        contentLength = Integer.parseInt(line.split(":")[1].trim());
                    } else if (lower.startsWith("range:")) {
                        rangeHeader = line.substring(6).trim();
                    }
                }

                // 1. Send the screen UI (frontend - inline player + 🚀 text editor built in + 🚀 drag & drop support)
                if (method.equals("GET") && path.equals("/")) {
                    String html = FILE_MANAGER_HTML;

                    os.write(("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=UTF-8\r\n\r\n" + html).getBytes("UTF-8"));
                }
                // 2. [API] Returns the file and folder list (JSON format)
                else if (method.equals("GET") && path.startsWith("/api/list")) {
                    String q = path.contains("?") ? path.split("\\?")[1] : "";
                    String dirStr = "";
                    if (q.startsWith("dir=")) dirStr = URLDecoder.decode(q.substring(4), "UTF-8");

                    File targetDir = resolveSafePath(dirStr);
                    StringBuilder json = new StringBuilder("[");

                    if (targetDir.exists() && targetDir.isDirectory()) {
                        File[] files = targetDir.listFiles();
                        if (files != null) {
                            for (int i=0; i<2; i++) {
                                for (File f : files) {
                                    boolean isDir = f.isDirectory();
                                    if ((i == 0 && isDir) || (i == 1 && !isDir)) {
                                        if (json.length() > 1) json.append(",");
                                        json.append("{\"name\":\"").append(f.getName().replace("\"", "\\\"")).append("\",\"isDir\":").append(isDir).append("}");
                                    }
                                }
                            }
                        }
                    }
                    json.append("]");
                    os.write(("HTTP/1.1 200 OK\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n" + json.toString()).getBytes("UTF-8"));
                }

                // 3. [API] Create folder
                else if (method.equals("POST") && path.startsWith("/api/create")) {
                    String q = path.split("\\?")[1];
                    String[] params = q.split("&");
                    String dirStr = "", name = "";
                    for (String p : params) {
                        if (p.startsWith("dir=")) dirStr = URLDecoder.decode(p.substring(4), "UTF-8");
                        if (p.startsWith("name=")) name = URLDecoder.decode(p.substring(5), "UTF-8");
                    }
                    File newDir = resolveSafePath(dirStr.isEmpty() ? name : dirStr + "/" + name);
                    newDir.mkdirs();
                    os.write("HTTP/1.1 200 OK\r\n\r\nOK".getBytes("UTF-8"));
                }

                // 4. [API] Delete file or folder
                else if (method.equals("POST") && path.startsWith("/api/delete")) {
                    String q = path.split("\\?")[1];
                    String targetPath = URLDecoder.decode(q.substring(5), "UTF-8");
                    File targetFile = resolveSafePath(targetPath);
                    if (targetFile.exists()) {
                        deleteFileOrFolder(targetFile);
                    }
                    os.write("HTTP/1.1 200 OK\r\n\r\nOK".getBytes("UTF-8"));
                }

                // 🚀 [fix 5] [API] File/folder rename engine!
                else if (method.equals("POST") && path.startsWith("/api/rename")) {
                    String q = path.split("\\?")[1];
                    String[] params = q.split("&");
                    String dirStr = "", oldName = "", newName = "";
                    for (String p : params) {
                        if (p.startsWith("dir=")) dirStr = URLDecoder.decode(p.substring(4), "UTF-8");
                        if (p.startsWith("old=")) oldName = URLDecoder.decode(p.substring(4), "UTF-8");
                        if (p.startsWith("new=")) newName = URLDecoder.decode(p.substring(4), "UTF-8");
                    }

                    File oldFile = resolveSafePath(dirStr.isEmpty() ? oldName : dirStr + "/" + oldName);
                    File newFile = resolveSafePath(dirStr.isEmpty() ? newName : dirStr + "/" + newName);

                    // Only rename safely when the existing file exists and no file with the new name already exists
                    if (oldFile.exists() && !newFile.exists()) {
                        oldFile.renameTo(newFile);
                    }
                    os.write("HTTP/1.1 200 OK\r\n\r\nOK".getBytes("UTF-8"));
                }

                // [API] Navidrome settings – GET
                else if (method.equals("GET") && path.equals("/api/navidrome-settings")) {
                    android.content.SharedPreferences prefs = context.getSharedPreferences("Y1Prefs", android.content.Context.MODE_PRIVATE);
                    String navUrl = prefs.getString("navidrome_url", "");
                    String navUser = prefs.getString("navidrome_user", "");
                    String navPass = prefs.getString("navidrome_pass", "");
                    String json = "{\"url\":\"" + navUrl.replace("\"","\\\"") + "\",\"user\":\"" + navUser.replace("\"","\\\"") + "\",\"pass\":\"" + navPass.replace("\"","\\\"") + "\"}";
                    os.write(("HTTP/1.1 200 OK\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n" + json).getBytes("UTF-8"));
                }

                // [API] Navidrome settings – POST
                else if (method.equals("POST") && path.equals("/api/navidrome-settings")) {
                    byte[] bodyBytes = new byte[Math.min(contentLength, 4096)];
                    int totalRead = 0;
                    while (totalRead < bodyBytes.length) {
                        int r = is.read(bodyBytes, totalRead, bodyBytes.length - totalRead);
                        if (r == -1) break;
                        totalRead += r;
                    }
                    String body = new String(bodyBytes, 0, totalRead, "UTF-8");
                    boolean pingOk = false;
                    String pingError = "Invalid request";
                    try {
                        org.json.JSONObject obj = new org.json.JSONObject(body);
                        String navUrl = obj.optString("url", "").trim().replaceAll("/+$", "");
                        String navUser = obj.optString("user", "").trim();
                        String navPass = obj.optString("pass", "");
                        context.getSharedPreferences("Y1Prefs", android.content.Context.MODE_PRIVATE).edit()
                                .putString("navidrome_url", navUrl)
                                .putString("navidrome_user", navUser)
                                .putString("navidrome_pass", navPass)
                                .apply();
                        com.themoon.y1.subsonic.SubsonicClient.getInstance().saveSettings(context, navUrl, navUser, navPass);
                        if (com.themoon.y1.subsonic.SubsonicClient.getInstance().isConfigured()) {
                            com.themoon.y1.subsonic.NavidromeProxyServer.ensureStarted();
                        }

                        // Actually test the new settings against the server rather than just
                        // trusting the save — this is what was silently failing before: the
                        // Y1 kept showing the previous server's cached artist list with no
                        // indication the new URL/login didn't work.
                        final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
                        final boolean[] okHolder = {false};
                        final String[] errHolder = {"Timed out waiting for server"};
                        com.themoon.y1.subsonic.SubsonicClient.getInstance().ping(
                                new com.themoon.y1.subsonic.SubsonicClient.Callback<Boolean>() {
                                    @Override public void onSuccess(Boolean result) { okHolder[0] = true; latch.countDown(); }
                                    @Override public void onError(String message) { errHolder[0] = message; latch.countDown(); }
                                });
                        latch.await(8, java.util.concurrent.TimeUnit.SECONDS);
                        pingOk = okHolder[0];
                        pingError = errHolder[0];
                    } catch (Exception e) {
                        pingError = e.getMessage() != null ? e.getMessage() : "Save failed";
                    }
                    String json = pingOk
                            ? "{\"ok\":true}"
                            : "{\"ok\":false,\"error\":\"" + (pingError == null ? "Unknown error" : pingError.replace("\\", "\\\\").replace("\"", "\\\"")) + "\"}";
                    os.write(("HTTP/1.1 200 OK\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n" + json).getBytes("UTF-8"));
                }

                // 5. [API] Read file (streaming, download, load code)
                else if (method.equals("GET") && path.startsWith("/api/file")) {
                    String q = path.split("\\?")[1];
                    String targetPath = URLDecoder.decode(q.substring(5), "UTF-8");
                    File targetFile = resolveSafePath(targetPath);

                    if (!targetFile.exists() || targetFile.isDirectory()) {
                        os.write("HTTP/1.1 404 Not Found\r\n\r\nNot Found".getBytes("UTF-8"));
                    } else {
                        String mimeType = "application/octet-stream";
                        String lowerName = targetFile.getName().toLowerCase();
                        if (lowerName.endsWith(".mp3")) mimeType = "audio/mpeg";
                        else if (lowerName.endsWith(".flac")) mimeType = "audio/flac";
                        else if (lowerName.endsWith(".wav")) mimeType = "audio/wav";
                        else if (lowerName.endsWith(".ogg")) mimeType = "audio/ogg";
                        else if (lowerName.endsWith(".m4a") || lowerName.endsWith(".aac")) mimeType = "audio/mp4";
                        else if (lowerName.endsWith(".jpg") || lowerName.endsWith(".jpeg")) mimeType = "image/jpeg";
                        else if (lowerName.endsWith(".png")) mimeType = "image/png";
                        else if (lowerName.endsWith(".json")) mimeType = "application/json";
                            // 🚀 [fix 2] Explicitly makes the browser treat these newly added file types as plain text so they open in the editor window!
                        else if (lowerName.endsWith(".txt") || lowerName.endsWith(".m3u") || lowerName.endsWith(".m3u8") || lowerName.endsWith(".eq")) mimeType = "text/plain";

                        long fileLen = targetFile.length();

                        // Honour a "Range: bytes=start-[end]" request so browser audio seeks
                        // fetch only the requested slice (206) instead of re-streaming from 0.
                        long start = 0;
                        long end = fileLen - 1;
                        boolean partial = false;
                        if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
                            String spec = rangeHeader.substring(6).trim();
                            int dash = spec.indexOf('-');
                            if (dash >= 0) {
                                String startStr = spec.substring(0, dash).trim();
                                String endStr = spec.substring(dash + 1).trim();
                                try {
                                    if (startStr.isEmpty() && !endStr.isEmpty()) {
                                        // Suffix range "bytes=-N" => the LAST N bytes of the file.
                                        long n = Long.parseLong(endStr);
                                        if (n > fileLen) n = fileLen;
                                        start = fileLen - n;
                                        end = fileLen - 1;
                                    } else {
                                        if (!startStr.isEmpty()) start = Long.parseLong(startStr);
                                        if (!endStr.isEmpty()) end = Long.parseLong(endStr);
                                        if (end > fileLen - 1) end = fileLen - 1;
                                    }
                                    if (start >= 0 && start <= end) partial = true;
                                } catch (NumberFormatException nfe) {
                                    partial = false;
                                    start = 0;
                                    end = fileLen - 1;
                                }
                            }
                        }

                        String header;
                        if (partial) {
                            long contentLen = end - start + 1;
                            header = "HTTP/1.1 206 Partial Content\r\n" +
                                    "Content-Type: " + mimeType + "\r\n" +
                                    "Content-Length: " + contentLen + "\r\n" +
                                    "Content-Range: bytes " + start + "-" + end + "/" + fileLen + "\r\n" +
                                    "Accept-Ranges: bytes\r\n\r\n";
                        } else {
                            header = "HTTP/1.1 200 OK\r\n" +
                                    "Content-Type: " + mimeType + "\r\n" +
                                    "Content-Length: " + fileLen + "\r\n" +
                                    "Accept-Ranges: bytes\r\n\r\n";
                        }
                        os.write(header.getBytes("UTF-8"));

                        FileInputStream fis = new FileInputStream(targetFile);
                        try {
                            if (partial && start > 0) {
                                long toSkip = start;
                                while (toSkip > 0) {
                                    long skipped = fis.skip(toSkip);
                                    if (skipped <= 0) break;
                                    toSkip -= skipped;
                                }
                            }
                            byte[] buffer = new byte[8192];
                            int bytesRead;
                            if (partial) {
                                long remaining = end - start + 1;
                                while (remaining > 0
                                        && (bytesRead = fis.read(buffer, 0, (int) Math.min(buffer.length, remaining))) != -1) {
                                    os.write(buffer, 0, bytesRead);
                                    remaining -= bytesRead;
                                }
                            } else {
                                while ((bytesRead = fis.read(buffer)) != -1) {
                                    os.write(buffer, 0, bytesRead);
                                }
                            }
                        } finally {
                            fis.close();
                        }
                    }
                }

                // 6. [API] File upload
                else if (method.equals("POST") && path.startsWith("/api/upload")) {
                    String q = path.split("\\?")[1];
                    String[] params = q.split("&");
                    String dirStr = "", name = "unnamed.file";
                    for (String p : params) {
                        if (p.startsWith("dir=")) dirStr = URLDecoder.decode(p.substring(4), "UTF-8");
                        if (p.startsWith("name=")) name = URLDecoder.decode(p.substring(5), "UTF-8");
                    }

                    File targetDir = resolveSafePath(dirStr);
                    if (!targetDir.exists()) targetDir.mkdirs();
                    File outFile = resolveSafePath(dirStr.isEmpty() ? name : dirStr + "/" + name);

                    FileOutputStream fos = new FileOutputStream(outFile);
                    try {
                        byte[] buffer = new byte[8192];
                        int bytesRead;
                        int totalRead = 0;
                        while (totalRead < contentLength && (bytesRead = is.read(buffer, 0, Math.min(buffer.length, contentLength - totalRead))) != -1) {
                            fos.write(buffer, 0, bytesRead);
                            totalRead += bytesRead;
                        }
                        fos.flush();
                        try {
                            fos.getFD().sync();
                        } catch (Exception e) {
                            Log.d(TAG, "fsync failed after upload write", e);
                        }
                    } finally {
                        fos.close();
                    }

                    os.write("HTTP/1.1 200 OK\r\n\r\nOK".getBytes("UTF-8"));
                }

                // 7. [API] 🚀 Save text file (overwrites the on-device file with text sent from the code editor)
                else if (method.equals("POST") && path.startsWith("/api/save")) {
                    String q = path.split("\\?")[1];
                    String targetPath = URLDecoder.decode(q.substring(5), "UTF-8");
                    File targetFile = resolveSafePath(targetPath);

                    FileOutputStream fos = new FileOutputStream(targetFile);
                    try {
                        byte[] buffer = new byte[8192];
                        int bytesRead;
                        int totalRead = 0;
                        // Write the received text body straight through to the file as-is.
                        while (totalRead < contentLength && (bytesRead = is.read(buffer, 0, Math.min(buffer.length, contentLength - totalRead))) != -1) {
                            fos.write(buffer, 0, bytesRead);
                            totalRead += bytesRead;
                        }
                        fos.flush();
                        try {
                            fos.getFD().sync();
                        } catch (Exception e) {
                            Log.d(TAG, "fsync failed after upload write", e);
                        }
                    } finally {
                        fos.close();
                    }

                    os.write("HTTP/1.1 200 OK\r\n\r\nOK".getBytes("UTF-8"));
                }

                os.flush();
            } catch (Exception e) {
                Log.w(TAG, "Request failed", e);
            } finally {
                try { socket.close(); } catch (Exception e) { /* socket already gone */ }
            }
        }
    }
}
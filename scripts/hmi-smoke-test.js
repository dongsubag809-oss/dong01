"use strict";
// Offline regression checks for the bundled HMI UI on industrial Android WebView.
const fs = require("fs");
const vm = require("vm");
const assert = require("assert");
const html = fs.readFileSync("app/src/main/assets/index.html", "utf8");
const scripts = [...html.matchAll(/<script(?:\s[^>]*)?>([\s\S]*?)<\/script>/g)].map(m => m[1]);
assert(scripts.length >= 2, "Expected independent error listener and main HMI script");
const main = scripts[scripts.length - 1];
assert(!/\b(?:let|const)\s+[A-Za-z_$]|=>|\.padStart\s*\(|Array\.from\s*\(/.test(main), "Old WebView incompatible JavaScript");
scripts.forEach(s => new vm.Script(s, {filename: "index.html inline"}));
const nodes = {};
function node(id) {
  if (!nodes[id]) nodes[id] = {
    id, value: id === "editHouse" || id === "doPlcId" ? "1" : "",
    children: [], innerHTML: "", textContent: "", scrollTop: 0,
    classList: {toggle() {},add() {},remove() {}},
    getAttribute(attr) {return attr === "maxlength" ? "40" : null;}
  };
  return nodes[id];
}
const pages = [node("dashboard"),node("io"),node("names")];
const nav = [{getAttribute:()=> "dashboard",classList:{toggle(){}}},{getAttribute:()=> "io",classList:{toggle(){}}}];
const doc = {
  getElementById:node,
  getElementsByClassName(name){return name==="page" ? pages : [];},
  querySelectorAll(selector){return selector==="nav button" ? nav : [];},
  querySelector(selector){return selector==="main"?node("main"):null;}
};
const saved = {};
const ctx = vm.createContext({
  document:doc,
  window:{},
  localStorage:{getItem(key){return saved[key]||null;},setItem(k,v){saved[k]=v;}},
  setInterval(){},
  Date,JSON,console
});
scripts.forEach(s=>vm.runInContext(s,ctx,{timeout:4000}));
assert(nodes.houses.innerHTML.includes("통신 대기"), "House dashboard did not render");
assert(nodes.doRegistry.innerHTML.includes("DO01")&&nodes.doRegistry.innerHTML.includes("DO64"), "128-DO label rendering failed");
ctx.show("io");
assert.strictEqual(nodes.main.scrollTop,0,"Menu navigation did not run");
assert(typeof ctx.openCom5==="function","COM5 open handler absent");
ctx.kbBase = "";
ctx.kbState = {l:"",v:"",t:""};
"ㅎㅏㄴㄱㅡㄹ".split("").forEach(ch=>ctx.kbWriteJamo(ch));
assert.strictEqual(ctx.kbText(),"한글","Korean composition failed");
assert(html.includes("onclick=\"openKeyboard(this)\""),"Korean keyboard event wiring absent");

// Restore in-app OTA without removing N4D4F64 read-only diagnostics.
const updater = fs.readFileSync("app/src/main/java/com/energyblue/househmi/ReleaseUpdater.java","utf8");
const manifest = fs.readFileSync("app/src/main/AndroidManifest.xml","utf8");
const paths = fs.readFileSync("app/src/main/res/xml/update_file_paths.xml","utf8");
assert(html.includes("3. 설치 승인"),"In-app OTA install button was not restored");
assert(html.includes("call('installUpdate')"),"HMI install button callback is missing");
assert(updater.includes("FileProvider.getUriForFile"),"User-approved verified APK URI is missing");
assert(updater.includes("Intent.ACTION_VIEW"),"Android installer intent is missing");
assert(updater.includes("Intent.FLAG_GRANT_READ_URI_PERMISSION"),"Android temporary APK read permission missing");
assert(updater.includes("canRequestPackageInstalls()"),"Android 8+ unknown-source install permission check missing");
assert(!updater.includes("ACTION_CREATE_DOCUMENT")&&!updater.includes("onExportResult"),"Obsolete save-only OTA flow remains");
assert(manifest.includes("android.permission.REQUEST_INSTALL_PACKAGES"),"User-approved install permission missing");
assert(manifest.includes("androidx.core.content.FileProvider"),"APK FileProvider missing");
assert(manifest.includes('android:exported="false"'),"APK provider must not be exported");
assert(paths.includes('path="ota/"'),"Restricted verified APK cache mapping missing");
assert(updater.includes("SHA-256")&&updater.includes("MessageDigest"),"Verified download regression");
assert(html.includes("PLC ID 1·2 실제 데이터 읽기"),"PLC FC03 diagnostics regressed");

console.log("PASS: WebView legacy syntax; render; 64-DO registry; menus; COM5 handler; Korean keyboard; user-approved verified APK install.");

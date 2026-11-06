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
assert(!updater.includes("canRequestPackageInstalls()"),"Undeclared install permission may not be requested");
assert(updater.includes("if(android.os.Build.VERSION.SDK_INT>=26)"),"Android 7 vs 8 installer branch missing");
assert(updater.includes("ACTION_CREATE_DOCUMENT")&&updater.includes("onExportResult"),"Android 8+ manual file-export fallback missing");
assert(!manifest.includes("android.permission.REQUEST_INSTALL_PACKAGES"),"V4.90 working baseline does not request APK installer permission");
assert(manifest.includes("androidx.core.content.FileProvider"),"APK FileProvider missing");
assert(manifest.includes('android:exported="false"'),"APK provider must not be exported");
assert(paths.includes('path="ota/"'),"Restricted verified APK cache mapping missing");
assert(updater.includes("SHA-256")&&updater.includes("MessageDigest"),"Verified download regression");
assert(html.includes("PLC ID 1·2 실제 데이터 읽기"),"PLC FC03 diagnostics regressed");


// Real DO buttons are generated from received state, not requested state.
const shown = ctx.plcGrid("0".repeat(64),"DO 01~64",1,true);
assert(shown.includes("tapDo(1,1)")&&shown.includes("tapDo(1,64)"),"All DO tiles must be touchable");
assert.strictEqual((shown.match(/<button /g)||[]).length,64,"DO 1~64 count");
assert(!ctx.plcGrid("0".repeat(64),"DI 01~64",2,false).includes("tapDo("),"DI must not control output");
let requested=null,armingModes=null;
ctx.window.confirm=()=>true;
ctx.window.NativeDiagnostics={
 armManualOutputs:(m1,m2)=>{armingModes=[m1,m2];},
 disarmManualOutputs:()=>"잠금",
 setDoOutput:(id,ch,on,mode)=>{requested={id,ch,on,mode};}
};
node("plcSafetyAck").checked=true;
node("plcMode1").value="default";
ctx.plcReceived(JSON.stringify({id:1,status:"OK",di:"0".repeat(64),do:"0".repeat(64),diSummary:"",doSummary:"",mode:"기본 모드"}));
ctx.armDoTest();
assert.strictEqual(ctx.plcArmed,false,"Outputs must remain locked during asynchronous fresh scan");
assert(armingModes&&armingModes[0]==="default","PLC commissioning must request actual configured M0 modes");
ctx.window.nativeMessage(JSON.stringify({type:"plcArm",message:"ARM_OK · PLC ID 1/2 fresh CRC verified"}));
assert.strictEqual(ctx.plcArmed,true,"Only confirmed PLC fresh scan may enable DO");
ctx.tapDo(1,64);
assert(requested&&requested.id===1&&requested.ch===64&&requested.on===true&&requested.mode==="default","ON request must use real readback OFF state");
ctx.plcWriteReceived(JSON.stringify({id:1,ch:64,status:"CONFIRMED",message:"실수신 일치"}));
ctx.plcReceived(JSON.stringify({id:1,status:"OK",di:"0".repeat(64),do:"0".repeat(63)+"1",diSummary:"",doSummary:"",mode:"기본 모드"}));
ctx.tapDo(1,64);
assert(requested.on===false,"OFF request must follow confirmed PLC ON state");
ctx.plcWriteReceived(JSON.stringify({id:1,ch:64,status:"UNKNOWN",message:"unknown"}));
assert.strictEqual(ctx.plcDoStates[1],null,"Unknown physical output must invalidate displayed state");
assert.strictEqual(ctx.plcArmed,false,"Unconfirmed output disables commissioning");
assert(html.includes("실제 출력 시험 안전 확인")&&html.includes("0.5초"),"Commissioning warning and interlock note missing");
const mainSrc=fs.readFileSync("app/src/main/java/com/energyblue/househmi/MainActivity.java","utf8");
assert(mainSrc.includes("ModbusReadOnly.safetyProblem"),"Remote/relationship safety guard missing");
assert(mainSrc.includes("void armManualOutputs(String mode1,String mode2)"),"Asynchronous arm with fresh M0 modes missing");
assert(mainSrc.includes("outputArmUntil=android.os.SystemClock.elapsedRealtime()+OUTPUT_ARM_MS"),"Arm must start AFTER PLC scans");
assert(mainSrc.includes("if(block!=null)throw new java.io.IOException(block)"),"Unsafe remote output config must deny arming");
ctx.window.nativeMessage(JSON.stringify({type:"plcArm",message:"수동 출력 시험 차단: ID 1 R245=1"}));
assert.strictEqual(ctx.plcArmed,false,"Failed safety register check must lock DO");
assert(node("plcArmStatus").textContent.includes("R245=1"),"Block reason must reach visible HMI");

console.log("PASS: WebView legacy syntax; render; 64-DO registry; menus; COM5 handler; Korean keyboard; user-approved verified APK install; guarded 128-channel DO taps and real readback.");

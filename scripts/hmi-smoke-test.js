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
assert(html.includes("선택 PLC 실제 데이터 읽기"),"PLC FC03 diagnostics regressed");
assert(html.includes('id="plcTestMode"'),"Commissioning mode selector missing");
assert(html.includes('value="single"')&&html.includes('value="bench"'),"Both commissioning modes must be present");
assert(html.includes('value="1" selected>1장 · ID 1만 시험'),"Default single-ID1 test mode missing");


// Real DO buttons are generated from received state, not requested state.
const shown = ctx.plcGrid("0".repeat(64),"DO 01~64",1,true);
assert(shown.includes("tapDo(1,1)")&&shown.includes("tapDo(1,64)"),"All DO tiles must be touchable");
assert.strictEqual((shown.match(/<button /g)||[]).length,64,"DO 1~64 count");
assert(!ctx.plcGrid("0".repeat(64),"DI 01~64",2,false).includes("tapDo("),"DI must not control output");
let requested=null,armingModes=null,readBoardCount=null;
ctx.window.confirm=()=>true;
ctx.window.NativeDiagnostics={
 armManualOutputs:(m1,m2,count,bench)=>{armingModes=[m1,m2,count,bench];},
 readPlc:(m1,m2,count)=>{readBoardCount=count;},
 disarmManualOutputs:()=>"잠금",
 setDoOutput:(id,ch,on,mode)=>{requested={id,ch,on,mode};}
};
node("plcSafetyAck").checked=true;
node("plcMode1").value="default";
node("plcMode2").value="default";
node("plcBoardCount").value="1";
assert.strictEqual(ctx.selectedPlcCount(),1,"Single-board default selector read");
ctx.readBothPlcs();
assert.strictEqual(readBoardCount,1,"Read must NOT poll absent ID2");
node("plcSafetyAck").checked=true;
ctx.plcReceived(JSON.stringify({id:1,status:"OK",di:"0".repeat(64),do:"0".repeat(64),diSummary:"",doSummary:"",mode:"기본 모드"}));
ctx.armDoTest();
assert.strictEqual(ctx.plcArmed,false,"Outputs must remain locked during asynchronous fresh scan");
assert(armingModes&&armingModes[0]==="default"&&armingModes[2]===1,"PLC commissioning must arm only physical ID1");
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
assert(html.includes("부하 미연결 상태를 먼저 확인")&&html.includes("0.5초"),"Commissioning warning and interlock note missing");
const mainSrc=fs.readFileSync("app/src/main/java/com/energyblue/househmi/MainActivity.java","utf8");
assert(mainSrc.includes("ModbusReadOnly.safetyProblem"),"Remote/relationship safety guard missing");
assert(mainSrc.includes("void armManualOutputs(String mode1,String mode2,int boardCount,boolean noLoadBench)"),"Asynchronous arming bridge signature missing");
assert(mainSrc.includes("outputArmUntil=-1;"),"Session-only arming without timer missing");
assert(!mainSrc.includes("OUTPUT_ARM_MS"),"Obsolete sixty-second authorization timer remains");
assert(mainSrc.includes("if(blocked!=null)throw new java.io.IOException(blocked)"),"Unsafe remote output config must deny arming");
ctx.window.nativeMessage(JSON.stringify({type:"plcArm",message:"수동 출력 시험 차단: ID 1 R245=1"}));
assert.strictEqual(ctx.plcArmed,false,"Failed safety register check must lock DO");
assert(node("plcArmStatus").textContent.includes("R245=1"),"Block reason must reach visible HMI");
node("plcTestMode").value="bench";
node("plcSafetyAck").checked=true;
ctx.plcReceived(JSON.stringify({id:1,status:"OK",di:"0".repeat(64),do:"0".repeat(64),diSummary:"",doSummary:"",mode:"기본 모드"}));
ctx.armDoTest();
assert.strictEqual(armingModes[3],true,"HMI bench selection must reach native bridge");
ctx.window.nativeMessage(JSON.stringify({type:"plcArm",message:"ARM_OK · ID1 bench mode"}));
assert.strictEqual(ctx.plcArmDeadline,-1,"Bench mode uses a session flag, not the 60-second timer");
ctx.disarmDoTest();
assert.strictEqual(ctx.plcArmed,false,"Explicit test lock must revoke arming");
node("plcBoardCount").value="2";
ctx.plcBoardCountChanged();
assert.strictEqual(node("plcMode2").disabled,false,"ID2 mode must enable in two-board mode");
ctx.readBothPlcs();
assert.strictEqual(readBoardCount,2,"Two-board mode must retain ID1 and ID2 polling");
node("plcBoardCount").value="1";
ctx.plcBoardCountChanged();
assert.strictEqual(node("plcMode2").disabled,true,"ID2 mode must be disabled when board is absent");
assert(node("plcBoard2").textContent.includes("시험 제외"),"Absent ID2 status must not be shown as comms failure");
assert.strictEqual(ctx.plcArmed,false,"Board configuration changes must disarm real outputs");
ctx.tapDo(2,1);
assert(node("plcReadLog").textContent.includes("미연결"),"ID2 output must be blocked in single mode");
const source=fs.readFileSync("app/src/main/java/com/energyblue/househmi/MainActivity.java","utf8");
assert(source.includes("for(int slave=1;slave<=boardCount;slave++)"),"ID1-only read must not interrogate absent board");
assert(source.includes("for(int id=1;id<=boardCount;id++)"),"Arming must not require absent ID2");
assert(source.includes("scanActiveBoards(modes,boardCount)"),"Output guard must scan only physically connected boards");
assert(source.includes("id<=count")&&source.includes("otherOutputActive"),"Single-active-channel safety must remain");
assert(source.includes("slave>activeBoards()"),"Absent ID2 output must not be enabled");
assert(source.includes("armingTicket!=armSequence"),"Disarmed while PLC scan underway must not be approved");
assert(source.includes("activeBoardCount=1"),"One connected ID1 board must be the native default");
assert(source.includes("(count==1||lastGoodRead[1]>0)"),"ID2 read required ONLY when two boards connected");

// ID1/ID2 automatic connection must reflect verified responses, not a port-open result.
assert(html.includes('id="plcLink1"')&&html.includes('id="plcLink2"'),"Live PLC status fields missing");
assert(html.includes('id="manualRawId"')&&html.includes('id="manualRawChannels"'),"128-channel real manual control panel missing");
assert(html.includes("DI 01~64 · 실제 수신 상태"),"Manual DI receive indicators missing");
const autoSrc=fs.readFileSync("app/src/main/java/com/energyblue/househmi/MainActivity.java","utf8");
const serialSrc=fs.readFileSync("app/src/main/java/com/energyblue/househmi/SerialPort.java","utf8");
assert(autoSrc.includes("ui.postDelayed(plcAutoPoll,1500)"),"PLC automatic startup monitoring missing");
assert(autoSrc.includes("for(int id=1;id<=2;id++)"),"Both PLC IDs must be checked independently");
assert(autoSrc.includes('sendLink(id,true,"CRC 확인'),"Connected status must follow actual Modbus receive");
assert(autoSrc.includes('sendLink(id,false,message)'),"Offline status must follow Modbus receive failure");
assert(autoSrc.includes("SerialPort.close(); // Retry port next cycle"),"COM5 reconnect handling missing");
assert(!autoSrc.includes("autoScanOnce(){\n  ModbusOutput.writeOnce"),"Auto-start must never actuate outputs");
assert(serialSrc.includes("isOpen()"),"COM5 automatic open status API missing");
node("manualRawId").value="1";
ctx.window.nativeMessage(JSON.stringify({type:"plcLink",message:JSON.stringify({id:1,connected:true,detail:"CRC OK"})}));
ctx.plcReceived(JSON.stringify({id:1,status:"OK",di:"0".repeat(64),do:"0".repeat(64),diSummary:"ON 0/64",doSummary:"ON 0/64",mode:"기본 모드"}));
assert(node("manualRawChannels").innerHTML.includes("DO64 OFF"),"Real ID1 manual DO64 display missing");
assert(node("manualRawChannels").innerHTML.includes("DI64 OFF"),"Real ID1 manual DI64 display missing");
ctx.window.nativeMessage(JSON.stringify({type:"plcLink",message:JSON.stringify({id:1,connected:false,detail:"TIMEOUT"})}));
assert(!node("manualRawChannels").innerHTML.includes("DO64 OFF"),"Stale DO must disappear after PLC disconnect");
assert(node("plcLink1").textContent.includes("미연결"),"Disconnected status indicator did not update");
// Historic N4D4F64 24-DO/house addressing: sequential across 64-channel board boundary.
assert.strictEqual(ctx.mappedOutputs.length,24,"Each house has exactly 24 real DO mappings");
const seen={};
for(var h=1;h<=5;h++)for(var k=0;k<24;k++){
 var a=ctx.assignedOutput(h,k),key=a.id+"-"+a.ch;
 assert(!seen[key],"Mapped channel duplicated "+key);
 seen[key]=true;
 assert.strictEqual((a.id-1)*64+a.ch,(h-1)*24+k+1,"Historical sequential DO address incorrect");
}
assert.strictEqual(Object.keys(seen).length,120,"Five houses must use 120 distinct DO");
assert.strictEqual(ctx.assignedOutput(3,15).id,1,"House 3 first 16 on ID1");
assert.strictEqual(ctx.assignedOutput(3,16).id,2,"House 3 final 8 on ID2");
assert.strictEqual(ctx.assignedOutput(5,23).ch,56,"House 5 final output ID2 DO56");
for(var spare=57;spare<=64;spare++)
 assert(ctx.defaultDoName(2,spare).includes("예비 출력"),"Unused ID2 outputs must be identified");
assert(ctx.defaultDoName(1,9).includes("내측창 좌측 열림"),"Real inner-window address absent");
assert(ctx.doLabel(1,0).includes("DO09")&&ctx.doLabel(1,0).includes("DO10"),"Legacy device text must show mapped DO");
assert(!ctx.doLabel(1,0).includes("미배정"),"Legacy unmapped warning remains");
ctx.doRegistry["1-9"]="내측 좌측 맞춤 이름";
assert.strictEqual(ctx.assignedDoName(1,9),"내측 좌측 맞춤 이름","User-customized 128-channel label not applied");
ctx.doRegistry["1-9"]="";// clear test override; original user edits remain persisted via localStorage
assert(html.includes('readonly onclick="openKeyboard(this)"'),"Korean editable DO name fields must remain");
ctx.contactNames["1"]=["사용자 내창 좌측"];
assert(ctx.assignedDoName(1,9).includes("사용자 내창 좌측 열림"),"Existing device alias name not reflected in mapped DO label");
delete ctx.contactNames["1"];
assert(!html.includes("window.confirm("),"PLC test must not show confirmation popups");
assert(html.includes('value="bench"'),"128-channel disconnected-load test mode missing");

ctx.selected=1;
ctx.renderMappedHouse();
assert(node("manualControls").innerHTML.includes("ID 1 / DO09"),"Mapped HMI device card missing exact PLC address");
assert(node("manualControls").innerHTML.includes("manualMappedOutput(1,8,true)"),"Mapped device ON button is not wired");
assert(html.includes("if(el('plcTestMode').value!=='bench'&&on&&slot>=4&&slot<20)"),"Normal motor reverse interlock must remain while no-load bench permits mapped channels");
assert(!html.includes("하우스별 모터 제어는 무부하 다채널 시험에서는 차단합니다"),"Mapped greenhouse DO buttons must work in bench mode");
assert(node("manualControls").innerHTML.includes("manualMappedOutput(1,8,false)"),"Mapped device OFF button is not wired");
assert(node("manualControls").innerHTML.includes("disabled"),"Unknown board connection must not enable physical outputs");
ctx.window.nativeMessage(JSON.stringify({type:"plcLink",message:JSON.stringify({id:1,connected:true,detail:"CRC OK"})}));
ctx.plcReceived(JSON.stringify({id:1,status:"OK",di:"0".repeat(64),do:"0".repeat(64),diSummary:"",doSummary:"",mode:"기본 모드"}));
ctx.renderMappedHouse();
assert(!node("manualControls").innerHTML.includes("DO 결선 미배정"),"Legacy unmapped output labels must not appear");
const nativeController=fs.readFileSync("app/src/main/java/com/energyblue/househmi/MainActivity.java","utf8");
assert(!nativeController.includes("noLoadBench&&boardCount!=1"),"ID2 bench mode must not be arbitrarily blocked");
assert(nativeController.includes("outputCommandPending.compareAndSet(false,true)"),"Manual output must queue during automatic scan");
assert(nativeController.includes("!outputCommandPending.get()"),"Automatic polls must yield to pending output");
assert(nativeController.includes("noLoadMultiOutput"),"No-load mode lifetime must remain session-scoped");
// Manual page must have its own arm controls; no hidden I/O trip is required.
assert(html.includes('id="manualOutputBadge"')&&html.includes('id="manualOutputStatus"'),"Manual output status must be visible next to greenhouse controls");
assert(html.includes('id="manualNoLoadAck"'),"Manual page needs explicit no-load confirmation");
assert(html.includes('onclick="startManualBench()"'),"Manual page must offer output-test activation");
assert(html.includes('onclick="stopManualBench()"'),"Manual page must offer a working lock control");
const mainManual=fs.readFileSync("app/src/main/java/com/energyblue/househmi/MainActivity.java","utf8");
assert(mainManual.includes("armRequestPending.compareAndSet(false,true)"),"Arming must queue behind automatic COM5 reads instead of rejecting busy port");
assert(mainManual.includes("!armRequestPending.get()"),"Autopoll must yield to manual output arming");
assert(mainManual.includes("ticket!=armSequence"),"Late/cancelled arming must remain blocked");
node("plcBoardCount").value="1";
node("manualNoLoadAck").checked=true;
ctx.startManualBench();
assert.deepStrictEqual(armingModes,["default","default",1,true],"Manual page must arm connected ID1 no-load session");
assert(node("manualOutputBadge").textContent.includes("출력 잠금"),"Manual outputs must remain locked until native ARM_OK");
ctx.window.nativeMessage(JSON.stringify({type:"plcArm",message:"ARM_OK · ID1 actual CRC and safety checks passed"}));
assert(node("manualOutputBadge").textContent.includes("활성"),"Manual page must show native approved status");
assert.strictEqual(ctx.plcArmDeadline,-1,"Manual output session must not expire after 60 seconds");
ctx.manualMappedOutput(1,0,true);
assert(requested&&requested.id===1&&requested.ch===1&&requested.on===true,"Manual house light DO1 ON must issue real native write");
ctx.plcWriteReceived(JSON.stringify({id:1,ch:1,status:"CONFIRMED",message:"ID1 DO01 실수신 ON"}));
assert(node("manualOutputStatus").textContent.includes("실수신 ON"),"Write confirmation must appear on the actual Manual page");
ctx.stopManualBench();
assert.strictEqual(ctx.plcArmed,false,"Manual lock must revoke output authorization");
assert(node("manualOutputBadge").textContent.includes("출력 잠금"),"Manual page must reflect revocation immediately");
console.log("PASS: WebView legacy syntax; render; 64-DO registry; menus; COM5 handler; Korean keyboard; user-approved verified APK install; guarded one-board ID1 test plus optional two-board DO taps and real readback.");

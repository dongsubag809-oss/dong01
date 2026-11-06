"use strict";
/* HMI V5.01 production-operation regression: no bench/virtual tests in UI. */
const fs=require("fs"),vm=require("vm"),assert=require("assert");
const html=fs.readFileSync("app/src/main/assets/index.html","utf8");
const scripts=Array.from(html.matchAll(/<script(?:\s[^>]*)?>([\s\S]*?)<\/script>/g),m=>m[1]);
assert(scripts.length>=2,"HMI scripts missing");
const main=scripts[scripts.length-1];
assert(!/\b(?:let|const)\s+[A-Za-z_$]|=>|\.padStart\s*\(|Array\.from\s*\(/.test(main),"Legacy Android 7 WebView syntax unsupported");
scripts.forEach((s,i)=>new vm.Script(s,{filename:"hmi-inline-"+i+".js"}));
const nodes={};
function node(id){
 if(!nodes[id])nodes[id]={
  id,value:id==="editHouse"||id==="doPlcId"?"1":"",
  children:[],innerHTML:"",textContent:"",scrollTop:0,
  classList:{toggle(){},add(){},remove(){}},
  setAttribute(name,value){this[name]=value;},
  getAttribute(name){return this[name]||null;}
 };
 return nodes[id];
}
const doc={
 getElementById:node,
 getElementsByClassName(n){return n==="page"?[node("dashboard"),node("io"),node("names")]:[]},
 querySelectorAll(n){return n==="nav button"?[]:[]},
 querySelector(n){return n==="main"?node("main"):null}
};
const saved={};
const ctx=vm.createContext({
 document:doc,window:{},
 localStorage:{getItem:k=>saved[k]||null,setItem:(k,v)=>{saved[k]=v}},
 setInterval(){},Date,JSON,console
});
scripts.forEach(s=>vm.runInContext(s,ctx,{timeout:4000}));
assert(node("houses").innerHTML.includes('id="houseComm1"'),"Per-house dashboard PLC status missing");
assert(node("houseComm1").textContent==="통신 확인 중","Startup must not falsely report good PLC comms");
assert(ctx.houseCommBoards(1).join(",")==="1","House 1 must follow ID1");
assert(ctx.houseCommBoards(2).join(",")==="1","House 2 must follow ID1");
assert(ctx.houseCommBoards(3).join(",")==="1,2","House 3 crosses the ID1/ID2 PLC boundary");
assert(ctx.houseCommBoards(4).join(",")==="2","House 4 must follow ID2");
assert(ctx.houseCommBoards(5).join(",")==="2","House 5 must follow ID2");
assert(html.includes('onclick="activateManualControl()"'),"Actual manual operation enable button missing");
assert(html.includes('onclick="lockManualControl()"'),"Actual manual operation lock button missing");
for(const oldElement of ["manualNoLoadAck","plcSafetyAck","plcTestMode","startManualBench","stopManualBench","armDoTest","disarmDoTest"]){
 assert(!html.includes(oldElement),"Legacy no-load/test control remains: "+oldElement);
}
for(const s of ["무부하","가상 시험","단일 채널 출력 시험"]){
 assert(!html.includes(s),"Non-production testing menu remains: "+s);
}
assert(html.includes("▲ 열림")&&html.includes("■ 정지")&&html.includes("▼ 닫힘"),"Motor open/stop/close labels missing");
assert(html.includes('class="motor-actions"')||html.includes('class=\\"motor-actions\\"')||html.includes("motor-actions"),"Motor vertical control CSS missing");
const updater=fs.readFileSync("app/src/main/java/com/energyblue/househmi/ReleaseUpdater.java","utf8");
const manifest=fs.readFileSync("app/src/main/AndroidManifest.xml","utf8");
assert(html.includes("3. 설치 승인")&&html.includes("call('installUpdate')"),"OTA install controls missing");
assert(updater.includes("FileProvider.getUriForFile")&&updater.includes("MessageDigest"),"Signed verified OTA changed");
assert(!manifest.includes("android.permission.REQUEST_INSTALL_PACKAGES"),"Working baseline installer privilege regressed");
ctx.kbBase="";ctx.kbState={l:"",v:"",t:""};
"ㅎㅏㄴㄱㅡㄹ".split("").forEach(ch=>ctx.kbWriteJamo(ch));
assert.strictEqual(ctx.kbText(),"한글","Korean keyboard composition regressed");
assert(html.includes('onclick="openKeyboard(this)"'),"Editable Korean DO labels removed");
assert.strictEqual(ctx.mappedOutputs.length,24,"Five greenhouse motor/relay layout must keep 24DO per house");
const assigned={};
for(let h=1;h<=5;h++)for(let slot=0;slot<24;slot++){
 let a=ctx.assignedOutput(h,slot),abs=(a.id-1)*64+a.ch;
 assert.strictEqual(abs,(h-1)*24+slot+1,"Wrong historical DO address");
 assert(!assigned[abs],"Duplicate output address");assigned[abs]=1;
}
assert.strictEqual(Object.keys(assigned).length,120,"Expected 120 house outputs");
for(let n=57;n<=64;n++)assert(ctx.defaultDoName(2,n).includes("예비"),"8 spare outputs absent");
ctx.selected=1;
ctx.renderMappedHouse();
const rendered=node("manualControls").innerHTML;
assert.strictEqual((rendered.match(/class="motor-control"/g)||[]).length,8,"Each house must group 8 motor OPEN/CLOSE pairs");
assert.strictEqual((rendered.match(/class="control"/g)||[]).length,8,"Each house must retain 8 independent relay DO controls");
assert(rendered.includes("motorMove(1,4,1)")&&rendered.includes("motorMove(1,4,0)")&&rendered.includes("motorMove(1,4,-1)"),"Motor OPEN/STOP/CLOSE callbacks missing");
assert(rendered.indexOf("▲ 열림")<rendered.indexOf("■ 정지")&&rendered.indexOf("■ 정지")<rendered.indexOf("▼ 닫힘"),"Vertical controls must render OPEN above STOP above CLOSE");
assert(rendered.includes("manualMappedOutput(1,0,true)"),"Real light ON control missing");
let writes=[],motor=[],armed=[],locks=0;
ctx.window.NativeDiagnostics={
 armManualOutputs:(a,b,c)=>{armed=[a,b,c]},
 disarmManualOutputs:()=>{locks++;return "수동 운전 잠금"},
 setDoOutput:(id,ch,on,mode)=>writes.push({id,ch,on,mode}),
 motorCommand:(id,open,close,dir,mode)=>motor.push({id,open,close,dir,mode}),
 readPlc:()=>{},getAutoPlcConfig:()=>"{}"
};
node("plcBoardCount").value="1";
node("plcMode1").value="default";
node("plcMode2").value="default";
ctx.window.nativeMessage(JSON.stringify({type:"plcLink",message:JSON.stringify({id:1,connected:true})}));
assert(node("houseComm1").textContent==="통신 양호"&&node("houseComm1").className.includes("good"),"ID1 CRC link must turn houses 1 and 2 green");
assert(node("houseComm2").textContent==="통신 양호","House 2 shares ID1");
assert(node("houseComm3").textContent==="통신 확인 중","House 3 cannot go green until ID2 also reports actual link");
ctx.window.nativeMessage(JSON.stringify({type:"plcLink",message:JSON.stringify({id:2,connected:false,detail:"no response"})}));
assert(node("houseComm3").textContent==="통신 미연결","House 3 must report bad when ID2 absent");
assert(node("houseComm4").textContent==="통신 미연결","House 4 must report bad when ID2 absent");
ctx.window.nativeMessage(JSON.stringify({type:"plcLink",message:JSON.stringify({id:2,connected:true,detail:"CRC valid"})}));
assert(node("houseComm3").textContent==="통신 양호","House 3 requires both boards");
assert(node("houseComm5").textContent==="통신 양호","House 5 uses ID2");
ctx.window.nativeMessage(JSON.stringify({type:"plcLink",message:JSON.stringify({id:2,connected:false})}));
assert(node("houseComm3").textContent==="통신 미연결","Lost ID2 must revoke house 3 green");
assert(node("houseComm5").textContent==="통신 미연결","Lost ID2 must revoke house 5 green");
ctx.window.nativeMessage(JSON.stringify({type:"plcLink",message:JSON.stringify({id:2,connected:true})}));
const zeros="0".repeat(64);
ctx.plcReceived(JSON.stringify({id:1,status:"OK",di:zeros,do:zeros,diSummary:"ON 0",doSummary:"ON 0",mode:"M0 개방"}));
// The selected motor action MUST fill the whole button, based on actual received DO only.
const manualCss=html.slice(html.indexOf(".motor-actions"),html.indexOf(".onoff-actions"));
assert(manualCss.includes("button.up.motor-selected{background:#14783c"),"Selected OPEN must have full green fill");
assert(manualCss.includes("button.stop.motor-selected{background:#c87919"),"Selected STOP must have full orange fill");
assert(manualCss.includes("button.down.motor-selected{background:#325caa"),"Selected CLOSE must have full blue fill");
function assertMotorChoice(bits,expected,description){
 ctx.plcReceived(JSON.stringify({id:1,status:"OK",di:zeros,do:bits,diSummary:"ON 0",doSummary:"",mode:"M0 개방"}));
 const card=node("manualControls").innerHTML;
 const motor=card.slice(card.indexOf('data-output="id1do5"'),card.indexOf('data-output="id1do7"'));
 assert(motor.includes('class="'+expected+' motor-selected"'),description);
 assert.strictEqual((motor.match(/motor-selected/g)||[]).length,1,"Only one motor choice should be selected");
}
assertMotorChoice(zeros,"stop","Both motor DO OFF -> orange STOP selected");
assertMotorChoice(zeros.slice(0,4)+"1"+zeros.slice(5),"up","PLC DO05 ON -> green OPEN selected");
assertMotorChoice(zeros.slice(0,5)+"1"+zeros.slice(6),"down","PLC DO06 ON -> blue CLOSE selected");
ctx.plcReceived(JSON.stringify({id:1,status:"OK",di:zeros,do:zeros,diSummary:"ON 0",doSummary:"ON 0",mode:"M0 개방"}));

ctx.window.nativeMessage(JSON.stringify({type:"plcLink",message:JSON.stringify({id:2,connected:false})}));
ctx.activateManualControl();
assert.strictEqual(armed[2],1,"Manual activation with only physically present ID1 must choose one board");
assert(!ctx.plcArmed,"Outputs cannot activate until native confirmation");
ctx.window.nativeMessage(JSON.stringify({type:"plcArm",message:"ARM_OK · PLC ID1 verified"}));
assert(ctx.plcArmed,"Actual native CRC confirmation must authorize manual outputs");
assert(node("manualOutputBadge").textContent.includes("활성"),"Manual status badge must reflect real state");
// Selected color MUST come from real PLC DO data, not from tapping a button.
ctx.renderMappedHouse();
var initialCards=node("manualControls").innerHTML;
assert(initialCards.includes("output-off-selected"),"Received DO OFF must highlight the OFF button");
assert(!initialCards.includes("PLC DO 실수신:"),"Redundant textual PLC DO state must be absent");
assert(!initialCards.includes("output-on-selected"),"Received OFF must not show green ON");
ctx.manualMappedOutput(1,0,true);
assert(writes.length===1&&writes[0].id===1&&writes[0].ch===1&&writes[0].on===true,"House light must write real ID1 DO1");
assert(!node("manualControls").innerHTML.includes("output-on-selected"),"Command dispatch alone must not color the ON button");
var readbackOn="1"+zeros.slice(1);
ctx.plcReceived(JSON.stringify({id:1,status:"OK",di:zeros,do:readbackOn,diSummary:"ON 0",doSummary:"ON 1",mode:"M0 개방"}));
assert(node("manualControls").innerHTML.includes("output-on-selected"),"Actual PLC DO ON readback must highlight green ON");
assert(node("manualControls").innerHTML.includes('aria-pressed="true"'),"Selected state must be accessible");
ctx.plcReceived(JSON.stringify({id:1,status:"OK",di:zeros,do:zeros,diSummary:"ON 0",doSummary:"ON 0",mode:"M0 개방"}));
assert(!node("manualControls").innerHTML.includes("output-on-selected"),"Actual DO OFF readback must clear green ON");
assert(node("manualControls").innerHTML.includes("output-off-selected"),"Actual DO OFF must restore selected OFF background");
ctx.plcWriteReceived(JSON.stringify({id:1,ch:1,status:"CONFIRMED",message:"PLC DO1 confirmed ON"}));
ctx.motorMove(1,4,1);
assert(motor.length===1&&motor[0].open===5&&motor[0].close===6&&motor[0].dir===1,"Left shade motor pair must use native guarded OPEN");
ctx.plcWriteReceived(JSON.stringify({id:1,ch:5,status:"CONFIRMED",message:"motor OPEN confirmed"}));
ctx.motorMove(1,4,0);
assert(motor.length===2&&motor[1].dir===0,"Motor STOP must use native paired-off command");
ctx.plcWriteReceived(JSON.stringify({id:1,ch:5,status:"CONFIRMED",message:"motor STOP confirmed"}));
ctx.motorMove(1,4,-1);
assert(motor.length===3&&motor[2].dir===-1,"Motor CLOSE must use native guarded reverse");
ctx.lockManualControl();
assert(!ctx.plcArmed&&locks>0,"Manual output locking lost");
const native=fs.readFileSync("app/src/main/java/com/energyblue/househmi/MainActivity.java","utf8");
assert(native.includes("static int pairedMotorChannel"),"Native motor map interlock missing");
assert(native.includes("validateMotorPairOn(states,slave,channel)"),"Raw DO writes bypass motor interlock");
assert(native.includes("void motorCommand("),"Motor paired action native API missing");
assert(native.includes("Thread.sleep(500)"),"0.5s break-before-make removed");
assert(native.includes("ModbusOutput.writeOnce")&&native.includes("ModbusReadOnly.read64"),"Real FC05/FC06 and received-state confirmation missing");
assert(!native.includes("noLoadMultiOutput")&&!native.includes("noLoadTestAuthorized"),"Unsafe bypass/test authorization remains");
assert(!native.includes("otherOutputActive("),"Unrelated single DO outputs must not block each other");
assert(native.includes("for(int id=1;id<=2;id++)"),"Automatic two-board status monitoring missing");
// HMI phone access mode backgrounds reflect actual server status, never the last click.
assert(html.includes('id="phoneMonitorButton"')&&html.includes('id="phoneControlButton"')&&html.includes('id="phoneStopButton"'),"Phone-mode buttons must have stable DOM identifiers");
assert(html.includes("button.phone-selected.monitor{background:#167147"),"Selected monitor must fill green");
assert(html.includes("button.phone-selected.control{background:#176aad"),"Selected control must fill blue");
assert(html.includes("button.phone-selected.stopped{background:#80512c"),"Selected OFF must have distinct background");
ctx.displayPhoneDetails(JSON.stringify({enabled:false}));
assert(node("phoneStopButton").className.includes("phone-selected"),"Disconnected phone mode must select stopped");
ctx.displayPhoneDetails(JSON.stringify({enabled:true,allowControl:false,port:18443,pairCode:"123456",certificateSha256:"ABCD"}));
assert(node("phoneMonitorButton").className.includes("phone-selected")&&!node("phoneControlButton").className.includes("phone-selected"),"Monitoring mode must select monitoring only");
assert.strictEqual(node("phoneMonitorButton").getAttribute("aria-pressed"),"true","Monitor selection accessible");
ctx.displayPhoneDetails(JSON.stringify({enabled:true,allowControl:true,port:18443,pairCode:"123456",certificateSha256:"ABCD"}));
assert(node("phoneControlButton").className.includes("phone-selected")&&!node("phoneMonitorButton").className.includes("phone-selected"),"Actual control mode must select control instead of monitor");
ctx.displayPhoneDetails("원격 HTTPS 시작 실패: TLS");
assert(node("phoneStopButton").className.includes("phone-selected"),"Server failure must never appear active");
assert(node("phoneStatus").textContent.includes("원격 HTTPS 시작 실패"),"Show original TLS failure on screen");
// Always-visible red emergency button, developer attribution, and physical-result acknowledgement.
assert(html.includes("개발자:박동수"),"Developer attribution missing from header");
assert(html.includes('id="emergencyButton"')&&html.includes('onclick="requestEmergencyStop()"'),"Header emergency button missing");
assert(html.includes("background:#c41423"),"Emergency button must be red and conspicuous");
assert(html.includes('id="emergencyResetButton"'),"Reset must be deliberate and explicit");
assert(html.includes('id="emergencyBanner"'),"Emergency OFF-confirmation/fault status must remain visible");
const emergencySrc=fs.readFileSync("app/src/main/java/com/energyblue/househmi/MainActivity.java","utf8");
assert(emergencySrc.includes("void emergencyStop()"),"Native emergency command missing");
assert(emergencySrc.includes("emergencyLatched.compareAndSet(false,true)"),"Emergency state must latch");
assert(emergencySrc.includes("if(emergencyLatched.get())return false"),"Native arming must be disabled when stopped");
assert(emergencySrc.includes('ModbusOutput.writeOnce(id,m0,ch,false)'),"Emergency must issue real OFF command for ON outputs");
assert(emergencySrc.includes('if(!off)throw new java.io.IOException'),"Actual DO OFF readback mandatory");
assert(emergencySrc.includes("for(int id=1;id<=2;id++)"),"Emergency must attempt both PLCs");
assert(emergencySrc.includes("emergencyStopInProgress.get()"),"Reset prohibited during OFF send");
assert(emergencySrc.includes("emergencyLatched.set(false)"),"Explicit reset API missing");
assert(emergencySrc.includes('sendLink(id,false,"비상정지 확인 실패'),"Offline/unverified board cannot be reported stopped");
let emergencyCalled=0,resetCalled=0;
ctx.window.NativeDiagnostics.emergencyStop=()=>{emergencyCalled++;};
ctx.window.NativeDiagnostics.resetEmergencyStop=()=>{resetCalled++;return "비상정지 잠금 해제 · 현장 확인 필요";};
ctx.window.confirm=()=>true;
ctx.requestEmergencyStop();
assert(emergencyCalled===1&&ctx.emergencyActive,"Red stop button must immediately latch GUI");
assert(!ctx.plcArmed,"Emergency must immediately revoke manual enable");
assert(node("emergencyBanner").className.includes("active"),"Emergency status banner must appear");
ctx.window.nativeMessage(JSON.stringify({type:"emergency",message:"ID 1: 64DO OFF 확인. ID 2: 정지 미확인."}));
assert(node("emergencyBanner").textContent.includes("정지 미확인"),"Emergency must show unresolved PLC ID2 state");
const previousWrites=writes.length;
ctx.manualMappedOutput(1,0,true);
assert(writes.length===previousWrites,"Output must remain blocked during emergency latch");
ctx.requestEmergencyReset();
assert(resetCalled===1&&!ctx.emergencyActive,"Manual emergency reset should clear latch only on native approval");
assert(!ctx.plcArmed,"Reset must not automatically enable motors or DOs");
console.log("PASS: Android WebView syntax, Korean keyboard, OTA, 128DO mapping, vertical motor OPEN/STOP/CLOSE, no bench UI, real CRC/Modbus IO and motor interlocks.");

/* Offline Android-7-compatible automation UI. Received values are never synthesized. */
window.EBAutomation=(function(){
 'use strict';
 var ui={house:1,slot:0,period:'day',date:'',tab:'history',graph:'trend',id:0,pending:false,requestedAt:0,data:null,dirty:0,savedVersion:0,formKey:'',windows:[],editIndex:-1,days:127,numTarget:'',numText:'',ready:false};
 var days=['일','월','화','수','목','금','토'];
 var periodLabels={live:'실시간',day:'24시간',week:'1주일',month:'1개월'};
 var slots=[0,1,2,3,4,6,8,10,12,14,16,18,20,21,22,23];
 function e(id){return document.getElementById(id);}
 function esc(v){return String(v===null||v===undefined?'':v).replace(/[&<>"']/g,function(c){return {'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c];});}
 function houseName(h){return typeof names!=='undefined'?names[h-1]:h+'동';}
 function deviceName(h,s){var n=(h-1)*24+s;var value=typeof assignedDoName==='function'?assignedDoName(Math.floor(n/64)+1,n%64+1):'DO '+(s+1);return value.replace(/ 열림$/,'');}
 function motor(s){return s>=4&&s<=19;}
 function n(id){var value=e(id).value;return value===''?null:Number(value);}
 function timeText(minute){return ('0'+Math.floor(minute/60)).slice(-2)+':'+('0'+minute%60).slice(-2);}
 function kst(ts,dateOnly){var d=new Date(ts+9*3600000);return (dateOnly?(''+(d.getUTCMonth()+1)+'/'+d.getUTCDate()):('0'+d.getUTCHours()).slice(-2)+':'+('0'+d.getUTCMinutes()).slice(-2));}
 function fullTime(ts){return kst(ts,true)+' '+kst(ts,false);}
 function host(name,args){if(!window.NativeDiagnostics||typeof window.NativeDiagnostics[name]!=='function'){notice('Android HMI에서 설정 저장과 실제 이력 조회가 가능합니다.',true);return false;}window.NativeDiagnostics[name].apply(window.NativeDiagnostics,args||[]);return true;}
 function notice(text,bad){var node=e('auNotice');if(node){node.textContent=text;node.className='auto-notice'+(bad?' error':'');}}
 function changed(){ui.dirty++;e('auDirty').textContent='저장하지 않은 변경 있음';}
 function numberField(id,label,unit){return '<div><label for="'+id+'">'+label+' <span class="auto-muted">'+unit+'</span></label><div class="auto-number"><button type="button" onclick="EBAutomation.step(\''+id+'\',-1)">−</button><input id="'+id+'" readonly inputmode="decimal" placeholder="입력" onclick="EBAutomation.number(\''+id+'\')"><button type="button" onclick="EBAutomation.step(\''+id+'\',1)">+</button></div></div>';}
 function thresholdBlock(prefix,label,unit){return '<div class="auto-field"><label>'+label+' 조건</label><select id="'+prefix+'Direction" onchange="EBAutomation.dirty()"><option value="high">높을 때 작동</option><option value="low">낮을 때 작동</option></select><div class="auto-subfields" style="margin-top:12px">'+numberField(prefix+'On','켜짐 기준',unit)+numberField(prefix+'Off','꺼짐 기준',unit)+'</div><small>높을 때: 켜짐 기준을 더 높게 / 낮을 때: 켜짐 기준을 더 낮게 설정합니다.</small></div>';}
 function layout(){
  return '<div class="auto-shell"><div class="auto-title"><div><h2>자동제어 · 그래프 관제</h2><div class="auto-muted">동·장비별 독립 설정 / 실제 수신 이력 / 한국 표준시 기준</div></div><span id="auRunBadge" class="auto-pill">자동 정지</span></div>'+
   '<div class="auto-toolbar"><label>하우스 <select id="auHouse" onchange="EBAutomation.selectHouse(this.value)"></select></label><label>장비 <select id="auDevice" onchange="EBAutomation.selectDevice(this.value)"></select></label></div>'+
   '<div class="auto-actions"><button type="button" class="auto-start" onclick="EBAutomation.start()">▶ 자동운전 시작</button><button type="button" class="auto-stop" onclick="EBAutomation.stop()">■ 자동운전 정지</button><span id="auDeviceStatus" class="auto-statusline">실제 PLC 상태 확인 중</span></div>'+
   '<p class="auto-muted">자동운전은 HMI 앱 실행 중 동작합니다. 앱 종료·전원 상실 시 소프트웨어로 PLC 출력 OFF를 보장할 수 없습니다.</p><div id="auNotice" class="auto-notice">저장 설정과 수신 이력 조회 중 · 재실행 시 자동으로 출력을 켜지 않습니다.</div>'+
   '<div class="auto-tabs"><button id="auTabhistory" onclick="EBAutomation.tab(\'history\')">◉ 계기판·이력</button><button id="auTabsettings" onclick="EBAutomation.tab(\'settings\')">⚙ 장비 조건</button><button id="auTabschedule" onclick="EBAutomation.tab(\'schedule\')">▤ 시간 등록</button><button id="auTaboverview" onclick="EBAutomation.tab(\'overview\')">☷ 등록 현황</button></div>'+
   '<div id="auViewhistory" class="auto-shell"><div id="auGauges" class="auto-gauges"></div><div class="auto-chart-card"><div class="auto-chart-title"><h3>온습도·장비 수신 이력</h3><span id="auRange" class="auto-muted"></span></div><div class="auto-periods" id="auPeriods" style="margin-top:12px"></div><div class="auto-actions" style="margin:10px 0"><label class="auto-muted">날짜 선택 <input class="auto-date" id="auDate" type="date" onchange="EBAutomation.date(this.value)"></label><button onclick="EBAutomation.date(\'\')">현재 시점</button><button onclick="EBAutomation.graph(\'trend\')" id="auGraphtrend">추이</button><button onclick="EBAutomation.graph(\'bars\')" id="auGraphbars">막대</button><button onclick="EBAutomation.graph(\'output\')" id="auGraphoutput">PLC ON/OFF</button></div><div id="auMetrics" class="auto-metrics"></div><div id="auCharts"></div><p class="auto-bar-note">온습도는 실측 수신값만 표시합니다. 설정선은 점선입니다. 기록 없는 시간은 비워 두며, PLC DO 이력은 실제 모터 위치·부하 전류 측정값이 아닙니다.</p></div><div class="auto-chart-card"><h3>자동 운전·설정 이력</h3><div id="auEvents" class="auto-events">기록 조회 중</div></div></div>'+
   '<div id="auViewsettings" class="auto-shell" style="display:none"><div class="auto-form"><div class="auto-field"><label><input type="checkbox" id="auEnabled" onchange="EBAutomation.dirty()">이 장비 자동조건 사용</label><small id="auAddress"></small></div><div class="auto-field"><label>자동 판단 조건</label><select id="auMode" onchange="EBAutomation.dirty();EBAutomation.mode()"><option value="time">시간 예약</option><option value="temperature">온도</option><option value="humidity">습도</option><option value="combined">온도 + 습도</option></select></div>'+thresholdBlock('auT','온도','°C')+thresholdBlock('auR','습도','%')+'<div class="auto-field"><label>온도·습도 조합</label><select id="auJoin" onchange="EBAutomation.dirty()"><option value="all">두 조건 모두 만족할 때</option><option value="any">한 조건 이상 만족할 때</option></select><label style="margin-top:12px"><input type="checkbox" id="auTimeGate" onchange="EBAutomation.dirty()">예약 시간 안에서만 센서 조건 적용</label></div><div class="auto-field"><div class="auto-subfields">'+numberField('auMax','최대 연속 운전','초')+numberField('auMin','최소 정지시간','초')+'</div><small>최대시간 도달 시 해당 장비는 OFF 확인 후 자동 잠금됩니다. 수동 조작도 해당 장비의 자동운전을 일시정지합니다.</small></div><div class="auto-field wide"><p id="auCapability" class="warn"></p><div class="auto-actions"><button class="auto-save" onclick="EBAutomation.save()">장비 설정 저장</button><span id="auDirty" class="auto-dirty"></span><button onclick="EBAutomation.reload()">저장값 다시 읽기</button></div><small>저장하면 해당 장비의 자동운전이 일시정지합니다. 설정 확인 후 자동운전 시작으로 적용하세요.</small></div></div><div class="auto-chart-card"><h3>다른 동으로 복사</h3><div class="auto-copy"><span class="auto-muted">현재 동의 저장된 장비 설정 전체 →</span><select id="auCopyHouse"></select><button onclick="EBAutomation.copy()">선택한 동으로 복사</button></div><p class="auto-muted" style="margin-top:8px">대상 동의 설정을 덮어씁니다. 고정 DO 주소는 대상 동 주소를 유지하고, 복사된 설정의 사용 여부는 OFF로 저장합니다.</p></div></div>'+
   '<div id="auViewschedule" class="auto-shell" style="display:none"><div class="auto-chart-card"><h3>선택 장비의 시간 예약</h3><div class="auto-schedule-form"><div class="auto-actions"><button onclick="EBAutomation.setDays(127)">매일</button><button onclick="EBAutomation.setDays(62)">평일</button><button onclick="EBAutomation.setDays(65)">주말</button><label><input type="checkbox" id="auAllDay" onchange="EBAutomation.clockMode()">하루 종일</label></div><div id="auDays" class="auto-days"></div><div class="auto-clock"><label>시작 시<select id="auStartH"></select></label><label>시작 분<select id="auStartM"></select></label><label>종료 시<select id="auEndH"></select></label><label>종료 분<select id="auEndM"></select></label></div><div class="auto-actions"><button onclick="EBAutomation.quick(10)">시작부터 10분</button><button onclick="EBAutomation.quick(30)">30분</button><button onclick="EBAutomation.quick(60)">1시간</button></div><div class="auto-form"><div class="auto-field"><label><input type="checkbox" id="auRepeat" onchange="EBAutomation.repeatMode()">시간 안에서 반복 운전</label><small>반복을 끄면 등록한 시간 구간 동안 계속 운전합니다.</small></div><div class="auto-field"><div class="auto-subfields">'+numberField('auRepeatMin','반복 간격','분')+numberField('auRepeatRun','한 번 작동','초')+'</div></div></div><div class="auto-actions"><button class="auto-save" onclick="EBAutomation.addWindow()">시간표에 추가·반영</button><button onclick="EBAutomation.cancelWindow()">편집 취소</button><span class="auto-muted">종료가 시작보다 이르면 다음 날 종료 · 최대 16개</span></div></div></div><div class="auto-chart-card"><h3>주간 예약 시간표</h3><div id="auWeek" class="auto-scroll-chart"></div><div id="auWindowList" class="auto-schedule-list"></div><div class="auto-actions" style="margin-top:12px"><button class="auto-save" onclick="EBAutomation.save()">조건과 예약 함께 저장</button><span class="auto-muted">시간표에 추가한 뒤 저장해야 장비에 적용됩니다.</span></div></div></div>'+
   '<div id="auViewoverview" class="auto-shell" style="display:none"><div class="auto-chart-card"><h3>선택 동 · 오늘의 예약</h3><div id="auOverviewTime" class="auto-scroll-chart"></div><div id="auOverview"></div></div></div></div>';
 }
 function init(){
  var root=e('automationRoot');if(!root)return;root.innerHTML=layout();
  try{var pref=JSON.parse(localStorage.getItem('eb-auto-view-v1')||'{}');if(pref.house>=1&&pref.house<=5)ui.house=+pref.house;if(slots.indexOf(+pref.slot)>=0)ui.slot=+pref.slot;if(periodLabels[pref.period])ui.period=pref.period;if(pref.date)ui.date=pref.date;}catch(ignore){}
  namesChanged();clockOptions();setDays(127);modal();ui.ready=true;tab('history');refresh(true);
  setInterval(function(){if(!ui.ready)return;var auto=e('auto'),schedule=e('schedule'),env=e('env'),dashboard=e('dashboard');if((dashboard&&dashboard.className.indexOf('active')>=0)||(auto&&auto.className.indexOf('active')>=0)||(schedule&&schedule.className.indexOf('active')>=0)||(env&&env.className.indexOf('active')>=0))refresh(false);},5000);
 }
 function remember(){try{localStorage.setItem('eb-auto-view-v1',JSON.stringify({house:ui.house,slot:ui.slot,period:ui.period,date:ui.date}));}catch(ignore){}}
 function namesChanged(){
  if(!e('auHouse'))return;var options='',devices='';
  for(var h=1;h<=5;h++)options+='<option value="'+h+'">'+esc(houseName(h))+'</option>';
  e('auHouse').innerHTML=options;e('auCopyHouse').innerHTML=options;e('auHouse').value=String(ui.house);e('auCopyHouse').value=String(ui.house===5?1:ui.house+1);
  for(var i=0;i<slots.length;i++)devices+='<option value="'+slots[i]+'">'+esc(deviceName(ui.house,slots[i]))+'</option>';
  e('auDevice').innerHTML=devices;e('auDevice').value=String(ui.slot);dashboardStatus();
 }
 function dashboardStatus(){
  if(!ui.data)return;
  var list=ui.data.rules||[];
  for(var h=1;h<=5;h++){
   var node=e('houseAuto'+h);if(!node)continue;var configured=0,paused=0;
   for(var i=0;i<list.length;i++)if(list[i].house===h&&list[i].enabled){configured++;if(list[i].paused)paused++;}
   node.textContent=!ui.data.loaded?'자동 설정 읽는 중':ui.data.active?(configured?'자동 조건 감시 '+configured+'대 · 일시정지 '+paused+'대':'자동 대상 없음'):'자동 정지 · 사용 설정 '+configured+'대';
  }
 }
 function selectHouse(h){ui.house=+h;ui.dirty=0;ui.formKey='';namesChanged();remember();refresh(true);}
 function selectDevice(s){ui.slot=+s;ui.dirty=0;ui.formKey='';remember();refresh(true);}
 function tab(name){ui.tab=name;var list=['history','settings','schedule','overview'];for(var i=0;i<list.length;i++){e('auView'+list[i]).style.display=list[i]===name?'grid':'none';e('auTab'+list[i]).className=list[i]===name?'selected':'';}if(name==='overview')overview();}
 function refresh(force){
  if(!ui.ready)return;if(ui.pending&&!force&&new Date().getTime()-ui.requestedAt<15000)return;
  ui.pending=true;ui.requestedAt=new Date().getTime();ui.id++;
  if(!host('automationSnapshot',[ui.house,ui.slot,ui.period,ui.date,ui.id]))ui.pending=false;
 }
 function receive(raw){
  try{var data=typeof raw==='string'?JSON.parse(raw):raw;if(data.requestId!==ui.id)return;ui.pending=false;if(!ui.data)notice(data.message||'저장 설정·수신 이력 조회 완료');ui.data=data;
   e('auRunBadge').textContent=data.active?'자동운전 활성':'자동 정지';e('auRunBadge').className='auto-pill'+(data.active?' running':'');
   e('auDeviceStatus').textContent=data.status+' · 실제 출력: '+(data.output===true?'ON':data.output===false?'OFF':'미수신')+' · 최근 PLC 수신: '+(data.lastPlcAt?fullTime(data.lastPlcAt):'없음');
   if(!ui.dirty&&ui.formKey!==ui.house+':'+ui.slot){loadForm(data.rule);ui.formKey=ui.house+':'+ui.slot;}
   renderGraphs();overview();dashboardStatus();
   if(e('environmentGraph'))e('environmentGraph').innerHTML='<div class="auto-gauges">'+gauge('현재 온도',data.temperature,'°C','#ffb45e',null,null)+gauge('현재 습도',data.humidity,'%','#64d7ff',0,100)+gauge('PLC DO ON',data.houseOnCount<0?null:data.houseOnCount,'/24','#73e2bc',0,24)+'</div><button onclick="EBAutomation.openHistory()" style="margin-top:12px">24시간·1주일·1개월 이력 보기</button><p class="auto-muted">'+esc(houseName(ui.house))+' · 온습도 통신맵 미등록으로 센서값은 미수신입니다.</p>';
  }catch(error){notice('자동 화면 응답 오류: '+error.message,true);}
 }
 function onNotice(text){notice(text,/(실패|오류|차단)/.test(text));if(text.indexOf('저장 완료')>=0){if(ui.dirty===ui.savedVersion){ui.dirty=0;ui.formKey='';e('auDirty').textContent='저장 완료';}refresh(true);}else if(text.indexOf('복사 완료')>=0)refresh(true);}
 function loadForm(r){
  e('auEnabled').checked=!!r.enabled;e('auMode').value=r.mode||'time';e('auJoin').value=r.join||'all';e('auTimeGate').checked=!!r.timeGate;
  var t=r.temperature||{},rh=r.humidity||{};
  e('auTDirection').value=t.direction||'high';e('auRDirection').value=rh.direction||'high';
  var values={auTOn:t.on,auTOff:t.off,auROn:rh.on,auROff:rh.off,auMax:r.maxOnSec,auMin:r.minOffSec};
  for(var id in values)if(values.hasOwnProperty(id))e(id).value=values[id]===null||values[id]===undefined?'':values[id];
  ui.windows=JSON.parse(JSON.stringify(r.schedules||[]));cancelWindow();renderWindows();mode();
  var absolute=(ui.house-1)*24+ui.slot;e('auAddress').textContent='고정 주소: PLC ID '+(Math.floor(absolute/64)+1)+' · DO '+(absolute%64+1)+(motor(ui.slot)?' / '+(absolute%64+2):'');
 }
 function mode(){
  var value=e('auMode').value;var motorMessage=motor(ui.slot)?'창문·그늘막 자동 구동은 실제 리미트 DI와 구동시간 확인 전까지 차단됩니다. 기존 수동 정역 제어는 유지합니다.':'시간 예약은 실제 PLC 명령에 연결됩니다. 새 자동운전은 현장 시작 버튼과 PLC 실수신 확인 후 가능합니다.';
  e('auCapability').textContent=motorMessage+(value==='time'?'':' 온습도 조건은 센서 미수신 상태에서는 실행하지 않습니다.');
  e('auTimeGate').disabled=value==='time';e('auJoin').disabled=value!=='combined';
 }
 function form(){return {house:ui.house,slot:ui.slot,enabled:e('auEnabled').checked,mode:e('auMode').value,join:e('auJoin').value,timeGate:e('auTimeGate').checked,temperature:{direction:e('auTDirection').value,on:n('auTOn'),off:n('auTOff')},humidity:{direction:e('auRDirection').value,on:n('auROn'),off:n('auROff')},maxOnSec:n('auMax'),minOffSec:n('auMin'),schedules:ui.windows};}
 function save(){ui.savedVersion=ui.dirty;notice('설정 저장 요청 중');host('saveAutomationRule',[ui.house,ui.slot,JSON.stringify(form())]);}
 function reload(){ui.dirty=0;ui.formKey='';e('auDirty').textContent='저장값 조회 중';refresh(true);}
 function start(){notice('PLC 실제 상태·운전 승인 확인 중 · 결과 수신 전까지 시작되지 않습니다.');host('startAutomation');}
 function stop(){notice('자동운전 정지 요청 · 실제 PLC OFF 수신 결과를 확인하세요.');host('stopAutomation');}
 function copy(){host('copyAutomationHouse',[ui.house,+e('auCopyHouse').value]);}
 function clockOptions(){var hours='',minutes='';for(var h=0;h<24;h++)hours+='<option value="'+h+'">'+('0'+h).slice(-2)+'시</option>';for(var m=0;m<60;m++)minutes+='<option value="'+m+'">'+('0'+m).slice(-2)+'분</option>';e('auStartH').innerHTML=hours;e('auEndH').innerHTML=hours;e('auStartM').innerHTML=minutes;e('auEndM').innerHTML=minutes;}
 function setDays(mask){ui.days=mask;var html='';for(var i=0;i<7;i++)html+='<button class="'+((mask&(1<<i))?'selected':'')+'" onclick="EBAutomation.toggleDay('+i+')">'+days[i]+'</button>';e('auDays').innerHTML=html;}
 function toggleDay(i){setDays(ui.days^(1<<i));}
 function clockMode(){var disabled=e('auAllDay').checked;var ids=['auStartH','auStartM','auEndH','auEndM'];for(var i=0;i<ids.length;i++)e(ids[i]).disabled=disabled;}
 function repeatMode(){e('auRepeatMin').parentNode.parentNode.style.opacity=e('auRepeat').checked?'1':'.55';}
 function quick(minutes){var start=(+e('auStartH').value)*60+(+e('auStartM').value),end=(start+minutes)%1440;e('auEndH').value=String(Math.floor(end/60));e('auEndM').value=String(end%60);e('auAllDay').checked=false;clockMode();}
 function addWindow(){
  var start=(+e('auStartH').value)*60+(+e('auStartM').value),end=(+e('auEndH').value)*60+(+e('auEndM').value),all=e('auAllDay').checked,repeat=e('auRepeat').checked;
  if(!ui.days||(!all&&start===end)){notice('요일과 시작·종료 시간을 선택하세요. 하루 종일은 체크로 선택합니다.',true);return;}
  var repeatMin=repeat?n('auRepeatMin'):0,runSec=repeat?n('auRepeatRun'):0;
  if(repeat&&(!repeatMin||!runSec||repeatMin<1||repeatMin>1440||runSec<1||runSec>repeatMin*60)){notice('반복 간격과 작동시간을 확인하세요. 작동시간은 반복 간격 이하여야 합니다.',true);return;}
  if(ui.editIndex<0&&ui.windows.length>=16){notice('장비당 최대 16개 예약을 등록할 수 있습니다.',true);return;}
  var w={days:ui.days,start:all?0:start,end:all?0:end,allDay:all,repeatMin:repeatMin,runSec:runSec};
  var candidate=ui.windows.slice();if(ui.editIndex>=0)candidate[ui.editIndex]=w;else candidate.push(w);
  if(overlap(candidate)){notice('예약 시간이 겹칩니다. 자정 이후 다음 날 구간도 확인하세요.',true);return;}
  ui.windows=candidate;changed();cancelWindow();renderWindows();notice('시간표에 반영했습니다. 조건과 예약 함께 저장을 눌러 주세요.');
 }
 function pieces(w){var out=[];for(var d=0;d<7;d++)if(w.days&(1<<d)){if(w.allDay)out.push({day:d,start:0,end:1440});else if(w.end>w.start)out.push({day:d,start:w.start,end:w.end});else{out.push({day:d,start:w.start,end:1440});out.push({day:(d+1)%7,start:0,end:w.end});}}return out;}
 function overlap(windows){var occupied={};for(var i=0;i<windows.length;i++){var parts=pieces(windows[i]);for(var j=0;j<parts.length;j++){var p=parts[j];for(var m=p.start;m<p.end;m++){var key=p.day*1440+m;if(occupied[key])return true;occupied[key]=1;}}}return false;}
 function windowLabel(w){var names=[];for(var i=0;i<7;i++)if(w.days&(1<<i))names.push(days[i]);return names.join('·')+' / '+(w.allDay?'하루 종일':timeText(w.start)+' → '+timeText(w.end)+(w.end<w.start?' (다음 날)':''))+(w.repeatMin?' / 매 '+w.repeatMin+'분, '+w.runSec+'초 작동':'');}
 function renderWindows(){var html='';for(var i=0;i<ui.windows.length;i++)html+='<div class="auto-schedule-row"><p>'+esc(windowLabel(ui.windows[i]))+'</p><button onclick="EBAutomation.editWindow('+i+')">수정</button><button onclick="EBAutomation.deleteWindow('+i+')">삭제</button></div>';e('auWindowList').innerHTML=html||'<p class="auto-muted">등록된 예약 없음</p>';e('auWeek').innerHTML=weekChart(ui.windows);}
 function editWindow(i){var w=ui.windows[i];ui.editIndex=i;setDays(w.days);e('auAllDay').checked=!!w.allDay;e('auStartH').value=String(Math.floor(w.start/60));e('auStartM').value=String(w.start%60);e('auEndH').value=String(Math.floor(w.end/60));e('auEndM').value=String(w.end%60);e('auRepeat').checked=!!w.repeatMin;e('auRepeatMin').value=w.repeatMin||'';e('auRepeatRun').value=w.runSec||'';clockMode();repeatMode();notice((i+1)+'번 예약 수정 중 · 시간표에 추가·반영을 눌러 완료하세요.');}
 function deleteWindow(i){ui.windows.splice(i,1);changed();cancelWindow();renderWindows();}
 function cancelWindow(){ui.editIndex=-1;setDays(127);e('auAllDay').checked=false;e('auRepeat').checked=false;clockMode();repeatMode();}
 function weekChart(windows){var s='<svg viewBox="0 0 740 246" role="img" aria-label="주간 예약 시간표"><rect x="0" y="0" width="740" height="246" fill="#102433"/>';for(var h=0;h<=24;h+=4){var x=45+h/24*675;s+='<path d="M'+x+' 25V230" stroke="#304d61"/><text x="'+x+'" y="17" text-anchor="middle" fill="#9ab8cb" font-size="12">'+h+'시</text>';}for(var d=0;d<7;d++)s+='<text x="17" y="'+(46+d*28)+'" fill="#c1d7e5" font-size="14">'+days[d]+'</text>';for(var i=0;i<windows.length;i++){var parts=pieces(windows[i]);for(var j=0;j<parts.length;j++){var p=parts[j];s+='<rect x="'+(45+p.start/1440*675)+'" y="'+(30+p.day*28)+'" width="'+Math.max(2,(p.end-p.start)/1440*675)+'" height="21" rx="4" fill="'+(windows[i].repeatMin?'#9970d4':'#2bafbd')+'"/>';}}return s+'</svg>';}
 function period(value){ui.period=value;remember();renderPeriod();refresh(true);}
 function date(value){ui.date=value||'';e('auDate').value=ui.date;remember();refresh(true);}
 function graph(value){ui.graph=value;renderGraphs();}
 function renderPeriod(){var html='';for(var p in periodLabels)if(periodLabels.hasOwnProperty(p))html+='<button class="'+(ui.period===p?'selected':'')+'" onclick="EBAutomation.period(\''+p+'\')">'+periodLabels[p]+'</button>';e('auPeriods').innerHTML=html;e('auDate').value=ui.date;}
 function gauge(title,value,unit,color,min,max){
  var valid=typeof value==='number'&&isFinite(value);if(min===null||max===null){min=valid?Math.floor((value-5)/10)*10:0;max=valid?Math.ceil((value+5)/10)*10:1;if(max<=min)max=min+10;}
  var fraction=valid?Math.max(0,Math.min(1,(value-min)/(max-min))):0,angle=(-225+fraction*270)*Math.PI/180,x=110+70*Math.cos(angle),y=105+70*Math.sin(angle);
  var path='M 53.43 161.57 A 80 80 0 1 1 166.57 161.57',s='<div class="auto-gauge-card"><h3>'+esc(title)+'</h3><svg viewBox="0 0 220 190" role="img" aria-label="'+esc(title)+(valid?value+' '+unit:' 미수신')+'"><path d="'+path+'" fill="none" stroke="#29485e" stroke-width="15" stroke-linecap="round"/>';
  if(valid)s+='<path d="'+path+'" fill="none" stroke="'+color+'" stroke-width="15" stroke-linecap="round" stroke-dasharray="'+(fraction*377)+' 377"/><line x1="110" y1="105" x2="'+x+'" y2="'+y+'" stroke="#edfbff" stroke-width="3"/><circle cx="110" cy="105" r="6" fill="'+color+'"/>';
  s+='<text x="110" y="143" text-anchor="middle" fill="'+(valid?color:'#9bb4c4')+'" font-size="'+(valid?'29':'21')+'">'+(valid?(Math.round(value*10)/10):'미수신')+'</text><text x="110" y="166" text-anchor="middle" fill="#b6c9d5" font-size="13">'+esc(unit)+'</text>';
  if(valid)s+='<text x="35" y="183" fill="#94b0c3" font-size="11">'+min+'</text><text x="185" y="183" fill="#94b0c3" font-size="11" text-anchor="end">'+max+'</text>';
  return s+'</svg><p>'+(valid?'실제 수신값':'센서 / PLC 실수신 대기')+'</p></div>';
 }
 function axes(hist,label,min,max,empty,binary){var s='<svg viewBox="0 0 740 215" role="img" aria-label="'+esc(label)+'"><rect width="740" height="215" fill="#102433"/>';for(var i=0;i<5;i++){var y=28+i*34;if(!binary||i===0||i===4)s+='<path d="M48 '+y+'H724" stroke="#2a455a"/><text x="42" y="'+(y+4)+'" text-anchor="end" fill="#9bb5c9" font-size="11">'+(binary?(i===0?'ON':'OFF'):empty?'—':Math.round((max-(max-min)*i/4)*10)/10)+'</text>';var x=48+i*169,ts=hist.from+(hist.to-hist.from)*i/4;s+='<text x="'+x+'" y="199" text-anchor="'+(i===0?'start':i===4?'end':'middle')+'" fill="#9bb5c9" font-size="11">'+kst(ts,hist.to-hist.from>2*86400000)+'</text>';}return s;}
 function sx(hist,ts){return 48+(ts-hist.from)/(hist.to-hist.from)*676;}
 function trend(hist,kind,settings){
  var temp=kind==='temperature',color=temp?'#ffb45e':'#64d7ff',unit=temp?'°C':'%',list=hist.environment||[],valid=[],low=temp?'tMin':'rMin',high=temp?'tMax':'rMax';
  for(var i=0;i<list.length;i++)if(typeof list[i][kind]==='number')valid.push(list[i]);
  var min=temp?Infinity:0,max=temp?-Infinity:100;
  for(i=0;i<valid.length;i++){min=Math.min(min,valid[i][low]);max=Math.max(max,valid[i][high]);}
  var on=settings?settings.on:null,off=settings?settings.off:null;
  if(typeof on==='number'){min=Math.min(min,on);max=Math.max(max,on);}if(typeof off==='number'){min=Math.min(min,off);max=Math.max(max,off);}
  var hasScale=isFinite(min)&&isFinite(max);if(!hasScale){min=0;max=1;}else if(temp){var pad=Math.max(2,(max-min)*.1);min-=pad;max+=pad;}
  function sy(v){return 164-(v-min)/(max-min)*136;}
  var s=axes(hist,kind,min,max,!hasScale);
  if(hasScale){var limits=[on,off];for(i=0;i<limits.length;i++)if(typeof limits[i]==='number')s+='<path d="M48 '+sy(limits[i])+'H724" stroke="'+color+'" stroke-opacity=".65" stroke-dasharray="5 5"/><text x="722" y="'+(sy(limits[i])-4)+'" fill="'+color+'" font-size="11" text-anchor="end">'+(i?'꺼짐 ':'켜짐 ')+limits[i]+unit+'</text>';}
  var path='',last=0;
  for(i=0;i<valid.length;i++){var point=valid[i],x=sx(hist,point.ts+hist.bucketMs/2);if(x<48||x>724)continue;s+='<line x1="'+x+'" y1="'+sy(point[low])+'" x2="'+x+'" y2="'+sy(point[high])+'" stroke="'+color+'" stroke-opacity=".3" stroke-width="5"/>';path+=(last&&point.ts-last<=hist.bucketMs*1.5?'L':'M')+x.toFixed(1)+' '+sy(point[kind]).toFixed(1)+' ';last=point.ts;}
  if(path)s+='<path d="'+path+'" fill="none" stroke="'+color+'" stroke-width="2.5"/>';
  else s+='<text x="386" y="105" text-anchor="middle" fill="#b5cad7" font-size="15">실제 '+(temp?'온도':'습도')+' 수신 기록 없음</text>';
  return '<h3 style="margin:12px 0 4px">'+(temp?'온도':'습도')+' 추이 <span class="auto-muted">'+unit+' · 실선 평균 / 옅은 막대 최저~최고 / 점선 설정</span></h3>'+s+'</svg>';
 }
 function bars(hist){var bars=hist.bars||[],max=hist.barBucketMs/3600000,s=axes(hist,'PLC DO ON 누적 시간',0,max,false);for(var i=0;i<bars.length;i++){var p=bars[i],a=Math.max(hist.from,p.ts),b=Math.min(hist.to,p.ts+hist.barBucketMs),x=sx(hist,a),w=Math.max(2,sx(hist,b)-x-3),height=p.onMs/3600000/max*136;s+='<rect x="'+x+'" y="'+(164-height)+'" width="'+w+'" height="'+Math.max(1,height)+'" fill="#69d8af" rx="2"/>';}
  if(!bars.length)s+='<text x="386" y="105" text-anchor="middle" fill="#b5cad7" font-size="15">PLC DO 실수신 이력 없음</text>';
  return '<h3 style="margin:12px 0 4px">PLC DO ON 누적 <span class="auto-muted">단위: 시간 · 미수신 구간 제외</span></h3>'+s+'</svg>'+sensorBars(hist,'temperature')+sensorBars(hist,'humidity');
 }
 function sensorBars(hist,kind){var rows=hist.environment||[],temp=kind==='temperature',lo=temp?'tMin':'rMin',hi=temp?'tMax':'rMax',min=temp?Infinity:0,max=temp?-Infinity:100,found=false;for(var i=0;i<rows.length;i++)if(typeof rows[i][kind]==='number'){found=true;min=Math.min(min,rows[i][lo]);max=Math.max(max,rows[i][hi]);}if(!found){min=0;max=1;}else if(temp){min-=2;max+=2;}var color=temp?'#ffb45e':'#64d7ff',s=axes(hist,kind,min,max,!found);for(i=0;i<rows.length;i++){var p=rows[i];if(typeof p[kind]!=='number')continue;var x=sx(hist,p.ts+hist.bucketMs/2),y=164-(p[kind]-min)/(max-min)*136;s+='<rect x="'+(x-4)+'" y="'+y+'" width="8" height="'+(164-y)+'" fill="'+color+'"/><line x1="'+x+'" y1="'+(164-(p[hi]-min)/(max-min)*136)+'" x2="'+x+'" y2="'+(164-(p[lo]-min)/(max-min)*136)+'" stroke="'+color+'" stroke-width="2"/>';}if(!found)s+='<text x="386" y="105" text-anchor="middle" fill="#b5cad7" font-size="15">실제 '+(temp?'온도':'습도')+' 수신 기록 없음</text>';return '<h3 style="margin:12px 0 4px">'+(temp?'온도':'습도')+' 최저·평균·최고 <span class="auto-muted">'+(temp?'°C':'%')+'</span></h3>'+s+'</svg>';}
 function outputGraph(hist){var s=axes(hist,'PLC DO 실제 회신 ON/OFF',0,1,false,true),segments=hist.segments||[];for(var i=0;i<segments.length;i++){var p=segments[i],a=sx(hist,p.start),b=sx(hist,p.end);s+='<rect x="'+a+'" y="'+(p.on?28:145)+'" width="'+Math.max(1,b-a)+'" height="19" rx="2" fill="'+(p.on?'#6de1af':'#668ca7')+'"/>';}if(!segments.length)s+='<text x="386" y="105" text-anchor="middle" fill="#b5cad7" font-size="15">PLC 실수신 이력 없음 · 기록 없는 시간은 빈 구간</text>';return '<h3 style="margin:12px 0 4px">선택 장비 PLC DO 실제 회신 <span class="auto-muted">1=ON / 0=OFF</span></h3>'+s+'</svg>';}
 function renderGraphs(){
  renderPeriod();var d=ui.data;if(!d)return;var h=d.history;if(!h)return;
  e('auGauges').innerHTML=gauge('현재 온도',d.temperature,'°C','#ffb45e',null,null)+gauge('현재 습도',d.humidity,'%','#64d7ff',0,100)+gauge('PLC DO ON',d.houseOnCount<0?null:d.houseOnCount,'/24','#73e2bc',0,24);
  e('auRange').textContent=fullTime(h.from)+' ~ '+fullTime(h.to);
  e('auMetrics').innerHTML='<span>PLC ON 누적 <strong>'+(h.observedMs>0?Math.round(h.onMs/36000)/100:'—')+'</strong> 시간</span><span>기록 확인 구간 <strong>'+(h.observedMs>0?Math.round(h.observedMs/36000)/100:'—')+'</strong> 시간</span><span>보관 '+h.retentionDays+'일 · 최초 설치 이전 기록 없음</span>';
  var types=['trend','bars','output'];for(var i=0;i<types.length;i++)e('auGraph'+types[i]).className=ui.graph===types[i]?'selected':'';
  var rule=d.rule||{};e('auCharts').innerHTML=ui.graph==='bars'?bars(h):ui.graph==='output'?outputGraph(h):trend(h,'temperature',rule.temperature)+trend(h,'humidity',rule.humidity)+outputGraph(h);
  if(h.truncated)e('auCharts').innerHTML+='<p class="warn">표시 건수 제한으로 일부 상세 이력이 생략되었습니다. 짧은 기간으로 조회하세요.</p>';
  var events=h.events||[],html='';for(i=0;i<events.length;i++)html+='<div>'+esc(fullTime(events[i].ts))+' · '+esc(events[i].message)+'</div>';e('auEvents').innerHTML=html||'선택 기간의 자동 운전·설정 기록 없음';
 }
 function overview(){
  if(!ui.data)return;var list=ui.data.rules||[],html='<table class="auto-table"><thead><tr><th>장비</th><th>조건 / 예약</th><th>상태</th></tr></thead><tbody>',count=0,chart='<svg viewBox="0 0 740 '+(40+slots.length*32)+'" role="img" aria-label="동별 오늘의 예약"><rect width="740" height="'+(40+slots.length*32)+'" fill="#102433"/>';
  var now=new Date(new Date().getTime()+9*3600000),day=now.getUTCDay();for(var hour=0;hour<=24;hour+=4){var x=190+hour/24*530;chart+='<path d="M'+x+' 25V'+(40+slots.length*32)+'" stroke="#304d61"/><text x="'+x+'" y="16" text-anchor="middle" fill="#a8c4d5" font-size="12">'+hour+'시</text>';}
  for(var j=0;j<slots.length;j++){var slot=slots[j],found=null;for(var k=0;k<list.length;k++)if(list[k].house===ui.house&&list[k].slot===slot)found=list[k];var name=deviceName(ui.house,slot);chart+='<text x="8" y="'+(46+j*32)+'" fill="#bdd3e1" font-size="12">'+esc(name.length>17?name.slice(0,16)+'…':name)+'</text>';
   if(!found)continue;count++;html+='<tr><td><button onclick="EBAutomation.editDevice('+slot+')">'+esc(name)+'</button></td><td>'+esc({time:'시간',temperature:'온도',humidity:'습도',combined:'온도+습도'}[found.mode])+' / '+found.schedules.length+'개</td><td>'+esc(!found.enabled?'사용 OFF':found.paused?'일시정지':found.status)+'</td></tr>';
   if(found.enabled)for(var w=0;w<found.schedules.length;w++){var p=pieces(found.schedules[w]);for(var z=0;z<p.length;z++)if(p[z].day===day)chart+='<rect x="'+(190+p[z].start/1440*530)+'" y="'+(30+j*32)+'" width="'+Math.max(2,(p[z].end-p[z].start)/1440*530)+'" height="22" rx="3" fill="#28a9bd"/>';}
  }
  html+='</tbody></table>';e('auOverview').innerHTML=count?html:'<p class="auto-muted">선택 동에 저장된 자동 설정이 없습니다.</p>';e('auOverviewTime').innerHTML=chart+'</svg>';
  if(e('scheduleOverview'))e('scheduleOverview').innerHTML='<p class="auto-muted">'+esc(houseName(ui.house))+' · 동/장비 선택과 시간 등록은 자동제어 메뉴에서 진행합니다.</p><button onclick="EBAutomation.openSchedule()">선택 장비 시간 등록 열기</button>'+chart+'</svg>'+html;
 }
 function editDevice(slot){selectDevice(slot);tab('settings');}
 function modal(){var root=document.createElement('div');root.id='auNumberModal';root.className='auto-modal';root.setAttribute('role','dialog');root.setAttribute('aria-label','숫자 입력');var keys=['7','8','9','4','5','6','1','2','3','−','0','.'],html='<div class="auto-modal-panel"><h3>설정값 입력</h3><div id="auNumPreview" class="auto-num-preview"></div><div class="auto-num-keys">';for(var i=0;i<keys.length;i++)html+='<button onclick="EBAutomation.numKey(\''+keys[i]+'\')">'+keys[i]+'</button>';root.innerHTML=html+'</div><div class="auto-actions" style="margin-top:10px"><button onclick="EBAutomation.numKey(\'delete\')">⌫ 삭제</button><button onclick="EBAutomation.numClose(false)">취소</button><button class="auto-save" onclick="EBAutomation.numClose(true)">입력 완료</button></div></div>';document.body.appendChild(root);}
 function number(id){ui.numTarget=id;ui.numText=e(id).value;e('auNumPreview').textContent=ui.numText||'0';e('auNumberModal').className='auto-modal open';}
 function numKey(value){if(value==='delete')ui.numText=ui.numText.slice(0,-1);else if(value==='−')ui.numText=ui.numText.charAt(0)==='-'?ui.numText.slice(1):'-'+ui.numText;else if(value==='.'&&ui.numText.indexOf('.')>=0)return;else if(ui.numText.length<10)ui.numText+=value;e('auNumPreview').textContent=ui.numText||'0';}
 function numClose(save){if(save){if(ui.numText!==''&&!isFinite(Number(ui.numText))){e('auNumPreview').textContent='숫자를 확인하세요';return;}e(ui.numTarget).value=ui.numText;changed();}e('auNumberModal').className='auto-modal';}
 function step(id,amount){var value=e(id).value;e(id).value=Math.round(((value===''?0:Number(value))+amount)*10)/10;changed();}
 function openHistory(){if(typeof show==='function')show('auto');tab('history');refresh(true);}
 function openSchedule(){if(typeof show==='function')show('auto');tab('schedule');refresh(true);}
 return {init:init,namesChanged:namesChanged,receive:receive,notice:onNotice,selectHouse:selectHouse,selectDevice:selectDevice,tab:tab,refresh:refresh,dirty:changed,mode:mode,save:save,reload:reload,start:start,stop:stop,copy:copy,setDays:setDays,toggleDay:toggleDay,clockMode:clockMode,repeatMode:repeatMode,quick:quick,addWindow:addWindow,editWindow:editWindow,deleteWindow:deleteWindow,cancelWindow:cancelWindow,period:period,date:date,graph:graph,number:number,numKey:numKey,numClose:numClose,step:step,editDevice:editDevice,openHistory:openHistory,openSchedule:openSchedule,_pieces:pieces,_overlap:overlap,_gauge:gauge,_trend:trend,_state:ui};
})();

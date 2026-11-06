/* Native-only COM6 input; values are never set by the web page. Android 7 syntax. */
window.EBSensor=(function(){
 var latest=null,dirty=false,loaded=false;
 function e(id){return document.getElementById(id);}
 function host(name,args){var h=window.NativeDiagnostics;if(!h||typeof h[name]!=='function'){e('sensorMessage').textContent='Android HMI 앱에서 실행하세요.';return false;}h[name].apply(h,args||[]);return true;}
 function esc(s){return String(s===null||s===undefined?'':s).replace(/[&<>"']/g,function(c){return {'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c];});}
 function init(){
  if(!e('sensorRoot'))return;
  e('sensorRoot').innerHTML='<div class="panel"><h3>1번동 내부 온습도 · COM6 RS485</h3><p class="muted">기본 /dev/ttyS6는 연결 시도용 경로입니다. 외부 COM6와 다르면 아래 실제 장치를 선택하세요. COM5 PLC 경로는 차단됩니다.</p><div class="auto-form" style="margin-top:12px"><div class="auto-field"><label>센서 실제 장치<select id="sensorPath" onchange="EBSensor.dirty()"></select></label><button onclick="EBSensor.reload()">장치 목록·저장 설정 다시 읽기</button></div><div class="auto-field"><label>속도<select id="sensorBaud" onchange="EBSensor.dirty()"><option>9600</option><option>4800</option></select></label><label>현재 센서 ID<select id="sensorId" onchange="EBSensor.dirty()"></select></label></div><div class="auto-field"><label>읽기 기능<select id="sensorFc" onchange="EBSensor.dirty()"><option value="3">FC03 · Holding</option><option value="4">FC04 · Input</option></select></label><label>시험 통신맵<select id="sensorMap" onchange="EBSensor.dirty()"><option value="h0t1">습도 0000 / 온도 0001 · ÷10</option><option value="t0h1">온도 0000 / 습도 0001 · ÷10</option></select></label></div><div class="auto-field"><p id="sensorMessage" style="overflow-wrap:anywhere">센서 상태 확인 중</p><p id="sensorConfigStatus" class="auto-muted"></p><p id="sensorEdit" class="auto-dirty"></p></div></div><div class="auto-actions" style="margin-top:12px"><button onclick="EBSensor.connect()">연결·설정 적용</button><button id="sensorSearch" onclick="EBSensor.search()">ID·속도 자동 검색</button><button onclick="EBSensor.cancel()">검색 취소</button><button onclick="EBSensor.stop()">감시 중지</button></div><div class="auto-actions" style="margin-top:12px"><label><input type="checkbox" id="sensorAck">센서 OLED와 아래 온도·습도 순서와 값이 일치합니다</label><button id="sensorConfirm" onclick="EBSensor.confirm()">OLED 일치 확인·이력 연결</button></div><p class="muted">ID 검색은 현재 ID와 1~247을 선택 기능으로 조회하며 9600·4800bps를 확인합니다. ID 쓰기는 제조사·모델 확정 전까지 잠금입니다. 온습도 조건의 자동 출력은 이번 통신 확인 버전에서 실행하지 않습니다.</p><div class="auto-metrics" style="margin-top:12px"><div>온도<strong id="sensorTemp">미수신</strong></div><div>습도<strong id="sensorHum">미수신</strong></div><div>최근 수신<strong id="sensorAt">없음</strong></div></div><details style="margin-top:12px"><summary>송수신 원문·오류 진단</summary><pre id="sensorRaw" style="white-space:pre-wrap;overflow-wrap:anywhere;font-size:16px">수신 없음</pre></details></div>';
  var options='';for(var id=1;id<=247;id++)options+='<option>'+id+'</option>';e('sensorId').innerHTML=options;
  reload();setInterval(refresh,2000);
 }
 function refresh(){try{var h=window.NativeDiagnostics;if(h&&typeof h.sensorInfo==='function')receive(h.sensorInfo());}catch(ex){e('sensorMessage').textContent='센서 상태 조회 오류: '+ex.message;}}
 function reload(){dirty=false;loaded=false;refresh();}
 function mark(){dirty=true;e('sensorAck').checked=false;e('sensorEdit').textContent='변경값 미적용 · 연결·설정 적용을 누르세요.';}
 function connect(){
  e('sensorAck').checked=false;dirty=false;loaded=false;
  host('configureSensor',[JSON.stringify({path:e('sensorPath').value,baud:+e('sensorBaud').value,id:+e('sensorId').value,fc:+e('sensorFc').value,map:e('sensorMap').value,enabled:true})]);
 }
 function search(){if(dirty){e('sensorMessage').textContent='변경한 설정을 먼저 적용하세요.';return;}host('searchSensor');}
 function confirm(){if(dirty||!e('sensorAck').checked){e('sensorMessage').textContent='현재 설정의 수신값과 OLED 일치를 체크한 뒤 확인하세요.';return;}host('confirmSensorMap');}
 function fresh(){return latest&&latest.connected&&latest.receivedAt&&new Date().getTime()-latest.receivedAt<10000;}
 function number(kind,unit){return fresh()&&typeof latest[kind]==='number'?latest[kind].toFixed(1)+' '+unit:'미수신';}
 function receive(raw){
  try{
   var data=typeof raw==='string'?JSON.parse(raw):raw,c=data.config;if(!c)return;
   latest=data;
   if(!dirty&&!loaded){
    var devices=data.devices||[],all=[c.path],html='';for(var i=0;i<devices.length;i++)if(all.indexOf(devices[i])<0)all.push(devices[i]);
    for(i=0;i<all.length;i++)html+='<option value="'+esc(all[i])+'">'+esc(all[i])+(all[i]===c.path?' · 현재 설정':'')+'</option>';
    e('sensorPath').innerHTML=html;e('sensorPath').value=c.path;e('sensorBaud').value=String(c.baud);e('sensorId').value=String(c.id);e('sensorFc').value=String(c.fc);e('sensorMap').value=c.map;loaded=true;
   }
   // Discovery may select another actual ID/baud; update saved configuration only while not editing.
   if(!dirty){e('sensorBaud').value=String(c.baud);e('sensorId').value=String(c.id);e('sensorEdit').textContent='';}
   e('sensorMessage').textContent=data.message||'상태 확인 중';
   e('sensorConfigStatus').textContent=(c.enabled?'감시 ON':'감시 OFF')+' · '+c.path+' · '+c.baud+'bps / ID '+c.id+' / FC0'+c.fc+' · '+(c.verified?'OLED 일치 확인됨':'시험 통신맵 · 미확정');
   e('sensorTemp').textContent=number('temperature','°C');e('sensorHum').textContent=number('humidity','%RH');
   e('sensorAt').textContent=fresh()?new Date(data.receivedAt).toLocaleTimeString():'없음';
   e('sensorRaw').textContent='TX: '+(data.tx||'없음')+'\nRX: '+(data.rx||'없음')+'\n오류: '+(data.error||'없음');
   e('sensorSearch').disabled=!!data.searching;e('sensorConfirm').disabled=!fresh()||!!data.searching||dirty;
   if(!fresh())e('sensorAck').checked=false;
   decorate();
  }catch(ex){e('sensorMessage').textContent='센서 응답 표시 오류: '+ex.message;}
 }
 function decorate(){
  if(!latest)return;var ok=fresh(),verified=latest.config.verified,label=ok?(verified?'COM6 온습도 수신':'COM6 시험 수신 · 맵 확인 전'):'COM6 센서 미수신';
  if(e('dashSensor'))e('dashSensor').textContent=label;
  if(e('houseTemperature1'))e('houseTemperature1').textContent=number('temperature','°C');
  if(e('houseHumidity1'))e('houseHumidity1').textContent=number('humidity','%');
  if(e('houseSensorStatus1'))e('houseSensorStatus1').textContent=label;
  if(e('environmentSensor1'))e('environmentSensor1').textContent='온도 '+number('temperature','°C')+' · 습도 '+number('humidity','%RH')+' · '+(ok?(verified?'실수신 · 이력 연결':'시험 변환값 · OLED 대조 필요'):'미수신');
 }
 return {init:init,receive:receive,reload:reload,dirty:mark,connect:connect,search:search,cancel:function(){host('cancelSensorSearch');},stop:function(){host('stopSensor');},confirm:confirm,decorate:decorate};
})();

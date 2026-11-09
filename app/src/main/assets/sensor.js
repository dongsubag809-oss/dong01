/* Supplier-manual COM6 setup; native callbacks are the only measurement source. */
window.EBSensor=(function(){
 var latest=null,dirty=false,loaded=false,formKey='',houseMarkup='';
 function e(id){return document.getElementById(id);}
 function host(name,args){
  var h=window.NativeDiagnostics,a=args||[];
  if(!h||typeof h[name]!=='function'){e('sensorMessage').textContent='Android HMI에서 실행하세요.';return false;}
  try{switch(a.length){
   case 0:h[name]();break;case 1:h[name](a[0]);break;case 2:h[name](a[0],a[1]);break;
   case 3:h[name](a[0],a[1],a[2]);break;case 4:h[name](a[0],a[1],a[2],a[3]);break;
   case 5:h[name](a[0],a[1],a[2],a[3],a[4]);break;default:throw new Error('지원하지 않는 호출 인수 개수');
  }return true;}catch(error){
   var detail='센서 설정 연결 오류 ['+name+']: '+(error.message||error);
   e('sensorMessage').textContent=detail;
   if(window.reportHmiFault)window.reportHmiFault(detail,'sensor.js',0,0,error.stack||'');return false;
  }
 }
 function esc(s){return String(s===null||s===undefined?'':s).replace(/[&<>"']/g,function(c){return {'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c];});}
 function init(){
  if(!e('sensorRoot'))return;
  e('sensorRoot').innerHTML=[
   '<div class="panel sensor-simple"><div class="sensor-heading"><div><h3>온습도 센서 연결</h3><p id="sensorIntro" class="auto-muted">동을 고르면 센서 ID도 같은 번호로 설정됩니다.</p></div><span id="sensorSetupStatus" class="auto-pill"></span></div>',
   '<ol class="sensor-steps"><li id="sensorStep1">① 동 선택</li><li id="sensorStep2">② 센서 확인</li><li id="sensorStep3">③ 적용·완료</li></ol>',
   '<div class="sensor-house-grid" id="sensorHouseButtons"></div><select id="sensorHouse" aria-label="설치할 동" style="display:none" onchange="EBSensor.selectHouse(this.value)"></select>',
   '<div class="sensor-guide"><h3 id="sensorFlowTitle">설치할 동을 선택하세요</h3><p id="sensorFlowHelp"></p><p id="sensorDetected" class="sensor-detected"></p>',
   '<button id="sensorBegin" class="sensor-main" onclick="EBSensor.begin()">선택 동에 센서 연결</button>',
   '<div id="sensorSetupControls"><label class="sensor-one"><input id="sensorOne" type="checkbox" onchange="EBSensor.oneChanged()"><span>다른 센서는 분리하고, 설정할 센서 <b>한 대만</b> 연결했습니다.</span></label>',
   '<div class="sensor-actions"><button id="sensorSearch" onclick="EBSensor.search()">센서 찾기</button><button id="sensorWriteId" class="sensor-main" onclick="EBSensor.apply()">선택 동으로 설정</button><button id="sensorCancel" onclick="EBSensor.cancel()">찾기 중지</button></div>',
   '<button id="sensorLeave" class="sensor-secondary" onclick="EBSensor.leave()">설정 닫기 · 전체 감시로 돌아가기</button></div>',
   '<div id="sensorPower" class="sensor-power"><strong>센서 전원을 껐다 켜주세요</strong><p>새 설정으로 실제 수신을 확인하고 있습니다.<br>정상 응답 2회 후 자동으로 등록됩니다. 설정 버튼을 다시 누르지 마세요.</p></div></div>',
   '<p id="sensorMessage" class="sensor-message" role="status">상태 확인 중</p>',
   '<div class="auto-metrics sensor-values"><div>선택 센서 온도<strong id="sensorTemp">미수신</strong></div><div>습도<strong id="sensorHum">미수신</strong></div><div>최근 수신<strong id="sensorAt">없음</strong></div></div>',
   '<details id="sensorAdvanced" class="sensor-advanced"><summary>고급 설정 · 통신 속도 / 장치 / 복구</summary><p class="auto-muted">일반 설치는 위의 순서대로 진행하세요. 기존 통신 설정을 직접 바꿀 때만 사용합니다.</p>',
   '<div class="auto-form"><div class="auto-field"><label>센서 실제 장치<select id="sensorPath" onchange="EBSensor.dirty()"></select></label><p class="auto-muted">/dev/ttyS6는 COM6 후보 경로입니다. COM5 PLC 경로 중복은 차단합니다.</p></div>',
   '<div class="auto-field"><label>현재 수신 속도<select id="sensorBaud" onchange="EBSensor.dirty()"><option>9600</option><option>4800</option><option>2400</option></select></label><label>현재 센서 ID<select id="sensorId" onchange="EBSensor.dirty()"></select></label></div>',
   '<div class="auto-field"><label>읽기 기능<select id="sensorFc" onchange="EBSensor.dirty()"><option value="3">FC03 · 제공 매뉴얼</option><option value="4">FC04 · 이전 시험 설정</option></select></label><label>수신맵<select id="sensorMap" onchange="EBSensor.dirty()"><option value="h0t1">습도 0000 / 온도 0001 · ÷10</option><option value="t0h1">온도 0000 / 습도 0001 · 이전 시험</option></select></label></div></div>',
   '<p id="sensorConfigStatus" class="auto-muted"></p><p id="sensorEdit" class="auto-dirty"></p><div class="sensor-actions"><button id="sensorConnect" onclick="EBSensor.connect()">수신 설정 저장·연결</button><button id="sensorReload" onclick="EBSensor.reload()">저장 설정 다시 읽기</button><button id="sensorStop" onclick="EBSensor.stop()">선택 동 감시 중지</button><button id="sensorEditExisting" onclick="EBSensor.edit()">현재 ID·속도로 수정 시작</button></div>',
   '<div class="sensor-baud"><label>센서에 저장할 새 속도<select id="sensorNewBaud"><option>9600</option><option>4800</option><option>2400</option></select></label><button id="sensorWriteBaud" onclick="EBSensor.writeBaud()">속도 변경</button></div><p class="auto-muted">속도 변경도 센서 한 대 연결 확인이 필요합니다. ID 등록 완료 후 수정 시작을 눌러 변경하세요. 변경 후 센서 전원 재인가가 필요합니다.</p>',
   '<p id="sensorPending" class="auto-notice"></p><div class="sensor-actions"><button id="sensorVerify" onclick="EBSensor.verify()">변경 결과 다시 확인</button><button id="sensorRestore" onclick="EBSensor.restore()">기존 응답 설정으로 복구</button><button id="sensorFinish" onclick="EBSensor.finish()">수동 등록 완료</button></div>',
   '<div class="sensor-actions"><label class="sensor-one"><input type="checkbox" id="sensorAck"><span>센서 OLED와 온습도가 일치합니다.</span></label><button id="sensorConfirm" onclick="EBSensor.confirm()">OLED 일치 확인</button></div><p class="auto-muted">설정 중 임시 ID 값은 동 대시보드·이력에 반영하지 않습니다. 온습도 조건의 실제 자동 출력은 기존 잠금 상태를 유지합니다.</p>',
   '<details><summary>송수신 원문·오류 진단</summary><pre id="sensorRaw" class="sensor-raw"></pre></details></details>',
   '<details class="sensor-advanced"><summary>전체 5동 센서 상태</summary><div class="auto-scroll-chart"><table class="auto-table"><thead><tr><th>동 / 고정 ID</th><th>속도</th><th>상태</th><th>온도 / 습도</th></tr></thead><tbody id="sensorRegistry"></tbody></table></div></details></div>'
  ].join('');
  var ids='',houses='';for(var id=1;id<=247;id++)ids+='<option>'+id+'</option>';for(id=1;id<=5;id++)houses+='<option value="'+id+'">'+id+'동 · ID '+id+'</option>';
  e('sensorId').innerHTML=ids;e('sensorHouse').innerHTML=houses;reload();setInterval(refresh,2000);
 }
 function showNode(id,show){e(id).style.display=show?'':'none';}
 function renderFlow(){
  if(!latest)return;var d=latest,c=d.config,h=d.selected||c.house,p=d.pending,setup=!!d.setup,busy=!!d.searching,here=d.setup===h,live=fresh(d),good=live&&c.verified&&d.registered&&c.enabled,single=e('sensorOne').checked;
  var buttons='',rows=d.houses||[];for(var i=1;i<=5;i++){
   var r=rows[i-1],ok=r&&r.registered&&r.config.verified&&r.config.enabled&&fresh(r)&&!setup&&!p;
   buttons+='<button id="sensorHouse'+i+'" class="sensor-house'+(i===h?' selected':'')+'" aria-pressed="'+(i===h?'true':'false')+'" '+((setup||busy||p)?'disabled':'')+' onclick="EBSensor.selectHouse('+i+')"><b>'+i+'동</b><span>ID '+i+'</span><small>'+(ok?'수신 정상':'미수신')+'</small></button>';
  }if(buttons!==houseMarkup){houseMarkup=buttons;e('sensorHouseButtons').innerHTML=buttons;}e('sensorHouse').disabled=setup||busy||!!p;
  e('sensorStep1').className=!setup&&!p?'current':'done';e('sensorStep2').className=setup&&!p?'current':'';e('sensorStep3').className=p?'current':!setup&&good?'done':'';
  showNode('sensorIntro',!setup&&!p);showNode('sensorHouseButtons',!setup&&!p);showNode('sensorBegin',!setup&&!p);showNode('sensorSetupControls',setup&&!p);showNode('sensorPower',!!p);showNode('sensorCancel',busy);
  e('sensorOne').disabled=busy||!!p;e('sensorBegin').disabled=setup||busy||!!p||dirty;e('sensorBegin').textContent=h+'동에 센서 연결 / 변경';
  e('sensorSearch').disabled=!here||busy||!!p||dirty||!single;
  e('sensorWriteId').disabled=!here||busy||!!p||dirty||!single||!live||!c.verified||(c.id!==h&&(c.fc!==3||c.map!=='h0t1'));
  e('sensorWriteId').textContent=c.id===h?h+'동에 연결':h+'동으로 설정 · ID '+c.id+' → '+h;
  e('sensorWriteBaud').disabled=!here||busy||!!p||dirty||!single||!live||c.id!==h;
  e('sensorDetected').textContent=setup&&!p&&live?'센서 확인됨 · 현재 ID '+c.id+' / '+c.baud+'bps · '+number(d,'temperature','°C')+' / '+number(d,'humidity','%RH'):'';
  if(p){e('sensorFlowTitle').textContent=p.house+'동 설정 적용 대기';e('sensorFlowHelp').textContent='설정 명령을 보냈습니다. 센서 전원을 껐다 켜면 자동으로 연결됩니다.';}
  else if(setup){e('sensorFlowTitle').textContent=d.setup+'동 센서 '+(busy?'찾는 중':live?'확인 완료':'확인');e('sensorFlowHelp').textContent=busy?'응답을 기다리고 있습니다. 찾기 중지로 취소할 수 있습니다.':!single?'COM6에 설정할 센서 한 대만 연결한 뒤 아래 항목을 체크하세요.':live?'아래 '+h+'동 설정 버튼을 누르세요. ID가 이미 같으면 쓰기 없이 실제 수신을 확인해 연결합니다.':'센서 찾기를 누르세요. ID와 통신 속도는 자동으로 확인합니다.';}
  else{e('sensorFlowTitle').textContent=good?h+'동 수신 정상 · ID '+h:h+'동 센서 연결';e('sensorFlowHelp').textContent=good?'온습도는 해당 동 대시보드와 이력에 연결되어 있습니다. 추가 센서는 설치할 동을 선택하세요.':'설치할 동을 고르고 아래 버튼을 누르세요. 센서 ID를 직접 입력할 필요가 없습니다.';}
 }
 function open(){var root=e('sensorRoot'),main=document.querySelector('main');if(root&&main){window.scrollTo(0,0);main.scrollTop+=root.getBoundingClientRect().top-main.getBoundingClientRect().top;}}
 function refresh(){try{var h=window.NativeDiagnostics;if(h&&typeof h.sensorInfo==='function')receive(h.sensorInfo());}catch(ex){e('sensorMessage').textContent='센서 조회 오류: '+ex.message;}}
 function reload(){dirty=false;loaded=false;refresh();}
 function selectHouse(h){if(!clean())return;if(latest&&(latest.setup||latest.pending||latest.searching)){e('sensorMessage').textContent='진행 중인 센서 설정을 먼저 마치세요.';return;}dirty=false;loaded=false;e('sensorOne').checked=false;host('selectSensorHouse',[+h]);}
 function mark(){dirty=true;e('sensorOne').checked=false;e('sensorEdit').textContent='변경값 미적용 · 수신 설정 저장·연결을 누르세요.';renderFlow();}
 function connect(){dirty=false;loaded=false;e('sensorOne').checked=false;host('configureSensor',[JSON.stringify({house:+e('sensorHouse').value,path:e('sensorPath').value,baud:+e('sensorBaud').value,id:+e('sensorId').value,fc:+e('sensorFc').value,map:e('sensorMap').value,enabled:true})]);}
 function clean(){if(dirty){e('sensorMessage').textContent='변경한 수신 설정을 먼저 저장하세요.';return false;}return true;}
 function begin(){if(clean())host('beginSensorSetup',[+e('sensorHouse').value]);}
 function edit(){if(clean())host('editSensorSetup',[+e('sensorHouse').value]);}
 function search(){if(!clean())return;if(!e('sensorOne').checked){e('sensorMessage').textContent='설정할 센서 한 대만 연결했는지 체크하세요.';return;}host('searchSensor');}
 function apply(){if(!clean())return;if(!latest||e('sensorWriteId').disabled)return;if(latest.config.id===latest.config.house)connect();else writeId();}
 function confirm(){if(!clean()||!e('sensorAck').checked){e('sensorMessage').textContent='현재 수신값과 OLED 일치 확인을 체크하세요.';return;}host('confirmSensorMap');}
 function writeId(){if(clean())host('changeSensorId',[latest.setup,!!e('sensorOne').checked]);e('sensorOne').checked=false;renderFlow();}
 function writeBaud(){if(clean())host('changeSensorBaud',[+e('sensorNewBaud').value,!!e('sensorOne').checked]);e('sensorOne').checked=false;renderFlow();}
 function fresh(d){return d&&d.connected&&d.receivedAt&&new Date().getTime()-d.receivedAt<12000;}
 function number(d,kind,unit){return fresh(d)&&typeof d[kind]==='number'?d[kind].toFixed(1)+' '+unit:'미수신';}
 function receive(raw){try{
  var d=typeof raw==='string'?JSON.parse(raw):raw,c=d.config;if(!c)return;latest=d;
  var nextKey=[d.selected||c.house,c.path,c.fc,c.map].join(':');if(!dirty&&(!loaded||formKey!==nextKey)){formKey=nextKey;var devices=d.devices||[],all=[c.path],html='';for(var i=0;i<devices.length;i++)if(all.indexOf(devices[i])<0)all.push(devices[i]);for(i=0;i<all.length;i++)html+='<option value="'+esc(all[i])+'">'+esc(all[i])+'</option>';e('sensorPath').innerHTML=html;e('sensorPath').value=c.path;e('sensorBaud').value=String(c.baud);e('sensorId').value=String(c.id);e('sensorFc').value=String(c.fc);e('sensorMap').value=c.map;loaded=true;}
  if(!dirty){e('sensorHouse').value=String(d.selected||c.house||1);e('sensorId').value=String(c.id);e('sensorBaud').value=String(c.baud);e('sensorEdit').textContent='';}
  e('sensorConfirm').disabled=!fresh(d)||!!d.pending||!!d.searching||dirty;e('sensorId').disabled=!d.setup;e('sensorSetupStatus').textContent=d.setup?'신규 '+d.setup+'동 단일 센서 설정 중':'ID1~5 전체 감시';
  e('sensorMessage').textContent=d.message||'';e('sensorConfigStatus').textContent=c.path+' · '+c.baud+'bps · 현재 ID '+c.id+' → '+c.house+'동'+(d.setup?' · 임시 수신/미등록':' · 고정 ID 수신');
  e('sensorTemp').textContent=number(d,'temperature','°C');e('sensorHum').textContent=number(d,'humidity','%RH');e('sensorAt').textContent=fresh(d)?new Date(d.receivedAt).toLocaleTimeString():'없음';
  e('sensorRaw').textContent='TX: '+(d.tx||'없음')+'\nRX: '+(d.rx||'없음')+'\n오류: '+(d.error||'없음');
  var p=d.pending,busy=!!d.searching,setup=!!d.setup,here=d.setup===(d.selected||c.house);
  e('sensorPending').textContent=p?'보류 변경 · '+p.house+'동 / '+(p.register===256?'ID':'속도')+' · '+p.old.id+'번 '+p.old.baud+'bps → '+p.next.id+'번 '+p.next.baud+'bps · '+p.phase+' · 센서 전원 재인가 후 결과 확인':'보류 변경 없음';
  e('sensorEditExisting').disabled=setup||busy||!!p;e('sensorConnect').disabled=busy||!!p;
  e('sensorVerify').disabled=!p||busy;e('sensorRestore').disabled=!p||busy||!d.oldConfirmed;e('sensorFinish').disabled=!here||busy||!!p||!fresh(d)||c.id!==c.house;e('sensorLeave').disabled=!setup||busy||!!p;
  var rows=d.houses||[],table='';for(i=0;i<rows.length;i++){var r=rows[i];table+='<tr><td>'+r.house+'동 / ID '+r.house+'</td><td>'+r.config.baud+'bps</td><td>'+esc(d.setup?'설정 중 · 이력 중지':r.connected?'수신 정상':r.status||'미수신')+'</td><td>'+esc(r.registered?number(r,'temperature','°C')+' / '+number(r,'humidity','%RH'):'미등록')+'</td></tr>';}e('sensorRegistry').innerHTML=table;renderFlow();decorate();
 }catch(ex){e('sensorMessage').textContent='센서 표시 오류: '+ex.message;}}
 function decorate(){if(!latest)return;var rows=latest.houses||[],count=0;for(var i=0;i<rows.length;i++){
  var r=rows[i],h=r.house,ok=!latest.setup&&!latest.pending&&r.registered&&fresh(r)&&r.config.verified,label=latest.setup?'신규 설정 중 · 이력 중지':ok?'ID '+h+' 온습도 실수신':'ID '+h+' 센서 미수신';if(ok)count++;
  if(e('houseTemperature'+h))e('houseTemperature'+h).textContent=ok?number(r,'temperature','°C'):'미수신';
  if(e('houseHumidity'+h))e('houseHumidity'+h).textContent=ok?number(r,'humidity','%'):'미수신';
  if(e('houseSensorStatus'+h))e('houseSensorStatus'+h).textContent=label;
  if(e('environmentSensor'+h))e('environmentSensor'+h).textContent=label+(ok?' · '+number(r,'temperature','°C')+' / '+number(r,'humidity','%RH'):'');
 }if(e('dashSensor'))e('dashSensor').textContent='COM6 센서 '+count+'/5동 수신';}
 return {init:init,open:open,receive:receive,reload:reload,dirty:mark,selectHouse:selectHouse,apply:apply,oneChanged:renderFlow,connect:connect,begin:begin,edit:edit,search:search,writeId:writeId,writeBaud:writeBaud,confirm:confirm,cancel:function(){host('cancelSensorSearch');},stop:function(){host('stopSensor');},verify:function(){host('verifySensorChange');},restore:function(){host('restoreSensorOld');},finish:function(){host('finishSensorSetup');},leave:function(){host('leaveSensorSetup');},decorate:decorate};
})();

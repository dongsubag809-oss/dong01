'use strict';
const fs=require('fs'),vm=require('vm'),assert=require('assert');
const source=fs.readFileSync('app/src/main/assets/sensor.js','utf8');assert(!/\b(?:let|const)\s+[A-Za-z_$]|=>|\.padStart\s*\(/.test(source));
const nodes={},calls=[];function node(id){return nodes[id]||(nodes[id]={value:'',textContent:'',innerHTML:'',disabled:false,checked:false});}
const bridge={sensorInfo:()=>JSON.stringify(data),configureSensor:raw=>calls.push(['configure',JSON.parse(raw)]),searchSensor:()=>calls.push(['search']),confirmSensorMap:()=>calls.push(['confirm']),cancelSensorSearch:()=>calls.push(['cancel']),stopSensor:()=>calls.push(['stop'])};
let data={config:{path:'/dev/ttyS6',baud:9600,id:1,fc:3,map:'h0t1',verified:false,enabled:true},connected:false,searching:false,temperature:null,humidity:null,receivedAt:0,tx:'',rx:'',devices:['/dev/ttyS6','/dev/ttyS7']};
const ctx=vm.createContext({window:{NativeDiagnostics:bridge},document:{getElementById:node},setInterval(){},Date,JSON});vm.runInContext(source,ctx);const api=ctx.window.EBSensor;api.init();assert(node('sensorTemp').textContent==='미수신');
data={...data,connected:true,temperature:25.1,humidity:56.3,receivedAt:Date.now(),tx:'01 03',rx:'01 03 04'};api.receive(data);assert.equal(node('houseTemperature1').textContent,'25.1 °C');assert(node('houseSensorStatus1').textContent.includes('맵 확인 전'));assert(!node('sensorConfirm').disabled);
api.confirm();assert(!calls.some(c=>c[0]==='confirm'));node('sensorAck').checked=true;api.confirm();assert(calls.some(c=>c[0]==='confirm'));
node('sensorId').value='7';api.dirty();api.receive(data);assert.equal(node('sensorId').value,'7');api.search();assert(!calls.some(c=>c[0]==='search'));api.connect();assert.equal(calls.filter(c=>c[0]==='configure')[0][1].id,7);assert(!calls.filter(c=>c[0]==='configure')[0][1].verified);
api.search();assert(calls.some(c=>c[0]==='search'));api.cancel();api.stop();assert(calls.some(c=>c[0]==='cancel')&&calls.some(c=>c[0]==='stop'));
api.receive({...data,connected:false,temperature:null,humidity:null,receivedAt:0});assert.equal(node('houseTemperature1').textContent,'미수신');assert.equal(node('houseHumidity1').textContent,'미수신');assert(node('sensorConfirm').disabled);
api.receive({...data,receivedAt:Date.now()-20000});assert.equal(node('sensorTemp').textContent,'미수신');
assert(!source.includes('setSensorValue')&&!source.includes('Math.random'));
console.log('PASS: sensor UI empty/stale clearing, unconfirmed labels, read-only search, explicit OLED confirmation and unsaved setting protection');

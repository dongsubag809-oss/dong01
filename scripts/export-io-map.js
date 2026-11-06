"use strict";
// Build-time audit of 128 logical N4D4F64 DO slots from the same labels as HMI.
// This does not assert that any wire is connected. DO commands are never executed here.
const fs=require("fs");
const path=require("path");
const html=fs.readFileSync("app/src/main/assets/index.html","utf8");
const match=html.match(/var mappedOutputs=\[([\s\S]*?)\];/);
if(!match)throw Error("HMI mappedOutputs assignment not found");
const labels=Array.from(match[1].matchAll(/'([^']+)'/g),m=>m[1]);
if(labels.length!==24)throw Error("Expected exactly 24 historical DO functions per house; found "+labels.length);
const esc=value=>'"'+String(value).replace(/"/g,'""')+'"';
const rows=[["PLC_ID","DO","HOUSE","DEFAULT_OUTPUT_NAME","PHYSICAL_WIRING","SOURCE"]];
for(let i=0;i<128;i++){
 const id=Math.floor(i/64)+1,ch=i%64+1,h=Math.floor(i/24)+1;
 const mapped=i<120;
 rows.push([id,"DO"+String(ch).padStart(2,"0"),mapped?h:"예비",
   mapped?h+"동 "+labels[i%24]:"예비 출력 "+(i-119),
   "현장 결선 미검증",mapped?"기존 do120_control.py 24DO/동 순서":"잔여 8DO 예비"]);
}
const output=process.argv[2]||"dist/DO_MAP_128.csv";
fs.mkdirSync(path.dirname(output),{recursive:true});
fs.writeFileSync(output,"\ufeff"+rows.map(row=>row.map(esc).join(",")).join("\r\n")+"\r\n","utf8");
console.log("PASS: mapped "+(rows.length-1)+" PLC DO channels across ID1/ID2; 120 house outputs and 8 spare; saved "+output);

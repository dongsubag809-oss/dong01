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
console.log("PASS: WebView legacy syntax; render; 64-DO registry; menus; COM5 handler; Korean keyboard.");

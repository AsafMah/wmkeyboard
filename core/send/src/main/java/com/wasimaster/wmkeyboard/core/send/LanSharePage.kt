package com.wasimaster.wmkeyboard.core.send

/**
 * The page a browser gets from [LanShare] (#927): one self-contained HTML
 * file, no fonts, no scripts from elsewhere, so it opens on a network with no
 * internet at all. It reads `files.json` for the list and the phone's name,
 * polls it so files added on the phone show up, offers each for download, and
 * sends picked or dropped files back one at a time with a progress bar.
 *
 * English only: it is read on someone else's device, in whatever language
 * that is, and the phone cannot know it. Names come in as JSON and go out
 * through `textContent`, never as markup.
 */
internal object LanSharePage {

    fun html(base: String): String = PAGE.replace("__BASE__", base)

    private val PAGE = """<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta name="referrer" content="no-referrer">
<title>WM Keyboard</title>
<style>
:root{--bg:#f6f7f9;--card:#fff;--text:#1b1d21;--muted:#62666d;--line:#e3e5e9;--accent:#2f6fde;--ok:#2e8540;--bad:#c62828}
@media (prefers-color-scheme:dark){:root{--bg:#121316;--card:#1d1f24;--text:#eceef1;--muted:#9aa0a8;--line:#2c2f36;--accent:#7aa7ff;--ok:#6cc47a;--bad:#ff7b72}}
*{box-sizing:border-box}
body{margin:0;background:var(--bg);color:var(--text);font:15px/1.45 system-ui,-apple-system,"Segoe UI",Roboto,sans-serif}
main{max-width:640px;margin:0 auto;padding:20px 16px 40px}
h1{font-size:21px;margin:4px 0 2px}
.sub{color:var(--muted);font-size:13px;margin:0 0 18px}
section{background:var(--card);border:1px solid var(--line);border-radius:14px;padding:14px;margin-bottom:14px}
h2{font-size:14px;margin:0 0 10px;color:var(--muted);font-weight:600;text-transform:uppercase;letter-spacing:.04em}
.row{display:flex;align-items:center;gap:12px;padding:9px 0;border-top:1px solid var(--line)}
.row:first-of-type{border-top:0}
.name{flex:1;min-width:0;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
.meta{color:var(--muted);font-size:12px}
a.btn,label.btn{display:inline-block;background:var(--accent);color:#fff;text-decoration:none;border-radius:999px;padding:7px 14px;font-size:14px;font-weight:600;cursor:pointer;white-space:nowrap}
.empty{color:var(--muted);font-size:14px;margin:4px 0}
#drop{border:2px dashed var(--line);border-radius:12px;padding:18px;text-align:center}
#drop.over{border-color:var(--accent)}
#drop p{margin:0 0 10px;color:var(--muted);font-size:14px}
input[type=file]{display:none}
.bar{height:4px;background:var(--line);border-radius:4px;overflow:hidden;margin-top:5px}
.bar i{display:block;height:100%;width:0;background:var(--accent)}
.ok{color:var(--ok)}.bad{color:var(--bad)}
#gone{display:none;background:var(--card);border:1px solid var(--line);border-radius:14px;padding:14px;color:var(--bad)}
footer{color:var(--muted);font-size:12px;text-align:center;margin-top:18px}
</style>
</head>
<body>
<main>
<h1 id="title">Files from a phone</h1>
<p class="sub">Shared with WM Keyboard over this network. Nothing goes through the internet.</p>
<div id="gone">The phone stopped sharing. Ask for a new code.</div>
<section id="get"><h2>Download</h2><div id="list"><p class="empty">Loading…</p></div></section>
<section id="put" hidden><h2>Send to the phone</h2>
<div id="drop"><p>Drop files here, or</p><label class="btn">Choose files<input id="pick" type="file" multiple></label></div>
<div id="sent"></div>
</section>
<footer>The page works only while the phone shares it.</footer>
</main>
<script>
(function(){
var B="__BASE__";
var last="";
var fails=0;
function el(tag,cls,text){var e=document.createElement(tag);if(cls)e.className=cls;if(text!=null)e.textContent=text;return e}
function size(n){if(n<0)return"";var u=["B","KB","MB","GB","TB"],i=0;while(n>=1024&&i<u.length-1){n/=1024;i++}return(i?n.toFixed(n<10?1:0):n)+" "+u[i]}
function render(d){
  document.getElementById("title").textContent=d.name?("Files from "+d.name):"Files from a phone";
  document.title=d.name?(d.name+" · WM Keyboard"):"WM Keyboard";
  document.getElementById("put").hidden=!d.uploads;
  var key=JSON.stringify(d.files);if(key===last)return;last=key;
  var list=document.getElementById("list");list.textContent="";
  if(!d.files.length){list.appendChild(el("p","empty","Nothing shared to download."));return}
  d.files.forEach(function(f){
    var r=el("div","row"),c=el("div","name");
    c.appendChild(el("div",null,f.name));c.appendChild(el("div","meta",size(f.size)));
    var a=el("a","btn","Download");a.href=B+"f/"+f.i+"/"+encodeURIComponent(f.name);a.setAttribute("download",f.name);
    r.appendChild(c);r.appendChild(a);list.appendChild(r);
  });
}
function poll(){
  var x=new XMLHttpRequest();x.open("GET",B+"files.json");
  x.onload=function(){if(x.status===200){fails=0;document.getElementById("gone").style.display="none";try{render(JSON.parse(x.responseText))}catch(e){}}else gone()};
  x.onerror=gone;x.send();
}
function gone(){if(++fails>=2)document.getElementById("gone").style.display="block"}
var queue=[],busy=false;
function add(files){for(var i=0;i<files.length;i++)queue.push(files[i]);next()}
function next(){
  if(busy||!queue.length)return;busy=true;
  var f=queue.shift(),r=el("div","row"),c=el("div","name"),m=el("div","meta",size(f.size)),b=el("div","bar"),i=el("i");
  b.appendChild(i);c.appendChild(el("div",null,f.name));c.appendChild(m);c.appendChild(b);r.appendChild(c);
  document.getElementById("sent").appendChild(r);
  var x=new XMLHttpRequest();x.open("POST",B+"upload?name="+encodeURIComponent(f.name));
  x.upload.onprogress=function(e){if(e.lengthComputable)i.style.width=(100*e.loaded/e.total)+"%"};
  x.onload=function(){var ok=x.status===200;i.style.width="100%";m.textContent=ok?(size(f.size)+" · Sent"):"Not sent";m.className="meta "+(ok?"ok":"bad");busy=false;next()};
  x.onerror=function(){m.textContent="Not sent";m.className="meta bad";busy=false;next()};
  x.send(f);
}
document.getElementById("pick").addEventListener("change",function(e){add(e.target.files);e.target.value=""});
var d=document.getElementById("drop");
d.addEventListener("dragover",function(e){e.preventDefault();d.className="over"});
d.addEventListener("dragleave",function(){d.className=""});
d.addEventListener("drop",function(e){e.preventDefault();d.className="";add(e.dataTransfer.files)});
poll();setInterval(poll,3000);
})();
</script>
</body>
</html>
"""
}

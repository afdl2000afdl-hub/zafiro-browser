package com.niki914.zafiro.chat.agentic.iab

/**
 * IAB（内置浏览器）的纯 JS 载荷构造器。
 *
 * 设计原则（对应 ZCode IAB 的 evaluate/query/input 能力面）：
 * 1. 每个构造器产出一个「自包含 IIFE + JSON.stringify 结果」的 JS 字符串，
 *    统一结果信封 `{ok, ...}` / `{ok:false, error}`，由 [IabManager] 解析；
 * 2. 零 Android 依赖（纯字符串逻辑），可在 JVM 单测中直接验证；
 * 3. 注入的字符串参数一律先经 [jsQuote] 转义成 JS 单引号字面量、再存入
 *    本地变量后使用，防止不可信输入（agent 传入的 selector / value）破坏 JS。
 */
object IabJs {

    /** 把 Kotlin 字符串变成 JS 单引号字符串字面量（转义 \ ' \n \r \t 及 <0x20 控制符）。 */
    fun jsQuote(value: String): String {
        val sb = StringBuilder(value.length + 8)
        sb.append('\'')
        for (c in value) {
            when (c) {
                '\'' -> sb.append("\\'")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\u0008' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else -> {
                    if (c.code < 0x20) sb.append(String.format("\\u%04x", c.code)) else sb.append(c)
                }
            }
        }
        sb.append('\'')
        return sb.toString()
    }

    /**
     * 任意表达式求值信封（对应 ZCode 的 evaluate）：
     * - 表达式内联进 async IIFE，因此表达式内部可用 `await`；
     * - [awaitPromise]=true 时外层再包一层 `await (async () => { <expr> })()`；
     * - 结果经 __conv 归一：DOM 节点 → 结构化描述符，NodeList/Array → 逐元素归一（截 200），
     *   普通对象 → 深 JSON 兜底；
     * - 运行期异常折进 {ok:false,error}；纯语法错会让 evaluateJavascript 返回 null，
     *   由管理器侧按 JS_ENGINE_ERROR 处理。
     */
    fun evaluateEnvelope(expression: String, awaitPromise: Boolean): String {
        val valStmt = if (awaitPromise) "await (async () => { ${expression} })()" else "(${expression})"
        return """
            (async () => {
              const __conv = (v) => {
                if (v === null) return null;
                if (v === undefined) return "undefined";
                const t = typeof v;
                if (t === "string" || t === "number" || t === "boolean") return v;
                if (t === "function" || t === "symbol" || t === "bigint") return String(v);
                if (typeof Node !== "undefined" && v instanceof Node) {
                  return { node: v.tagName ? v.tagName.toLowerCase() : "#node", id: v.id || "", class: v.className || "", text: (v.textContent || "").slice(0, 500) };
                }
                if (Array.isArray(v) || (typeof NodeList !== "undefined" && v instanceof NodeList)) {
                  return Array.from(v).slice(0, 200).map(__conv);
                }
                try { return JSON.parse(JSON.stringify(v)); } catch (e) { try { return String(v); } catch (e2) { return "<unserializable>"; } }
              };
              try {
                const __val = ${valStmt};
                return JSON.stringify({ ok: true, value: __conv(__val) });
              } catch (e) {
                return JSON.stringify({ ok: false, error: String((e && e.message) ? e.message : e) });
              }
            })()
        """.trimIndent()
    }

    /** 控制台钩子：幂等安装（重复注入直接返回 "installed"），把 console.* 折进 500 条环形缓冲。 */
    fun consoleHook(): String = """
        (function(){
          if (window.__zafiroConsoleHook) return "installed";
          window.__zafiroConsoleHook = true;
          var ring = [];
          var push = function(level, args){
            try {
              var s = Array.prototype.map.call(args, function(a){
                try { return (typeof a === "object" && a !== null) ? JSON.stringify(a) : String(a); } catch (e) { return String(a); }
              }).join(" ");
              ring.push({ t: Date.now(), level: level, msg: s.slice(0, 2000) });
              if (ring.length > 500) ring.splice(0, ring.length - 500);
            } catch (e) {}
          };
          var install = function(){
            if (!window.__zafiroConsole) {
              window.__zafiroConsole = ring;
              ["log","info","warn","error","debug"].forEach(function(m){
                var orig = console[m];
                if (orig) console[m] = function(){ push(m, arguments); return orig.apply(console, arguments); };
              });
            }
          };
          if (document.readyState === "loading") document.addEventListener("DOMContentLoaded", install);
          else install();
          return "installed";
        })()
    """.trimIndent()

    /** 控制台读取：最近 [limit] 条（未安装钩子时 files 为空数组）。 */
    fun consoleRead(limit: Int): String =
        "JSON.stringify((function(){ var r = window.__zafiroConsole || []; return { count: r.length, lines: r.slice(-${limit.coerceIn(1, 500)}).map(function(e){ return e.level + \": \" + e.msg; }) }; })())"

    /** querySelector：CSS 选择器命中列表（tag/id/text/attrs 结构，截 [limit] 个）。 */
    fun querySelector(selector: String, limit: Int): String {
        val sel = jsQuote(selector)
        val n = limit.coerceIn(1, 500)
        return """
            (function(){
              try {
                var sel = ${sel};
                var all = document.querySelectorAll(sel);
                var nodes = Array.from(all).slice(0, ${n}).map(function(n){
                  var attrs = {};
                  for (var i = 0; i < n.attributes.length; i++) { var a = n.attributes[i]; attrs[a.name] = String(a.value).slice(0, 200); }
                  return { tag: n.tagName.toLowerCase(), id: n.id || "", text: (n.textContent || "").trim().slice(0, 300), attrs: attrs };
                });
                return JSON.stringify({ ok: true, count: all.length, nodes: nodes });
              } catch (e) { return JSON.stringify({ ok: false, error: String(e) }); }
            })()
        """.trimIndent()
    }

    /** 正文文本（innerText，截断 [limit] 字符）。 */
    fun pageText(limit: Int): String = """
        (function(){ try { var t = document.body ? document.body.innerText : (document.documentElement ? document.documentElement.innerText : ""); t = (t || "").replace(/\n{3,}/g, "\n\n"); return JSON.stringify({ ok: true, text: t.slice(0, ${limit.coerceIn(0, 200_000)}) }); } catch (e) { return JSON.stringify({ ok: false, error: String(e) }); } })()
    """.trimIndent()

    /** 整页 HTML（outerHTML，截断 [limit] 字符）。 */
    fun pageHtml(limit: Int): String = """
        (function(){ try { var h = document.documentElement ? document.documentElement.outerHTML : ""; return JSON.stringify({ ok: true, html: h.slice(0, ${limit.coerceIn(0, 200_000)}) }); } catch (e) { return JSON.stringify({ ok: false, error: String(e) }); } })()
    """.trimIndent()

    /** 按选择器点击：focus + click + blur（兼容 React 合成事件）。 */
    fun click(selector: String): String {
        val sel = jsQuote(selector)
        return """
            (function(){
              try {
                var sel = ${sel};
                var el = document.querySelector(sel);
                if (!el) return JSON.stringify({ ok: false, error: "selector not found: " + sel });
                el.focus && el.focus();
                el.click();
                return JSON.stringify({ ok: true, tag: el.tagName.toLowerCase(), id: el.id || "" });
              } catch (e) { return JSON.stringify({ ok: false, error: String(e) }); }
            })()
        """.trimIndent()
    }

    /** 按可见文本点击（先精确匹配，退化到 startsWith；作用域限定可交互元素，避免命中外层容器）。 */
    fun clickText(text: String): String {
        val want = jsQuote(text)
        return """
            (function(){
              try {
                var want = ${want};
                var cands = Array.from(document.querySelectorAll("a,button,input[type=submit],input[type=button],label,[role=button],[role=menuitem]")).filter(function(el){
                  var t = (el.textContent || "").trim();
                  return t === want || t.indexOf(want) === 0;
                });
                if (!cands.length) cands = Array.from(document.querySelectorAll("span,div")).filter(function(el){
                  return el.children.length === 0 && (el.textContent || "").trim() === want;
                });
                if (!cands.length) return JSON.stringify({ ok: false, error: "no element with text: " + want });
                var el = cands[cands.length - 1];
                el.scrollIntoView && el.scrollIntoView({ block: "center" });
                el.click();
                return JSON.stringify({ ok: true, tag: el.tagName.toLowerCase(), text: (el.textContent || "").trim().slice(0, 120) });
              } catch (e) { return JSON.stringify({ ok: false, error: String(e) }); }
            })()
        """.trimIndent()
    }

    /** 坐标点击（elementFromPoint + Pointer/MouseEvent 序列，模拟真实手势）。 */
    fun clickAt(x: Int, y: Int): String = """
        (function(){
          try {
            var x = ${x}; var y = ${y};
            var el = document.elementFromPoint(x, y);
            if (!el) return JSON.stringify({ ok: false, error: "no element at point (" + x + ", " + y + ")" });
            var opts = { bubbles: true, cancelable: true, clientX: x, clientY: y };
            ["pointerdown","mousedown","pointerup","mouseup","click"].forEach(function(name){
              var Ctor = (name.indexOf("pointer") === 0 && typeof PointerEvent !== "undefined") ? PointerEvent : MouseEvent;
              el.dispatchEvent(new Ctor(name, opts));
            });
            return JSON.stringify({ ok: true, tag: el.tagName.toLowerCase() });
          } catch (e) { return JSON.stringify({ ok: false, error: String(e) }); }
        })()
    """.trimIndent()

    /** 填输入框（React 兼容：走原型 value setter + input/change 事件）。 */
    fun fill(selector: String, value: String): String {
        val sel = jsQuote(selector)
        val valQuoted = jsQuote(value)
        return """
            (function(){
              try {
                var sel = ${sel};
                var el = document.querySelector(sel);
                if (!el) return JSON.stringify({ ok: false, error: "selector not found: " + sel });
                el.focus && el.focus();
                var isTa = typeof HTMLTextAreaElement !== "undefined" && el instanceof HTMLTextAreaElement;
                var proto = isTa ? HTMLTextAreaElement.prototype : (typeof HTMLInputElement !== "undefined" && el instanceof HTMLInputElement ? HTMLInputElement.prototype : HTMLSelectElement.prototype);
                var desc = Object.getOwnPropertyDescriptor(proto, "value");
                var v = ${valQuoted};
                if (desc && desc.set) desc.set.call(el, v); else el.value = v;
                el.dispatchEvent(new Event("input", { bubbles: true }));
                el.dispatchEvent(new Event("change", { bubbles: true }));
                el.blur && el.blur();
                return JSON.stringify({ ok: true, tag: el.tagName.toLowerCase(), value: String(el.value).slice(0, 200) });
              } catch (e) { return JSON.stringify({ ok: false, error: String(e) }); }
            })()
        """.trimIndent()
    }

    /** 勾选/取消勾选（property + change，兼容合成事件框架）。 */
    fun setChecked(selector: String, checked: Boolean): String {
        val sel = jsQuote(selector)
        return """
            (function(){
              try {
                var sel = ${sel};
                var el = document.querySelector(sel);
                if (!el) return JSON.stringify({ ok: false, error: "selector not found: " + sel });
                el.checked = ${checked};
                el.dispatchEvent(new Event("change", { bubbles: true }));
                return JSON.stringify({ ok: true, checked: el.checked, tag: el.tagName.toLowerCase() });
              } catch (e) { return JSON.stringify({ ok: false, error: String(e) }); }
            })()
        """.trimIndent()
    }

    /** 下拉选择（value + change；按 value/text/trim(text) 三向匹配）。 */
    fun selectOption(selector: String, option: String): String {
        val sel = jsQuote(selector)
        val opt = jsQuote(option)
        return """
            (function(){
              try {
                var sel = ${sel};
                var el = document.querySelector(sel);
                if (!el || el.tagName.toUpperCase() !== "SELECT") return JSON.stringify({ ok: false, error: "selector must resolve to a <select>" });
                var opts = Array.from(el.options || []);
                var want = ${opt};
                var target = opts.filter(function(o){ return o.value === want || o.text === want || o.text.trim() === want; })[0];
                if (!target) return JSON.stringify({ ok: false, error: "no option matching: " + want + " (have: " + opts.map(function(o){ return o.value; }).join(",") + ")" });
                el.value = target.value;
                el.dispatchEvent(new Event("change", { bubbles: true }));
                return JSON.stringify({ ok: true, value: el.value });
              } catch (e) { return JSON.stringify({ ok: false, error: String(e) }); }
            })()
        """.trimIndent()
    }

    /**
     * 按键（对应 ZCode keypress）：在 document.activeElement（退化 body）上派发
     * keydown/keyup；单字母键额外派发 keypress；Enter 命中 button 时补 click。
     * 限制（如实）：JS 合成按键无法替代真实 IME 输入，仅对监听 keydown/keyup 的表单有效。
     */
    fun keypress(keys: String): String {
        val key = jsQuote(keys)
        val singleLetter = keys.length == 1 && keys[0].isLetter()
        return """
            (function(){
              try {
                var key = ${key};
                var el = document.activeElement || document.body;
                var down = new KeyboardEvent("keydown", { key: key, bubbles: true, cancelable: true });
                el.dispatchEvent(down);
                ${if (singleLetter) "el.dispatchEvent(new KeyboardEvent(\"keypress\", { key: key, bubbles: true, cancelable: true }));" else "/* special key: no keypress */"}
                if (key === "Enter" && el.tagName === "BUTTON") el.click();
                el.dispatchEvent(new KeyboardEvent("keyup", { key: key, bubbles: true, cancelable: true }));
                return JSON.stringify({ ok: true, key: key, target: el.tagName.toLowerCase() });
              } catch (e) { return JSON.stringify({ ok: false, error: String(e) }); }
            })()
        """.trimIndent()
    }

    /** 滚动到坐标。 */
    fun scroll(x: Int, y: Int): String = """
        (function(){ window.scrollTo(${x}, ${y}); return JSON.stringify({ ok: true, scrollX: window.scrollX, scrollY: window.scrollY }); })()
    """.trimIndent()

    /** 表单提交（requestSubmit 优先，兼容旧引擎）；selector 命中 input/button 时自动上溯所在 form。 */
    fun submitForm(selector: String): String {
        val sel = jsQuote(selector)
        return """
            (function(){
              try {
                var sel = ${sel};
                var el = document.querySelector(sel);
                if (!el) return JSON.stringify({ ok: false, error: "selector not found: " + sel });
                var form = el.tagName === "FORM" ? el : el.form;
                if (!form) return JSON.stringify({ ok: false, error: "element is not inside a form" });
                if (form.requestSubmit && el.tagName !== "FORM") form.requestSubmit(el); else form.submit();
                return JSON.stringify({ ok: true, action: String(form.action || "") });
              } catch (e) { return JSON.stringify({ ok: false, error: String(e) }); }
            })()
        """.trimIndent()
    }

    /** 触发文件输入框的原生选择器（配合 onShowFileChooser；[selector] 须命中 input[type=file]）。 */
    fun uploadClick(selector: String): String {
        val sel = jsQuote(selector)
        return """
            (function(){
              try {
                var sel = ${sel};
                var el = document.querySelector(sel);
                if (!el) return JSON.stringify({ ok: false, error: "selector not found: " + sel });
                if (el.tagName.toUpperCase() !== "INPUT" || (el.type || "").toLowerCase() !== "file") return JSON.stringify({ ok: false, error: "selector is not an input[type=file]" });
                el.click();
                return JSON.stringify({ ok: true });
              } catch (e) { return JSON.stringify({ ok: false, error: String(e) }); }
            })()
        """.trimIndent()
    }

    /** 读取文件输入框当前文件数（1 = 文件已被 WebView 接受）。 */
    fun uploadFileCount(selector: String): String {
        val sel = jsQuote(selector)
        return """
            (function(){
              try {
                var sel = ${sel};
                var el = document.querySelector(sel);
                if (!el) return JSON.stringify({ ok: false, error: "selector not found: " + sel });
                return JSON.stringify({ ok: true, files: el.files ? el.files.length : -1 });
              } catch (e) { return JSON.stringify({ ok: false, error: String(e) }); }
            })()
        """.trimIndent()
    }

    /** 桌面模式：开启 → 写 meta viewport width=1280；关闭 → 移除该 meta。 */
    fun desktopViewport(on: Boolean): String = if (on) """
        (function(){
          var meta = document.querySelector('meta[name="viewport"]');
          if (!meta) { meta = document.createElement("meta"); meta.name = "viewport"; document.head.appendChild(meta); }
          meta.setAttribute("content", "width=1280");
          return JSON.stringify({ ok: true });
        })()
    """ else """
        (function(){
          var meta = document.querySelector('meta[name="viewport"]');
          if (meta) { document.head.removeChild(meta); return JSON.stringify({ ok: true }); }
          return JSON.stringify({ ok: true, note: "no meta viewport to remove" });
        })()
    """
}

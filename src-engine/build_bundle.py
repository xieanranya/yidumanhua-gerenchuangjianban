# 打包两端共用的 JS 引擎：prelude（Venera API + 纯 JS DOM）+ 线路层 + 固定源 + 门面 API
# 产物 bundle.js 由 Android 塞进 assets、iOS 塞进 Resources，两端跑同一份代码。
import json
import os

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "bundle.js")
MANIFEST = os.path.join(HERE, "bundle_sources.json")

# 固定源：key -> 文件（顺序即 UI 里的显示顺序）
FIXED = [
    ("jm", "jm.js"),
    ("copy_manga", "copy_manga.js"),
    ("Komiic", "komiic.js"),
    ("noyacg", "noyacg.js"),
    ("picacg", "picacg.js"),
    ("ehentai", "ehentai.js"),
    ("htmanga", "wnacg.js"),
]
# 可选源：和固定源一样会注册进来（UI 里能开关/排序）
# 1.9.17 按用户要求删掉 baozi(包子漫画)/zaimanhua(再漫画)/comick/mxs(漫小肆)/goda(GoDa漫画)，
# 脚本移到 vendor/_unused/，不打包也就不会出现在源列表里。
EXTRA = [
    ("mh18", "mh18.js"),
]


def read(p):
    return open(os.path.join(HERE, p), encoding="utf-8").read()


parts = []
parts.append("// bundle.js —— 由 build_bundle.py 生成，勿手改\n")
parts.append(read("prelude.js"))
parts.append("\n// ===== 线路层 =====\n")
parts.append(read("route.js"))

# 源代码以字符串形式嵌入，运行时用 new Function 还原（保留各自的类名作用域）
parts.append("\n// ===== 源 =====\nvar __SOURCE_CODE = {};\n")
for key, f in FIXED + EXTRA:
    code = read(os.path.join("vendor", f))
    parts.append("__SOURCE_CODE[%s] = %s;\n" % (json.dumps(key), json.dumps(code)))

parts.append("\n// ===== 门面 =====\n")
parts.append(read("engine_api.js"))

# 启动：把自己注册进 SourceEngine
parts.append("""
(function(){
  var fixed = %s, extra = %s;
  SourceEngine.__fixed = fixed;
  SourceEngine.__extra = extra;
  function boot(){
    for (var k in __SOURCE_CODE) SourceEngine.register(k, __SOURCE_CODE[k]);
  }
  SourceEngine.boot = boot;
})();
""" % (json.dumps([k for k, _ in FIXED]), json.dumps([k for k, _ in EXTRA])))

bundle = "".join(parts)
open(OUT, "w", encoding="utf-8").write(bundle)
print("bundle.js:", len(bundle), "字符 /", round(len(bundle.encode("utf-8")) / 1024, 1), "KB")

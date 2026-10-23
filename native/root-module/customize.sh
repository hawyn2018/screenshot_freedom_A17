#!/system/bin/sh
ui_print "自由截图A17 3.4.2 · 系统截图与录屏组件"
ui_print "截图与录屏按采集工具仲裁；受限采集会话优先保留图层排除。"
ui_print "系统桥完成身份与会话登记后，原生组件才按配置启用。"
ui_print "按实际存在的过滤代码匹配，适配入口与机型、系统版本分离。"
set_perm_recursive "$MODPATH" 0 0 0755 0644
set_perm "$MODPATH/bin/sf17fix" 0 0 0755
set_perm "$MODPATH/service.sh" 0 0 0755
set_perm "$MODPATH/uninstall.sh" 0 0 0755

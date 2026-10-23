#!/system/bin/sh
MODDIR=${0%/*}
# Prevent a concurrent boot/service invocation from enabling either patch.
touch "$MODDIR/remove"
# --stop stops the supervisor and its identified broker, then restores both
# owned pipelines. An uncertain stop or restoration remains a visible failure.
if [ -r "$MODDIR/service.sh" ]; then
    /system/bin/sh "$MODDIR/service.sh" --stop
else
    exit 1
fi

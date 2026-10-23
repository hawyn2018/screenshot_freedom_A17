#!/system/bin/sh
# Hawyn; Codex. Supervisor for the capture-policy broker.
MODDIR=${0%/*}
BIN="$MODDIR/bin/sf17fix"
APPFILES=/data/user_de/0/net.hawyn.screenshotfreedoma17/files
PIDFILE="$MODDIR/daemon.pid"
BROKERFILE="$MODDIR/broker.pid"
SYSTEM_UIDS="$MODDIR/policy-system-uids.conf"
OWNDIR=/data/adb/enable_screenshot17fix
child_pid=
child_start=
umask 077

service_alive() {
    case "$1" in ''|*[!0-9]*) return 1 ;; esac
    [ -r "/proc/$1/cmdline" ] &&
        tr '\000' ' ' < "/proc/$1/cmdline" | grep -Fq "$MODDIR/service.sh"
}

valid_status() {
    case "$1" in \{*\}) ;; *) return 1 ;; esac
    printf '%s\n' "$1" | grep -q '"schema"[[:space:]]*:[[:space:]]*1[[:space:]]*[,}]' &&
        printf '%s\n' "$1" | grep -Eq '"active_known"[[:space:]]*:[[:space:]]*(true|false)' &&
        printf '%s\n' "$1" | grep -Eq '"ok"[[:space:]]*:[[:space:]]*(true|false)'
}

inactive_status() {
    printf '%s\n' "$1" | grep -q '"ok"[[:space:]]*:[[:space:]]*true' &&
        printf '%s\n' "$1" | grep -q '"active_known"[[:space:]]*:[[:space:]]*true' &&
        printf '%s\n' "$1" | grep -q '"active"[[:space:]]*:[[:space:]]*false'
}

pipeline_without_matcher() {
    # A pipeline which this executable cannot manage owns nothing to restore.
    # Keep the original unsupported/unknown observation; never invent inactivity.
    ownership_exists "$1" && return 1
    recording_reason=$(printf '%s\n' "$2" | sed -n 's/.*"reason"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p')
    printf '%s\n' "$recording_reason" | grep -Eq '^(signature_not_found|signature_ambiguous|unsupported_[a-z0-9_]+)$' || return 1
    printf '%s\n' "$2" | grep -q '"supported"[[:space:]]*:[[:space:]]*false'
}

ownership_exists() {
    case "$1" in
        screenshot) ownership_file="$OWNDIR/native-patch.state" ;;
        recording) ownership_file="$OWNDIR/native-recording-patch.state" ;;
        *) return 1 ;;
    esac
    [ -e "$ownership_file" ] || [ -L "$ownership_file" ]
}

unavailable_status() {
    unavailable_owned=false
    ownership_exists "$1" && unavailable_owned=true
    result='{"schema":1,"pipeline":"'"$1"'","ok":false,"supported":false,"active":false,"active_known":false,"owned":'"$unavailable_owned"',"reason":"'"$2"'","detail":"Native status is unavailable; active state is unconfirmed","pid":0,"build_id":"","site_offset":"","errno":0}'
}

run_native() {
    result=$("$BIN" "$2" 2>>"$MODDIR/daemon.log")
    native_code=$?
    if valid_status "$result"; then
        # A 2.0 executable has schema 1 but no pipeline field.
        if ! printf '%s\n' "$result" | grep -q '"pipeline"[[:space:]]*:'; then
            result='{"pipeline":"'"$1"'",'"${result#?}"
        fi
    else
        unavailable_status "$1" native_status_unavailable
        native_code=20
    fi
}

publish() {
    case "$1" in
        screenshot) module_status=status.json; app_status=sf17fix-status.json ;;
        recording) module_status=recording-status.json; app_status=sf17fix-recording-status.json ;;
        policy) module_status=policy-status.json; app_status=sf17fix-policy-status.json ;;
        *) return 1 ;;
    esac
    printf '%s\n' "$2" > "$MODDIR/$module_status.new" &&
        mv -f "$MODDIR/$module_status.new" "$MODDIR/$module_status"
    if [ -d "$APPFILES" ] && [ ! -L "$APPFILES" ]; then
        app_owner=$(stat -c '%u:%g' "$APPFILES") || return 1
        app_label=$(ls -Zd "$APPFILES" | awk '{print $1}')
        app_tmp="$APPFILES/$app_status.new.$$"
        # App files are app-owned. Create without following a pre-existing link,
        # and change metadata through the held descriptor rather than a path
        # which the application could replace between these operations.
        rm -f "$app_tmp"
        (
            set -C
            exec 9> "$app_tmp" || exit 1
            printf '%s\n' "$2" >&9 || exit 1
            # Android mksh marks this descriptor close-on-exec. External
            # toybox commands must address the holding shell, not their own
            # /proc/self; $$ remains the parent PID inside a subshell.
            read -r publish_pid publish_comm publish_rest < /proc/self/stat || exit 1
            case "$publish_pid" in ''|*[!0-9]*) exit 1 ;; esac
            publish_fd="/proc/$publish_pid/fd/9"
            chmod 600 "$publish_fd" && chown "$app_owner" "$publish_fd" || exit 1
            case "$app_label" in u:object_r:*) chcon "$app_label" "$publish_fd" || exit 1 ;; esac
            mv -f "$app_tmp" "$APPFILES/$app_status"
        )
    fi
}

detect_recording() {
    recording_cli=1
    run_native recording record-probe
    if printf '%s\n' "$result" | grep -q '"reason"[[:space:]]*:[[:space:]]*"usage"'; then
        recording_cli=0
    fi
}

restore_pipeline() {
    restore_pipeline_name=$1
    restore_ok=0
    if [ "$1" = recording ] && [ "$recording_cli" = 0 ]; then
        unavailable_status recording recording_cli_unavailable
        publish recording "$result"
        # A legacy binary owns no recording patch. Retain a failure if an
        # ownership record from a newer binary remains to be restored.
        ownership_exists recording || restore_ok=1
        return
    fi
    case "$1" in screenshot) restore_command=disable ;; recording) restore_command=record-disable ;; esac
    restore_attempt=0
    while [ "$restore_attempt" -lt 3 ]; do
        run_native "$restore_pipeline_name" "$restore_command"
        publish "$restore_pipeline_name" "$result"
        if [ "$native_code" = 0 ] && inactive_status "$result" &&
                ! ownership_exists "$restore_pipeline_name"; then
            restore_ok=1
            return
        fi
        if pipeline_without_matcher "$restore_pipeline_name" "$result"; then
            restore_ok=1
            return
        fi
        restore_attempt=$((restore_attempt + 1))
        [ "$restore_attempt" -lt 3 ] && sleep 1
    done
}

restore_both() {
    restore_pipeline screenshot
    screenshot_restored=$restore_ok
    restore_pipeline recording
    recording_restored=$restore_ok
    [ "$screenshot_restored" = 1 ] && [ "$recording_restored" = 1 ]
}

# /proc names are the known service shell or sf17fix executable. A zombie
# has already released its native mutation lock and must be reaped, not signalled.
process_start() {
    case "$1" in ''|*[!0-9]*) return 1 ;; esac
    [ "$1" -gt 1 ] || return 1
    [ -r "/proc/$1/stat" ] || return 1
    read -r process_id process_comm process_state process_ppid process_pgrp process_session process_tty process_tpgid process_flags process_minflt process_cminflt process_majflt process_cmajflt process_utime process_stime process_cutime process_cstime process_priority process_nice process_threads process_itreal process_start_time process_rest < "/proc/$1/stat" || return 1
    [ "$process_state" != Z ] || return 1
    case "$process_start_time" in ''|*[!0-9]*) return 1 ;; esac
    printf '%s\n' "$process_start_time"
}

owned_child_alive() {
    [ -n "$child_pid" ] && [ -n "$child_start" ] &&
        [ "$(process_start "$child_pid")" = "$child_start" ]
}

broker_alive() {
    case "$1:$2" in *[!0-9:]*|:|*:|:*) return 1 ;; esac
    [ "$(process_start "$1")" = "$2" ] &&
        tr '\000' ' ' < "/proc/$1/cmdline" | grep -Fq -e "$BIN policy-serve $APPFILES $MODDIR" -e "$BIN direct-serve $APPFILES $MODDIR"
}

read_broker_identity() {
    broker_pid=
    broker_start=
    broker_extra=
    [ ! -L "$BROKERFILE" ] || return 1
    [ -e "$BROKERFILE" ] || return 0
    [ -f "$BROKERFILE" ] && [ -r "$BROKERFILE" ] || return 1
    read -r broker_pid broker_start broker_extra < "$BROKERFILE" || return 1
    case "$broker_pid:$broker_start" in *[!0-9:]*|:|*:|:*) return 1 ;; esac
    [ "$broker_pid" -gt 1 ] && [ "$broker_start" -gt 0 ] && [ -z "$broker_extra" ]
}

stop_broker() {
    # A directly launched child is known even before it writes broker.pid.
    if owned_child_alive; then
        stopped_broker=$child_pid
        stopped_start=$child_start
    else
        read_broker_identity || return 1
        stopped_broker=$broker_pid
        stopped_start=$broker_start
        if [ -z "$stopped_broker" ]; then return 0; fi
        if ! broker_alive "$stopped_broker" "$stopped_start"; then
            # Never signal a reused PID or an unrelated process.
            if [ "$(process_start "$stopped_broker")" = "$stopped_start" ]; then return 1; fi
            rm -f "$BROKERFILE"
            return 0
        fi
    fi
    kill -TERM "$stopped_broker" 2>/dev/null || {
        [ "$(process_start "$stopped_broker")" != "$stopped_start" ] || return 1
    }
    broker_stop_wait=0
    while [ "$(process_start "$stopped_broker")" = "$stopped_start" ] &&
            [ "$broker_stop_wait" -lt 12 ]; do
        sleep 1
        broker_stop_wait=$((broker_stop_wait + 1))
    done
    if [ "$(process_start "$stopped_broker")" = "$stopped_start" ]; then
        # SIGKILL during a multi-instruction write could leave a partial group.
        # Retain the failure marker instead of terminating an in-flight mutation.
        printf '%s\n' 'Broker stop deadline exceeded; no SIGKILL was sent' >> "$MODDIR/daemon.log"
        return 1
    fi
    [ "$stopped_broker" = "$child_pid" ] && wait "$child_pid" 2>/dev/null
    child_pid=
    child_start=
    # Only remove an identity file after the matching process is gone.
    if read_broker_identity && [ "$broker_pid" = "$stopped_broker" ] &&
            [ "$broker_start" = "$stopped_start" ]; then rm -f "$BROKERFILE"; fi
    return 0
}

publish_guardian() {
    guardian_state=$1
    for guardian_pipeline in screenshot recording; do
        case "$guardian_pipeline" in screenshot) guardian_command=status ;; recording) guardian_command=record-status ;; esac
        if [ "$guardian_pipeline" = recording ] && [ "$recording_cli" = 0 ]; then
            unavailable_status recording recording_cli_unavailable
        else
            run_native "$guardian_pipeline" "$guardian_command"
        fi
        # Observed active/active_known remain independent of supervisor health.
        # A guardian result is never mistaken for a fresh READY broker status.
        result=$(printf '%s\n' "$result" | sed 's/"ok"[[:space:]]*:[[:space:]]*true/"ok":false/')
        result="${result%?},\"policy_ready\":false,\"policy_paused\":true,\"blocked_count\":0,\"guardian_state\":\"$guardian_state\"}"
        publish "$guardian_pipeline" "$result"
    done
    # A stale READY file from a crashed broker is not a current inventory.
    publish policy '{"schema":1,"pipeline":"policy","running":false,"ok":false,"ready":false,"policy_ready":false,"paused":false,"policy_paused":false,"inventory_known":false,"original_verified":false,"reason":"'"$guardian_state"'"}'
}

# The application owns these private, single-value settings. A missing new
# arbitration setting preserves the previous enabled default during upgrade.
read_setting() {
    setting_path="$APPFILES/$1"
    setting_value=$2
    if [ ! -e "$setting_path" ] && [ ! -L "$setting_path" ]; then return 0; fi
    [ -f "$setting_path" ] && [ ! -L "$setting_path" ] || return 1
    setting_info=$(stat -c '%u:%a:%s' "$setting_path") || return 1
    setting_owner=${setting_info%%:*}
    setting_info=${setting_info#*:}
    setting_mode=${setting_info%%:*}
    setting_bytes=${setting_info#*:}
    settings_app_owner=$(stat -c '%u' "$APPFILES") || return 1
    case "$setting_mode:$setting_bytes" in *[!0-7:]*|:|*:|:*) return 1 ;; esac
    [ "$setting_owner" = "$settings_app_owner" ] && [ "$setting_owner" -gt 0 ] &&
        [ "$setting_bytes" -le 4 ] || return 1
    # Android mksh may interpret leading-zero arithmetic as decimal. Inspect
    # the group/other permission digits directly instead of parsing an octal.
    case "$setting_mode" in *00) ;; *) return 1 ;; esac
    setting_text=$(cat "$setting_path") || return 1
    case "$setting_text" in 0|1) setting_value=$setting_text ;; *) return 1 ;; esac
}

arbitration_enabled() {
    read_setting sf17fix-arbitration.conf 1 || return 0
    [ "$setting_value" = 1 ]
}

publish_not_running() {
    publish policy '{"schema":1,"pipeline":"policy","running":false,"ok":true,"ready":false,"policy_ready":false,"paused":false,"policy_paused":false,"inventory_known":false,"sessions":0,"blockers":0,"blocked_count":0,"allow_all_tools":true,"reason":"'"$1"'"}'
}

publish_waiting() {
    waiting_reason=$1
    for waiting_pipeline in screenshot recording; do
        publish "$waiting_pipeline" '{"schema":1,"pipeline":"'"$waiting_pipeline"'","ok":false,"active":false,"active_known":false,"owned":false,"supported":false,"matching":false,"phase":"config_wait","reason":"'"$waiting_reason"'"}'
    done
    publish_not_running "$waiting_reason"
}

generate_system_uids() {
    uid_source="$MODDIR/policy-system-uids.source.$$"
    uid_pending="$SYSTEM_UIDS.new.$$"
    # The native broker accepts only this root-owned, private regular file.
    # The list identifies trusted system hosts; capture ownership is still
    # validated separately in system_server, including the active tool matrix.
    # PackageManager shell commands write through Binder-passed descriptors.
    # Keep both descriptors as pipes: the system service may not write the
    # root module's file even though this root shell can create that file.
    if ! uid_listing=$(/system/bin/cmd package list packages -s -U 2>&1); then
        printf '%s\n' "$uid_listing" >> "$MODDIR/daemon.log"
        unset uid_listing
        rm -f "$uid_source" "$uid_pending"
        return 1
    fi
    if ! printf '%s\n' "$uid_listing" > "$uid_source"; then
        unset uid_listing
        rm -f "$uid_source" "$uid_pending"
        return 1
    fi
    unset uid_listing
    if ! awk '/^package:/ {for (i=1;i<=NF;i++) if ($i ~ /^uid:[0-9]+(,[0-9]+)*$/) {n=split(substr($i,5),uids,","); for (j=1;j<=n;j++) print uids[j]; found=1}} END {if (!found) exit 1}' \
            "$uid_source" > "$uid_pending"; then
        rm -f "$uid_source" "$uid_pending"
        return 1
    fi
    printf '%s\n' 1000 >> "$uid_pending"
    if ! sort -nu "$uid_pending" > "$uid_source"; then
        rm -f "$uid_source" "$uid_pending"
        return 1
    fi
    uid_count=$(wc -l < "$uid_source")
    uid_bytes=$(wc -c < "$uid_source")
    if [ "$uid_count" -gt 1024 ] || [ "$uid_bytes" -gt 8192 ]; then
        printf '%s\n' 'System-host UID inventory exceeds broker bounds; broker remains unready' >> "$MODDIR/daemon.log"
        rm -f "$uid_source" "$uid_pending"
        return 1
    fi
    chmod 600 "$uid_source" && chown 0:0 "$uid_source" &&
        mv -f "$uid_source" "$SYSTEM_UIDS"
    uid_code=$?
    rm -f "$uid_source" "$uid_pending"
    return "$uid_code"
}

cleanup() {
    trap - EXIT
    trap '' TERM INT
    cleanup_code=1
    if stop_broker; then
        detect_recording
        restore_both
        cleanup_code=$?
    else
        printf '%s\n' 'Broker is still present or its identity is uncertain; cleanup remains pending' >> "$MODDIR/daemon.log"
    fi
    if [ "$cleanup_code" = 0 ]; then
        if [ "$(cat "$PIDFILE" 2>/dev/null)" = "$$" ]; then rm -f "$PIDFILE"; fi
        if [ -f "$MODDIR/disable" ] || [ -f "$MODDIR/remove" ]; then
            publish_not_running module_disabled
        else
            publish_not_running service_stopped
        fi
    else
        detect_recording
        publish_guardian cleanup_pending
        # Keep daemon.pid to block an installer from replacing a still-owned
        # executable or interpreting an uncertain restoration as success.
        printf '%s\n' 'Cleanup incomplete; daemon.pid retained for installer verification' >> "$MODDIR/daemon.log"
    fi
    exit "$cleanup_code"
}

# Installation and uninstall both stop supervision before restoring ownership.
# A dead supervisor can leave a live broker; its PID/start identity is checked too.
if [ "$1" = --stop ]; then
    stopped_pid=$(cat "$PIDFILE" 2>/dev/null)
    if service_alive "$stopped_pid"; then
        [ "$stopped_pid" = "$$" ] && exit 1
        kill -TERM "$stopped_pid" || exit 1
        stop_wait=0
        while service_alive "$stopped_pid" && [ "$stop_wait" -lt 45 ]; do
            sleep 1
            stop_wait=$((stop_wait + 1))
        done
        service_alive "$stopped_pid" && exit 1
    fi
    stop_broker || exit 1
    detect_recording
    restore_both
    stop_code=$?
    if [ "$stop_code" = 0 ] && ! service_alive "$(cat "$PIDFILE" 2>/dev/null)"; then
        rm -f "$PIDFILE"
    fi
    exit "$stop_code"
fi

oldpid=$(cat "$PIDFILE" 2>/dev/null)
if [ "$oldpid" != "$$" ] && service_alive "$oldpid"; then exit 0; fi
printf '%s\n' "$$" > "$PIDFILE.new" && mv -f "$PIDFILE.new" "$PIDFILE" || exit 1
chmod 600 "$PIDFILE"
trap cleanup EXIT TERM INT

# Both modes retain a single native process and its validated in-memory ELF
# cache. Only policy-serve creates a socket or capture leases. Startup restoration
# belongs to the child while it holds native.lock; do not cold-scan both pipes
# repeatedly in the shell before launching the child.
recording_cli=1
while [ ! -f "$MODDIR/disable" ] && [ ! -f "$MODDIR/remove" ]; do
    if ! stop_broker; then
        detect_recording
        publish_guardian broker_identity_unknown
        exit 1
    fi
    if [ ! -d "$APPFILES" ] || [ -L "$APPFILES" ] ||
            [ ! -f "$APPFILES/sf17fix.conf" ] || [ ! -f "$APPFILES/sf17fix-recording.conf" ]; then
        # Existing ownership must still be recovered even when application
        # data/configuration was cleared. With no ownership there is no reason
        # to start repeated cold matchers while waiting for initialization.
        if ownership_exists screenshot || ownership_exists recording; then
            if ! restore_both; then publish_guardian configuration_restore_failed; exit 1; fi
        fi
        publish_waiting awaiting_configuration
        sleep 2
        continue
    fi
    child_mode=direct-serve
    if arbitration_enabled; then
        child_mode=policy-serve
        publish_waiting awaiting_system_host_inventory
        if ! generate_system_uids; then
            publish_waiting system_host_inventory_failed
            sleep 3
            continue
        fi
    fi
    "$BIN" "$child_mode" "$APPFILES" "$MODDIR" >> "$MODDIR/daemon.log" 2>&1 &
    child_pid=$!
    child_start=$(process_start "$child_pid")
    mode_changed=0
    while owned_child_alive && [ ! -f "$MODDIR/disable" ] && [ ! -f "$MODDIR/remove" ]; do
        current_mode=direct-serve
        arbitration_enabled && current_mode=policy-serve
        if [ "$current_mode" != "$child_mode" ]; then mode_changed=1; break; fi
        sleep 2
    done
    if [ -f "$MODDIR/disable" ] || [ -f "$MODDIR/remove" ]; then break; fi
    if [ "$mode_changed" = 1 ]; then
        # Normal TERM confirms original code before the next child takes over.
        if ! stop_broker; then publish_guardian mode_switch_stop_failed; exit 1; fi
        continue
    fi
    wait "$child_pid" 2>/dev/null
    broker_exit=$?
    child_pid=
    child_start=
    printf '%s\n' "Broker exited with code $broker_exit; restoring before a fresh unready restart" >> "$MODDIR/daemon.log"
    if ! stop_broker || ! restore_both; then
        publish_guardian broker_restore_failed
        exit 1
    fi
    publish_guardian broker_exited
    sleep 5
done

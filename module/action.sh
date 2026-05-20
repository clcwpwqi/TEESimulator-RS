#!/system/bin/sh
MODDIR=${0%/*}
CONFIG_DIR=/data/adb/tricky_store

. "$MODDIR/action_i18n.sh"

echo "  ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo "  ⚠️  $(_msg confirm_header)"
echo "  ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo " "
echo "  $(_msg confirm_warning_1)"
echo "  $(_msg confirm_warning_2)"
echo " "
echo "  🔊  $(_msg confirm_vol_up)"
echo "  🔉  $(_msg confirm_vol_down)"
echo " "

confirm() {
    vol_tmp="${TMPDIR:-/data/local/tmp}/teesim_vol_key"
    : > "$vol_tmp"

    # Stream getevent and match VOLUME DOWN inline. Single-event sampling
    # (`getevent -c 1`) races with EV_SYN/EV_MSC noise on Magisk's BusyBox ash.
    /system/bin/timeout 10 /system/bin/sh -c '
        /system/bin/getevent -lq 2>/dev/null | while IFS= read -r line; do
            case "$line" in
                *KEY_VOLUMEUP*DOWN*)   echo UP   > "$1"; exit 0 ;;
                *KEY_VOLUMEDOWN*DOWN*) echo DOWN > "$1"; exit 0 ;;
            esac
        done
    ' _ "$vol_tmp"

    key=$(cat "$vol_tmp" 2>/dev/null)
    rm -f "$vol_tmp"
    [ "$key" = "UP" ] && return 0
    return 1
}

if ! confirm; then
    echo " "
    echo "  ❌ $(_msg confirm_cancelled)"
    exit 0
fi

if [ -d "$CONFIG_DIR/persistent_keys" ]; then
    rm -rf "$CONFIG_DIR/persistent_keys"
    mkdir -p "$CONFIG_DIR/persistent_keys"
    echo " "
    echo "  ✅ $(_msg confirm_cleared)"
else
    echo " "
    echo "  ℹ️  $(_msg confirm_not_found)"
fi

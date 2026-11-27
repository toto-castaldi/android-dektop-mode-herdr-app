# hda <azione> ['<argomenti JSON>']: comando di un agente all'app Herdr Desktop for Android
hda() {
  local hc id args want re i
  hc=$(command -v hc || echo "$HOME/.local/bin/hc")
  id="$HC_TERMINAL_ID-$(date +%s)-$$-$RANDOM"
  args=${2:-'{}'}
  want="\"herdr-desktop-android.re\":\"{\\\"id\\\":\\\"$id\\\""
  "$hc" label "$HC_TERMINAL_ID" "herdr-desktop-android.do={\"id\":\"$id\",\"action\":\"$1\",\"args\":$args}" >/dev/null || return 1
  if command -v timeout >/dev/null; then
    grep -m1 -q -F "$want" <(timeout 10 "$hc" events --terminal "$HC_TERMINAL_ID" --json)
  else # senza timeout (per esempio macOS): controlla ogni 0,2 s per 10 s
    for i in $(seq 50); do
      "$hc" get "$HC_TERMINAL_ID" --json | grep -q -F "$want" && break
      sleep 0.2
    done
  fi
  re=$("$hc" get "$HC_TERMINAL_ID" --json |
    sed -nE 's/.*"herdr-desktop-android\.re":"((\\.|[^"\\])*)".*/\1/p' | sed -e 's/\\"/"/g' -e 's/\\\\/\\/g')
  case $re in
    "{\"id\":\"$id\",\"ok\":true"*) echo "$re" ;;
    "{\"id\":\"$id\""*) echo "$re" >&2; return 1 ;;
    *) "$hc" label "$HC_TERMINAL_ID" "herdr-desktop-android.do=" >/dev/null
       echo '{"ok":false,"error":"timeout","message":"nessuna risposta dall'"'"'app"}' >&2; return 1 ;;
  esac
}

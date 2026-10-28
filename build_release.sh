#!/usr/bin/env bash
# Compila "Travesías en Ecuador" y genera APK + AAB de release firmados (Linux / macOS).
# Uso: ./build_release.sh [--debug] [--skip-clean] [--install]
set -euo pipefail
cd "$(dirname "$0")"

DEBUG=0; SKIP_CLEAN=0; INSTALL=0
for a in "$@"; do
  case "$a" in
    --debug) DEBUG=1 ;;
    --skip-clean) SKIP_CLEAN=1 ;;
    --install) INSTALL=1 ;;
  esac
done

step() { printf '\n\033[36m==> %s\033[0m\n' "$1"; }
ok()   { printf '    \033[32mOK  %s\033[0m\n' "$1"; }
warn() { printf '    \033[33m!!  %s\033[0m\n' "$1"; }
fail() { printf '\n\033[31mERROR: %s\033[0m\n' "$1"; exit 1; }

step "Verificando Java (JDK 17+)"
if [ -z "${JAVA_HOME:-}" ]; then
  for c in "/Applications/Android Studio.app/Contents/jbr/Contents/Home" "$HOME/android-studio/jbr" "/opt/android-studio/jbr" "/usr/lib/jvm/java-17-openjdk-amd64" "/usr/lib/jvm/java-21-openjdk-amd64"; do
    if [ -x "$c/bin/java" ]; then export JAVA_HOME="$c"; break; fi
  done
fi
[ -n "${JAVA_HOME:-}" ] || command -v java >/dev/null || fail "No se encontró Java. Instala Android Studio o un JDK 17 y define JAVA_HOME."
[ -n "${JAVA_HOME:-}" ] && export PATH="$JAVA_HOME/bin:$PATH"
ok "$(java -version 2>&1 | head -1)"

step "Verificando Android SDK"
SDK_DIR=""
if [ -f local.properties ]; then SDK_DIR="$(grep -E '^sdk\.dir=' local.properties | cut -d= -f2- || true)"; fi
if [ -z "$SDK_DIR" ] || [ ! -d "$SDK_DIR" ]; then
  for c in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}" "$HOME/Android/Sdk" "$HOME/Library/Android/sdk"; do
    if [ -n "$c" ] && [ -d "$c" ]; then SDK_DIR="$c"; break; fi
  done
  [ -n "$SDK_DIR" ] || fail "No se encontró el Android SDK. Define ANDROID_HOME."
  echo "sdk.dir=$SDK_DIR" > local.properties
  ok "local.properties creado (sdk.dir=$SDK_DIR)"
else ok "SDK: $SDK_DIR"; fi

step "Verificando claves de API (.env)"
if [ ! -f .env ]; then cp .env.example .env; warn ".env creado desde .env.example: pega tu GEMINI_API_KEY para activar la IA."; fi
grep -Eq '^\s*GEMINI_API_KEY\s*=\s*\S+' .env && ok "GEMINI_API_KEY presente" || warn "GEMINI_API_KEY vacía: solo modo offline."
[ -f app/google-services.json ] || warn "app/google-services.json no encontrado: Comunidad desactivada (ver README)."

step "Clave de firma para Google Play"
KS=travesias-upload-key.jks
if [ ! -f keystore.properties ] || [ ! -f "$KS" ]; then
  read -r -p "    Contraseña del keystore (ENTER = generar aleatoria): " PWD1
  if [ -z "$PWD1" ]; then PWD1="$(LC_ALL=C tr -dc 'A-Za-z0-9' </dev/urandom | head -c 20)"; echo "    Contraseña generada: $PWD1"; fi
  keytool -genkeypair -v -keystore "$KS" -alias upload -keyalg RSA -keysize 2048 -validity 10000 \
    -storepass "$PWD1" -keypass "$PWD1" \
    -dname "CN=Travesias en Ecuador, OU=Mobile, O=Travesias, L=Santo Domingo, ST=Santo Domingo de los Tsachilas, C=EC" >/dev/null
  printf 'storeFile=%s\nstorePassword=%s\nkeyAlias=upload\nkeyPassword=%s\n' "$KS" "$PWD1" "$PWD1" > keystore.properties
  ok "Keystore creado: $KS (RESPÁLDALO junto con keystore.properties)"
else ok "Usando keystore existente: $KS"; fi

step "Compilando con Gradle"
TASKS=()
[ "$SKIP_CLEAN" = 1 ] || TASKS+=(clean)
TASKS+=(bundleRelease assembleRelease)
[ "$DEBUG" = 1 ] && TASKS+=(assembleDebug)
chmod +x gradlew
./gradlew "${TASKS[@]}" --no-daemon --console=plain

step "Copiando resultados a release/"
VN="$(grep -oE 'versionName = "[^"]+"' app/build.gradle.kts | cut -d'"' -f2)"
VC="$(grep -oE 'versionCode = [0-9]+' app/build.gradle.kts | grep -oE '[0-9]+')"
STAMP="$(date +%Y%m%d-%H%M)"
mkdir -p release
AAB="$(ls app/build/outputs/bundle/release/*.aab | head -1)"
APK="$(ls app/build/outputs/apk/release/*.apk | head -1)"
AAB_OUT="release/TravesiasEnEcuador-v$VN-$VC-$STAMP.aab"
APK_OUT="release/TravesiasEnEcuador-v$VN-$VC-$STAMP.apk"
cp "$AAB" "$AAB_OUT"; cp "$APK" "$APK_OUT"
[ "$DEBUG" = 1 ] && cp "$(ls app/build/outputs/apk/debug/*.apk | head -1)" "release/TravesiasEnEcuador-v$VN-debug-$STAMP.apk"

APKSIGNER="$(ls -d "$SDK_DIR"/build-tools/*/apksigner 2>/dev/null | sort -V | tail -1 || true)"
if [ -n "$APKSIGNER" ]; then
  SIG="$("$APKSIGNER" verify --print-certs "$APK_OUT" 2>&1 | grep -E 'Signer #1 certificate DN' || true)"
  ok "Firma: $SIG"
  echo "$SIG" | grep -q "Android Debug" && warn "APK firmado con clave DEBUG: Play Store no lo aceptará."
fi

echo
echo "================= LISTO ================="
echo " AAB para Google Play : $AAB_OUT"
echo " APK para instalar    : $APK_OUT"
echo " SHA-256 APK          : $(shasum -a 256 "$APK_OUT" 2>/dev/null | cut -d' ' -f1 || sha256sum "$APK_OUT" | cut -d' ' -f1)"
echo " Versión              : $VN ($VC)"
echo "========================================="

if [ "$INSTALL" = 1 ]; then "$SDK_DIR/platform-tools/adb" install -r "$APK_OUT"; fi

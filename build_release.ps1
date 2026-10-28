<#
.SYNOPSIS
  Compila "Travesías en Ecuador" y genera el APK y el AAB de release firmados, listos para Google Play.

.DESCRIPTION
  1. Detecta Java (JDK 17+) y el Android SDK (o usa los de Android Studio).
  2. Verifica el archivo .env con las claves de API.
  3. Crea (si no existe) la clave de firma de subida (upload key) y keystore.properties.
  4. Ejecuta Gradle: bundleRelease (AAB para Play) + assembleRelease (APK con la clave de Play)
     + assemblePruebas (mismo APK pero firmado con la clave debug, para instalar encima de lo ya
     instalado sin desinstalar ni perder datos).
  5. Copia los resultados a la carpeta release\ con nombre versionado y verifica la firma.

.EJEMPLOS
  .\build_release.ps1                 # AAB + APK de release
  .\build_release.ps1 -Debug          # además genera el APK debug para pruebas rápidas
  .\build_release.ps1 -SkipClean      # más rápido (no limpia la compilación anterior)
  .\build_release.ps1 -Install        # instala por USB (adb) el APK de PRUEBAS, que entra encima sin desinstalar
  .\build_release.ps1 -Install -Play  # instala el APK de release (exige desinstalar antes: otra clave de firma)
  .\build_release.ps1 -Reinstall      # desinstala y luego instala
#>
param(
    [switch]$Debug,
    [switch]$SkipClean,
    [switch]$Install,
    [switch]$Reinstall,
    [switch]$Play,          # instalar el APK de release en vez del de pruebas
    [switch]$NoPause      # no esperar ENTER al terminar (util para tareas automaticas)
)

$ErrorActionPreference = "Stop"
# Identificador del paquete: lo usa la instalacion por adb (desinstalar / reinstalar).
$appId = "ec.travesias.app"
$Root = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $Root

# Todo lo que se imprima en pantalla queda tambien en build_logs\last_build.log,
# desde la primera linea, para poder revisar cualquier fallo despues.
$logDir  = Join-Path $Root "build_logs"
New-Item -ItemType Directory -Force -Path $logDir | Out-Null
$logFile = Join-Path $logDir "last_build.log"
try { Start-Transcript -Path $logFile -Force | Out-Null } catch { }

function Stop-Log { try { Stop-Transcript | Out-Null } catch { } }

# Evita que la ventana se cierre sola sin poder leer el resultado.
function Wait-Close {
    if ($NoPause) { return }
    Write-Host ""
    Write-Host "Pulsa ENTER para cerrar esta ventana..." -ForegroundColor DarkGray
    try { [void](Read-Host) } catch { }
}

function Write-Step($msg) { Write-Host "`n==> $msg" -ForegroundColor Cyan }
function Write-Ok($msg)   { Write-Host "    OK  $msg" -ForegroundColor Green }
function Write-Warn2($msg){ Write-Host "    !!  $msg" -ForegroundColor Yellow }
function Fail($msg)       { Write-Host "`nERROR: $msg" -ForegroundColor Red; Write-Host "Log completo: $logFile" -ForegroundColor DarkGray; Stop-Log; Wait-Close; exit 1 }

# Ejecuta un programa externo capturando TODA su salida (incluida la de error) como texto.
# Sin esto, con $ErrorActionPreference="Stop" cualquier programa que escriba en stderr
# (java -version, apksigner, gradle...) aborta el script con un NativeCommandError enganoso.
function Invoke-Native {
    param([Parameter(Mandatory = $true)][scriptblock]$Block, [switch]$Show)
    $prev = $ErrorActionPreference
    # TIENE QUE SER "Continue". Con "SilentlyContinue", PowerShell 5.1 se TRAGA lo que los
    # programas mandan por stderr en vez de dejarlo pasar por la tuberia, y `java -version`
    # escribe TODA su salida por ahi: el script se quedaba sin poder leer la version y
    # cortaba con "No se encontro un JDK" teniendo el JDK delante. Ya paso una vez; no
    # volver a cambiarlo. El ruido rojo de Gradle en el log es feo pero es inofensivo, y
    # el resumen de errores ya no depende de el.
    $ErrorActionPreference = "Continue"
    try {
        & $Block 2>&1 | ForEach-Object {
            $texto = "$_"
            if ($Show) { Write-Host $texto }
            $texto
        }
    }
    finally { $ErrorActionPreference = $prev }
}

# Devuelve la version mayor del JDK que hay en una carpeta (0 si no hay ninguno valido).
function Get-JavaMajor {
    param([string]$JavaHome)
    if (-not $JavaHome) { return 0 }
    $exe = Join-Path $JavaHome "bin\java.exe"
    if (-not (Test-Path $exe)) { return 0 }
    $out = (Invoke-Native { & $exe -version }) -join " "
    if ($out -match 'version "(\d+)') { return [int]$Matches[1] }
    return 0
}

# ---------------------------------------------------------------- 1. Java
Write-Step "Verificando Java (JDK 17 o superior; se prefiere el mas nuevo)"
$jdkBest = $null
$jdkBestVer = 0
$jdkCandidates = @(
    "$env:ProgramFiles\Android\Android Studio\jbr",
    "$env:ProgramFiles\Android\Android Studio Preview\jbr",
    "$env:LOCALAPPDATA\Programs\Android Studio\jbr",
    "$env:LOCALAPPDATA\Programs\Android Studio Preview\jbr",
    $env:JAVA_HOME,
    $env:JDK_HOME
)
foreach ($pat in @("$env:ProgramFiles\Eclipse Adoptium\jdk-2*", "$env:ProgramFiles\Java\jdk-2*",
                   "$env:ProgramFiles\Eclipse Adoptium\jdk-17*", "$env:ProgramFiles\Java\jdk-17*")) {
    # foreach (no ForEach-Object) para que el += afecte a la variable de fuera del bucle
    foreach ($f in @(Get-Item $pat -ErrorAction SilentlyContinue)) {
        if ($f) { $jdkCandidates += $f.FullName }
    }
}
foreach ($c in $jdkCandidates) {
    if ($c -and (Test-Path $c -ErrorAction SilentlyContinue)) {
        $v = Get-JavaMajor $c
        if ($v -gt $jdkBestVer) { $jdkBestVer = $v; $jdkBest = $c }
    }
}
if (-not $jdkBest) { Fail "No se encontro un JDK. Instala Android Studio (incluye su propio JDK) o define JAVA_HOME." }
if ($jdkBestVer -lt 17) { Fail "El JDK encontrado es la version $jdkBestVer y se necesita 17 o superior. Instala Android Studio o un JDK 21." }
$env:JAVA_HOME = $jdkBest
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
Write-Ok "JAVA_HOME = $env:JAVA_HOME  (JDK $jdkBestVer)"

# ---------------------------------------------------------------- 2. Android SDK
Write-Step "Verificando Android SDK"
$localProps = Join-Path $Root "local.properties"
$sdkDir = $null
if (Test-Path $localProps) {
    $line = Get-Content $localProps | Where-Object { $_ -match '^sdk\.dir=' } | Select-Object -First 1
    if ($line) { $sdkDir = ($line -replace '^sdk\.dir=', '') -replace '\\\\', '\' -replace '\\:', ':' }
}
if (-not $sdkDir -or -not (Test-Path $sdkDir)) {
    foreach ($c in @($env:ANDROID_HOME, $env:ANDROID_SDK_ROOT, "$env:LOCALAPPDATA\Android\Sdk")) {
        if ($c -and (Test-Path $c)) { $sdkDir = $c; break }
    }
    if (-not $sdkDir) { Fail "No se encontró el Android SDK. Abre Android Studio una vez para instalarlo o define ANDROID_HOME." }
    $escaped = $sdkDir -replace '\\', '\\' -replace ':', '\:'
    "sdk.dir=$escaped" | Set-Content $localProps -Encoding ASCII
    Write-Ok "local.properties creado con sdk.dir=$sdkDir"
} else { Write-Ok "SDK: $sdkDir" }

# ---------------------------------------------------------------- 3. Claves .env
Write-Step "Verificando claves de API (.env)"
$envFile = Join-Path $Root ".env"
if (-not (Test-Path $envFile)) {
    Copy-Item (Join-Path $Root ".env.example") $envFile
    Write-Warn2 ".env no existía: se creó desde .env.example. Edítalo y pega tu GEMINI_API_KEY para activar la IA."
} else {
    $gem = Get-Content $envFile | Where-Object { $_ -match '^\s*GEMINI_API_KEY\s*=\s*\S+' }
    if ($gem) { Write-Ok "GEMINI_API_KEY presente" } else { Write-Warn2 "GEMINI_API_KEY vacía: la app funcionará solo con datos offline." }
}
$gsvc = Join-Path $Root "app\google-services.json"
$gsvcBackup = Join-Path $Root ".gradle\google-services.json"
if (-not (Test-Path $gsvc)) {
    if (Test-Path $gsvcBackup) {
        Copy-Item $gsvcBackup $gsvc -Force
        Write-Warn2 "app\google-services.json faltaba y se restauro desde la copia de .gradle\. Revisa que no se borre de nuevo."
    } else {
        Write-Warn2 "app\google-services.json NO existe: la pestaña Comunidad quedara desactivada. Descargalo de la consola de Firebase."
    }
} else {
    Write-Ok "google-services.json presente (Comunidad activa)"
    # Copia de seguridad: este archivo esta en .gitignore y se ha perdido antes.
    New-Item -ItemType Directory -Force -Path (Split-Path $gsvcBackup) | Out-Null
    Copy-Item $gsvc $gsvcBackup -Force
}

# ---------------------------------------------------------------- 4. Keystore de release
Write-Step "Clave de firma para Google Play (upload key)"
$ksProps = Join-Path $Root "keystore.properties"
$ksFile  = Join-Path $Root "travesias-upload-key.jks"
if (-not (Test-Path $ksProps) -or -not (Test-Path $ksFile)) {
    Write-Host "    No existe una clave de firma. Se creará una nueva (guárdala y respáldala: sin ella no podrás actualizar la app en Play)." -ForegroundColor Yellow
    $pwd1 = Read-Host "    Contraseña para el keystore (mínimo 6 caracteres, ENTER = generar una aleatoria)"
    if ([string]::IsNullOrWhiteSpace($pwd1)) {
        $pwd1 = -join ((48..57 + 65..90 + 97..122) | Get-Random -Count 20 | ForEach-Object { [char]$_ })
        Write-Host "    Contraseña generada: $pwd1" -ForegroundColor Yellow
    }
    $alias = "upload"
    $dname = "CN=Travesias en Ecuador, OU=Mobile, O=Travesias, L=Santo Domingo, ST=Santo Domingo de los Tsachilas, C=EC"
    Invoke-Native { & "$env:JAVA_HOME\bin\keytool.exe" -genkeypair -v -keystore $ksFile -alias $alias -keyalg RSA -keysize 2048 -validity 10000 -storepass $pwd1 -keypass $pwd1 -dname $dname } | Out-Null
    if ($LASTEXITCODE -ne 0) { Fail "keytool falló al crear el keystore." }
    @(
        "storeFile=travesias-upload-key.jks",
        "storePassword=$pwd1",
        "keyAlias=$alias",
        "keyPassword=$pwd1"
    ) | Set-Content $ksProps -Encoding ASCII
    Write-Ok "Keystore creado: $ksFile"
    Write-Ok "Credenciales guardadas en keystore.properties (está en .gitignore)"
    Write-Warn2 "RESPALDA travesias-upload-key.jks y keystore.properties en un lugar seguro."
} else { Write-Ok "Usando keystore existente: $ksFile" }

# ---------------------------------------------------------------- 5. Compilar
Write-Step "Compilando con Gradle (puede tardar varios minutos la primera vez)"
$tasks = @()
if (-not $SkipClean) { $tasks += "clean" }
$tasks += "bundleRelease", "assembleRelease", "assemblePruebas"
if ($Debug) { $tasks += "assembleDebug" }
Write-Host " Log completo: $logFile" -ForegroundColor DarkGray
# Invoke-Native: mantiene la salida en vivo, la guarda en el log y evita que los
# avisos de Gradle (que salen por stderr) aborten el script.
# Se guarda la salida TAL CUAL, sin pasar por la transcripcion. La transcripcion corta las
# lineas al ancho de la consola, y por eso los errores de Kotlin salian partidos en dos
# ("...CommunityPhoto." y en la linea siguiente "kt:124: ...") y no se podian ni leer ni buscar.
$salidaGradle = @(Invoke-Native { & .\gradlew.bat @tasks --no-daemon --console=plain } -Show)
$gradleExit = $LASTEXITCODE
$logCrudo = Join-Path $logDir "gradle_raw.log"
try { $salidaGradle | Set-Content -Path $logCrudo -Encoding UTF8 } catch { }

if ($gradleExit -ne 0) {
    Write-Host ""
    Write-Host "----- ERRORES ENCONTRADOS (resumen) -----" -ForegroundColor Red
    # Del array en memoria: lineas enteras, sin cortar.
    $resumen = @($salidaGradle |
        Where-Object { $_ -match "^\s*e: |error:|FAILURE: |Execution failed|Caused by:" } |
        Select-Object -Unique | Select-Object -First 30)
    if ($resumen.Count -eq 0) {
        # Por si acaso: el mismo filtro sobre la transcripcion.
        $resumen = @(Get-Content $logFile -ErrorAction SilentlyContinue |
            Where-Object { $_ -match "^\s*e: |error:|FAILURE: |Execution failed|Caused by:" } |
            Select-Object -Unique | Select-Object -First 30)
    }
    if ($resumen.Count -gt 0) { $resumen | ForEach-Object { Write-Host $_ -ForegroundColor Red } }
    else { Write-Host "(sin lineas de error reconocibles; revisa el log completo)" -ForegroundColor Red }
    Write-Host "-----------------------------------------" -ForegroundColor Red
    Write-Host " Salida de Gradle sin cortar: $logCrudo" -ForegroundColor DarkGray
    Fail "La compilacion fallo. El log completo esta en $logFile"
}

# ---------------------------------------------------------------- 6. Resultados
Write-Step "Copiando resultados a release\"
$versionName = (Select-String -Path "app\build.gradle.kts" -Pattern 'versionName = "([^"]+)"').Matches[0].Groups[1].Value
$versionCode = (Select-String -Path "app\build.gradle.kts" -Pattern 'versionCode = (\d+)').Matches[0].Groups[1].Value
$stamp = Get-Date -Format "yyyyMMdd-HHmm"
$outDir = Join-Path $Root "release"
New-Item -ItemType Directory -Force -Path $outDir | Out-Null

$aab = Get-ChildItem "app\build\outputs\bundle\release\*.aab" -ErrorAction SilentlyContinue | Select-Object -First 1
$apk = Get-ChildItem "app\build\outputs\apk\release\*.apk" -ErrorAction SilentlyContinue | Select-Object -First 1
if (-not $apk) { Fail "Gradle termino sin errores pero no se encontro el APK en app\build\outputs\apk\release\. Revisa $logFile" }
if (-not $aab) { Fail "Gradle termino sin errores pero no se encontro el AAB en app\build\outputs\bundle\release\. Revisa $logFile" }
$aabOut = Join-Path $outDir "TravesiasEnEcuador-v$versionName-$versionCode-$stamp.aab"
$apkOut = Join-Path $outDir "TravesiasEnEcuador-v$versionName-$versionCode-$stamp.apk"
Copy-Item $aab.FullName $aabOut -Force
Copy-Item $apk.FullName $apkOut -Force
# APK de PRUEBAS: firmado con la clave debug, entra encima de lo ya instalado.
$pru = Get-ChildItem "app\build\outputs\apk\pruebas\*.apk" -ErrorAction SilentlyContinue | Select-Object -First 1
$pruOut = $null
if ($pru) {
    $pruOut = Join-Path $outDir "TravesiasEnEcuador-v$versionName-$versionCode-PRUEBAS-$stamp.apk"
    Copy-Item $pru.FullName $pruOut -Force
} else {
    Write-Warn2 "No se genero el APK de pruebas (app\build\outputs\apk\pruebas\). Revisa $logFile"
}
if ($Debug) {
    $dbg = Get-ChildItem "app\build\outputs\apk\debug\*.apk" -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($dbg) { Copy-Item $dbg.FullName (Join-Path $outDir "TravesiasEnEcuador-v$versionName-debug-$stamp.apk") -Force }
}

# Verificación de firma con apksigner (si está disponible)
$apksigner = Get-ChildItem "$sdkDir\build-tools\*\apksigner.bat" -ErrorAction SilentlyContinue | Sort-Object FullName -Descending | Select-Object -First 1
if ($apksigner) {
    $sig = Invoke-Native { & $apksigner.FullName verify --print-certs $apkOut } | Select-String "Signer #1 certificate DN|WARNING" | Select-Object -First 2
    Write-Ok "Firma verificada: $($sig -join ' | ')"
    if ("$sig" -match "Android Debug") { Write-Warn2 "El APK de release está firmado con la clave DEBUG (no se encontró keystore.properties). Play Store NO lo aceptará." }
    if ($pruOut) {
        $sig2 = Invoke-Native { & $apksigner.FullName verify --print-certs $pruOut } | Select-String "Signer #1 certificate DN" | Select-Object -First 1
        Write-Ok "Firma del APK de PRUEBAS: $sig2"
        if ("$sig2" -notmatch "Android Debug") { Write-Warn2 "El APK de PRUEBAS deberia ir con la clave DEBUG y no es asi; no entrara encima de lo instalado." }
    }
}

$hash = (Get-FileHash $apkOut -Algorithm SHA256).Hash
Write-Host ""
Write-Host "================= LISTO =================" -ForegroundColor Green
Write-Host ""
Write-Host " 1) PARA PROBAR en tu celular o en LDPlayer:" -ForegroundColor Cyan
if ($pruOut) {
    Write-Host "    $pruOut"
    Write-Host "    Este entra ENCIMA de la app que ya tienes: no hay que desinstalar y no se pierde nada."
} else {
    Write-Host "    (no se genero; revisa el log)"
}
Write-Host ""
Write-Host " 2) PARA SUBIR A GOOGLE PLAY:" -ForegroundColor Cyan
Write-Host "    $aabOut"
Write-Host ""
Write-Host " 3) APK de release (misma clave que Play). Solo instala si antes DESINSTALAS la app:" -ForegroundColor DarkGray
Write-Host "    $apkOut"
Write-Host ""
Write-Host " Version : $versionName ($versionCode)     APK: $([math]::Round($apk.Length / 1MB, 1)) MB     SHA-256: $hash"
Write-Host "=========================================" -ForegroundColor Green
Write-Host " Sube el .aab en Play Console -> Produccion / Pruebas internas -> Crear nueva version."
Write-Host " Para la siguiente version aumenta versionCode en app\build.gradle.kts."
Write-Host ""
Write-Warn2 'Por que hay dos APK: Android solo deja ACTUALIZAR si la firma coincide con la instalada.'
Write-Warn2 'Todo lo anterior al 2026-09-09 salio firmado con la clave DEBUG (habia un fallo que no'
Write-Warn2 'encontraba el keystore de verdad). El de PRUEBAS lleva esa misma clave y entra encima;'
Write-Warn2 'el de release lleva ya la clave de subida de Play y por eso pide desinstalar primero.'

if ($Install -or $Reinstall) {
    $adb = Join-Path $sdkDir "platform-tools\adb.exe"
    if (-not (Test-Path $adb)) {
        Write-Warn2 "adb no encontrado en $adb"
    } else {
        Write-Step "Instalando en el dispositivo conectado"

        # Android NO deja actualizar una app por encima de otra firmada con distinta clave.
        # Como los APK de antes salian con la clave DEBUG y estos ya salen con la de subida,
        # el primer intento despues del cambio falla con INSTALL_FAILED_UPDATE_INCOMPATIBLE.
        if ($Reinstall) {
            Write-Warn2 "Desinstalando $appId (se borran los datos locales de la app en el dispositivo)."
            Invoke-Native { & $adb uninstall $appId } | Out-Null
        }

        # Por defecto se instala el de PRUEBAS: es el unico que entra encima sin desinstalar.
        $aInstalar = if ($pruOut -and -not $Play) { $pruOut } else { $apkOut }
        Write-Host "    Instalando: $aInstalar"
        $salida = Invoke-Native { & $adb install -r $aInstalar }
        Write-Host $salida

        if ("$salida" -match "INSTALL_FAILED_UPDATE_INCOMPATIBLE|signatures do not match|INSTALL_FAILED_VERSION_DOWNGRADE") {
            Write-Host ""
            Write-Warn2 "El dispositivo tiene instalada una version firmada con OTRA clave."
            Write-Warn2 "Los APK de antes salian con la clave DEBUG; este ya sale con la clave de subida de Play."
            Write-Warn2 "Android no permite actualizar por encima: hay que desinstalar primero."
            Write-Warn2 "Se pierden los datos guardados en el telefono (rutas y fotos locales)."
            Write-Warn2 "La cuenta de la Comunidad vuelve con tu @usuario y contrasena, si ya se publico el cofre."
            $r = if ($NoPause) { "s" } else { Read-Host "    Desinstalar $appId e instalar de nuevo? (s/N)" }
            if ($r -match "^[sSyY]") {
                Invoke-Native { & $adb uninstall $appId } | Out-Null
                $salida2 = Invoke-Native { & $adb install $aInstalar }
                Write-Host $salida2
                if ("$salida2" -match "Success") { Write-Ok "Instalada de nuevo, ya con la clave de subida." }
                else { Write-Warn2 "Sigue fallando. Mira el mensaje de arriba." }
            } else {
                Write-Warn2 "Sin desinstalar no se puede instalar. Manualmente: $adb uninstall $appId"
            }
        } elseif ("$salida" -match "Success") {
            Write-Ok "Instalada en el dispositivo."
        }
    }
}

Stop-Log
Wait-Close

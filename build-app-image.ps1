$ErrorActionPreference = "Stop"

$projectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $projectRoot

Write-Host "[1/3] Build jar..." -ForegroundColor Cyan
mvn -DskipTests package

$jar = Get-ChildItem -Path "$projectRoot\target" -Filter "rezervari-restaurant-*.jar" | Sort-Object LastWriteTime -Descending | Select-Object -First 1
if (-not $jar) {
  throw "Nu am gasit JAR in target."
}

# Discover JavaFX jars from local Maven repo
$fxVersion = "21.0.2"
$repo = Join-Path $env:USERPROFILE ".m2\repository\org\openjfx"
$fxJars = @(
  (Join-Path $repo "javafx-base\$fxVersion\javafx-base-$fxVersion-win.jar"),
  (Join-Path $repo "javafx-graphics\$fxVersion\javafx-graphics-$fxVersion-win.jar"),
  (Join-Path $repo "javafx-controls\$fxVersion\javafx-controls-$fxVersion-win.jar")
)
$fxJars = $fxJars | Where-Object { Test-Path $_ }
if ($fxJars.Count -lt 3) {
  throw "Nu gasesc toate modulele JavaFX. Ruleaza: mvn -DskipTests package si verifica repo local Maven."
}
$modulePath = [string]::Join(";", $fxJars)

Write-Host "[2/3] Prepare resources..." -ForegroundColor Cyan
$resourceDir = Join-Path $projectRoot "package-resources"
Remove-Item -Recurse -Force $resourceDir -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Path $resourceDir | Out-Null
Copy-Item -LiteralPath "$projectRoot\rezervare.html" -Destination $resourceDir
Copy-Item -LiteralPath "$projectRoot\restaurant.db" -Destination $resourceDir -ErrorAction SilentlyContinue
Copy-Item -LiteralPath "$projectRoot\restaurant-seed.sql" -Destination $resourceDir -ErrorAction SilentlyContinue
Copy-Item -LiteralPath "$projectRoot\docker-compose.yml" -Destination $resourceDir -ErrorAction SilentlyContinue
Copy-Item -LiteralPath "$projectRoot\dockerfile" -Destination $resourceDir -ErrorAction SilentlyContinue

Write-Host "[3/3] Build app image..." -ForegroundColor Cyan
$dist = Join-Path $projectRoot "dist-app"
Remove-Item -Recurse -Force $dist -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Path $dist -Force | Out-Null

jpackage `
  --type app-image `
  --name "RezervariRestaurant" `
  --input "$projectRoot\target" `
  --main-jar $jar.Name `
  --main-class com.rezervari.main `
  --module-path "$modulePath" `
  --add-modules javafx.controls,javafx.graphics,jdk.httpserver,java.sql `
  --win-console `
  --resource-dir "$resourceDir" `
  --dest "$dist"

Write-Host "[post] Copy Docker files + jar into app image..." -ForegroundColor Cyan
$appRoot = Join-Path $dist "RezervariRestaurant"
Copy-Item -LiteralPath $jar.FullName -Destination (Join-Path $appRoot "app.jar") -Force
Copy-Item -LiteralPath (Join-Path $projectRoot "rezervare.html") -Destination (Join-Path $appRoot "rezervare.html") -Force

$dbPath = Join-Path $appRoot "restaurant.db"
if (Test-Path $dbPath) {
  if ((Get-Item $dbPath).PSIsContainer) {
    Remove-Item -Recurse -Force $dbPath
  }
}
if (Test-Path (Join-Path $projectRoot "restaurant.db")) {
  Copy-Item -LiteralPath (Join-Path $projectRoot "restaurant.db") -Destination $dbPath -Force
}

$dataDir = Join-Path $appRoot "data"
Remove-Item -Recurse -Force $dataDir -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Path $dataDir | Out-Null
if (Test-Path (Join-Path $projectRoot "restaurant.db")) {
  Copy-Item -LiteralPath (Join-Path $projectRoot "restaurant.db") -Destination (Join-Path $dataDir "restaurant.db") -Force
}

$dockerfile = @"
FROM eclipse-temurin:17-jre-jammy
WORKDIR /app
COPY app.jar /app/app.jar
COPY rezervare.html /app/rezervare.html
COPY data /app/data
ENTRYPOINT ["java", "-cp", "/app/app.jar", "com.rezervari.main"]
"@
Set-Content -Path (Join-Path $appRoot "dockerfile") -Value $dockerfile -Encoding UTF8

$compose = @"
version: '3.8'
services:
  rezervari:
    build: .
    container_name: rezervari_app
    volumes:
      - ./data:/app/data
    environment:
      - RESERVARI_HTTP_PORT=8082
      - RESERVARI_DB_PATH=/app/data/restaurant.db
    ports:
      - "8082:8082"
"@
Set-Content -Path (Join-Path $appRoot "docker-compose.yml") -Value $compose -Encoding UTF8

Write-Host "Done. App image in $dist\RezervariRestaurant" -ForegroundColor Green

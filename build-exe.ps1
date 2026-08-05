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

Write-Host "[3/3] Build EXE..." -ForegroundColor Cyan
$dist = Join-Path $projectRoot "dist"
Remove-Item -Recurse -Force $dist -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Path $dist -Force | Out-Null

jpackage `
  --type exe `
  --name "RezervariRestaurant" `
  --input "$projectRoot\target" `
  --main-jar $jar.Name `
  --main-class com.rezervari.main `
  --module-path "$modulePath" `
  --add-modules javafx.controls,javafx.graphics,jdk.httpserver,java.sql `
  --win-console `
  --resource-dir "$resourceDir" `
  --dest "$dist"

Write-Host "Done. EXE in $dist" -ForegroundColor Green

#!/bin/bash
set -e

# Playwright Integration Tests ausführen mit automatischem App-Start
# Dieses Script startet die Applikation, führt die Tests aus und stoppt sie wieder

echo "========================================"
echo "Playwright Integration Tests"
echo "========================================"
echo ""

# Farben für Output
GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# Port für Test-Applikation
TEST_PORT=9090
APP_PID=""

# Cleanup-Funktion beim Exit
cleanup() {
    if [ ! -z "$APP_PID" ]; then
        echo -e "${YELLOW}Stoppe Applikation (PID: $APP_PID)...${NC}"
        kill $APP_PID 2>/dev/null || true
        sleep 2
        # Force kill falls noch läuft
        kill -9 $APP_PID 2>/dev/null || true
        echo -e "${GREEN}Applikation gestoppt${NC}"
    fi
}

# Registriere Cleanup bei Exit
trap cleanup EXIT INT TERM

echo -e "${YELLOW}1. Kompiliere Projekt...${NC}"
mvn clean package -DskipTests
echo -e "${GREEN}✓ Projekt kompiliert${NC}"
echo ""

echo -e "${YELLOW}2. Starte Applikation auf Port $TEST_PORT...${NC}"
# Starte Applikation im Hintergrund
java -jar target/plaintext-webapp-*.jar \
    --server.port=$TEST_PORT \
    --spring.flyway.enabled=false \
    --spring.jpa.hibernate.ddl-auto=create-drop \
    > /tmp/playwright-app.log 2>&1 &
APP_PID=$!
echo -e "${GREEN}✓ Applikation gestartet (PID: $APP_PID)${NC}"
echo ""

echo -e "${YELLOW}3. Warte auf Applikation...${NC}"
# Warte bis Applikation bereit ist (max 60 Sekunden)
RETRIES=60
COUNT=0
while [ $COUNT -lt $RETRIES ]; do
    if curl -s http://localhost:$TEST_PORT/login.xhtml > /dev/null 2>&1; then
        echo -e "${GREEN}✓ Applikation ist bereit!${NC}"
        break
    fi
    echo -n "."
    sleep 1
    COUNT=$((COUNT+1))

    # Prüfe ob Prozess noch läuft
    if ! kill -0 $APP_PID 2>/dev/null; then
        echo -e "${RED}✗ Applikation ist abgestürzt!${NC}"
        echo "Letzte Zeilen aus dem Log:"
        tail -50 /tmp/playwright-app.log
        exit 1
    fi
done
echo ""

if [ $COUNT -eq $RETRIES ]; then
    echo -e "${RED}✗ Timeout beim Warten auf Applikation!${NC}"
    echo "Letzte Zeilen aus dem Log:"
    tail -50 /tmp/playwright-app.log
    exit 1
fi

echo ""
echo -e "${YELLOW}4. Führe Playwright Tests aus...${NC}"
# Tests ausführen
mvn failsafe:integration-test failsafe:verify \
    -Dtest.server.port=$TEST_PORT \
    -Dtest.server.url=http://localhost:$TEST_PORT

TEST_RESULT=$?
echo ""

if [ $TEST_RESULT -eq 0 ]; then
    echo -e "${GREEN}========================================"
    echo -e "✓ Alle Tests erfolgreich!"
    echo -e "========================================${NC}"
else
    echo -e "${RED}========================================"
    echo -e "✗ Tests fehlgeschlagen!"
    echo -e "========================================${NC}"

    # Zeige Fehler-Details
    echo ""
    echo "Test-Reports:"
    find target/failsafe-reports -name "*.txt" -exec echo "---" \; -exec cat {} \;
fi

# Cleanup wird automatisch durch trap aufgerufen
exit $TEST_RESULT

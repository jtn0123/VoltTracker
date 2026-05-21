#!/bin/bash
#
# VoltTracker Development Helper
# Usage: ./scripts/dev.sh [command]
#
# Commands:
#   start     - Start development services
#   stop      - Stop all services
#   restart   - Restart services
#   logs      - Tail logs from all services
#   ip        - Show Mac's IP for Torque Pro configuration
#   status    - Show service status
#   simulate  - Run the data simulator
#   db        - Connect to PostgreSQL CLI
#   reset     - Reset database (WARNING: deletes all data)
#   test      - Run pytest tests
#   test-cov  - Run tests with coverage report
#

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"
cd "$PROJECT_DIR"

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

print_header() {
    echo -e "${BLUE}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
    echo -e "${BLUE}  VoltTracker Development Environment${NC}"
    echo -e "${BLUE}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
}

get_mac_ip() {
    # Try to get IP from common interfaces
    # Priority: iPhone hotspot (bridge), then en0 (WiFi), then en1

    # iPhone USB hotspot typically uses bridge interface
    local bridge_ip=$(ipconfig getifaddr bridge0 2>/dev/null)
    if [ -n "$bridge_ip" ]; then
        echo "$bridge_ip"
        return
    fi

    # iPhone WiFi hotspot - look for 172.20.10.x range
    local en0_ip=$(ipconfig getifaddr en0 2>/dev/null)
    if [ -n "$en0_ip" ]; then
        echo "$en0_ip"
        return
    fi

    local en1_ip=$(ipconfig getifaddr en1 2>/dev/null)
    if [ -n "$en1_ip" ]; then
        echo "$en1_ip"
        return
    fi

    echo "NOT_FOUND"
}

cmd_start() {
    print_header
    echo -e "${GREEN}Starting development services...${NC}"

    # Create .env if it doesn't exist
    if [ ! -f .env ]; then
        echo -e "${YELLOW}Creating .env from .env.example...${NC}"
        cp .env.example .env
    fi

    # Start with dev override
    docker-compose -f docker-compose.yml -f docker-compose.dev.yml up -d --build

    echo ""
    echo -e "${GREEN}Services started!${NC}"
    echo ""
    cmd_ip
}

cmd_stop() {
    print_header
    echo -e "${YELLOW}Stopping services...${NC}"
    docker-compose -f docker-compose.yml -f docker-compose.dev.yml down
    echo -e "${GREEN}Services stopped.${NC}"
}

cmd_restart() {
    cmd_stop
    echo ""
    cmd_start
}

cmd_logs() {
    print_header
    echo -e "${BLUE}Tailing logs (Ctrl+C to stop)...${NC}"
    echo ""
    docker-compose -f docker-compose.yml -f docker-compose.dev.yml logs -f
}

cmd_ip() {
    local ip=$(get_mac_ip)

    echo -e "${BLUE}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
    echo -e "${BLUE}  Torque Pro Configuration${NC}"
    echo -e "${BLUE}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"

    if [ "$ip" = "NOT_FOUND" ]; then
        echo -e "${RED}Could not detect Mac's IP address.${NC}"
        echo ""
        echo "Make sure you're connected to a network."
        echo "For phone hotspot testing:"
        echo "  1. Enable hotspot on your phone"
        echo "  2. Connect Mac to phone's WiFi hotspot"
        echo "  3. Run this command again"
    else
        echo ""
        echo -e "  Mac IP Address: ${GREEN}$ip${NC}"
        echo ""
        echo -e "  ${YELLOW}Configure Torque Pro:${NC}"
        echo -e "  URL: ${GREEN}http://$ip:8080/torque/upload${NC}"
        echo ""
        echo "  In Torque Pro:"
        echo "    Settings → Data Logging & Upload → Webserver URL"
        echo "    Enter the URL above"
        echo "    Enable 'Upload to webserver'"
        echo ""
        echo -e "  ${YELLOW}Dashboard:${NC}"
        echo -e "  Open ${GREEN}http://$ip:8080${NC} in browser"
        echo -e "  Or   ${GREEN}http://localhost:8080${NC} on this Mac"
    fi
    echo -e "${BLUE}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
}

cmd_status() {
    print_header
    echo -e "${BLUE}Service Status:${NC}"
    echo ""
    docker-compose -f docker-compose.yml -f docker-compose.dev.yml ps
    echo ""

    # Check if receiver is responding
    if curl -s http://localhost:8080/api/status > /dev/null 2>&1; then
        echo -e "API Status: ${GREEN}Online${NC}"
        curl -s http://localhost:8080/api/status | python3 -m json.tool 2>/dev/null || true
    else
        echo -e "API Status: ${RED}Offline${NC}"
    fi
}

cmd_simulate() {
    print_header
    echo -e "${BLUE}Starting Data Simulator...${NC}"
    echo ""

    # Check if server is running
    if ! curl -s http://localhost:8080/api/status > /dev/null 2>&1; then
        echo -e "${RED}Error: Server is not running. Start it first with:${NC}"
        echo "  ./scripts/dev.sh start"
        exit 1
    fi

    # Pass all arguments to simulator
    python3 "$SCRIPT_DIR/simulator.py" "$@"
}

cmd_db() {
    print_header
    echo -e "${BLUE}Connecting to PostgreSQL...${NC}"
    echo "Type \\q to quit"
    echo ""
    docker-compose -f docker-compose.yml -f docker-compose.dev.yml exec db psql -U volt -d volt_tracker
}

cmd_reset() {
    print_header
    echo -e "${RED}WARNING: This will delete ALL data!${NC}"
    read -p "Are you sure? (type 'yes' to confirm): " confirm

    if [ "$confirm" = "yes" ]; then
        echo "Resetting database..."
        docker-compose -f docker-compose.yml -f docker-compose.dev.yml down -v
        echo -e "${GREEN}Database reset. Run './scripts/dev.sh start' to restart.${NC}"
    else
        echo "Cancelled."
    fi
}

cmd_test() {
    print_header
    echo -e "${BLUE}Running tests...${NC}"
    echo ""

    # Check if pytest is installed
    if ! command -v pytest &> /dev/null; then
        echo -e "${YELLOW}Installing test dependencies...${NC}"
        pip install -r receiver/requirements-dev.txt
    fi

    # Run pytest from project root
    cd "$PROJECT_DIR"
    pytest "$@"
}

cmd_test_cov() {
    print_header
    echo -e "${BLUE}Running tests with coverage...${NC}"
    echo ""

    # Check if pytest-cov is installed
    if ! python3 -c "import pytest_cov" 2>/dev/null; then
        echo -e "${YELLOW}Installing test dependencies...${NC}"
        pip install -r receiver/requirements-dev.txt
    fi

    # Run pytest with coverage
    cd "$PROJECT_DIR"
    pytest --cov=receiver --cov-report=term-missing --cov-report=html "$@"

    echo ""
    echo -e "${GREEN}Coverage report saved to htmlcov/index.html${NC}"
}

cmd_help() {
    print_header
    echo ""
    echo "Usage: ./scripts/dev.sh [command]"
    echo ""
    echo "Commands:"
    echo "  start      Start development services"
    echo "  stop       Stop all services"
    echo "  restart    Restart services"
    echo "  logs       Tail logs from all services"
    echo "  ip         Show Mac's IP for Torque Pro configuration"
    echo "  status     Show service status"
    echo "  simulate   Run the data simulator (pass --help for options)"
    echo "  db         Connect to PostgreSQL CLI"
    echo "  reset      Reset database (deletes all data)"
    echo "  test       Run pytest tests"
    echo "  test-cov   Run tests with coverage report"
    echo "  help       Show this help message"
    echo ""
    echo "Phone Hotspot Setup:"
    echo "  1. Enable hotspot on your phone"
    echo "  2. Connect Mac to phone's WiFi hotspot"
    echo "  3. Run: ./scripts/dev.sh start"
    echo "  4. Run: ./scripts/dev.sh ip"
    echo "  5. Configure Torque Pro with the URL shown"
    echo ""
}

# Main command dispatcher
case "${1:-help}" in
    start)    cmd_start ;;
    stop)     cmd_stop ;;
    restart)  cmd_restart ;;
    logs)     cmd_logs ;;
    ip)       cmd_ip ;;
    status)   cmd_status ;;
    simulate) shift; cmd_simulate "$@" ;;
    db)       cmd_db ;;
    reset)    cmd_reset ;;
    test)     shift; cmd_test "$@" ;;
    test-cov) shift; cmd_test_cov "$@" ;;
    help|--help|-h) cmd_help ;;
    *)
        echo -e "${RED}Unknown command: $1${NC}"
        echo "Run './scripts/dev.sh help' for usage"
        exit 1
        ;;
esac

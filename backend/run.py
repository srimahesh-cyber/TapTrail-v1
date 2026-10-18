#!/usr/bin/env python3
"""Entrypoint for TapTrail backend service."""

import argparse
import socket
import sys
import uvicorn
from app.config import get_settings


def is_port_available(host: str, port: int) -> bool:
    """Check if the given host and port can be bound."""
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
        try:
            s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            s.bind((host, port))
            return True
        except OSError:
            return False


def find_available_port(host: str, preferred_port: int, max_attempts: int = 10) -> int:
    """Find a usable port starting from preferred_port."""
    for candidate in range(preferred_port, preferred_port + max_attempts):
        if is_port_available(host, candidate):
            return candidate
    return preferred_port


def main():
    settings = get_settings()

    parser = argparse.ArgumentParser(description="TapTrail Backend API Server")
    parser.add_argument("--host", default=settings.HOST, help=f"Host address (default: {settings.HOST})")
    parser.add_argument("--port", type=int, default=settings.PORT, help=f"Port number (default: {settings.PORT})")
    args = parser.parse_args()

    target_host = args.host
    target_port = args.port

    if not is_port_available(target_host, target_port):
        alt_port = find_available_port(target_host, target_port + 1)
        print(f"\n[WARNING] Port {target_port} is already in use by another application.")
        print(f"[INFO] Automatically switching to available port: {alt_port}")
        print(f"[INFO] Make sure to update your Android app's Backend URL to http://10.0.2.2:{alt_port} (emulator) or your LAN IP.\n")
        target_port = alt_port

    print("=" * 60)
    print("TapTrail Backend Server Starting")
    print(f" • Host:    {target_host}")
    print(f" • Port:    {target_port}")
    print(f" • Model:   {settings.MODEL_NAME}")
    print(f" • Health:  http://localhost:{target_port}/api/v1/health")
    print(f" • Auth:    Bearer {settings.TAPTRAIL_AUTH_TOKEN}")
    print("=" * 60 + "\n")

    uvicorn.run(
        "app.main:app",
        host=target_host,
        port=target_port,
        log_level="info",
        access_log=False,  # Managed by RequestLoggingMiddleware for privacy
    )


if __name__ == "__main__":
    main()

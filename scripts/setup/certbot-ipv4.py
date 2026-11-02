#!/usr/bin/env python3
"""Run the installed Certbot over IPv4 on hosts with unreliable IPv6 egress.

Install with the OS-packaged certbot and python3-urllib3 on the Linux server.
Only this process is affected; normal TLS certificate verification is preserved.
"""
import socket
import sys


def main():
    import urllib3.util.connection as connection
    from certbot.main import main as certbot_main

    connection.allowed_gai_family = lambda: socket.AF_INET
    return certbot_main(sys.argv[1:])


if __name__ == '__main__':
    raise SystemExit(main())

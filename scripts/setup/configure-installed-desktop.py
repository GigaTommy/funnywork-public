"""Configure the installed application's service without a source launcher."""
import argparse
import json
from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[2]))
from funnyworker.desktop import validate_server
from funnyworker.model_config import private_write, protect_directory


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--server', required=True)
    parser.add_argument('--home', type=Path, default=Path.home() / '.dsh')
    args = parser.parse_args()
    validate_server(args.server)
    home = args.home.expanduser().resolve()
    protect_directory(home)
    private_write(home / 'funnyworker-backend.json', json.dumps({'url': args.server}).encode('utf-8'))
    print('Installed desktop service configured. Restart the application to apply.')


if __name__ == '__main__':
    main()

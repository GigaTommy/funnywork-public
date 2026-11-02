#!/usr/bin/env python3
"""Check the built admin entry before publishing it below /admin/."""
import argparse
from html.parser import HTMLParser
from pathlib import Path
import re
from urllib.parse import unquote, urlsplit


class Entry(HTMLParser):
    def __init__(self):
        super().__init__()
        self.resources = []

    def handle_starttag(self, tag, attrs):
        attrs = dict(attrs)
        if tag == 'script' and attrs.get('src'):
            self.resources.append(attrs['src'])
        if tag == 'link' and attrs.get('href'):
            self.resources.append(attrs['href'])


def check(directory, base='/admin/'):
    directory = directory.resolve()
    text = (directory / 'index.html').read_text()
    if re.search(r'%VITE_[A-Z0-9_]+%', text):
        raise ValueError('Unresolved Vite environment variables in the built entry')
    if not re.search(r'<title>[^<%]+</title>', text):
        raise ValueError('Missing resolved page title')
    entry = Entry()
    entry.feed(text)
    if not any(urlsplit(url).path.endswith('.js') for url in entry.resources):
        raise ValueError('Missing application JavaScript')
    for url in entry.resources:
        parsed = urlsplit(url)
        if parsed.scheme or parsed.netloc or not parsed.path.startswith(base):
            raise ValueError('Resource outside the deployment base: ' + url)
        file = (directory / unquote(parsed.path[len(base):])).resolve()
        if not file.is_relative_to(directory) or not file.is_file():
            raise ValueError('Missing or invalid built resource: ' + url)
    return len(entry.resources)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('directory', type=Path, nargs='?', default=Path(__file__).resolve().parents[2] / 'apps/management/dist')
    args = parser.parse_args()
    print(f'Management artifact: {check(args.directory)} valid /admin/ resources; environment resolved')


if __name__ == '__main__':
    main()

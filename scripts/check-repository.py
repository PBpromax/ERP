#!/usr/bin/env python3
"""Check the Git index without printing suspected secret values (stdlib only)."""
import io
import re
import subprocess
import sys
import zipfile
from pathlib import PurePosixPath


def git(*args):
    return subprocess.check_output(['git', *args])


def check():
    paths = git('ls-files', '-z').decode().split('\0')
    blocked_dirs = {'data', 'uploads', 'outputs', 'logs', 'tmp', 'backups',
                    'node_modules', 'target', 'dist', '.od-skills'}
    blocked_ext = re.compile(r'\.(?:db(?:-.*)?|sqlite\w*|dump|backup|bak|pem|key|p12|pfx|jks|log|pid|csv|tsv|xls|docx|pdf|zip|tgz|tar(?:\.gz)?)$', re.I)
    signatures = {
        'private key': re.compile(r'-----BEGIN (?:[A-Z0-9]+ )*PRIVATE KEY-----'),
        'GitHub token': re.compile(r'\b(?:gh[pousr]_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{30,})\b'),
        'API token': re.compile(r'\bsk-[A-Za-z0-9_-]{20,}\b'),
        'AWS access ID': re.compile(r'\b(?:AKIA|ASIA)[A-Z0-9]{16}\b'),
        'credential URL': re.compile(r'://[^\s/:@]+:[^\s/@]+@'),
        'bcrypt credential': re.compile(r'\$2[aby]\$\d{2}\$[./A-Za-z0-9]{53}'),
    }
    failures = []
    count = 0
    for path in filter(None, paths):
        p = PurePosixPath(path)
        example = p.name.endswith('.example')
        template = path.startswith('backend/src/main/resources/templates/') and p.name.endswith('-template.xlsx')
        forbidden = bool(set(p.parts) & blocked_dirs or blocked_ext.search(path)
                         or (p.name.startswith('.env') and not example)
                         or (p.suffix == '.xlsx' and not template))
        if forbidden:
            failures.append(f'{path}: file type/directory must stay local')
            continue
        raw = git('show', ':' + path)
        count += 1
        members = [(path, raw)]
        if template:
            with zipfile.ZipFile(io.BytesIO(raw)) as book:
                if any(n.startswith(('xl/media/', 'xl/embeddings/', 'xl/externalLinks/')) for n in book.namelist()):
                    failures.append(f'{path}: embedded media/objects/links require private review')
                members = [(f'{path}:{name}', book.read(name)) for name in book.namelist() if name.endswith('.xml')]
        for label, content in members:
            text = content.decode('utf-8', errors='replace')
            for description, pattern in signatures.items():
                if pattern.search(text):
                    failures.append(f'{label}: possible {description}')
            if template and (re.search(r'(?<!\d)1[3-9]\d{9}(?!\d)', text)
                             or re.search(r'(?<!\d)\d{16,22}(?!\d)', text)):
                failures.append(f'{label}: possible phone/bank account in template')
            if p.suffix in {'.yml', '.yaml'}:
                for line_no, line in enumerate(text.splitlines(), 1):
                    match = re.match(r'\s*(?:password|token-secret):\s*(\S.+|\S)\s*$', line)
                    if match and not match[1].startswith(('${', 'test-only', '#')):
                        failures.append(f'{path}:{line_no}: literal password/secret in configuration')
    if failures:
        print('\n'.join(failures), file=sys.stderr)
        return 1
    print(f'Repository check passed: {count} staged/tracked files inspected. Review business content manually before publishing.')
    return 0


if __name__ == '__main__':
    sys.exit(check())

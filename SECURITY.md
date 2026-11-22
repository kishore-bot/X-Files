# Security policy

Xfiles opens untrusted files: PDFs, Office documents, EPUBs, archives, images and media. Parsing bugs can be security bugs.

## Reporting a vulnerability

Please **do not open a public issue.** Instead, report it privately through GitHub's **"Report a vulnerability"** button on the repository's Security tab. If that isn't available, contact a maintainer privately through their GitHub profile.

Include:

- the affected version or commit;
- steps to reproduce, ideally with a sample file;
- what an attacker could achieve.

We aim to acknowledge reports within 7 days and to ship a fix as soon as possible after confirming it. We will credit you if you wish.

## In scope

- Crashes, hangs or memory-safety issues caused by a crafted file, including zip-slip and decompression bombs.
- Ways to read files the user didn't open, or to send data off the device.
- Prompt injection that makes Fily act without the user's confirmation.

## Supported versions

Only the latest release and the `dev-deploy` branch receive fixes.

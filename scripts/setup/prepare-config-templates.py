#!/usr/bin/env python3
"""Create missing local configuration from credential-free source templates.

A destination that already exists is left completely alone: it is only tested
for existence, its template is never opened or decoded and no temporary file is
ever created for it. Its bytes and mtime, and the template file itself,
therefore stay exactly as the user left them, even when the template is not
valid UTF-8.

Existence is decided on the directory entry itself (``os.path.lexists``), never
by following the entry: a symlink counts as existing user configuration whether
or not the target it points at currently exists. A dangling link is therefore
skipped exactly like a regular file — it is not read, not replaced and not
resolved, so the link and its (absent) target keep their pre-run state and the
remaining templates are still generated. Following such a link would instead
report the destination as missing and publish over it, which is precisely the
user configuration this script must never touch.

A destination file is never opened or truncated in place. For every missing
destination the template content is first written to a uniquely named
same-directory temporary file created exclusively (``O_CREAT | O_EXCL``),
restricted with ``chmod`` and only then published, so a reader can never observe
a partially written destination and no pre-existing file or symlink can be
followed or clobbered.

If any step of that sequence raises, the temporary file is discarded best-effort
and the original exception is re-raised unchanged: the failure is never
swallowed, never retried and never replaced by a cleanup error. The destination
therefore keeps its pre-run state (absent, or an existing user file with its
original bytes and mtime), and running the script again after the environment
recovers generates the complete UTF-8 template.

That cleanup also covers the raw descriptor returned by the exclusive
``os.open``: it is owned by this function until :func:`os.fdopen` has returned
the managed file object. If ``os.fdopen`` itself raises — for example on
``EMFILE`` when the process is at its descriptor limit — it does not take the
descriptor, so the still-unclaimed integer descriptor is closed exactly once
before the original exception propagates. That close is best-effort: it is a
single ``os.close`` whose error is contained, it is never retried (a retried
close could hit a descriptor number reused by another thread) and it can
neither replace nor alter the original exception. If that close itself fails the
descriptor remains open — cleanup has no further recourse — and the original
exception object and message are still what the caller sees.

Once :func:`os.fdopen` has returned, the raw descriptor is forgotten right away
and is never closed here again: the file object owns it from that point, for the
successful write as well as for any later failure, so the cleanup path cannot
double-close a descriptor the file object may still hold or a number that has
since been reused.

Templates are read only for destinations that are still missing, so an
undecodable template can no longer fail a run whose destination already exists.
A template that cannot be decoded for a genuinely missing destination still
raises the original :class:`UnicodeDecodeError` before any temporary file is
created.

Publication uses :func:`os.link`, which fails with :class:`FileExistsError`
instead of replacing: a destination created between the existence check and
publication therefore keeps its own bytes and mtime. ``os.replace`` cannot be
used for that step, because on POSIX it silently replaces a concurrently created
destination.
"""
from pathlib import Path
import os
import random
import secrets

root = Path(__file__).resolve().parents[2]
patterns = ('apps/management/.env.*.example', 'services/identity/**/src/main/resources/application*.yml.example')
mode = 0o600


def discard(path):
    """Remove a temporary file without letting cleanup mask the real failure."""
    try:
        os.unlink(path)
    except FileNotFoundError:
        return
    except BaseException:
        # Never raise a secondary error while unwinding the original one.
        return


def close_descriptor(descriptor):
    """Best-effort release of a descriptor the file object never claimed.

    Only used for a raw integer descriptor whose handover to :func:`os.fdopen`
    did not complete. The close is attempted exactly once: retrying could hit a
    descriptor number the kernel already released and another thread reused.
    Every error (including ``EBADF``) is contained, so cleanup can never replace
    or alter the exception that is already being propagated, even if the close
    itself fails and the descriptor stays open.
    """
    try:
        os.close(descriptor)
    except BaseException:
        # The failure being unwound is the one that matters; a cleanup error
        # must never replace it.
        pass


def create_temporary(destination):
    """Reserve a unique sibling temporary file exclusively and return its descriptor."""
    for attempt in range(100):
        name = '.{0}.{1:08x}{2:08x}.tmp'.format(
            destination.name, secrets.randbits(32), random.getrandbits(32))
        path = destination.with_name(name)
        try:
            return os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600), path
        except FileExistsError:
            continue
        except BaseException:
            discard(path)
            raise
    raise FileExistsError('cannot create a unique temporary file for ' + str(destination))


def publish(source, destination):
    """Publish a completed temporary file, refusing to replace any existing file.

    ``os.link`` fails with :class:`FileExistsError` when the destination already
    exists, which is exactly the guarantee required here: a destination created
    between the existence check and publication keeps its own bytes and mtime.
    The temporary name is unlinked once the destination owns the content, so the
    destination is left as the only name for that content.
    """
    os.link(source, destination)
    discard(source)


def prepare(destination, template):
    """Create ``destination`` from ``template`` when it does not already exist.

    ``template`` is read only after ``destination`` has been confirmed missing,
    so an existing user file is skipped without touching the template at all.
    The read happens before any temporary file is created, which keeps a
    decode failure free of partial output.

    ``descriptor`` holds the raw descriptor only until :func:`os.fdopen`
    returns the file object that takes ownership of it. Handover is recorded
    immediately on that return, before the file object is entered, written to or
    closed, so the cleanup path closes the descriptor exactly when ``os.fdopen``
    failed and never afterwards — not for the successful write and not for any
    later failure either, which rules out a double close. The result of
    ``os.fdopen`` is used only as the ``with`` item, exactly like any other
    context manager, so the write path also works when the factory yields a
    context manager rather than a plain file object.
    """
    if os.path.lexists(destination):
        return
    content = template.read_text(encoding='utf-8')
    descriptor = None
    temp = None
    try:
        descriptor, temp = create_temporary(destination)
        try:
            file = os.fdopen(descriptor, 'w', encoding='utf-8', newline='\n')
        except BaseException:
            # `os.fdopen` failed, so the file object never took the descriptor:
            # release it once (best-effort), then forget it so the outer handler
            # below cannot close it a second time and keep the original error.
            close_descriptor(descriptor)
            descriptor = None
            raise
        # The file object now owns the descriptor; forget the raw number before
        # anything else can fail, so cleanup never closes it again.
        descriptor = None
        with file as stream:
            stream.write(content)
        os.chmod(temp, mode)
        if os.path.lexists(destination):
            # Another run created the destination (or linked it) while the
            # template was being written; keep that entry as it is, including
            # the link itself and its target, and drop this temporary file.
            discard(temp)
            return
        publish(temp, destination)
        temp = None
    except BaseException:
        if descriptor is not None:
            close_descriptor(descriptor)
        if temp is not None:
            discard(temp)
        raise


def main():
    """Create every missing local configuration from its source template.

    Each destination is checked for existence before its template is read, so a
    destination that already exists is skipped whole: no read, no write, no
    temporary file. The check tests the directory entry without following it, so
    a symlink — dangling or not — counts as existing configuration and the
    remaining templates are still processed. A template is only read for a
    destination that is genuinely missing.
    """
    for pattern in patterns:
        for template in root.glob(pattern):
            destination = template.with_name(template.name.removesuffix('.example'))
            if os.path.lexists(destination):
                continue
            prepare(destination, template)


if __name__ == '__main__':
    main()

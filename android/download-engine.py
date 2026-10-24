#!/usr/bin/env python3
"""Downloads the user's own prototypeenginef.dll for the android-apk "full" build.

Accepts the links people actually copy from a browser:

* Hugging Face - file pages and direct links, model/dataset/space repos alike:
    https://huggingface.co/<user>/<repo>/blob/main/prototypeenginef.dll    (viewer page)
    https://huggingface.co/<user>/<repo>/resolve/main/prototypeenginef.dll (direct)
  The /blob/ form is rewritten to /resolve/ (the download route). Private repos work when the
  HF_TOKEN environment variable holds a read token (a repository secret in CI); the token is sent
  only to huggingface.co itself, never to the storage CDN the resolve URL redirects to.
* Google Drive - share links in any common shape:
    https://drive.google.com/file/d/<id>/view?usp=sharing
    https://drive.google.com/open?id=<id>
    https://drive.google.com/uc?id=<id>&export=download
  The file must be shared as "anyone with the link". Downloads go through gdown when installed,
  otherwise through drive.usercontent.google.com with confirm=t (skips the virus-scan page).
* Any other direct https link.

The URL is never printed (links may carry credentials); only the method and host are logged.
The result must start with "MZ" or the script fails with a hint about the usual causes.

Usage:
  android/download-engine.py <url> <dest>
  android/download-engine.py --dry-run <url>   # print the download plan (for tests)
"""
import os
import re
import sys
import urllib.error
import urllib.parse
import urllib.request

USER_AGENT = "proto-re-ci/1.0 (+https://github.com/Player124413/proto-re)"


def classify(url):
    """Returns (method, target): target is the effective URL, or a Drive file id."""
    parsed = urllib.parse.urlparse(url)
    host = parsed.netloc.lower()
    if host == "huggingface.co" or host.endswith(".huggingface.co"):
        # /blob/<rev>/<path> is the HTML file viewer; /resolve/<rev>/<path> serves the bytes.
        return "huggingface", re.sub(r"/blob/", "/resolve/", url, count=1)
    if host in ("drive.google.com", "drive.usercontent.google.com", "docs.google.com"):
        return "google-drive", drive_file_id(url)
    return "direct", url


def drive_file_id(url):
    match = re.search(r"/file/d/([A-Za-z0-9_-]{10,})", url) or re.search(r"[?&]id=([A-Za-z0-9_-]{10,})", url)
    return match.group(1) if match else None


class _StripAuthOnRedirect(urllib.request.HTTPRedirectHandler):
    """urllib would forward Authorization to the redirect target; the HF token must stay on huggingface.co."""

    def redirect_request(self, req, fp, code, msg, headers, newurl):
        new = super().redirect_request(req, fp, code, msg, headers, newurl)
        if new is not None and urllib.parse.urlparse(newurl).netloc != urllib.parse.urlparse(req.full_url).netloc:
            new.remove_header("Authorization")
        return new


def download_urllib(url, dest_part, headers=None):
    request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT, **(headers or {})})
    opener = urllib.request.build_opener(_StripAuthOnRedirect)
    with opener.open(request, timeout=120) as response, open(dest_part, "wb") as out:
        total = response.headers.get("Content-Length")
        total = int(total) if total and total.isdigit() else None
        done = 0
        next_report = 0
        while True:
            chunk = response.read(1 << 20)
            if not chunk:
                break
            out.write(chunk)
            done += len(chunk)
            if total and done >= next_report:
                print(f"download-engine: {done * 100 // total}% ({done}/{total} bytes)", flush=True)
                next_report += max(total // 10, 1)


def download_drive(url, file_id, dest_part):
    if file_id is None:
        raise SystemExit("download-engine: could not find a file id in the Google Drive link; "
                         "paste the link from Share -> Copy link")
    try:
        import gdown
    except ImportError:
        gdown = None
    if gdown is not None:
        try:
            result = gdown.download(id=file_id, output=dest_part, quiet=False)
            if result and os.path.exists(dest_part):
                return
            print("download-engine: gdown did not produce a file, trying the direct endpoint", file=sys.stderr)
        except Exception as exc:  # quota, permission, network: surface via the fallback's error or below
            print(f"download-engine: gdown failed ({exc}), trying the direct endpoint", file=sys.stderr)
    direct = f"https://drive.usercontent.google.com/download?id={file_id}&export=download&confirm=t"
    download_urllib(direct, dest_part)


def check_result(path):
    """Fails unless the download is a PE image; HTML answers get an actionable message."""
    with open(path, "rb") as f:
        head = f.read(4096)
    if head[:2] == b"MZ":
        return
    if head.lstrip()[:1] == b"<":
        title = re.search(rb"<title[^>]*>([^<]{0,120})", head, re.IGNORECASE)
        hint = f" (page title: {title.group(1).decode('utf-8', 'replace').strip()})" if title else ""
        raise SystemExit(
            "download-engine: got an HTML page instead of the DLL" + hint + ". Usual causes: the Google Drive "
            "file is not shared as 'anyone with the link', the Hugging Face repo is private without an "
            "HF_TOKEN secret, or the link points at a folder/preview page rather than the file itself.")
    raise SystemExit("download-engine: the downloaded file is not a PE/DLL (missing MZ header); wrong link?")


def main(argv):
    args = [a for a in argv[1:] if a != "--dry-run"]
    dry_run = "--dry-run" in argv[1:]
    if dry_run:
        if len(args) != 1:
            print(__doc__)
            return 2
    elif len(args) != 2:
        print(__doc__)
        return 2

    url = args[0].strip().strip("'\"")
    parsed = urllib.parse.urlparse(url)
    is_localhost = parsed.hostname in ("127.0.0.1", "localhost")
    if parsed.scheme != "https" and not (parsed.scheme == "http" and is_localhost):
        raise SystemExit("download-engine: the link must start with https://")

    method, target = classify(url)
    if dry_run:
        print(f"method: {method}")
        print(f"target: {target}")
        return 0

    dest = args[1]
    os.makedirs(os.path.dirname(os.path.abspath(dest)), exist_ok=True)
    dest_part = dest + ".part"
    print(f"download-engine: method={method} host={parsed.netloc}")
    try:
        if method == "google-drive":
            download_drive(url, target, dest_part)
        elif method == "huggingface":
            headers = {}
            token = os.environ.get("HF_TOKEN", "")
            if token:
                headers["Authorization"] = f"Bearer {token}"
                print("download-engine: using HF_TOKEN (private Hugging Face repo)")
            download_urllib(target, dest_part, headers)
        else:
            download_urllib(target, dest_part)
        check_result(dest_part)
    except urllib.error.HTTPError as exc:
        if os.path.exists(dest_part):
            os.remove(dest_part)
        hint = ""
        if method == "huggingface" and exc.code in (401, 403):
            hint = " - a private Hugging Face repo needs a read token in the HF_TOKEN repository secret"
        if method == "google-drive" and exc.code in (401, 403, 404):
            hint = " - share the Drive file as 'anyone with the link' and copy the link again"
        raise SystemExit(f"download-engine: download failed: HTTP {exc.code} from {parsed.netloc}{hint}")
    except BaseException:
        if os.path.exists(dest_part):
            os.remove(dest_part)
        raise
    os.replace(dest_part, dest)
    print(f"download-engine: OK, {os.path.getsize(dest)} bytes -> {dest}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))

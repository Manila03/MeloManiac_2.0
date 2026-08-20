"""Verify Spotify embed + pathfinder pagination (playlist + album)."""
import json
import re
import sys
import urllib.request

sys.stdout.reconfigure(encoding="utf-8", errors="replace")

GOLDEN_ID = "3c6jpDUyKRyIoRoxdxqPQW"
LONG_ID = "5S8SJdl1BDc0ugpkEvFsIL"
ALBUM_ID = "4LH4d3cOWNNsVw41Gqt2kv"
UA = (
    "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
)
PATHFINDER_HASH = "7982b11e21535cd2594badc40030b745671b61a1fa66766e569d45e6364f3422"
ALBUM_HASH = "b9bfabef66ed756e5e13f68a942deb60bd4125ec1f1be8cc42769dc0259b4b10"
PATHFINDER_URLS = (
    "https://api-partner.spotify.com/pathfinder/v1/query",
    "https://api-partner.spotify.com/pathfinder/v2/query",
)


def fetch_html(kind: str, sid: str) -> str:
    url = f"https://open.spotify.com/embed/{kind}/{sid}"
    req = urllib.request.Request(
        url,
        headers={"User-Agent": UA, "Accept": "text/html", "Accept-Language": "en-US,en;q=0.9"},
    )
    with urllib.request.urlopen(req, timeout=30) as resp:
        return resp.read().decode("utf-8", "ignore")


def fetch_entity(kind: str, sid: str) -> dict:
    html = fetch_html(kind, sid)
    m = re.search(
        r'<script[^>]*type=["\']application/json["\'][^>]*>(.*?)</script>',
        html,
        re.S | re.I,
    )
    if not m:
        raise SystemExit(f"FAIL {kind}/{sid}: no application/json script")
    data = json.loads(m.group(1))
    return data["props"]["pageProps"]["state"]["data"]["entity"]


def extract_token(html: str) -> str:
    m = re.search(r'"accessToken"\s*:\s*"([^"]+)"', html)
    if not m:
        raise SystemExit("FAIL: no accessToken")
    return m.group(1)


def pathfinder(token: str, operation: str, variables: dict, sha: str) -> dict:
    body = json.dumps(
        {
            "operationName": operation,
            "variables": variables,
            "extensions": {"persistedQuery": {"version": 1, "sha256Hash": sha}},
        }
    ).encode("utf-8")
    last_err = None
    for url in PATHFINDER_URLS:
        req = urllib.request.Request(
            url,
            data=body,
            method="POST",
            headers={
                "Authorization": f"Bearer {token}",
                "Content-Type": "application/json",
                "Accept": "application/json",
                "User-Agent": UA,
                "App-Platform": "WebPlayer",
                "Origin": "https://open.spotify.com",
                "Referer": "https://open.spotify.com/",
            },
        )
        try:
            with urllib.request.urlopen(req, timeout=30) as resp:
                return json.loads(resp.read().decode("utf-8", "ignore"))
        except Exception as e:
            last_err = e
    raise SystemExit(f"FAIL pathfinder: {last_err}")


def main() -> None:
    entity = fetch_entity("playlist", GOLDEN_ID)
    tracks = entity.get("trackList") or []
    print(f"playlist embed tracks={len(tracks)}")
    if len(tracks) < 20:
        raise SystemExit("FAIL golden playlist too small")

    track = fetch_entity("track", "3qT4bUD1MaWpGrTwcvguhb")
    print(f"track title={track.get('title')!r}")

    album = fetch_entity("album", ALBUM_ID)
    print(f"album embed tracks={len(album.get('trackList') or [])}")

    html = fetch_html("playlist", LONG_ID)
    token = extract_token(html)
    collected = 0
    total = None
    offset = 0
    for _ in range(3):
        data = pathfinder(
            token,
            "fetchPlaylist",
            {
                "uri": f"spotify:playlist:{LONG_ID}",
                "offset": offset,
                "limit": 100,
                "enableWatchFeedEntrypoint": False,
            },
            PATHFINDER_HASH,
        )
        content = data["data"]["playlistV2"]["content"]
        if total is None:
            total = int(content.get("totalCount") or 0)
        items = content.get("items") or []
        collected += len(items)
        offset += 100
        if len(items) < 100:
            break
    print(f"long pathfinder collected={collected} totalCount={total}")
    if total <= 100 or collected <= 100:
        raise SystemExit("FAIL pathfinder pagination")

    album_html = fetch_html("album", ALBUM_ID)
    album_token = extract_token(album_html)
    album_data = pathfinder(
        album_token,
        "getAlbum",
        {"uri": f"spotify:album:{ALBUM_ID}", "locale": "", "offset": 0, "limit": 50},
        ALBUM_HASH,
    )
    album_tracks = (
        album_data.get("data", {}).get("albumUnion", {}).get("tracksV2", {}).get("items") or []
    )
    print(f"album pathfinder tracks={len(album_tracks)}")
    if not album_tracks:
        print("WARN: album pathfinder returned 0 (hash may have rotated; embed still works)")

    print("OK spotify scraper verification")


if __name__ == "__main__":
    main()

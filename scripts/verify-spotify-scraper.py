"""Verify Spotify embed + pathfinder pagination (no Android needed)."""
import json
import re
import sys
import urllib.request

sys.stdout.reconfigure(encoding="utf-8", errors="replace")

GOLDEN_ID = "3c6jpDUyKRyIoRoxdxqPQW"
LONG_ID = "5S8SJdl1BDc0ugpkEvFsIL"  # known >100; embed caps at 100
UA = (
    "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
)
PATHFINDER_HASH = "7982b11e21535cd2594badc40030b745671b61a1fa66766e569d45e6364f3422"
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
        raise SystemExit(f"FAIL {kind}/{sid}: no application/json script (html={len(html)})")
    data = json.loads(m.group(1))
    return data["props"]["pageProps"]["state"]["data"]["entity"]


def extract_token(html: str) -> str:
    m = re.search(r'"accessToken"\s*:\s*"([^"]+)"', html)
    if not m:
        raise SystemExit("FAIL: no accessToken in embed HTML")
    return m.group(1)


def pathfinder_page(token: str, playlist_id: str, offset: int, limit: int) -> dict:
    body = json.dumps(
        {
            "operationName": "fetchPlaylist",
            "variables": {
                "uri": f"spotify:playlist:{playlist_id}",
                "offset": offset,
                "limit": limit,
                "enableWatchFeedEntrypoint": False,
            },
            "extensions": {"persistedQuery": {"version": 1, "sha256Hash": PATHFINDER_HASH}},
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


def count_pathfinder_tracks(playlist_id: str, max_pages: int = 3) -> tuple[int, int]:
    """Return (collected, totalCount) for the first max_pages."""
    html = fetch_html("playlist", playlist_id)
    token = extract_token(html)
    collected = 0
    total = None
    offset = 0
    limit = 100
    for _ in range(max_pages):
        data = pathfinder_page(token, playlist_id, offset, limit)
        content = data["data"]["playlistV2"]["content"]
        if total is None:
            total = int(content.get("totalCount") or 0)
        items = content.get("items") or []
        if not items:
            break
        collected += sum(
            1
            for it in items
            if ((it.get("itemV2") or {}).get("data") or {}).get("__typename") == "Track"
        )
        offset += limit
        if len(items) < limit:
            break
        if total and offset >= total:
            break
    return collected, total or 0


def main() -> None:
    entity = fetch_entity("playlist", GOLDEN_ID)
    tracks = entity.get("trackList") or []
    name = entity.get("title") or entity.get("name")
    print(f"playlist name={name!r} tracks={len(tracks)} strategy=embed")
    if len(tracks) < 20:
        raise SystemExit(f"FAIL: expected dozens of tracks, got {len(tracks)}")
    sample = tracks[0]
    title = sample.get("title")
    artist = sample.get("subtitle")
    print(f"first={title!r} artist={artist!r}")
    if not title or not artist:
        raise SystemExit("FAIL: first track missing title/artist")

    track = fetch_entity("track", "3qT4bUD1MaWpGrTwcvguhb")
    print(f"track title={track.get('title')!r} artists={track.get('artists')}")

    album = fetch_entity("album", "4LH4d3cOWNNsVw41Gqt2kv")
    print(f"album title={album.get('title')!r} tracks={len(album.get('trackList') or [])}")

    embed_long = fetch_entity("playlist", LONG_ID)
    embed_n = len(embed_long.get("trackList") or [])
    print(f"long embed tracks={embed_n} (cap expected ~100)")
    if embed_n < 100:
        print("WARN: long playlist embed returned <100; still checking pathfinder")

    collected, total = count_pathfinder_tracks(LONG_ID, max_pages=3)
    print(f"long pathfinder pages<=3 collected={collected} totalCount={total}")
    if total <= 100:
        raise SystemExit(f"FAIL: expected totalCount>100, got {total}")
    if collected <= 100:
        raise SystemExit(f"FAIL: pathfinder did not page beyond embed cap ({collected})")

    print("OK golden playlist + track + album embeds parseable; pathfinder pages past 100")


if __name__ == "__main__":
    main()

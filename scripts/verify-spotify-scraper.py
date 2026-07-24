"""Verify Spotify embed scraper against the golden playlist (no Android needed)."""
import json
import re
import sys
import urllib.request

sys.stdout.reconfigure(encoding="utf-8", errors="replace")

GOLDEN_ID = "3c6jpDUyKRyIoRoxdxqPQW"
UA = (
    "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
)


def fetch_entity(kind: str, sid: str) -> dict:
    url = f"https://open.spotify.com/embed/{kind}/{sid}"
    req = urllib.request.Request(
        url,
        headers={"User-Agent": UA, "Accept": "text/html", "Accept-Language": "en-US,en;q=0.9"},
    )
    with urllib.request.urlopen(req, timeout=30) as resp:
        html = resp.read().decode("utf-8", "ignore")
    m = re.search(
        r'<script[^>]*type=["\']application/json["\'][^>]*>(.*?)</script>',
        html,
        re.S | re.I,
    )
    if not m:
        raise SystemExit(f"FAIL {kind}/{sid}: no application/json script (html={len(html)})")
    data = json.loads(m.group(1))
    entity = data["props"]["pageProps"]["state"]["data"]["entity"]
    return entity


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

    print("OK golden playlist + track + album embeds parseable")


if __name__ == "__main__":
    main()

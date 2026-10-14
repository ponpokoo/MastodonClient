"""Compare/update copied production sources without overwriting prototype experiments.

Run from any directory: python ui-prototype/import-production-ui.py --check | --diff | --refresh
Add a repository-relative source path with --add PATH, then import its dependencies explicitly.
"""
import argparse
import difflib
import hashlib
import json
from pathlib import Path

MODULE = Path(__file__).resolve().parent
ROOT = MODULE.parent
MANIFEST = MODULE / "production-sources.json"


def normalized(path):
    return path.read_text(encoding="utf-8").replace(
        "io.github.ponpokoo.mastodonclient.R", "io.github.ponpokoo.transitionprototype.R"
    )


def digest(text):
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


def destination(source):
    path = Path(source)
    if not source.startswith("app/src/main/") or ".." in path.parts:
        raise ValueError("Only app/src/main source paths can be imported")
    return MODULE / path.relative_to("app")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--production-root", type=Path, default=ROOT, metavar="DIRECTORY",
                        help="Read production sources from another checkout, such as main")
    parser.add_argument("--add", action="append", default=[], metavar="PATH")
    parser.add_argument("--record", action="append", default=[], metavar="PATH",
                        help="Record current upstream baseline after manually merging a copied file")
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--check", action="store_true")
    mode.add_argument("--diff", action="store_true")
    mode.add_argument("--refresh", action="store_true")
    args = parser.parse_args()
    production_root = args.production_root.resolve()
    if not (production_root / "app/src/main").is_dir():
        parser.error("Production root must contain app/src/main")
    entries = json.loads(MANIFEST.read_text(encoding="utf-8")) if MANIFEST.exists() else {}
    for source in args.record:
        destination(source)
        if source not in entries:
            raise SystemExit(f"Not imported: {source}")
        entries[source] = digest(normalized(production_root / source))
    for source in args.add:
        target = destination(source)
        if source in entries or target.exists():
            raise SystemExit(f"Already imported: {source}")
        original = normalized(production_root / source)
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(original, encoding="utf-8", newline="\n")
        entries[source] = digest(original)
    changed = []
    unresolved = []
    for source, baseline in sorted(entries.items()):
        original = normalized(production_root / source)
        target = destination(source)
        copied = target.read_text(encoding="utf-8") if target.exists() else ""
        upstream_changed = digest(original) != baseline
        adapted = digest(copied) != baseline
        if upstream_changed or adapted:
            print(f"{'UPSTREAM' if upstream_changed else '        '} {'ADAPTED' if adapted else '       '} {source}")
        if args.diff and copied != original:
            print("".join(difflib.unified_diff(original.splitlines(True), copied.splitlines(True),
                                             fromfile=source, tofile=str(target.relative_to(ROOT)))), end="")
        if upstream_changed:
            changed.append(source)
            if args.refresh and not adapted:
                target.write_text(original, encoding="utf-8", newline="\n")
                entries[source] = digest(original)
            elif args.refresh:
                unresolved.append(source)
                print("  Kept local changes. Merge the --diff output manually.")
    if args.add or args.refresh or args.record:
        MANIFEST.write_text(json.dumps(dict(sorted(entries.items())), indent=2) + "\n", encoding="utf-8")
    print(f"{len(entries)} imported files; {len(changed)} changed in production")
    return 1 if unresolved or (changed and not args.refresh) else 0


if __name__ == "__main__":
    raise SystemExit(main())

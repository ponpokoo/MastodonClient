import contextlib
import importlib.util
import io
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("import_ui", Path(__file__).resolve().parents[1] / "import-production-ui.py")
import_ui = importlib.util.module_from_spec(spec)
spec.loader.exec_module(import_ui)


class SourceImportTest(unittest.TestCase):
    def setUp(self):
        self.workspace = tempfile.TemporaryDirectory()
        self.addCleanup(self.workspace.cleanup)
        self.root = Path(self.workspace.name)
        self.module = self.root / "ui-prototype"
        self.module.mkdir()
        self.manifest = self.module / "production-sources.json"
        self.source = "app/src/main/java/example/Screen.kt"
        self.original = self.root / self.source
        self.original.parent.mkdir(parents=True)
        self.original.write_text("import io.github.ponpokoo.mastodonclient.R\nval label = 1\n", encoding="utf-8")
        self.copied = self.module / "src/main/java/example/Screen.kt"
        for name, value in (("ROOT", self.root), ("MODULE", self.module), ("MANIFEST", self.manifest)):
            patcher = patch.object(import_ui, name, value)
            patcher.start()
            self.addCleanup(patcher.stop)

    def run_import(self, *args):
        with patch.object(sys, "argv", ["import-production-ui.py", *args]), contextlib.redirect_stdout(io.StringIO()):
            return import_ui.main()

    def test_import_normalizes_resources_and_preserves_existing_files(self):
        self.assertEqual(0, self.run_import("--add", self.source))
        self.assertIn("transitionprototype.R", self.copied.read_text(encoding="utf-8"))
        self.copied.write_text("experiment\n", encoding="utf-8")
        with self.assertRaises(SystemExit):
            self.run_import("--add", self.source)
        self.assertEqual("experiment\n", self.copied.read_text(encoding="utf-8"))

    def test_refresh_updates_an_unmodified_copy(self):
        self.run_import("--add", self.source)
        self.original.write_text("updated\n", encoding="utf-8")
        self.assertEqual(1, self.run_import("--check"))
        self.assertEqual(0, self.run_import("--refresh"))
        self.assertEqual("updated\n", self.copied.read_text(encoding="utf-8"))
        self.assertEqual(0, self.run_import("--check"))

    def test_refresh_keeps_experiments_and_requires_manual_merge(self):
        self.run_import("--add", self.source)
        baseline = json.loads(self.manifest.read_text(encoding="utf-8"))
        self.copied.write_text("experiment\n", encoding="utf-8")
        self.original.write_text("production update\n", encoding="utf-8")
        self.assertEqual(1, self.run_import("--refresh"))
        self.assertEqual("experiment\n", self.copied.read_text(encoding="utf-8"))
        self.assertEqual(baseline, json.loads(self.manifest.read_text(encoding="utf-8")))
        self.copied.write_text("merged experiment\n", encoding="utf-8")
        self.assertEqual(0, self.run_import("--record", self.source))
        self.assertEqual("merged experiment\n", self.copied.read_text(encoding="utf-8"))
        self.assertEqual(0, self.run_import("--check"))

    def test_import_rejects_paths_outside_production_sources(self):
        for source in ("../Screen.kt", "app/src/main/../../Screen.kt", "ui-prototype/src/main/Screen.kt"):
            with self.assertRaises(ValueError):
                self.run_import("--add", source)


if __name__ == "__main__":
    unittest.main()

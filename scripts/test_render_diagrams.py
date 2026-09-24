#!/usr/bin/env python3
"""Tests for scripts/render-diagrams.

Run with:

    python3 scripts/test_render_diagrams.py
"""

import importlib.machinery
import importlib.util
import io
import pathlib
import sys
import tempfile
import unittest
from contextlib import redirect_stderr
from unittest.mock import patch

# Loading scripts/render-diagrams should not emit scripts/__pycache__/.
sys.dont_write_bytecode = True

SCRIPT_PATH = pathlib.Path(__file__).resolve().parent / "render-diagrams"


def load_render_diagrams():
    """Load scripts/render-diagrams as a module without running its CLI."""
    loader = importlib.machinery.SourceFileLoader(
        "render_diagrams_helper", str(SCRIPT_PATH)
    )
    spec = importlib.util.spec_from_loader("render_diagrams_helper", loader)
    module = importlib.util.module_from_spec(spec)
    loader.exec_module(module)
    return module


rd = load_render_diagrams()


class DiscoverDiagramsTests(unittest.TestCase):
    def test_finds_and_sorts_known_suffixes(self):
        with tempfile.TemporaryDirectory() as tmp:
            src = pathlib.Path(tmp)
            (src / "b.mmd").write_text("graph LR", encoding="utf-8")
            (src / "a.mermaid").write_text("graph LR", encoding="utf-8")
            (src / "notes.txt").write_text("ignore me", encoding="utf-8")

            found = rd.discover_diagrams(src)

            self.assertEqual([p.name for p in found], ["a.mermaid", "b.mmd"])

    def test_skips_output_directory(self):
        with tempfile.TemporaryDirectory() as tmp:
            src = pathlib.Path(tmp)
            (src / "real.mmd").write_text("graph LR", encoding="utf-8")
            out = src / "out"
            out.mkdir()
            (out / "stale.mmd").write_text("graph LR", encoding="utf-8")

            found = rd.discover_diagrams(src, out)

            self.assertEqual([p.name for p in found], ["real.mmd"])

    def test_missing_directory_returns_empty(self):
        self.assertEqual(rd.discover_diagrams(pathlib.Path("/no/such/dir")), [])


class FilterDiagramsTests(unittest.TestCase):
    def test_none_keeps_everything(self):
        diagrams = [pathlib.Path("a.mmd"), pathlib.Path("b.mmd")]
        self.assertEqual(rd.filter_diagrams(diagrams, None), diagrams)

    def test_keeps_only_requested_stems(self):
        a = pathlib.Path("a.mmd")
        b = pathlib.Path("b.mmd")
        self.assertEqual(rd.filter_diagrams([a, b], ["b"]), [b])


class BuildHtmlTests(unittest.TestCase):
    def test_embeds_source_and_mermaid_version(self):
        with tempfile.TemporaryDirectory() as tmp:
            src = pathlib.Path(tmp)
            diagram = src / "flow.mmd"
            diagram.write_text("flowchart LR\n  a --> b\n", encoding="utf-8")

            page = rd.build_html([diagram], src, mermaid_version="11")

            self.assertIn('class="mermaid"', page)
            self.assertIn("flow.mmd", page)
            self.assertIn("a --&gt; b", page)
            self.assertIn("mermaid@11/dist/mermaid.esm.min.mjs", page)

    def test_reports_when_empty(self):
        page = rd.build_html([], pathlib.Path("."))
        self.assertIn("No diagrams found", page)


class RenderTests(unittest.TestCase):
    def test_html_writes_index(self):
        with tempfile.TemporaryDirectory() as tmp:
            src = pathlib.Path(tmp) / "src"
            src.mkdir()
            (src / "flow.mmd").write_text("graph LR", encoding="utf-8")
            out = pathlib.Path(tmp) / "out"

            produced = rd.render(
                rd.discover_diagrams(src, out), src, out, mode="html"
            )

            self.assertEqual([p.name for p in produced], ["index.html"])
            self.assertTrue(produced[0].read_text(encoding="utf-8").startswith("<!DOCTYPE html>"))

    def test_svg_without_mmdc_raises(self):
        with tempfile.TemporaryDirectory() as tmp:
            src = pathlib.Path(tmp) / "src"
            src.mkdir()
            (src / "flow.mmd").write_text("graph LR", encoding="utf-8")

            with patch.object(rd.shutil, "which", return_value=None):
                with self.assertRaises(rd.RenderError):
                    rd.render(
                        rd.discover_diagrams(src),
                        src,
                        pathlib.Path(tmp) / "out",
                        mode="svg",
                    )


class MainTests(unittest.TestCase):
    def test_missing_sources_exit_code(self):
        with tempfile.TemporaryDirectory() as tmp:
            stderr = io.StringIO()
            with redirect_stderr(stderr):
                code = rd.main(["--src", str(pathlib.Path(tmp) / "nope")])

            self.assertEqual(code, 1)
            self.assertIn("No diagrams found", stderr.getvalue())


if __name__ == "__main__":
    unittest.main()

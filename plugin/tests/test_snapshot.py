import json
import os
import sys
import time
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

from snapshot import Debouncer, TextCache, encode_message, tab_from_view_data, tab_title, window_name  # noqa: E402


class TitleTest(unittest.TestCase):
    def test_title_rules(self):
        self.assertEqual(tab_title("named", "/a/b.txt", "x"), "named")
        self.assertEqual(tab_title("", "/a/b.txt", "x"), "b.txt")
        self.assertEqual(tab_title("", None, "\n  \n  hola \nmundo"), "hola")
        self.assertEqual(tab_title("", None, "x" * 100), "x" * 80)
        self.assertEqual(tab_title("", None, "   "), "untitled")


class WindowNameTest(unittest.TestCase):
    def test_window_name(self):
        self.assertEqual(window_name("/a/web.sublime-project", ["/a"]), "web")
        self.assertEqual(window_name(None, ["/home/user/notas/"]), "notas")
        self.assertEqual(window_name(None, []), "")


class EncodeTest(unittest.TestCase):
    def test_encode_unicode(self):
        tab = tab_from_view_data(1, 2, 0, "", None, True, "Plain Text", "ñ 🙂")
        raw = encode_message([tab])
        self.assertTrue(raw.endswith(b"\n"))
        self.assertIn("ñ 🙂".encode("utf-8"), raw)
        msg = json.loads(raw.decode("utf-8"))
        self.assertEqual(msg["type"], "snapshot")
        self.assertEqual(msg["tabs"][0]["path"], None)
        self.assertEqual(msg["tabs"][0]["title"], "ñ 🙂")


class DebouncerTest(unittest.TestCase):
    def test_debouncer_coalesces(self):
        calls = []
        d = Debouncer(0.1, lambda: calls.append(1))
        for _ in range(5):
            d.trigger()
            time.sleep(0.02)
        time.sleep(0.3)
        self.assertEqual(len(calls), 1)


class TextCacheTest(unittest.TestCase):
    def test_rereads_only_changed_views(self):
        reads = []
        c = TextCache()

        def read(v):
            reads.append(v)
            return v

        c.get(1, 10, lambda: read("a"))
        self.assertEqual(c.get(1, 10, lambda: read("b")), "a")
        self.assertEqual(c.get(1, 11, lambda: read("c")), "c")
        self.assertEqual(reads, ["a", "c"])
        c.retain(set())
        self.assertEqual(c.get(1, 11, lambda: read("d")), "d")


if __name__ == "__main__":
    unittest.main()

import os
import sys
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

from commands import close_decision, draft_title, plan_draft, text_hash  # noqa: E402


class DraftTitleTest(unittest.TestCase):
    def test_draft_title_numbering(self):
        self.assertEqual(draft_title("notas", []), "✎ notas")
        self.assertEqual(draft_title("notas", ["✎ notas"]), "✎ notas (2)")
        self.assertEqual(draft_title("notas", ["✎ notas", "✎ notas (2)"]), "✎ notas (3)")
        self.assertEqual(draft_title("✎ notas", []), "✎ notas")  # already a draft title
        self.assertEqual(draft_title("", []), "✎ untitled")


class PlanDraftTest(unittest.TestCase):
    def test_plan_draft(self):
        h = text_hash("a")
        self.assertEqual(plan_draft(None, 1), "create")
        self.assertEqual(plan_draft((h, 1, h), 2), "update")
        self.assertEqual(plan_draft((h, 3, h), 2), "skip")
        self.assertEqual(plan_draft((h, 2, h), 2), "skip")
        self.assertEqual(plan_draft((h, 1, text_hash("changed on the Mac")), 2), "conflict")

    def test_conflict_opens_numbered_copy(self):
        h = text_hash("phone text")
        state = (h, 1, text_hash("phone text + Mac edit"))
        self.assertEqual(plan_draft(state, 2), "conflict")
        self.assertEqual(draft_title("notas", ["✎ notas"]), "✎ notas (2)")


class CloseRuleTest(unittest.TestCase):
    def test_close_rule(self):
        h = text_hash("x")
        # saved file without changes
        self.assertEqual(close_decision(False, True, False, h, None, False), (True, ""))
        # unsaved changes in a file
        ok, reason = close_decision(True, True, False, h, None, False)
        self.assertFalse(ok)
        self.assertIn("unsaved", reason)
        # untitled with text, not a draft
        self.assertFalse(close_decision(True, False, False, h, None, False)[0])
        # empty untitled tab
        self.assertTrue(close_decision(False, False, False, text_hash(""), None, True)[0])
        # draft unchanged since last sync
        self.assertTrue(close_decision(True, False, True, h, h, False)[0])
        # draft edited on the Mac after the sync
        ok, reason = close_decision(True, False, True, text_hash("y"), h, False)
        self.assertFalse(ok)
        self.assertIn("changed", reason)


if __name__ == "__main__":
    unittest.main()

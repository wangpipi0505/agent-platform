import unittest
from unittest.mock import Mock
from republish_demo import prepare, publish


class DemoPublicationTest(unittest.TestCase):
    def test_prepare_keeps_the_seed_prompt_and_freezes_the_saved_revision(self):
        call = Mock(side_effect=[
            {"type": "CHAT", "draftRev": 1, "config": {"systemPrompt": "保留", "maxTurns": 200,
                                                       "modelBinding": {"modelName": "demo-model"}}},
            {"draftRev": 2}, {"draftRev": 3, "modelBindingRefs": [{"bindingId": 8}]}])
        self.assertEqual(prepare(call, 7)["draftRev"], 3)
        body = call.call_args_list[1].args[2]
        self.assertEqual(body["config"], {"systemPrompt": "保留", "context": {"maxTurns": 200},
                                          "modelBinding": {"modelEntryId": 7}})
        call.assert_called_with("POST", "/model-bindings/refreeze", {"draftRev": 2, "requestId": "r44-2-7"})

    def test_publishes_only_the_requested_revision_with_passing_evidence(self):
        check = {"draftRev": 3, "candidateSha256": "a" * 64, "passed": True, "precheckId": "p1"}
        for blocked in (dict(check, passed=False), dict(check, draftRev=4),
                        dict(check, environmentConfirmationRequired=True)):
            call = Mock(return_value=blocked)
            with self.assertRaises((RuntimeError, ValueError)):
                publish(call, 3)
            self.assertEqual(call.call_count, 1)
        call = Mock(side_effect=[check, {"versionNo": 2}])
        self.assertEqual(publish(call, 3)["versionNo"], 2)
        self.assertEqual(call.call_args.args[2]["candidateSha256"], check["candidateSha256"])

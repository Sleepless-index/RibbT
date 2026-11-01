import os
import sys
import unittest
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))


class AccessTokenTests(unittest.TestCase):
    def client(self, token):
        from mediadl import create_app
        env = {"RIBBT_TOKEN": token} if token else {"RIBBT_TOKEN": ""}
        with mock.patch.dict(os.environ, env):
            return create_app().test_client()

    def test_every_request_needs_the_token_when_one_is_set(self):
        client = self.client("s3cret-token")
        self.assertEqual(client.get("/api/platforms").status_code, 401)
        self.assertEqual(client.get("/api/platforms", headers={"X-RibbT-Token": "wrong"}).status_code, 401)
        self.assertEqual(client.get("/api/platforms", headers={"X-RibbT-Token": "s3cret-token"}).status_code, 200)

    def test_links_in_previews_can_carry_it_in_the_query(self):
        client = self.client("s3cret-token")
        self.assertEqual(client.get("/api/platforms?k=s3cret-token").status_code, 200)
        self.assertEqual(client.get("/api/platforms?k=nope").status_code, 401)
        self.assertEqual(client.post("/api/cookies", data="x").status_code, 401)

    def test_no_token_set_means_no_check(self):
        self.assertEqual(self.client("").get("/api/platforms").status_code, 200)

    def test_the_servers_own_job_requests_carry_the_token(self):
        from mediadl.services import jobs
        job = mock.Mock(cookie="mdl_settings=x")
        with mock.patch.dict(os.environ, {"RIBBT_TOKEN": "abc"}):
            self.assertEqual(jobs.internal_headers(job), {"Cookie": "mdl_settings=x", "X-RibbT-Token": "abc"})
        with mock.patch.dict(os.environ, {"RIBBT_TOKEN": ""}):
            self.assertEqual(jobs.internal_headers(job), {"Cookie": "mdl_settings=x"})

    def test_a_job_started_with_the_token_can_run_its_own_request(self):
        """The job's internal GET goes through the same check: it must not be rejected as unauthorized."""
        from mediadl import create_app
        from mediadl.services import jobs
        with mock.patch.dict(os.environ, {"RIBBT_TOKEN": "abc"}):
            app = create_app()
            job = mock.Mock(cookie="")
            response = app.test_client().get("/api/media?u=http://x", headers=jobs.internal_headers(job))
        self.assertNotEqual(response.status_code, 401)  # refused for its own reason (not https), not for the token


if __name__ == "__main__":
    unittest.main()

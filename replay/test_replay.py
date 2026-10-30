import unittest

from replay import schedule, to_issue


class MappingTest(unittest.TestCase):
    def test_nyc311_record(self):
        issue = to_issue("nyc311", {
            "unique_key": "70919536", "created_date": "2026-10-29T02:38:41.000", "agency": "DOT",
            "complaint_type": "Street Condition", "descriptor": "Pothole", "borough": "MANHATTAN",
            "incident_zip": "10029"})
        self.assertEqual(issue["externalId"], "70919536")
        self.assertEqual(issue["title"], "Pothole")
        self.assertEqual(issue["category"], "Street Condition")
        self.assertEqual(issue["attributes"], {"agency": "DOT", "borough": "MANHATTAN", "zip": "10029"})
        self.assertTrue(issue["sourceCreatedAt"].startswith("2026-10-29T02:38:41"))

    def test_cfpb_record(self):
        issue = to_issue("cfpb", {
            "complaint_id": "31385786", "date_received": "2026-10-30T00:52:43.000Z",
            "product": "Credit reporting or other personal consumer reports",
            "issue": "Incorrect information on your report", "sub_issue": "Information belongs to someone else",
            "company": "Experian Information Solutions Inc.", "state": "WA", "sub_product": None})
        self.assertEqual(issue["title"], "Incorrect information on your report: Information belongs to someone else")
        self.assertEqual(issue["category"], "Credit reporting or other personal consumer reports")
        self.assertNotIn("subProduct", issue["attributes"])


class ScheduleTest(unittest.TestCase):
    def issues(self, *times):
        return [{"externalId": str(n), "sourceCreatedAt": t} for n, t in enumerate(times)]

    def test_sorted_and_scaled(self):
        plan = schedule(self.issues("2026-01-01T00:01:40+00:00", "2026-01-01T00:00:00+00:00"), speed=10)
        self.assertEqual([offset for offset, _ in plan], [0.0, 10.0])
        self.assertEqual(plan[0][1]["externalId"], "1")

    def test_max_gap(self):
        plan = schedule(self.issues("2026-01-01T00:00:00+00:00", "2026-01-01T01:00:00+00:00",
                                    "2026-01-01T01:00:01+00:00"), speed=1, max_gap=5)
        self.assertEqual([offset for offset, _ in plan], [0.0, 5.0, 6.0])


if __name__ == "__main__":
    unittest.main()

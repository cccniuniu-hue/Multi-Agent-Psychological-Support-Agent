import os
import unittest
from unittest.mock import patch

from contract import ContractError, load_settings, validate_filename, validate_size


class ContractTests(unittest.TestCase):
    def test_accepts_pdf_and_docx(self):
        self.assertEqual(validate_filename("guide.PDF"), ("guide.PDF", ".pdf"))
        self.assertEqual(validate_filename("notes.docx"), ("notes.docx", ".docx"))

    def test_rejects_unsupported_and_oversized_files(self):
        with self.assertRaisesRegex(ContractError, "unsupported_file_type") as caught:
            validate_filename("notes.txt")
        self.assertEqual(caught.exception.status_code, 415)
        self.assertEqual(caught.exception.body(), {
            "success": False,
            "error": {"code": "unsupported_file_type", "message": "仅支持 PDF 和 DOCX 文件"},
        })
        with self.assertRaisesRegex(ContractError, "file_too_large"):
            validate_size(11, 10)

    def test_loads_size_and_timeout_limits_from_environment(self):
        with patch.dict(os.environ, {
            "MARKER_MAX_FILE_BYTES": "2048",
            "MARKER_TIMEOUT_SECONDS": "30",
        }):
            settings = load_settings()

        self.assertEqual(settings.max_file_bytes, 2048)
        self.assertEqual(settings.timeout_seconds, 30.0)


if __name__ == "__main__":
    unittest.main()

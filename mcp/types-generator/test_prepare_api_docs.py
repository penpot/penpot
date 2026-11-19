import unittest

from prepare_api_docs import PenpotAPIContentMarkdownConverter


class PenpotAPIContentMarkdownConverterTest(unittest.TestCase):
    def test_preserves_line_breaks_in_code_examples(self):
        html = (
            '<pre><code>'
            'const source = await penpot.library.connectLibrary("library-id");<br>'
            'penpot.library.tokensSource = source;<br>'
            '// Restore local tokens:<br>'
            'penpot.library.tokensSource = penpot.library.local;'
            '</code><button>Copy</button></pre>'
        )

        result = PenpotAPIContentMarkdownConverter().convert(html).strip()

        self.assertEqual(
            result,
            '```\n'
            'const source = await penpot.library.connectLibrary("library-id");\n'
            'penpot.library.tokensSource = source;\n'
            '// Restore local tokens:\n'
            'penpot.library.tokensSource = penpot.library.local;\n'
            '```',
        )


if __name__ == '__main__':
    unittest.main()

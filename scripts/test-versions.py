import importlib.util
import unittest

spec = importlib.util.spec_from_file_location('versions', 'scripts/resolve-versions.py')
v = importlib.util.module_from_spec(spec)
spec.loader.exec_module(v)

class VersionRangeTest(unittest.TestCase):
    def test_ranges(self):
        for version in ['1.20', '1.20.6', '1.21', '1.21.11', '26.1', '26.2', '26.3', '26.3.1']:
            self.assertTrue(v.in_range('fabric', version))
        self.assertFalse(v.in_range('neoforge', '1.20.6'))
        self.assertTrue(v.in_range('neoforge', '1.21'))
        self.assertTrue(v.in_range('forge', '1.21.11'))
        for version in ['26.4', '26.3-pre1', '1.19.4', '1.21-snapshot', '27.1']:
            self.assertFalse(v.in_range('fabric', version))
        self.assertFalse(v.in_range('forge', '26.1'))
    def test_java_boundary(self):
        self.assertEqual('17', v.row('fabric', '1.20.4', 'x')['java'])
        self.assertEqual('21', v.row('fabric', '1.20.5', 'x')['java'])
        self.assertEqual('25', v.row('fabric', '26.3', 'x')['java'])

if __name__ == '__main__':
    unittest.main()

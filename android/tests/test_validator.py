import importlib.util
import json
import tempfile
import unittest
from pathlib import Path

spec=importlib.util.spec_from_file_location('validator',Path(__file__).parents[1]/'tools/validate_dataset.py')
validator=importlib.util.module_from_spec(spec);spec.loader.exec_module(validator)

class ValidatorTests(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory();self.folder=Path(self.tmp.name)
        (self.folder/'video.mp4').write_bytes(b'Synthetic fixture, not a movie')
        (self.folder/'frames.csv').write_text('index,encoded_pts_us,mux_pts_us,callback_elapsed_ns,bytes,flags\n0,1000000,0,1000000000,100,1\n1,1008333,8333,1008333000,100,0\n2,1016666,16666,1016666000,100,0\n')
        for name,units in [('accelerometer','x_m_s2,y_m_s2,z_m_s2'),('gyroscope','x_rad_s,y_rad_s,z_rad_s')]:
            (self.folder/(name+'.csv')).write_text('timestamp_ns,'+units+',callback_elapsed_ns\n1000000000,0,0,1,1000000001\n1005000000,0,0,1,1005000001\n1010000000,0,0,1,1010000001\n')
        for name in ['capture_results','events','intrinsics']:(self.folder/(name+'.csv')).write_text('header\n')
        self.m=dict(schema='poco-recorder-1',status='finalized',completion_clean=True,errors=[],
                    first_encoded_pts_us=1000000,fps_requested=120,width=1920,height=1080,
                    camera_timestamp_source=1,stop_reason='user stopped',video=dict(count=3,measured_hz=2e6/16666),
                    accelerometer=dict(count=3,measured_hz=200),gyroscope=dict(count=3,measured_hz=200))
        self.save()
    def save(self):(self.folder/'manifest.json').write_text(json.dumps(self.m))
    def tearDown(self):self.tmp.cleanup()
    def test_different_rates(self):
        r=validator.validate(self.folder);self.assertAlmostEqual(r['streams']['video']['measured_hz'],120,delta=.01)
        self.assertEqual(r['streams']['accelerometer']['measured_hz'],200)
    def test_incomplete(self):
        self.m['status']='interrupted';self.save()
        with self.assertRaisesRegex(ValueError,'Incomplete'):validator.validate(self.folder)
    def test_sidecar_missing(self):
        (self.folder/'events.csv').unlink()
        with self.assertRaisesRegex(ValueError,'Missing'):validator.validate(self.folder)
    def test_counts(self):
        self.m['gyroscope']['count']=4;self.save()
        with self.assertRaisesRegex(ValueError,'count mismatch'):validator.validate(self.folder)
    def test_duplicate_timestamp(self):
        p=self.folder/'accelerometer.csv';p.write_text(p.read_text().replace('1005000000,','1000000000,'))
        with self.assertRaisesRegex(ValueError,'nonmonotonic'):validator.validate(self.folder)
    def test_bad_mapping(self):
        p=self.folder/'frames.csv';p.write_text(p.read_text().replace('1,1008333,8333,','1,1008333,0,'))
        with self.assertRaisesRegex(ValueError,'mapping'):validator.validate(self.folder)
    def test_nonfinite(self):
        p=self.folder/'gyroscope.csv';p.write_text(p.read_text().replace(',0,0,1,',',nan,0,1,'))
        with self.assertRaisesRegex(ValueError,'nonfinite'):validator.validate(self.folder)
    def test_unknown_clock(self):
        self.m['camera_timestamp_source']=0;self.save()
        self.assertTrue(any('UNKNOWN' in w for w in validator.validate(self.folder)['warnings']))

if __name__=='__main__':unittest.main()

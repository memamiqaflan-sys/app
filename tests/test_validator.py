import csv
import importlib.util
import json
import tempfile
import unittest
from pathlib import Path

spec = importlib.util.spec_from_file_location('validator', Path(__file__).parents[1] / 'tools/validate_dataset.py')
validator = importlib.util.module_from_spec(spec)
spec.loader.exec_module(validator)

class ValidationTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.folder = Path(self.temp.name)
        self.manifest = dict(status='finalized', log_errors=[], frames_appended=240,
            accelerometer_samples=101, gyroscope_samples=101,
            first_video_pts_value=24000, first_video_pts_timescale=240,
            fps_requested=240, imu_hz_requested=1000, drops=0, width=1920, height=1080,
            stop_reason='user stopped')
        self.save_manifest()
        (self.folder/'video.mov').write_bytes(b'Synthetic fixture, not a real movie')
        with (self.folder/'frames.csv').open('w') as f:
            f.write('index,pts_value,pts_timescale,host_s,video_s\n')
            for i in range(240): f.write(f'{i},{24000+i},240,{100+i/240},{i/240}\n')
        for name, cols in [('accelerometer', 'ax_g,ay_g,az_g'), ('gyroscope', 'gx_rad_s,gy_rad_s,gz_rad_s')]:
            (self.folder/(name+'.csv')).write_text('timestamp_s,'+cols+'\n'+''.join(f'{100+i/100},0,0,1\n' for i in range(101)))
        (self.folder/'drops.csv').write_text('pts_value,pts_timescale,callback_host_s,reason\n')
    def tearDown(self): self.temp.cleanup()
    def save_manifest(self): (self.folder/'manifest.json').write_text(json.dumps(self.manifest))
    def test_distinct_camera_and_imu_rates(self):
        result = validator.validate(self.folder)
        self.assertAlmostEqual(result['streams']['video']['measured_hz'], 240)
        self.assertAlmostEqual(result['streams']['accelerometer']['measured_hz'], 100)
        self.assertEqual(result['streams']['accelerometer']['gaps_over_1_5_observed_periods'], 0)
    def test_count_mismatch(self):
        self.manifest['frames_appended'] = 241; self.save_manifest()
        with self.assertRaisesRegex(ValueError, 'count mismatch'): validator.validate(self.folder)
    def test_partial_recording(self):
        self.manifest['status'] = 'finalized_with_errors'; self.save_manifest()
        with self.assertRaisesRegex(ValueError, 'Incomplete'): validator.validate(self.folder)
    def test_duplicate_sensor_timestamp(self):
        p=self.folder/'gyroscope.csv';p.write_text(p.read_text().replace('100.01,','100.0,'))
        with self.assertRaisesRegex(ValueError, 'nonmonotonic'): validator.validate(self.folder)
    def test_nonfinite_sensor(self):
        p=self.folder/'accelerometer.csv';p.write_text(p.read_text().replace('100.01,0,','100.01,nan,'))
        with self.assertRaisesRegex(ValueError, 'nonfinite'): validator.validate(self.folder)
    def test_relative_pts_corruption(self):
        p=self.folder/'frames.csv'
        with p.open() as f: rows=list(csv.DictReader(f))
        rows[10]['video_s']='999'
        with p.open('w',newline='') as f:
            w=csv.DictWriter(f,fieldnames=list(rows[0]));w.writeheader();w.writerows(rows)
        with self.assertRaisesRegex(ValueError, 'PTS mismatch'): validator.validate(self.folder)
    def test_missing_video(self):
        (self.folder/'video.mov').unlink()
        with self.assertRaisesRegex(ValueError, 'Missing'): validator.validate(self.folder)
    def test_dropped_frame_count_mismatch(self):
        self.manifest['drops']=1;self.save_manifest()
        with self.assertRaisesRegex(ValueError,'Drop count'):validator.validate(self.folder)

if __name__ == '__main__': unittest.main()

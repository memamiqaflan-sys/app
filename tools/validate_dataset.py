#!/usr/bin/env python3
"""Validate recorded files without modifying or resampling them. Standard library only."""
import argparse
import csv
import json
import math
import subprocess
from fractions import Fraction
from pathlib import Path


def scan(path, key, vector_keys=()):
    count = 0
    first = last = None
    max_gap = 0.0
    with path.open(newline='') as file:
        for row in csv.DictReader(file):
            time = float(row[key])
            if not all(math.isfinite(float(row[k])) for k in (key, *vector_keys)):
                raise ValueError(f'{path.name}: nonfinite measurement at row {count}')
            if last is not None:
                if time <= last:
                    raise ValueError(f'{path.name}: nonmonotonic timestamp at row {count}')
                max_gap = max(max_gap, time - last)
            if first is None:
                first = time
            last = time
            count += 1
    if count < 2:
        raise ValueError(f'{path.name}: fewer than two samples')
    hz = (count - 1) / (last - first)
    # Compare intervals against the observed mean rate, not the possibly capped request.
    gaps = 0
    previous = None
    with path.open(newline='') as file:
        for row in csv.DictReader(file):
            time = float(row[key])
            if previous is not None and time - previous > 1.5 / hz:
                gaps += 1
            previous = time
    return dict(count=count, first_s=first, last_s=last, measured_hz=hz,
                max_gap_s=max_gap, gaps_over_1_5_observed_periods=gaps)


def validate(folder, decode=False):
    m = json.loads((folder / 'manifest.json').read_text())
    if m.get('status') != 'finalized' or m.get('log_errors'):
        raise ValueError(f'Incomplete recording: status={m.get("status")}, errors={m.get("log_errors")}')
    if m.get('schema_version', 2) >= 3:
        if m.get('completion_clean') is not True:
            raise ValueError('Incomplete dataset: completion_clean is not true')
        for name in ['frames', 'accelerometer', 'gyroscope', 'drops', 'errors', 'intrinsics', 'telemetry']:
            path = folder / (name + '.csv')
            if not path.is_file() or not path.stat().st_size:
                raise ValueError(f'Missing or empty {name}.csv')
        for name in ['frames', 'accelerometer', 'gyroscope']:
            with (folder / (name + '.csv')).open(newline='') as file:
                for index, row in enumerate(csv.DictReader(file)):
                    arrival = float(row['callback_host_s'])
                    if not math.isfinite(arrival):
                        raise ValueError(f'{name}: nonfinite callback timestamp at row {index}')
    video = folder / 'video.mov'
    if not video.is_file() or video.stat().st_size == 0:
        raise ValueError('Missing or empty video.mov')
    results = {
        'video': scan(folder / 'frames.csv', 'host_s'),
        'accelerometer': scan(folder / 'accelerometer.csv', 'timestamp_s', ('ax_g', 'ay_g', 'az_g')),
        'gyroscope': scan(folder / 'gyroscope.csv', 'timestamp_s', ('gx_rad_s', 'gy_rad_s', 'gz_rad_s')),
    }
    for name, field in [('video', 'frames_appended'), ('accelerometer', 'accelerometer_samples'), ('gyroscope', 'gyroscope_samples')]:
        if results[name]['count'] != m[field]:
            raise ValueError(f'{name}: manifest/CSV count mismatch')
    def rational(value, scale):
        if int(scale) <= 0:
            raise ValueError('Invalid video PTS timescale')
        return Fraction(int(value), int(scale))
    origin = rational(m['first_video_pts_value'], m['first_video_pts_timescale'])
    previous_pts = None
    with (folder / 'frames.csv').open(newline='') as file:
        for index, row in enumerate(csv.DictReader(file)):
            if int(row['index']) != index:
                raise ValueError('Nonconsecutive frame indices')
            pts = rational(row['pts_value'], row['pts_timescale'])
            relative = float(row['video_s'])
            if pts < origin or not math.isfinite(relative) or abs(float(pts - origin) - relative) > 1e-6:
                raise ValueError('Relative video PTS mismatch')
            if previous_pts is not None and pts <= previous_pts:
                raise ValueError('Nonmonotonic source video PTS')
            previous_pts = pts
    with (folder / 'drops.csv').open(newline='') as file:
        drops = sum(1 for _ in csv.DictReader(file))
    if drops != m['drops']:
        raise ValueError('Drop count mismatch')
    warnings = []
    v = results['video']
    if abs(v['measured_hz'] / m['fps_requested'] - 1) > .05:
        warnings.append('Video average differs from requested fps by more than 5%.')
    for name in ['accelerometer', 'gyroscope']:
        data = results[name]
        if data['first_s'] > v['first_s'] or data['last_s'] < v['last_s']:
            warnings.append(f'{name}: does not fully bracket video; use common coverage later.')
    for name, data in results.items():
        if data['gaps_over_1_5_observed_periods']:
            warnings.append(f'{name}: timestamp gaps detected; inspect before analysis.')
    if drops:
        warnings.append(f'{drops} video drops logged.')
    for name in ['accelerometer', 'gyroscope']:
        if abs(results[name]['measured_hz'] / m['imu_hz_requested'] - 1) > .05:
            warnings.append(f'{name}: measured rate differs from requested; API request is not a delivered-rate guarantee.')
    if decode:
        command = ['ffprobe', '-v', 'error', '-select_streams', 'v:0', '-count_frames',
                   '-show_entries', 'stream=nb_read_frames,width,height,codec_name', '-of', 'json', str(video)]
        result = subprocess.run(command, check=True, capture_output=True, text=True)
        streams = json.loads(result.stdout)['streams']
        if not streams or int(streams[0]['nb_read_frames']) != v['count']:
            raise ValueError('Decoded video frame count differs from CSV')
        if streams[0]['width'] != m['width'] or streams[0]['height'] != m['height']:
            raise ValueError('Decoded dimensions differ from manifest')
    return {'stop_reason': m.get('stop_reason'), 'streams': results, 'logged_drops': drops,
            'decoded_video_checked': decode, 'warnings': warnings,
            'note': 'Checks file integrity and timing consistency, not physical synchronization accuracy.'}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('folder', type=Path)
    parser.add_argument('--decode-video', action='store_true', help='Requires ffprobe on PATH; counts decoded frames')
    args = parser.parse_args()
    try:
        print(json.dumps(validate(args.folder, args.decode_video), indent=2))
    except (OSError, ValueError, KeyError, ZeroDivisionError, subprocess.CalledProcessError) as error:
        parser.exit(1, f'FAILED: {error}\n')


if __name__ == '__main__':
    main()

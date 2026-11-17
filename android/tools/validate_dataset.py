#!/usr/bin/env python3
"""Read-only validation of POCO Research Recorder exports; no motion reconstruction."""
import argparse
import csv
import json
import math
import subprocess
from pathlib import Path


def scan(path, time_key, vectors=()):
    count = 0
    first = previous = None
    max_gap = 0
    with path.open(newline='') as f:
        for row in csv.DictReader(f):
            timestamp = int(row[time_key])
            if timestamp < 0 or previous is not None and timestamp <= previous:
                raise ValueError(f'{path.name}: invalid/nonmonotonic timestamp at {count}')
            if not all(math.isfinite(float(row[k])) for k in vectors):
                raise ValueError(f'{path.name}: nonfinite measurement')
            if int(row['callback_elapsed_ns']) < 0:
                raise ValueError(f'{path.name}: invalid callback time')
            if previous is None:
                first = timestamp
            else:
                max_gap = max(max_gap, timestamp - previous)
            previous = timestamp
            count += 1
    if count < 2:
        raise ValueError(f'{path.name}: fewer than two samples')
    unit = 1000 if time_key == 'encoded_pts_us' else 1
    hz = (count - 1) * 1e9 / ((previous - first) * unit)
    gaps = 0
    last = None
    with path.open(newline='') as f:
        for row in csv.DictReader(f):
            t = int(row[time_key])
            if last is not None and (t-last)*unit > 1.5e9/hz:
                gaps += 1
            last = t
    return dict(count=count, first_ns=first*unit, last_ns=previous*unit,
                measured_hz=hz, max_gap_ns=max_gap*unit, gaps_over_observed_period=gaps)


def validate(folder, decode=False):
    m = json.loads((folder/'manifest.json').read_text())
    if m.get('schema') != 'poco-recorder-1':
        raise ValueError('Wrong dataset schema; use the matching iOS or Android validator')
    if m.get('status') != 'finalized' or m.get('completion_clean') is not True or m.get('errors'):
        raise ValueError(f'Incomplete dataset: {m.get("status")}, {m.get("errors")}')
    for name in ['video.mp4', 'frames.csv', 'accelerometer.csv', 'gyroscope.csv',
                 'capture_results.csv', 'events.csv', 'intrinsics.csv']:
        if not (folder/name).is_file() or not (folder/name).stat().st_size:
            raise ValueError(f'Missing or empty {name}')
    streams = {
        'video': scan(folder/'frames.csv', 'encoded_pts_us'),
        'accelerometer': scan(folder/'accelerometer.csv', 'timestamp_ns', ('x_m_s2','y_m_s2','z_m_s2')),
        'gyroscope': scan(folder/'gyroscope.csv', 'timestamp_ns', ('x_rad_s','y_rad_s','z_rad_s')),
    }
    for name, stats in streams.items():
        if stats['count'] != m[name]['count']:
            raise ValueError(f'{name}: manifest/CSV count mismatch')
        if abs(stats['measured_hz']-m[name]['measured_hz']) > .001:
            raise ValueError(f'{name}: manifest/CSV rate mismatch')
    origin = int(m['first_encoded_pts_us'])
    with (folder/'frames.csv').open(newline='') as f:
        for i,row in enumerate(csv.DictReader(f)):
            if int(row['index']) != i or int(row['encoded_pts_us'])-origin != int(row['mux_pts_us']):
                raise ValueError('Frame index/PTS mapping mismatch')
            if int(row['mux_pts_us']) < 0 or int(row['bytes']) <= 0:
                raise ValueError('Invalid encoded frame')
    warnings=[]
    if m.get('camera_timestamp_source') != 1:
        warnings.append('Camera timestamp source is UNKNOWN; camera/IMU clock alignment needs calibration.')
    warnings.append('Encoder PTS and Camera2 timestamps are separate records. Verify their relationship; do not align by callback time or row index.')
    for name, stats in streams.items():
        if stats['gaps_over_observed_period']:
            warnings.append(f'{name}: timestamp gaps detected')
    if abs(streams['video']['measured_hz']/m['fps_requested']-1) > .05:
        warnings.append('Measured video fps differs from request by over 5%')
    if m.get('capture_failures',0):
        warnings.append(f'{m["capture_failures"]} camera capture failures logged')
    if decode:
        out=subprocess.run(['ffprobe','-v','error','-select_streams','v:0','-count_frames',
                            '-show_entries','stream=nb_read_frames,width,height','-of','json',str(folder/'video.mp4')],
                           check=True,capture_output=True,text=True)
        s=json.loads(out.stdout)['streams'][0]
        if int(s['nb_read_frames']) != streams['video']['count']:
            raise ValueError('Decoded video/CSV frame count mismatch')
        if s['width'] != m['width'] or s['height'] != m['height']:
            raise ValueError('Decoded video dimensions mismatch')
    return dict(streams=streams, stop_reason=m['stop_reason'], warnings=warnings,
                decoded_video_checked=decode, physical_synchronization_accuracy='not verified by this validator')


if __name__ == '__main__':
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('folder',type=Path);p.add_argument('--decode-video',action='store_true')
    args=p.parse_args()
    try: print(json.dumps(validate(args.folder,args.decode_video),indent=2))
    except (OSError,ValueError,KeyError,IndexError,subprocess.CalledProcessError) as e: p.exit(1,f'FAILED: {e}\n')

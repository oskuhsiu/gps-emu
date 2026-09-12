#!/usr/bin/env python3
"""Assert observable receiver behavior from JSON logs; no main-app private state."""
import argparse
import json
import math
from pathlib import Path

R=6371008.8
def distance(a,b):
    p,q=math.radians(a[0]),math.radians(b[0])
    dlat=q-p; dlon=math.radians(b[1]-a[1])
    h=math.sin(dlat/2)**2+math.cos(p)*math.cos(q)*math.sin(dlon/2)**2
    return 2*R*math.asin(min(1,math.sqrt(h)))

def route_distance(point, route):
    # Independent local tangent-plane projection; fixture covers <1 km in Taipei.
    lat0=math.radians(point[0]); scale=R*math.pi/180
    def xy(p): return ((p[1]-point[1])*scale*math.cos(lat0),(p[0]-point[0])*scale)
    closest=float('inf')
    for a,b in zip(route,route[1:]):
        x,y=xy(a); bx,by=xy(b);dx,dy=bx-x,by-y;length=dx*dx+dy*dy
        t=max(0,min(1,-(x*dx+y*dy)/length)) if length else 0
        closest=min(closest,math.hypot(x+t*dx,y+t*dy))
    return closest

def verify(path, speed_kmh=None, point=None, route=None, min_span=5):
    rows=[json.loads(s) for s in Path(path).read_text().splitlines() if s.startswith('{')]
    result={}
    for channel in ['GPS','NETWORK','FUSED']:
        samples=[x for x in rows if x.get('channel')==channel]
        assert len(samples)>=4,(channel,'too few samples',len(samples))
        # Requests may deliver a cached first fix. Assess fresh steady state after 2 s.
        start=samples[0]['elapsedRealtimeNanos']+2_000_000_000
        samples=[x for x in samples if x['elapsedRealtimeNanos']>=start]
        assert len(samples)>=3,(channel,'too few fresh samples')
        times=[x['elapsedRealtimeNanos'] for x in samples]
        assert all(b>a for a,b in zip(times,times[1:])),(channel,'non-monotonic timestamps')
        span=(times[-1]-times[0])/1e9
        assert span>=min_span,(channel,'short observation',span)
        assert all(x['isMock'] and x['accuracy']<=10 for x in samples),(channel,'invalid mock/accuracy')
        if speed_kmh is not None:
            assert all(abs(x['speedMps']-speed_kmh/3.6)<0.01 for x in samples),(channel,'wrong speed')
        positions=[(x['latitude'],x['longitude']) for x in samples]
        max_error=None
        if point:
            max_error=max(distance(x,point) for x in positions)
            assert max_error<=10,(channel,'wrong point',max_error)
        if speed_kmh==0:
            assert max(distance(x,positions[0]) for x in positions)<0.1,(channel,'hold drift')
        if route:
            max_error=max(route_distance(x,route) for x in positions)
            assert max_error<=10,(channel,'off route',max_error)
        traveled=sum(distance(a,b) for a,b in zip(positions,positions[1:]))
        if speed_kmh and speed_kmh>0:
            assert abs(traveled-span*speed_kmh/3.6)<=max(2,span*speed_kmh/3.6*.05),(channel,'distance/time mismatch',traveled,span)
        result[channel]={'samples':len(samples),'spanSeconds':round(span,3),'distanceMeters':round(traveled,3),'lastSpeedMps':samples[-1]['speedMps'],'maxPositionErrorMeters':max_error}
    return result

if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('log');parser.add_argument('--speed',type=float);parser.add_argument('--point',nargs=2,type=float);parser.add_argument('--route');parser.add_argument('--min-span',type=float,default=5)
    args=parser.parse_args();route=None
    if args.route:
        data=json.loads(Path(args.route).read_text());route=[(p[1],p[0]) for p in data['routes'][0]['geometry']['coordinates']]
    print(json.dumps(verify(args.log,args.speed,args.point,route,args.min_span),indent=2))

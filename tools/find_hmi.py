#!/usr/bin/env python3
# ENERGYBLUE HMI discovery tool - Python 3, no third-party dependencies.
import socket, json, ipaddress, argparse, time

def discover(timeout=3.0):
    found={}
    probe=b'ENERGYBLUE_HMI_DISCOVER_V1'
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as sock:
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
        sock.bind(('', 0))
        sock.settimeout(0.35)
        # Global broadcast works on most Wi-Fi LANs, but AP isolation can block it.
        sock.sendto(probe, ('255.255.255.255', 38541))
        deadline=time.monotonic()+timeout
        while time.monotonic()<deadline:
            try:
                data, address=sock.recvfrom(1024)
            except socket.timeout:
                continue
            try:
                payload=json.loads(data.decode('utf-8'))
                if payload.get('type')!='ENERGYBLUE_HMI_V1':
                    continue
                ip=str(ipaddress.ip_address(address[0]))
                found[ip]={'ip':ip,'model':payload.get('model',''),'version':payload.get('version','')}
            except (ValueError, UnicodeError, KeyError):
                continue
    return list(found.values())

if __name__=='__main__':
    parser=argparse.ArgumentParser(description='ENERGYBLUE IXP101H1 LAN IP auto discovery')
    parser.add_argument('--timeout',type=float,default=3.0,help='scan duration, seconds')
    args=parser.parse_args()
    print('HMI 검색 중... (노트북과 HMI는 같은 Wi-Fi에 연결)')
    try:
        results=discover(max(0.5,min(30.0,args.timeout)))
        for hmi in results:
            print('검색 성공: {ip} / {model} / V{version}'.format(**hmi))
        if not results:
            print('검색 결과 없음. HMI 앱 실행, 같은 네트워크, 방화벽 UDP 38541, AP 격리 설정을 확인하세요.')
    except OSError as e:
        print('네트워크 검색 실패:',e)

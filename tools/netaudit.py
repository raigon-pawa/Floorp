#!/usr/bin/env python3
# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at http://mozilla.org/MPL/2.0/.
"""List the hosts an emulator talked to, from a packet capture.

Usage: tools/netaudit.py <capture.pcap> [--after SECONDS] [--before SECONDS] [--json]

Capture inside the guest (the emulator images ship tcpdump and allow root), which sees every
interface including the virtual Wi-Fi:

    tools/emu.py capture-start
    ... exercise the app ...
    tools/emu.py capture-stop /tmp/run.pcap
    tools/netaudit.py /tmp/run.pcap

Reports, with the time each was first seen (seconds from the start of the capture):
  - DNS names looked up over plain DNS (UDP/TCP port 53),
  - TLS server names (SNI) from ClientHello messages, reassembled across TCP segments,
  - QUIC / other UDP 443 destinations, by address (their server name is encrypted).

With DNS over HTTPS on, lookups do not appear as plain DNS; the SNI list still names every TLS
host. Android itself is chatty (connectivity checks, time, Google services): compare against a
capture taken with the app stopped to tell the two apart.
"""

import argparse
import ipaddress
import json
import struct
import sys

MAX_HELLO = 16384  # bytes of client->server stream kept per connection while looking for a ClientHello


def packets(path):
    """Yields (timestamp, link-layer frame) from a classic pcap file."""
    with open(path, "rb") as capture:
        header = capture.read(24)
        if len(header) < 24:
            sys.exit(f"{path}: not a pcap file (too short)")
        magic = header[:4]
        if magic in (b"\xd4\xc3\xb2\xa1", b"\x4d\x3c\xb2\xa1"):
            endian = "<"
        elif magic in (b"\xa1\xb2\xc3\xd4", b"\xa1\xb2\x3c\x4d"):
            endian = ">"
        else:
            sys.exit(f"{path}: not a classic pcap file (pcapng is not supported)")
        nanos = magic in (b"\x4d\x3c\xb2\xa1", b"\xa1\xb2\x3c\x4d")
        linktype = struct.unpack(endian + "I", header[20:24])[0]
        if linktype not in LINK_HEADERS:
            sys.exit(f"{path}: unsupported link type {linktype}")
        while True:
            record = capture.read(16)
            if len(record) < 16:
                return
            seconds, fraction, captured, _original = struct.unpack(endian + "IIII", record)
            frame = capture.read(captured)
            if len(frame) < captured:
                return
            yield seconds + fraction / (1e9 if nanos else 1e6), linktype, frame


# Link type -> (offset of the 2-byte protocol field, header length).
# 1 = Ethernet, 113 = Linux cooked (tcpdump -i any), 276 = Linux cooked v2.
LINK_HEADERS = {1: (12, 14), 113: (14, 16), 276: (0, 20)}


def ip_payload(frame, linktype):
    """Returns (src, dst, protocol, payload) for IPv4/IPv6 frames, else None."""
    protocol_at, offset = LINK_HEADERS[linktype]
    if len(frame) < offset:
        return None
    ethertype = struct.unpack("!H", frame[protocol_at:protocol_at + 2])[0]
    if linktype == 1 and ethertype == 0x8100 and len(frame) >= 18:  # VLAN tag
        ethertype = struct.unpack("!H", frame[16:18])[0]
        offset = 18
    if ethertype == 0x0800 and len(frame) >= offset + 20:
        header_length = (frame[offset] & 0x0F) * 4
        total = struct.unpack("!H", frame[offset + 2:offset + 4])[0]
        fragment = struct.unpack("!H", frame[offset + 6:offset + 8])[0] & 0x1FFF
        if fragment:
            return None
        protocol = frame[offset + 9]
        src = str(ipaddress.IPv4Address(frame[offset + 12:offset + 16]))
        dst = str(ipaddress.IPv4Address(frame[offset + 16:offset + 20]))
        return src, dst, protocol, frame[offset + header_length:offset + total]
    if ethertype == 0x86DD and len(frame) >= offset + 40:
        length = struct.unpack("!H", frame[offset + 4:offset + 6])[0]
        protocol = frame[offset + 6]
        src = str(ipaddress.IPv6Address(frame[offset + 8:offset + 24]))
        dst = str(ipaddress.IPv6Address(frame[offset + 24:offset + 40]))
        return src, dst, protocol, frame[offset + 40:offset + 40 + length]
    return None


def dns_question(message):
    """Returns the first question name of a DNS query, or None."""
    if len(message) < 13 or message[2] & 0x80:  # too short, or a response
        return None
    labels, offset = [], 12
    while offset < len(message):
        length = message[offset]
        if length == 0:
            return ".".join(labels) or None
        if length & 0xC0:
            return None
        labels.append(message[offset + 1:offset + 1 + length].decode("ascii", "replace"))
        offset += 1 + length
    return None


def client_hello_sni(stream):
    """Returns (server name or '', done). done is False while more bytes are needed."""
    if len(stream) < 6:
        return "", False
    if stream[0] != 0x16 or stream[1] != 0x03:
        return "", True  # not TLS
    # A ClientHello may span several TLS records; join their payloads.
    handshake, offset = b"", 0
    while offset + 5 <= len(stream) and stream[offset] == 0x16:
        length = struct.unpack("!H", stream[offset + 3:offset + 5])[0]
        handshake += stream[offset + 5:offset + 5 + length]
        offset += 5 + length
        if len(handshake) >= 4:
            needed = 4 + int.from_bytes(handshake[1:4], "big")
            if len(handshake) >= needed:
                break
    if len(handshake) < 4:
        return "", False
    if handshake[0] != 0x01:
        return "", True  # not a ClientHello
    needed = 4 + int.from_bytes(handshake[1:4], "big")
    if len(handshake) < needed:
        return "", len(stream) >= MAX_HELLO
    try:
        position = 4 + 2 + 32
        position += 1 + handshake[position]  # session id
        position += 2 + struct.unpack("!H", handshake[position:position + 2])[0]  # cipher suites
        position += 1 + handshake[position]  # compression methods
        end = position + 2 + struct.unpack("!H", handshake[position:position + 2])[0]
        position += 2
        while position + 4 <= end:
            kind, length = struct.unpack("!HH", handshake[position:position + 4])
            body = handshake[position + 4:position + 4 + length]
            if kind == 0 and len(body) >= 5:
                name_length = struct.unpack("!H", body[3:5])[0]
                return body[5:5 + name_length].decode("ascii", "replace"), True
            position += 4 + length
    except (IndexError, struct.error):
        pass
    return "", True


def analyse(path, after, before):
    start = None
    dns, sni, udp443, other_tcp = {}, {}, {}, {}
    streams = {}  # (src, sport, dst, dport) -> [first sequence number, bytearray, done]

    def note(table, key, when):
        entry = table.setdefault(key, [when, 0])
        entry[1] += 1

    for timestamp, linktype, frame in packets(path):
        if start is None:
            start = timestamp
        when = timestamp - start
        if when < after or (before is not None and when > before):
            continue
        parsed = ip_payload(frame, linktype)
        if not parsed:
            continue
        src, dst, protocol, payload = parsed
        if protocol == 17 and len(payload) >= 8:
            sport, dport = struct.unpack("!HH", payload[:4])
            if dport == 53:
                name = dns_question(payload[8:])
                if name:
                    note(dns, name, when)
            elif dport == 443:
                note(udp443, dst, when)
        elif protocol == 6 and len(payload) >= 20:
            sport, dport, sequence = struct.unpack("!HHI", payload[:8])
            data = payload[(payload[12] >> 4) * 4:]
            flags = payload[13]
            key = (src, sport, dst, dport)
            if dport == 53 and len(data) > 2:
                name = dns_question(data[2:])
                if name:
                    note(dns, name, when)
                continue
            if flags & 0x02 and not flags & 0x10:  # SYN: a client opening a connection
                streams[key] = [sequence + 1, bytearray(), False, when]
                continue
            state = streams.get(key)
            if state is None or state[2] or not data:
                continue
            position = (sequence - state[0]) & 0xFFFFFFFF
            if position > MAX_HELLO:
                continue
            buffer = state[1]
            if len(buffer) < position + len(data):
                buffer.extend(b"\0" * (position + len(data) - len(buffer)))
            buffer[position:position + len(data)] = data
            name, done = client_hello_sni(bytes(buffer))
            if done or len(buffer) >= MAX_HELLO:
                state[2] = True
                if name:
                    note(sni, name, state[3])
                else:
                    note(other_tcp, f"{dst}:{dport}", state[3])
    return {"dns": dns, "tls_sni": sni, "udp_443": udp443, "other_tcp": other_tcp}


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("capture")
    parser.add_argument("--after", type=float, default=0, help="ignore packets before this many seconds")
    parser.add_argument("--before", type=float, default=None, help="ignore packets after this many seconds")
    parser.add_argument("--json", action="store_true", help="print JSON instead of a table")
    options = parser.parse_args()
    result = analyse(options.capture, options.after, options.before)
    if options.json:
        print(json.dumps({
            section: [{"name": name, "first_seen": round(first, 1), "count": count}
                      for name, (first, count) in sorted(table.items(), key=lambda item: item[1][0])]
            for section, table in result.items()
        }, indent=2))
        return
    titles = {
        "dns": "Plain DNS lookups",
        "tls_sni": "TLS server names (SNI)",
        "udp_443": "UDP 443 (QUIC) destinations",
        "other_tcp": "TCP connections without a TLS server name",
    }
    for section, table in result.items():
        print(f"{titles[section]}: {len(table)}")
        for name, (first, count) in sorted(table.items(), key=lambda item: item[1][0]):
            print(f"  {first:8.1f}s  x{count:<4} {name}")
        print()


if __name__ == "__main__":
    main()

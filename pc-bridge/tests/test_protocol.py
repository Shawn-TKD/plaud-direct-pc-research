import base64
import struct
import unittest

from plaud_pc.protocol import (
    CMD_PRE_RSA,
    ProtocolError,
    SessionCrypto,
    assemble_pre_handshake,
    build_handshake_frame,
    normalize_handshake_token,
    pre_handshake_chunks,
    signature_chunks,
    parse_file_data_body,
)


class ProtocolTests(unittest.TestCase):
    def test_parse_protocol20_file_data(self):
        body = b"\x02" + struct.pack("<IIB", 123, 7, 3) + b"abc"
        frame = parse_file_data_body(body, port_version=20)
        self.assertEqual(frame.session_id, 123)
        self.assertEqual(frame.offset, 7)
        self.assertEqual(frame.data, b"abc")

    def test_reject_truncated_file_data(self):
        body = b"\x02" + struct.pack("<IIB", 123, 7, 4) + b"abc"
        with self.assertRaises(ProtocolError):
            parse_file_data_body(body, port_version=20)

    def test_pre_handshake_chunks_round_trip(self):
        data = bytes(range(256))
        frames = pre_handshake_chunks(CMD_PRE_RSA, data)
        self.assertEqual(
            [frame[2:4] for frame in frames],
            [b"\x03\x00", b"\x03\x01", b"\x03\x02"],
        )
        self.assertEqual(assemble_pre_handshake(reversed(frames), CMD_PRE_RSA), data)

    def test_signature_is_base64_decoded_before_chunking(self):
        raw = b"signature" * 30
        frames = signature_chunks(base64.b64encode(raw).decode("ascii"))
        self.assertEqual(b"".join(frame[4:] for frame in frames), raw)

    def test_handshake_token_is_fixed_width(self):
        frame = build_handshake_frame("abc", port_version=20)
        self.assertEqual(len(frame), 38)
        self.assertEqual(frame[:6], b"\x01\x01\x00\x02\x01\x00")
        self.assertEqual(frame[6:], b"abc" + b"0" * 29)

    def test_handshake_token_matches_sdk_normalization(self):
        self.assertEqual(
            normalize_handshake_token("client_user_12345678-1234-1234-1234-123456789abc"),
            "12345678123412341234123456789abc",
        )

    def test_session_crypto_round_trip_and_replay_rejection(self):
        key = bytes(range(32))
        nonce = bytes(range(12))
        aad = bytes(range(12, 24))
        sender = SessionCrypto(key, nonce, aad)
        receiver = SessionCrypto(key, nonce, aad)
        packet = sender.encrypt_command(6, b"payload")
        sequence, command, payload = receiver.decrypt_command(packet)
        self.assertEqual((sequence, command, payload), (1, 6, b"payload"))
        with self.assertRaisesRegex(ProtocolError, "duplicate"):
            receiver.decrypt_command(packet)


if __name__ == "__main__":
    unittest.main()

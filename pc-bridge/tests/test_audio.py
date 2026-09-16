import struct
import unittest

from plaud_pc.audio import HEADER_SIZE, MAGIC, PlaudAudioHeader, _chacha20, _ogg_page


class AudioTests(unittest.TestCase):
    def test_rfc8439_chacha20_block(self):
        key = bytes(range(32))
        nonce = bytes.fromhex("000000090000004a00000000")
        expected = bytes.fromhex(
            "10f1e7e4d13b5915500fdd1fa32071c4"
            "c7d1f4c733c068030422aa9ac3d46c4e"
            "d2826446079faa0914c2d705d98b02a2"
            "b5129cd1de164eb9cbd083e8a2503c4e"
        )
        self.assertEqual(_chacha20(bytes(64), key, nonce, 1), expected)

    def test_ogg_page_crc(self):
        page = bytearray(
            _ogg_page(b"OpusHead", serial=1, sequence=0, granule=0, header_type=2)
        )
        stored = struct.unpack_from("<I", page, 22)[0]
        page[22:26] = b"\0\0\0\0"
        from plaud_pc.audio import _ogg_crc

        self.assertEqual(_ogg_crc(page), stored)

    def test_parse_header(self):
        data = bytearray(HEADER_SIZE)
        data[:8] = MAGIC
        struct.pack_into("<HHI", data, 8, 1, HEADER_SIZE, 123)
        data[16:20] = b"user"
        struct.pack_into("<HHH", data, 48, 5, 1, 1)
        struct.pack_into("<I", data, 54, 12)
        struct.pack_into("<I", data, 128, 0)
        data[132:144] = bytes(range(12))
        struct.pack_into("<I", data, 144, 80)
        header = PlaudAudioHeader.parse(bytes(data))
        self.assertEqual(header.file_type, 5)
        self.assertEqual(header.channels, 1)
        self.assertEqual(header.segment, 80)
        self.assertEqual(header.user_id, "user")

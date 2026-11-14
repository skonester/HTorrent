"""Exercise source verification and pinned fallback selection without network access."""
import hashlib
import io
import os
from pathlib import Path
import subprocess
import tarfile
import tempfile
import unittest


COLLECTOR = Path(__file__).resolve().parents[1] / "android-gpl-sources.sh"
BASH = os.environ.get("BASH_EXECUTABLE", "bash")


class SourceDownloadsTest(unittest.TestCase):
    def setUp(self):
        directory = tempfile.TemporaryDirectory(prefix="htorrent-source-test-")
        self.addCleanup(directory.cleanup)
        self.folder = Path(directory.name)
        self.fixture = self.folder / "fixture"
        self.destination = self.folder / "download.tar.gz"
        self.calls = self.folder / "calls"

    def run_shell(self, body, **variables):
        env = dict(os.environ, SOURCE_COLLECTOR=COLLECTOR.as_posix(),
                   SOURCE_FIXTURE=self.fixture.name, SOURCE_DESTINATION=self.destination.name,
                   SOURCE_CALLS=self.calls.name, **variables)
        return subprocess.run(
            [BASH, "--noprofile", "--norc", "-c", 'source "$SOURCE_COLLECTOR"\n' + body],
            env=env, cwd=self.folder, capture_output=True, text=True,
        )

    def download(self, data, checksum):
        self.fixture.write_bytes(data)
        return self.run_shell('''
curl() {
  printf '%s\\n' "$@" > "$SOURCE_CALLS"
  while [ "$#" -gt 0 ]; do
    if [ "$1" = -o ]; then cp "$SOURCE_FIXTURE" "$2"; return; fi
    shift
  done
  return 1
}
fetch https://example.invalid/source.tar.gz "$SOURCE_DESTINATION" "$RECIPE_HASH"
''', RECIPE_HASH=checksum)

    def archive(self):
        buffer = io.BytesIO()
        with tarfile.open(fileobj=buffer, mode="w:gz") as archive:
            entry = tarfile.TarInfo("source/README")
            entry.size = len(b"source code\n")
            archive.addfile(entry, io.BytesIO(b"source code\n"))
        return buffer.getvalue()

    def assert_rejected(self, result):
        self.assertNotEqual(result.returncode, 0, result.stdout)
        self.assertFalse(self.destination.exists())
        self.assertFalse(self.destination.with_suffix(".gz.part").exists())

    def test_recipe_checksum_accepts_exact_bytes(self):
        data = self.archive()
        result = self.download(data, hashlib.sha256(data).hexdigest())
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(self.destination.read_bytes(), data)
        self.assertIn("--http1.1", self.calls.read_text().splitlines())

    def test_readable_archive_with_wrong_checksum_is_rejected(self):
        self.assert_rejected(self.download(self.archive(), "0" * 64))

    def test_checksum_can_verify_a_plain_source_file(self):
        data = b"a source patch need not be an archive\n"
        result = self.download(data, hashlib.sha256(data).hexdigest())
        self.assertEqual(result.returncode, 0, result.stderr)

    def test_unpinned_html_response_is_rejected(self):
        self.assert_rejected(self.download(b"<html>challenge</html>", "SKIP_CHECKSUM"))

    def test_unpinned_archive_is_checked_for_valid_structure(self):
        result = self.download(self.archive(), "SKIP_CHECKSUM")
        self.assertEqual(result.returncode, 0, result.stderr)

    def test_partial_download_is_removed(self):
        result = self.run_shell('''
curl() {
  while [ "$#" -gt 0 ]; do
    if [ "$1" = -o ]; then printf partial > "$2"; return 22; fi
    shift
  done
  return 22
}
fetch https://example.invalid/source.tar.gz "$SOURCE_DESTINATION" ''
''')
        self.assert_rejected(result)

    def route(self, original, recipe_hash, alternate):
        return self.run_shell('''
fetch() {
  printf '%s\\t%s\\t%s\\n' "$1" "$2" "$3" >> "$SOURCE_CALLS"
  [ "$1" = "$ALTERNATE_URL" ] || return 1
  SOURCE_URL="$1"; SOURCE_FILE="$2"; SOURCE_SHA256="$3"
}
sleep() { :; }
get "$ORIGINAL_URL" "$SOURCE_DESTINATION" "$RECIPE_HASH"
printf '%s\\n%s\\n%s\\n' "$SOURCE_URL" "$SOURCE_FILE" "$SOURCE_SHA256"
''', ORIGINAL_URL=original, RECIPE_HASH=recipe_hash, ALTERNATE_URL=alternate)

    def test_lzo_replacement_has_its_own_checksum_and_gzip_filename(self):
        self.destination = self.folder / "lzo-2.10.tar.xz"
        result = self.route(
            "https://fossies.org/linux/misc/lzo-2.10.tar.xz",
            "37ed4369e45944c53306b0d6a36b66f03e5b6aede8849c9b6388f4b62b20b443",
            "https://www.oberhumer.com/opensource/lzo/download/lzo-2.10.tar.gz",
        )
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout.splitlines()[1:], [
            "lzo-2.10.tar.gz",
            "c0f892943208266f9b6543b3ae308fab6284c5c90e627931446fb49b4221a072",
        ])
        self.assertEqual(len(self.calls.read_text().splitlines()), 1)

    def test_foot_replacement_is_pinned_to_verified_commit(self):
        result = self.route(
            "https://codeberg.org/dnkl/foot/archive/1.24.0.tar.gz",
            "db2266f1a541662187ded46648a52feb0f8761b26ef7a8360bf3ac8315321bef",
            "https://codeberg.org/dnkl/foot/archive/fa0fd2f50f41d8fc47241dd576be42a2f29d6530.tar.gz",
        )
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout.splitlines()[-1],
                         "e86cf92895f16bbd3f02c6bae706717790f5a7a686bc37182d715a3defe73349")

    def test_v4l_mirror_fallback_has_its_own_checksum_and_filename(self):
        self.destination = self.folder / "v4l-utils-1.24.1.tar.bz2"
        result = self.route(
            "https://linuxtv.org/downloads/v4l-utils/v4l-utils-1.24.1.tar.bz2",
            "cbb7fe8a6307f5ce533a05cded70bb93c3ba06395ab9b6d007eb53b75d805f5b",
            "https://github.com/gjasny/v4l-utils/archive/refs/tags/v4l-utils-1.24.1.tar.gz",
        )
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout.splitlines()[1:], [
            "v4l-utils-1.24.1.tar.gz",
            "d4e43d47ef3c23b808ae4fdf6a1dd366e89e0cf8e5f5cd43ee4751d315444ffe",
        ])
        self.assertEqual(len(self.calls.read_text().splitlines()), 3)

    def test_replacement_is_not_used_for_an_unrecognized_recipe_checksum(self):
        result = self.route(
            "https://codeberg.org/dnkl/foot/archive/1.24.0.tar.gz", "0" * 64,
            "https://codeberg.org/dnkl/foot/archive/fa0fd2f50f41d8fc47241dd576be42a2f29d6530.tar.gz",
        )
        self.assertNotEqual(result.returncode, 0)
        self.assertNotIn("fa0fd2f", self.calls.read_text())


if __name__ == "__main__":
    unittest.main()

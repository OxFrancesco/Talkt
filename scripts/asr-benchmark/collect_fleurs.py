"""Stream a bounded, deterministic sample from the public FLEURS test archives."""
import csv
import hashlib
import io
import json
import pathlib
import subprocess
import sys
import tarfile
import wave

root = pathlib.Path(sys.argv[1])
count = int(sys.argv[2]) if len(sys.argv) > 2 else 20
revision = "70bb2e84b976b7e960aa89f1c648e09c59f894dd"
if not 1 <= count <= 100:
    raise ValueError("Use 1 to 100 clips per language")
(root / "audio").mkdir(parents=True, exist_ok=True)
manifest = []
for language in ["en_us", "it_it"]:
    base = f"https://huggingface.co/datasets/google/fleurs/resolve/{revision}/data/{language}"
    tsv = subprocess.check_output(["curl", "-fsSL", f"{base}/test.tsv"]).decode()
    rows = {row[1]: row for row in csv.reader(io.StringIO(tsv), delimiter="\t")}
    download = subprocess.Popen(["curl", "-fsSL", f"{base}/audio/test.tar.gz"], stdout=subprocess.PIPE)
    selected = 0
    seen_ids = set()
    try:
        with tarfile.open(fileobj=download.stdout, mode="r|gz") as archive:
            for item in archive:
                name = pathlib.PurePosixPath(item.name).name
                row = rows.get(name)
                if not item.isfile() or not row or row[0] in seen_ids:
                    continue
                data = archive.extractfile(item).read()
                output = root / "audio" / f"{language}-{name}"
                subprocess.run(["ffmpeg", "-v", "error", "-y", "-i", "pipe:0", "-ar", "16000", "-ac", "1", "-c:a", "pcm_s16le", str(output)], input=data, check=True)
                with wave.open(str(output)) as wav:
                    duration = wav.getnframes() / wav.getframerate()
                if not 3 <= duration <= 20:
                    output.unlink()
                    continue
                manifest.append({"file": output.name, "language": language[:2], "fleurs_id": row[0], "reference": row[2], "normalized_reference": row[3], "gender": row[6], "sha256": hashlib.sha256(output.read_bytes()).hexdigest(), "source": f"{base}/audio/test.tar.gz"})
                seen_ids.add(row[0])
                selected += 1
                print(language, selected, output.name, flush=True)
                if selected == count:
                    break
    finally:
        download.terminate()
        download.stdout.close()
        download.wait()
    if selected != count:
        raise RuntimeError(f"Only found {selected} clips for {language}")
with wave.open(str(root / "audio" / "silence.wav"), "wb") as wav:
    wav.setnchannels(1)
    wav.setsampwidth(2)
    wav.setframerate(16000)
    wav.writeframes(bytes(16000 * 2 * 5))
manifest.append({"file": "silence.wav", "language": "none", "reference": "", "source": "generated digital silence, 5 seconds"})
(root / "manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n")

#!/usr/bin/env python3
"""
PNG Firehose Converter v3
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
Key fix: submit() + as_completed() instead of map().
map() returns results IN ORDER — counter stays 0 until the whole first ordered
batch is ready. as_completed() fires the moment ANY file finishes.

Install: pip install opencv-python numpy pyvips pyvips-binary
"""

import os
import time
import threading
import tkinter as tk
from tkinter import filedialog, ttk
from concurrent.futures import ProcessPoolExecutor, as_completed
from multiprocessing import freeze_support


# ══════════════════════════════════════════════════════════════════════════════
# WORKER
# ══════════════════════════════════════════════════════════════════════════════
def _convert_worker(task):
    src, quality, fmt = task
    dst = src[:-4] + '.' + fmt.lower()
    try:
        src_bytes = os.path.getsize(src)
        encoded = False

        try:
            import pyvips
            img = pyvips.Image.new_from_file(src, access='sequential')
            if fmt == 'WEBP':
                img.webpsave(dst, Q=quality, lossless=False, strip=True, effort=4)
            else:
                img.jpegsave(dst, Q=quality, strip=True, optimize_coding=True)
            encoded = True
        except ImportError:
            pass
        except Exception:
            pass

        if not encoded:
            import cv2, numpy as np
            with open(src, 'rb') as fh:
                buf = np.frombuffer(fh.read(), np.uint8)
            img = cv2.imdecode(buf, cv2.IMREAD_UNCHANGED)
            if img is None:
                return False, 0
            if fmt == 'WEBP':
                ok = cv2.imwrite(dst, img, [cv2.IMWRITE_WEBP_QUALITY, quality])
            else:
                if img.ndim == 3 and img.shape[2] == 4:
                    img = cv2.cvtColor(img, cv2.COLOR_BGRA2BGR)
                ok = cv2.imwrite(dst, img, [cv2.IMWRITE_JPEG_QUALITY, quality,
                                             cv2.IMWRITE_JPEG_OPTIMIZE, 1])
            if not ok:
                return False, 0

        if os.path.exists(dst) and os.path.getsize(dst) > 0:
            os.remove(src)
            return True, src_bytes

    except Exception:
        pass
    return False, 0


# ══════════════════════════════════════════════════════════════════════════════
# APP
# ══════════════════════════════════════════════════════════════════════════════
class FirehoseApp:

    def __init__(self, root):
        self.root   = root
        self.root.title("PNG Firehose Converter")
        self.root.resizable(False, False)

        self._running  = False
        self._stop_evt = threading.Event()
        self._count    = 0
        self._freed    = 0
        self._total    = 0   # files found (for progress %)
        self._start_t  = 0.0

        self._build_ui()
        self._check_encoder()

    def _build_ui(self):
        ttk.Style(self.root).theme_use('clam')
        P = dict(padx=14, pady=5)

        frm = ttk.Frame(self.root)
        frm.grid(row=0, column=0, sticky='ew', **P)
        ttk.Label(frm, text="Source directory:").grid(row=0, column=0, sticky='w')
        self._dir_var = tk.StringVar()
        ttk.Entry(frm, textvariable=self._dir_var, width=46).grid(row=0, column=1, padx=6)
        ttk.Button(frm, text="Browse…", command=self._browse).grid(row=0, column=2)

        fmtf = ttk.LabelFrame(self.root, text="Output format")
        fmtf.grid(row=1, column=0, sticky='ew', **P)
        self._fmt_var = tk.StringVar(value='WEBP')
        ttk.Radiobutton(fmtf,
            text="WebP  — recommended (better quality, 25-35% smaller than JPEG at same %)",
            variable=self._fmt_var, value='WEBP').pack(anchor='w', padx=10, pady=2)
        ttk.Radiobutton(fmtf,
            text="JPEG  — fastest decode on legacy viewers",
            variable=self._fmt_var, value='JPEG').pack(anchor='w', padx=10, pady=2)

        qf = ttk.Frame(self.root)
        qf.grid(row=2, column=0, sticky='ew', **P)
        ttk.Label(qf, text="Quality:").pack(side='left')
        self._quality_var = tk.IntVar(value=85)
        ttk.Scale(qf, from_=60, to=100, variable=self._quality_var,
                  orient='horizontal', length=260,
                  command=lambda v: self._quality_var.set(int(float(v)))
                  ).pack(side='left', padx=6)
        ttk.Label(qf, textvariable=self._quality_var, width=3).pack(side='left')

        wf = ttk.Frame(self.root)
        wf.grid(row=3, column=0, sticky='ew', **P)
        max_cpu = os.cpu_count() or 4
        ttk.Label(wf, text="Workers:").pack(side='left')
        self._workers_var = tk.IntVar(value=max_cpu)
        ttk.Scale(wf, from_=1, to=max_cpu * 2, variable=self._workers_var,
                  orient='horizontal', length=260,
                  command=lambda v: self._workers_var.set(int(float(v)))
                  ).pack(side='left', padx=6)
        self._wlbl = ttk.Label(wf, width=3, text=str(max_cpu))
        self._wlbl.pack(side='left')
        self._workers_var.trace_add('write',
            lambda *_: self._wlbl.config(text=str(self._workers_var.get())))
        ttk.Label(wf, text=f"(cores: {max_cpu})", foreground='gray').pack(side='left', padx=6)

        self._bar = ttk.Progressbar(self.root, length=520, mode='determinate')
        self._bar.grid(row=4, column=0, **P, sticky='ew')

        self._stats_var = tk.StringVar(value="Ready.")
        ttk.Label(self.root, textvariable=self._stats_var,
                  font=('Courier', 10)).grid(row=5, column=0, pady=2)

        self._enc_var = tk.StringVar(value="Checking encoder…")
        ttk.Label(self.root, textvariable=self._enc_var,
                  foreground='gray', font=('Arial', 9)).grid(row=6, column=0)

        bf = ttk.Frame(self.root)
        bf.grid(row=7, column=0, pady=12)
        self._start_btn = ttk.Button(bf, text="START FIREHOSE", command=self._start)
        self._start_btn.pack(side='left', padx=8)
        self._stop_btn  = ttk.Button(bf, text="STOP", command=self._stop, state='disabled')
        self._stop_btn.pack(side='left', padx=8)

    def _browse(self):
        d = filedialog.askdirectory()
        if d:
            self._dir_var.set(d)

    def _check_encoder(self):
        def _probe():
            try:
                import pyvips
                self._enc_var.set(
                    f"Encoder: pyvips {pyvips.__version__}  (sequential streaming — fastest)")
            except ImportError:
                self._enc_var.set(
                    "Encoder: OpenCV  (pip install pyvips pyvips-binary for 3-5x speedup)")
        threading.Thread(target=_probe, daemon=True).start()

    def _scan_pngs(self, directory):
        """Filename-only scan — reads OS directory index, zero file opens."""
        try:
            with os.scandir(directory) as it:
                for entry in it:
                    if self._stop_evt.is_set():
                        return
                    try:
                        if entry.is_file(follow_symlinks=False):
                            if entry.name.lower().endswith('.png'):
                                yield entry.path
                        elif entry.is_dir(follow_symlinks=False):
                            yield from self._scan_pngs(entry.path)
                    except OSError:
                        pass
        except PermissionError:
            pass

    def _start(self):
        d = self._dir_var.get().strip(' \'"')
        if not d or not os.path.isdir(d):
            self._stats_var.set("Select a valid directory first.")
            return
        self._dir_var.set(d)
        self._running  = True
        self._stop_evt.clear()
        self._count    = 0
        self._freed    = 0
        self._total    = 0
        self._start_t  = time.perf_counter()
        self._start_btn.config(state='disabled')
        self._stop_btn.config(state='normal')
        self._bar.config(mode='indeterminate')
        self._bar.start(10)
        self._stats_var.set("Scanning…")
        threading.Thread(target=self._run, args=(d,), daemon=True).start()
        self._tick()

    def _stop(self):
        self._stop_evt.set()

    def _run(self, directory):
        quality = self._quality_var.get()
        fmt     = self._fmt_var.get()
        workers = max(1, self._workers_var.get())

        try:
            with ProcessPoolExecutor(max_workers=workers) as pool:

                # Submit everything immediately — scandir is pure index reads,
                # takes <1s even for 120k files.
                futures = {
                    pool.submit(_convert_worker, (path, quality, fmt)): path
                    for path in self._scan_pngs(directory)
                    if not self._stop_evt.is_set()
                }
                self._total = len(futures)

                # Switch progress bar to determinate now we know the total
                self.root.after(0, lambda: (
                    self._bar.stop(),
                    self._bar.config(mode='determinate', maximum=max(self._total, 1))
                ))

                # as_completed() fires the moment ANY future finishes —
                # no ordered waiting, counter increments instantly.
                for fut in as_completed(futures):
                    if self._stop_evt.is_set():
                        break
                    try:
                        success, freed = fut.result()
                        if success:
                            self._count += 1
                            self._freed += freed
                    except Exception:
                        pass

        except Exception as exc:
            print(f"[Firehose] {exc}")
        finally:
            self._running = False

    def _tick(self):
        if self._running:
            elapsed = max(time.perf_counter() - self._start_t, 1e-6)
            total   = max(self._total, 1)
            pct     = min(self._count / total * 100, 100)
            speed   = self._count / elapsed
            eta_s   = (total - self._count) / speed if speed > 0 else 0
            self._bar['value'] = self._count
            self._stats_var.set(
                f"{self._count:>7,} / {total:,}  ({pct:.0f}%)  |"
                f"  {speed:>6,.0f} img/s  |"
                f"  {self._freed / 1_073_741_824:.2f} GB freed  |"
                f"  ETA {eta_s:.0f}s"
            )
            self.root.after(120, self._tick)
        else:
            self._bar.stop()
            self._bar['value'] = self._total
            self._start_btn.config(state='normal')
            self._stop_btn.config(state='disabled')
            elapsed = max(time.perf_counter() - self._start_t, 1e-6)
            self._stats_var.set(
                f"Done: {self._count:,} converted  |  "
                f"{self._freed / 1_073_741_824:.2f} GB freed  |  "
                f"{elapsed:.1f}s total"
            )


if __name__ == '__main__':
    freeze_support()
    root = tk.Tk()
    FirehoseApp(root)
    root.mainloop()
# FastPNGTOWEBP Android

Native Android batch converter derived from the design goals of `FastPNGTOWEBP.PY`.

## Modes

- `Fast Folder Mode`: direct file-path scan and sibling writes for huge folders such as `DCIM` or WhatsApp media. This is the intended high-throughput path.
- `SAF Folder Mode`: `ACTION_OPEN_DOCUMENT_TREE` fallback for provider-backed trees and narrower user-granted access.
- `Single Image Mode`: pick one image and create one output file.

## To get the best results, use WebP at 95% quality to shrink your large PNG photo folders into high-quality, space-saving files.

## What changed

- Fast mode now uses direct `File` access instead of forcing the entire job through provider URIs.
- Fast mode now has a compiled native path on Android 11+ using the NDK image decoder and bitmap compressor.
- The queue window is explicitly `2N`, where `N` is worker count.
- Folder work is now submitted in batches instead of one image per executor task.
- Batch sizing now targets a `50+` image in-flight window without decoding 50 full images into RAM at once.
- The UI now shows a live throughput graph and batch metadata.
- The UI exposes a manual folder path so the user is not forced through the default tree picker for huge jobs.
- Progress is indeterminate while scanning, then determinate after scan completion.

## Important limits

- Android apps cannot reliably force a specific third-party file manager such as ZArchiver for `ACTION_OPEN_DOCUMENT_TREE`. The system picker presents installed document providers.
- Fast mode on Android 11+ requires All Files Access. This is a sideload/local-tool choice, not a Play-friendly design.
- Throughput still depends on decode and encode cost. The fast path removes a major I/O and object-allocation bottleneck, but it does not make Android equal to a desktop NVMe workstation.
- Android's standard bitmap codecs still decode and encode one image at a time. The new batching is around scan, queue, worker execution, and progress reporting, while the new native path reduces Java-side overhead for those repeated operations.

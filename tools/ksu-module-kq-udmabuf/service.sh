#!/system/bin/sh
# Open /dev/udmabuf for untrusted_app (kqprobe GPU broker).
# Verified on dash 2026-08-31:
#  - base sepolicy already allows: untrusted_app gpu_device:chr_file
#    { append getattr ioctl lock map open read watch watch_reads write }
#    => relabel to gpu_device, no custom sepolicy.rule needed.
#  - DAC: 666 mirrors /dev/mali0 (system:graphics 666 on this device).
# udmabuf_create() additionally requires the caller's memfd to carry
# F_SEAL_SHRINK (handled by the -DHAVE_MEMFD_CREATE backend build).
MODDIR=${0%/*}
log_with() { echo "kq-udmabuf: $1" > /dev/kmsg; }
[ -c /dev/udmabuf ] || { log_with "/dev/udmabuf absent"; exit 0; }
chmod 666 /dev/udmabuf
chown system graphics /dev/udmabuf
chcon u:object_r:gpu_device:s0 /dev/udmabuf
log_with "/dev/udmabuf ready ($(stat -c '%a %U:%G %C' /dev/udmabuf))"
